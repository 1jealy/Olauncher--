package app.olaunchercf.helper

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import app.olaunchercf.R
import app.olaunchercf.data.Constants
import app.olaunchercf.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import java.net.URL
import java.net.URLEncoder
import java.util.*
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Current weather from Open-Meteo (free, no API key).
 * The place is either a city the user typed in, or the coarse device location.
 */
object Weather {

    private const val LOCATION_MAX_AGE_MS = 3 * 60 * 60 * 1000L
    private const val LOCATION_TIMEOUT_MS = 20_000L

    sealed class Result {
        class Success(val text: String) : Result()
        class Failure(@StringRes val message: Int) : Result()
    }

    fun nowMinutes(): Int = (System.currentTimeMillis() / 60_000L).toInt()

    fun isStale(prefs: Prefs): Boolean =
        prefs.weatherText.isEmpty() ||
                nowMinutes() - prefs.weatherUpdatedMinutes >= Constants.WEATHER_REFRESH_MINUTES

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    /** Finds the place and loads its weather. */
    suspend fun load(context: Context, prefs: Prefs): Result {
        val city = prefs.weatherCity.trim()
        val coordinates: Pair<Double, Double> = if (city.isNotEmpty()) {
            geocode(context, city) ?: return Result.Failure(R.string.weather_city_not_found)
        } else {
            if (!hasLocationPermission(context)) return Result.Failure(R.string.weather_no_permission)
            val location = currentLocation(context)
            if (location != null) {
                prefs.weatherLastLocation = "${location.latitude},${location.longitude}"
                Pair(location.latitude, location.longitude)
            } else {
                // fall back to the last place that worked
                parseCoordinates(prefs.weatherLastLocation) ?: return Result.Failure(
                    if (isLocationEnabled(context)) R.string.weather_no_location
                    else R.string.weather_location_off
                )
            }
        }
        return fetch(context, coordinates.first, coordinates.second)
    }

    private fun parseCoordinates(value: String): Pair<Double, Double>? {
        val parts = value.split(",")
        if (parts.size != 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        return Pair(lat, lon)
    }

    private fun isLocationEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    // ---------- device location ----------

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(context: Context): Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = try { manager.getProviders(true) } catch (_: Exception) { emptyList() }

        val last = providers
            .mapNotNull { try { manager.getLastKnownLocation(it) } catch (_: Exception) { null } }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < LOCATION_MAX_AGE_MS) return last

        val provider = listOf(
            "fused", // LocationManager.FUSED_PROVIDER, Android 12+
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
        ).firstOrNull { it in providers } ?: return last

        val fresh = withContext(Dispatchers.Main) { requestSingleLocation(context, manager, provider) }
        return fresh ?: last
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingleLocation(context: Context, manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            var done = false
            fun finish(location: Location?) {
                if (done) return
                done = true
                if (continuation.isActive) continuation.resume(location)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val cancel = CancellationSignal()
                continuation.invokeOnCancellation { cancel.cancel() }
                try {
                    manager.getCurrentLocation(provider, cancel, ContextCompat.getMainExecutor(context)) { finish(it) }
                } catch (_: Exception) {
                    finish(null)
                }
                handler.postDelayed({ cancel.cancel(); finish(null) }, LOCATION_TIMEOUT_MS)
            } else {
                // all methods are implemented: older Android versions have no default implementations
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        manager.removeUpdates(this)
                        finish(location)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                try {
                    manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                } catch (_: Exception) {
                    finish(null)
                    return@suspendCancellableCoroutine
                }
                handler.postDelayed({ manager.removeUpdates(listener); finish(null) }, LOCATION_TIMEOUT_MS)
            }
        }

    // ---------- city name ----------

    private suspend fun geocode(context: Context, city: String): Pair<Double, Double>? =
        withContext(Dispatchers.IO) {
            // the system geocoder understands Korean place names well
            try {
                if (Geocoder.isPresent()) {
                    @Suppress("DEPRECATION")
                    val address = Geocoder(context, Locale.getDefault()).getFromLocationName(city, 1)?.firstOrNull()
                    if (address != null) return@withContext Pair(address.latitude, address.longitude)
                }
            } catch (_: Exception) { }

            // fallback: Open-Meteo geocoding, preferring the biggest town with that name
            try {
                val url = URL(
                    "https://geocoding-api.open-meteo.com/v1/search?count=10&name=" +
                            URLEncoder.encode(city, "UTF-8")
                )
                val results = JSONObject(httpGet(url)).optJSONArray("results") ?: return@withContext null
                var best: JSONObject? = null
                for (i in 0 until results.length()) {
                    val place = results.getJSONObject(i)
                    if (!place.optString("feature_code").startsWith("PPL")) continue
                    if (best == null || place.optLong("population") > best.optLong("population")) best = place
                }
                best = best ?: results.optJSONObject(0) ?: return@withContext null
                Pair(best.getDouble("latitude"), best.getDouble("longitude"))
            } catch (_: Exception) {
                null
            }
        }

    // ---------- weather ----------

