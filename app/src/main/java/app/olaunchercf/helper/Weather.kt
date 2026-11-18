package app.olaunchercf.helper

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import app.olaunchercf.R
import app.olaunchercf.data.Constants
import app.olaunchercf.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import kotlin.math.roundToInt

/** Current weather from Open-Meteo (free, no API key), based on the coarse device location. */
object Weather {

    private const val LOCATION_MAX_AGE_MS = 3 * 60 * 60 * 1000L
    private const val LOCATION_TIMEOUT_MS = 15_000L

    fun nowMinutes(): Int = (System.currentTimeMillis() / 60_000L).toInt()

    fun isStale(prefs: Prefs): Boolean =
        prefs.weatherText.isEmpty() ||
                nowMinutes() - prefs.weatherUpdatedMinutes >= Constants.WEATHER_REFRESH_MINUTES

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun requestLocation(context: Context, callback: (Location?) -> Unit) {
        if (!hasLocationPermission(context)) {
            callback(null)
            return
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = manager.getProviders(true)

        val last = providers
            .mapNotNull { try { manager.getLastKnownLocation(it) } catch (_: Exception) { null } }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < LOCATION_MAX_AGE_MS) {
            callback(last)
            return
        }

        val provider = when {
            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
            LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            else -> null
        }
        if (provider == null) {
            callback(last)
            return
        }

        var done = false
        val handler = Handler(Looper.getMainLooper())
        // all methods are implemented: older Android versions have no default implementations
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (done) return
                done = true
                manager.removeUpdates(this)
                callback(location)
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        } catch (_: Exception) {
            callback(last)
            return
        }
        handler.postDelayed({
            if (!done) {
                done = true
                manager.removeUpdates(listener)
                callback(last)
            }
        }, LOCATION_TIMEOUT_MS)
    }

    /** Returns e.g. "Clear 12°", or null if the request failed. */
    suspend fun fetch(context: Context, latitude: Double, longitude: Double): String? =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                // two decimals (~1 km) is plenty for weather and keeps the location coarse
                val url = URL(
                    String.format(
                        Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m,weather_code",
                        latitude, longitude
                    )
                )
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val current = JSONObject(body).getJSONObject("current")
                val temperature = current.getDouble("temperature_2m").roundToInt()
                val code = current.getInt("weather_code")
                "${context.getString(describe(code))} $temperature°"
            } catch (_: Exception) {
                null
            } finally {
                connection?.disconnect()
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
