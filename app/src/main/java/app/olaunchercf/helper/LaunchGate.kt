package app.olaunchercf.helper

import androidx.lifecycle.MutableLiveData

/**
 * Shared state between the launcher UI and [ActionService] for apps that are not allowed:
 * such apps only open after the launch countdown.
 */
object LaunchGate {
    // app that finished its countdown; it may stay open until the user leaves it
    @Volatile
    var passedPackage: String? = null

    // app opened outside the launcher that still needs its countdown
    val countdownRequest = MutableLiveData<String?>()
}
