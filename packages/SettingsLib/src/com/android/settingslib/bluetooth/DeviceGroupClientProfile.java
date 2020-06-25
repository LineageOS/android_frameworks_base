/******************************************************************************
 *  Copyright (c) 2020, The Linux Foundation. All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are
 *  met:
 *      * Redistributions of source code must retain the above copyright
 *        notice, this list of conditions and the following disclaimer.
 *      * Redistributions in binary form must reproduce the above
 *        copyright notice, this list of conditions and the following
 *        disclaimer in the documentation and/or other materials provided
 *        with the distribution.
 *      * Neither the name of The Linux Foundation nor the names of its
 *        contributors may be used to endorse or promote products derived
 *        from this software without specific prior written permission.
 *
 *  THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 *  WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 *  MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 *  ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 *  BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 *  CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 *  SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 *  BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 *  WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 *  OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 *  IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *****************************************************************************/

package com.android.settingslib.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothDeviceGroup;
import android.bluetooth.BluetoothGroupCallback;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.DeviceGroup;
import android.content.Context;
import android.app.ActivityThread;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.android.settingslib.R;

import java.util.List;
import java.util.UUID;

/**
 * DeviceGroupClientProfile handles group operations required in client Role.
 */
public class DeviceGroupClientProfile implements LocalBluetoothProfile {
    private static final String TAG = "DeviceGroupClientProfile";

    private BluetoothDeviceGroup mService;
    private boolean mIsProfileReady;

    private final CachedBluetoothDeviceManager mDeviceManager;
    private final LocalBluetoothProfileManager mProfileManager;

    static final String NAME = "DeviceGroup Client";
    private static final String GROUP_APP = "com.android.settings";
    private String mCallingPackage;

    // Order of this profile in device profiles list
    private static final int ORDINAL = 3;

    DeviceGroupClientProfile(Context context,
            CachedBluetoothDeviceManager deviceManager,
            LocalBluetoothProfileManager profileManager) {
        mDeviceManager = deviceManager;
        mProfileManager = profileManager;
        mCallingPackage = ActivityThread.currentOpPackageName();
        BluetoothAdapter.getDefaultAdapter()
                        .getProfileProxy(context, new GroupClientServiceListener(),
                                         BluetoothProfile.GROUP_CLIENT);
    }

    // These callbacks run on the main thread.
    private final class GroupClientServiceListener
            implements BluetoothProfile.ServiceListener {

        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            mService = (BluetoothDeviceGroup) proxy;
            mIsProfileReady = true;
            Log.d(TAG, "onServiceConnected: mCallingPackage = " + mCallingPackage);
            // register Group Client App
            if (GROUP_APP.equals(mCallingPackage)) {
                mService.registerGroupClientApp(mGroupCallback,
                        new Handler(Looper.getMainLooper()));
            }
        }

