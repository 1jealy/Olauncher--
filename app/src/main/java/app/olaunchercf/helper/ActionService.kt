package app.olaunchercf.helper

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
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
import java.util.concurrent.Executors

/**
 * Accessibility service used for
 *  - locking the screen / showing recents (gestures)
 *  - making apps that are not on the allow list wait for the launch countdown (allowlist mode)
 *  - sending a notification after a set time of continuous phone use
 *  - stopping the background processes of apps that are not allowed
 */
class ActionService : AccessibilityService() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private var usageStart = 0L
    private var lastGatedPackage: String? = null
    private var lastGatedTime = 0L
    private var receiverRegistered = false
    private val sleepExecutor = Executors.newSingleThreadExecutor()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    stopUsageTimer()
                    sleepNotAllowedApps()
                }
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

        // the app that finished its countdown stays open until the user switches to another app
        if (pkg == LaunchGate.passedPackage) return
        if (pkg in NEUTRAL_PACKAGES || isKeyboard(pkg)) return
        LaunchGate.passedPackage = null

        if (prefs.allowedApps.contains(pkg) || isExempt(pkg)) return

        // the app is not allowed: go back to the launcher, which shows the countdown
        val now = SystemClock.elapsedRealtime()
        if (pkg == lastGatedPackage && now - lastGatedTime < 1500) return
        lastGatedPackage = pkg
        lastGatedTime = now

        performGlobalAction(GLOBAL_ACTION_HOME)
        LaunchGate.countdownRequest.postValue(pkg)
    }

    // windows that show up on top of apps (notification shade, keyboard, system dialogs)
    private fun isKeyboard(pkg: String): Boolean {
        val ime = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return ime != null && ime.substringBefore('/') == pkg
    }

    // apps that must never be blocked, so the phone stays usable
    private fun isExempt(pkg: String): Boolean {
        if (pkg == packageName || pkg in ALWAYS_ALLOWED || isKeyboard(pkg)) return true

        // phone calls
        try {
            val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (telecom.defaultDialerPackage == pkg) return true
        } catch (_: Exception) { }

        // system components without a launcher icon (permission dialogs, share sheets, ...)
        return packageManager.getLaunchIntentForPackage(pkg) == null
    }

    // ---------- sleep apps that are not allowed ----------

    // Regular apps cannot force-stop other apps, but they may stop their background processes.
    // Apps the user is looking at and running foreground services (music, navigation) are not affected.
    private fun sleepNotAllowedApps() {
        if (!prefs.sleepOtherApps) return
        val allowed = prefs.allowedApps
        if (allowed.isEmpty()) return

        sleepExecutor.execute {
            try {
                val launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
                launcherApps.getActivityList(null, Process.myUserHandle())
                    .map { it.applicationInfo.packageName }
                    .toSet()
                    .filter { !allowed.contains(it) && !isExempt(it) }
                    .forEach { sleepApp(it) }
            } catch (_: Exception) { }
        }
    }

    private fun sleepApp(pkg: String) {
        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager.killBackgroundProcesses(pkg)
        } catch (_: Exception) { }
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

        private val NEUTRAL_PACKAGES = setOf("android", "com.android.systemui")

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
