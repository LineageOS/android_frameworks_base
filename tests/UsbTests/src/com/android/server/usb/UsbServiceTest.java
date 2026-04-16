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

package com.android.server.usb;

import static android.hardware.usb.UsbOperationInternal.USB_OPERATION_ERROR_INTERNAL;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.hardware.usb.IUsbManagerInternal;
import android.hardware.usb.IUsbOperationInternal;
import android.hardware.usb.UsbOperationInternal;
import android.hardware.usb.flags.Flags;
import android.hardware.usb.UsbPort;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserManager;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import com.android.internal.widget.LockPatternUtils;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.android.server.usb.UsbService.IUsbOperationInternalProvider;
import com.android.server.usb.UsbService.UsbManagerInternalImpl;

/** Tests for {@link com.android.server.usb.UsbService} atest UsbTests:UsbServiceTest */
@RunWith(AndroidJUnit4.class)
public class UsbServiceTest {

    @Mock private Context mContext;
    @Mock private UsbPortManager mUsbPortManager;
    @Mock private UsbAlsaManager mUsbAlsaManager;
    @Mock private UserManager mUserManager;
    @Mock private UsbSettingsManager mUsbSettingsManager;
    @Mock private IUsbOperationInternal mCallback;
    @Mock private IUsbOperationInternalProvider mUsbOperationInternalProvider;
    @Mock private UserManagerInternal mUserManagerInternal;
    @Mock private Resources mResources;

    private static final String TEST_PORT_ID = "1";

    private static final String TEST_PORT_ID_2 = "2";

    private static final int TEST_TRANSACTION_ID = 1;

    private static final int TEST_FIRST_CALLER_ID = 1000;

    private static final int TEST_SECOND_CALLER_ID = 2000;

    private static final int TEST_INTERNAL_REQUESTER_REASON_1 = 0;

    private static final int TEST_INTERNAL_REQUESTER_REASON_2 = 1;

    private static final int TEST_INTERNAL_REQUESTER_INVALID_REASON = 999;

    private UsbService mUsbService;

    private IUsbManagerInternal mIUsbManagerInternal;

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Before
    public void setUp() {
        mSetFlagsRule.enableFlags(Flags.FLAG_ENABLE_USB_DATA_SIGNAL_STAKING);
        mSetFlagsRule.enableFlags(Flags.FLAG_ENABLE_USB_DATA_SIGNAL_STAKING_INTERNAL);
        LocalServices.removeAllServicesForTest();
        MockitoAnnotations.initMocks(this);
        LocalServices.addService(UserManagerInternal.class, mUserManagerInternal);

        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.getBoolean(anyInt())).thenReturn(false);

