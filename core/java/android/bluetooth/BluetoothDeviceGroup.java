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

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.bluetooth.IBluetoothGroupCallback;
import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;


/**
 * This class provides the public APIs to perform operations of
 * the Group Identification Profile.
 *
 * <p> This class provides functionalities to enable communication with remote
 * devices which are grouped together to achieve common use cases in
 * synchronized manner.
 * <p> BluetoothDeviceGroup is a proxy object for controlling the Bluetooth Group
 * Service via IPC. Use {@link BluetoothAdapter#getProfileProxy} to get the BluetoothDeviceGroup
 * proxy object. Use {@link BluetoothAdapter#closeProfileProxy} to close connection
 * of the BluetoothDeviceGroup proxy object with the profile service.
 * <p> BluetoothDeviceGroup proxy object can be used to identify and fetch Device Group.
 * Also, API’s are exposed to get exclusive access of group devices for critical
 * operations. Implement BluetoothGroupCallback to get results invoked API's.
 *
 * @hide
 */


public final class BluetoothDeviceGroup implements BluetoothProfile {
    private static final String TAG = "BluetoothDeviceGroup";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    /** Group Client App is registerd for callbacks successfully */
    public static final int APP_REGISTRATION_SUCCESSFUL = 0;
    /** Group Client App registration failed for callbacks */
    public static final int APP_REGISTRATION_FAILED = 1;

    /** Group Discovery Status when discovery is started */
    public static final int GROUP_DISCOVERY_STARTED = 0x00;

    /** Group Discovery Status when discovery is stopped */
    public static final int GROUP_DISCOVERY_STOPPED = 0x01;

    /** When Application starts Group discovery */
    public static final int DISCOVERY_STARTED_BY_APPL = 0x00;

    /** When Application stops Group discovery */
    public static final int DISCOVERY_STOPPED_BY_APPL = 0x01;

    /** When Group discovery is started as a result of
     * change in Group property. */
    public static final int DISCOVERY_STARTED_GROUP_PROP_CHANGED = 0x02;

    /** When all devices of Group are discovered */
    public static final int DISCOVERY_COMPLETED = 0x03;

    /** Group discovery by timeeut. Group device not found in 10 sec. */
    public static final int DISCOVERY_STOPPED_BY_TIMEOUT = 0x04;

    /** Invalid params are provided for Group discovery */
    public static final int DISCOVERY_NOT_STARTED_INVALID_PARAMS = 0x05;

    /** Value to release Exclusive Access */
    public static final int ACCESS_RELEASED = 0x01;

    /** Value to acquire Exclusive Access */
    public static final int ACCESS_GRANTED = 0x02;

    /** When exclusive access is changed to #ACCESS_RELEASED for all reqested Group devices */
    public static final int EXCLUSIVE_ACCESS_RELEASED = 0x00;

    /** When exclusive access of the Group device is changed to #ACCESS_RELEASED by timeout */
    public static final int EXCLUSIVE_ACCESS_RELEASED_BY_TIMEOUT = 0x01;

    /** When exclusive access of all requested Group devices is changed to #ACCESS_GRANTED */
    public static final int ALL_DEVICES_GRANTED_ACCESS = 0x02;

    /** When exclusive access of some of the requested Group devices is changed to #ACCESS_GRANTED
      * because of timeout in #setExclusiveAccess operation */
    public static final int SOME_GRANTED_ACCESS_REASON_TIMEOUT = 0x03;

    /** When access value of some of the requested Group devices is changed to #ACCESS_GRANTED
      * because some of the Group devices were disconnected */
    public static final int SOME_GRANTED_ACCESS_REASON_DISCONNECTION = 0x04;

    /** When Exclusive Access couldnt be fetched as one of the Group devices denied
      * to set value to #ACCESS_DENIED*/
    public static final int ACCESS_DENIED = 0x05;

    /** Suggests that invalid parameters are passed in #setExclusiveAccess request*/
    public static final int INVALID_ACCESS_REQ_PARAMS = 0x06;

    /** Invalid Group ID */
    public static final int INVALID_GROUP_ID = 0x10;

    /** MIN GROUP_ID Value*/
    public static final int GROUP_ID_MIN = 0x00;
    /** MAX GROUP_ID Value*/
    public static final int GROUP_ID_MAX = 0x0F;

    /** Invalid APP ID */
    public static final int INVALID_APP_ID = 0x10;

    /** MIN APP_ID Value*/
    public static final int APP_ID_MIN = 0x00;
    /** MAX APP_ID Value*/
    public static final int APP_ID_MAX = 0x0F;

