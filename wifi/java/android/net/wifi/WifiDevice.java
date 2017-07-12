/*
 * Copyright (c) 2014, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *   * Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *   * Redistributions in binary form must reproduce the above
 *     copyright notice, this list of conditions and the following
 *     disclaimer in the documentation and/or other materials provided
 *     with the distribution.
 *   * Neither the name of The Linux Foundation nor the names of its
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package android.net.wifi;

import android.os.Parcelable;
import android.os.Parcel;

/**
 * Describes information about a detected Wi-Fi STA.
 * {@hide}
 */
public class WifiDevice implements Parcelable {
    /**
     * The device MAC address is the unique id of a Wi-Fi STA
     */
    public String deviceAddress = "";

    /**
     * The device name is a readable string of a Wi-Fi STA
     */
    public String deviceName = "";

    /**
     * The device state is the state of a Wi-Fi STA
     */
    public int deviceState = 0;

    /**
     * These definitions are for deviceState
     */
    public static final int DISCONNECTED = 0;
    public static final int CONNECTED    = 1;


    /** {@hide} */
    public WifiDevice() {}

   /** {@hide} */
   public WifiDevice(String deviceAddress, boolean isConnected) {

        if (isConnected) {
            deviceState = CONNECTED;
        } else if (isConnected) {
            deviceState = DISCONNECTED;
    }
        this.deviceAddress = deviceAddress;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof WifiDevice)) {
            return false;
        }

        WifiDevice other = (WifiDevice) obj;

        if (deviceAddress == null) {
            return (other.deviceAddress == null);
        } else {
            return deviceAddress.equals(other.deviceAddress);
        }
    }

    /** Implement the Parcelable interface {@hide} */
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface {@hide} */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(deviceAddress);
        dest.writeString(deviceName);
        dest.writeInt(deviceState);
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Creator<WifiDevice> CREATOR =
        new Creator<WifiDevice>() {
            public WifiDevice createFromParcel(Parcel in) {
                WifiDevice device = new WifiDevice();
                device.deviceAddress = in.readString();
                device.deviceName = in.readString();
                device.deviceState = in.readInt();
                return device;
            }

            public WifiDevice[] newArray(int size) {
                return new WifiDevice[size];
            }
        };
}
