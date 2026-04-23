/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.lockscreen

import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.WallpaperManager
import android.app.smartspace.SmartspaceConfig
import android.app.smartspace.SmartspaceManager
import android.app.smartspace.SmartspaceSession
import android.app.smartspace.SmartspaceTarget
import android.app.smartspace.SmartspaceTargetEvent
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS
import android.provider.Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS
import android.provider.Settings.Secure.LOCK_SCREEN_WEATHER_ENABLED
import android.provider.Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.View
import androidx.annotation.VisibleForTesting
import com.android.internal.colorextraction.ColorExtractor
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.settingslib.Utils
import com.android.systemui.Dumpable
import com.android.systemui.colorextraction.SysuiColorExtractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.BcSmartspaceConfigPlugin
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.android.systemui.plugins.BcSmartspaceDataPlugin.SmartspaceTargetListener
import com.android.systemui.plugins.BcSmartspaceDataPlugin.SmartspaceView
import com.android.systemui.plugins.BcSmartspaceDataPlugin.TimeChangedDelegate
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.keyguard.data.model.WeatherData
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.settings.UserTracker
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shared.regionsampling.RegionSampler
import com.android.systemui.smartspace.dagger.SmartspaceModule.Companion.DATE_SMARTSPACE_DATA_PLUGIN
import com.android.systemui.smartspace.dagger.SmartspaceModule.Companion.WEATHER_SMARTSPACE_DATA_PLUGIN
import com.android.systemui.smartspace.ui.binder.SmartspaceViewBinder
import com.android.systemui.smartspace.ui.viewmodel.SmartspaceViewModel
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.util.asIndenting
import com.android.systemui.util.concurrency.Execution
import com.android.systemui.util.printCollection
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.time.SystemClock
import java.io.PrintWriter
import java.time.Instant
import java.util.Deque
import java.util.LinkedList
import java.util.Optional
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Named

