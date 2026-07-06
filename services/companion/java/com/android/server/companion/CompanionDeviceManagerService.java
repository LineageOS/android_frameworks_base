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


package com.android.server.companion;

import static android.Manifest.permission.ACCESS_COMPANION_INFO;
import static android.Manifest.permission.ACCESS_COMPANION_MESSAGE_PCC;
import static android.Manifest.permission.ASSOCIATE_COMPANION_DEVICES;
import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.DELIVER_COMPANION_MESSAGES;
import static android.Manifest.permission.MANAGE_COMPANION_DEVICES;
import static android.Manifest.permission.REQUEST_COMPANION_SELF_MANAGED;
import static android.Manifest.permission.REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE;
import static android.Manifest.permission.USE_COMPANION_TRANSPORTS;
import static android.content.pm.PackageManager.CERT_INPUT_SHA256;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Process.SYSTEM_UID;
import static android.os.UserHandle.getCallingUserId;

import static com.android.internal.util.CollectionUtils.any;
import static com.android.internal.util.Preconditions.checkState;
import static com.android.server.companion.association.DisassociationProcessor.REASON_API;
import static com.android.server.companion.association.DisassociationProcessor.REASON_PKG_DATA_CLEARED;
import static com.android.server.companion.association.DisassociationProcessor.REASON_REVOKED;
import static com.android.server.companion.utils.PackageUtils.enforceUsesCompanionDeviceFeature;
import static com.android.server.companion.utils.PackageUtils.isRestrictedSettingsAllowed;
import static com.android.server.companion.utils.PermissionsUtils.checkCallerCanUseSystemDataTransports;
import static com.android.server.companion.utils.PermissionsUtils.enforceCallerCanInteractWithSystemDataSyncFlags;
import static com.android.server.companion.utils.PermissionsUtils.enforceCallerCanManageAssociationsForPackage;
import static com.android.server.companion.utils.PermissionsUtils.enforceCallerIsSystemOr;
import static com.android.server.companion.utils.PermissionsUtils.enforceCallerIsSystemOrCanInteractWithUserId;
import static com.android.server.companion.utils.PermissionsUtils.enforceMessagePermissions;
import static com.android.server.companion.utils.PermissionsUtils.enforceValidServiceName;
import static com.android.server.companion.utils.Utils.generateRandom128BitKey;

import static java.util.Objects.requireNonNull;

import android.annotation.EnforcePermission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.PermissionManuallyEnforced;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.ecm.EnhancedConfirmationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.companion.ActionRequest;
import android.companion.ActionResult;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.DeviceId;
import android.companion.DevicePresenceEvent;
import android.companion.IAssociationRequestCallback;
import android.companion.ICompanionDeviceManager;
import android.companion.IOnActionResultListener;
import android.companion.IOnAssociationsChangedListener;
import android.companion.IOnDevicePresenceEventListener;
import android.companion.IOnMessageReceivedListener;
import android.companion.IOnTransportEventListener;
import android.companion.IOnTransportsChangedListener;
import android.companion.ISystemDataTransferCallback;
import android.companion.ObservingDevicePresenceRequest;
import android.companion.datatransfer.PermissionSyncRequest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManagerInternal;
import android.net.MacAddress;
import android.os.Binder;
import android.os.Build;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.PowerExemptionManager;
import android.os.PowerManagerInternal;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ExceptionUtils;
import android.util.Slog;

import com.android.internal.content.PackageMonitor;
import com.android.internal.notification.NotificationAccessConfirmationActivityContract;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.companion.actionrequest.ActionRequestProcessor;
import com.android.server.companion.association.AssociationDiskStore;
import com.android.server.companion.association.AssociationRequestsProcessor;
import com.android.server.companion.association.AssociationStore;
import com.android.server.companion.association.DisassociationProcessor;
import com.android.server.companion.association.InactiveAssociationsRemovalService;
import com.android.server.companion.datasync.DataSyncProcessor;
import com.android.server.companion.datasync.LocalMetadataStore;
import com.android.server.companion.datatransfer.SystemDataTransferProcessor;
import com.android.server.companion.datatransfer.SystemDataTransferRequestStore;
import com.android.server.companion.datatransfer.contextsync.CrossDeviceCall;
import com.android.server.companion.datatransfer.contextsync.CrossDeviceSyncController;
import com.android.server.companion.datatransfer.contextsync.CrossDeviceSyncControllerCallback;
import com.android.server.companion.devicepresence.CompanionAppBinder;
import com.android.server.companion.devicepresence.DevicePresenceProcessor;
import com.android.server.companion.devicepresence.ObservableUuid;
import com.android.server.companion.devicepresence.ObservableUuidStore;
import com.android.server.companion.devicetrust.BluetoothPasskeyProvider;
import com.android.server.companion.devicetrust.RandomKeyProvider;
import com.android.server.companion.devicetrust.TrustedDeviceProcessor;
import com.android.server.companion.devicetrust.TrustedDeviceStore;
import com.android.server.companion.powerexemption.CompanionExemptionProcessor;
import com.android.server.companion.powerexemption.CompanionExemptionStore;
import com.android.server.companion.transport.CompanionTransportManager;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint("LongLogTag")
public class CompanionDeviceManagerService extends SystemService {
    private static final String TAG = "CDM_CompanionDeviceManagerService";

    private static final long PAIR_WITHOUT_PROMPT_WINDOW_MS = 10 * 60 * 1000; // 10 min
    private static final int MAX_CN_LENGTH = 500;