        public void onServiceDisconnected(int profile) {
            mIsProfileReady=false;
        }
    }

    private final BluetoothGroupCallback mGroupCallback = new BluetoothGroupCallback() {

        @Override
        public void onNewGroupFound (int groupId,  BluetoothDevice device, UUID uuid) {
            Log.d(TAG, "onNewGroupFound()");

            CachedBluetoothDevice cachedDevice = mDeviceManager.findDevice(device);
            if (cachedDevice == null) {
                cachedDevice = mDeviceManager.addDevice(device);
            }

            mProfileManager.mEventManager.dispatchNewGroupFound(
                    cachedDevice, groupId, uuid);
            Log.d(TAG, "Start Group Discovery for Audio capable device");
            //if (device.isAdvAudioDevice())
                mService.startGroupDiscovery(groupId);
        }

        @Override
        public void onGroupDiscoveryStatusChanged (int groupId,
                int status, int reason) {
            Log.d(TAG, "onGroupDiscoveryStatusChanged()");

            mProfileManager.mEventManager.dispatchGroupDiscoveryStatusChanged(
                        groupId, status, reason);
        }

    };

    public boolean connectGroup (int groupId) {
        Log.d(TAG, "connectGroup(): groupId = " + groupId);
        boolean isTriggered = false;

        if(mService == null || mIsProfileReady == false) {
            Log.e(TAG, "connectGroup:  mService = " + mService +
                " mIsProfileReady = " + mIsProfileReady);
            return false;
        }

        DeviceGroup mGroup = mService.getGroup(groupId);

        if (mGroup == null || mGroup.getDeviceGroupMembers().size() == 0) {
            Log.e(TAG, "Requested device group not found");
            return false;
        }

        for (BluetoothDevice device: mGroup.getDeviceGroupMembers()) {
            CachedBluetoothDevice cachedDevice = mDeviceManager.findDevice(device);
            if (cachedDevice == null) {
                Log.w(TAG, "CachedBluetoothDevice not found for device: " + device);
                continue;
            }

            if (!cachedDevice.isConnected()) {
                cachedDevice.connect(true);
                isTriggered = true;
            }
        }
        return isTriggered;
    }

    public boolean disconnectGroup (int groupId) {
        Log.d(TAG, "disconnectGroup(): groupId = " + groupId);
        boolean isTriggered = false;

        if(mService == null || mIsProfileReady == false) {
            Log.e(TAG, "connectGroup:  mService = " + mService +
                " mIsProfileReady = " + mIsProfileReady);
            return false;
        }

        DeviceGroup mGroup = mService.getGroup(groupId);

        if (mGroup == null || mGroup.getDeviceGroupMembers().size() == 0) {
            Log.e(TAG, "Requested device group is not found");
            return false;
        }

        for (BluetoothDevice device: mGroup.getDeviceGroupMembers()) {
            CachedBluetoothDevice cachedDevice = mDeviceManager.findDevice(device);
            if (cachedDevice == null) {
                Log.w(TAG, "CachedBluetoothDevice not found for device: " + device);
                continue;
            }

            if (cachedDevice.isConnected()) {
                cachedDevice.disconnect();
                isTriggered = true;
            }
        }

        return isTriggered;
    }

    public boolean forgetGroup(int groupId) {
        Log.d(TAG, "forgetGroup(): groupId = " + groupId);

        if(mService == null || mIsProfileReady == false) {
            Log.e(TAG, "forgetGroup:  mService = " + mService +
                " mIsProfileReady = " + mIsProfileReady);
            return false;
        }

        DeviceGroup mGroup = mService.getGroup(groupId);
        if (mGroup == null || mGroup.getDeviceGroupMembers().size() == 0) {
            Log.e(TAG, "Requested device group is not found");
            return false;
        }

        for (BluetoothDevice device: mGroup.getDeviceGroupMembers()) {
            CachedBluetoothDevice cachedDevice = mDeviceManager.findDevice(device);
            if (cachedDevice == null) {
                Log.w(TAG, "CachedBluetoothDevice not found for device: " + device);
                continue;
            }
            cachedDevice.unpair();
        }

        return true;
    }

    public boolean startGroupDiscovery (int groupId) {
       Log.d(TAG, "startGroupDiscovery: groupId = " + groupId);

       if(mService == null || mIsProfileReady == false) {
           Log.e(TAG, "startGroupDiscovery:  mService = " + mService +
               " mIsProfileReady = " + mIsProfileReady);
           return false;
       }

        return mService.startGroupDiscovery(groupId);
    }

    public boolean stopGroupDiscovery (int groupId) {
       Log.d(TAG, "stopGroupDiscovery: groupId = " + groupId);

       if(mService == null || mIsProfileReady == false) {
           Log.e(TAG, "stopGroupDiscovery:  mService = " + mService +
               " mIsProfileReady = " + mIsProfileReady);
           return false;
       }

        return mService.stopGroupDiscovery(groupId);
    }

    public DeviceGroup getGroup (int groupId) {
        Log.d(TAG, "getGroup: groupId = " + groupId);

        if(mService == null || mIsProfileReady == false) {
            Log.e(TAG, "getGroup:  mService = " + mService +
                " mIsProfileReady = " + mIsProfileReady);
            return null;
        }

        return mService.getGroup(groupId);
    }

    public List<DeviceGroup> getDiscoveredGroups () {
       Log.d(TAG, "getDiscoveredGroups");

       if(mService == null || mIsProfileReady == false) {
           Log.e(TAG, "getDiscoveredGroups:  mService = " + mService +
               " mIsProfileReady = " + mIsProfileReady);
           return null;
       }

       return mService.getDiscoveredGroups();
    }

    public boolean isGroupDiscoveryInProgress (int groupId) {
       Log.d(TAG, "isGroupDiscoveryInProgress: groupId = " + groupId);

       if (mService == null) {
            Log.e(TAG, "Not connected to Profile Service. Return.");
            return false;
        }

       return mService.isGroupDiscoveryInProgress(groupId);
    }

    public int getRemoteDeviceGroupId (BluetoothDevice device) {
       Log.d(TAG, "getRemoteDeviceGroupId: device = " + device);

       if(mService == null || mIsProfileReady == false) {
           Log.e(TAG, "getRemoteDeviceGroupId:  mService = " + mService +
               " mIsProfileReady = " + mIsProfileReady);
           return BluetoothDeviceGroup.INVALID_GROUP_ID;
       }

       return mService.getRemoteDeviceGroupId(device, null);
    }

    public boolean isProfileReady() {
        return mIsProfileReady;
    }

    @Override
    public int getProfileId() {
        return BluetoothProfile.GROUP_CLIENT;
    }

    public boolean accessProfileEnabled() {
        return false;
    }

    public boolean isAutoConnectable() {
        return false;
    }

    public void setPreferred(BluetoothDevice device, boolean preferred) {}

    public int getPreferred(BluetoothDevice device) { return BluetoothProfile.PRIORITY_OFF;}

    public boolean isPreferred(BluetoothDevice device) {return false;}

    public boolean connect(BluetoothDevice device) { return false;}

    public boolean disconnect(BluetoothDevice device) {return false;}

    public int getConnectionStatus(BluetoothDevice device) {
        return BluetoothProfile.STATE_DISCONNECTED;
    }

    @Override
    public boolean isEnabled(BluetoothDevice device) {
        if (mService == null) {
            return false;
        }
        return true;
    }

    @Override
    public int getConnectionPolicy(BluetoothDevice device) {
        return 0;
    }

    @Override
    public boolean setEnabled(BluetoothDevice device, boolean enabled) {
        boolean isEnabled = false;

        return isEnabled;
    }

    public String toString() {
        return NAME;
    }

    public int getOrdinal() {
        return ORDINAL;
    }

    public int getNameResource(BluetoothDevice device) {
        return 0;//R.string.bluetooth_profile_group_client;
    }

    public int getSummaryResourceForDevice(BluetoothDevice device) {
        return 0;
    }

    public int getDrawableResource(BluetoothClass btClass) {
        return 0;
    }

    protected void finalize() {
        Log.d(TAG, "finalize()");
        if (mService != null) {
            try {
                BluetoothAdapter.getDefaultAdapter()
                                .closeProfileProxy(BluetoothProfile.GROUP_CLIENT,
                                                   mService);
                mService = null;
            }catch (Throwable t) {
                Log.w(TAG, "Error cleaning up BluetoothDeviceGroup proxy Object", t);
            }
        }
    }
}

