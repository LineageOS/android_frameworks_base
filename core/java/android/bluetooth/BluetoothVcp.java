/*
 *Copyright (c) 2020, The Linux Foundation. All rights reserved.
 *Not a contribution
 */

/*
 * Copyright 2018 The Android Open Source Project
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

package android.bluetooth;

import android.annotation.RequiresPermission;
import android.bluetooth.annotations.RequiresBluetoothConnectPermission;
import android.content.AttributionSource;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * This class provides the public APIs to control the Bluetooth VCP profile.
 *
 * <p>BluetoothVcp is a proxy object for controlling the Bluetooth VolumeControl
 * Service via IPC. Use {@link BluetoothAdapter#getProfileProxy} to get
 * the BluetoothVcp proxy object.
 *
 * {@hide}
 */
public final class BluetoothVcp implements BluetoothProfile {
    private static final String TAG = "BluetoothVcp";
    private static final boolean DBG = true;
    private static final boolean VDBG = true;

    /**
     * Intent used to broadcast the change in connection state of the VCP
     * profile.
     *
     * <p>This intent will have 3 extras:
     * <ul>
     * <li> {@link #EXTRA_STATE} - The current state of the profile. </li>
     * <li> {@link #EXTRA_PREVIOUS_STATE}- The previous state of the profile.</li>
     * <li> {@link BluetoothDevice#EXTRA_DEVICE} - The remote device. </li>
     * </ul>
     *
     * <p>{@link #EXTRA_STATE} or {@link #EXTRA_PREVIOUS_STATE} can be any of
     * {@link #STATE_DISCONNECTED}, {@link #STATE_CONNECTING},
     * {@link #STATE_CONNECTED}, {@link #STATE_DISCONNECTING}.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission to
     * receive.
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public static final String ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.vcp.profile.action.CONNECTION_STATE_CHANGED";

    /**
     * Intent used to broadcast the volume change of the Volume Renderer device
     *
     * <p>This intent will have 1 extras:
     * <ul>
     * <li> {@link #EXTRA_VOLUME} - Current volume settings of Renderer device
     *      device. Range: 0 - 255.
     *
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public static final String ACTION_VOLUME_CHANGED =
            "android.bluetooth.vcp.profile.action.VOLUME_CHANGED";

    /**
     * A int extra field in {@link #ACTION_VOLUME_CHANGED}
     * intents that contains the volume of the Volume Renderer device.
     */
    public static final String EXTRA_VOLUME =
            "android.bluetooth.vcp.profile.extra.VOLUME";

    /**
     * Intent used to broadcast the mute change of the Volume Renderer device
     *
     * <p>This intent will have 1 extras:
     * <ul>
     * <li> {@link #EXTRA_MUTE} - Current mute status of Renderer device
     *      device. False: unmute, True: mute.
     *
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public static final String ACTION_MUTE_CHANGED =
            "android.bluetooth.vcp.profile.action.MUTE_CHANGED";

    /**
     * A boolean extra field in {@link #ACTION_MUTE_CHANGED}
     * intents that contains the mute status of the Volume Renderer device.
     */
    public static final String EXTRA_MUTE =
            "android.bluetooth.vcp.profile.extra.MUTE";

    /**
     * Intent used to broadcast the connection mode change of the VCP
     *
     * <p>This intent will have 1 extras:
     * <ul>
     * <li> {@link #EXTRA_MODE} - Current connection mode of VCP
     * can be any of {@link #MODE_NONE}, {@link #MODE_UNICAST},
     * {@link #MODE_BROADCAST}, {@link #MODE_UNICAST_BROADCAST},
     *
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public static final String ACTION_CONNECTION_MODE_CHANGED =
            "android.bluetooth.vcp.profile.action.CONNECTION_MODE_CHANGED";

    /**
     * A int extra field in {@link #ACTION_CONNECTION_MODE_CHANGED}
     * intents that contains the connection mode of the VCP.
     */
    public static final String EXTRA_MODE =
            "android.bluetooth.vcp.profile.extra.MODE";

    /** None VCP connection */
    public static final int MODE_NONE = 0x00;
    /** VCP connection setup with unicast mode */
    public static final int MODE_UNICAST = 0x01;
    /** VCP connection setup with broadcast mode */
    public static final int MODE_BROADCAST = 0x02;
    /** VCP connection setup with unicast and broadcast mode */
    public static final int MODE_UNICAST_BROADCAST = 0x03;

    public static final int A2DP = 0x0001;
    public static final int HFP = 0x0002;
    public static final int LE_MEDIA = 0x0010;
    public static final int LE_VOICE = 0x2000;

    public static final int CALL_STREAM = 0;
    public static final int MEDIA_STREAM = 1;