    private class WeatherNow(val temperature: Double, @StringRes val description: Int)

    // tried in order, so one blocked server does not break the weather
    private val providers: List<(Double, Double) -> WeatherNow> = listOf(
        ::fromOpenMeteo,
        ::fromMetNorway,
        ::fromWttr,
    )

    /** Returns e.g. "Clear 12°", or a failure that says why no server could be reached. */
    private suspend fun fetch(context: Context, latitude: Double, longitude: Double): Result =
        withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            for (provider in providers) {
                try {
                    val now = provider(latitude, longitude)
                    return@withContext Result.Success(
                        "${context.getString(now.description)} ${now.temperature.roundToInt()}°"
                    )
                } catch (e: Exception) {
                    lastError = e
                }
            }
            Result.Failure(errorMessage(lastError))
        }

    @StringRes
    private fun errorMessage(error: Exception?): Int = when (error) {
        is UnknownHostException -> R.string.weather_error_dns
        is SSLException, is CertificateException -> R.string.weather_error_ssl
        is SocketTimeoutException -> R.string.weather_error_timeout
        else -> R.string.weather_network_error
    }

    // coordinates are rounded to two decimals (~1 km): plenty for weather, keeps the location coarse
    private fun coords(format: String, latitude: Double, longitude: Double): String =
        String.format(Locale.US, format, latitude, longitude)

    private fun fromOpenMeteo(latitude: Double, longitude: Double): WeatherNow {
        val url = coords(
            "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m,weather_code",
            latitude, longitude
        )
        val current = JSONObject(httpGet(url)).getJSONObject("current")
        return WeatherNow(current.getDouble("temperature_2m"), describeWmo(current.getInt("weather_code")))
    }

    private fun fromMetNorway(latitude: Double, longitude: Double): WeatherNow {
        val url = coords(
            "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.2f&lon=%.2f",
            latitude, longitude
        )
        val data = JSONObject(httpGet(url)).getJSONObject("properties")
            .getJSONArray("timeseries").getJSONObject(0).getJSONObject("data")
        val temperature = data.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature")
        val symbol = data.optJSONObject("next_1_hours")?.optJSONObject("summary")?.optString("symbol_code")
            ?: data.optJSONObject("next_6_hours")?.optJSONObject("summary")?.optString("symbol_code")
            ?: ""
        return WeatherNow(temperature, describeMetSymbol(symbol))
    }

    private fun fromWttr(latitude: Double, longitude: Double): WeatherNow {
        val url = coords("https://wttr.in/%.2f,%.2f?format=j1", latitude, longitude)
        val current = JSONObject(httpGet(url)).getJSONArray("current_condition").getJSONObject(0)
        return WeatherNow(current.getString("temp_C").toDouble(), describeWwo(current.getString("weatherCode").toInt()))
    }

    private fun httpGet(address: String): String = httpGet(URL(address))

    private fun httpGet(url: URL): String {
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            // MET Norway requires an identifying User-Agent
            connection.setRequestProperty("User-Agent", "OlauncherCF-weather/1.0 (+https://github.com/1jealy/Olauncher--)")
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    // MET Norway symbol codes, e.g. "lightrainshowers_day"
    @StringRes
    private fun describeMetSymbol(symbol: String): Int {
        val name = symbol.substringBefore('_')
        return when {
            "thunder" in name -> R.string.weather_thunderstorm
            "snow" in name || "sleet" in name -> R.string.weather_snow
            "rain" in name -> R.string.weather_rain
            name == "fog" -> R.string.weather_fog
            name == "clearsky" -> R.string.weather_clear
            name == "fair" || name == "partlycloudy" -> R.string.weather_partly_cloudy
            else -> R.string.weather_cloudy
        }
    }

    // World Weather Online codes used by wttr.in
    @StringRes
    private fun describeWwo(code: Int): Int = when (code) {
        113 -> R.string.weather_clear
        116 -> R.string.weather_partly_cloudy
        143, 248, 260 -> R.string.weather_fog
        263, 266, 281, 284 -> R.string.weather_drizzle
        200, 386, 389, 392, 395 -> R.string.weather_thunderstorm
        176, 293, 296, 299, 302, 305, 308, 311, 314, 353, 356, 359 -> R.string.weather_rain
        179, 182, 185, 227, 230, 317, 320, 323, 326, 329, 332, 335, 338,
        350, 362, 365, 368, 371, 374, 377 -> R.string.weather_snow
        else -> R.string.weather_cloudy
    }

    // WMO weather interpretation codes
    @StringRes
    private fun describeWmo(code: Int): Int = when (code) {
        0 -> R.string.weather_clear
        1, 2 -> R.string.weather_partly_cloudy
        3 -> R.string.weather_cloudy
        45, 48 -> R.string.weather_fog
        in 51..57 -> R.string.weather_drizzle
        in 61..67, in 80..82 -> R.string.weather_rain
        in 71..77, 85, 86 -> R.string.weather_snow
        in 95..99 -> R.string.weather_thunderstorm
        else -> R.string.weather_cloudy
    }
}
