/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs.tiles.dialog

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyDisplayInfo
import android.text.Html
import android.text.Layout
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.view.WindowManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.MutableLiveData
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
import com.android.internal.telephony.flags.Flags as telephonyFlags
import com.android.settingslib.satellite.SatelliteDialogUtils.TYPE_IS_WIFI
import com.android.settingslib.satellite.SatelliteDialogUtils.mayStartSatelliteWarningDialog
import com.android.settingslib.wifi.WifiEnterpriseRestrictionUtils
import com.android.settingslib.wifi.WifiUtils
import com.android.systemui.Flags
import com.android.systemui.Prefs
import com.android.systemui.accessibility.floatingmenu.AnnotationLinkSpan
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dynamiccolors.R as DynamicColorsR
import com.android.systemui.qs.flags.QsWifiConfig
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.ListenerSet
import com.android.wifitrackerlib.WifiEntry
import com.google.common.annotations.VisibleForTesting
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * View content for the Internet tile details that handles all UI interactions and state management.
 */
class InternetDetailsContentManager
@AssistedInject
constructor(
    private val internetDetailsContentController: InternetDetailsContentController,
    @Assisted(CAN_CONFIG_MOBILE_DATA) private val canConfigMobileData: Boolean,
    @Assisted(CAN_CONFIG_WIFI) private val canConfigWifi: Boolean,
    @Assisted(IS_IN_DIALOG) private val isInDialog: Boolean,
    private val uiEventLogger: UiEventLogger,
    @Main private val handler: Handler,
    @Background private val backgroundExecutor: Executor,
    private val keyguard: KeyguardStateController,
    private val userRepository: UserRepository,
) {
    private val isFlagEnabledAndInDialog: Boolean
        get() = Flags.internetDialogDelegateLegacyDeprecation() && isInDialog

    // Lifecycle
    private lateinit var lifecycleRegistry: LifecycleRegistry
    @VisibleForTesting internal var lifecycleOwner: LifecycleOwner? = null
    @VisibleForTesting internal val internetContentData = MutableLiveData<InternetContent>()
    @VisibleForTesting internal var connectedWifiEntry: WifiEntry? = null
    private var dialog: SystemUIDialog? = null
    @VisibleForTesting internal var isProgressBarAnimating = false

    // UI Components
    private lateinit var contentView: View
    private lateinit var progressBar: ProgressBar
    private lateinit var ethernetLayout: LinearLayout
    private lateinit var mobileNetworkLayout: LinearLayout
    private var secondaryMobileNetworkLayout: LinearLayout? = null
    private lateinit var turnWifiOnLayout: LinearLayout
    private lateinit var wifiToggleTitleTextView: TextView
    private lateinit var wifiScanNotifyLayout: LinearLayout
    private lateinit var wifiScanNotifyTextView: TextView
    private var internetDialogTitle: TextView? = null
    private var internetDialogSubTitle: TextView? = null
    private lateinit var connectedWifiListLayout: LinearLayout
    private lateinit var connectedWifiIcon: ImageView
    private lateinit var connectedWifiTitleTextView: TextView
    private lateinit var connectedWifiSummaryTextView: TextView
    private lateinit var wifiSettingsIcon: ImageView
    private lateinit var wifiRecyclerView: RecyclerView
    private lateinit var seeAllLayout: LinearLayout
    private lateinit var signalIcon: ImageView
    private var turnMobileOnLayout: LinearLayout? = null
    private var connectedMobileLayout: LinearLayout? = null
    private lateinit var mobileTitleTextView: TextView
    private lateinit var mobileSummaryTextView: TextView
    private lateinit var airplaneModeSummaryTextView: TextView
    private lateinit var mobileDataToggle: CompoundButton
    private lateinit var wifiToggle: CompoundButton
    private lateinit var shareWifiButton: View
    private var addNetworkButton: LinearLayout? = null
    private lateinit var airplaneModeButton: View
    private var doneButton: View? = null
    private var mobileToggleDivider: View? = null
    private lateinit var wifiButtonsContainer: ConstraintLayout
    private var alertDialog: AlertDialog? = null
    private var canChangeWifiState = false
    private var wifiNetworkHeight = 0
    private var entryBackgroundActive: Drawable? = null
    private var entryBackgroundInactive: Drawable? = null
    private var entryBackgroundStart: Drawable? = null
    private var entryBackgroundEnd: Drawable? = null
    private var entryBackgroundMiddle: Drawable? = null
    private var clickJob: Job? = null
    private var defaultDataSubId = internetDetailsContentController.defaultDataSubscriptionId
    @VisibleForTesting internal lateinit var adapter: InternetAdapter
    @VisibleForTesting internal var wifiEntriesCount: Int = 0
    @VisibleForTesting internal var hasMoreWifiEntries: Boolean = false
    @VisibleForTesting internal var hasSeeAllClicked: Boolean = false
    private lateinit var context: Context
    private lateinit var coroutineScope: CoroutineScope

    var title by mutableStateOf("")
        private set

    var subTitle by mutableStateOf("")
        private set

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted(CAN_CONFIG_MOBILE_DATA) canConfigMobileData: Boolean,
            @Assisted(CAN_CONFIG_WIFI) canConfigWifi: Boolean,
            @Assisted(IS_IN_DIALOG) isInDialog: Boolean,
        ): InternetDetailsContentManager
    }

    /**
     * Binds the content manager to the provided content view.
     *
     * This method initializes the lifecycle, views, click listeners, and UI of the details content.
     * It also updates the UI with the current Wi-Fi network information.
     *
     * @param contentView The view to which the content manager should be bound.
     */
    fun bind(contentView: View, dialog: SystemUIDialog?, coroutineScope: CoroutineScope) {
        if (DEBUG) {
            Log.d(TAG, "Bind InternetDetailsContentManager")
        }

        assert(!isFlagEnabledAndInDialog || dialog != null)

        this.contentView = contentView
        this.dialog = dialog
        context = contentView.context
        this.coroutineScope = coroutineScope
        adapter =
            InternetAdapter(
                internetDetailsContentController,
                coroutineScope,
                /* isInDetailsView= */ !isFlagEnabledAndInDialog,
                userRepository,
            )
        canChangeWifiState = WifiEnterpriseRestrictionUtils.isChangeWifiStateAllowed(context)

        initializeLifecycle()
        initializeViews()
        updateDetailsUI(getStartingInternetContent())
        initializeAndConfigure()
    }

    /**
     * Initializes the LifecycleRegistry. It sets the initial state of the LifecycleRegistry to
     * Lifecycle.State.CREATED.
     */
    fun initializeLifecycle() {
        // If a lifecycle already exists, destroy it before creating a new one.
        if (::lifecycleRegistry.isInitialized) {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }

        lifecycleOwner =
            object : LifecycleOwner {
                override val lifecycle: Lifecycle
                    get() = lifecycleRegistry
            }
        lifecycleRegistry = LifecycleRegistry(lifecycleOwner!!)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    interface Listener {
        /** Called when the Internet details UI content is updated. */
        fun onContentDataUpdated()
    }

    private val listeners = ListenerSet<Listener>()

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun initializeViews() {
        // Set accessibility properties
        contentView.accessibilityPaneTitle =
            context.getText(R.string.accessibility_desc_quick_settings)

        // Get dimension resources
        wifiNetworkHeight =
            context.resources.getDimensionPixelSize(R.dimen.tile_details_entry_height)

        // Initialize LiveData observer
        internetContentData.observe(lifecycleOwner!!) { internetContent ->
            internetContent?.let { updateDetailsUI(it) }
            listeners.forEach { it.onContentDataUpdated() }
        }

        // Network layouts
        progressBar =
            contentView.requireViewById<ProgressBar>(R.id.wifi_searching_progress).apply {
                visibility = View.VISIBLE
            }

        // Background drawables
        entryBackgroundActive =
            if (isFlagEnabledAndInDialog) {
                context.getDrawable(R.drawable.settingslib_switch_bar_bg_on)
            } else {
                context.getDrawable(R.drawable.settingslib_entry_bg_on)
            }
        entryBackgroundInactive =
            if (isFlagEnabledAndInDialog) {
                context.getDrawable(R.drawable.internet_dialog_selected_effect)
            } else {
                context.getDrawable(R.drawable.settingslib_entry_bg_off)
            }
        entryBackgroundStart = context.getDrawable(R.drawable.settingslib_entry_bg_off_start)
        entryBackgroundEnd = context.getDrawable(R.drawable.settingslib_entry_bg_off_end)
        entryBackgroundMiddle = context.getDrawable(R.drawable.settingslib_entry_bg_off_middle)

        // Set wifi, mobile and ethernet layouts
        setWifiLayout()
        setMobileLayout()
        ethernetLayout =
            contentView.requireViewById<LinearLayout>(R.id.ethernet_layout).apply {
                setEntryMargins()
            }

        // Share WiFi
        shareWifiButton = contentView.requireViewById(R.id.share_wifi_button)
        shareWifiButton.setOnClickListener { view ->
            if (
                internetDetailsContentController.mayLaunchShareWifiSettings(
                    connectedWifiEntry,
                    view,
                )
            ) {
                uiEventLogger.log(InternetDetailsEvent.SHARE_WIFI_QS_BUTTON_CLICKED)
            }
        }
        setButtonAccessibilityRole(shareWifiButton)

        // Add network
        addNetworkButton = contentView.findViewById(R.id.add_network_button)
        if (QsWifiConfig.isEnabled) {
            addNetworkButton?.let { button ->
                button.setOnClickListener {
                    val intent =
                        WifiUtils.getWifiDialogIntent(null, true /* connectForCaller */).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        }
                    internetDetailsContentController.startActivityForDialog(intent)
                }
                setButtonAccessibilityRole(button)
            }
        }

        // Airplane mode
        airplaneModeButton = contentView.requireViewById(R.id.apm_button)
        airplaneModeButton.setOnClickListener {
            internetDetailsContentController.setAirplaneModeDisabled()
        }
        setButtonAccessibilityRole(airplaneModeButton)
        if (isFlagEnabledAndInDialog) {
            internetDialogTitle = contentView.requireViewById(R.id.internet_dialog_title)
            internetDialogSubTitle = contentView.requireViewById(R.id.internet_dialog_subtitle)
            doneButton = contentView.findViewById(R.id.done_button)
            doneButton?.setOnClickListener { dialog?.dismiss() }
            wifiButtonsContainer = contentView.requireViewById(R.id.button_layout)
        } else {
            wifiButtonsContainer = contentView.requireViewById(R.id.wifi_buttons_container)
        }
        airplaneModeSummaryTextView = contentView.requireViewById(R.id.airplane_mode_summary)
    }

    // Sets horizontal margins to create a consistent look for tile detail entries.
    private fun ViewGroup.setEntryMargins() {
        val horizontalMargin =
            this.resources.getDimensionPixelSize(R.dimen.tile_details_entry_horizontal_margin)

        updateLayoutParams<ViewGroup.MarginLayoutParams> {
            marginStart = horizontalMargin
            marginEnd = horizontalMargin
        }
    }

    private fun setWifiLayout() {
        // Initialize Wi-Fi related views
        turnWifiOnLayout = contentView.requireViewById(R.id.turn_on_wifi_layout)
        wifiToggleTitleTextView = contentView.requireViewById(R.id.wifi_toggle_title)
        wifiScanNotifyLayout = contentView.requireViewById(R.id.wifi_scan_notify_layout)
        wifiScanNotifyTextView = contentView.requireViewById(R.id.wifi_scan_notify_text)
        connectedWifiListLayout = contentView.requireViewById(R.id.wifi_connected_layout)
        connectedWifiIcon = contentView.requireViewById(R.id.wifi_connected_icon)
        connectedWifiTitleTextView = contentView.requireViewById(R.id.wifi_connected_title)
        connectedWifiSummaryTextView = contentView.requireViewById(R.id.wifi_connected_summary)
        wifiSettingsIcon = contentView.requireViewById(R.id.wifi_settings_icon)
        wifiToggle = contentView.requireViewById(R.id.wifi_toggle)
        wifiRecyclerView =
            contentView.requireViewById<RecyclerView>(R.id.wifi_list_layout).apply {
                layoutManager = LinearLayoutManager(context)
                adapter = this@InternetDetailsContentManager.adapter
            }

        val entryHeight =
            contentView.context.resources.getDimensionPixelSize(R.dimen.tile_details_entry_height)

        turnWifiOnLayout.apply {
            setEntryMargins()
            layoutParams = layoutParams.apply { height = entryHeight }
        }

        // Add backgrounds for each entry and also add a gap between each item.
        if (!isFlagEnabledAndInDialog) {
            val verticalSpacing =
                context.resources.getDimensionPixelSize(R.dimen.tile_details_entry_gap)
            wifiRecyclerView.addItemDecoration(
                object : RecyclerView.ItemDecoration() {
                    override fun getItemOffsets(
                        outRect: Rect,
                        view: View,
                        parent: RecyclerView,
                        state: RecyclerView.State,
                    ) {
                        outRect.top = verticalSpacing
                    }

                    override fun onDraw(
                        c: Canvas,
                        parent: RecyclerView,
                        state: RecyclerView.State,
                    ) {
                        // `itemCount` represents the total number of items in your adapter's data
                        // set,
                        // regardless of what's visible.
                        val adapter = parent.adapter ?: return
                        val itemCount = adapter.itemCount

                        // `parent.childCount` is the number of child views currently visible on
                        // screen.
                        // Often less than itemCount since RecyclerView recycles views that scroll
                        // off-screen.
                        for (i in 0 until parent.childCount) {
                            val child = parent.getChildAt(i) ?: continue
                            val adapterPosition = parent.getChildAdapterPosition(child)
                            if (adapterPosition == RecyclerView.NO_POSITION) continue
                            val entryView = child.requireViewById<LinearLayout>(R.id.wifi_list)
                            val background =
                                when {
                                    itemCount == 1 -> entryBackgroundInactive
                                    adapterPosition == 0 -> entryBackgroundStart
                                    adapterPosition == itemCount - 1 &&
                                        !hasMoreWifiEntries &&
                                        !QsWifiConfig.isEnabled -> entryBackgroundEnd
                                    else -> entryBackgroundMiddle
                                }

                            val left = child.left + entryView.left
                            val top = child.top + entryView.top
                            val right = child.left + entryView.right
                            val bottom = child.top + entryView.bottom
                            background?.setBounds(left, top, right, bottom)
                            background?.draw(c)
                        }
                    }
                }
            )
        }

        seeAllLayout =
            contentView.requireViewById<LinearLayout>(R.id.see_all_layout).apply {
                setEntryMargins()
                layoutParams = layoutParams.apply { height = entryHeight }
            }
        seeAllLayout.setOnClickListener(this::onClickSeeMoreButton)
        setButtonAccessibilityRole(seeAllLayout)

        // Set click listeners for Wi-Fi related views
        turnWifiOnLayout.setOnClickListener {
            wifiToggle.toggle()
            val isChecked = wifiToggle.isChecked
            handleWifiToggleClicked(isChecked)
        }
        if (isFlagEnabledAndInDialog) {
            wifiToggle.isClickable = false
            wifiToggle.isFocusable = false
        }

        connectedWifiListLayout.setOnClickListener(this::onClickConnectedWifi)
        if (!isFlagEnabledAndInDialog) {
            connectedWifiListLayout.background =
                context.getDrawable(R.drawable.settingslib_entry_bg_on)
        }
    }

    private fun setMobileLayout() {
        // Initialize mobile data related views
        mobileNetworkLayout = contentView.requireViewById(R.id.mobile_network_layout)
        turnMobileOnLayout = contentView.findViewById(R.id.turn_on_mobile_layout)
        connectedMobileLayout = contentView.findViewById(R.id.mobile_connected_layout)
        signalIcon = contentView.requireViewById(R.id.signal_icon)
        mobileTitleTextView = contentView.requireViewById(R.id.mobile_title)
        mobileSummaryTextView = contentView.requireViewById(R.id.mobile_summary)
        mobileDataToggle = contentView.requireViewById(R.id.mobile_toggle)
        mobileToggleDivider = contentView.findViewById(R.id.mobile_toggle_divider)

        // Set click listeners for mobile data related views
        val mobileLayoutClickListener =
            View.OnClickListener {
                val autoSwitchNonDdsSubId: Int =
                    internetDetailsContentController.getActiveAutoSwitchNonDdsSubId()
                if (autoSwitchNonDdsSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    showTurnOffAutoDataSwitchDialog(autoSwitchNonDdsSubId)
                }
                internetDetailsContentController.connectCarrierNetwork()
            }
        if (connectedMobileLayout != null) {
            connectedMobileLayout?.setOnClickListener(mobileLayoutClickListener)
        } else if (isFlagEnabledAndInDialog) {
            mobileNetworkLayout.setOnClickListener(mobileLayoutClickListener)
        }

        val mobileToggleClickListener =
            View.OnClickListener {
                val isChecked = mobileDataToggle.isChecked
                if (!isChecked && shouldShowMobileDialog()) {
                    mobileDataToggle.isChecked = true
                    showTurnOffMobileDialog()
                } else if (internetDetailsContentController.isMobileDataEnabled != isChecked) {
                    internetDetailsContentController.setMobileDataEnabled(
                        context,
                        defaultDataSubId,
                        isChecked,
                        false,
                    )
                }
            }

        // Mobile data toggle entry
        if (turnMobileOnLayout != null) {
            turnMobileOnLayout?.setOnClickListener {
                mobileDataToggle.toggle()
                mobileToggleClickListener.onClick(mobileDataToggle)
            }
        } else if (isFlagEnabledAndInDialog) {
            mobileDataToggle.setOnClickListener(mobileToggleClickListener)
        }
    }

    /**
     * This function ensures the component is in the RESUMED state and sets up the internet details
     * content controller.
     *
     * If the component is already in the RESUMED state, this function does nothing.
     */
    fun initializeAndConfigure() {
        // If the current state is RESUMED, it's already initialized.
        if (lifecycleRegistry.currentState == Lifecycle.State.RESUMED) {
            return
        }

        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        internetDetailsContentController.onStart(
            internetDetailsCallback,
            canConfigWifi,
            coroutineScope,
        )
        if (!canConfigWifi) {
            hideWifiViews()
        }
    }

    private fun getTitleText(): String {
        return internetDetailsContentController.getDialogTitleText(canConfigMobileData).toString()
    }

    private fun getSubtitleText(): String {
        return internetDetailsContentController.getSubtitleText(isProgressBarAnimating).toString()
    }

    // Add Accessibility Role for layout view so that they can be announced as "Button"
    private fun setButtonAccessibilityRole(view: View) {
        ViewCompat.setAccessibilityDelegate(
            view,
            object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfoCompat,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                }
            },
        )
    }

    private fun updateDetailsUI(internetContent: InternetContent) {
        if (DEBUG) {
            Log.d(TAG, "updateDetailsUI ")
        }

        if (!::context.isInitialized) {
            return
        }

        title = getTitleText()
        subTitle = getSubtitleText()

        if (isFlagEnabledAndInDialog) {
            internetDialogTitle?.text = title
            internetDialogSubTitle?.text = subTitle
        }

        if (!internetContent.isWifiEnabled) {
            setProgressBarAnimating(false)
        }

        updateEthernetUI(internetContent)
        updateMobileUI(internetContent)
        updateWifiUI(internetContent)
        updateButtonsLayout(internetContent)
    }

    private fun getStartingInternetContent(): InternetContent {
        return InternetContent(
            isWifiEnabled = internetDetailsContentController.isWifiEnabled,
            isDeviceLocked = internetDetailsContentController.isDeviceLocked,
            isHeadlessSystemUser = internetDetailsContentController.isHeadlessSystemUser,
        )
    }

    @VisibleForTesting
    internal fun hideWifiViews() {
        setProgressBarAnimating(false)
        turnWifiOnLayout.visibility = View.GONE
        connectedWifiListLayout.visibility = View.GONE
        wifiRecyclerView.visibility = View.GONE
        seeAllLayout.visibility = View.GONE
        shareWifiButton.visibility = View.GONE
        addNetworkButton?.visibility = View.GONE
    }

    private fun setProgressBarAnimating(isAnimating: Boolean) {
        if (isProgressBarAnimating == isAnimating) {
            return
        }
        isProgressBarAnimating = isAnimating

        // The progress bar when not animating,
        // changes color and fills to maximum to act as a static background.
        if (isAnimating) {
            progressBar.isIndeterminate = true
            progressBar.progressTintList = null
        } else {
            progressBar.isIndeterminate = false
            progressBar.progress = progressBar.max

            progressBar.progressTintList =
                context.getColorStateList(DynamicColorsR.color.materialColorSurfaceContainerHigh)
        }
    }

    private fun showTurnOffAutoDataSwitchDialog(subId: Int) {
        var carrierName: CharSequence? = getMobileNetworkTitle(defaultDataSubId)
        if (TextUtils.isEmpty(carrierName)) {
            carrierName = getDefaultCarrierName()
        }
        alertDialog =
            AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.auto_data_switch_disable_title, carrierName))
                .setMessage(R.string.auto_data_switch_disable_message)
                .setNegativeButton(R.string.auto_data_switch_dialog_negative_button) { _, _ -> }
                .setPositiveButton(R.string.auto_data_switch_dialog_positive_button) { _, _ ->
                    internetDetailsContentController.setAutoDataSwitchMobileDataPolicy(
                        subId,
                        /* enable= */ false,
                    )
                    secondaryMobileNetworkLayout?.visibility = View.GONE
                }
                .create()
        alertDialog!!.window?.setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG)
        SystemUIDialog.setShowForAllUsers(alertDialog, true)
        SystemUIDialog.registerDismissListener(alertDialog)
        SystemUIDialog.setWindowOnTop(alertDialog, keyguard.isShowing())
        alertDialog!!.show()
    }

    private fun shouldShowMobileDialog(): Boolean {
        val mobileDataTurnedOff =
            Prefs.getBoolean(context, Prefs.Key.QS_HAS_TURNED_OFF_MOBILE_DATA, false)
        return internetDetailsContentController.isMobileDataEnabled && !mobileDataTurnedOff
    }

    private fun getMobileNetworkTitle(subId: Int): CharSequence {
        return internetDetailsContentController.getMobileNetworkTitle(subId)
    }

    private fun showTurnOffMobileDialog() {
        var carrierName: CharSequence? = getMobileNetworkTitle(defaultDataSubId)
        val isInService: Boolean =
            internetDetailsContentController.isVoiceStateInService(defaultDataSubId)
        if (TextUtils.isEmpty(carrierName) || !isInService) {
            carrierName = getDefaultCarrierName()
        }
        alertDialog =
            AlertDialog.Builder(context)
                .setTitle(R.string.mobile_data_disable_title)
                .setMessage(context.getString(R.string.mobile_data_disable_message, carrierName))
                .setNegativeButton(android.R.string.cancel) { _: DialogInterface?, _: Int -> }
                .setPositiveButton(
                    com.android.internal.R.string.alert_windows_notification_turn_off_action
                ) { _: DialogInterface?, _: Int ->
                    internetDetailsContentController.setMobileDataEnabled(
                        context,
                        defaultDataSubId,
                        false,
                        false,
                    )
                    mobileDataToggle.isChecked = false
                    Prefs.putBoolean(context, Prefs.Key.QS_HAS_TURNED_OFF_MOBILE_DATA, true)
                }
                .create()
        alertDialog!!.window?.setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG)
        SystemUIDialog.setShowForAllUsers(alertDialog, true)
        SystemUIDialog.registerDismissListener(alertDialog)
        SystemUIDialog.setWindowOnTop(alertDialog, keyguard.isShowing())
        alertDialog!!.show()
    }

    private fun onClickConnectedWifi(view: View?) {
        if (connectedWifiEntry == null) {
            return
        }
        internetDetailsContentController.launchWifiDetailsSetting(connectedWifiEntry!!.key, view)
    }

    private fun onClickSeeMoreButton(view: View?) {
        if (!isFlagEnabledAndInDialog && QsWifiConfig.isEnabled) {
            hasSeeAllClicked = true
            updateContent(shouldUpdateMobileNetwork = false)
        } else {
            internetDetailsContentController.launchNetworkSetting(view)
        }
    }

    private fun handleWifiToggleClicked(isChecked: Boolean) {
        if (clickJob != null && !clickJob!!.isCompleted) {
            return
        }
        clickJob =
            mayStartSatelliteWarningDialog(contentView.context, coroutineScope, TYPE_IS_WIFI) {
                isAllowClick: Boolean ->
                if (isAllowClick) {
                    setWifiEnabled(isChecked)
                } else {
                    wifiToggle.isChecked = !isChecked
                }
            }
    }

    private fun setWifiEnabled(isEnabled: Boolean) {
        if (internetDetailsContentController.isWifiEnabled == isEnabled) {
            return
        }
        internetDetailsContentController.isWifiEnabled = isEnabled
    }

    @MainThread
    private fun updateEthernetUI(internetContent: InternetContent) {
        ethernetLayout.visibility = if (internetContent.hasEthernet) View.VISIBLE else View.GONE
    }

    private fun updateWifiUI(internetContent: InternetContent) {
        if (!canConfigWifi) {
            return
        }

        updateWifiToggle(internetContent)
        updateConnectedWifi(internetContent)
        updateWifiListAndSeeAll(internetContent)
        updateWifiScanNotify(internetContent)
    }

    private fun updateMobileUI(internetContent: InternetContent) {
        if (!internetContent.shouldUpdateMobileNetwork) {
            return
        }

        val isNetworkConnected =
            internetContent.activeNetworkIsCellular || internetContent.isCarrierNetworkActive
        // 1. Mobile network should be gone if airplane mode ON or the list of active
        //    subscriptionId is null.
        // 2. Carrier network should be gone if airplane mode ON and Wi-Fi is OFF.
        if (DEBUG) {
            Log.d(
                TAG,
                /*msg = */ "updateMobileUI, isCarrierNetworkActive = " +
                    internetContent.isCarrierNetworkActive,
            )
        }

        if (
            !internetContent.hasActiveSubIdOnDds &&
                (!internetContent.isWifiEnabled || !internetContent.isCarrierNetworkActive)
        ) {
            mobileNetworkLayout.visibility = View.GONE
            secondaryMobileNetworkLayout?.visibility = View.GONE
            return
        }

        mobileNetworkLayout.visibility = View.VISIBLE
        if (
            internetContent.currentSatelliteState >
                InternetDetailsContentController.SATELLITE_NOT_STARTED
        ) {
            mobileTitleTextView.setText(R.string.satellite_network_title_text)
            mobileDataToggle.visibility = View.INVISIBLE
            mobileToggleDivider?.visibility = View.INVISIBLE
            mobileSummaryTextView.text = ""
            if (
                internetContent.currentSatelliteState ==
                    InternetDetailsContentController.SATELLITE_CONNECTED
            ) {
                val strConnected = context.getString(R.string.mobile_data_connection_active)
                mobileSummaryTextView.text =
                    if (telephonyFlags.newSatelliteIcon()) {
                        satelliteSummary(strConnected)
                    } else {
                        strConnected
                    }
                mobileSummaryTextView.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                mobileSummaryTextView.visibility = View.VISIBLE
            } else {
                mobileSummaryTextView.visibility = View.GONE
            }
            val drawable =
                context.resources.getDrawable(
                    if (
                        internetContent.currentSatelliteState >
                            InternetDetailsContentController.SATELLITE_STARTED
                    )
                        R.drawable.ic_satellite_connected_2
                    else R.drawable.ic_satellite_not_connected
                )
            drawable.setTint(context.getColor(R.color.connected_network_primary_color))
            signalIcon.setImageDrawable(drawable)
            return
        }

        mobileDataToggle.setChecked(internetDetailsContentController.isMobileDataEnabled)
        mobileTitleTextView.text = getMobileNetworkTitle(defaultDataSubId)
        val summary = getMobileNetworkSummary(defaultDataSubId)
        if (!TextUtils.isEmpty(summary)) {
            mobileSummaryTextView.text = Html.fromHtml(summary, Html.FROM_HTML_MODE_LEGACY)
            mobileSummaryTextView.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            mobileSummaryTextView.visibility = View.VISIBLE
        } else {
            mobileSummaryTextView.visibility = View.GONE
        }
        backgroundExecutor.execute {
            val drawable = getSignalStrengthDrawable(defaultDataSubId)
            handler.post { signalIcon.setImageDrawable(drawable) }
        }

        mobileDataToggle.visibility = if (canConfigMobileData) View.VISIBLE else View.INVISIBLE

        if (isFlagEnabledAndInDialog) {
            mobileToggleDivider?.visibility =
                if (canConfigMobileData) View.VISIBLE else View.INVISIBLE
            val primaryColor =
                if (isNetworkConnected) R.color.connected_network_primary_color
                else R.color.disconnected_network_primary_color
            mobileToggleDivider?.setBackgroundColor(context.getColor(primaryColor))
        }

        // Display the info for the non-DDS if it's actively being used
        val autoSwitchNonDdsSubId: Int = internetContent.activeAutoSwitchNonDdsSubId

        val nonDdsVisibility =
            if (autoSwitchNonDdsSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) View.VISIBLE
            else View.GONE

        val secondaryRes =
            if (isNetworkConnected) R.style.TextAppearance_TileDetailsEntrySubTitle_Active
            else R.style.TextAppearance_TileDetailsEntrySubTitle
        if (nonDdsVisibility == View.VISIBLE) {
            // non DDS is the currently active sub, set primary visual for it
            setNonDDSActive(autoSwitchNonDdsSubId)
        } else if (connectedMobileLayout != null) {
            connectedMobileLayout?.background =
                if (isNetworkConnected) entryBackgroundActive else entryBackgroundInactive
            mobileTitleTextView.setTextAppearance(
                if (isNetworkConnected) R.style.TextAppearance_TileDetailsEntryTitle_Active
                else R.style.TextAppearance_TileDetailsEntryTitle
            )
            mobileSummaryTextView.setTextAppearance(secondaryRes)
        } else if (isFlagEnabledAndInDialog) {
            mobileNetworkLayout.background =
                if (isNetworkConnected) entryBackgroundActive else entryBackgroundInactive
            mobileTitleTextView.setTextAppearance(
                if (isNetworkConnected) R.style.TextAppearance_TileDetailsEntryTitle_Active
                else R.style.TextAppearance_TileDetailsEntryTitle
            )
            mobileSummaryTextView.setTextAppearance(secondaryRes)
        }

        secondaryMobileNetworkLayout?.visibility = nonDdsVisibility

        // Set airplane mode to the summary for carrier network
        if (internetContent.isAirplaneModeEnabled) {
            airplaneModeSummaryTextView.apply {
                visibility = View.VISIBLE
                text = context.getText(R.string.airplane_mode)
                setTextAppearance(secondaryRes)
            }
        } else {
            airplaneModeSummaryTextView.visibility = View.GONE
        }
    }

    private fun setNonDDSActive(autoSwitchNonDdsSubId: Int) {
        if (secondaryMobileNetworkLayout == null) {
            secondaryMobileNetworkLayout =
                contentView.findViewById(R.id.secondary_mobile_network_layout)
                    ?: (contentView
                        .findViewById<ViewStub>(R.id.secondary_mobile_network_stub)
                        ?.inflate() as? LinearLayout)
        }

        if (canConfigMobileData || !Flags.internetDialogDelegateLegacyDeprecation()) {
            secondaryMobileNetworkLayout?.setOnClickListener { view: View? ->
                this.onClickConnectedSecondarySub(view)
            }
        }
        secondaryMobileNetworkLayout?.background = entryBackgroundActive

        contentView.requireViewById<TextView>(R.id.secondary_mobile_title).apply {
            text = getMobileNetworkTitle(autoSwitchNonDdsSubId)
            setTextAppearance(R.style.TextAppearance_TileDetailsEntryTitle_Active)
        }

        val secondarySummaryTextView =
            contentView.requireViewById<TextView>(R.id.secondary_mobile_summary)
        val summary = getMobileNetworkSummary(autoSwitchNonDdsSubId)
        if (Flags.internetDialogDelegateLegacyDeprecation()) {
            backgroundExecutor.execute {
                handler.post {
                    secondarySummaryTextView.apply {
                        if (!TextUtils.isEmpty(summary)) {
                            text = Html.fromHtml(summary, Html.FROM_HTML_MODE_LEGACY)
                            breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
                            setTextAppearance(R.style.TextAppearance_TileDetailsEntryTitle_Active)
                        }
                    }
                }
            }
        } else {
            secondarySummaryTextView.apply {
                if (!TextUtils.isEmpty(summary)) {
                    text = Html.fromHtml(summary, Html.FROM_HTML_MODE_LEGACY)
                    breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
                    setTextAppearance(R.style.TextAppearance_TileDetailsEntryTitle_Active)
                }
            }
        }

        val secondarySignalIcon: ImageView = contentView.requireViewById(R.id.secondary_signal_icon)
        backgroundExecutor.execute {
            val drawable = getSignalStrengthDrawable(autoSwitchNonDdsSubId)
            handler.post { secondarySignalIcon.setImageDrawable(drawable) }
        }

        contentView.requireViewById<ImageView>(R.id.secondary_settings_icon).apply {
            setColorFilter(context.getColor(R.color.connected_network_primary_color))
            if (Flags.internetDialogDelegateLegacyDeprecation()) {
                visibility = if (canConfigMobileData) View.VISIBLE else View.INVISIBLE
            }
        }

        // set secondary visual for default data sub
        if (connectedMobileLayout != null) {
            connectedMobileLayout?.background = entryBackgroundInactive
        } else if (isFlagEnabledAndInDialog) {
            mobileNetworkLayout.background = entryBackgroundInactive
        }

        mobileTitleTextView.setTextAppearance(R.style.TextAppearance_TileDetailsEntryTitle)
        mobileSummaryTextView.setTextAppearance(R.style.TextAppearance_TileDetailsEntrySubTitle)
        signalIcon.setColorFilter(context.getColor(R.color.connected_network_secondary_color))
    }

    @MainThread
    private fun updateWifiToggle(internetContent: InternetContent) {
        if (wifiToggle.isChecked != internetContent.isWifiEnabled) {
            wifiToggle.isChecked = internetContent.isWifiEnabled
        }

        if (isFlagEnabledAndInDialog) {
            if (internetContent.isDeviceLocked) {
                wifiToggleTitleTextView.setTextAppearance(
                    if (connectedWifiEntry != null) R.style.TextAppearance_InternetDialog_Active
                    else R.style.TextAppearance_InternetDialog
                )
            }
            turnWifiOnLayout.background =
                if (internetContent.isDeviceLocked && connectedWifiEntry != null)
                    entryBackgroundActive
                else null
        }

        if (!canChangeWifiState && wifiToggle.isEnabled) {
            wifiToggle.isEnabled = false
            wifiToggleTitleTextView.isEnabled = false
            contentView.requireViewById<TextView>(R.id.wifi_toggle_summary).apply {
                isEnabled = false
                visibility = View.VISIBLE
            }
        }
    }

    @MainThread
    private fun updateButtonsLayout(internetContent: InternetContent) {
        airplaneModeButton.visibility =
            if (internetContent.isAirplaneModeEnabled) View.VISIBLE else View.GONE

        val apmVisible = airplaneModeButton.isVisible
        val shareVisible = shareWifiButton.isVisible
        if (!apmVisible && !shareVisible) return
        if (isFlagEnabledAndInDialog) return

        val shareParams = shareWifiButton.layoutParams as ConstraintLayout.LayoutParams
        shareWifiButton.minimumWidth = 0

        if (apmVisible) {
            shareParams.matchConstraintDefaultWidth =
                ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_WRAP
        } else {
            shareParams.matchConstraintDefaultWidth =
                ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_SPREAD

            // By setting `MATCH_CONSTRAINT_SPREAD`, we ask ConstraintLayout to expand this
            // `shareWifiButton`. However, due to layout timing, this change may not be reflected
            // immediately. To force the button to occupy the full width on the very next layout
            // pass, we set its minimumWidth to the exact current width of the parent container.
            shareWifiButton.minimumWidth = wifiButtonsContainer.width
        }

        shareWifiButton.layoutParams = shareParams
    }

    @MainThread
    private fun updateConnectedWifi(internetContent: InternetContent) {
        if (
            !internetContent.isWifiEnabled ||
                connectedWifiEntry == null ||
                internetContent.isDeviceLocked ||
                internetContent.isHeadlessSystemUser
        ) {
            connectedWifiListLayout.visibility = View.GONE
            shareWifiButton.visibility = View.GONE
            return
        }
        connectedWifiListLayout.visibility = View.VISIBLE
        connectedWifiTitleTextView.text = connectedWifiEntry!!.title
        connectedWifiSummaryTextView.text = connectedWifiEntry!!.getSummary(false)
        connectedWifiIcon.setImageDrawable(
            internetDetailsContentController.getInternetWifiDrawable(connectedWifiEntry!!)
        )

        val canShareWifi =
            internetDetailsContentController.getConfiguratorQrCodeGeneratorIntentOrNull(
                connectedWifiEntry
            ) != null
        shareWifiButton.visibility = if (canShareWifi) View.VISIBLE else View.GONE

        secondaryMobileNetworkLayout?.visibility = View.GONE
    }

    @MainThread
    private fun updateWifiListAndSeeAll(internetContent: InternetContent) {
        if (
            !internetContent.isWifiEnabled ||
                internetContent.isDeviceLocked ||
                internetContent.isHeadlessSystemUser
        ) {
            wifiRecyclerView.visibility = View.GONE
            seeAllLayout.visibility = View.GONE
            addNetworkButton?.visibility = View.GONE
            return
        }
        val isAddNetworkVisible = QsWifiConfig.isEnabled

        if (isAddNetworkVisible) {
            addNetworkButton?.visibility = View.VISIBLE
        }
        if (isAddNetworkVisible && internetContent.showAllWifiInList) {
            hasMoreWifiEntries = false
            adapter.setShowAllWifi()
            seeAllLayout.visibility = View.GONE
        } else {
            val wifiListMaxCount = getWifiListMaxCount()
            if (adapter.itemCount > wifiListMaxCount) {
                hasMoreWifiEntries = true
            }
            adapter.setMaxEntriesCount(wifiListMaxCount)
            val wifiListMinHeight = wifiNetworkHeight * wifiListMaxCount
            if (wifiRecyclerView.minimumHeight != wifiListMinHeight) {
                wifiRecyclerView.minimumHeight = wifiListMinHeight
            }
            seeAllLayout.visibility = if (hasMoreWifiEntries) View.VISIBLE else View.INVISIBLE
        }
        wifiRecyclerView.invalidateItemDecorations()

        // Here using `View.setBackgroundResource` instead of setting the background directly. This
        // is a deliberate choice to avoid issues where a shared Drawable's state can cause
        // rendering problems (e.g., an empty background).
        if (!isFlagEnabledAndInDialog) {
            seeAllLayout.setBackgroundResource(
                if (isAddNetworkVisible) R.drawable.settingslib_entry_bg_off_middle
                else R.drawable.settingslib_entry_bg_off_end
            )
        }
        wifiRecyclerView.visibility = View.VISIBLE
    }

    @MainThread
    private fun updateWifiScanNotify(internetContent: InternetContent) {
        if (
            internetContent.isWifiEnabled ||
                !internetContent.isWifiScanEnabled ||
                internetContent.isDeviceLocked
        ) {
            wifiScanNotifyLayout.visibility = View.GONE
            return
        }

        if (TextUtils.isEmpty(wifiScanNotifyTextView.text)) {
            val linkInfo =
                AnnotationLinkSpan.LinkInfo(AnnotationLinkSpan.LinkInfo.DEFAULT_ANNOTATION) {
                    view: View? ->
                    internetDetailsContentController.launchWifiScanningSetting(view)
                }
            wifiScanNotifyTextView.text =
                AnnotationLinkSpan.linkify(
                    context.getText(R.string.wifi_scan_notify_message),
                    linkInfo,
                )
            wifiScanNotifyTextView.movementMethod = LinkMovementMethod.getInstance()
        }
        wifiScanNotifyLayout.visibility = View.VISIBLE
    }

    @VisibleForTesting
    @MainThread
    internal fun getWifiListMaxCount(): Int {
        // Use the maximum count of networks to calculate the remaining count for Wi-Fi networks.
        var count = MAX_NETWORK_COUNT
        if (ethernetLayout.isVisible) {
            count -= 1
        }
        if (mobileNetworkLayout.isVisible) {
            count -= 1
        }

        // If the remaining count is greater than the maximum count of the Wi-Fi network, the
        // maximum count of the Wi-Fi network is used.
        if (count > InternetDetailsContentController.MAX_WIFI_ENTRY_COUNT) {
            count = InternetDetailsContentController.MAX_WIFI_ENTRY_COUNT
        }
        if (connectedWifiListLayout.isVisible) {
            count -= 1
        }
        return count
    }

    private fun getMobileNetworkSummary(subId: Int): String {
        return internetDetailsContentController.getMobileNetworkSummary(subId)
    }

    /** For DSDS auto data switch */
    private fun onClickConnectedSecondarySub(view: View?) {
        internetDetailsContentController.launchMobileNetworkSettings(view)
    }

    private fun getSignalStrengthDrawable(subId: Int): Drawable {
        return internetDetailsContentController.getSignalStrengthDrawable(subId)
    }

    /**
     * Unbinds all listeners and resources associated with the view. This method should be called
     * when the view is no longer needed.
     */
    fun unBind() {
        if (DEBUG) {
            Log.d(TAG, "unBind")
        }

        // Android view inside of the composable view can call onRelease (triggering unBind) before
        // bind() has ever been executed, so we must check if the lifecycle is initialized.
        if (!::lifecycleRegistry.isInitialized) {
            return
        }

        listeners.forEach { listeners.remove(it) }
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        connectedMobileLayout?.setOnClickListener(null)
        mobileNetworkLayout.setOnClickListener(null)
        connectedWifiListLayout.setOnClickListener(null)
        secondaryMobileNetworkLayout?.setOnClickListener(null)
        seeAllLayout.setOnClickListener(null)
        turnWifiOnLayout.setOnClickListener(null)
        turnMobileOnLayout?.setOnClickListener(null)
        mobileDataToggle.setOnClickListener(null)
        shareWifiButton.setOnClickListener(null)
        addNetworkButton?.setOnClickListener(null)
        airplaneModeButton.setOnClickListener(null)

        connectedMobileLayout = null
        secondaryMobileNetworkLayout = null
        turnMobileOnLayout = null
        addNetworkButton = null
        doneButton = null
        mobileToggleDivider = null
        internetDialogTitle = null
        internetDialogSubTitle = null

        internetDetailsContentController.onStop()
    }

    /**
     * Update the internet details content when receiving the callback.
     *
     * @param shouldUpdateMobileNetwork `true` for update the mobile network layout, otherwise
     *   `false`.
     */
    @VisibleForTesting
    internal fun updateContent(shouldUpdateMobileNetwork: Boolean) {
        backgroundExecutor.execute {
            internetContentData.postValue(getInternetContent(shouldUpdateMobileNetwork))
        }
    }

    private fun getInternetContent(shouldUpdateMobileNetwork: Boolean): InternetContent {
        return InternetContent(
            shouldUpdateMobileNetwork = shouldUpdateMobileNetwork,
            activeNetworkIsCellular =
                if (shouldUpdateMobileNetwork)
                    internetDetailsContentController.activeNetworkIsCellular()
                else false,
            isCarrierNetworkActive =
                if (shouldUpdateMobileNetwork)
                    internetDetailsContentController.isCarrierNetworkActive()
                else false,
            isAirplaneModeEnabled = internetDetailsContentController.isAirplaneModeEnabled,
            hasEthernet = internetDetailsContentController.hasEthernet(),
            isWifiEnabled = internetDetailsContentController.isWifiEnabled,
            hasActiveSubIdOnDds = internetDetailsContentController.hasActiveSubIdOnDds(),
            isDeviceLocked = internetDetailsContentController.isDeviceLocked,
            isWifiScanEnabled = internetDetailsContentController.isWifiScanEnabled(),
            isHeadlessSystemUser = internetDetailsContentController.isHeadlessSystemUser,
            activeAutoSwitchNonDdsSubId =
                internetDetailsContentController.getActiveAutoSwitchNonDdsSubId(),
            showAllWifiInList = hasSeeAllClicked,
            activeDataSubId = internetDetailsContentController.getActiveDataSubId(),
            currentSatelliteState = internetDetailsContentController.getCurrentSatelliteState(),
            defaultSubSignalStrengthIcon =
                internetDetailsContentController.getSignalStrengthDrawable(defaultDataSubId),
        )
    }

    private fun getDefaultCarrierName(): String? {
        return context.getString(R.string.mobile_data_disable_message_default_carrier)
    }

    private fun satelliteSummary(strConnected: String): String {
        val strSat: String = context.getString(com.android.internal.R.string.satellite_indicator)
        return context.getString(
            com.android.settingslib.R.string.preference_summary_default_combination,
            strConnected,
            strSat,
        )
    }

    @VisibleForTesting
    internal val internetDetailsCallback =
        object : InternetDetailsContentController.InternetDialogCallback {
            override fun onRefreshCarrierInfo() {
                updateContent(shouldUpdateMobileNetwork = true)
            }

            override fun onSimStateChanged() {
                updateContent(shouldUpdateMobileNetwork = true)
            }

            @WorkerThread
            override fun onCapabilitiesChanged(
                network: Network?,
                networkCapabilities: NetworkCapabilities?,
            ) {
                updateContent(shouldUpdateMobileNetwork = true)
            }

            @WorkerThread
            override fun onLost(network: Network) {
                updateContent(shouldUpdateMobileNetwork = true)
            }

            override fun onSubscriptionsChanged(dataSubId: Int) {
                defaultDataSubId = dataSubId
                updateContent(shouldUpdateMobileNetwork = true)
            }

            override fun onServiceStateChanged(serviceState: ServiceState?) {
                updateContent(shouldUpdateMobileNetwork = true)
            }

            @WorkerThread
            override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
                updateContent(shouldUpdateMobileNetwork = true)
            }

            override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                updateContent(shouldUpdateMobileNetwork = true)
            }

            override fun onUserMobileDataStateChanged(enabled: Boolean) {
                updateContent(shouldUpdateMobileNetwork = true)
            }

            override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo?) {
                updateContent(shouldUpdateMobileNetwork = true)
            }

            override fun onCarrierNetworkChange(active: Boolean) {
                updateContent(shouldUpdateMobileNetwork = true)
            }

            override fun dismissDialog() {
                // With the dialog, this would be implemented to close the internet dialog. As this
                // details view is embedded within the QS panel, collapsing is handled by System UI.
                if (DEBUG) {
                    Log.d(TAG, "dismissDialog")
                }
            }

            override fun onAccessPointsChanged(
                wifiEntries: MutableList<WifiEntry>?,
                connectedEntry: WifiEntry?,
                ifHasMoreWifiEntries: Boolean,
            ) {
                // Should update the carrier network layout when it is connected under airplane
                // mode ON.
                val shouldUpdateCarrierNetwork =
                    (mobileNetworkLayout.isVisible) &&
                        internetDetailsContentController.isAirplaneModeEnabled
                handler.post {
                    connectedWifiEntry = connectedEntry
                    wifiEntriesCount = wifiEntries?.size ?: 0
                    hasMoreWifiEntries = ifHasMoreWifiEntries
                    updateContent(shouldUpdateCarrierNetwork)
                    adapter.setWifiEntries(wifiEntries, wifiEntriesCount)
                    adapter.notifyDataSetChanged()
                }
            }

            override fun onWifiScan(isScan: Boolean) {
                setProgressBarAnimating(isScan)
            }

            override fun onSatelliteModemStateChanged(state: Int) {
                updateContent(shouldUpdateMobileNetwork = true)
            }
        }

    enum class InternetDetailsEvent(private val id: Int) : UiEventLogger.UiEventEnum {
        @UiEvent(doc = "The Internet details became visible on the screen.")
        INTERNET_DETAILS_VISIBLE(2071),
        @UiEvent(doc = "The share wifi button is clicked.") SHARE_WIFI_QS_BUTTON_CLICKED(1462);

        override fun getId(): Int {
            return id
        }
    }

    @VisibleForTesting
    data class InternetContent(
        val isAirplaneModeEnabled: Boolean = false,
        val hasEthernet: Boolean = false,
        val shouldUpdateMobileNetwork: Boolean = false,
        val activeNetworkIsCellular: Boolean = false,
        val isCarrierNetworkActive: Boolean = false,
        val isWifiEnabled: Boolean = false,
        val hasActiveSubIdOnDds: Boolean = false,
        val isDeviceLocked: Boolean = false,
        val isWifiScanEnabled: Boolean = false,
        val isHeadlessSystemUser: Boolean = false,
        val showAllWifiInList: Boolean = false,
        val activeAutoSwitchNonDdsSubId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID,
        val activeDataSubId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID,
        val currentSatelliteState: Int = InternetDetailsContentController.SATELLITE_NOT_STARTED,
        val defaultSubSignalStrengthIcon: Drawable? = null,
    )

    companion object {
        private const val TAG = "InternetDetailsContent"
        private val DEBUG: Boolean = Log.isLoggable(TAG, Log.DEBUG)
        private const val MAX_NETWORK_COUNT = 4
        const val CAN_CONFIG_MOBILE_DATA = "can_config_mobile_data"
        const val CAN_CONFIG_WIFI = "can_config_wifi"
        const val IS_IN_DIALOG = "is_in_dialog"
    }
}
