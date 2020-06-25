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

package android.bluetooth;

import android.os.Parcel;
import android.os.Parcelable;
import android.os.ParcelUuid;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Provides Device Group details.
 *
 * {@see BluetoothDeviceGroup}
 * @hide
 *
 */

public final class DeviceGroup implements Parcelable {
    /** Identifier of the Device Group */
    private int mGroupId;
    /** Size of the Device Group. */
    private int mSize;
    /** List of all group devices {@link BluetoothDevice} */
    private List <BluetoothDevice> mGroupDevices = new ArrayList<BluetoothDevice>();
    /** Primary Service UUID which has included required Device Group service*/
    private final ParcelUuid mIncludingSrvcUUID;
    /** Suggests whether exclusive access can be taken for this device group */
    private final boolean mExclusiveAccessSupport;

    /**
     * Constructor.
     * @hide
     */
    public DeviceGroup(int groupId, int size, List<BluetoothDevice> groupDevices,
            ParcelUuid includingSrvcUUID, boolean exclusiveAccessSupport) {
        mGroupId = groupId;
        mSize = size;
        mGroupDevices = groupDevices;
        mIncludingSrvcUUID = includingSrvcUUID;
        mExclusiveAccessSupport = exclusiveAccessSupport;
    }

    public DeviceGroup(Parcel in) {
        mGroupId = in.readInt();
        mSize = in.readInt();
        in.readList(mGroupDevices, BluetoothDevice.class.getClassLoader());
        mIncludingSrvcUUID = in.readParcelable(ParcelUuid.class.getClassLoader());
        mExclusiveAccessSupport = in.readBoolean();
    }

    /**
     * Used to retrieve identifier of the Device Group.
     *
     * @return  Identifier of the Device Group.
     */
    public int getDeviceGroupId() {
        return mGroupId;
    }

    /**
     * Used to know total number group devices which are part of this Device Group.
     *
     * @return size of the Device Group
     */
    public int getDeviceGroupSize() {
        return mSize;
    }

    /**
     * Indicates total number of group devices discovered in Group Discovery procedure.
     *
     * @return total group devices discovered in the Device Group.
     */
    public int getTotalDiscoveredGroupDevices() {
        return mGroupDevices.size();
    }


    /**
     * Used to fetch group devices of the Device Group.
     *
     *@return List of group devices {@link BluetoothDevice} in the Device Group.
     */
    public List<BluetoothDevice> getDeviceGroupMembers() {
        return mGroupDevices;
    }

    /**
     * Suggests primary GATT service which has included this DeviceGroup Service
     * for this device group. If remote device is part of multiple Device Groups then
     * this uuid cant be null. If remote device is part of only one device froup
     * then this returned parameter can be null.
     *
     *@return UUID of the GATT primary Service which has included this device group.
     */
    public ParcelUuid getIncludingServiceUUID() {
        return mIncludingSrvcUUID;
    }

    /**
     * Suggests whether exclusive access is supported by this Device Group.
     *
     * @return true, if exclusive access operation is supported by this Device Group.
     * Otherwise, false.
     */
    public boolean isExclusiveAccessSupported() {
        return mExclusiveAccessSupport;
    }

    /**
     * Indicates whether all devices of this Device Group are discovered.
     *
     * @return true, if all group devices are discovered. Otherwise, false.
     */
    public boolean isGroupDiscoveredCompleted() {
      return (mSize == getTotalDiscoveredGroupDevices());
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mGroupId);
        dest.writeInt(mSize);
        dest.writeList(mGroupDevices);
        dest.writeParcelable(mIncludingSrvcUUID, 0);
        dest.writeBoolean(mExclusiveAccessSupport);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<DeviceGroup> CREATOR =
            new Parcelable.Creator<DeviceGroup>() {
        public DeviceGroup createFromParcel(Parcel in) {
            return new DeviceGroup(in);
        }

        public DeviceGroup[] newArray(int size) {
            return new DeviceGroup[size];
        }
    };
}