    private BluetoothAdapter mAdapter;
    private final AttributionSource mAttributionSource;
    private final BluetoothProfileConnector<IBluetoothVcp> mProfileConnector =
            new BluetoothProfileConnector(this, BluetoothProfile.VCP,
                    "BluetoothVcp", IBluetoothVcp.class.getName()) {
                @Override
                public IBluetoothVcp getServiceInterface(IBinder service) {
                    return IBluetoothVcp.Stub.asInterface(
                            Binder.allowBlocking(service));
                }
    };

    /**
     * Create a BluetoothVcp proxy object for interacting with the local
     * Bluetooth VCP service.
     */
    /*package*/ BluetoothVcp(Context context, ServiceListener listener) {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mProfileConnector.connect(context, listener);
        mAttributionSource = mAdapter.getAttributionSource();
    }

    /*package*/ void close() {
        mProfileConnector.disconnect();
    }

    private IBluetoothVcp getService() {
        return mProfileConnector.getService();
    }

    @Override
    public void finalize() {
        close();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<BluetoothDevice> getConnectedDevices() {
        if (VDBG) log("getConnectedDevices()");
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        if (VDBG) log("getDevicesMatchingStates()");
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public int getConnectionState(BluetoothDevice device) {
        if (VDBG) log("getConnectionState(" + device + ")");
        final IBluetoothVcp service =
                getService();
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.getConnectionState(device, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
                return BluetoothProfile.STATE_DISCONNECTED;
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return BluetoothProfile.STATE_DISCONNECTED;
    }

    /**
     * Get current VCP Connection mode
     *
     * @param device: remote device instance
     * @return current connection mode of VCP:
     * {@link #BluetoothVcp.MODE_NONE} if none VCP connection
     * {@link #BluetoothVcp.MODE_UNICAST} if VCP is connected for unicast
     * {@link #BluetoothVcp.MODE_BROADCAST} if VCP is connected for broadcast
     * {@link #BluetoothVcp.MODE_UNICAST_BROADCAST} if VCP
     * is connected for unicast and broadcast
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public int getConnectionMode(BluetoothDevice device) {
        if (VDBG) log("getConnectionMode(" + device + ")");
        final IBluetoothVcp service =
                getService();
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.getConnectionMode(device, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
                return MODE_NONE;
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return MODE_NONE;
    }

    /**
     * Set absolute volume to remote device via VCP connection
     *
     * @param device: remote device instance
     * @prarm volume: requested volume settings for remote device
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public void setAbsoluteVolume(BluetoothDevice device, int volume) {
        if (VDBG) log("setAbsoluteVolume(" + device + ")");
        final IBluetoothVcp service =
                getService();
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                service.setAbsoluteVolume(device, volume, mAttributionSource);
                return;
            } catch (RemoteException e) {
                Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
                return;
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
    }

    /**
     * Get current absolute volume of the remote device
     *
     * @param device: remote device instance
     * @return current absolute volume of the remote device
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public int getAbsoluteVolume(BluetoothDevice device) {
        if (VDBG) log("getAbsoluteVolume(" + device + ")");
        final IBluetoothVcp service =
                getService();
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.getAbsoluteVolume(device, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
                return -1;
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return -1;
    }

    /**
     * Mute or unmute remote device via VCP connection
     *
     * @param device: remote device instance
     * @prarm enableMute: true if mute, false if unmute
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public void setMute(BluetoothDevice device, boolean enableMute) {
        if (VDBG) log("setMute(" + device + ")" +" enableMute: " + enableMute);
        final IBluetoothVcp service =
                getService();
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                service.setMute(device, enableMute, mAttributionSource);
                return;
            } catch (RemoteException e) {
                Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
                return;
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
    }

    /**
     * Get mute status of remote device
     *
     * @param device: remote device instance
     * @return current mute status of the remote device
     * true if mute status, false if unmute status
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public boolean isMute(BluetoothDevice device) {
        if (VDBG) log("isMute(" + device + ")");
        final IBluetoothVcp service =
                getService();
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.isMute(device, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
                return false;
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return false;
    }

    /**
     * set active stream for a DuMo device
     *
     * @param device: remote device instance
     * @param audioType: call/media audio
     * @param profile: profile that is needed to be active
     * @return success/failure
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public boolean setActiveProfile(BluetoothDevice device, int audioType, int profile) {
        if (VDBG) log("setActiveProfile(" + device + ")");
        final IBluetoothVcp service =
                getService();
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.setActiveProfile(device, audioType, profile, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
                return false;
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return false;
    }

    /**
     * set active stream for a DuMo device
     *
     * @param audioType: call/media audio
     * @return ID of current active profile
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public int getActiveProfile(int audioType) {
        if (VDBG) log("getActiveProfile(" + audioType + ")");
        final IBluetoothVcp service =
                getService();
        if (service != null && isEnabled()) {
            try {
                return service.getActiveProfile(audioType, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
                return -1;
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return -1;
    }

    private boolean isEnabled() {
        return mAdapter.getState() == BluetoothAdapter.STATE_ON;
    }

    private static boolean isValidDevice(BluetoothDevice device) {
        return device != null && BluetoothAdapter.checkBluetoothAddress(device.getAddress());
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}

