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

import java.util.UUID;
import java.util.List;
/**
 * This abstract class is used to implement {@link BluetoothDeviceGroup} callbacks.
 * @hide
 */
public abstract class BluetoothGroupCallback {
    /**
     * This Callback gives connection state changed with specific group device.
     *
     * @param state Connection state of the {@link BluetoothProfile} group device.
     * @param device Remote device for which connection state has changed.
     */
    public void onConnectionStateChanged (int state, BluetoothDevice device) {
    }

    /**
     * This callback is given when application is registered for Group operation
     * callbacks. This callback is given after {@link BluetoothDeviceGroup#registerGroupClientApp}
     * is called.
     *
     * @param status Status of the group client app registration.
     * @param appId Identifier of the application for group operations.
     */
    public void onGroupClientAppRegistered(int status, int appId) {
    }

    /**
     * This callback is triggered when a new device group has been identified
     * from one of the connected device. After this callback is received, application
     * can choose to trigger discovery of device group using API
     * {@link BluetoothDeviceGroup#startGroupDiscovery}
     *
     * @param groupId   Identifier of the Device Group.
     * @param device  Remote device with which Device Group is found.
     * @param uuid    UUID of the primary Service for this Device Group Service.
     */
    public void onNewGroupFound (int groupId,  BluetoothDevice device, UUID uuid) {
    }

    /**
     * This Callback is triggered when device group discovery is either started/stopped.
     *
     * @param groupId   Identifier of the device group.
     * @param status    Device Group Discovery status.
	 *                  {@link BluetoothDeviceGroup#GROUP_DISCOVERY_STARTED}
     *                  or  {@link BluetoothDeviceGroup#GROUP_DISCOVERY_STOPPED}.
     * @param reason    Reason for change in the discovery status.
     */
    public void onGroupDiscoveryStatusChanged (int groupId, int status, int reason) {
    }

    /**
     * This callback is triggered when new group device has been found after group
     * discovery has been started. This callback is given on discovery of every
     * new group device.
     *
     * @param groupId  Identifier of the device group.
     * @param device  {@link BluetoothDevice} instance of discovered group device.
     */
    public void onGroupDeviceFound (int groupId, BluetoothDevice device) {
    }

    /**
     * This callback is triggered after exclusive access status of the group
     * or subgroup has been changed after the request from application.
     *
     * @param groupId   Identifier of the device group.
     * @param value     Changed value of the exclusive access.
     * @param status    Status associated with the exclusive access.
     * @param devices   List of devices for which exclusive access has been changed.
     */
    public void onExclusiveAccessChanged (int groupId, int value, int status,
            List<BluetoothDevice> devices) {
    }

    /**
     * This callback gives access status of requested group/subgroup once
     * it is fetched.
     *
     * @param groupId       Identifier of the device group.
     * @param accessStatus  Value of the Exclusive Access.
     */
    public void onExclusiveAccessStatusFetched (int groupId, int accessStatus) {
    }

    /**
     * This callback is given to application when exclusive access is available
     * for the device of a given group for which was denied earlier.
     * <p> Exclusive Access is considered available when group device sends notification
     * for access changed to BluetoothDeviceGroup#ACCESS_RELEASED. This callback is
     * given to the application which has requested the access earlier and the request
     * had failed as one of the group device had DENIED the access.
     *
     * @param groupId  Identifier of the device group.
     * @param device  {@link BluetoothDevice} which has exclusive access available.
     */
    public void onExclusiveAccessAvailable (int groupId, BluetoothDevice device) {
    }

}