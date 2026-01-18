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

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.fakeExecutorHandler
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.telephony.telephonyManager
import android.testing.TestableLooper.RunWithLooper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.annotation.UiThreadTest
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito
import com.android.internal.logging.UiEventLogger
import com.android.settingslib.wifi.WifiEnterpriseRestrictionUtils
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.android.wifitrackerlib.WifiEntry
import com.google.common.truth.Expect
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.MockitoSession
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
@RunWithLooper(setAsMainLooper = true)
@EnableSceneContainer
@EnableFlags(
    Flags.FLAG_QS_TILE_DETAILED_VIEW,
    Flags.FLAG_INTERNET_DIALOG_DELEGATE_LEGACY_DEPRECATION,
)
@UiThreadTest
class InternetDetailsContentManagerTest(private val isInDialog: Boolean) : SysuiTestCase() {
    @get:Rule val expect: Expect = Expect.create()

    private val kosmos = testKosmos()
    private val handler: Handler = kosmos.fakeExecutorHandler
    private val testScope = kosmos.testScope
    private val telephonyManager: TelephonyManager = kosmos.telephonyManager
    private val internetWifiEntry: WifiEntry = mock<WifiEntry>()
    private val wifiEntries: List<WifiEntry> = mock<List<WifiEntry>>()
    private val internetAdapter = mock<InternetAdapter>()
    private val internetDetailsContentController: InternetDetailsContentController =
        mock<InternetDetailsContentController>()
    private val keyguard: KeyguardStateController = mock<KeyguardStateController>()
    private val bgExecutor = FakeExecutor(FakeSystemClock())
    private val userRepository = kosmos.fakeUserRepository
    private lateinit var internetDetailsContentManager: InternetDetailsContentManager
    private var ethernet: LinearLayout? = null
    private var mobileDataLayout: LinearLayout? = null
    private var mobileToggleSwitch: CompoundButton? = null
    private var wifiToggle: LinearLayout? = null
    private var wifiToggleSwitch: CompoundButton? = null
    private var wifiToggleSummary: TextView? = null
    private var connectedWifi: LinearLayout? = null
    private var wifiList: RecyclerView? = null
    private var seeAll: LinearLayout? = null
    private var wifiScanNotify: LinearLayout? = null
    private var airplaneModeSummaryText: TextView? = null
    private var mockitoSession: MockitoSession? = null
    private var sharedWifiButton: View? = null
    private var addNetworkButton: View? = null
    private lateinit var contentView: View