    public static final String ACTION_CONNECTION_STATE_CHANGED =
                "android.bluetooth.group.profile.action.CONNECTION_STATE_CHANGED";

    private int mAppId;
    private boolean mAppRegistered = false;
    private Handler mHandler;
    private BluetoothGroupCallback mCallback;

    private BluetoothAdapter mAdapter;
    private final BluetoothProfileConnector<IBluetoothDeviceGroup> mProfileConnector =
        new BluetoothProfileConnector(this, BluetoothProfile.GROUP_CLIENT,
                "BluetoothDeviceGroup", IBluetoothDeviceGroup.class.getName()) {
            @Override
            public IBluetoothDeviceGroup getServiceInterface(IBinder service) {
                return IBluetoothDeviceGroup.Stub.asInterface(Binder.allowBlocking(service));
            }
        };

    /**
     * Creates a BluetoothDeviceGroup proxy object for interacting with the local
     * Bluetooth Service which handles Group operations.
     * @hide
     */
    /*package*/ BluetoothDeviceGroup(Context context, ServiceListener listener) {
        mProfileConnector.connect(context, listener);
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        IBluetoothManager mgr = mAdapter.getBluetoothManager();
        if (mgr != null) {
            try {
                mgr.registerStateChangeCallback(mBluetoothStateChangeCallback);
            } catch (RemoteException re) {
                Log.e(TAG, "", re);
            }
        }
    }

    private final IBluetoothStateChangeCallback mBluetoothStateChangeCallback =
               new IBluetoothStateChangeCallback.Stub() {
                   public void onBluetoothStateChange(boolean up) {
                       if (!up) {
                           mAppRegistered = false;
                       }
                   }
               };

    /**
     * Close this BluetoothGroupDevice client object.
     *
     * Application should call this method as soon as it is done with
     * Group operations.
     */
    /*package*/ void close() {
        if (VDBG) log("close()");

        mAppRegistered = false;
        final IBluetoothDeviceGroup service = getService();
        if (service != null) {
            try {
                service.unregisterGroupClientApp(mAppId);
            } catch (RemoteException e) {
                Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            }
        }

        mProfileConnector.disconnect();
    }

    /**
     * @hide
     */
    private IBluetoothDeviceGroup getService() {
        return mProfileConnector.getService();
    }

    /**
     * {@inheritDoc}
     */
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

    private boolean isEnabled() {
        return mAdapter.getState() == BluetoothAdapter.STATE_ON;
    }