    private final AssociationStore mAssociationStore;
    private final SystemDataTransferRequestStore mSystemDataTransferRequestStore;
    private final ObservableUuidStore mObservableUuidStore;
    private final TrustedDeviceStore mTrustedDeviceStore;

    private final CompanionExemptionProcessor mCompanionExemptionProcessor;
    private final AssociationRequestsProcessor mAssociationRequestsProcessor;
    private final SystemDataTransferProcessor mSystemDataTransferProcessor;
    private final BackupRestoreProcessor mBackupRestoreProcessor;
    private final DevicePresenceProcessor mDevicePresenceProcessor;
    private final CompanionAppBinder mCompanionAppBinder;
    private final CompanionTransportManager mTransportManager;
    private final DisassociationProcessor mDisassociationProcessor;
    private final CrossDeviceSyncController mCrossDeviceSyncController;
    private final LocalMetadataStore mLocalMetadataStore;
    private final DataSyncProcessor mDataSyncProcessor;
    private final TrustedDeviceProcessor mTrustedDeviceProcessor;
    private final ActionRequestProcessor mActionRequestProcessor;
    private final CompanionExemptionStore mCompanionExemptionStore;
    private final Object mPackageLock = new Object();

    public CompanionDeviceManagerService(Context context) {
        super(context);

        final ActivityManager activityManager = context.getSystemService(ActivityManager.class);
        final PowerExemptionManager powerExemptionManager = context.getSystemService(
                PowerExemptionManager.class);
        final AppOpsManager appOpsManager = context.getSystemService(AppOpsManager.class);
        final ActivityTaskManagerInternal atmInternal = LocalServices.getService(
                ActivityTaskManagerInternal.class);
        final ActivityManagerInternal amInternal = LocalServices.getService(
                ActivityManagerInternal.class);
        final PackageManagerInternal packageManagerInternal = LocalServices.getService(
                PackageManagerInternal.class);
        final UserManager userManager = context.getSystemService(UserManager.class);
        final PowerManagerInternal powerManagerInternal = LocalServices.getService(
                PowerManagerInternal.class);
        final NotificationManager notificationManager = context.getSystemService(
                NotificationManager.class);

        final AssociationDiskStore associationDiskStore = new AssociationDiskStore();
        mAssociationStore = new AssociationStore(context, userManager, associationDiskStore);
        mSystemDataTransferRequestStore = new SystemDataTransferRequestStore();
        mObservableUuidStore = new ObservableUuidStore();
        mLocalMetadataStore = new LocalMetadataStore();
        mTrustedDeviceStore = new TrustedDeviceStore();
        mCompanionExemptionStore = new CompanionExemptionStore();

        // Init processors
        mAssociationRequestsProcessor = new AssociationRequestsProcessor(context,
                packageManagerInternal, mAssociationStore);
        mBackupRestoreProcessor = new BackupRestoreProcessor(context, packageManagerInternal,
                mAssociationStore, associationDiskStore, mSystemDataTransferRequestStore,
                mAssociationRequestsProcessor);

        mCompanionAppBinder = new CompanionAppBinder(context);

        mCompanionExemptionProcessor = new CompanionExemptionProcessor(context,
                powerExemptionManager, appOpsManager, packageManagerInternal, atmInternal,
                amInternal, mAssociationStore, mCompanionExemptionStore);

        mDevicePresenceProcessor = new DevicePresenceProcessor(context,
                mCompanionAppBinder, userManager, mAssociationStore, mObservableUuidStore,
                powerManagerInternal, mCompanionExemptionProcessor);

        mTransportManager = new CompanionTransportManager(context, mAssociationStore);

        mActionRequestProcessor = new ActionRequestProcessor(mAssociationStore,
                mDevicePresenceProcessor, mCompanionAppBinder);

        mDisassociationProcessor = new DisassociationProcessor(context, activityManager,
                mAssociationStore, packageManagerInternal, mDevicePresenceProcessor,
                mCompanionAppBinder, mSystemDataTransferRequestStore, mTransportManager,
                mTrustedDeviceStore, notificationManager);

        mSystemDataTransferProcessor = new SystemDataTransferProcessor(this,
                packageManagerInternal, mAssociationStore,
                mSystemDataTransferRequestStore, mTransportManager);

        mDataSyncProcessor = new DataSyncProcessor(mAssociationStore, mLocalMetadataStore,
                mTransportManager);

        mTrustedDeviceProcessor = new TrustedDeviceProcessor(context, mAssociationStore,
                mTrustedDeviceStore, mTransportManager);
        mTrustedDeviceProcessor.addPskProvider(
                new BluetoothPasskeyProvider(context, mAssociationStore));

        // TODO(b/279663946): move context sync to a dedicated system service
        mCrossDeviceSyncController = new CrossDeviceSyncController(getContext(), mTransportManager);
    }

    @Override
    public void onStart() {
        // Init association stores
        mAssociationStore.refreshCache();

        // Remove any revoked associations after reboot.
        for (AssociationInfo ai : mAssociationStore.getRevokedAssociations()) {
            mDisassociationProcessor.disassociate(ai.getId(), REASON_REVOKED);
        }

        // Init UUID store
        mObservableUuidStore.readObservableUuids(getContext().getUserId());

        // Publish "binder" service.
        final CompanionDeviceManagerImpl impl = new CompanionDeviceManagerImpl();
        publishBinderService(Context.COMPANION_DEVICE_SERVICE, impl);

        // Publish "local" service.
        LocalServices.addService(CompanionDeviceManagerServiceInternal.class, new LocalService());
    }

