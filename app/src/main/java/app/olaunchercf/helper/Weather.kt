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
import java.net.HttpURLConnection
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
        val text = fetch(context, coordinates.first, coordinates.second)
            ?: return Result.Failure(R.string.weather_network_error)
        return Result.Success(text)
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

    /** Returns e.g. "Clear 12°", or null if the request failed. */
    private suspend fun fetch(context: Context, latitude: Double, longitude: Double): String? =
        withContext(Dispatchers.IO) {
            try {
                // two decimals (~1 km) is plenty for weather and keeps the location coarse
                val url = URL(
                    String.format(
                        Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m,weather_code",
                        latitude, longitude
                    )
                )
                val current = JSONObject(httpGet(url)).getJSONObject("current")
                val temperature = current.getDouble("temperature_2m").roundToInt()
                val code = current.getInt("weather_code")
                "${context.getString(describe(code))} $temperature°"
            } catch (_: Exception) {
                null
            }
        }

    private fun httpGet(url: URL): String {
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    // WMO weather interpretation codes
    @StringRes
    private fun describe(code: Int): Int = when (code) {
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