/** Controller for managing the smartspace view on the lockscreen */
@SysUISingleton
class LockscreenSmartspaceController
@Inject
constructor(
    @ShadeDisplayAware private val context: Context,
    featureFlags: FeatureFlags,
    private val activityStarter: ActivityStarter,
    private val falsingManager: FalsingManager,
    private val systemClock: SystemClock,
    private val secureSettings: SecureSettings,
    private val userTracker: UserTracker,
    private val sysuiColorExtractor: SysuiColorExtractor,
    @ShadeDisplayAware private val configurationController: ConfigurationController,
    private val statusBarStateController: StatusBarStateController,
    private val deviceProvisionedController: DeviceProvisionedController,
    private val bypassController: KeyguardBypassController,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val smartspaceViewModelFactory: SmartspaceViewModel.Factory,
    dumpManager: DumpManager,
    private val execution: Execution,
    @Main private val uiExecutor: Executor,
    @Background private val bgExecutor: Executor,
    @Main private val handler: Handler,
    @Background private val bgHandler: Handler,
    @Named(DATE_SMARTSPACE_DATA_PLUGIN) optionalDatePlugin: Optional<BcSmartspaceDataPlugin>,
    @Named(WEATHER_SMARTSPACE_DATA_PLUGIN) optionalWeatherPlugin: Optional<BcSmartspaceDataPlugin>,
    optionalPlugin: Optional<BcSmartspaceDataPlugin>,
    optionalConfigPlugin: Optional<BcSmartspaceConfigPlugin>,
) : Dumpable {
    companion object {
        private const val TAG = "LockscreenSmartspaceController"

        private const val MAX_RECENT_SMARTSPACE_DATA_FOR_DUMP = 5
    }

    private var userSmartspaceManager: SmartspaceManager? = null
    private var session: SmartspaceSession? = null
    private val datePlugin: BcSmartspaceDataPlugin? = optionalDatePlugin.orElse(null)
    private val weatherPlugin: BcSmartspaceDataPlugin? = optionalWeatherPlugin.orElse(null)
    private val plugin: BcSmartspaceDataPlugin? = optionalPlugin.orElse(null)
    private val configPlugin: BcSmartspaceConfigPlugin? = optionalConfigPlugin.orElse(null)

    // This stores recently received Smartspace pushes to be included in dumpsys.
    private val recentSmartspaceData: Deque<List<SmartspaceTarget>> = LinkedList()

    // Smartspace can be used on multiple displays, such as when the user casts their screen
    @VisibleForTesting var smartspaceViews = mutableSetOf<SmartspaceView>()
    private var regionSamplers = mutableMapOf<SmartspaceView, RegionSampler>()
    private var dataListeners = mutableSetOf<SmartspaceTargetListener>()

    private val regionSamplingEnabled = featureFlags.isEnabled(Flags.REGION_SAMPLING)
    private var showNotifications = false
    private var showMediaControls = false
    private var showSensitiveContentForCurrentUser = false
    private var showSensitiveContentForManagedUser = false
    private var managedUserHandle: UserHandle? = null
    private var mSplitShadeEnabled = false
    private var storedMediaTarget: SmartspaceTarget? = null
    var mediaTarget: SmartspaceTarget? = null
        private set

    private val refreshInvoker: () -> Unit = { session?.requestSmartspaceUpdate() }

    var suppressDisconnects = false
        set(value) {
            field = value
            disconnect()
        }

    // TODO(b/202758428): refactor so that we can test color updates via region samping, similar to
    //  how we test color updates when theme changes (See testThemeChangeUpdatesTextColor).

    // TODO: Move logic into SmartspaceView
    var stateChangeListener =
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                (v as SmartspaceView).setSplitShadeEnabled(mSplitShadeEnabled)
                (v as SmartspaceView).setMediaTarget(mediaTarget)
                smartspaceViews.add(v as SmartspaceView)

                connectSession()

                updateBackgroundColorFromWallpaper()
                updateTextColorFromWallpaper()
                statusBarStateListener.onDozeAmountChanged(0f, statusBarStateController.dozeAmount)

                if (regionSamplingEnabled && (!regionSamplers.containsKey(v))) {
                    var regionSampler =
                        RegionSampler(
                            v as View,
                            uiExecutor,
                            bgExecutor,
                            regionSamplingEnabled,
                            isLockscreen = true,
                        ) {
                            updateTextColorFromRegionSampler()
                        }
                    initializeTextColors(regionSampler)
                    regionSamplers[v] = regionSampler
                    regionSampler.startRegionSampler()
                }
            }

            override fun onViewDetachedFromWindow(v: View) {
                smartspaceViews.remove(v as SmartspaceView)

                regionSamplers[v]?.stopRegionSampler()
                regionSamplers.remove(v as SmartspaceView)

                if (smartspaceViews.isEmpty()) {
                    disconnect()
                }
            }
        }

    private val sessionListener =
        SmartspaceSession.OnTargetsAvailableListener { targets ->
            execution.assertIsMainThread()

            // The weather data plugin takes unfiltered targets and performs the filtering
            // internally.
            weatherPlugin?.onTargetsAvailable(targets)

            val now = Instant.ofEpochMilli(systemClock.currentTimeMillis())
            val weatherTarget =
                targets.find { t ->
                    t.featureType == SmartspaceTarget.FEATURE_WEATHER &&
                        now.isAfter(Instant.ofEpochMilli(t.creationTimeMillis)) &&
                        now.isBefore(Instant.ofEpochMilli(t.expiryTimeMillis))
                }
            if (weatherTarget != null) {
                val clickIntent = weatherTarget.headerAction?.intent
                val weatherData =
                    weatherTarget.baseAction?.extras?.let { extras ->
                        WeatherData.fromBundle(extras) { _ ->
                            if (!falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                                activityStarter.startActivity(
                                    clickIntent,
                                    true, /* dismissShade */
                                    null,
                                    false,
                                )
                            }
                        }
                    }

                if (weatherData != null) {
                    keyguardUpdateMonitor.sendWeatherData(weatherData)
                }
            }

            val filteredTargets = targets.filter(::filterSmartspaceTarget)

            synchronized(recentSmartspaceData) {
                recentSmartspaceData.offerLast(filteredTargets)
                if (recentSmartspaceData.size > MAX_RECENT_SMARTSPACE_DATA_FOR_DUMP) {
                    recentSmartspaceData.pollFirst()
                }
            }

            plugin?.onTargetsAvailable(filteredTargets)
        }

    private val userTrackerCallback =
        object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) {
                execution.assertIsMainThread()
                reloadSmartspace()
            }
        }

    private val settingsObserver =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                execution.assertIsMainThread()
                if (session == null) {
                    return
                }
                reloadSmartspace()
            }
        }

    private val configChangeListener =
        object : ConfigurationController.ConfigurationListener {
            override fun onThemeChanged() {
                execution.assertIsMainThread()
                updateTextColorFromWallpaper()
            }
        }

    private val statusBarStateListener =
        object : StatusBarStateController.StateListener {
            override fun onDozeAmountChanged(linear: Float, eased: Float) {
                execution.assertIsMainThread()
                smartspaceViews.forEach { it.setDozeAmount(eased) }
            }

            override fun onDozingChanged(isDozing: Boolean) {
                execution.assertIsMainThread()
                smartspaceViews.forEach { it.setDozing(isDozing) }
            }
        }

    private val deviceProvisionedListener =
        object : DeviceProvisionedController.DeviceProvisionedListener {
            override fun onDeviceProvisionedChanged() {
                connectSession()
            }

            override fun onUserSetupChanged() {
                connectSession()
            }
        }

    private val bypassStateChangedListener =
        object : KeyguardBypassController.OnBypassStateChangedListener {
            override fun onBypassStateChanged(isEnabled: Boolean) {
                updateBypassEnabled()
            }
        }

    private val onColorsChangedListener =
        ColorExtractor.OnColorsChangedListener { _, which ->
            if (which == WallpaperManager.FLAG_LOCK) {
                updateBackgroundColorFromWallpaper()
            }
        }

    init {
        deviceProvisionedController.addCallback(deviceProvisionedListener)
        dumpManager.registerDumpable(this)
    }

    val isEnabled: Boolean = plugin != null

    val isWeatherEnabled: Boolean
        get() {
            val showWeather =
                secureSettings.getIntForUser(LOCK_SCREEN_WEATHER_ENABLED, 1, userTracker.userId) ==
                    1
            return showWeather
        }

    private fun updateBypassEnabled() {
        val bypassEnabled = bypassController.bypassEnabled
        smartspaceViews.forEach { it.setKeyguardBypassEnabled(bypassEnabled) }
    }

    /** Constructs the date view and connects it to the smartspace service. */
    fun buildAndConnectDateView(context: Context?, isLargeClock: Boolean): View? {
        execution.assertIsMainThread()

        if (!isEnabled) {
            throw RuntimeException("Cannot build view when not enabled")
        }

        val view =
            buildView(
                surfaceName = SmartspaceViewModel.SURFACE_DATE_VIEW,
                context = context,
                plugin = datePlugin,
                isLargeClock = isLargeClock,
            )
        connectSession()

        return view
    }

    /** Constructs the weather view and connects it to the smartspace service. */
    fun buildAndConnectWeatherView(context: Context?, isLargeClock: Boolean): View? {
        execution.assertIsMainThread()

        if (!isEnabled) {
            throw RuntimeException("Cannot build view when not enabled")
        }

        val view =
            buildView(
                surfaceName = SmartspaceViewModel.SURFACE_WEATHER_VIEW,
                context = context,
                plugin = weatherPlugin,
                isLargeClock = isLargeClock,
            )
        connectSession()

        return view
    }

    /** Constructs the smartspace view and connects it to the smartspace service. */
    fun buildAndConnectView(context: Context?): View? {
        execution.assertIsMainThread()

        if (!isEnabled) {
            throw RuntimeException("Cannot build view when not enabled")
        }

        val view =
            buildView(
                surfaceName = SmartspaceViewModel.SURFACE_GENERAL_VIEW,
                context = context,
                plugin = plugin,
                configPlugin = configPlugin,
                isLargeClock = false,
            )
        connectSession()

        return view
    }

    private fun buildView(
        surfaceName: String,
        context: Context?,
        plugin: BcSmartspaceDataPlugin?,
        configPlugin: BcSmartspaceConfigPlugin? = null,
        isLargeClock: Boolean,
    ): View? {
        if (plugin == null) {
            return null
        }

        val ctx = context ?: this.context
        val ssView = if (isLargeClock) plugin.getLargeClockView(ctx) else plugin.getView(ctx)
        configPlugin?.let { ssView.registerConfigProvider(it) }
        ssView.setBgHandler(bgHandler)
        ssView.setUiSurface(BcSmartspaceDataPlugin.UI_SURFACE_LOCK_SCREEN_AOD)
        ssView.setTimeChangedDelegate(SmartspaceTimeChangedDelegate(keyguardUpdateMonitor))
        plugin.setIntentStarter(
            object : BcSmartspaceDataPlugin.IntentStarter {
                override fun startIntent(view: View, intent: Intent, showOnLockscreen: Boolean) {
                    if (showOnLockscreen) {
                        activityStarter.startActivity(
                            intent,
                            true, /* dismissShade */
                            // launch animator - looks bad with the transparent smartspace bg
                            null,
                            true,
                        )
                    } else {
                        activityStarter.postStartActivityDismissingKeyguard(intent, 0)
                    }
                }

                override fun startPendingIntent(
                    view: View,
                    pi: PendingIntent,
                    showOnLockscreen: Boolean,
                ) {
                    if (showOnLockscreen) {
                        val options =
                            ActivityOptions.makeBasic()
                                .setPendingIntentBackgroundActivityStartMode(
                                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                                )
                                .toBundle()
                        pi.send(options)
                    } else {
                        activityStarter.postStartActivityDismissingKeyguard(pi)
                    }
                }
            }
        )

        ssView.registerDataProvider(plugin)
        ssView.setFalsingManager(falsingManager)
        ssView.setKeyguardBypassEnabled(bypassController.bypassEnabled)
        return (ssView as View).apply {
            setTag(R.id.tag_smartspace_view, Any())
            addOnAttachStateChangeListener(stateChangeListener)

            val viewModel = smartspaceViewModelFactory.create(surfaceName)
            SmartspaceViewBinder.bind(
                smartspaceView = ssView,
                refreshInvoker = refreshInvoker,
                viewModel = viewModel,
            )
        }
    }

    private fun connectSession() {
        if (userSmartspaceManager == null) {
            userSmartspaceManager =
                userTracker.userContext.getSystemService(SmartspaceManager::class.java)
        }
        if (userSmartspaceManager == null) return
        if (datePlugin == null && weatherPlugin == null && plugin == null) return
        if (session != null || (smartspaceViews.isEmpty() && dataListeners.isEmpty())) {
            return
        }

        // Only connect after the device is fully provisioned to avoid connection caching
        // issues
        if (
            !deviceProvisionedController.isDeviceProvisioned() ||
                !deviceProvisionedController.isCurrentUserSetup()
        ) {
            return
        }

        val newSession =
            userSmartspaceManager?.createSmartspaceSession(
                SmartspaceConfig.Builder(
                        userTracker.userContext,
                        BcSmartspaceDataPlugin.UI_SURFACE_LOCK_SCREEN_AOD,
                    )
                    .build()
            )
        Log.d(
            TAG,
            "Starting smartspace session for " + BcSmartspaceDataPlugin.UI_SURFACE_LOCK_SCREEN_AOD,
        )
        newSession?.addOnTargetsAvailableListener(uiExecutor, sessionListener)
        this.session = newSession

        deviceProvisionedController.removeCallback(deviceProvisionedListener)
        userTracker.addCallback(userTrackerCallback, uiExecutor)
        secureSettings.registerContentObserverForUserAsync(
            secureSettings.getUriFor(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS),
            true,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserAsync(
            secureSettings.getUriFor(LOCK_SCREEN_SHOW_NOTIFICATIONS),
            true,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserAsync(
            secureSettings.getUriFor(MEDIA_CONTROLS_LOCK_SCREEN),
            true,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        sysuiColorExtractor.addOnColorsChangedListener(onColorsChangedListener)
        configurationController.addCallback(configChangeListener)
        statusBarStateController.addCallback(statusBarStateListener)
        bypassController.registerOnBypassStateChangedListener(bypassStateChangedListener)

        datePlugin?.setEventDispatcher { e -> notifySmartspaceEvent(e) }
        weatherPlugin?.setEventDispatcher { e -> notifySmartspaceEvent(e) }
        plugin?.setEventDispatcher { e -> notifySmartspaceEvent(e) }

        updateBypassEnabled()
        reloadSmartspace()
    }

    /** Pushes a given SmartspaceTargetEvent to the SmartspaceSession. */
    private fun notifySmartspaceEvent(targetEvent: SmartspaceTargetEvent) {
        Log.d(TAG, "notifySmartspaceEvent: $targetEvent")
        session?.notifySmartspaceEvent(targetEvent)
    }

    /** Requests the smartspace session for an update. */
    fun requestSmartspaceUpdate() {
        session?.requestSmartspaceUpdate()
    }

    fun setMediaTarget(target: SmartspaceTarget?) {
        storedMediaTarget = target
        updateMediaTarget()
    }

    private fun updateMediaTarget() {
        val filteredTarget = if (showMediaControls) storedMediaTarget else null
        mediaTarget = filteredTarget
        smartspaceViews.forEach { it.setMediaTarget(filteredTarget) }
    }

    /** Disconnects the smartspace view from the smartspace service and cleans up any resources. */
    fun disconnect() {
        if (!smartspaceViews.isEmpty() || !dataListeners.isEmpty()) return
        if (suppressDisconnects) return

        execution.assertIsMainThread()

        if (session == null) {
            return
        }

        session?.let {
            it.removeOnTargetsAvailableListener(sessionListener)
            it.close()
        }
        userTracker.removeCallback(userTrackerCallback)
        secureSettings.unregisterContentObserverAsync(settingsObserver)
        sysuiColorExtractor.removeOnColorsChangedListener(onColorsChangedListener)
        configurationController.removeCallback(configChangeListener)
        statusBarStateController.removeCallback(statusBarStateListener)
        bypassController.unregisterOnBypassStateChangedListener(bypassStateChangedListener)
        session = null

        datePlugin?.setEventDispatcher(null)

        weatherPlugin?.setEventDispatcher(null)
        if (!SceneContainerFlag.isEnabled) {
            weatherPlugin?.onTargetsAvailable(emptyList())
        }

        plugin?.setEventDispatcher(null)
        if (!SceneContainerFlag.isEnabled) {
            plugin?.onTargetsAvailable(emptyList())
        }

        Log.d(TAG, "Ended smartspace session for lockscreen")
    }

    fun addListener(listener: SmartspaceTargetListener) {
        execution.assertIsMainThread()
        plugin?.registerListener(listener)
        if (SceneContainerFlag.isEnabled) {
            dataListeners.add(listener)
            connectSession()
        }
    }

    fun removeListener(listener: SmartspaceTargetListener) {
        execution.assertIsMainThread()
        plugin?.unregisterListener(listener)
        if (SceneContainerFlag.isEnabled) {
            dataListeners.remove(listener)
            disconnect()
        }
    }

    fun isWithinSmartspaceBounds(x: Int, y: Int): Boolean {
        smartspaceViews.forEach {
            val bounds = Rect()
            with(it as View) {
                this.getBoundsOnScreen(bounds)
                if (bounds.contains(x, y)) {
                    return true
                }
            }
        }

        return false
    }

    private fun filterSmartspaceTarget(t: SmartspaceTarget): Boolean {
        if (t.featureType == SmartspaceTarget.FEATURE_WEATHER) {
            return false
        }
        if (!showNotifications) {
            return t.featureType == SmartspaceTarget.FEATURE_WEATHER
        }
        return when (t.userHandle) {
            userTracker.userHandle -> {
                !t.isSensitive || showSensitiveContentForCurrentUser
            }

            managedUserHandle -> {
                // Really, this should be "if this managed profile is associated with the current
                // active user", but we don't have a good way to check that, so instead we cheat:
                // Only the primary user can have an associated managed profile, so only show
                // content for the managed profile if the primary user is active
                userTracker.userHandle.identifier == UserHandle.USER_SYSTEM &&
                    (!t.isSensitive || showSensitiveContentForManagedUser)
            }

            else -> {
                false
            }
        }
    }

    private fun initializeTextColors(regionSampler: RegionSampler) {
        val lightThemeContext = ContextThemeWrapper(context, R.style.Theme_SystemUI_LightWallpaper)
        val darkColor = Utils.getColorAttrDefaultColor(lightThemeContext, R.attr.wallpaperTextColor)

        val darkThemeContext = ContextThemeWrapper(context, R.style.Theme_SystemUI)
        val lightColor = Utils.getColorAttrDefaultColor(darkThemeContext, R.attr.wallpaperTextColor)

        regionSampler.setForegroundColors(lightColor, darkColor)
    }

    private fun updateTextColorFromRegionSampler() {
        regionSamplers.forEach { (view, region) ->
            val textColor = region.currentForegroundColor()
            if (textColor != null) {
                view.setPrimaryTextColor(textColor)
            }
        }
    }

    private fun updateBackgroundColorFromWallpaper() {
        val wallpaperColors =
            sysuiColorExtractor.getWallpaperColors(WallpaperManager.FLAG_LOCK) ?: return
        smartspaceViews.forEach { it.setHighContrastBackgroundColor(wallpaperColors.colorHints) }
    }

    private fun updateTextColorFromWallpaper() {
        if (!regionSamplingEnabled || regionSamplers.isEmpty()) {
            val wallpaperTextColor =
                Utils.getColorAttrDefaultColor(context, R.attr.wallpaperTextColor)
            smartspaceViews.forEach { it.setPrimaryTextColor(wallpaperTextColor) }
        } else {
            updateTextColorFromRegionSampler()
        }
    }

    private fun reloadSmartspace() {
        showNotifications =
            secureSettings.getIntForUser(LOCK_SCREEN_SHOW_NOTIFICATIONS, 0, userTracker.userId) == 1

        showMediaControls =
            secureSettings.getBoolForUser(MEDIA_CONTROLS_LOCK_SCREEN, true, userTracker.userId)

        showSensitiveContentForCurrentUser =
            secureSettings.getIntForUser(
                LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS,
                0,
                userTracker.userId,
            ) == 1

        managedUserHandle = getWorkProfileUser()
        val managedId = managedUserHandle?.identifier
        if (managedId != null) {
            showSensitiveContentForManagedUser =
                secureSettings.getIntForUser(
                    LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS,
                    0,
                    managedId,
                ) == 1
        }

        // Update the media target in case media controls setting changes.
        updateMediaTarget()
        session?.requestSmartspaceUpdate()
    }

    private fun getWorkProfileUser(): UserHandle? {
        for (userInfo in userTracker.userProfiles) {
            if (userInfo.isManagedProfile) {
                return userInfo.userHandle
            }
        }
        return null
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.asIndenting().run {
            printCollection("Region Samplers", regionSamplers.values) { it.dump(this) }
        }

        pw.println("Recent BC Smartspace Targets (most recent first)")
        synchronized(recentSmartspaceData) {
            if (recentSmartspaceData.size === 0) {
                pw.println("   No data\n")
                return
            }
            recentSmartspaceData.descendingIterator().forEachRemaining { smartspaceTargets ->
                pw.println("   Number of targets: ${smartspaceTargets.size}")
                for (target in smartspaceTargets) {
                    pw.println("      $target")
                }
                pw.println()
            }
        }
    }

    private class SmartspaceTimeChangedDelegate(
        private val keyguardUpdateMonitor: KeyguardUpdateMonitor
    ) : TimeChangedDelegate {
        private var keyguardUpdateMonitorCallback: KeyguardUpdateMonitorCallback? = null

        override fun register(callback: Runnable) {
            if (keyguardUpdateMonitorCallback != null) {
                unregister()
            }
            keyguardUpdateMonitorCallback =
                object : KeyguardUpdateMonitorCallback() {
                    override fun onTimeChanged() {
                        callback.run()
                    }
                }
            keyguardUpdateMonitor.registerCallback(keyguardUpdateMonitorCallback)
            callback.run()
        }

        override fun unregister() {
            keyguardUpdateMonitor.removeCallback(keyguardUpdateMonitorCallback)
            keyguardUpdateMonitorCallback = null
        }
    }
}