    @Override
    public void onBootPhase(int phase) {
        final Context context = getContext();
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            // WARNING: moving PackageMonitor to another thread (Looper) may introduce significant
            // delays (even in case of the Main Thread). It may be fine overall, but would require
            // updating the tests (adding a delay there).
            mPackageMonitor.register(context, FgThread.get().getLooper(), UserHandle.ALL, true);
        } else if (phase == PHASE_BOOT_COMPLETED) {
            mDevicePresenceProcessor.init(context);
            // Run the Inactive Association Removal job service daily.
            InactiveAssociationsRemovalService.schedule(getContext());
            mCrossDeviceSyncController.onBootCompleted();
        }
    }

    @Override
    public void onUserUnlocking(@NonNull TargetUser user) {
        Slog.d(TAG, "onUserUnlocking...");
        final int userId = user.getUserIdentifier();
        final List<AssociationInfo> associations = mAssociationStore.getActiveAssociationsByUser(
                userId);

        if (associations.isEmpty()) return;

        mCompanionExemptionProcessor.updateAtm(userId, associations);
    }

    @Override
    public void onUserUnlocked(@NonNull TargetUser user) {
        Slog.i(TAG, "onUserUnlocked() user=" + user);
        final int userId = user.getUserIdentifier();

        // Notify and bind the app after the phone is unlocked.
        mDevicePresenceProcessor.sendDevicePresenceEventOnUnlocked(userId);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            mTrustedDeviceProcessor.loadKeysForUser(userId);
            mCompanionExemptionProcessor.updateAutoRevokeExemptions(userId);
        });
    }

    private void onPackageRemoveOrDataClearedInternal(
            @UserIdInt int userId, @NonNull String packageName) {
        // Clear all associations for the package.
        final List<AssociationInfo> associationsForPackage =
                mAssociationStore.getAssociationsByPackage(userId, packageName);
        if (!associationsForPackage.isEmpty()) {
            Slog.i(TAG, "Package removed or data cleared for user=[" + userId + "], package=["
                    + packageName + "]. Cleaning up CDM data...");

            for (AssociationInfo association : associationsForPackage) {
                mDisassociationProcessor.disassociate(association.getId(), REASON_PKG_DATA_CLEARED);
            }
        }

        // Clear observable UUIDs for the package.
        final List<ObservableUuid> uuidsTobeObserved =
                mObservableUuidStore.readObservableUuidsForPackage(userId, packageName);
        for (ObservableUuid uuid : uuidsTobeObserved) {
            mObservableUuidStore.removeObservableUuid(userId, uuid.uuid(), packageName);
        }
        mCompanionExemptionProcessor.removePackage(userId, packageName);
    }

    private void onPackageModifiedInternal(@UserIdInt int userId, @NonNull String packageName) {
        final List<AssociationInfo> associations =
                mAssociationStore.getAssociationsByPackage(userId, packageName);

       if (!associations.isEmpty()) {
            mCompanionExemptionProcessor.exemptPackage(userId, packageName, false);
       }
    }

    private void onPackageAddedInternal(@UserIdInt int userId, @NonNull String packageName) {
        mBackupRestoreProcessor.restorePendingAssociations(userId, packageName);
    }

    void removeInactiveSelfManagedAssociations() {
        mDisassociationProcessor.removeIdleSelfManagedAssociations();
    }

    public class CompanionDeviceManagerImpl extends ICompanionDeviceManager.Stub {
        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            try {
                return super.onTransact(code, data, reply, flags);
            } catch (Throwable e) {
                Slog.e(TAG, "Error during IPC", e);
                throw ExceptionUtils.propagate(e, RemoteException.class);
            }
        }

        @Override
        public void associate(AssociationRequest request, IAssociationRequestCallback callback,
                String packageName, int userId) throws RemoteException {
            if (Build.isDebuggable()) {
                Slog.d(TAG, "associate() "
                        + "request=" + request + ", "
                        + "package=u" + userId + "/" + packageName);
            }
            enforceCallerCanManageAssociationsForPackage(getContext(), userId, packageName,
                    "create associations");

            final int callingUid = Binder.getCallingUid();
            // Foreground check is bypassed if the caller is system, or it is a self-managed
            // association, or the caller has the privileged MANAGE_COMPANION_DEVICES permission.
            if (!(callingUid == SYSTEM_UID
                    || request.isSelfManaged()
                    || getContext().checkCallingPermission(MANAGE_COMPANION_DEVICES)
                            == PERMISSION_GRANTED)) {
                ActivityManagerInternal amInternal = LocalServices.getService(
                        ActivityManagerInternal.class);
                int procState = amInternal.getUidProcessState(callingUid);
                if (procState != ActivityManager.PROCESS_STATE_TOP) {
                    throw new SecurityException("Caller must be in foreground to associate");
                }
            }

            if (request.isSkipRoleGrant()) {
                checkCallerCanSkipRoleGrant();
                mAssociationRequestsProcessor.createAssociation(userId, packageName,
                        /* macAddress= */ null, request.getDisplayName(),
                        request.getDeviceProfile(), /* associatedDevice= */ null,
                        request.isSelfManaged(), callback, /* resultReceiver= */ null,
                        request.getDeviceIcon(), /* skipRoleGrant= */ true,
                        request.getExtraPermissions(), request.isRemoteAiAgentSupported());
            } else {
                mAssociationRequestsProcessor.processNewAssociationRequest(
                        request, packageName, userId, callback);
            }
        }

        @Override
        public PendingIntent buildAssociationCancellationIntent(String packageName,
                int userId) throws RemoteException {
            Slog.i(TAG, "buildAssociationCancellationIntent() "
                    + "package=u" + userId + "/" + packageName);
            enforceCallerCanManageAssociationsForPackage(getContext(), userId, packageName,
                    "build association cancellation intent");

            return mAssociationRequestsProcessor.buildAssociationCancellationIntent(
                    packageName, userId);
        }

        @Override
        public List<AssociationInfo> getAssociations(String packageName, int userId) {
            enforceCallerCanManageAssociationsForPackage(getContext(), userId, packageName,
                    "get associations");
            return mAssociationStore.getActiveAssociationsByPackage(userId, packageName);
        }

        @Override
        @EnforcePermission(MANAGE_COMPANION_DEVICES)
        public List<AssociationInfo> getAllAssociationsForUser(int userId) throws RemoteException {
            getAllAssociationsForUser_enforcePermission();

            enforceCallerIsSystemOrCanInteractWithUserId(getContext(), userId);

            if (userId == UserHandle.USER_ALL) {
                return mAssociationStore.getActiveAssociations();
            }
            return mAssociationStore.getActiveAssociationsByUser(userId);
        }

        @Override
        @EnforcePermission(ACCESS_COMPANION_MESSAGE_PCC)
        public List<AssociationInfo> getTrustedAssociationsForUser(int userId)
                throws RemoteException {
            getTrustedAssociationsForUser_enforcePermission();

            enforceCallerIsSystemOrCanInteractWithUserId(getContext(), userId);

            // TODO(b/496715920): Temporarily give PCC all the associations for it
            // to establish trust. Should give only trusted associations after 26Q3.
            return mAssociationStore.getActiveAssociationsByUser(userId);
        }

        @Override
        @EnforcePermission(MANAGE_COMPANION_DEVICES)
        public void addOnAssociationsChangedListener(IOnAssociationsChangedListener listener,
                int userId) {
            addOnAssociationsChangedListener_enforcePermission();

            enforceCallerIsSystemOrCanInteractWithUserId(getContext(), userId);

            mAssociationStore.registerRemoteListener(listener, userId);
        }

        @Override
        @EnforcePermission(MANAGE_COMPANION_DEVICES)
        public void removeOnAssociationsChangedListener(IOnAssociationsChangedListener listener,
                int userId) {
            removeOnAssociationsChangedListener_enforcePermission();

            enforceCallerIsSystemOrCanInteractWithUserId(getContext(), userId);

            mAssociationStore.unregisterRemoteListener(listener);
        }

        @Override
        @EnforcePermission(USE_COMPANION_TRANSPORTS)
        public void addOnTransportsChangedListener(IOnTransportsChangedListener listener) {
            addOnTransportsChangedListener_enforcePermission();

            mTransportManager.addListener(listener);
        }

        @Override
        @EnforcePermission(USE_COMPANION_TRANSPORTS)
        public void removeOnTransportsChangedListener(IOnTransportsChangedListener listener) {
            removeOnTransportsChangedListener_enforcePermission();

            mTransportManager.removeListener(listener);
        }

        @Override
        @EnforcePermission(USE_COMPANION_TRANSPORTS)
        public List<AssociationInfo> getAllAssociationsWithTransports() {
            getAllAssociationsWithTransports_enforcePermission();

            return mTransportManager.getAssociationsWithTransport();
        }

        @Override
        @PermissionManuallyEnforced
        public void sendMessage(int messageType, byte[] data, int[] associationIds) {
            enforceMessagePermissions(getContext(), messageType);

            mTransportManager.sendMessage(messageType, data, associationIds);
        }

        @Override
        @PermissionManuallyEnforced
        public void addOnMessageReceivedListener(int messageType,
                IOnMessageReceivedListener listener) {
            enforceMessagePermissions(getContext(), messageType);

            mTransportManager.addListener(messageType, listener);
        }

        @Override
        @PermissionManuallyEnforced
        public void removeOnMessageReceivedListener(int messageType,
                IOnMessageReceivedListener listener) {
            enforceMessagePermissions(getContext(), messageType);

            mTransportManager.removeListener(messageType, listener);
        }

        @Override
        @EnforcePermission(USE_COMPANION_TRANSPORTS)
        public void addOnTransportEventListener(int associationId,
                IOnTransportEventListener listener) {
            addOnTransportEventListener_enforcePermission();

            mTransportManager.addListener(associationId, listener);
        }

        @Override
        @EnforcePermission(USE_COMPANION_TRANSPORTS)
        public void removeOnTransportEventListener(int associationId,
                IOnTransportEventListener listener) {
            removeOnTransportEventListener_enforcePermission();

            mTransportManager.removeListener(associationId, listener);
        }

        /**
         * @deprecated use {@link #disassociate(int)} instead
         */
        @Deprecated
        @Override
        public void legacyDisassociate(String deviceMacAddress, String packageName, int userId) {
            requireNonNull(deviceMacAddress);
            requireNonNull(packageName);

            mDisassociationProcessor.disassociate(userId, packageName, deviceMacAddress);
        }

        @Override
        public void disassociate(int associationId) {
            mDisassociationProcessor.disassociate(associationId, REASON_API);
        }

        @Override
        public PendingIntent requestNotificationAccess(ComponentName component, int userId)
                throws RemoteException {
            int callingUid = getCallingUid();
            final String callingPackage = component.getPackageName();

            checkCanCallNotificationApi(callingPackage, userId);

            if (component.flattenToString().length() > MAX_CN_LENGTH) {
                throw new IllegalArgumentException("Component name is too long.");
            }

            return Binder.withCleanCallingIdentity(() -> {
                final Intent intent;
                if (!isRestrictedSettingsAllowed(getContext(), callingPackage, callingUid)) {
                    Slog.e(TAG, "Side loaded app must enable restricted "
                            + "setting before request the notification access");
                    if (android.permission.flags.Flags.enhancedConfirmationModeApisEnabled()) {
                        intent = getContext()
                                .getSystemService(EnhancedConfirmationManager.class)
                                .createRestrictedSettingDialogIntent(callingPackage,
                                        AppOpsManager.OPSTR_ACCESS_NOTIFICATIONS);
                    } else {
                        return null;
                    }
                } else {
                    intent = NotificationAccessConfirmationActivityContract.launcherIntent(
                            getContext(), userId, component);
                }

                return PendingIntent.getActivityAsUser(getContext(),
                        0 /* request code */,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT
                                | PendingIntent.FLAG_CANCEL_CURRENT,
                        null /* options */,
                        new UserHandle(userId));
            });
        }

        /**
         * @deprecated Use
         * {@link NotificationManager#isNotificationListenerAccessGranted(ComponentName)} instead.
         */
        @Deprecated
        @Override
        public boolean hasNotificationAccess(ComponentName component) throws RemoteException {
            checkCanCallNotificationApi(component.getPackageName(), getCallingUserId());
            NotificationManager nm = getContext().getSystemService(NotificationManager.class);
            return nm.isNotificationListenerAccessGranted(component);
        }

        @Override
        @EnforcePermission(MANAGE_COMPANION_DEVICES)
        public boolean isDeviceAssociatedForWifiConnection(String packageName, String macAddress,
                int userId) {
            isDeviceAssociatedForWifiConnection_enforcePermission();

            boolean bypassMacPermission = getContext().getPackageManager().checkPermission(
                    android.Manifest.permission.COMPANION_APPROVE_WIFI_CONNECTIONS, packageName)
                    == PERMISSION_GRANTED;
            if (bypassMacPermission) {
                return true;
            }

            return any(mAssociationStore.getActiveAssociationsByPackage(userId, packageName),
                    a -> a.isLinkedTo(macAddress));
        }

        @Override
        @Deprecated
        @EnforcePermission(REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE)
        public void legacyStartObservingDevicePresence(String deviceAddress, String callingPackage,
                int userId) throws RemoteException {
            legacyStartObservingDevicePresence_enforcePermission();

            mDevicePresenceProcessor.startObservingDevicePresence(userId, callingPackage,
                    deviceAddress);
        }

        @Override
        @Deprecated
        @EnforcePermission(REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE)
        public void legacyStopObservingDevicePresence(String deviceAddress, String callingPackage,
                int userId) throws RemoteException {
            legacyStopObservingDevicePresence_enforcePermission();

            mDevicePresenceProcessor.stopObservingDevicePresence(userId, callingPackage,
                    deviceAddress);
        }

        @Override
        @EnforcePermission(REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE)
        public void startObservingDevicePresence(ObservingDevicePresenceRequest request,
                String packageName, int userId) {
            startObservingDevicePresence_enforcePermission();

            mDevicePresenceProcessor.startObservingDevicePresence(
                    request, packageName, userId, /* enforcePermissions */ true);
        }

        @Override
        @EnforcePermission(REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE)
        public void stopObservingDevicePresence(ObservingDevicePresenceRequest request,
                String packageName, int userId) {
            stopObservingDevicePresence_enforcePermission();

            mDevicePresenceProcessor.stopObservingDevicePresence(
                    request, packageName, userId, /* enforcePermissions */ true);
        }

        @Override
        @EnforcePermission(BLUETOOTH_CONNECT)
        public boolean removeBond(int associationId, String packageName, int userId) {
            removeBond_enforcePermission();

            Slog.i(TAG, "removeBond() "
                    + "associationId=" + associationId + ", "
                    + "package=u" + userId + "/" + packageName);
            enforceCallerCanManageAssociationsForPackage(getContext(), userId, packageName,
                    "remove bonds");

            AssociationInfo association = mAssociationStore
                    .getAssociationWithCallerChecks(associationId);
            MacAddress address = association.getDeviceMacAddress();
            if (address == null) {
                throw new IllegalArgumentException(
                        "Association id=[" + associationId + "] doesn't have a device address.");
            }

            BluetoothAdapter btAdapter = getContext().getSystemService(BluetoothManager.class)
                    .getAdapter();
            BluetoothDevice btDevice = btAdapter.getRemoteDevice(address.toString().toUpperCase());
            return btDevice.removeBond();
        }

        @Override
        public PendingIntent buildPermissionTransferUserConsentIntent(String packageName,
                int userId, int associationId) {
            return mSystemDataTransferProcessor.buildPermissionTransferUserConsentIntent(
                    packageName, userId, associationId);
        }

        @Override
        public boolean isPermissionTransferUserConsented(String packageName, int userId,
                int associationId) {
            return mSystemDataTransferProcessor.isPermissionTransferUserConsented(associationId);
        }

        @Override
        public void startSystemDataTransfer(String packageName, int userId, int associationId,
                ISystemDataTransferCallback callback) {
            mSystemDataTransferProcessor.startSystemDataTransfer(packageName, userId,
                    associationId, callback);
        }

        @Override
        @EnforcePermission(DELIVER_COMPANION_MESSAGES)
        public void attachSystemDataTransport(String packageName, int userId, int associationId,
                ParcelFileDescriptor fd) {
            attachSystemDataTransport_enforcePermission();

            mTransportManager.attachSystemDataTransport(associationId, fd);
        }

        @Override
        @EnforcePermission(DELIVER_COMPANION_MESSAGES)
        public void detachSystemDataTransport(String packageName, int userId, int associationId) {
            detachSystemDataTransport_enforcePermission();

            mTransportManager.detachSystemDataTransport(associationId);
        }

        @Override
        @EnforcePermission(MANAGE_COMPANION_DEVICES)
        public void overrideTransportType(int typeOverride) {
            overrideTransportType_enforcePermission();

            mTransportManager.overrideTransportType(typeOverride);

            // When using raw channel, enable a random key provider for testing
            if (typeOverride == 1) {
                mTrustedDeviceProcessor.addPskProvider(new RandomKeyProvider());
            } else {
                mTrustedDeviceProcessor.removePskProvider(RandomKeyProvider.NAME);
            }
        }

        @Override
        @PermissionManuallyEnforced()
        public void enableSystemDataSync(int associationId, int flags) {
            enforceCallerCanInteractWithSystemDataSyncFlags(getContext(), flags);

            mDataSyncProcessor.enableSystemDataSync(associationId, flags);
        }

        @Override
        @PermissionManuallyEnforced()
        public void disableSystemDataSync(int associationId, int flags) {
            enforceCallerCanInteractWithSystemDataSyncFlags(getContext(), flags);

            mDataSyncProcessor.disableSystemDataSync(associationId, flags);
        }

        @Override
        public void enablePermissionsSync(int associationId) {
            enforceCallerIsSystem();

            mSystemDataTransferProcessor.enablePermissionsSync(associationId);
        }

        @Override
        public void disablePermissionsSync(int associationId) {
            enforceCallerIsSystem();

            mSystemDataTransferProcessor.disablePermissionsSync(associationId);
        }

        @Override
        public PermissionSyncRequest getPermissionSyncRequest(int associationId) {
            enforceCallerIsSystem();

            return mSystemDataTransferProcessor.getPermissionSyncRequest(associationId);
        }

        @Override
        @EnforcePermission(REQUEST_COMPANION_SELF_MANAGED)
        public void notifySelfManagedDeviceAppeared(int associationId) {
            notifySelfManagedDeviceAppeared_enforcePermission();

            mDevicePresenceProcessor.notifySelfManagedDevicePresenceEvent(associationId, true);
        }

        @Override
        @EnforcePermission(REQUEST_COMPANION_SELF_MANAGED)
        public void notifySelfManagedDeviceDisappeared(int associationId) {
            notifySelfManagedDeviceDisappeared_enforcePermission();

            mDevicePresenceProcessor.notifySelfManagedDevicePresenceEvent(associationId, false);
        }

        @Override
        @EnforcePermission(anyOf = { USE_COMPANION_TRANSPORTS, ACCESS_COMPANION_MESSAGE_PCC})
        public void requestAction(@NonNull ActionRequest request, @NonNull String serviceName,
                @NonNull String callingPackageName, int[] associationIds) {
            requestAction_enforcePermission();

            // If caller doesn't have the blanket permissions to use transports,
            // enforce that the caller can manage every association.
            if (!checkCallerCanUseSystemDataTransports(getContext())) {
                enforceValidServiceName(serviceName, callingPackageName);
                for (int associationId : associationIds) {
                    mAssociationStore.getAssociationWithCallerChecks(associationId);
                }
            }

            android.os.Trace.asyncTraceForTrackBegin(
                    Trace.TRACE_TAG_SYSTEM_SERVER,
                    "CompanionDeviceManager",
                    "requestAction",
                    request.hashCode()
            );

            mActionRequestProcessor.requestAction(request, serviceName, associationIds);
        }

        @Override
        @EnforcePermission(USE_COMPANION_TRANSPORTS)
        public void setRequestActionAllowList(List<String> allowList) {
            setRequestActionAllowList_enforcePermission();

            mActionRequestProcessor.setRequestActionAllowList(allowList);
        }

        @Override
        public boolean isCompanionApplicationBound(String packageName, int userId) {
            return mCompanionAppBinder.isCompanionApplicationBound(userId, packageName);
        }

        @Override
        @EnforcePermission(ASSOCIATE_COMPANION_DEVICES)
        public void createAssociation(String packageName, String macAddress, int userId,
                byte[] certificate) {
            createAssociation_enforcePermission();

            if (!getContext().getPackageManager().hasSigningCertificate(
                    packageName, certificate, CERT_INPUT_SHA256)) {
                Slog.e(TAG, "Given certificate doesn't match the package certificate.");
                return;
            }

            final MacAddress macAddressObj = MacAddress.fromString(macAddress);
            mAssociationRequestsProcessor.createAssociation(userId, packageName, macAddressObj,
                    null, null, null, false, null, null, null, false, new HashSet<>(), false);
        }

        private void checkCanCallNotificationApi(String callingPackage, int userId) {
            enforceCallerIsSystemOr(userId, callingPackage);

            if (getCallingUid() == SYSTEM_UID) return;

            enforceUsesCompanionDeviceFeature(getContext(), userId, callingPackage);
            checkState(!ArrayUtils.isEmpty(
                            mAssociationStore.getActiveAssociationsByPackage(userId,
                                    callingPackage)),
                    "App must have an association before calling this API");
        }

        private void checkCallerCanSkipRoleGrant() {
            final Context context =
                    getContext().createContextAsUser(Binder.getCallingUserHandle(), 0);
            final KeyguardManager keyguardManager =
                    context.getSystemService(KeyguardManager.class);
            if (keyguardManager != null && keyguardManager.isKeyguardSecure()) {
                throw new SecurityException("Skipping CDM role grant requires insecure keyguard.");
            }
            if (getContext().checkCallingPermission(ASSOCIATE_COMPANION_DEVICES)
                    != PERMISSION_GRANTED) {
                throw new SecurityException(
                        "Skipping CDM role grant requires ASSOCIATE_COMPANION_DEVICES permission.");
            }
        }

        private void enforceCallerIsSystem() {
            if (UserHandle.getAppId(Binder.getCallingUid()) != SYSTEM_UID) {
                throw new SecurityException("Caller must be system UID");
            }
        }

        @Override
        public boolean canPairWithoutPrompt(String packageName, String macAddress, int userId) {
            final AssociationInfo association =
                    mAssociationStore.getFirstAssociationByAddress(
                            userId, packageName, macAddress);
            if (association == null) {
                return false;
            }
            return System.currentTimeMillis() - association.getTimeApprovedMs()
                    < PAIR_WITHOUT_PROMPT_WINDOW_MS;
        }

        @Override
        public DeviceId setDeviceId(int associationId, DeviceId deviceId) {
            Slog.i(TAG, "Setting DeviceId=[" + deviceId + "] to id=[" + associationId + "]...");

            DeviceId newDeviceId = deviceId != null
                    ? new DeviceId.Builder()
                    .setCustomId(deviceId.getCustomId())
                    .setMacAddress(deviceId.getMacAddress())
                    .setKey(generateRandom128BitKey())
                    .build()
                    : null;
            mAssociationStore.updateAssociation(associationId,
                    a -> (new AssociationInfo.Builder(a))
                            .setDeviceId(newDeviceId)
                            .build());

            return newDeviceId;
        }

        @Override
        @EnforcePermission(ACCESS_COMPANION_INFO)
        public AssociationInfo getAssociationByDeviceId(int userId, DeviceId deviceId) {
            getAssociationByDeviceId_enforcePermission();

            return mAssociationStore.getAssociationByDeviceId(userId, deviceId);
        }

        @Override
        @EnforcePermission(MANAGE_COMPANION_DEVICES)
        public void setLocalMetadata(int userId, String key, PersistableBundle value) {
            setLocalMetadata_enforcePermission();

            mDataSyncProcessor.setLocalMetadata(userId, key, value);
        }

        @Override
        @EnforcePermission(MANAGE_COMPANION_DEVICES)
        public PersistableBundle getLocalMetadata(int userId) {
            getLocalMetadata_enforcePermission();

            return mDataSyncProcessor.getLocalMetadata(userId);
        }

        @Override
        public byte[] getBackupPayload(int userId) {
            enforceCallerIsSystem();

            return mBackupRestoreProcessor.getBackupPayload(userId);
        }

        @Override
        @EnforcePermission(REQUEST_COMPANION_SELF_MANAGED)
        public void notifyDevicePresence(int associationId, @NonNull DevicePresenceEvent event) {
            notifyDevicePresence_enforcePermission();

            android.os.Trace.asyncTraceForTrackBegin(
                    Trace.TRACE_TAG_SYSTEM_SERVER,
                    "CompanionDeviceManager",
                    "notifyDevicePresence",
                    event.hashCode()
            );

            mDevicePresenceProcessor.processSelfManagedDevicePresenceEvent(associationId, event);
        }

        @Override
        @EnforcePermission(MANAGE_COMPANION_DEVICES)
        public boolean isDevicePresent(int associationId) {
            isDevicePresent_enforcePermission();

            mAssociationStore.getAssociationWithCallerChecks(associationId);
            return mDevicePresenceProcessor.isDevicePresent(associationId);
        }

        @Override
        public void notifyActionResult(int associationId, @NonNull ActionResult result) {
            android.os.Trace.asyncTraceForTrackBegin(
                    Trace.TRACE_TAG_SYSTEM_SERVER,
                    "CompanionDeviceManager",
                    "notifyActionResult",
                    result.hashCode()
            );
            mActionRequestProcessor.processActionResult(associationId, result);
        }

        @Override
        public void applyRestoredPayload(byte[] payload, int userId) {
            enforceCallerIsSystem();

            mBackupRestoreProcessor.applyRestoredPayload(payload, userId);
        }

        @Override
        @EnforcePermission(USE_COMPANION_TRANSPORTS)
        public void setOnDevicePresenceEventListener(int[] associationIds, String serviceName,
                IOnDevicePresenceEventListener listener, int userId) {
            setOnDevicePresenceEventListener_enforcePermission();
            enforceCallerIsSystemOrCanInteractWithUserId(getContext(), userId);

            mDevicePresenceProcessor.setOnDevicePresenceEventListener(
                    associationIds, serviceName, listener);
        }

        @Override
        @EnforcePermission(USE_COMPANION_TRANSPORTS)
        public void removeOnDevicePresenceEventListener(@NonNull String serviceName,
                int userId) {
            removeOnDevicePresenceEventListener_enforcePermission();
            enforceCallerIsSystemOrCanInteractWithUserId(getContext(), userId);

            mDevicePresenceProcessor.removeOnDevicePresenceEventListener(serviceName);
        }

        @Override
        @EnforcePermission(anyOf = { USE_COMPANION_TRANSPORTS, ACCESS_COMPANION_MESSAGE_PCC })
        public void setOnActionResultListener(@NonNull int[] associationIds,
                @NonNull String serviceName, @NonNull String callingPackageName,
                IOnActionResultListener listener) {
            setOnActionResultListener_enforcePermission();

            // If caller doesn't have the blanket permissions to use transports,
            // enforce that the caller can manage every association.
            if (!checkCallerCanUseSystemDataTransports(getContext())) {
                enforceValidServiceName(serviceName, callingPackageName);
                for (int associationId : associationIds) {
                    mAssociationStore.getAssociationWithCallerChecks(associationId);
                }
            }

            mActionRequestProcessor.setOnActionResultListener(
                    associationIds, serviceName, listener);
        }

        @Override
        @EnforcePermission(anyOf = { USE_COMPANION_TRANSPORTS, ACCESS_COMPANION_MESSAGE_PCC })
        public void clearOnActionResultListener(@NonNull String serviceName,
                @NonNull String callingPackageName) {
            clearOnActionResultListener_enforcePermission();

            // If caller doesn't have the blanket permissions to use transports,
            // enforce that the caller can use the serviceName.
            if (!checkCallerCanUseSystemDataTransports(getContext())) {
                enforceValidServiceName(serviceName, callingPackageName);
            }

            mActionRequestProcessor.clearOnActionResultListener(serviceName);
        }

        @Override
        @PermissionManuallyEnforced
        public boolean isSystemDataTransportAttached(int associationId) {
            mAssociationStore.getAssociationWithCallerChecks(associationId);
            return mTransportManager.getTransport(associationId) != null;
        }

        @Override
        public int handleShellCommand(@NonNull ParcelFileDescriptor in,
                @NonNull ParcelFileDescriptor out, @NonNull ParcelFileDescriptor err,
                @NonNull String[] args) {
            return new CompanionDeviceShellCommand(CompanionDeviceManagerService.this,
                    mAssociationStore, mDevicePresenceProcessor, mTransportManager,
                    mSystemDataTransferProcessor, mAssociationRequestsProcessor,
                    mBackupRestoreProcessor, mDisassociationProcessor, mDataSyncProcessor)
                    .exec(this, in.getFileDescriptor(), out.getFileDescriptor(),
                            err.getFileDescriptor(), args);
        }

        @Override
        public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter out,
                @Nullable String[] args) {
            if (!DumpUtils.checkDumpAndUsageStatsPermission(getContext(), TAG, out)) {
                return;
            }

            mAssociationStore.dump(out);
            mDevicePresenceProcessor.dump(out);
            mCompanionAppBinder.dump(out);
            mTransportManager.dump(out);
            mSystemDataTransferRequestStore.dump(out);
        }
    }

    private final PackageMonitor mPackageMonitor = new PackageMonitor() {
        @Override
        public void onPackageRemoved(String packageName, int uid) {
            synchronized (mPackageLock) {
                onPackageRemoveOrDataClearedInternal(getChangingUserId(), packageName);
            }
        }

        @Override
        public void onPackageDataCleared(String packageName, int uid) {
            synchronized (mPackageLock) {
                onPackageRemoveOrDataClearedInternal(getChangingUserId(), packageName);
            }
        }

        @Override
        public void onPackageModified(@NonNull String packageName) {
            synchronized (mPackageLock) {
                onPackageModifiedInternal(getChangingUserId(), packageName);
            }
        }

        @Override
        public void onPackageAdded(String packageName, int uid) {
            synchronized (mPackageLock) {
                onPackageAddedInternal(getChangingUserId(), packageName);
            }
        }
    };

    private class LocalService implements CompanionDeviceManagerServiceInternal {

        @Override
        public void removeInactiveSelfManagedAssociations() {
            mDisassociationProcessor.removeIdleSelfManagedAssociations();
        }

        @Override
        public void registerCallMetadataSyncCallback(CrossDeviceSyncControllerCallback callback,
                @CrossDeviceSyncControllerCallback.Type int type) {
            if (CompanionDeviceConfig.isEnabled(
                    CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)) {
                mCrossDeviceSyncController.registerCallMetadataSyncCallback(callback, type);
            }
        }

        @Override
        public void crossDeviceSync(int userId, Collection<CrossDeviceCall> calls) {
            if (CompanionDeviceConfig.isEnabled(
                    CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)) {
                mCrossDeviceSyncController.syncToAllDevicesForUserId(userId, calls);
            }
        }

        @Override
        public void crossDeviceSync(AssociationInfo associationInfo,
                Collection<CrossDeviceCall> calls) {
            if (CompanionDeviceConfig.isEnabled(
                    CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)) {
                mCrossDeviceSyncController.syncToSingleDevice(associationInfo, calls);
            }
        }

        @Override
        public void sendCrossDeviceSyncMessage(int associationId, byte[] message) {
            if (CompanionDeviceConfig.isEnabled(
                    CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)) {
                mCrossDeviceSyncController.syncMessageToDevice(associationId, message);
            }
        }

        @Override
        public void sendCrossDeviceSyncMessageToAllDevices(int userId, byte[] message) {
            if (CompanionDeviceConfig.isEnabled(
                    CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)) {
                mCrossDeviceSyncController.syncMessageToAllDevicesForUserId(userId, message);
            }
        }

        @Override
        public void addSelfOwnedCallId(String callId) {
            if (CompanionDeviceConfig.isEnabled(
                    CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)) {
                mCrossDeviceSyncController.addSelfOwnedCallId(callId);
            }
        }

        @Override
        public void removeSelfOwnedCallId(String callId) {
            if (CompanionDeviceConfig.isEnabled(
                    CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)) {
                mCrossDeviceSyncController.removeSelfOwnedCallId(callId);
            }
        }
    }
}
