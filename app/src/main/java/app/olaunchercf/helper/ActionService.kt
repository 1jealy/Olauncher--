package app.olaunchercf.helper

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.olaunchercf.R
import app.olaunchercf.data.Prefs
import java.lang.ref.WeakReference

/**
 * Accessibility service used for
 *  - locking the screen / showing recents (gestures)
 *  - blocking every app that is not on the allow list (allowlist mode)
 *  - sending a notification after a set time of continuous phone use
 */
class ActionService : AccessibilityService() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private var usageStart = 0L
    private var lastBlockToast = 0L
    private var receiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> stopUsageTimer()
                Intent.ACTION_USER_PRESENT -> startUsageTimer()
                Intent.ACTION_SCREEN_ON -> if (!isKeyguardLocked()) startUsageTimer()
            }
        }
    }

    private val usageRunnable = object : Runnable {
        override fun run() {
            val minutes = prefs.usageAlertMinutes
            if (minutes <= 0) return
            val usedMinutes = ((SystemClock.elapsedRealtime() - usageStart) / 60_000L).toInt()
            sendUsageNotification(usedMinutes)
            handler.postDelayed(this, minutes * 60_000L)
        }
    }

    override fun onServiceConnected() {
        instance = WeakReference(this)
        prefs = Prefs(this)

        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(screenReceiver, filter)
            receiverRegistered = true
        }
        restartUsageTimer()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = WeakReference(null)
        stopUsageTimer()
        if (receiverRegistered) {
            try { unregisterReceiver(screenReceiver) } catch (_: Exception) { }
            receiverRegistered = false
        }
        return super.onUnbind(intent)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun lockScreen(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun showRecents(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    // ---------- allowlist mode ----------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!::prefs.isInitialized || !prefs.allowlistMode) return

        val pkg = event.packageName?.toString() ?: return
        if (event.className?.toString()?.startsWith("android.widget.Toast") == true) return
        if (prefs.allowedApps.contains(pkg) || isExempt(pkg)) return

        // the app is not allowed: go back to the launcher
        performGlobalAction(GLOBAL_ACTION_HOME)
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlockToast > 2000) {
            lastBlockToast = now
            showToastShort(this, getString(R.string.app_blocked))
        }
    }

    // apps that must never be blocked, so the phone stays usable
    private fun isExempt(pkg: String): Boolean {
        if (pkg == packageName || pkg in ALWAYS_ALLOWED) return true

        // keyboard
        val ime = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        if (ime != null && ime.substringBefore('/') == pkg) return true

        // phone calls
        try {
            val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (telecom.defaultDialerPackage == pkg) return true
        } catch (_: Exception) { }

        // system components without a launcher icon (permission dialogs, share sheets, ...)
        return packageManager.getLaunchIntentForPackage(pkg) == null
    }

    // ---------- usage alert ----------

    fun restartUsageTimer() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (power.isInteractive && !isKeyguardLocked()) startUsageTimer() else stopUsageTimer()
    }

    private fun startUsageTimer() {
        handler.removeCallbacks(usageRunnable)
        usageStart = SystemClock.elapsedRealtime()
        val minutes = prefs.usageAlertMinutes
        if (minutes > 0) handler.postDelayed(usageRunnable, minutes * 60_000L)
    }

    private fun stopUsageTimer() {
        handler.removeCallbacks(usageRunnable)
    }

    private fun isKeyguardLocked(): Boolean {
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return keyguard.isKeyguardLocked
    }

    private fun sendUsageNotification(usedMinutes: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                USAGE_CHANNEL_ID,
                getString(R.string.usage_alert_channel),
                NotificationManager.IMPORTANCE_HIGH
            )
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, USAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_timer)
            .setContentTitle(getString(R.string.usage_alert_title))
            .setContentText(getString(R.string.usage_alert_message, usedMinutes))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(USAGE_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // notification permission not granted
        }
    }

    override fun onInterrupt() { }

    companion object {
        private const val USAGE_CHANNEL_ID = "usage_alert"
        private const val USAGE_NOTIFICATION_ID = 1001

        private val ALWAYS_ALLOWED = setOf(
            "android",
            "com.android.systemui",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.emergency",
        )

        private var instance: WeakReference<ActionService> = WeakReference(null)

        fun instance(): ActionService? {
            return instance.get()
        }
    }
}