    @Before
    fun setUp() {
        whenever(telephonyManager.createForSubscriptionId(ArgumentMatchers.anyInt()))
            .thenReturn(telephonyManager)
        whenever(internetWifiEntry.title).thenReturn(WIFI_TITLE)
        whenever(internetWifiEntry.getSummary(false)).thenReturn(WIFI_SUMMARY)
        whenever(internetWifiEntry.isDefaultNetwork).thenReturn(true)
        whenever(internetWifiEntry.hasInternetAccess()).thenReturn(true)
        whenever(wifiEntries.size).thenReturn(1)
        whenever(internetDetailsContentController.getDialogTitleText(true)).thenReturn(TITLE)
        whenever(internetDetailsContentController.getSubtitleText(ArgumentMatchers.anyBoolean()))
            .thenReturn("")
        whenever(internetDetailsContentController.getMobileNetworkTitle(ArgumentMatchers.anyInt()))
            .thenReturn(MOBILE_NETWORK_TITLE)
        whenever(
                internetDetailsContentController.getMobileNetworkSummary(ArgumentMatchers.anyInt())
            )
            .thenReturn(MOBILE_NETWORK_SUMMARY)
        val mockDrawable = mock<Drawable>()
        whenever(mockDrawable.mutate()).thenReturn(mockDrawable)
        whenever(
                internetDetailsContentController.getSignalStrengthDrawable(
                    ArgumentMatchers.anyInt()
                )
            )
            .thenReturn(mockDrawable)
        whenever(internetDetailsContentController.isWifiEnabled).thenReturn(true)
        whenever(internetDetailsContentController.activeAutoSwitchNonDdsSubId)
            .thenReturn(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        mockitoSession =
            ExtendedMockito.mockitoSession()
                .spyStatic(WifiEnterpriseRestrictionUtils::class.java)
                .startMocking()
        whenever(WifiEnterpriseRestrictionUtils.isChangeWifiStateAllowed(mContext)).thenReturn(true)
        createView()
    }

    private fun createView() {
        val layoutId =
            if (isInDialog) {
                R.layout.internet_connectivity_dialog
            } else {
                R.layout.internet_connectivity_details
            }
        contentView = LayoutInflater.from(mContext).inflate(layoutId, null)
        internetDetailsContentManager =
            InternetDetailsContentManager(
                internetDetailsContentController,
                canConfigMobileData = true,
                canConfigWifi = true,
                isInDialog = isInDialog,
                uiEventLogger = mock<UiEventLogger>(),
                handler = handler,
                backgroundExecutor = bgExecutor,
                keyguard = keyguard,
                userRepository = userRepository,
            )

        internetDetailsContentManager.bind(contentView, null, testScope)
        internetDetailsContentManager.adapter = internetAdapter
        internetDetailsContentManager.connectedWifiEntry = internetWifiEntry
        internetDetailsContentManager.wifiEntriesCount = wifiEntries.size

        ethernet = contentView.requireViewById(R.id.ethernet_layout)
        mobileDataLayout = contentView.requireViewById(R.id.mobile_network_layout)
        mobileToggleSwitch = contentView.requireViewById(R.id.mobile_toggle)
        wifiToggle = contentView.requireViewById(R.id.turn_on_wifi_layout)
        wifiToggleSwitch = contentView.requireViewById(R.id.wifi_toggle)
        wifiToggleSummary = contentView.requireViewById(R.id.wifi_toggle_summary)
        connectedWifi = contentView.requireViewById(R.id.wifi_connected_layout)
        wifiList = contentView.requireViewById(R.id.wifi_list_layout)
        seeAll = contentView.requireViewById(R.id.see_all_layout)
        wifiScanNotify = contentView.requireViewById(R.id.wifi_scan_notify_layout)
        airplaneModeSummaryText = contentView.requireViewById(R.id.airplane_mode_summary)
        sharedWifiButton = contentView.findViewById(R.id.share_wifi_button)
        addNetworkButton = contentView.findViewById(R.id.add_network_button)
    }

    @After
    fun tearDown() {
        internetDetailsContentManager.unBind()
        mockitoSession!!.finishMocking()
    }

    @Test
    fun createView_setAccessibilityPaneTitleToQuickSettings() {
        assertThat(contentView.accessibilityPaneTitle)
            .isEqualTo(mContext.getText(R.string.accessibility_desc_quick_settings))
    }

    @Test
    fun hideWifiViews_WifiViewsGone() {
        internetDetailsContentManager.hideWifiViews()

        assertThat(internetDetailsContentManager.isProgressBarAnimating).isFalse()
        assertThat(wifiToggle!!.visibility).isEqualTo(View.GONE)
        assertThat(connectedWifi!!.visibility).isEqualTo(View.GONE)
        assertThat(wifiList!!.visibility).isEqualTo(View.GONE)
        assertThat(seeAll!!.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun updateContent_apmOffAndHasEthernet_showEthernet() {
        whenever(internetDetailsContentController.isAirplaneModeEnabled).thenReturn(false)
        whenever(internetDetailsContentController.hasEthernet()).thenReturn(true)
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(ethernet!!.visibility).isEqualTo(View.VISIBLE)
        }
    }

    @Test
    fun updateContent_apmOffAndNoEthernet_hideEthernet() {
        whenever(internetDetailsContentController.isAirplaneModeEnabled).thenReturn(false)
        whenever(internetDetailsContentController.hasEthernet()).thenReturn(false)
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(ethernet!!.visibility).isEqualTo(View.GONE)
        }
    }

    @Test
    fun updateContent_apmOnAndHasEthernet_showEthernet() {
        whenever(internetDetailsContentController.isAirplaneModeEnabled).thenReturn(true)
        whenever(internetDetailsContentController.hasEthernet()).thenReturn(true)
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(ethernet!!.visibility).isEqualTo(View.VISIBLE)
        }
    }