        mUsbService =
                new UsbService(
                        mContext,
                        mUsbPortManager,
                        mUsbAlsaManager,
                        mUserManager,
                        mUsbSettingsManager,
                        mUsbOperationInternalProvider);
        mIUsbManagerInternal = LocalServices.getService(IUsbManagerInternal.class);
        assertWithMessage("LocalServices.getService(IUsbManagerInternal.class)")
                .that(mIUsbManagerInternal)
                .isNotNull();
    }

    private void assertToggleUsbSuccessfully(
            int requester, boolean enable, boolean isInternalRequest) {
        assertTrue(
                mUsbService.enableUsbDataInternal(
                        TEST_PORT_ID,
                        enable,
                        TEST_TRANSACTION_ID,
                        mCallback,
                        requester,
                        isInternalRequest));

        verify(mUsbPortManager)
                .enableUsbData(TEST_PORT_ID, enable, TEST_TRANSACTION_ID, mCallback, null);
        verifyNoMoreInteractions(mCallback);

        clearInvocations(mUsbPortManager);
        clearInvocations(mCallback);
    }

    private void assertToggleUsbFailed(int requester, boolean enable, boolean isInternalRequest)
            throws Exception {
        assertFalse(
                mUsbService.enableUsbDataInternal(
                        TEST_PORT_ID,
                        enable,
                        TEST_TRANSACTION_ID,
                        mCallback,
                        requester,
                        isInternalRequest));

        verifyNoMoreInteractions(mUsbPortManager);
        verify(mCallback).onOperationComplete(USB_OPERATION_ERROR_INTERNAL);

        clearInvocations(mUsbPortManager);
        clearInvocations(mCallback);
    }

    /** Verify enableUsbData successfully disables USB port without error */
    @Test
    public void disableUsb_successfullyDisable() {
        assertToggleUsbSuccessfully(TEST_FIRST_CALLER_ID, false, false);
    }

    /** Verify enableUsbData successfully enables USB port without error given no other stakers */
    @Test
    public void enableUsbWhenNoOtherStakers_successfullyEnable() {
        assertToggleUsbSuccessfully(TEST_FIRST_CALLER_ID, true, false);
    }

    /** Verify enableUsbData does not enable USB port if other stakers are present */
    @Test
    public void enableUsbPortWithOtherStakers_failsToEnable() throws Exception {
        assertToggleUsbSuccessfully(TEST_FIRST_CALLER_ID, false, false);

        assertToggleUsbFailed(TEST_SECOND_CALLER_ID, true, false);
    }

    /** Verify enableUsbData successfully enables USB port when the last staker is removed */
    @Test
    public void enableUsbByTheOnlyStaker_successfullyEnable() {
        assertToggleUsbSuccessfully(TEST_FIRST_CALLER_ID, false, false);

        assertToggleUsbSuccessfully(TEST_FIRST_CALLER_ID, true, false);
    }

    /**
     * Verify enableUsbDataWhileDockedInternal does not enable USB port if other stakers are present
     */
    @Test
    public void enableUsbWhileDockedWhenThereAreOtherStakers_failsToEnable()
            throws RemoteException {
        assertToggleUsbSuccessfully(TEST_FIRST_CALLER_ID, false, false);

        mUsbService.enableUsbDataWhileDockedInternal(
                TEST_PORT_ID, TEST_TRANSACTION_ID, mCallback, TEST_SECOND_CALLER_ID, false);

        verifyNoMoreInteractions(mUsbPortManager);
        verify(mCallback).onOperationComplete(USB_OPERATION_ERROR_INTERNAL);
    }

    /**
     * Verify enableUsbDataWhileDockedInternal does enable USB port if other stakers are not present
     */
    @Test
    public void enableUsbWhileDockedWhenThereAreNoStakers_SuccessfullyEnable() {
        mUsbService.enableUsbDataWhileDockedInternal(
                TEST_PORT_ID, TEST_TRANSACTION_ID, mCallback, TEST_SECOND_CALLER_ID, false);

        verify(mUsbPortManager)
                .enableUsbDataWhileDocked(TEST_PORT_ID, TEST_TRANSACTION_ID, mCallback, null);
        verifyNoMoreInteractions(mCallback);
    }

    /**
     * Verify enableUsbData successfully enables USB port without error given no other stakers for
     * internal requests
     */
    @Test
    public void enableUsbWhenNoOtherStakers_forInternalRequest_successfullyEnable() {
        assertToggleUsbSuccessfully(TEST_INTERNAL_REQUESTER_REASON_1, true, true);
    }

    /**
     * Verify enableUsbData does not enable USB port if other internal stakers are present for
     * internal requests
     */
    @Test
    public void enableUsbPortWithOtherInternalStakers_forInternalRequest_failsToEnable()
            throws Exception {
        assertToggleUsbSuccessfully(TEST_INTERNAL_REQUESTER_REASON_1, false, true);

        assertToggleUsbFailed(TEST_INTERNAL_REQUESTER_REASON_2, true, true);
    }

    /**
     * Verify enableUsbData does not enable USB port if other external stakers are present for
     * internal requests
     */
    @Test
    public void enableUsbPortWithOtherExternalStakers_forInternalRequest_failsToEnable()
            throws Exception {
        assertToggleUsbSuccessfully(TEST_FIRST_CALLER_ID, false, false);

        assertToggleUsbFailed(TEST_INTERNAL_REQUESTER_REASON_2, true, true);
    }

    /**
     * Verify enableUsbData does not enable USB port if other internal stakers are present for
     * external requests
     */
    @Test
    public void enableUsbPortWithOtherInternalStakers_forExternalRequest_failsToEnable()
            throws Exception {
        assertToggleUsbSuccessfully(TEST_INTERNAL_REQUESTER_REASON_1, false, true);

        assertToggleUsbFailed(TEST_FIRST_CALLER_ID, true, false);
    }

    /**
     * Verify enableUsbData successfully enables USB port when the last staker is removed for
     * internal requests
     */
    @Test
    public void enableUsbByTheOnlyStaker_forInternalRequest_successfullyEnable() {
        assertToggleUsbSuccessfully(TEST_INTERNAL_REQUESTER_REASON_1, false, false);

        assertToggleUsbSuccessfully(TEST_INTERNAL_REQUESTER_REASON_1, true, false);
    }

    @Test
    public void usbManagerInternal_enableUsbDataSignal_successfullyEnabled() {
        assertTrue(runInternalUsbDataSignalTest(true, true, true));
    }

    @Test
    public void usbManagerInternal_enableUsbDataSignal_successfullyDisabled() {
        assertTrue(runInternalUsbDataSignalTest(false, true, true));
    }

    @Test
    public void usbManagerInternal_enableUsbDataSignal_returnsFalseIfOnePortFails() {
        assertFalse(runInternalUsbDataSignalTest(true, true, false));
    }

    private boolean runInternalUsbDataSignalTest(
            boolean desiredEnableState, boolean portOneSuccess, boolean portTwoSuccess) {
        UsbPort port = mock(UsbPort.class);
        UsbPort port2 = mock(UsbPort.class);
        setupUsbOperationInternalProvider(true, true);
        when(port.getId()).thenReturn(TEST_PORT_ID);
        when(port2.getId()).thenReturn(TEST_PORT_ID_2);
        when(mUsbPortManager.getPorts()).thenReturn(new UsbPort[] {port, port2});
        when(mUsbPortManager.enableUsbData(
                        eq(TEST_PORT_ID),
                        eq(desiredEnableState),
                        anyInt(),
                        any(IUsbOperationInternal.class),
                        isNull()))
                .thenReturn(portOneSuccess);
        when(mUsbPortManager.enableUsbData(
                        eq(TEST_PORT_ID_2),
                        eq(desiredEnableState),
                        anyInt(),
                        any(IUsbOperationInternal.class),
                        isNull()))
                .thenReturn(portTwoSuccess);
        try {
            boolean result =
                    mIUsbManagerInternal.enableUsbDataSignal(
                            desiredEnableState, TEST_INTERNAL_REQUESTER_REASON_1);
            clearInvocations(mUsbPortManager);
            return result;
        } catch (RemoteException e) {
            fail("RemoteException thrown when calling enableUsbDataSignal");
            return false;
        }
    }

    private void setupUsbOperationInternalProvider(boolean portOneSuccess, boolean portTwoSuccess) {
        UsbOperationInternal usbOperationInternalPortOne = mock(UsbOperationInternal.class);
        UsbOperationInternal usbOperationInternalPortTwo = mock(UsbOperationInternal.class);
        when(usbOperationInternalPortOne.getStatus())
                .thenReturn(
                        portOneSuccess
                                ? UsbOperationInternal.USB_OPERATION_SUCCESS
                                : UsbOperationInternal.USB_OPERATION_ERROR_INTERNAL);
        when(usbOperationInternalPortTwo.getStatus())
                .thenReturn(
                        portTwoSuccess
                                ? UsbOperationInternal.USB_OPERATION_SUCCESS
                                : UsbOperationInternal.USB_OPERATION_ERROR_INTERNAL);
        when(mUsbOperationInternalProvider.getUsbOperationInternal(anyInt(), eq(TEST_PORT_ID)))
                .thenReturn(usbOperationInternalPortOne);
        when(mUsbOperationInternalProvider.getUsbOperationInternal(anyInt(), eq(TEST_PORT_ID_2)))
                .thenReturn(usbOperationInternalPortTwo);
    }

    @Test
    public void usbManagerInternal_enableUsbDataSignal_returnsFalseIfOnePortCallbackFails()
            throws RemoteException {
        boolean desiredEnableState = true;
        UsbPort port = mock(UsbPort.class);
        UsbPort port2 = mock(UsbPort.class);
        setupUsbOperationInternalProvider(true, false);
        when(port.getId()).thenReturn(TEST_PORT_ID);
        when(port2.getId()).thenReturn(TEST_PORT_ID_2);
        when(mUsbPortManager.getPorts()).thenReturn(new UsbPort[] {port, port2});
        when(mUsbPortManager.enableUsbData(
                        eq(TEST_PORT_ID),
                        eq(desiredEnableState),
                        anyInt(),
                        any(IUsbOperationInternal.class),
                        isNull()))
                .thenReturn(true);
        when(mUsbPortManager.enableUsbData(
                        eq(TEST_PORT_ID_2),
                        eq(desiredEnableState),
                        anyInt(),
                        any(IUsbOperationInternal.class),
                        isNull()))
                .thenReturn(true);

        boolean result =
                mIUsbManagerInternal.enableUsbDataSignal(
                        desiredEnableState, TEST_INTERNAL_REQUESTER_REASON_1);

        assertFalse(result);
        clearInvocations(mUsbPortManager);
    }

    @Test(expected = IllegalArgumentException.class)
    public void usbManagerInternal_enableUsbDataSignal_throwsExceptionIfInvalidDisableReason()
            throws IllegalArgumentException, RemoteException {
        mIUsbManagerInternal.enableUsbDataSignal(true, TEST_INTERNAL_REQUESTER_INVALID_REASON);
    }

    @Test
    public void onStrongAuthRequiredChanged_lockdownTriggered_disablesUsb() {
        int userId = 10;
        UsbPort port = mock(UsbPort.class);
        when(port.getId()).thenReturn(TEST_PORT_ID);
        when(mUsbPortManager.getPorts()).thenReturn(new UsbPort[] {port});

        UsbService.StrongAuthTracker tracker =
                spy(mUsbService.new StrongAuthTracker(mContext, Looper.getMainLooper()));
        doReturn(LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN)
                .when(tracker)
                .getStrongAuthForUser(userId);

        tracker.onStrongAuthRequiredChanged(userId);

        verify(mUsbPortManager)
                .enableUsbData(eq(TEST_PORT_ID), eq(false), anyInt(), any(), isNull());
    }

    @Test
    public void onStrongAuthRequiredChanged_lockdownCleared_mainUser_enablesUsb() {
        int userId = 10;
        UsbPort port = mock(UsbPort.class);
        when(port.getId()).thenReturn(TEST_PORT_ID);
        when(mUsbPortManager.getPorts()).thenReturn(new UsbPort[] {port});
        when(mUserManagerInternal.getMainUserId()).thenReturn(userId);
        UsbService.StrongAuthTracker tracker =
                spy(mUsbService.new StrongAuthTracker(mContext, Looper.getMainLooper()));
        doReturn(LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN)
                .when(tracker)
                .getStrongAuthForUser(userId);
        tracker.onStrongAuthRequiredChanged(userId);
        clearInvocations(mUsbPortManager);
        doReturn(LockPatternUtils.StrongAuthTracker.STRONG_AUTH_NOT_REQUIRED)
                .when(tracker)
                .getStrongAuthForUser(userId);

        tracker.onStrongAuthRequiredChanged(userId);

        verify(mUsbPortManager)
                .enableUsbData(eq(TEST_PORT_ID), eq(true), anyInt(), any(), isNull());
    }

    @Test
    public void onStrongAuthRequiredChanged_lockdownCleared_nonMainUser_doesNotEnableUsb() {
        int userId = 11;
        UsbPort port = mock(UsbPort.class);
        when(port.getId()).thenReturn(TEST_PORT_ID);
        when(mUsbPortManager.getPorts()).thenReturn(new UsbPort[] {port});
        when(mUserManagerInternal.getMainUserId()).thenReturn(userId - 1);
        UsbService.StrongAuthTracker tracker =
                spy(mUsbService.new StrongAuthTracker(mContext, Looper.getMainLooper()));
        doReturn(LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN)
                .when(tracker)
                .getStrongAuthForUser(userId);
        tracker.onStrongAuthRequiredChanged(userId);
        clearInvocations(mUsbPortManager);
        doReturn(LockPatternUtils.StrongAuthTracker.STRONG_AUTH_NOT_REQUIRED)
                .when(tracker)
                .getStrongAuthForUser(userId);

        tracker.onStrongAuthRequiredChanged(userId);

        verifyNoMoreInteractions(mUsbPortManager);
    }
}
