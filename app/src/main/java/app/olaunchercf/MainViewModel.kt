package app.olaunchercf

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import app.olaunchercf.data.AppModel
import app.olaunchercf.data.Constants
import app.olaunchercf.data.Constants.AppDrawerFlag
import app.olaunchercf.data.Prefs
import app.olaunchercf.helper.*
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext by lazy { application.applicationContext }
    private val prefs = Prefs(appContext)

    // setup variables with initial values
    val showMessageDialog = MutableLiveData<String>()

    val appList = MutableLiveData<List<AppModel>?>()
    val hiddenApps = MutableLiveData<List<AppModel>?>()
    val isOlauncherDefault = MutableLiveData<Boolean>()
    val launcherResetFailed = MutableLiveData<Boolean>()

    val showTime = MutableLiveData(prefs.showTime)
    val showDate = MutableLiveData(prefs.showDate)
    val clockAlignment = MutableLiveData(prefs.clockAlignment)
    val homeAppsAlignment = MutableLiveData(Pair(prefs.homeAlignment, prefs.homeAlignmentBottom))
    val homeAppsCount = MutableLiveData(prefs.homeAppsNum)
    val showWeather = MutableLiveData(prefs.showWeather)

    // app that should be opened after the launch delay countdown
    val delayedLaunch = MutableLiveData<AppModel?>()

    fun selectedApp(appModel: AppModel, flag: AppDrawerFlag, n: Int = 0) {
        when (flag) {
            AppDrawerFlag.LaunchApp, AppDrawerFlag.HiddenApps -> {
                requestLaunch(appModel)
            }
            AppDrawerFlag.AllowedApps -> prefs.allowedApps = prefs.allowedApps.toggle(appModel.appPackage)
            AppDrawerFlag.DelayedApps -> prefs.delayedApps = prefs.delayedApps.toggle(appModel.appPackage)
            AppDrawerFlag.SetHomeApp -> {
                prefs.setHomeAppModel(n, appModel)
            }
            AppDrawerFlag.SetSwipeLeft -> prefs.appSwipeLeft = appModel
            AppDrawerFlag.SetSwipeRight -> prefs.appSwipeRight = appModel
            AppDrawerFlag.SetSwipeUp -> prefs.appSwipeUp = appModel
            AppDrawerFlag.SetSwipeDown -> prefs.appSwipeDown = appModel
            AppDrawerFlag.SetClickClock -> prefs.appClickClock = appModel
            AppDrawerFlag.SetClickDate -> prefs.appClickDate = appModel
            AppDrawerFlag.SetDoubleTap -> prefs.appDoubleTap = appModel
        }
    }

    private fun Set<String>.toggle(item: String): Set<String> =
        if (contains(item)) this - item else this + item

    // checks the allow list and the launch delay before opening an app
    private fun requestLaunch(appModel: AppModel) {
        if (!prefs.isAppAllowed(appModel.appPackage)) {
            showToastShort(appContext, appContext.getString(R.string.app_blocked))
            return
        }
        if (prefs.delayedApps.contains(appModel.appPackage)) {
            delayedLaunch.value = appModel
            return
        }
        launchApp(appModel)
    }

    // called when the launch delay countdown is over
    fun launchDelayedApp(appModel: AppModel) {
        delayedLaunch.value = null
        launchApp(appModel)
    }

    fun cancelDelayedLaunch() {
        delayedLaunch.value = null
    }

    fun setShowWeather(visibility: Boolean) {
        showWeather.value = visibility
    }

    fun setShowDate(visibility: Boolean) {
        showDate.value = visibility
    }

    fun setShowTime(visibility: Boolean) {
        showTime.value = visibility
    }

    private fun launchApp(appModel: AppModel) {
        val packageName = appModel.appPackage
        val appActivityName = appModel.appActivityName
        val userHandle = appModel.user
        val launcher = appContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val activityInfo = launcher.getActivityList(packageName, userHandle)

        // TODO: Handle multiple launch activities in an app. This is NOT the way.
        val component = when (activityInfo.size) {
            0 -> {
                showToastShort(appContext, "App not found")
                return
            }
            1 -> ComponentName(packageName, activityInfo[0].name)
            else -> if (appActivityName.isNotEmpty()) {
                ComponentName(packageName, appActivityName)
            } else {
                ComponentName(packageName, activityInfo[activityInfo.size - 1].name)
            }
        }

        try {
            launcher.startMainActivity(component, userHandle, null, null)
        } catch (e: SecurityException) {
            try {
                launcher.startMainActivity(component, android.os.Process.myUserHandle(), null, null)
            } catch (e: Exception) {
                showToastShort(appContext, "Unable to launch app")
            }
        } catch (e: Exception) {
            showToastShort(appContext, "Unable to launch app")
        }
    }

    fun getAppList(showHiddenApps: Boolean = false) {
        viewModelScope.launch {
            appList.value = getAppsList(appContext, showHiddenApps)
        }
    }

    fun getHiddenApps() {
        viewModelScope.launch {
            hiddenApps.value = getHiddenAppsList(appContext)
        }
    }

    fun isOlauncherDefault() {
        isOlauncherDefault.value = isOlauncherDefault(appContext)
    }

    fun resetDefaultLauncherApp(context: Context) {
        resetDefaultLauncher(context)
        launcherResetFailed.value = getDefaultLauncherPackage(
            appContext
        ).contains(".")
    }

    fun updateDrawerAlignment(gravity: Constants.Gravity) {
        prefs.drawerAlignment = gravity
    }

    fun updateClockAlignment(gravity: Constants.Gravity) {
        clockAlignment.value = gravity
    }

    fun updateHomeAppsAlignment(gravity: Constants.Gravity, onBottom: Boolean) {
        homeAppsAlignment.value = Pair(gravity, onBottom)
    }

    fun showMessageDialog(message: String) {
        showMessageDialog.postValue(message)
    }
}
