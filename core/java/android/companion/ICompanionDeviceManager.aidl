/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.companion;

import android.app.PendingIntent;
import android.companion.IAssociationRequestCallback;
import android.companion.IOnAssociationsChangedListener;
import android.companion.IOnMessageReceivedListener;
import android.companion.IOnTransportEventListener;
import android.companion.IOnTransportsChangedListener;
import android.companion.IOnDevicePresenceEventListener;
import android.companion.IOnActionResultListener;
import android.companion.ISystemDataTransferCallback;
import android.companion.ActionRequest;
import android.companion.ActionResult;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.ObservingDevicePresenceRequest;
import android.companion.DevicePresenceEvent;
import android.companion.datatransfer.PermissionSyncRequest;
import android.content.ComponentName;
import android.os.ParcelUuid;
import android.os.PersistableBundle;
import android.companion.DeviceId;


/**
 * Interface for communication with the core companion device manager service.
 *
 * @hide
 */
interface ICompanionDeviceManager {
    void associate(in AssociationRequest request, in IAssociationRequestCallback callback,
        in String callingPackage, int userId);

    List<AssociationInfo> getAssociations(String callingPackage, int userId);

    @EnforcePermission("MANAGE_COMPANION_DEVICES")
    List<AssociationInfo> getAllAssociationsForUser(int userId);

    @EnforcePermission("ACCESS_COMPANION_MESSAGE_PCC")
    List<AssociationInfo> getTrustedAssociationsForUser(int userId);

    /** @deprecated */
    void legacyDisassociate(String deviceMacAddress, String callingPackage, int userId);

    void disassociate(int associationId);

    /** @deprecated */
    boolean hasNotificationAccess(in ComponentName component);

    PendingIntent requestNotificationAccess(in ComponentName component, int userId);

    @EnforcePermission("MANAGE_COMPANION_DEVICES")
    boolean isDeviceAssociatedForWifiConnection(in String packageName, in String macAddress,
        int userId);

    @EnforcePermission("REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE")
    void legacyStartObservingDevicePresence(in String deviceAddress, in String callingPackage, int userId);

    @EnforcePermission("REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE")
    void legacyStopObservingDevicePresence(in String deviceAddress, in String callingPackage, int userId);

    @EnforcePermission("REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE")
    void startObservingDevicePresence(in ObservingDevicePresenceRequest request, in String packageName, int userId);

    @EnforcePermission("REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE")
    void stopObservingDevicePresence(in ObservingDevicePresenceRequest request, in String packageName, int userId);

    boolean canPairWithoutPrompt(in String packageName, in String deviceMacAddress, int userId);

    @EnforcePermission("ASSOCIATE_COMPANION_DEVICES")
    void createAssociation(in String packageName, in String macAddress, int userId,
        in byte[] certificate);

    @EnforcePermission("MANAGE_COMPANION_DEVICES")
    void addOnAssociationsChangedListener(IOnAssociationsChangedListener listener, int userId);

    @EnforcePermission("MANAGE_COMPANION_DEVICES")
    void removeOnAssociationsChangedListener(IOnAssociationsChangedListener listener, int userId);

    @EnforcePermission("USE_COMPANION_TRANSPORTS")
    void addOnTransportsChangedListener(IOnTransportsChangedListener listener);

    @EnforcePermission("USE_COMPANION_TRANSPORTS")
    void removeOnTransportsChangedListener(IOnTransportsChangedListener listener);

    @EnforcePermission("USE_COMPANION_TRANSPORTS")
    List<AssociationInfo> getAllAssociationsWithTransports();

    @PermissionManuallyEnforced
    void sendMessage(int messageType, in byte[] data, in int[] associationIds);

    @PermissionManuallyEnforced
    void addOnMessageReceivedListener(int messageType, IOnMessageReceivedListener listener);

    @PermissionManuallyEnforced
    void removeOnMessageReceivedListener(int messageType, IOnMessageReceivedListener listener);

    @EnforcePermission("USE_COMPANION_TRANSPORTS")
    void addOnTransportEventListener(int associationId, IOnTransportEventListener listener);