    @Test
    fun updateContent_apmOnAndNoEthernet_hideEthernet() {
        whenever(internetDetailsContentController.isAirplaneModeEnabled).thenReturn(true)
        whenever(internetDetailsContentController.hasEthernet()).thenReturn(false)
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(ethernet!!.visibility).isEqualTo(View.GONE)
        }
    }

    @Test
    fun updateContent_apmOffAndNotCarrierNetwork_mobileDataLayoutGone() {
        // Mobile network should be gone if the list of active subscriptionId is null.
        whenever(internetDetailsContentController.isCarrierNetworkActive).thenReturn(false)
        whenever(internetDetailsContentController.isAirplaneModeEnabled).thenReturn(false)
        whenever(internetDetailsContentController.hasActiveSubIdOnDds()).thenReturn(false)
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(mobileDataLayout!!.visibility).isEqualTo(View.GONE)
        }
    }

    @Test
    fun updateContent_apmOnWithCarrierNetworkAndWifiStatus_mobileDataLayoutVisible() {
        // Carrier network should be visible if airplane mode ON and Wi-Fi is ON.
        whenever(internetDetailsContentController.isCarrierNetworkActive).thenReturn(true)
        whenever(internetDetailsContentController.isAirplaneModeEnabled).thenReturn(true)
        whenever(internetDetailsContentController.isWifiEnabled).thenReturn(true)
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(mobileDataLayout!!.visibility).isEqualTo(View.VISIBLE)
        }
    }

    @Test
    fun updateContent_apmOnWithCarrierNetworkAndWifiStatus_mobileDataLayoutGone() {
        // Carrier network should be gone if airplane mode ON and Wi-Fi is off.
        whenever(internetDetailsContentController.isCarrierNetworkActive).thenReturn(true)
        whenever(internetDetailsContentController.isAirplaneModeEnabled).thenReturn(true)
        whenever(internetDetailsContentController.isWifiEnabled).thenReturn(false)
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(mobileDataLayout!!.visibility).isEqualTo(View.GONE)
        }
    }

    @Test
    fun updateContent_apmOnAndNoCarrierNetwork_mobileDataLayoutGone() {
        whenever(internetDetailsContentController.isCarrierNetworkActive).thenReturn(false)
        whenever(internetDetailsContentController.isAirplaneModeEnabled).thenReturn(true)
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(mobileDataLayout!!.visibility).isEqualTo(View.GONE)
        }
    }

    @Test
    fun updateContent_apmOnAndWifiOnHasCarrierNetwork_showAirplaneSummary() {
        whenever(internetDetailsContentController.isCarrierNetworkActive).thenReturn(true)
        whenever(internetDetailsContentController.isAirplaneModeEnabled).thenReturn(true)
        internetDetailsContentManager.connectedWifiEntry = null
        whenever(internetDetailsContentController.activeNetworkIsCellular()).thenReturn(false)
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(mobileDataLayout!!.visibility).isEqualTo(View.VISIBLE)
            assertThat(airplaneModeSummaryText!!.visibility).isEqualTo(View.VISIBLE)
        }
    }

    @Test
    fun updateContent_apmOffAndWifiOnHasCarrierNetwork_notShowApmSummary() {
        whenever(internetDetailsContentController.isCarrierNetworkActive).thenReturn(true)
        whenever(internetDetailsContentController.isAirplaneModeEnabled).thenReturn(false)
        internetDetailsContentManager.connectedWifiEntry = null
        whenever(internetDetailsContentController.activeNetworkIsCellular()).thenReturn(false)
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(airplaneModeSummaryText!!.visibility).isEqualTo(View.GONE)
        }
    }

    @Test
    fun updateContent_apmOffAndHasCarrierNetwork_notShowApmSummary() {
        whenever(internetDetailsContentController.isCarrierNetworkActive).thenReturn(true)
        whenever(internetDetailsContentController.isAirplaneModeEnabled).thenReturn(false)
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(airplaneModeSummaryText!!.visibility).isEqualTo(View.GONE)
        }
    }

    @Test
    fun updateContent_apmOnAndNoCarrierNetwork_notShowApmSummary() {
        whenever(internetDetailsContentController.isCarrierNetworkActive).thenReturn(false)
        whenever(internetDetailsContentController.isAirplaneModeEnabled).thenReturn(true)
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(airplaneModeSummaryText!!.visibility).isEqualTo(View.GONE)
        }
    }

    @Test
    fun updateContent_mobileDataIsEnabled_checkMobileDataSwitch() {
        whenever(internetDetailsContentController.hasActiveSubIdOnDds()).thenReturn(true)
        whenever(internetDetailsContentController.isCarrierNetworkActive).thenReturn(true)
        whenever(internetDetailsContentController.isMobileDataEnabled).thenReturn(true)
        mobileToggleSwitch!!.isChecked = false
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(mobileToggleSwitch!!.isChecked).isTrue()
        }
    }

    @Test
    fun updateContent_mobileDataIsNotChanged_checkMobileDataSwitch() {
        whenever(internetDetailsContentController.hasActiveSubIdOnDds()).thenReturn(true)
        whenever(internetDetailsContentController.isCarrierNetworkActive).thenReturn(true)
        whenever(internetDetailsContentController.isMobileDataEnabled).thenReturn(false)
        mobileToggleSwitch!!.isChecked = false
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(mobileToggleSwitch!!.isChecked).isFalse()
        }
    }

    @Test
    @DisableFlags(Flags.FLAG_QS_WIFI_CONFIG, Flags.FLAG_QS_WIFI_MULTIUSER)
    fun updateContent_wifiOnAndHasInternetWifi_showConnectedWifi() {
        whenever(internetDetailsContentController.activeAutoSwitchNonDdsSubId).thenReturn(1)
        whenever(internetDetailsContentController.hasActiveSubIdOnDds()).thenReturn(true)

        // The preconditions WiFi ON and Internet WiFi are already in setUp()
        whenever(internetDetailsContentController.activeNetworkIsCellular()).thenReturn(false)

        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(connectedWifi!!.visibility).isEqualTo(View.VISIBLE)
            val secondaryLayout =
                contentView.requireViewById<LinearLayout>(R.id.secondary_mobile_network_layout)
            assertThat(secondaryLayout.visibility).isEqualTo(View.GONE)
            addNetworkButton?.let { assertThat(it.visibility).isEqualTo(View.GONE) }
        }
    }

    @Test
    @DisableFlags(Flags.FLAG_QS_WIFI_CONFIG, Flags.FLAG_QS_WIFI_MULTIUSER)
    fun updateContent_wifiOnAndNoConnectedWifi_hideConnectedWifi() {
        // The precondition WiFi ON is already in setUp()
        internetDetailsContentManager.connectedWifiEntry = null
        whenever(internetDetailsContentController.activeNetworkIsCellular()).thenReturn(false)

        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(connectedWifi!!.visibility).isEqualTo(View.GONE)
            addNetworkButton?.let { assertThat(it.visibility).isEqualTo(View.GONE) }
        }
    }

    @Test
    @DisableFlags(Flags.FLAG_QS_WIFI_CONFIG, Flags.FLAG_QS_WIFI_MULTIUSER)
    fun updateContent_wifiOnAndNoWifiEntry_showWifiListAndSeeAllArea() {
        // The precondition WiFi ON is already in setUp()
        internetDetailsContentManager.connectedWifiEntry = null
        internetDetailsContentManager.wifiEntriesCount = 0
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(connectedWifi!!.visibility).isEqualTo(View.GONE)
            // Show a blank block to fix the details content height even if there is no WiFi list
            assertThat(wifiList!!.visibility).isEqualTo(View.VISIBLE)
            verify(internetAdapter).setMaxEntriesCount(3)
            assertThat(seeAll!!.visibility).isEqualTo(View.INVISIBLE)
            addNetworkButton?.let { assertThat(it.visibility).isEqualTo(View.GONE) }
        }
    }

    @Test
    @DisableFlags(Flags.FLAG_QS_WIFI_CONFIG, Flags.FLAG_QS_WIFI_MULTIUSER)
    fun updateContent_wifiOnAndOneWifiEntry_showWifiListAndSeeAllArea() {
        // The precondition WiFi ON is already in setUp()
        internetDetailsContentManager.connectedWifiEntry = null
        internetDetailsContentManager.wifiEntriesCount = 1
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(connectedWifi!!.visibility).isEqualTo(View.GONE)
            // Show a blank block to fix the details content height even if there is no WiFi list
            assertThat(wifiList!!.visibility).isEqualTo(View.VISIBLE)
            verify(internetAdapter).setMaxEntriesCount(3)
            assertThat(seeAll!!.visibility).isEqualTo(View.INVISIBLE)
            addNetworkButton?.let { assertThat(it.visibility).isEqualTo(View.GONE) }
        }
    }

    @Test
    @DisableFlags(Flags.FLAG_QS_WIFI_CONFIG, Flags.FLAG_QS_WIFI_MULTIUSER)
    fun updateContent_wifiOnAndHasConnectedWifi_showAllWifiAndSeeAllArea() {
        // The preconditions WiFi ON and WiFi entries are already in setUp()
        internetDetailsContentManager.wifiEntriesCount = 0
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(connectedWifi!!.visibility).isEqualTo(View.VISIBLE)
            // Show a blank block to fix the details content height even if there is no WiFi list
            assertThat(wifiList!!.visibility).isEqualTo(View.VISIBLE)
            verify(internetAdapter).setMaxEntriesCount(2)
            assertThat(seeAll!!.visibility).isEqualTo(View.INVISIBLE)
            addNetworkButton?.let { assertThat(it.visibility).isEqualTo(View.GONE) }
        }
    }

    @Test
    @DisableFlags(Flags.FLAG_QS_WIFI_CONFIG, Flags.FLAG_QS_WIFI_MULTIUSER)
    fun updateContent_wifiOnAndHasMaxWifiList_showWifiListAndSeeAll() {
        // The preconditions WiFi ON and WiFi entries are already in setUp()
        internetDetailsContentManager.connectedWifiEntry = null
        internetDetailsContentManager.wifiEntriesCount =
            InternetDetailsContentController.MAX_WIFI_ENTRY_COUNT
        internetDetailsContentManager.hasMoreWifiEntries = true
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(connectedWifi!!.visibility).isEqualTo(View.GONE)
            assertThat(wifiList!!.visibility).isEqualTo(View.VISIBLE)
            verify(internetAdapter).setMaxEntriesCount(3)
            assertThat(seeAll!!.visibility).isEqualTo(View.VISIBLE)
            addNetworkButton?.let { assertThat(it.visibility).isEqualTo(View.GONE) }
        }
    }

    @Test
    @DisableFlags(Flags.FLAG_QS_WIFI_CONFIG, Flags.FLAG_QS_WIFI_MULTIUSER)
    fun updateContent_wifiOnAndHasBothWifiEntry_showBothWifiEntryAndSeeAll() {
        // The preconditions WiFi ON and WiFi entries are already in setUp()
        internetDetailsContentManager.wifiEntriesCount =
            InternetDetailsContentController.MAX_WIFI_ENTRY_COUNT - 1
        internetDetailsContentManager.hasMoreWifiEntries = true
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(connectedWifi!!.visibility).isEqualTo(View.VISIBLE)
            assertThat(wifiList!!.visibility).isEqualTo(View.VISIBLE)
            verify(internetAdapter).setMaxEntriesCount(2)
            assertThat(seeAll!!.visibility).isEqualTo(View.VISIBLE)
            addNetworkButton?.let { assertThat(it.visibility).isEqualTo(View.GONE) }
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_QS_WIFI_MULTIUSER)
    fun updateContent_deviceLockedAndNoConnectedWifi_showWifiToggle() {
        // The preconditions WiFi entries are already in setUp()
        whenever(internetDetailsContentController.isDeviceLocked).thenReturn(true)
        internetDetailsContentManager.connectedWifiEntry = null
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            // Show WiFi Toggle
            assertThat(wifiToggle!!.visibility).isEqualTo(View.VISIBLE)
            // Hide Wi-Fi networks and See all
            assertThat(connectedWifi!!.visibility).isEqualTo(View.GONE)
            assertThat(wifiList!!.visibility).isEqualTo(View.GONE)
            assertThat(seeAll!!.visibility).isEqualTo(View.GONE)
            addNetworkButton?.let { assertThat(it.visibility).isEqualTo(View.GONE) }
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_QS_WIFI_MULTIUSER)
    fun updateContent_deviceLockedAndHasConnectedWifi_showWifiToggleWithBackground() {
        // The preconditions WiFi ON and WiFi entries are already in setUp()
        whenever(internetDetailsContentController.isDeviceLocked).thenReturn(true)
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            // Show WiFi Toggle
            assertThat(wifiToggle!!.visibility).isEqualTo(View.VISIBLE)
            // Hide Wi-Fi networks and See all
            assertThat(connectedWifi!!.visibility).isEqualTo(View.GONE)
            assertThat(wifiList!!.visibility).isEqualTo(View.GONE)
            assertThat(seeAll!!.visibility).isEqualTo(View.GONE)
            addNetworkButton?.let { assertThat(it.visibility).isEqualTo(View.GONE) }
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_QS_WIFI_MULTIUSER)
    fun updateContent_showAddNetworkButton() {
        if (isInDialog) {
            return
        }
        // The preconditions WiFi ON and WiFi entries are already in setUp()
        internetDetailsContentManager.wifiEntriesCount =
            InternetDetailsContentController.MAX_WIFI_ENTRY_COUNT - 1
        internetDetailsContentManager.hasMoreWifiEntries = true
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            addNetworkButton?.let { assertThat(it.visibility).isEqualTo(View.VISIBLE) }
        }
    }

    @Test
    @DisableFlags(Flags.FLAG_QS_WIFI_CONFIG, Flags.FLAG_QS_WIFI_MULTIUSER)
    fun updateContent_notShowAddNetworkButtonWhenFlagDisabled() {
        if (isInDialog) {
            return
        }
        // The preconditions WiFi ON and WiFi entries are already in setUp()
        internetDetailsContentManager.wifiEntriesCount =
            InternetDetailsContentController.MAX_WIFI_ENTRY_COUNT - 1
        internetDetailsContentManager.hasMoreWifiEntries = true
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            addNetworkButton?.let { assertThat(it.visibility).isEqualTo(View.GONE) }
        }
    }

    @Test
    fun updateContent_disallowChangeWifiState_disableWifiSwitch() {
        whenever(WifiEnterpriseRestrictionUtils.isChangeWifiStateAllowed(mContext))
            .thenReturn(false)
        createView()
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            // Disable Wi-Fi switch and show restriction message in summary.
            assertThat(wifiToggleSwitch!!.isEnabled).isFalse()
            assertThat(wifiToggleSummary!!.visibility).isEqualTo(View.VISIBLE)
            assertThat(wifiToggleSummary!!.text.length).isNotEqualTo(0)
        }
    }

    @Test
    fun updateContent_allowChangeWifiState_enableWifiSwitch() {
        whenever(WifiEnterpriseRestrictionUtils.isChangeWifiStateAllowed(mContext)).thenReturn(true)
        createView()
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            // Enable Wi-Fi switch and hide restriction message in summary.
            assertThat(wifiToggleSwitch!!.isEnabled).isTrue()
            assertThat(wifiToggleSummary!!.visibility).isEqualTo(View.GONE)
        }
    }

    @Test
    fun updateContent_showSecondaryDataSub_twice_noCrash() {
        whenever(internetDetailsContentController.activeAutoSwitchNonDdsSubId).thenReturn(1)
        whenever(internetDetailsContentController.hasActiveSubIdOnDds()).thenReturn(true)
        whenever(internetDetailsContentController.isAirplaneModeEnabled).thenReturn(false)

        // First call inflates the stub
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        // Second call should not attempt to inflate the stub again and should not crash
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()
    }

    @Test
    fun internetContentData_nullValue_noCrash() {
        // Use value instead of postValue to trigger the observer immediately on the UI thread
        internetDetailsContentManager.internetContentData.value = null
    }

    @Test
    fun updateContent_showSecondaryDataSub() {
        whenever(internetDetailsContentController.activeAutoSwitchNonDdsSubId).thenReturn(1)
        whenever(internetDetailsContentController.hasActiveSubIdOnDds()).thenReturn(true)
        whenever(internetDetailsContentController.isAirplaneModeEnabled).thenReturn(false)

        clearInvocations(internetDetailsContentController)
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            val primaryLayout =
                if (isInDialog) {
                    contentView.requireViewById<LinearLayout>(R.id.mobile_network_layout)
                } else {
                    contentView.requireViewById<LinearLayout>(R.id.mobile_connected_layout)
                }
            val secondaryLayout =
                contentView.requireViewById<LinearLayout>(R.id.secondary_mobile_network_layout)

            bgExecutor.runAllReady()
            verify(internetDetailsContentController).getMobileNetworkSummary(1)
            assertThat(primaryLayout.background).isNotEqualTo(secondaryLayout.background)
        }
    }

    @Test
    fun updateContent_wifiOn_hideWifiScanNotify() {
        // The preconditions WiFi ON and WiFi entries are already in setUp()
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(wifiScanNotify!!.visibility).isEqualTo(View.GONE)
        }

        assertThat(wifiScanNotify!!.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun updateContent_wifiOffAndWifiScanOff_hideWifiScanNotify() {
        whenever(internetDetailsContentController.isWifiEnabled).thenReturn(false)
        whenever(internetDetailsContentController.isWifiScanEnabled).thenReturn(false)
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(wifiScanNotify!!.visibility).isEqualTo(View.GONE)
        }

        assertThat(wifiScanNotify!!.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun updateContent_wifiOffAndWifiScanOnAndDeviceLocked_hideWifiScanNotify() {
        whenever(internetDetailsContentController.isWifiEnabled).thenReturn(false)
        whenever(internetDetailsContentController.isWifiScanEnabled).thenReturn(true)
        whenever(internetDetailsContentController.isDeviceLocked).thenReturn(true)
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(wifiScanNotify!!.visibility).isEqualTo(View.GONE)
        }

        assertThat(wifiScanNotify!!.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun updateContent_wifiOffAndWifiScanOnAndDeviceUnlocked_showWifiScanNotify() {
        whenever(internetDetailsContentController.isWifiEnabled).thenReturn(false)
        whenever(internetDetailsContentController.isWifiScanEnabled).thenReturn(true)
        whenever(internetDetailsContentController.isDeviceLocked).thenReturn(false)
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(wifiScanNotify!!.visibility).isEqualTo(View.VISIBLE)
            val wifiScanNotifyText =
                contentView.requireViewById<TextView>(R.id.wifi_scan_notify_text)
            assertThat(wifiScanNotifyText.text.length).isNotEqualTo(0)
            assertThat(wifiScanNotifyText.movementMethod).isNotNull()
        }
    }

    @Test
    fun updateContent_wifiIsDisabled_uncheckWifiSwitch() {
        whenever(internetDetailsContentController.isWifiEnabled).thenReturn(false)
        wifiToggleSwitch!!.isChecked = true
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(wifiToggleSwitch!!.isChecked).isFalse()
        }
    }

    @Test
    @Throws(Exception::class)
    fun updateContent_wifiIsEnabled_checkWifiSwitch() {
        whenever(internetDetailsContentController.isWifiEnabled).thenReturn(true)
        wifiToggleSwitch!!.isChecked = false
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(wifiToggleSwitch!!.isChecked).isTrue()
        }
    }

    @Test
    @DisableFlags(Flags.FLAG_QS_WIFI_CONFIG, Flags.FLAG_QS_WIFI_MULTIUSER)
    fun onClickSeeMoreButton_clickSeeAll_verifyLaunchNetworkSetting() {
        seeAll!!.performClick()

        verify(internetDetailsContentController)
            .launchNetworkSetting(contentView.requireViewById(R.id.see_all_layout))
    }

    @Test
    @EnableFlags(Flags.FLAG_QS_WIFI_MULTIUSER)
    fun onClickSeeMoreButton_clickSeeAll_verifExpandWifiList() {
        internetDetailsContentManager.hasSeeAllClicked = true
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(seeAll!!.visibility).isEqualTo(View.GONE)
            verify(internetAdapter).setShowAllWifi()
        }
    }

    @Test
    fun onWifiScan_isScanTrue_setProgressBarAnimatingTrue() {
        val progressBar =
            contentView.requireViewById<android.widget.ProgressBar>(R.id.wifi_searching_progress)
        internetDetailsContentManager.isProgressBarAnimating = false

        internetDetailsContentManager.internetDetailsCallback.onWifiScan(true)

        assertThat(internetDetailsContentManager.isProgressBarAnimating).isTrue()
        assertThat(progressBar.visibility).isEqualTo(View.VISIBLE)
        assertThat(progressBar.isIndeterminate).isTrue()
    }

    @Test
    fun onWifiScan_isScanFalse_setProgressBarAnimatingFalse() {
        val progressBar =
            contentView.requireViewById<android.widget.ProgressBar>(R.id.wifi_searching_progress)
        internetDetailsContentManager.isProgressBarAnimating = true

        internetDetailsContentManager.internetDetailsCallback.onWifiScan(false)

        assertThat(internetDetailsContentManager.isProgressBarAnimating).isFalse()
        assertThat(progressBar.visibility).isEqualTo(View.VISIBLE)
        assertThat(progressBar.isIndeterminate).isFalse()
        assertThat(progressBar.progress).isEqualTo(progressBar.max)
    }

    @Test
    fun onWifiScan_isScanFalse_applyStaticColorTint() {
        val progressBar =
            contentView.requireViewById<android.widget.ProgressBar>(R.id.wifi_searching_progress)

        internetDetailsContentManager.internetDetailsCallback.onWifiScan(true)
        internetDetailsContentManager.internetDetailsCallback.onWifiScan(false)

        assertThat(progressBar.progressTintList).isNotNull()
    }

    @Test
    fun onWifiScan_isScanTrue_removeStaticColorTint() {
        val progressBar =
            contentView.requireViewById<android.widget.ProgressBar>(R.id.wifi_searching_progress)

        internetDetailsContentManager.internetDetailsCallback.onWifiScan(true)
        internetDetailsContentManager.internetDetailsCallback.onWifiScan(false)
        internetDetailsContentManager.internetDetailsCallback.onWifiScan(true)

        assertThat(progressBar.progressTintList).isNull()
    }

    @Test
    fun updateContent_shareWifiIntentNull_hideButton() {
        whenever(
                internetDetailsContentController.getConfiguratorQrCodeGeneratorIntentOrNull(
                    ArgumentMatchers.any()
                )
            )
            .thenReturn(null)
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(sharedWifiButton?.visibility).isEqualTo(View.GONE)
        }
    }

    @Test
    fun updateContent_shareWifiShareable_showButton() {
        whenever(
                internetDetailsContentController.getConfiguratorQrCodeGeneratorIntentOrNull(
                    ArgumentMatchers.any()
                )
            )
            .thenReturn(Intent())
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(sharedWifiButton?.visibility).isEqualTo(View.VISIBLE)
        }
    }

    @Test
    fun updateTitleAndSubtitle() {
        assertThat(internetDetailsContentManager.title).isEqualTo("Internet")
        assertThat(internetDetailsContentManager.subTitle).isEqualTo("")

        whenever(internetDetailsContentController.getDialogTitleText(true)).thenReturn("New title")
        whenever(internetDetailsContentController.getSubtitleText(ArgumentMatchers.anyBoolean()))
            .thenReturn("New subtitle")

        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(internetDetailsContentManager.title).isEqualTo("New title")
            assertThat(internetDetailsContentManager.subTitle).isEqualTo("New subtitle")
        }
    }

    @Test
    fun turnOffProgressBarWhenWifiDisabled() {
        whenever(internetDetailsContentController.isWifiEnabled).thenReturn(false)
        internetDetailsContentManager.isProgressBarAnimating = true

        internetDetailsContentManager.updateContent(false)

        bgExecutor.runAllReady()
        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(internetDetailsContentManager.isProgressBarAnimating).isFalse()
        }
    }

    @Test
    fun updateContent_satelliteStarted_showSatelliteUI() {
        whenever(internetDetailsContentController.getCurrentSatelliteState())
            .thenReturn(InternetDetailsContentController.SATELLITE_STARTED)
        whenever(internetDetailsContentController.hasActiveSubIdOnDds()).thenReturn(true)
        mobileDataLayout!!.visibility = View.GONE

        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(mobileDataLayout!!.visibility).isEqualTo(View.VISIBLE)
            val mobileTitle = contentView.requireViewById<TextView>(R.id.mobile_title)
            assertThat(mobileTitle.text)
                .isEqualTo(mContext.getText(R.string.satellite_network_title_text))
        }
    }

    @Test
    @DisableFlags(com.android.internal.telephony.flags.Flags.FLAG_NEW_SATELLITE_ICON)
    fun updateContent_satelliteConnected_showSatelliteUIAndConnected() {
        whenever(internetDetailsContentController.getCurrentSatelliteState())
            .thenReturn(InternetDetailsContentController.SATELLITE_CONNECTED)
        whenever(internetDetailsContentController.hasActiveSubIdOnDds()).thenReturn(true)
        mobileDataLayout!!.visibility = View.GONE

        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(mobileDataLayout!!.visibility).isEqualTo(View.VISIBLE)
            val mobileTitle = contentView.requireViewById<TextView>(R.id.mobile_title)
            assertThat(mobileTitle.text)
                .isEqualTo(mContext.getText(R.string.satellite_network_title_text))
            val mobileSummary = contentView.requireViewById<TextView>(R.id.mobile_summary)
            assertThat(mobileSummary.visibility).isEqualTo(View.VISIBLE)
            assertThat(mobileSummary.text)
                .isEqualTo(mContext.getText(R.string.mobile_data_connection_active))
        }
    }

    @Test
    @EnableFlags(com.android.internal.telephony.flags.Flags.FLAG_NEW_SATELLITE_ICON)
    fun updateContent_satelliteConnected_showSatelliteUIAndConnectedWithNewString() {
        whenever(internetDetailsContentController.getCurrentSatelliteState())
            .thenReturn(InternetDetailsContentController.SATELLITE_CONNECTED)
        whenever(internetDetailsContentController.hasActiveSubIdOnDds()).thenReturn(true)
        mobileDataLayout!!.visibility = View.GONE

        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(mobileDataLayout!!.visibility).isEqualTo(View.VISIBLE)
            val mobileTitle = contentView.requireViewById<TextView>(R.id.mobile_title)
            assertThat(mobileTitle.text)
                .isEqualTo(mContext.getText(R.string.satellite_network_title_text))
            val mobileSummary = contentView.requireViewById<TextView>(R.id.mobile_summary)
            assertThat(mobileSummary.visibility).isEqualTo(View.VISIBLE)

            val strConnected = mContext.getString(R.string.mobile_data_connection_active)
            val strSat = mContext.getString(com.android.internal.R.string.satellite_indicator)
            assertThat(mobileSummary.text)
                .isEqualTo(
                    mContext.getString(
                        com.android.settingslib.R.string.preference_summary_default_combination,
                        strConnected,
                        strSat,
                    )
                )
        }
    }

    @Test
    fun updateContent_satelliteStarted_mobileSwitchDisabled() {
        whenever(internetDetailsContentController.hasActiveSubIdOnDds()).thenReturn(true)
        whenever(internetDetailsContentController.isCarrierNetworkActive).thenReturn(true)
        whenever(internetDetailsContentController.isMobileDataEnabled).thenReturn(false)
        whenever(internetDetailsContentController.getCurrentSatelliteState())
            .thenReturn(InternetDetailsContentController.SATELLITE_STARTED)
        mobileToggleSwitch!!.isChecked = false
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(mobileToggleSwitch!!.visibility).isEqualTo(View.INVISIBLE)
        }
    }

    @Test
    fun updateContent_satelliteConnected_mobileSwitchDisabled() {
        whenever(internetDetailsContentController.hasActiveSubIdOnDds()).thenReturn(true)
        whenever(internetDetailsContentController.isCarrierNetworkActive).thenReturn(true)
        whenever(internetDetailsContentController.isMobileDataEnabled).thenReturn(false)
        whenever(internetDetailsContentController.getCurrentSatelliteState())
            .thenReturn(InternetDetailsContentController.SATELLITE_CONNECTED)
        mobileToggleSwitch!!.isChecked = false
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(mobileToggleSwitch!!.visibility).isEqualTo(View.INVISIBLE)
        }
    }

    @Test
    fun updateContent_canConfigMobileDataFalse_mobileDataToggleHidden() {
        internetDetailsContentManager =
            InternetDetailsContentManager(
                internetDetailsContentController,
                canConfigMobileData = false,
                canConfigWifi = true,
                isInDialog = isInDialog,
                uiEventLogger = mock<UiEventLogger>(),
                handler = handler,
                backgroundExecutor = bgExecutor,
                keyguard = keyguard,
                userRepository = userRepository,
            )
        internetDetailsContentManager.bind(contentView, null, testScope)
        mobileToggleSwitch = contentView.requireViewById(R.id.mobile_toggle)

        whenever(internetDetailsContentController.hasActiveSubIdOnDds()).thenReturn(true)
        whenever(internetDetailsContentController.isCarrierNetworkActive).thenReturn(true)

        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(mobileToggleSwitch!!.visibility).isEqualTo(View.INVISIBLE)
        }
    }

    @Test
    fun updateContent_headlessSystemUser_hideWifiContent() {
        whenever(internetDetailsContentController.isHeadlessSystemUser).thenReturn(true)

        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            expect.that(connectedWifi!!.visibility).isEqualTo(View.GONE)
            expect.that(wifiList!!.visibility).isEqualTo(View.GONE)
            expect.that(seeAll!!.visibility).isEqualTo(View.GONE)
        }
    }

    fun testButtonsAnnounceAsButton() {
        // Test Share Wi-Fi button
        val shareWifiNodeInfo = AccessibilityNodeInfoCompat.obtain()
        ViewCompat.onInitializeAccessibilityNodeInfo(sharedWifiButton!!, shareWifiNodeInfo)
        assertThat(shareWifiNodeInfo.className).isEqualTo(Button::class.java.name)

        val seeAllNodeInfo = AccessibilityNodeInfoCompat.obtain()
        ViewCompat.onInitializeAccessibilityNodeInfo(seeAll!!, seeAllNodeInfo)
        assertThat(seeAllNodeInfo.className).isEqualTo(Button::class.java.name)

        // Test Add Network Button
        addNetworkButton?.let { button ->
            val addNetworkNodeInfo = AccessibilityNodeInfoCompat.obtain()
            ViewCompat.onInitializeAccessibilityNodeInfo(button, addNetworkNodeInfo)
            assertThat(addNetworkNodeInfo.className).isEqualTo(Button::class.java.name)
        }
    }

    companion object {
        private const val TITLE = "Internet"
        private const val MOBILE_NETWORK_TITLE = "Mobile Title"
        private const val MOBILE_NETWORK_SUMMARY = "Mobile Summary"
        private const val WIFI_TITLE = "Connected Wi-Fi Title"
        private const val WIFI_SUMMARY = "Connected Wi-Fi Summary"

        @JvmStatic
        @Parameters(name = "isInDialog={0}")
        fun data(): Iterable<Any> {
            return listOf(true, false)
        }
    }
}
