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

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothVcp;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;

import com.android.settingslib.R;

import java.util.ArrayList;
import java.util.List;

public class VcpProfile implements LocalBluetoothProfile {
    private static final String TAG = "VcpProfile";
    private static boolean V = true;

    private Context mContext;

    private BluetoothVcp mService;
    private boolean mIsProfileReady;

    private final CachedBluetoothDeviceManager mDeviceManager;

    static final String NAME = "VCP";
    private final LocalBluetoothProfileManager mProfileManager;
    private final BluetoothAdapter mBluetoothAdapter;

    // Order of this profile in device profiles list
    private static final int ORDINAL = 1;

    // These callbacks run on the main thread.
    private final class VcpServiceListener
            implements BluetoothProfile.ServiceListener {

        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            mService = (BluetoothVcp) proxy;
            Log.w(TAG, "Bluetooth service Connected");
            mIsProfileReady=true;
            mProfileManager.callServiceConnectedListeners();
        }

        public void onServiceDisconnected(int profile) {
            Log.w(TAG, "Bluetooth service Disconnected");
            mIsProfileReady=false;
        }
    }

    public boolean isProfileReady() {
        return mIsProfileReady;
    }

    @Override
    public int getProfileId() {
        return BluetoothProfile.VCP;
    }

    VcpProfile(Context context, CachedBluetoothDeviceManager deviceManager,
            LocalBluetoothProfileManager profileManager) {
        mContext = context;
        mDeviceManager = deviceManager;
        mProfileManager = profileManager;
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mBluetoothAdapter.getProfileProxy(context,
                new VcpServiceListener(), BluetoothProfile.VCP);
    }

    public boolean accessProfileEnabled() {
        return false;
    }

    public boolean isAutoConnectable() {
        return false;
    }

    public int getConnectionStatus(BluetoothDevice device) {
        if (mService == null) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
        return mService.getConnectionState(device);
    }

    public int getConnectionMode(BluetoothDevice device) {
        if (mService == null) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
        return mService.getConnectionMode(device);
    }

    @Override
    public boolean isEnabled(BluetoothDevice device) {
       return false;
    }

    @Override
    public int getConnectionPolicy(BluetoothDevice device) {
            return CONNECTION_POLICY_UNKNOWN;
    }

    @Override
    public boolean setEnabled(BluetoothDevice device, boolean enabled) {
        return false;
    }

    public void setAbsoluteVolume(BluetoothDevice device, int volume) {
        if (mService == null) {
            return;
        }
        mService.setAbsoluteVolume(device, volume);
    }

    public int getAbsoluteVolume(BluetoothDevice device) {
        if (mService == null) {
            return -1;
        }
        return mService.getAbsoluteVolume(device);
    }

    public void setMute(BluetoothDevice device, boolean enableMute) {
        if (mService == null) {
            return;
        }
        mService.setMute(device, enableMute);
    }

    public boolean isMute(BluetoothDevice device) {
        if (mService == null) {
            return false;
        }
        return mService.isMute(device);
    }

    public boolean setActiveProfile(BluetoothDevice device, int audioType, int profile) {
        if(mService != null) {
            return mService.setActiveProfile(device, audioType, profile);
        }
        return false;
    }

    public int getActiveProfile(int audioType) {
        if(mService != null) {
            return mService.getActiveProfile(audioType);
        }
        return -1;
    }

    public String toString() {
        return NAME;
    }

    public int getOrdinal() {
        return ORDINAL;
    }

    public int getNameResource(BluetoothDevice device) {
        return R.string.bluetooth_profile_vcp;
    }

    public int getSummaryResourceForDevice(BluetoothDevice device) {
        return 0;     // VCP profile not displayed in UI
    }

    public int getDrawableResource(BluetoothClass btClass) {
        return 0;   // no icon for VCP
    }

    protected void finalize() {
        Log.d(TAG, "finalize()");
        if (mService != null) {
            try {
                BluetoothAdapter.getDefaultAdapter().closeProfileProxy(BluetoothProfile.VCP,
                                                                       mService);
                mService = null;
            }catch (Throwable t) {
                Log.w(TAG, "Error cleaning up Vcp proxy", t);
            }
        }
    }
}