    @EnforcePermission("USE_COMPANION_TRANSPORTS")
    void removeOnTransportEventListener(int associationId, IOnTransportEventListener listener);

    @EnforcePermission("REQUEST_COMPANION_SELF_MANAGED")
    void notifySelfManagedDeviceAppeared(int associationId);

    @EnforcePermission("REQUEST_COMPANION_SELF_MANAGED")
    void notifySelfManagedDeviceDisappeared(int associationId);

    PendingIntent buildPermissionTransferUserConsentIntent(int associationId);

    boolean isPermissionTransferUserConsented(int associationId);

    void startSystemDataTransfer(int associationId,
        in ISystemDataTransferCallback callback);

    @EnforcePermission("DELIVER_COMPANION_MESSAGES")
    void attachSystemDataTransport(String packageName, int userId, int associationId, in ParcelFileDescriptor fd);

    @EnforcePermission("DELIVER_COMPANION_MESSAGES")
    void detachSystemDataTransport(String packageName, int userId, int associationId);

    boolean isCompanionApplicationBound(String packageName, int userId);

    PendingIntent buildAssociationCancellationIntent(in String callingPackage, int userId);

    @PermissionManuallyEnforced
    void enableSystemDataSync(int associationId, int flags);

    @PermissionManuallyEnforced
    void disableSystemDataSync(int associationId, int flags);

    void enablePermissionsSync(int associationId);

    void disablePermissionsSync(int associationId);

    PermissionSyncRequest getPermissionSyncRequest(int associationId);

    @EnforcePermission("MANAGE_COMPANION_DEVICES")
    void overrideTransportType(int typeOverride);

    byte[] getBackupPayload(int userId);

    void applyRestoredPayload(in byte[] payload, int userId);

    @EnforcePermission("BLUETOOTH_CONNECT")
    boolean removeBond(int associationId, in String packageName, int userId);

    @EnforcePermission("ACCESS_COMPANION_INFO")
    AssociationInfo getAssociationByDeviceId(int userId, in DeviceId deviceId);

    DeviceId setDeviceId(int associationId, in DeviceId deviceId);

    @EnforcePermission("MANAGE_COMPANION_DEVICES")
    void setLocalMetadata(int userId, String key, in PersistableBundle value);

    @EnforcePermission("MANAGE_COMPANION_DEVICES")
    PersistableBundle getLocalMetadata(int userId);

    @EnforcePermission("USE_COMPANION_TRANSPORTS")
    void setOnDevicePresenceEventListener(in int[] associationIds, in String serviceName,
            IOnDevicePresenceEventListener listener, in int userId);

    @EnforcePermission("USE_COMPANION_TRANSPORTS")
    void removeOnDevicePresenceEventListener(in String serviceName, in int userId);

    @EnforcePermission("REQUEST_COMPANION_SELF_MANAGED")
    void notifyDevicePresence(in int associationId, in DevicePresenceEvent event);

    @EnforcePermission(anyOf = { "USE_COMPANION_TRANSPORTS", "ACCESS_COMPANION_MESSAGE_PCC" })
    void requestAction(in ActionRequest request, in String serviceName, in String packageName, in int[] associationIds);

    void notifyActionResult(in int associationId, in ActionResult result);

    @EnforcePermission(anyOf = { "USE_COMPANION_TRANSPORTS", "ACCESS_COMPANION_MESSAGE_PCC" })
    void setOnActionResultListener(in int[] associationIds, in String serviceName, in String callingPackageName,
            in IOnActionResultListener listener);

    @EnforcePermission(anyOf = { "USE_COMPANION_TRANSPORTS", "ACCESS_COMPANION_MESSAGE_PCC" })
    void clearOnActionResultListener(in String serviceName, in String callingPackageName);

    @PermissionManuallyEnforced
    boolean isSystemDataTransportAttached(in int associationId);

    @EnforcePermission("USE_COMPANION_TRANSPORTS")
    void setRequestActionAllowList(in List<String> allowList);

    @EnforcePermission("MANAGE_COMPANION_DEVICES")
    boolean isDevicePresent(in int associationId);
}