    private static boolean isValidDevice(BluetoothDevice device) {
        return device != null &&
                BluetoothAdapter.checkBluetoothAddress(device.getAddress());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getConnectionState(BluetoothDevice device) {
        if (VDBG) log("getState(" + device + ")");
        return BluetoothProfile.STATE_DISCONNECTED;
    }

    private final IBluetoothGroupCallback.Stub mBluetoothGroupCallback =
            new IBluetoothGroupCallback.Stub() {

        @Override
        public void onGroupClientAppRegistered(int status, int appId) {
            if (DBG) {
                Log.d(TAG, "onGroupClientAppRegistered() - status=" + status
                        + " appId = " + appId);
            }

            if (status != APP_REGISTRATION_SUCCESSFUL) {
              mAppRegistered = false;
            }

            mAppId = appId;
            runOrQueueCallback(new Runnable() {
                @Override
                public void run() {
                    final BluetoothGroupCallback callback = mCallback;
                    if (callback != null) {
                        callback.onGroupClientAppRegistered(status, appId);
                    }
                }
            });
        }

        @Override
        public void onGroupClientAppUnregistered(int status) {
            if (DBG) {
                Log.d(TAG, "onGroupClientAppUnregistered() - status=" + status
                        + " mAppId=" + mAppId);
            }
        }

        @Override
        public void onConnectionStateChanged (int state, BluetoothDevice device) {
            if (DBG) {
                Log.d(TAG, "onConnectionStateChanged() - state = " + state
                        + " device = " + device);
            }

            runOrQueueCallback(new Runnable() {
                @Override
                public void run() {
                    final BluetoothGroupCallback callback = mCallback;
                    if (callback != null) {
                        callback.onConnectionStateChanged(state, device);
                    }
                }
            });
        }

        @Override
        public void onNewGroupFound(int groupId, BluetoothDevice device,
                ParcelUuid uuid) {
            if (DBG) {
                Log.d(TAG, "onNewGroupFound() - appId = " + mAppId +
                        ", groupId = " + groupId + ", device: " + device +
                        ", Including service UUID: " + uuid.toString());
            }

            runOrQueueCallback(new Runnable() {
                @Override
                public void run() {
                    final BluetoothGroupCallback callback = mCallback;
                    if (callback != null) {
                        callback.onNewGroupFound(groupId, device, uuid.getUuid());
                    }
                }
            });
        }

        @Override
        public void onGroupDiscoveryStatusChanged(int groupId, int status, int reason) {
            if (DBG) {
                Log.d(TAG, "onGroupDiscoveryStatusChanged() - appId = " + mAppId +
                        ", groupId = " + groupId + ", status: " + status +
                        ", reason: " + reason);
            }

            runOrQueueCallback(new Runnable() {
                @Override
                public void run() {
                    final BluetoothGroupCallback callback = mCallback;
                    if (callback != null) {
                        callback.onGroupDiscoveryStatusChanged(groupId, status, reason);
                    }
                }
            });
        }

        @Override
        public void onGroupDeviceFound(int groupId, BluetoothDevice device) {
            if (DBG) {
                Log.d(TAG, "onGroupDeviceFound() - appId = " + mAppId + ", device = " + device);
            }

            runOrQueueCallback(new Runnable() {
                @Override
                public void run() {
                    final BluetoothGroupCallback callback = mCallback;
                    if (callback != null) {
                        callback.onGroupDeviceFound(groupId, device);
                    }
                }
            });
        }

        @Override
        public void onExclusiveAccessChanged(int groupId, int value, int status,
                List<BluetoothDevice> devices) {
            if (DBG) {
                Log.d(TAG, "onExclusiveAccessChanged() - appId = " + mAppId
                        + ", groupId = " + groupId + ", value = " + value
                        + " accessStatus = " + status + ", devices: " + devices);
            }

            runOrQueueCallback(new Runnable() {
                @Override
                public void run() {
                    final BluetoothGroupCallback callback = mCallback;
                    if (callback != null) {
                        callback.onExclusiveAccessChanged(groupId, value, status, devices);
                    }
                }
            });
        }

        @Override
        public void onExclusiveAccessStatusFetched(int groupId, int accessValue) {
        }

        @Override
        public void onExclusiveAccessAvailable (int groupId, BluetoothDevice device) {
            if (DBG) {
                Log.d(TAG, "onExclusiveAccessAvailable() - appId = " + mAppId
                        + ", groupId = " + groupId + ", device: " + device);
            }

            runOrQueueCallback(new Runnable() {
                @Override
                public void run() {
                    final BluetoothGroupCallback callback = mCallback;
                    if (callback != null) {
                        callback.onExclusiveAccessAvailable(groupId, device);
                    }
                }
            });
        }
    };

    /**
     * Registers callbacks to be received by application on completion of
     * required operations.
     *
     * @param callbacks    Reference of BluetoothGroupCallback implemented in
     *                     application.
     * @param handler      handler that will receive asynchronous callbacks.
     * @return true, if operation was initiated successfully.
     */
    public boolean registerGroupClientApp(BluetoothGroupCallback callbacks, Handler handler) {
        if (DBG) log("registerGroupClientApp() mAppRegistered = " + mAppRegistered);

        /* Check if app is trying multiple registrations */
        if (mAppRegistered) {
            Log.e(TAG, "App already registered.");
            return false;
        }

        mHandler = handler;
        mCallback = callbacks;

        final IBluetoothDeviceGroup service = getService();
        if (service == null) {
            Log.e(TAG, "Proxy not attached to Profile Service. Can't register App.");
            return false;
        }

        mAppRegistered = true;
        try {
            UUID uuid = UUID.randomUUID();
            service.registerGroupClientApp(new ParcelUuid(uuid), mBluetoothGroupCallback);
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
        }
        return true;
     }

    /**
     * Starts discovery of the remaining Group devices which are part of the group.
     *
     * <p> This API should be called when onNewGroupFound() is received in the
     * application and when given group is the required device group. This
     * API can also be used to rediscover the undiscovered Group devices.
     *
     * <p> To the application that started group discovery,
     * {@link BluetoothGroupCallback#onGroupDeviceFound} callback will be given when
     * a new Group device is found and {@link BluetoothGroupCallback#onGroupDiscoveryStatusChanged}
     * callback will be given when discovery is started.
     *
     * @param groupId    Identifier of the Group for which group
     *                 discovery has to be started.
     * @return true, if operation was initiated successfully.
     */
    public boolean startGroupDiscovery(int groupId) {
        if (DBG) log("startGroupDiscovery() : groupId = " + groupId);

        if (!mAppRegistered) {
            Log.e(TAG, "App not registered for Group operations." +
                    " Register App using registerGroupClientApp");
            return false;
        }

        final IBluetoothDeviceGroup service = getService();
        if (service == null) {
            Log.e(TAG, "Proxy is not attached to Profile Service. Can't start group discovery");
            return false;
        }

        try {
            UUID uuid = UUID.randomUUID();
            service.startGroupDiscovery(mAppId ,groupId);
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
        }
        return true;
    }

    /**
     * Stops ongoing group discovery for Group identified by groupId.
     *
     * <p> {@link BluetoothGroupCallback#onGroupDiscoveryStatusChanged} is given
     * when group discovery is stopped.
     *
     * @param groupId  Identifier of the Group for which group
     *                 discovery has to be stopped.
     * @return true, if operation was initiated successfully.
     */
    public boolean stopGroupDiscovery(int groupId) {
        if (DBG) log("stopGroupDiscovery() : groupId = " + groupId);

        if (!mAppRegistered) {
            Log.e(TAG, "App not registered for Group operations." +
                    " Register App using registerGroupClientApp");
            return false;
        }

        final IBluetoothDeviceGroup service = getService();
        if (service == null) {
            Log.e(TAG, "Proxy is not attached to Profile Service. Can't Stop group discovery");
            return false;
        }

        try {
            service.stopGroupDiscovery(mAppId ,groupId);
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
        }
        return true;
    }

    /**
     * Fetches already discovered Groups.
     *
     * @return    List of DeviceGroup that are already discovered.
     */
    public List<DeviceGroup> getDiscoveredGroups() {
        if (DBG) log("getDiscoveredGroups()");

        if (!mAppRegistered) {
            Log.e(TAG, "App not registered for Group operations." +
                    " Register App using registerGroupClientApp");
            return null;
        }

        final IBluetoothDeviceGroup service = getService();
        if (service == null) {
            Log.e(TAG, "Proxy is not attached to Profile Service. Can't fetch Groups.");
            return null;
        }

        try {
            List<DeviceGroup> groups = service.getDiscoveredGroups();
            return groups;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
        }

        return null;
    }

    /**
     * Fetch details of a already discovered Group identified by groupId.
     *
     * @param groupId Identifier of the Group for which Group details are required.
     * @return        Required DeviceGroup.
     */
    public DeviceGroup getGroup(int groupId) {
        if (DBG) log("getGroup() : groupId = " + groupId);

        if (!mAppRegistered) {
            Log.e(TAG, "App not registered for Group operations." +
                    " Register App using registerGroupClientApp");
            return null;
        }

        final IBluetoothDeviceGroup service = getService();
        if (service == null) {
            Log.e(TAG, "Proxy is not attached to Profile Service. Can't fetch Group.");
            return null;
        }

        try {
            DeviceGroup group = service.getDeviceGroup(groupId);
            return group;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
        }

        return null;
    }

    /**
     * Get Group Identifier of the remote device to which it belongs.
     *
     * @param device        BluetoothDevice instance of the remote device.
     * @param uuid          ParcelUuid of the primary service in which this
     *                      Group Service is included.
     * @return              Group identifier of the required device.
     */
    public int getRemoteDeviceGroupId (BluetoothDevice device, ParcelUuid uuid) {
        if (DBG) log("getRemoteDeviceGroupId() : device = " + device);

        if (!mAppRegistered) {
            Log.e(TAG, "App not registered for Group operations." +
                    " Register App using registerGroupClientApp");
            return INVALID_GROUP_ID;
        }

        final IBluetoothDeviceGroup service = getService();
        if (service == null) {
            Log.e(TAG, "Proxy is not attached to Profile Service." +
                    "Can't get group id for device.");
            return INVALID_GROUP_ID;
        }

        try {
            return service.getRemoteDeviceGroupId(device, uuid);
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
        }
        return INVALID_GROUP_ID;
    }

    /**
     * Suggests whether discovery for a given Group is ongoing.
     *
     * @param groupId  Identifier of the Group for which discovery
     *                 status is to be known.
     * @return         true, if group discovery is ongoing for mentioned group.
     *                 Otherwise, false.
     */
    public boolean isGroupDiscoveryInProgress (int groupId) {
        if (DBG) log("isGroupDiscoveryInProgress() : groupId = " + groupId);

        if (!mAppRegistered) {
            Log.e(TAG, "App not registered for Group operations." +
                    " Register App using registerGroupClientApp");
            return false;
        }

        final IBluetoothDeviceGroup service = getService();
        if (service == null) {
            Log.e(TAG, "Proxy is not attached to Profile Service.Can't get discovery status.");
            return false;
        }

        try {
            return service.isGroupDiscoveryInProgress(groupId);
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
        }
        return false;
    }

    /**
     * Acquires/Releases exclusive access of a given Group or subgroup.
     * The result of this operation is returned in
     * {@link BluetoothGroupCallback#onExclusiveAccessChanged} callback.
     *
     * @param groupId  Identifier of the Group.
     * @param devices  List of BluetoothDevice for which access has to be changed.
     *                 If this parameter is passed as null, all Group devices in the
     *                 mentioned group will be considered for request.
     * @param value    Access which required to be changed.
     *                 0x01 – Access released ({@link #ACCESS_RELEASED}).
     *                 0x02 - Access granted ({@link #ACCESS_GRANTED}).
     * @return true, if operation was initiated successfully.
     */
    public boolean setExclusiveAccess(int groupId, List<BluetoothDevice> devices, int value) {
        if (DBG) log("setExclusiveAccess() : groupId = " + groupId +
                        ", access value: " + value);

        if (!mAppRegistered) {
            Log.e(TAG, "App not registered for Group operations." +
                    " Register App using registerGroupClientApp");
            return false;
        }

        final IBluetoothDeviceGroup service = getService();
        if (service == null) {
            Log.e(TAG, "Proxy is not attached to Profile Service. Can't proceed.");
            return false;
        }

        try {
            service.setExclusiveAccess(mAppId, groupId, devices, value);
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
        }
        return true;
    }

    /**
     * Returns Status of the exclusive access for mentioned Group.
     *
     * @param groupId  Identifier of the Group.
     * @param devices  List of BluetoothDevice for which access value has to be known.
     *                 If this parameter is passed as null, all Group devices in the
     *                 mentioned group will be queried for access status.
     * @return true, if operation was initiated successfully.
     * @hide
     */
    public boolean getExclusiveAccessStatus (int groupId, List<BluetoothDevice> devices) {
        if (DBG) log("getExclusiveAccessStatus() : groupId = " + groupId);

        if (!mAppRegistered) {
            Log.e(TAG, "App not registered for Group operations." +
                    " Register App using registerGroupClientApp");
            return false;
        }

        final IBluetoothDeviceGroup service = getService();
        if (service == null) {
            Log.e(TAG, "Proxy is not attached to Profile Service." +
                    " Can't get exclusive access status.");
            return false;
        }

        try {
            service.getExclusiveAccessStatus(mAppId, groupId, devices);
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
        }
        return true;
    }

    /**
     * Creates GATT Connection with remote device for Group Operations.
     *
     * <p> This API acts as trigger to start service discovery to identify
     * new device group on remote device once connection has been established
     * successfully. Application calling connect will get
     * {@link BluetoothGroupCallback#onNewGroupFoundcallback} after
	 * {@link #onConnectionStateChanged} (once connection has been established
	 * and group discovery is completed.)
     *
     * @param device  BluetoothDevice instance od remote device with which
     *                Connection is required to be established.
     * @return true, if operation was initiated successfully.
     */
    public boolean connect (BluetoothDevice device) {
        if (DBG) log("connect : device = " + device);

        if (!mAppRegistered) {
            Log.e(TAG, "App not registered for Group operations." +
                    " Register App using registerGroupClientApp");
            return false;
        }

        final IBluetoothDeviceGroup service = getService();
        if (service == null) {
            Log.e(TAG, "Proxy is not attached to Profile Service. Can't connect.");
            return false;
        }

        try {
            service.connect(mAppId, device);
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
        }
        return true;
    }

    /**
     * Initiates GATT disconnection for Group Operations.
     *
     * @param device  BluetoothDevice instance of remote device.
     *                This API must be called if application is not
     *                interested in any Group operations.
     * @return true, if operation was initiated successfully.
     */
    public boolean disconnect (BluetoothDevice device) {
        if (DBG) log("disconnect : device = " + device);

        if (!mAppRegistered) {
            Log.e(TAG, "App not registered for Group operations." +
                    " Register App using registerGroupClientApp");
            return false;
        }

        final IBluetoothDeviceGroup service = getService();
        if (service == null) {
            Log.e(TAG, "Proxy is not attached to Profile Service. Can't disconnect");
            return false;
        }

        try {
            service.disconnect(mAppId, device);
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
        }
        return true;
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }

    /**
     * Queue the runnable on a {@link Handler} provided by the user, or execute the runnable
     * immediately if no Handler was provided.
     */
    private void runOrQueueCallback(final Runnable cb) {
        if (mHandler == null) {
            try {
                cb.run();
            } catch (Exception ex) {
                Log.w(TAG, "Unhandled exception in callback", ex);
            }
        } else {
            mHandler.post(cb);
        }
    }
}
