/*
 * Copyright (C) 2025 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.usb

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.PermissionChecker
import android.content.pm.PackageItemInfo.DEFAULT_MAX_LABEL_SIZE_PX
import android.hardware.usb.IUsbManager
import android.hardware.usb.IUsbSerialReader
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.hardware.usb.flags.Flags.FLAG_ENABLE_PERSISTENT_USB_DEVICE_PERMISSIONS
import android.os.IBinder
import android.os.ServiceManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.text.Html
import android.text.TextUtils.SAFE_STRING_FLAG_FIRST_LINE
import android.text.TextUtils.SAFE_STRING_FLAG_TRIM
import android.view.View
import android.view.WindowManager
import androidx.test.rule.ActivityTestRule
import androidx.test.runner.intercepting.SingleActivityFactory
import com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession
import com.android.internal.app.AlertController
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Answers
import org.mockito.MockitoSession
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness

abstract class UsbDialogActivityTest<T> : SysuiTestCase()
    where T : Activity, T : UsbDialogActivityTest.UsbDialogActivityTestable {
    companion object {
        protected const val PACKAGE: String = "com.android.systemui.tests"
        protected const val EXTRA_PACKAGE: String = "com.android.systemui"
        protected const val EXTRA_UID: Int = 12

        protected const val NAME: String = "name"
        protected const val VENDOR_ID: Int = 3
        protected const val PRODUCT_ID: Int = 4
        protected const val CLASS: Int = 5
        protected const val SUB_CLASS: Int = 6
        protected const val PROTOCOL: Int = 7
        protected const val MANUFACTURER: String = "manufacturer"
        protected const val MODEL: String = "model"
        protected const val USB_ACCESSORY_DESCRIPTION: String = "description"
        protected const val USB_DEVICE_PRODUCT_NAME: String = "productName"
        protected const val VERSION: String = "version"
        protected const val URI: String = "uri"
        protected const val SERIAL_NUMBER: String = "serialNumber"
        protected const val HAS_MIDI: Boolean = false
        protected const val HAS_VIDEO_PLAYBACK: Boolean = false
        protected const val HAS_VIDEO_CAPTURE: Boolean = false
    }

    interface UsbDialogActivityTestable {
        fun getAlertParams(): AlertController.AlertParams
    }

    protected val mMessage: UsbAudioWarningDialogMessage = UsbAudioWarningDialogMessage()
    protected val mUsbService: IUsbManager = mock<IUsbManager>()
    protected var mUsbDevice: UsbDevice? = null
    protected var mUsbAccessory: UsbAccessory? = null
    protected lateinit var mAppName: CharSequence
    protected lateinit var mAlertParams: AlertController.AlertParams

    private lateinit var mMockSession: MockitoSession
    private val mSerialReader: IUsbSerialReader =
        object : IUsbSerialReader.Stub() {
            override fun getSerial(packageName: String): String {
                return SERIAL_NUMBER
            }
        }

    protected abstract fun createActivity(): T

    protected abstract fun getActivityClass(): Class<T>

    protected abstract fun getTitleResId(): Int

    protected open fun createIntent(canBeDefault: Boolean): Intent {
        return Intent(mContext, getActivityClass()).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(UsbManager.EXTRA_CAN_BE_DEFAULT, canBeDefault)
        }
    }

    @Rule
    @JvmField
    val activityRule =
        ActivityTestRule(
            // To avoid type erasure, getActivityClass() is explicitly passed to the factory's
            // constructor.
            object : SingleActivityFactory<T>(getActivityClass()) {
                override fun create(intent: Intent?): T {
                    return createActivity()
                }
            },
            false,
            false,
        )

    @Before
    fun setUp() {
        mMockSession =
            mockitoSession()
                .strictness(Strictness.LENIENT)
                .mockStatic(PermissionChecker::class.java)
                .mockStatic(ServiceManager::class.java, Answers.CALLS_REAL_METHODS)
                .startMocking()

        val binder = mock<IBinder>()
        whenever(ServiceManager.getService(android.content.Context.USB_SERVICE)).thenReturn(binder)
        whenever(binder.queryLocalInterface(any())).thenReturn(mUsbService)
    }

    @After
    fun tearDown() {
        activityRule.finishActivity()
        mMockSession.finishMocking()

        mUsbDevice = null
        mUsbAccessory = null
    }

    private fun createDeviceIntent(
        canBeDefault: Boolean,
        hasAudioPlayback: Boolean,
        hasAudioCapture: Boolean,
    ): Intent {
        mUsbDevice =
            UsbDevice.Builder(
                    NAME,
                    VENDOR_ID,
                    PRODUCT_ID,
                    CLASS,
                    SUB_CLASS,
                    PROTOCOL,
                    MANUFACTURER,
                    USB_DEVICE_PRODUCT_NAME,
                    VERSION,
                    emptyArray<UsbConfiguration>(),
                    SERIAL_NUMBER,
                    hasAudioPlayback,
                    hasAudioCapture,
                    HAS_MIDI,
                    HAS_VIDEO_PLAYBACK,
                    HAS_VIDEO_CAPTURE,
                )
                .build(mSerialReader)

        return createIntent(canBeDefault).putExtra(UsbManager.EXTRA_DEVICE, mUsbDevice)
    }

    private fun createAccessoryIntent(canBeDefault: Boolean): Intent {
        mUsbAccessory =
            UsbAccessory(
                MANUFACTURER,
                MODEL,
                USB_ACCESSORY_DESCRIPTION,
                VERSION,
                URI,
                mSerialReader,
            )

        return createIntent(canBeDefault).putExtra(UsbManager.EXTRA_ACCESSORY, mUsbAccessory)
    }

    protected fun deviceConfiguration(
        isUsbDevice: Boolean,
        canBeDefault: Boolean = false,
        audioRecordingPermissionStatus: Int = android.content.pm.PackageManager.PERMISSION_DENIED,
        hasAudioPlayback: Boolean = false,
        hasAudioCapture: Boolean = false,
    ) {
        whenever(
                PermissionChecker.checkPermissionForPreflight(
                    any(),
                    eq(Manifest.permission.RECORD_AUDIO),
                    eq(PermissionChecker.PID_UNKNOWN),
                    eq(EXTRA_UID),
                    eq(EXTRA_PACKAGE),
                )
            )
            .thenReturn(audioRecordingPermissionStatus)

        if (isUsbDevice) {
            activityRule.launchActivity(
                createDeviceIntent(canBeDefault, hasAudioPlayback, hasAudioCapture)
            )
        } else {
            activityRule.launchActivity(createAccessoryIntent(canBeDefault))
        }

        mAppName =
            context.packageManager
                .getApplicationInfo(EXTRA_PACKAGE, 0)
                .loadSafeLabel(
                    context.packageManager,
                    DEFAULT_MAX_LABEL_SIZE_PX,
                    SAFE_STRING_FLAG_TRIM or SAFE_STRING_FLAG_FIRST_LINE,
                )
        mAlertParams = activityRule.activity.getAlertParams()
    }

    protected fun checkNewUiIsNotInitialized() {
        if (mAlertParams.mView != null) {
            assertThat(mAlertParams.mView.findViewById<View>(R.id.usb_device_dialog)).isNull()
        }
    }

    protected fun checkOldUiIsNotInitialized() {
        assertThat(mAlertParams.mTitle).isNull()
        assertThat(mAlertParams.mMessage).isNull()
        assertThat(mAlertParams.mPositiveButtonText).isNull()
        assertThat(mAlertParams.mNegativeButtonText).isNull()
        assertThat(mAlertParams.mPositiveButtonListener).isNull()
        assertThat(mAlertParams.mNegativeButtonListener).isNull()
    }

    protected fun getDeviceName(): String {
        if (mUsbDevice != null) {
            return USB_DEVICE_PRODUCT_NAME
        }

        return USB_ACCESSORY_DESCRIPTION
    }

    protected fun getTitle(): String {
        return Html.fromHtml(
                context.getString(getTitleResId(), mAppName, getDeviceName()),
                Html.FROM_HTML_MODE_LEGACY,
            )
            .toString()
    }

    protected fun getMessage(resId: Int): String {
        return context.getString(resId, mAppName, getDeviceName())
    }

    protected fun getAlwaysUseCheckboxMessage(): String {
        if (mUsbDevice != null) {
            return context.getString(R.string.always_use_device, mAppName, USB_DEVICE_PRODUCT_NAME)
        }

        return context.getString(R.string.always_use_accessory, mAppName, USB_ACCESSORY_DESCRIPTION)
    }

    protected fun prepareNewDialogCancel(isUsbDevice: Boolean) {
        deviceConfiguration(isUsbDevice = isUsbDevice)

        val dialogView: View = mAlertParams.mView
        activityRule.runOnUiThread {
            dialogView.findViewById<View>(R.id.usb_device_dialog_cancel_button).performClick()
        }
    }

    protected fun prepareNewDialogAllowOnlyThisTime(isUsbDevice: Boolean) {
        deviceConfiguration(isUsbDevice = isUsbDevice, canBeDefault = true)

        val dialogView: View = mAlertParams.mView
        activityRule.runOnUiThread {
            dialogView
                .findViewById<View>(R.id.usb_device_dialog_allow_only_this_time_button)
                .performClick()
        }
    }

    protected fun prepareNewDialogAllowOnlyThisTimeWithAlwaysUse(isUsbDevice: Boolean) {
        deviceConfiguration(isUsbDevice = isUsbDevice, canBeDefault = true)

        val dialogView: View = mAlertParams.mView
        activityRule.runOnUiThread {
            dialogView.findViewById<View>(com.android.internal.R.id.alwaysUse).performClick()
            dialogView
                .findViewById<View>(R.id.usb_device_dialog_allow_only_this_time_button)
                .performClick()
        }
    }

    // The 'Always allow' button is applicable only to UsbDevice.
    protected fun prepareNewDialogAlwaysAllow() {
        deviceConfiguration(isUsbDevice = true)

        val dialogView: View = mAlertParams.mView
        activityRule.runOnUiThread {
            dialogView.findViewById<View>(R.id.usb_device_dialog_always_allow_button).performClick()
        }
    }

    private fun verifyHideNonSystemOverlay() {
        deviceConfiguration(isUsbDevice = false)
        assertThat(
                activityRule.activity.window.attributes.privateFlags and
                    WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS
            )
            .isEqualTo(WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS)
    }

    @Test
    @DisableFlags(FLAG_ENABLE_PERSISTENT_USB_DEVICE_PERMISSIONS)
    fun testHideNonSystemOverlay() {
        verifyHideNonSystemOverlay()
    }

    @Test
    @EnableFlags(FLAG_ENABLE_PERSISTENT_USB_DEVICE_PERMISSIONS)
    fun testHideNonSystemOverlayNewDialog() {
        verifyHideNonSystemOverlay()
    }
}
