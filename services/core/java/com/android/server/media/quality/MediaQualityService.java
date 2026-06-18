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

package com.android.server.media.quality;

import static android.media.quality.AmbientBacklightEvent.AMBIENT_BACKLIGHT_EVENT_ENABLED;
import static android.media.quality.AmbientBacklightEvent.AMBIENT_BACKLIGHT_EVENT_DISABLED;
import static android.media.quality.AmbientBacklightEvent.AMBIENT_BACKLIGHT_EVENT_METADATA_AVAILABLE;
import static android.media.quality.AmbientBacklightEvent.AMBIENT_BACKLIGHT_EVENT_INTERRUPTED;

import android.annotation.NonNull;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.hardware.audio.effect.DefaultExtension;
import android.hardware.tv.mediaquality.AmbientBacklightColorFormat;
import android.hardware.tv.mediaquality.IMediaQuality;
import android.hardware.tv.mediaquality.IPictureProfileAdjustmentListener;
import android.hardware.tv.mediaquality.IPictureProfileChangedListener;
import android.hardware.tv.mediaquality.ISoundProfileAdjustmentListener;
import android.hardware.tv.mediaquality.ISoundProfileChangedListener;
import android.hardware.tv.mediaquality.ParamCapability;
import android.hardware.tv.mediaquality.ParameterDefaultValue;
import android.hardware.tv.mediaquality.PictureParameter;
import android.hardware.tv.mediaquality.PictureParameters;
import android.hardware.tv.mediaquality.SoundParameter;
import android.hardware.tv.mediaquality.SoundParameters;
import android.hardware.tv.mediaquality.StreamStatus;
import android.hardware.tv.mediaquality.VendorParamCapability;
import android.hardware.tv.mediaquality.VendorParameterIdentifier;
import android.media.quality.ActiveProcessingPicture;
import android.media.quality.AmbientBacklightEvent;
import android.media.quality.AmbientBacklightMetadata;
import android.media.quality.AmbientBacklightSettings;
import android.media.quality.IActiveProcessingPictureListener;
import android.media.quality.IAmbientBacklightCallback;
import android.media.quality.IMediaQualityManager;
import android.media.quality.IPictureProfileCallback;
import android.media.quality.ISoundProfileCallback;
import android.media.quality.MediaQualityContract.BaseParameters;
import android.media.quality.ParameterCapability;
import android.media.quality.PictureProfile;
import android.media.quality.PictureProfileHandle;
import android.media.quality.SoundProfile;
import android.media.quality.SoundProfileHandle;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.view.SurfaceControlActivePicture;
import android.view.SurfaceControlActivePictureListener;

import com.android.internal.annotations.GuardedBy;
import com.android.server.SystemService;
import com.android.server.utils.Slogf;

import java.io.File;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * This service manage picture profile and sound profile for TV setting. Also communicates with the
 * database to save, update the profiles.
 */
public class MediaQualityService extends SystemService {

    private static final boolean DEBUG = false;
    private static final String TAG = "MediaQualityService";
    private static final String ALLOWLIST = "allowlist";
    private static final String PICTURE_PROFILE_PREFERENCE = "picture_profile_preference";
    private static final String SOUND_PROFILE_PREFERENCE = "sound_profile_preference";
    private static final String COMMA_DELIMITER = ",";
    private static final String DEFAULT_PICTURE_PROFILE_ID = "default_picture_profile_id";
    private static final String STREAM_STATUS = "stream_status";
    private static final String PREVIOUS_STREAM_STATUS = "previous_stream_status";
    private static final String STREAM_STATUS_NOT_CREATED = "stream_status_not_created";
    private final Context mContext;
    private final MediaQualityDbHelper mMediaQualityDbHelper;
    private final BiMap<Long, String> mPictureProfileTempIdMap;
    private final BiMap<Long, String> mSoundProfileTempIdMap;
    private final Map<String, Long> mPackageDefaultPictureProfileHandleMap = new HashMap<>();
    private IMediaQuality mMediaQuality;
    private PictureProfileAdjustmentListenerImpl mPictureProfileAdjListener;
    private SoundProfileAdjustmentListenerImpl mSoundProfileAdjListener;
    private IPictureProfileChangedListener mPpChangedListener;
    private ISoundProfileChangedListener mSpChangedListener;
    private final HalAmbientBacklightCallback mHalAmbientBacklightCallback;
    private final Map<String, AmbientBacklightCallbackRecord> mCallbackRecords = new HashMap<>();
    private final PackageManager mPackageManager;
    private final SparseArray<UserState> mUserStates = new SparseArray<>();
    private SharedPreferences mPictureProfileSharedPreference;
    private SharedPreferences mSoundProfileSharedPreference;
    private HalNotifier mHalNotifier;
    private MqManagerNotifier mMqManagerNotifier;
    private MqDatabaseUtils mMqDatabaseUtils;
    private Handler mHandler;
    private SurfaceControlActivePictureListener mSurfaceControlActivePictureListener;

    // A global lock for picture profile objects.
    private final Object mPictureProfileLock = new Object();
    // A global lock for sound profile objects.
    private final Object mSoundProfileLock = new Object();
    // A global lock for user state objects.
    private final Object mUserStateLock = new Object();
    // A global lock for ambient backlight objects.
    private final Object mAmbientBacklightLock = new Object();

    private final Map<Long, PictureProfile> mOriginalHandleToCurrentPictureProfile =
            new HashMap<>();
    private final BiMap<Long, Long> mCurrentPictureHandleToOriginal = new BiMap<>();
    private final Set<Long> mPictureProfileForHal = new HashSet<>();

    public MediaQualityService(Context context) {
        super(context);
        mContext = context;
        mHalAmbientBacklightCallback = new HalAmbientBacklightCallback();
        mPackageManager = mContext.getPackageManager();
        mPictureProfileTempIdMap = new BiMap<>();
        mSoundProfileTempIdMap = new BiMap<>();
        mMediaQualityDbHelper = new MediaQualityDbHelper(mContext);
        mMediaQualityDbHelper.setWriteAheadLoggingEnabled(true);
        mMediaQualityDbHelper.setIdleConnectionTimeout(30);
        mMqManagerNotifier = new MqManagerNotifier();
        mMqDatabaseUtils = new MqDatabaseUtils();
        mHalNotifier = new HalNotifier();
        mPictureProfileAdjListener = new PictureProfileAdjustmentListenerImpl();
        mSoundProfileAdjListener = new SoundProfileAdjustmentListenerImpl();
        mHandler = new Handler(Looper.getMainLooper());

        // The package info in the context isn't initialized in the way it is for normal apps,
        // so the standard, name-based context.getSharedPreferences doesn't work. Instead, we
        // build the path manually below using the same policy that appears in ContextImpl.
        final Context deviceContext = mContext.createDeviceProtectedStorageContext();
        final File pictureProfilePrefs = new File(Environment.getDataSystemDirectory(),
                PICTURE_PROFILE_PREFERENCE);
        mPictureProfileSharedPreference = deviceContext.getSharedPreferences(
                pictureProfilePrefs, Context.MODE_PRIVATE);
        final File soundProfilePrefs = new File(Environment.getDataSystemDirectory(),
                SOUND_PROFILE_PREFERENCE);
        mSoundProfileSharedPreference = deviceContext.getSharedPreferences(
                soundProfilePrefs, Context.MODE_PRIVATE);
    }

    @GuardedBy("mPictureProfileLock")
    @Override
    public void onStart() {
        IBinder binder = ServiceManager.getService(IMediaQuality.DESCRIPTOR + "/default");
        if (binder == null) {
            Slogf.d(TAG, "Binder is null");
            return;
        }
        Slogf.d(TAG, "Binder is not null");

        mSurfaceControlActivePictureListener = new SurfaceControlActivePictureListener() {
            @Override
            public void onActivePicturesChanged(SurfaceControlActivePicture[] activePictures) {
                handleOnActivePicturesChanged(activePictures);
            }
        };
        mSurfaceControlActivePictureListener.startListening(); // TODO: stop listening

        mMediaQuality = IMediaQuality.Stub.asInterface(binder);
        if (mMediaQuality != null) {
            try {
                mMediaQuality.setAmbientBacklightCallback(mHalAmbientBacklightCallback);

                mPpChangedListener = mMediaQuality.getPictureProfileListener();
                mSpChangedListener = mMediaQuality.getSoundProfileListener();

                mMediaQuality.setPictureProfileAdjustmentListener(mPictureProfileAdjListener);
                mMediaQuality.setSoundProfileAdjustmentListener(mSoundProfileAdjListener);

                synchronized (mPictureProfileLock) {
                    String selection = BaseParameters.PARAMETER_TYPE + " = ? AND ("
                            + BaseParameters.PARAMETER_NAME + " = ? OR "
                            + BaseParameters.PARAMETER_NAME + " = ?)";
                    String[] selectionArguments = {
                            Integer.toString(PictureProfile.TYPE_SYSTEM),
                            PictureProfile.NAME_DEFAULT,
                            PictureProfile.NAME_DEFAULT + "/" + PictureProfile.STATUS_SDR
                    };
                    List<PictureProfile> packageDefaultPictureProfiles =
                            mMqDatabaseUtils.getPictureProfilesBasedOnConditions(MediaQualityUtils
                                .getMediaProfileColumns(false), selection, selectionArguments);
                    mPackageDefaultPictureProfileHandleMap.clear();
                    for (PictureProfile profile : packageDefaultPictureProfiles) {
                        if (isPackageDefaultPictureProfile(profile)) {
                            mPackageDefaultPictureProfileHandleMap.put(
                                    profile.getPackageName(), profile.getHandle().getId());
                        }
                    }
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to set ambient backlight detector callback", e);
            }
        }

        publishBinderService(Context.MEDIA_QUALITY_SERVICE, new BinderService());
    }

    private void handleOnActivePicturesChanged(SurfaceControlActivePicture[] scActivePictures) {
        if (DEBUG) {
            Slog.d(TAG, "handleOnActivePicturesChanged");
        }
        synchronized (mPictureProfileLock) {
        // TODO handle other users
            UserState userState = getOrCreateUserState(UserHandle.USER_SYSTEM);
            int n = userState.mActiveProcessingPictureCallbacks.beginBroadcast();
            for (int i = 0; i < n; ++i) {
                try {
                    IActiveProcessingPictureListener l = userState
                            .mActiveProcessingPictureCallbacks
                            .getBroadcastItem(i);
                    ActiveProcessingPictureListenerInfo info =
                            userState.mActiveProcessingPictureListenerMap.get(l);
                    if (info == null) {
                        continue;
                    }
                    int uid = info.mUid;
                    boolean hasGlobalPermission = mContext.checkPermission(
                            android.Manifest.permission.MANAGE_GLOBAL_PICTURE_QUALITY_SERVICE,
                            info.mPid, uid)
                            == PackageManager.PERMISSION_GRANTED;
                    List<ActiveProcessingPicture> aps = new ArrayList<>();
                    for (SurfaceControlActivePicture scap : scActivePictures) {
                        if (!hasGlobalPermission && scap.getOwnerUid() != uid) {
                            // should not receive the event
                            continue;
                        }
                        String profileId = mPictureProfileTempIdMap.getValue(
                                scap.getPictureProfileHandle().getId());
                        if (profileId == null) {
                            continue;
                        }
                        aps.add(new ActiveProcessingPicture(
                                scap.getLayerId(), profileId, scap.getOwnerUid() != uid));

                    }

                    l.onActiveProcessingPicturesChanged(aps);
                } catch (RemoteException e) {
                    Slog.e(TAG, "failed to report added AD service to callback", e);
                }
            }
            userState.mActiveProcessingPictureCallbacks.finishBroadcast();
        }
    }

    private final class BinderService extends IMediaQualityManager.Stub {

        @GuardedBy("mPictureProfileLock")
        @Override
        public void createPictureProfile(PictureProfile pp, int userId) {
            if (DEBUG) {
                Slog.d(TAG, "createPictureProfile: "
                        + "createPictureProfile for " + pp.getName());
            }
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            mHandler.post(() -> {
                if ((pp.getPackageName() != null && !pp.getPackageName().isEmpty()
                            && !incomingPackageEqualsUidPackage(pp.getPackageName(), callingUid))
                        && !hasGlobalPictureQualityServicePermission(callingUid, callingPid)) {
                    mMqManagerNotifier.notifyOnPictureProfileError(
                            null, PictureProfile.ERROR_NO_PERMISSION, callingUid, callingPid);
                    Slog.e(TAG, "createPictureProfile: "
                            + "no permission to create picture profile");
                    return;
                }

                synchronized (mPictureProfileLock) {
                    SQLiteDatabase db = mMediaQualityDbHelper.getWritableDatabase();

                    ContentValues values = MediaQualityUtils.getContentValues(null,
                            pp.getProfileType(), pp.getName(),
                            pp.getPackageName() == null || pp.getPackageName().isEmpty()
                                    ? getPackageOfUid(callingUid)
                                    : pp.getPackageName(),
                            pp.getInputId(), pp.getParameters());

                    if (DEBUG) {
                        Slog.d(TAG, "insert " + pp.getName() + " to database");
                    }
                    // id is auto-generated by SQLite upon successful insertion of row
                    Long id = db.insert(
                            mMediaQualityDbHelper.PICTURE_QUALITY_TABLE_NAME, null, values);
                    MediaQualityUtils.populateTempIdMap(mPictureProfileTempIdMap, id);
                    String value = mPictureProfileTempIdMap.getValue(id);
                    pp.setProfileId(value);
                    mMqManagerNotifier.notifyOnPictureProfileAdded(
                            value, pp, callingUid, callingPid);
                    if (isPackageDefaultPictureProfile(pp)) {
                        mPackageDefaultPictureProfileHandleMap.put(
                                pp.getPackageName(), id);
                    }
                }
            });
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public void updatePictureProfile(String id, PictureProfile pp, int userId) {
            if (DEBUG) {
                Slog.d(TAG, "updatePictureProfile: "
                        + "updatePictureProfile for " + pp.getName());
            }
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            mHandler.post(() -> {
                Long dbId = mPictureProfileTempIdMap.getKey(id);
                if (dbId == null) {
                    mMqManagerNotifier.notifyOnPictureProfileError(
                            id, PictureProfile.ERROR_INVALID_ARGUMENT, callingUid, callingPid);
                    Slog.e(TAG, "updatePictureProfile: "
                            + "dbId not found in mPictureProfileTempIdMap");
                    return;
                }
                if (DEBUG) {
                    Slog.d(TAG, "the dbId associated with id is " + dbId);
                }
                if (!hasPermissionToUpdatePictureProfile(dbId, pp, callingUid, callingPid)) {
                    mMqManagerNotifier.notifyOnPictureProfileError(
                            id, PictureProfile.ERROR_NO_PERMISSION, callingUid, callingPid);
                    Slog.e(TAG, "updatePictureProfile: "
                            + "no permission to update picture profile");
                    return;
                }
                synchronized (mPictureProfileLock) {
                    ContentValues values = MediaQualityUtils.getContentValues(dbId,
                            pp.getProfileType(),
                            pp.getName(),
                            pp.getPackageName(),
                            pp.getInputId(),
                            pp.getParameters());
                    if (mPictureProfileForHal.contains(dbId)) {
                        if (DEBUG) {
                            Slog.d(TAG, "updatePictureProfile: "
                                    + "notify manager and HAL about the picture profile update");
                        }
                        updateDatabaseOnPictureProfileAndNotifyManager(
                                values, pp.getParameters(), callingUid, callingPid, true);
                    } else {
                        if (DEBUG) {
                            Slog.d(TAG, "updatePictureProfile: "
                                    + "notify manager about the picture profile update");
                        }
                        updateDatabaseOnPictureProfileAndNotifyManager(
                                values, pp.getParameters(), callingUid, callingPid, false);
                    }
                    // Keep cache in sync with database, and check for profile id and handle of the
                    // updated picture profile, because user might call this with a picture profile
                    // without handle or profileId.
                    Long originalHandle = mCurrentPictureHandleToOriginal.getValue(dbId);
                    if (originalHandle != null) {
                        PictureProfile cachedPp = mOriginalHandleToCurrentPictureProfile
                                .get(originalHandle);
                        if (cachedPp != null) {
                            if (pp.getProfileId() == null
                                    || pp.getHandle() == PictureProfileHandle.NONE) {
                                cachedPp = new PictureProfile.Builder(cachedPp)
                                        .setProfileId(cachedPp.getProfileId())
                                        .setParameters(pp.getParameters())
                                        .build();
                                mOriginalHandleToCurrentPictureProfile
                                        .put(originalHandle, cachedPp);
                            } else {
                                mOriginalHandleToCurrentPictureProfile.put(originalHandle, pp);
                            }
                        }
                    }

                    if (isPackageDefaultPictureProfile(pp)) {
                        if (DEBUG) {
                            Slog.d(TAG, "updatePictureProfile: "
                                    + "updated picture profile is package default picture profile");
                        }
                        mPackageDefaultPictureProfileHandleMap.put(pp.getPackageName(), dbId);
                    }
                }
            });
        }

        private boolean hasPermissionToUpdatePictureProfile(
                Long dbId, PictureProfile toUpdate, int uid, int pid) {
            PictureProfile fromDb = mMqDatabaseUtils.getPictureProfile(dbId);
            if (fromDb == null) {
                Slog.e(TAG, "Failed to get picture profile from db");
                return false;
            }
            boolean isPackageOwner = fromDb.getPackageName().equals(getPackageOfUid(uid));
            boolean isSystemAppWithPermission =
                hasGlobalPictureQualityServicePermission(uid, pid)
                    && fromDb.getProfileType() == PictureProfile.TYPE_SYSTEM;
            return fromDb.getProfileType() == toUpdate.getProfileType()
                    && fromDb.getName().equals(toUpdate.getName())
                    && fromDb.getPackageName().equals(toUpdate.getPackageName())
                    && (isPackageOwner || isSystemAppWithPermission);
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public void removePictureProfile(String id, int userId) {
            if (DEBUG) {
                Slog.d(TAG, "removePictureProfile: remove picture profile");
            }
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            mHandler.post(() -> {
                synchronized (mPictureProfileLock) {
                    Long dbId = mPictureProfileTempIdMap.getKey(id);
                    if (dbId == null) {
                        mMqManagerNotifier.notifyOnPictureProfileError(
                                id, PictureProfile.ERROR_INVALID_ARGUMENT, callingUid, callingPid);
                        Slog.e(TAG, "removePictureProfile: "
                                + "dbId not found in mPictureProfileTempIdMap");
                        return;
                    }

                    PictureProfile toDelete = mMqDatabaseUtils.getPictureProfile(dbId);
                    if (!hasPermissionToRemovePictureProfile(toDelete, callingUid)) {
                        mMqManagerNotifier.notifyOnPictureProfileError(
                                id, PictureProfile.ERROR_NO_PERMISSION, callingUid, callingPid);
                        Slog.e(TAG, "removePictureProfile: "
                                + "no permission to remove picture profile");
                        return;
                    }

                    if (dbId != null) {
                        SQLiteDatabase db = mMediaQualityDbHelper.getWritableDatabase();
                        String selection = BaseParameters.PARAMETER_ID + " = ?";
                        String[] selectionArgs = {Long.toString(dbId)};
                        int result = db.delete(mMediaQualityDbHelper.PICTURE_QUALITY_TABLE_NAME,
                                selection, selectionArgs);
                        if (result == 0) {
                            mMqManagerNotifier.notifyOnPictureProfileError(id,
                                    PictureProfile.ERROR_INVALID_ARGUMENT, callingUid, callingPid);
                            Slog.e(TAG, "Failed to remove picture profile");
                            return;
                        } else {
                            mMqManagerNotifier.notifyOnPictureProfileRemoved(
                                    mPictureProfileTempIdMap.getValue(dbId), toDelete, callingUid,
                                    callingPid);
                            mPictureProfileTempIdMap.remove(dbId);
                        }
                    }

                    if (isPackageDefaultPictureProfile(toDelete)) {
                        mPackageDefaultPictureProfileHandleMap.remove(
                                toDelete.getPackageName());
                    }
                }
            });
        }

        @Override
        public void changeStreamStatus(
                @NonNull String profileId, @NonNull String newStatus, int userId) {
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            if (!hasGlobalPictureQualityServicePermission(callingUid, callingPid)) {
                mMqManagerNotifier.notifyOnPictureProfileError(
                        null, PictureProfile.ERROR_NO_PERMISSION, callingUid, callingPid);
                Slog.e(TAG, "changeStreamStatus: no permission");
                return;
            }
            Long dbId = null;
            synchronized (mPictureProfileLock) {
                dbId = mPictureProfileTempIdMap.getKey(profileId);
                if (dbId == null) {
                    return;
                }
            }
            try {
                mPictureProfileAdjListener.onStreamStatusChanged(
                        dbId, mPictureProfileAdjListener.toHalStatus(newStatus));
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }

        }

        private boolean hasPermissionToRemovePictureProfile(PictureProfile toDelete, int uid) {
            if (toDelete != null) {
                return toDelete.getPackageName().equalsIgnoreCase(getPackageOfUid(uid));
            }
            return false;
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public PictureProfile getPictureProfile(int type, String name, boolean includeParams,
                int userId) {
            if (DEBUG) {
                Slog.d(TAG, "getPictureProfile: get " + name);
            }
            int callingUid = Binder.getCallingUid();
            String selection = BaseParameters.PARAMETER_TYPE + " = ? AND "
                    + BaseParameters.PARAMETER_NAME + " = ? AND "
                    + BaseParameters.PARAMETER_PACKAGE + " = ? AND "
                    + BaseParameters.PARAMETER_INPUT_ID + " IS NULL";
            String[] selectionArguments = {
                    Integer.toString(type), name, getPackageOfUid(callingUid)};

            synchronized (mPictureProfileLock) {
                try (
                        Cursor cursor = mMqDatabaseUtils.getCursorAfterQuerying(
                                mMediaQualityDbHelper.PICTURE_QUALITY_TABLE_NAME,
                                MediaQualityUtils.getMediaProfileColumns(includeParams), selection,
                                selectionArguments)
                ) {
                    int count = cursor.getCount();
                    if (count == 0) {
                        Slog.e(TAG, "getPictureProfile: "
                                + "no picture profile found for " + name);
                        return null;
                    }
                    if (count > 1) {
                        Log.wtf(TAG,
                                TextUtils.formatSimple("%d entries found for type=%d and name=%s "
                                                       + "in %s. Should only ever be 0 or 1.",
                                        count, type, name,
                                        mMediaQualityDbHelper.PICTURE_QUALITY_TABLE_NAME));
                        return null;
                    }
                    cursor.moveToFirst();
                    return MediaQualityUtils.convertCursorToPictureProfileWithTempId(cursor,
                            mPictureProfileTempIdMap);
                }
            }
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public List<PictureProfile> getPictureProfilesByPackage(
                String packageName, boolean includeParams, int userId) {
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            return getPictureProfilesByPackage(
                    packageName, includeParams, userId, callingUid, callingPid);
        }

        private List<PictureProfile> getPictureProfilesByPackage(
                String packageName, boolean includeParams, int userId, int uid, int pid) {
            if (DEBUG) {
                Slog.d(TAG, "getPictureProfilesByPackage: "
                        + "get picture profile for package" + packageName);
            }
            if (!hasGlobalPictureQualityServicePermission(uid, pid)) {
                mMqManagerNotifier.notifyOnPictureProfileError(
                        null, PictureProfile.ERROR_NO_PERMISSION, uid, pid);
                Slog.e(TAG, "getPictureProfilesByPackage: "
                        + "no permission to get picture profile by package");
                return new ArrayList<>();
            }

            synchronized (mPictureProfileLock) {
                String selection = BaseParameters.PARAMETER_PACKAGE + " = ?";
                String[] selectionArguments = {packageName};
                return mMqDatabaseUtils.getPictureProfilesBasedOnConditions(MediaQualityUtils
                                .getMediaProfileColumns(includeParams),
                        selection, selectionArguments);
            }
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public List<PictureProfile> getAvailablePictureProfiles(boolean includeParams, int userId) {
            if (DEBUG) {
                Slog.d(TAG, "getAvailablePictureProfiles");
            }
            int callingUid = BinderService.getCallingUid();
            int callingPid = BinderService.getCallingPid();
            String packageName = getPackageOfUid(callingUid);
            if (packageName != null) {
                return getPictureProfilesByPackage(
                        packageName, includeParams, userId, callingUid, callingPid);
            }
            return new ArrayList<>();
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public PictureProfile getDefaultPictureProfile() {
            if (DEBUG) {
                Slog.d(TAG, "getDefaultPictureProfile");
            }
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            if (!hasGlobalPictureQualityServicePermission(callingUid, callingPid)) {
                mMqManagerNotifier.notifyOnPictureProfileError(
                        null, PictureProfile.ERROR_NO_PERMISSION, callingUid, callingPid);
                Slog.e(TAG, "getDefaultPictureProfile: "
                        + "no permission to get default picture profile");
                return null;
            }
            Long defaultPictureProfileId = mPictureProfileSharedPreference.getLong(
                    DEFAULT_PICTURE_PROFILE_ID,
                    -1
            );
            if (defaultPictureProfileId != -1) {
                synchronized (mPictureProfileLock) {
                    PictureProfile currentDefaultPictureProfile =
                            mOriginalHandleToCurrentPictureProfile.get(defaultPictureProfileId);
                    if (currentDefaultPictureProfile != null) {
                        if (DEBUG) {
                            Slog.d(TAG, "getDefaultPictureProfile: "
                                    + "return current picture profile");
                        }
                        return currentDefaultPictureProfile;
                    } else {
                        if (DEBUG) {
                            Slog.d(TAG, "getDefaultPictureProfile: "
                                    + "return default picture profile from the database");
                        }
                        return mMqDatabaseUtils.getPictureProfile(defaultPictureProfileId, true);
                    }
                }
            }
            return null;
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public boolean setDefaultPictureProfile(String profileId, int userId) {
            if (DEBUG) {
                Slog.d(TAG, "setDefaultPictureProfile");
            }
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            if (!hasGlobalPictureQualityServicePermission(callingUid, callingPid)) {
                mMqManagerNotifier.notifyOnPictureProfileError(
                        profileId, PictureProfile.ERROR_NO_PERMISSION, callingUid, callingPid);
                Slog.e(TAG, "setDefaultPictureProfile: "
                        + "no permission to set default picture profile");
                return false;
            }

            Long longId = mPictureProfileTempIdMap.getKey(profileId);
            if (longId == null) {
                Slog.e(TAG, "setDefaultPictureProfile: "
                        + "can not find the default picture profile");
                return false;
            }

            mPictureProfileForHal.add(longId);
            SharedPreferences.Editor editor = mPictureProfileSharedPreference.edit();
            editor.putLong(DEFAULT_PICTURE_PROFILE_ID, longId);
            editor.apply();

            PictureProfile pictureProfile = mMqDatabaseUtils.getPictureProfile(longId, true);
            PersistableBundle params = pictureProfile.getParameters();

            try {
                if (mMediaQuality != null) {
                    PictureParameters pp = new PictureParameters();
                    // put ID in params for profile update in HAL
                    // TODO: update HAL API for this case
                    params.putLong(BaseParameters.PARAMETER_ID, longId);
                    params.putBoolean("default_picture_profile", true);
                    PictureParameter[] pictureParameters = MediaQualityUtils
                            .convertPersistableBundleToPictureParameterList(params);

                    Parcel parcel = Parcel.obtain();
                    setVendorPictureParameters(pp, parcel, params);

                    pp.pictureParameters = pictureParameters;

                    mMediaQuality.sendDefaultPictureParameters(pp);
                    parcel.recycle();
                    return true;
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to set default picture profile", e);
            }
            return false;
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public List<String> getPictureProfilePackageNames(int userId) {
            if (DEBUG) {
                Slog.d(TAG, "getPictureProfilePackageNames");
            }
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            if (!hasGlobalPictureQualityServicePermission(callingUid, callingPid)) {
                mMqManagerNotifier.notifyOnPictureProfileError(
                        null, PictureProfile.ERROR_NO_PERMISSION, callingUid, callingPid);
                Slog.e(TAG, "getPictureProfilePackageNames: no permission to get picture"
                        + " profiles package names");
                return new ArrayList<>();
            }
            String [] column = {BaseParameters.PARAMETER_PACKAGE};
            synchronized (mPictureProfileLock) {
                List<PictureProfile> pictureProfiles =
                        mMqDatabaseUtils.getPictureProfilesBasedOnConditions(column, null, null);
                return pictureProfiles.stream()
                        .map(PictureProfile::getPackageName)
                        .distinct()
                        .collect(Collectors.toList());
            }
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public List<PictureProfileHandle> getPictureProfileHandle(String[] ids, int userId) {
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            if (!hasGlobalPictureQualityServicePermission(callingUid, callingPid)) {
                mMqManagerNotifier.notifyOnPictureProfileError(
                        null, PictureProfile.ERROR_NO_PERMISSION, callingUid, callingPid);
                Slog.e(TAG, "getPictureProfileHandle: no permission");
                return new ArrayList<>();
            }
            List<PictureProfileHandle> toReturn = new ArrayList<>();
            synchronized (mPictureProfileLock) {
                for (String id : ids) {
                    Long key = mPictureProfileTempIdMap.getKey(id);
                    if (key != null) {
                        toReturn.add(new PictureProfileHandle(key));
                    } else {
                        toReturn.add(null);
                    }
                }
            }
            return toReturn;
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public long getPictureProfileHandleValue(String id, int userId) {
            if (DEBUG) {
                Slog.d(TAG, "getPictureProfileHandleValue with id = " + id);
            }
            synchronized (mPictureProfileLock) {
                Long value = mPictureProfileTempIdMap.getKey(id);
                if (DEBUG) {
                    Collection<String> values = mPictureProfileTempIdMap.getValues();
                    for (String val: values) {
                        Slog.d(TAG, "key: " + mPictureProfileTempIdMap.getKey(val)
                                + " value: " + val);
                    }
                }
                if (value != null) {
                    if (DEBUG) {
                        Slog.d(TAG, "the value from the mPictureProfileTempIdMap is: "
                                + value);
                    }
                    mPictureProfileForHal.add(value);
                    mHalNotifier.notifyHalOnPictureProfileChange(value, null);
                }
                return value != null ? value : -1;
            }
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public long getDefaultPictureProfileHandleValue(int userId) {
            if (DEBUG) {
                Slog.d(TAG, "getDefaultPictureProfileHandleValue");
            }
            int callingUid = Binder.getCallingUid();
            synchronized (mPictureProfileLock) {
                String packageName = getPackageOfUid(callingUid);

                Long value = null;
                if (packageName != null) {
                    if (DEBUG) {
                        Slog.d(TAG, "getDefaultPictureProfileHandleValue: "
                                + "the package name is " + packageName);
                    }
                    value = mPackageDefaultPictureProfileHandleMap.get(packageName);

                    if (value == null) {
                        Long defaultPictureProfileId = mPictureProfileSharedPreference.getLong(
                                DEFAULT_PICTURE_PROFILE_ID, -1);
                        if (defaultPictureProfileId != -1) {
                          Log.v(TAG,
                                  "Default picture profile handle value for " + packageName
                                  + " not found. Fallback to return global default: "
                                          + defaultPictureProfileId);
                          value = defaultPictureProfileId;
                        }
                    }

                    if (value != null) {
                        if (DEBUG) {
                            Slog.d(TAG, "the value got from the map is " + value);
                        }
                        mPictureProfileForHal.add(value);
                        mHalNotifier.notifyHalOnPictureProfileChange(value, null);
                    }
                } else {
                    Slog.e(TAG, "The packageName of callingUid: " + callingUid + " is null");
                    return -1;
                }
                return value != null ? value : -1;
            }
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public void notifyPictureProfileHandleSelection(long handle, int userId) {
            if (DEBUG) {
                Slog.d(TAG, "notifyPictureProfileHandleSelection with handle " + handle);
            }
            PictureProfile profile = mMqDatabaseUtils.getPictureProfile(handle, true);
            if (profile != null) {
                if (DEBUG) {
                    Slog.d(TAG, "the picture profile got from this handle is "
                            + profile.getName());
                }
                mPictureProfileForHal.add(handle);
                mHalNotifier.notifyHalOnPictureProfileChange(handle, profile.getParameters());
            }
        }

        public long getPictureProfileForTvInput(String inputId, int userId) {
            if (DEBUG) {
                Slog.d(TAG, "getPictureProfileForTvInput for id " + inputId);
            }
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            if (!hasGlobalPictureQualityServicePermission(callingUid, callingPid)) {
                mMqManagerNotifier.notifyOnPictureProfileError(
                        null, PictureProfile.ERROR_NO_PERMISSION, callingUid, callingPid);
                Slog.e(TAG, "getPictureProfileForTvInput: no permission");
                return -1;
            }
            // TODO: cache profiles
            String[] columns = {BaseParameters.PARAMETER_ID};
            String selection = BaseParameters.PARAMETER_TYPE + " = ? AND ("
                    + BaseParameters.PARAMETER_NAME + " = ? OR "
                    + BaseParameters.PARAMETER_NAME + " = ? OR "
                    + BaseParameters.PARAMETER_NAME + " LIKE ?) AND "
                    + BaseParameters.PARAMETER_INPUT_ID + " = ?";
            String[] selectionArguments = {
                    Integer.toString(PictureProfile.TYPE_SYSTEM),
                    PictureProfile.NAME_DEFAULT,
                    PictureProfile.NAME_DEFAULT + "/" + PictureProfile.STATUS_SDR,
                    // b/427656481 Workaround to recognize temp input default.
                    "%" + PictureProfile.NAME_DEFAULT + "/" + PictureProfile.STATUS_SDR,
                    inputId
            };
            synchronized (mPictureProfileLock) {
                try (Cursor cursor = mMqDatabaseUtils.getCursorAfterQuerying(
                        mMediaQualityDbHelper.PICTURE_QUALITY_TABLE_NAME,
                        columns, selection, selectionArguments)) {
                    int count = cursor.getCount();
                    if (count == 0) {
                        Slog.e(TAG, "getPictureProfileForTvInput: the count is 0");
                        return -1;
                    }
                    long handle = -1;
                    cursor.moveToFirst();
                    PictureProfile p = MediaQualityUtils.convertCursorToPictureProfileWithTempId(
                            cursor, mPictureProfileTempIdMap);
                    handle = p.getHandle().getId();
                    PictureProfile current = mOriginalHandleToCurrentPictureProfile.get(handle);
                    if (current != null) {
                        if (DEBUG) {
                            Slog.d(TAG, "handle returned is " + current.getHandle().getId());
                        }
                        return current.getHandle().getId();
                    }
                    if (DEBUG) {
                        Slog.d(TAG, "handle returned is " + handle);
                    }
                    return handle;
                }
            }
        }

        public PictureProfile getCurrentPictureProfileForTvInput(String inputId, int userId) {
            if (DEBUG) {
                Slog.d(TAG, "getCurrentPictureProfileForTvInput");
            }
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            if (!hasGlobalPictureQualityServicePermission(callingUid, callingPid)) {
                mMqManagerNotifier.notifyOnPictureProfileError(
                        null, PictureProfile.ERROR_NO_PERMISSION, callingUid, callingPid);
                Slog.e(TAG, "getCurrentPictureProfileForTvInput: no permission");
                return null;
            }
            long profileHandle = getPictureProfileForTvInput(inputId, userId);
            if (profileHandle == -1) {
                return null;
            }
            synchronized (mPictureProfileLock) {
                return mMqDatabaseUtils.getPictureProfile(profileHandle, true);
            }
        }

        public List<PictureProfile> getAllPictureProfilesForTvInput(String inputId, int userId) {
            if (DEBUG) {
                Slog.d(TAG, "getAllPictureProfilesForTvInput");
            }
            // TODO: cache profiles
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            if (!hasGlobalPictureQualityServicePermission(callingUid, callingPid)) {
                mMqManagerNotifier.notifyOnPictureProfileError(
                        null, PictureProfile.ERROR_NO_PERMISSION, callingUid, callingPid);
                Slog.e(TAG, "getAllPictureProfilesForTvInput: "
                        + "no permission to get all picture profiles for tv input");
                return new ArrayList<>();
            }
            String[] columns = MediaQualityUtils.getMediaProfileColumns(/* includeParams= */ true);
            String selection = BaseParameters.PARAMETER_TYPE + " = ? AND "
                    + BaseParameters.PARAMETER_INPUT_ID + " = ?";
            String[] selectionArguments = {
                    Integer.toString(PictureProfile.TYPE_SYSTEM),
                    inputId
            };
            List<PictureProfile> profiles = new ArrayList<>();
            synchronized (mPictureProfileLock) {
                try (Cursor cursor = mMqDatabaseUtils.getCursorAfterQuerying(
                        mMediaQualityDbHelper.PICTURE_QUALITY_TABLE_NAME,
                        columns, selection, selectionArguments)) {
                    int count = cursor.getCount();
                    if (count == 0) {
                        Slog.e(TAG, "getAllPictureProfilesForTvInput: "
                                + "count is 0, didn't find any profile with this input id");
                        return profiles;
                    }
                    while (cursor.moveToNext()) {
                        profiles.add(MediaQualityUtils.convertCursorToPictureProfileWithTempId(
                                cursor, mPictureProfileTempIdMap));
                    }
                    return profiles;
                }
            }
        }

        @GuardedBy("mSoundProfileLock")
        @Override
        public List<SoundProfileHandle> getSoundProfileHandle(String[] ids, int userId) {
            if (DEBUG) {
                Slog.d(TAG, "getSoundProfileHandle");
            }
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            if (!hasGlobalSoundQualityServicePermission(callingUid, callingPid)) {
                mMqManagerNotifier.notifyOnSoundProfileError(
                        null, SoundProfile.ERROR_NO_PERMISSION, callingUid, callingPid);
                Slog.e(TAG, "getSoundProfileHandle: no permission");
                return new ArrayList<>();
            }
            List<SoundProfileHandle> toReturn = new ArrayList<>();
            synchronized (mSoundProfileLock) {
                for (String id : ids) {
                    Long key = mSoundProfileTempIdMap.getKey(id);
                    if (key != null) {
                        toReturn.add(MediaQualityUtils.SOUND_PROFILE_HANDLE_NONE);
                    } else {
                        toReturn.add(null);
                    }
                }
            }
            return toReturn;
        }

        @GuardedBy("mSoundProfileLock")
        @Override
        public void createSoundProfile(SoundProfile sp, int userId) {
            if (DEBUG) {
                Slog.d(TAG, "createSoundProfile: create sound profile for " + sp.getName());
            }
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            mHandler.post(() -> {
                if ((sp.getPackageName() != null && !sp.getPackageName().isEmpty()
                            && !incomingPackageEqualsUidPackage(sp.getPackageName(), callingUid))
                        && !hasGlobalSoundQualityServicePermission(callingUid, callingPid)) {
                    mMqManagerNotifier.notifyOnSoundProfileError(
                            null, SoundProfile.ERROR_NO_PERMISSION, callingUid, callingPid);
                    Slog.e(TAG, "createSoundProfile: no permission to create sound profile");
                    return;
                }

                synchronized (mSoundProfileLock) {
                    SQLiteDatabase db = mMediaQualityDbHelper.getWritableDatabase();

                    ContentValues values = MediaQualityUtils.getContentValues(null,
                            sp.getProfileType(), sp.getName(),
                            sp.getPackageName() == null || sp.getPackageName().isEmpty()
                                    ? getPackageOfUid(callingUid)
                                    : sp.getPackageName(),
                            sp.getInputId(), sp.getParameters());

                    // id is auto-generated by SQLite upon successful insertion of row
                    Long id = db.insert(mMediaQualityDbHelper.SOUND_QUALITY_TABLE_NAME,
                            null, values);
                    MediaQualityUtils.populateTempIdMap(mSoundProfileTempIdMap, id);
                    String value = mSoundProfileTempIdMap.getValue(id);
                    sp.setProfileId(value);
                    mMqManagerNotifier.notifyOnSoundProfileAdded(value, sp, callingUid, callingPid);
                }
            });
        }

        @GuardedBy("mSoundProfileLock")
        @Override
        public void updateSoundProfile(String id, SoundProfile sp, int userId) {
            if (DEBUG) {
                Slog.d(TAG, "updateSoundProfile: update sound profile for " + sp.getName());
            }
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            mHandler.post(() -> {
                Long dbId = mSoundProfileTempIdMap.getKey(id);
                if (!hasPermissionToUpdateSoundProfile(dbId, sp, callingUid, callingPid)) {
                    mMqManagerNotifier.notifyOnSoundProfileError(
                            id, SoundProfile.ERROR_NO_PERMISSION, callingUid, callingPid);
                    Slog.e(TAG, "updateSoundProfile: no permission to update sound profile");
                    return;
                }

                synchronized (mSoundProfileLock) {
                    ContentValues values = MediaQualityUtils.getContentValues(dbId,
                            sp.getProfileType(),
                            sp.getName(),
                            sp.getPackageName(),
                            sp.getInputId(),
                            sp.getParameters());

                    updateDatabaseOnSoundProfileAndNotifyManager(
                            values, sp.getParameters(), callingUid, callingPid, true);
                }
            });
        }

        private boolean hasPermissionToUpdateSoundProfile(
                Long dbId, SoundProfile toUpdate, int uid, int pid) {
            SoundProfile fromDb = mMqDatabaseUtils.getSoundProfile(dbId);
            if (fromDb == null) {
                Slog.e(TAG, "Failed to get sound profile from db");
                return false;
            }
            boolean isPackageOwner = fromDb.getPackageName().equals(getPackageOfUid(uid));
            boolean isSystemAppWithPermission = hasGlobalSoundQualityServicePermission(uid, pid)
                    && fromDb.getProfileType() == PictureProfile.TYPE_SYSTEM;
            return fromDb.getProfileType() == toUpdate.getProfileType()
                    && fromDb.getName().equals(toUpdate.getName())
                    && fromDb.getPackageName().equals(toUpdate.getPackageName())
                    && (isPackageOwner || isSystemAppWithPermission);
        }

        @GuardedBy("mSoundProfileLock")
        @Override
        public void removeSoundProfile(String id, int userId) {
            if (DEBUG) {
                Slog.d(TAG, "removeSoundProfile");
            }
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            mHandler.post(() -> {
                synchronized (mSoundProfileLock) {
                    Long dbId = mSoundProfileTempIdMap.getKey(id);
                    SoundProfile toDelete = mMqDatabaseUtils.getSoundProfile(dbId);
                    if (!hasPermissionToRemoveSoundProfile(toDelete, callingUid)) {
                        mMqManagerNotifier.notifyOnSoundProfileError(
                                id, SoundProfile.ERROR_NO_PERMISSION, callingUid, callingPid);
                        Slog.e(TAG, "removeSoundProfile: "
                                + "no permission to remove sound profile");
                        return;
                    }
                    if (dbId != null) {
                        SQLiteDatabase db = mMediaQualityDbHelper.getWritableDatabase();
                        String selection = BaseParameters.PARAMETER_ID + " = ?";
                        String[] selectionArgs = {Long.toString(dbId)};
                        int result = db.delete(mMediaQualityDbHelper.SOUND_QUALITY_TABLE_NAME,
                                selection,
                                selectionArgs);
                        if (result == 0) {
                            mMqManagerNotifier.notifyOnSoundProfileError(id,
                                    SoundProfile.ERROR_INVALID_ARGUMENT, callingUid, callingPid);
                        } else {
                            mMqManagerNotifier.notifyOnSoundProfileRemoved(
                                    mSoundProfileTempIdMap.getValue(dbId), toDelete, callingUid,
                                    callingPid);
                            mSoundProfileTempIdMap.remove(dbId);
                        }
                    }
                }
            });
        }

        private boolean hasPermissionToRemoveSoundProfile(SoundProfile toDelete, int uid) {
            if (toDelete != null) {
                return toDelete.getPackageName().equalsIgnoreCase(getPackageOfUid(uid));
            }
            return false;
        }

        @GuardedBy("mSoundProfileLock")
        @Override
        public SoundProfile getSoundProfile(int type, String name, boolean includeParams,
                int userId) {
            if (DEBUG) {
                Slog.d(TAG, "getSoundProfile: " + name);
            }
            int callingUid = Binder.getCallingUid();

            String selection = BaseParameters.PARAMETER_TYPE + " = ? AND "
                    + BaseParameters.PARAMETER_NAME + " = ? AND "
                    + BaseParameters.PARAMETER_PACKAGE + " = ?";
            String[] selectionArguments = {String.valueOf(type), name, getPackageOfUid(callingUid)};

            synchronized (mSoundProfileLock) {
                try (
                        Cursor cursor = mMqDatabaseUtils.getCursorAfterQuerying(
                                mMediaQualityDbHelper.SOUND_QUALITY_TABLE_NAME,
                                MediaQualityUtils.getMediaProfileColumns(includeParams), selection,
                                selectionArguments)
                ) {
                    int count = cursor.getCount();
                    if (count == 0) {
                        Slog.e(TAG, "getSoundProfile: count is 0, no sound profile found");
                        return null;
                    }
                    if (count > 1) {
                        Log.wtf(TAG, TextUtils.formatSimple(String.valueOf(Locale.US), "%d "
                                        + "entries found for name=%s in %s. Should only ever "
                                        + "be 0 or 1.", String.valueOf(count), name,
                                mMediaQualityDbHelper.SOUND_QUALITY_TABLE_NAME));
                        return null;
                    }
                    cursor.moveToFirst();
                    return MediaQualityUtils.convertCursorToSoundProfileWithTempId(cursor,
                            mSoundProfileTempIdMap);
                }
            }
        }

        @GuardedBy("mSoundProfileLock")
        @Override
        public List<SoundProfile> getSoundProfilesByPackage(
                String packageName, boolean includeParams, int userId) {
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            return getSoundProfilesByPackage(
                    packageName, includeParams, userId, callingUid, callingPid);
        }

        private List<SoundProfile> getSoundProfilesByPackage(
                String packageName, boolean includeParams, int userId, int uid, int pid) {
            if (DEBUG) {
                Slog.d(TAG, "getSoundProfilesByPackage: " + packageName);
            }
            if (!hasGlobalSoundQualityServicePermission(uid, pid)) {
                mMqManagerNotifier.notifyOnSoundProfileError(
                        null, SoundProfile.ERROR_NO_PERMISSION, uid, pid);
                Slog.e(TAG, "getSoundProfilesByPackage: no permission to get sound profile "
                        + "by package");
                return new ArrayList<>();
            }

            synchronized (mSoundProfileLock) {
                String selection = BaseParameters.PARAMETER_PACKAGE + " = ?";
                String[] selectionArguments = {packageName};
                return mMqDatabaseUtils.getSoundProfilesBasedOnConditions(MediaQualityUtils
                                .getMediaProfileColumns(includeParams),
                        selection, selectionArguments);
            }
        }

        @GuardedBy("mSoundProfileLock")
        @Override
        public List<SoundProfile> getAvailableSoundProfiles(boolean includeParams, int userId) {
            if (DEBUG) {
                Slog.d(TAG, "getAvailableSoundProfiles");
            }
            int callingUid = BinderService.getCallingUid();
            int callingPid = BinderService.getCallingPid();
            String packageName = getPackageOfUid(callingUid);
            if (packageName != null) {
                return getSoundProfilesByPackage(
                        packageName, includeParams, userId, callingUid, callingPid);
            }
            Slog.e(TAG, "no available sound profiles found");
            return new ArrayList<>();
        }

        @GuardedBy("mSoundProfileLock")
        @Override
        public boolean setDefaultSoundProfile(String profileId, int userId) {
            if (DEBUG) {
                Slog.d(TAG, "setDefaultSoundProfile");
            }
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            if (!hasGlobalSoundQualityServicePermission(callingUid, callingPid)) {
                mMqManagerNotifier.notifyOnSoundProfileError(
                        profileId, SoundProfile.ERROR_NO_PERMISSION, callingUid, callingPid);
                Slog.e(TAG, "setDefaultSoundProfile: "
                        + "no permission to set default sound profile");
                return false;
            }

            Long longId = mSoundProfileTempIdMap.getKey(profileId);
            if (longId == null) {
                Slog.e(TAG, "the longId is not found for profileId" + profileId);
                return false;
            }

            SoundProfile soundProfile = mMqDatabaseUtils.getSoundProfile(longId);
            PersistableBundle params = soundProfile.getParameters();

            try {
                if (mMediaQuality != null) {
                    SoundParameters sp = new SoundParameters();
                    // put ID in params for profile update in HAL
                    // TODO: update HAL API for this case
                    params.putLong(BaseParameters.PARAMETER_ID, longId);
                    SoundParameter[] soundParameters =
                            MediaQualityUtils.convertPersistableBundleToSoundParameterList(params);

                    Parcel parcel = Parcel.obtain();
                    setVendorSoundParameters(sp, parcel, params);
                    sp.soundParameters = soundParameters;

                    mMediaQuality.sendDefaultSoundParameters(sp);
                    return true;
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to set default sound profile", e);
            }
            return false;
        }

        @GuardedBy("mSoundProfileLock")
        @Override
        public List<String> getSoundProfilePackageNames(int userId) {
            if (DEBUG) {
                Slog.d(TAG, "getSoundProfilePackageNames");
            }
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            if (!hasGlobalSoundQualityServicePermission(callingUid, callingPid)) {
                mMqManagerNotifier.notifyOnSoundProfileError(
                        null, SoundProfile.ERROR_NO_PERMISSION, callingUid, callingPid);
                Slog.e(TAG, "no permission to get sound profile package names");
                return new ArrayList<>();
            }
            String [] column = {BaseParameters.PARAMETER_NAME};

            synchronized (mSoundProfileLock) {
                List<SoundProfile> soundProfiles =
                        mMqDatabaseUtils.getSoundProfilesBasedOnConditions(column,
                        null, null);
                return soundProfiles.stream()
                        .map(SoundProfile::getPackageName)
                        .distinct()
                        .collect(Collectors.toList());
            }
        }

        private String getPackageOfUid(int uid) {
            String[] packageNames = mPackageManager.getPackagesForUid(uid);
            if (packageNames != null && packageNames.length == 1 && !packageNames[0].isEmpty()) {
                return packageNames[0];
            }
            return null;
        }

        private boolean incomingPackageEqualsUidPackage(String incomingPackage, int uid) {
            return incomingPackage.equalsIgnoreCase(getPackageOfUid(uid));
        }

        private boolean hasReadColorZonesPermission(int uid, int pid) {
            return mContext.checkPermission(android.Manifest.permission.READ_COLOR_ZONES, pid, uid)
                    == PackageManager.PERMISSION_GRANTED || uid == Process.SYSTEM_UID;
        }

        @Override
        public void registerPictureProfileCallback(final IPictureProfileCallback callback) {
            if (DEBUG) {
                Slog.d(TAG, "registerPictureProfileCallback");
            }
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();

            UserState userState = getOrCreateUserState(UserHandle.USER_SYSTEM);
            userState.mPictureProfileCallbackPidUidMap.put(callback,
                    Pair.create(callingPid, callingUid));
            userState.mPictureProfileCallbacks.register(callback);
        }

        @Override
        public void registerSoundProfileCallback(final ISoundProfileCallback callback) {
            if (DEBUG) {
                Slog.d(TAG, "registerSoundProfileCallback");
            }
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();

            UserState userState = getOrCreateUserState(UserHandle.USER_SYSTEM);
            userState.mSoundProfileCallbackPidUidMap.put(callback,
                    Pair.create(callingPid, callingUid));
            userState.mSoundProfileCallbacks.register(callback);
        }

        @Override
        public void registerActiveProcessingPictureListener(
                final IActiveProcessingPictureListener l) {
            if (DEBUG) {
                Slog.d(TAG, "registerActiveProcessingPictureListener");
            }
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();

            UserState userState = getOrCreateUserState(UserHandle.USER_SYSTEM);
            String packageName = getPackageOfUid(callingUid);
            userState.mActiveProcessingPictureListenerMap.put(l,
                    new ActiveProcessingPictureListenerInfo(callingUid, callingPid, packageName));
            userState.mActiveProcessingPictureCallbacks.register(l);
        }

        @Override
        public void registerAmbientBacklightCallback(IAmbientBacklightCallback callback) {
            if (DEBUG) {
                Slog.d(TAG, "registerAmbientBacklightCallback");
            }
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();

            if (!hasReadColorZonesPermission(callingUid, callingPid)) {
                //TODO: error handling
            }

            String callingPackageName = getPackageOfUid(callingUid);

            synchronized (mCallbackRecords) {
                AmbientBacklightCallbackRecord record = mCallbackRecords.get(callingPackageName);
                if (record != null) {
                    if (record.mCallback.asBinder().equals(callback.asBinder())) {
                        Slog.w(TAG, "AmbientBacklight Callback already registered");
                        return;
                    }
                    record.release();
                    mCallbackRecords.remove(callingPackageName);
                }
                mCallbackRecords.put(callingPackageName,
                        new AmbientBacklightCallbackRecord(callingPackageName, callback));
            }
        }

        public void unregisterAmbientBacklightCallback(IAmbientBacklightCallback callback) {
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            if (DEBUG) {
                Slog.d(TAG, "unregisterAmbientBacklightCallback");
            }

            if (!hasReadColorZonesPermission(callingUid, callingPid)) {
                //TODO: error handling
            }

            synchronized (mCallbackRecords) {
                for (AmbientBacklightCallbackRecord record : mCallbackRecords.values()) {
                    if (record.mCallback.asBinder().equals(callback.asBinder())) {
                        record.release();
                        mCallbackRecords.remove(record.mPackageName);
                        return;
                    }
                }
            }
        }

        @GuardedBy("mAmbientBacklightLock")
        @Override
        public void setAmbientBacklightSettings(
                AmbientBacklightSettings settings, int userId) {
            if (DEBUG) {
                Slog.d(TAG, "setAmbientBacklightSettings " + settings);
            }

            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();

            if (!hasReadColorZonesPermission(callingUid, callingPid)) {
                //TODO: error handling
            }

            try {
                if (mMediaQuality != null) {
                    android.hardware.tv.mediaquality.AmbientBacklightSettings halSettings =
                            new android.hardware.tv.mediaquality.AmbientBacklightSettings();
                    halSettings.uid = callingUid;
                    halSettings.source = (byte) settings.getSource();
                    halSettings.maxFramerate = settings.getMaxFps();
                    halSettings.colorFormat = (byte) settings.getColorFormat();
                    halSettings.hZonesNumber = settings.getHorizontalZonesCount();
                    halSettings.vZonesNumber = settings.getVerticalZonesCount();
                    halSettings.hasLetterbox = settings.isLetterboxOmitted();
                    halSettings.colorThreshold = settings.getThreshold();

                    mMediaQuality.setAmbientBacklightDetector(halSettings);

                    mHalAmbientBacklightCallback.setAmbientBacklightClientPackageName(
                            getPackageOfUid(callingUid));

                    if (DEBUG) {
                        Slog.d(TAG, "set ambient settings package: " + halSettings.uid);
                    }
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to set ambient backlight settings", e);
            }
        }

        @GuardedBy("mAmbientBacklightLock")
        @Override
        public void setAmbientBacklightEnabled(boolean enabled, int userId) {
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            if (DEBUG) {
                Slog.d(TAG, "setAmbientBacklightEnabled " + enabled);
            }
            if (!hasReadColorZonesPermission(callingUid, callingPid)) {
                //TODO: error handling
            }
            try {
                if (mMediaQuality != null) {
                    mMediaQuality.setAmbientBacklightDetectionEnabled(enabled);
                }
            } catch (UnsupportedOperationException e) {
                Slog.e(TAG, "The current device is not supported");
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to set ambient backlight enabled", e);
            }
        }

        @Override
        public List<ParameterCapability> getParameterCapabilities(
                List<String> names, int userId) {
            if (DEBUG) {
                Slog.d(TAG, "getParameterCapabilities: " + names);
            }
            byte[] byteArray = MediaQualityUtils.convertParameterToByteArray(names);
            ParamCapability[] caps = new ParamCapability[byteArray.length];
            try {
                mMediaQuality.getParamCaps(byteArray, caps);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to get parameter capabilities", e);
            }

            //Handle vendor parameter capability.
            MediaQualityUtils.getVendorParamsByRemovePreDefineParams(names);
            int namesCount = names.size();
            VendorParamCapability[] vendorParamCapabilities =
                    new VendorParamCapability[namesCount];
            if (!names.isEmpty()) {
                List<VendorParameterIdentifier> vendorParamIdentifiersList = new ArrayList<>();
                for (String name: names) {
                    DefaultExtension vendorParamCapDefaultExtension = new DefaultExtension();
                    Parcel vendorParamCapParcel = Parcel.obtain();
                    vendorParamCapParcel.writeString(name);
                    vendorParamCapDefaultExtension.bytes = vendorParamCapParcel.marshall();

                    VendorParameterIdentifier vendorParamIdentifier =
                            new VendorParameterIdentifier();
                    vendorParamIdentifier.identifier.setParcelable(vendorParamCapDefaultExtension);
                    vendorParamIdentifiersList.add(vendorParamIdentifier);
                    vendorParamCapParcel.recycle();
                }

                VendorParameterIdentifier[] vendorParamIdentifierArray =
                        new VendorParameterIdentifier[namesCount];
                for (int i = 0; i < namesCount; i++) {
                    vendorParamIdentifierArray[i] = vendorParamIdentifiersList.get(i);
                }

                try {
                    mMediaQuality.getVendorParamCaps(
                            vendorParamIdentifierArray, vendorParamCapabilities);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to get vendor parameter capabilities", e);
                }
            }

            return getParameterCapabilityList(caps, vendorParamCapabilities);
        }

        private List<ParameterCapability> getParameterCapabilityList(
                ParamCapability[] caps, VendorParamCapability[] vendorParamCaps) {
            List<ParameterCapability> pcList = new ArrayList<>();

            if (caps != null) {
                for (ParamCapability pcHal : caps) {
                    if (pcHal != null) {
                        String name = MediaQualityUtils.getParameterName(pcHal.name);
                        boolean isSupported = pcHal.isSupported;
                        int type = pcHal.defaultValue == null ? 0 : pcHal.defaultValue.getTag() + 1;
                        Bundle bundle = MediaQualityUtils.convertToCaps(type, pcHal.range);
                        putParamCapDefaultValueIntoBundle(bundle, pcHal.defaultValue);

                        pcList.add(new ParameterCapability(name, isSupported, type, bundle));
                    }
                }
            }

            if (vendorParamCaps != null) {
                for (VendorParamCapability vpcHal : vendorParamCaps) {
                    if (vpcHal != null) {
                        String name = MediaQualityUtils.getVendorParameterName(vpcHal);
                        boolean isSupported = vpcHal.isSupported;
                        // The default value for VendorParamCapability in HAL is IntValue = 0,
                        // LongValue = 1, DoubleValue = 2, StringValue = 3. The default value for
                        // ParameterCapability in the framework is None = 0, IntValue = 1,
                        // LongValue = 2, DoubleValue = 3, StringValue = 4. So +1 here to map the
                        // default value in HAL with framework.
                        int type = vpcHal.defaultValue
                                == null ? 0 : vpcHal.defaultValue.getTag() + 1;
                        Bundle paramRangeBundle = MediaQualityUtils.convertToCaps(
                                type, vpcHal.range);
                        putParamCapDefaultValueIntoBundle(paramRangeBundle, vpcHal.defaultValue);
                        MediaQualityUtils.convertToVendorCaps(vpcHal, paramRangeBundle);
                        pcList.add(new ParameterCapability(
                                name, isSupported, type, paramRangeBundle));
                    }
                }
            }

            return pcList;
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public List<String> getPictureProfileAllowList(int userId) {
            if (DEBUG) {
                Slog.d(TAG, "getPictureProfileAllowList");
            }
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            if (!hasGlobalPictureQualityServicePermission(callingUid, callingPid)) {
                mMqManagerNotifier.notifyOnPictureProfileError(
                        null, PictureProfile.ERROR_NO_PERMISSION, callingUid, callingPid);
                Slog.e(TAG, "no permission to get picture profile allow list");
                return new ArrayList<>();
            }
            String allowlist = mPictureProfileSharedPreference.getString(ALLOWLIST, null);
            if (allowlist != null) {
                String[] stringArray = allowlist.split(COMMA_DELIMITER);
                return new ArrayList<>(Arrays.asList(stringArray));
            }
            return new ArrayList<>();
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public void setPictureProfileAllowList(List<String> packages, int userId) {
            if (DEBUG) {
                Slog.d(TAG, "setPictureProfileAllowList");
            }
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            mHandler.post(() -> {
                if (!hasGlobalPictureQualityServicePermission(callingUid, callingPid)) {
                    mMqManagerNotifier.notifyOnPictureProfileError(
                            null, PictureProfile.ERROR_NO_PERMISSION, callingUid, callingPid);
                    Slog.e(TAG, "No permission to set picture profile allow list");
                    return;
                }
                SharedPreferences.Editor editor = mPictureProfileSharedPreference.edit();
                editor.putString(ALLOWLIST, String.join(COMMA_DELIMITER, packages));
                editor.commit();
            });
        }

        @GuardedBy("mSoundProfileLock")
        @Override
        public List<String> getSoundProfileAllowList(int userId) {
            if (DEBUG) {
                Slog.d(TAG, "getSoundProfileAllowList");
            }
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            if (!hasGlobalSoundQualityServicePermission(callingUid, callingPid)) {
                mMqManagerNotifier.notifyOnSoundProfileError(
                        null, SoundProfile.ERROR_NO_PERMISSION, callingUid, callingPid);
                Slog.e(TAG, "No permission to get sound profile allow list");
                return new ArrayList<>();
            }
            String allowlist = mSoundProfileSharedPreference.getString(ALLOWLIST, null);
            if (allowlist != null) {
                String[] stringArray = allowlist.split(COMMA_DELIMITER);
                return new ArrayList<>(Arrays.asList(stringArray));
            }
            return new ArrayList<>();
        }

        @GuardedBy("mSoundProfileLock")
        @Override
        public void setSoundProfileAllowList(List<String> packages, int userId) {
            if (DEBUG) {
                Slog.d(TAG, "setSoundProfileAllowList");
            }
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            mHandler.post(() -> {
                if (!hasGlobalSoundQualityServicePermission(callingUid, callingPid)) {
                    mMqManagerNotifier.notifyOnSoundProfileError(
                            null, SoundProfile.ERROR_NO_PERMISSION, callingUid, callingPid);
                    Slog.e(TAG, "No permission to set sound profile allow list");
                    return;
                }
                SharedPreferences.Editor editor = mSoundProfileSharedPreference.edit();
                editor.putString(ALLOWLIST, String.join(COMMA_DELIMITER, packages));
                editor.commit();
            });
        }

        @Override
        public boolean isSupported(int userId) {
            return false;
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public void setAutoPictureQualityEnabled(boolean enabled, int userId) {
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            mHandler.post(() -> {
                if (!hasGlobalPictureQualityServicePermission(callingUid, callingPid)) {
                    mMqManagerNotifier.notifyOnPictureProfileError(
                            null, PictureProfile.ERROR_NO_PERMISSION, callingUid, callingPid);
                    return;
                }
                synchronized (mPictureProfileLock) {
                    try {
                        if (mMediaQuality != null) {
                            if (mMediaQuality.isAutoPqSupported()) {
                                mMediaQuality.setAutoPqEnabled(enabled);
                            }
                        }
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Failed to set auto picture quality", e);
                    }
                }
            });
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public boolean isAutoPictureQualityEnabled(int userId) {
            synchronized (mPictureProfileLock) {
                try {
                    if (mMediaQuality != null) {
                        if (mMediaQuality.isAutoPqSupported()) {
                            return mMediaQuality.getAutoPqEnabled();
                        }
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to get auto picture quality", e);
                }
                return false;
            }
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public void setSuperResolutionEnabled(boolean enabled, int userId) {
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            mHandler.post(() -> {
                if (!hasGlobalPictureQualityServicePermission(callingUid, callingPid)) {
                    mMqManagerNotifier.notifyOnPictureProfileError(
                            null, PictureProfile.ERROR_NO_PERMISSION, callingUid, callingPid);
                    return;
                }
                synchronized (mPictureProfileLock) {
                    try {
                        if (mMediaQuality != null) {
                            if (mMediaQuality.isAutoSrSupported()) {
                                mMediaQuality.setAutoSrEnabled(enabled);
                            }
                        }
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Failed to set super resolution", e);
                    }
                }
            });
        }

        @GuardedBy("mPictureProfileLock")
        @Override
        public boolean isSuperResolutionEnabled(int userId) {
            synchronized (mPictureProfileLock) {
                try {
                    if (mMediaQuality != null) {
                        if (mMediaQuality.isAutoSrSupported()) {
                            return mMediaQuality.getAutoSrEnabled();
                        }
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to get super resolution", e);
                }
                return false;
            }
        }

        @GuardedBy("mSoundProfileLock")
        @Override
        public void setAutoSoundQualityEnabled(boolean enabled, int userId) {
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            mHandler.post(() -> {
                if (!hasGlobalSoundQualityServicePermission(callingUid, callingPid)) {
                    mMqManagerNotifier.notifyOnSoundProfileError(
                            null, SoundProfile.ERROR_NO_PERMISSION, callingUid, callingPid);
                    Slog.e(TAG, "setAutoSoundQualityEnabled: "
                            + "no permission to set auto sound quality enabled");
                    return;
                }

                synchronized (mSoundProfileLock) {
                    try {
                        if (mMediaQuality != null) {
                            if (mMediaQuality.isAutoAqSupported()) {
                                mMediaQuality.setAutoAqEnabled(enabled);
                            }
                        }
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Failed to set auto sound quality", e);
                    }
                }
            });
        }

        @GuardedBy("mSoundProfileLock")
        @Override
        public boolean isAutoSoundQualityEnabled(int userId) {
            synchronized (mSoundProfileLock) {
                try {
                    if (mMediaQuality != null) {
                        if (mMediaQuality.isAutoAqSupported()) {
                            return mMediaQuality.getAutoAqEnabled();
                        }
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to get auto sound quality", e);
                }
                return false;
            }
        }

        @GuardedBy("mAmbientBacklightLock")
        @Override
        public boolean isAmbientBacklightEnabled(int userId) {
            return false;
        }
    }

    public void updatePictureProfileFromHal(Long dbId, PersistableBundle bundle) {
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        PictureProfile pp = mMqDatabaseUtils.getPictureProfile(dbId);
        ContentValues values = MediaQualityUtils.getContentValues(dbId,
                pp.getProfileType(),
                pp.getName(),
                pp.getPackageName(),
                pp.getInputId(),
                bundle);

        updateDatabaseOnPictureProfileAndNotifyManager(
                values, bundle, callingUid, callingPid, false);
    }

    public void updateDatabaseOnPictureProfileAndNotifyManager(
            ContentValues values, PersistableBundle bundle, int uid, int pid, boolean notifyHal) {
        SQLiteDatabase db = mMediaQualityDbHelper.getWritableDatabase();
        db.replace(mMediaQualityDbHelper.PICTURE_QUALITY_TABLE_NAME,
                null, values);
        Long dbId = values.getAsLong(BaseParameters.PARAMETER_ID);
        mMqManagerNotifier.notifyOnPictureProfileUpdated(mPictureProfileTempIdMap.getValue(dbId),
                mMqDatabaseUtils.getPictureProfile(dbId, true), uid, pid);
        if (notifyHal) {
            mHalNotifier.notifyHalOnPictureProfileChange(dbId, bundle);
        }
    }

    public void updateSoundProfileFromHal(Long dbId, PersistableBundle bundle) {
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        SoundProfile sp = mMqDatabaseUtils.getSoundProfile(dbId);
        ContentValues values = MediaQualityUtils.getContentValues(dbId,
                sp.getProfileType(),
                sp.getName(),
                sp.getPackageName(),
                sp.getInputId(),
                bundle);

        updateDatabaseOnSoundProfileAndNotifyManager(values, bundle, callingUid,
                callingPid, false);
    }

    public void updateDatabaseOnSoundProfileAndNotifyManager(
            ContentValues values, PersistableBundle bundle, int uid, int pid, boolean notifyHal) {
        SQLiteDatabase db = mMediaQualityDbHelper.getWritableDatabase();
        db.replace(mMediaQualityDbHelper.SOUND_QUALITY_TABLE_NAME,
                null, values);
        Long dbId = values.getAsLong(BaseParameters.PARAMETER_ID);
        mMqManagerNotifier.notifyOnSoundProfileUpdated(mSoundProfileTempIdMap.getValue(dbId),
                mMqDatabaseUtils.getSoundProfile(dbId), uid, pid);
        if (notifyHal) {
            mHalNotifier.notifyHalOnSoundProfileChange(dbId, bundle);
        }
    }

    private class MediaQualityManagerPictureProfileCallbackList extends
            RemoteCallbackList<IPictureProfileCallback> {
        @Override
        public void onCallbackDied(IPictureProfileCallback callback) {
            synchronized (mPictureProfileLock) {
                for (int i = 0; i < mUserStates.size(); i++) {
                    int userId = mUserStates.keyAt(i);
                    UserState userState = getOrCreateUserState(userId);
                    userState.mPictureProfileCallbackPidUidMap.remove(callback);
                }
            }
        }
    }

    private class MediaQualityManagerSoundProfileCallbackList extends
            RemoteCallbackList<ISoundProfileCallback> {
        @Override
        public void onCallbackDied(ISoundProfileCallback callback) {
            synchronized (mSoundProfileLock) {
                for (int i = 0; i < mUserStates.size(); i++) {
                    int userId = mUserStates.keyAt(i);
                    UserState userState = getOrCreateUserState(userId);
                    userState.mSoundProfileCallbackPidUidMap.remove(callback);
                }
            }
        }
    }

    private class ActiveProcessingPictureCallbackList extends
            RemoteCallbackList<IActiveProcessingPictureListener> {
        @Override
        public void onCallbackDied(IActiveProcessingPictureListener l) {
            synchronized (mPictureProfileLock) {
                for (int i = 0; i < mUserStates.size(); i++) {
                    int userId = mUserStates.keyAt(i);
                    UserState userState = getOrCreateUserState(userId);
                    userState.mActiveProcessingPictureListenerMap.remove(l);
                }
            }
        }
    }

    private final class UserState {
        // A list of callbacks.
        private final MediaQualityManagerPictureProfileCallbackList mPictureProfileCallbacks =
                new MediaQualityManagerPictureProfileCallbackList();

        private final MediaQualityManagerSoundProfileCallbackList mSoundProfileCallbacks =
                new MediaQualityManagerSoundProfileCallbackList();

        private final ActiveProcessingPictureCallbackList mActiveProcessingPictureCallbacks =
                new ActiveProcessingPictureCallbackList();

        private final Map<IPictureProfileCallback, Pair<Integer, Integer>>
                mPictureProfileCallbackPidUidMap = new HashMap<>();

        private final Map<ISoundProfileCallback, Pair<Integer, Integer>>
                mSoundProfileCallbackPidUidMap = new HashMap<>();

        private final Map<IActiveProcessingPictureListener, ActiveProcessingPictureListenerInfo>
                mActiveProcessingPictureListenerMap = new HashMap<>();

        private UserState(Context context, int userId) {

        }
    }

    private final class ActiveProcessingPictureListenerInfo {
        private int mUid;
        private int mPid;
        private String mPackageName;

        ActiveProcessingPictureListenerInfo(int uid, int pid, String packageName) {
            mUid = uid;
            mPid = pid;
            mPackageName = packageName;
        }
    }

    private UserState getOrCreateUserState(int userId) {
        UserState userState = getUserState(userId);
        if (userState == null) {
            userState = new UserState(mContext, userId);
            synchronized (mUserStateLock) {
                mUserStates.put(userId, userState);
            }
        }
        return userState;
    }

    @GuardedBy("mUserStateLock")
    private UserState getUserState(int userId) {
        synchronized (mUserStateLock) {
            return mUserStates.get(userId);
        }
    }

    private final class MqDatabaseUtils {

        private PictureProfile getPictureProfile(Long dbId) {
            return getPictureProfile(dbId, false);
        }

        private PictureProfile getPictureProfile(Long dbId, boolean includeParams) {
            String selection = BaseParameters.PARAMETER_ID + " = ?";
            String[] selectionArguments = {Long.toString(dbId)};

            try (Cursor cursor = getCursorAfterQuerying(
                    mMediaQualityDbHelper.PICTURE_QUALITY_TABLE_NAME,
                    MediaQualityUtils.getMediaProfileColumns(includeParams), selection,
                    selectionArguments)) {
                int count = cursor.getCount();
                if (count == 0) {
                    return null;
                }
                if (count > 1) {
                    Log.wtf(TAG, TextUtils.formatSimple(String.valueOf(Locale.US), "%d entries "
                                    + "found for id=%d in %s. Should only ever be 0 or 1.",
                            count, dbId, mMediaQualityDbHelper.PICTURE_QUALITY_TABLE_NAME));
                    return null;
                }
                cursor.moveToFirst();
                return MediaQualityUtils.convertCursorToPictureProfileWithTempId(cursor,
                        mPictureProfileTempIdMap);
            }
        }

        private List<PictureProfile> getPictureProfilesBasedOnConditions(String[] columns,
                String selection, String[] selectionArguments) {
            try (Cursor cursor = getCursorAfterQuerying(
                    mMediaQualityDbHelper.PICTURE_QUALITY_TABLE_NAME, columns, selection,
                    selectionArguments)) {
                List<PictureProfile> pictureProfiles = new ArrayList<>();
                while (cursor.moveToNext()) {
                    pictureProfiles.add(MediaQualityUtils.convertCursorToPictureProfileWithTempId(
                            cursor, mPictureProfileTempIdMap));
                }
                return pictureProfiles;
            }
        }

        private SoundProfile getSoundProfile(Long dbId) {
            String selection = BaseParameters.PARAMETER_ID + " = ?";
            String[] selectionArguments = {Long.toString(dbId)};

            try (Cursor cursor = mMqDatabaseUtils.getCursorAfterQuerying(
                    mMediaQualityDbHelper.SOUND_QUALITY_TABLE_NAME,
                    MediaQualityUtils.getMediaProfileColumns(false), selection,
                    selectionArguments)) {
                int count = cursor.getCount();
                if (count == 0) {
                    return null;
                }
                if (count > 1) {
                    Log.wtf(TAG, TextUtils.formatSimple(String.valueOf(Locale.US), "%d entries "
                                    + "found for id=%s in %s. Should only ever be 0 or 1.", count,
                            dbId, mMediaQualityDbHelper.SOUND_QUALITY_TABLE_NAME));
                    return null;
                }
                cursor.moveToFirst();
                return MediaQualityUtils.convertCursorToSoundProfileWithTempId(
                        cursor, mSoundProfileTempIdMap);
            }
        }

        private List<SoundProfile> getSoundProfilesBasedOnConditions(String[] columns,
                String selection, String[] selectionArguments) {
            try (Cursor cursor = mMqDatabaseUtils.getCursorAfterQuerying(
                    mMediaQualityDbHelper.SOUND_QUALITY_TABLE_NAME, columns, selection,
                    selectionArguments)) {
                List<SoundProfile> soundProfiles = new ArrayList<>();
                while (cursor.moveToNext()) {
                    soundProfiles.add(MediaQualityUtils.convertCursorToSoundProfileWithTempId(
                            cursor, mSoundProfileTempIdMap));
                }
                return soundProfiles;
            }
        }

        private Cursor getCursorAfterQuerying(String table, String[] columns, String selection,
                String[] selectionArgs) {
            SQLiteDatabase db = mMediaQualityDbHelper.getReadableDatabase();
            return db.query(table, columns, selection, selectionArgs,
                    /*groupBy=*/ null, /*having=*/ null, /*orderBy=*/ null);
        }

        private MqDatabaseUtils() {
        }
    }

    private final class MqManagerNotifier {

        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        private @interface ProfileModes {
            int ADD = 1;
            int UPDATE = 2;
            int REMOVE = 3;
            int ERROR = 4;
            int PARAMETER_CAPABILITY_CHANGED = 5;
        }

        private void notifyOnPictureProfileAdded(String profileId, PictureProfile profile,
                int uid, int pid) {
            notifyPictureProfileHelper(ProfileModes.ADD, profileId, profile, null, null, uid, pid);
        }

        private void notifyOnPictureProfileUpdated(String profileId, PictureProfile profile,
                int uid, int pid) {
            notifyPictureProfileHelper(ProfileModes.UPDATE, profileId, profile, null, null, uid,
                    pid);
        }

        private void notifyOnPictureProfileRemoved(String profileId, PictureProfile profile,
                int uid, int pid) {
            notifyPictureProfileHelper(ProfileModes.REMOVE, profileId, profile, null, null, uid,
                    pid);
        }

        private void notifyOnPictureProfileError(String profileId, int errorCode,
                int uid, int pid) {
            notifyPictureProfileHelper(ProfileModes.ERROR, profileId, null, errorCode, null, uid,
                    pid);
        }

        private void notifyOnPictureProfileParameterCapabilitiesChanged(Long profileId,
                List<ParameterCapability> paramCaps, int uid, int pid) {
            String uuid = mPictureProfileTempIdMap.getValue(profileId);
            notifyPictureProfileHelper(ProfileModes.PARAMETER_CAPABILITY_CHANGED, uuid,
                    null, null, paramCaps , uid, pid);
        }

        private void notifyPictureProfileHelper(int mode, String profileId,
                PictureProfile profile, Integer errorCode,
                List<ParameterCapability> paramCaps, int uid, int pid) {
            UserState userState = getOrCreateUserState(UserHandle.USER_SYSTEM);
            int n = userState.mPictureProfileCallbacks.beginBroadcast();
            for (int i = 0; i < n; ++i) {
                try {
                    IPictureProfileCallback callback = userState.mPictureProfileCallbacks
                            .getBroadcastItem(i);
                    Pair<Integer, Integer> pidUid = userState.mPictureProfileCallbackPidUidMap
                            .get(callback);
                    if ((pidUid.first == pid && pidUid.second == uid)
                            || (hasGlobalPictureQualityServicePermission(
                                    pidUid.second, pidUid.first))) {
                        if (profile != null
                                && profile.getProfileType() == PictureProfile.TYPE_SYSTEM) {
                            switch (mode) {
                                case ProfileModes.ADD ->
                                        callback.onPictureProfileAdded(profileId, profile);
                                case ProfileModes.UPDATE ->
                                        callback.onPictureProfileUpdated(profileId, profile);
                                case ProfileModes.REMOVE ->
                                        callback.onPictureProfileRemoved(profileId, profile);
                            }
                        } else {
                            switch (mode) {
                                case ProfileModes.ERROR -> callback.onError(profileId, errorCode);
                                case ProfileModes.PARAMETER_CAPABILITY_CHANGED ->
                                    callback.onParameterCapabilitiesChanged(profileId, paramCaps);
                            }
                        }
                    }
                } catch (RemoteException e) {
                    if (mode == ProfileModes.ADD) {
                        Slog.e(TAG, "Failed to report added picture profile to callback", e);
                    } else if (mode == ProfileModes.UPDATE) {
                        Slog.e(TAG, "Failed to report updated picture profile to callback", e);
                    } else if (mode == ProfileModes.REMOVE) {
                        Slog.e(TAG, "Failed to report removed picture profile to callback", e);
                    } else if (mode == ProfileModes.ERROR) {
                        Slog.e(TAG, "Failed to report picture profile error to callback", e);
                    } else {
                        Slog.e(TAG, "Failed to report picture profile parameter capability"
                                + " change to callback", e);
                    }
                }
            }
            userState.mPictureProfileCallbacks.finishBroadcast();
        }

        private void notifyOnSoundProfileAdded(String profileId, SoundProfile profile,
                int uid, int pid) {
            notifySoundProfileHelper(ProfileModes.ADD, profileId, profile, null, null, uid, pid);
        }

        private void notifyOnSoundProfileUpdated(String profileId, SoundProfile profile,
                int uid, int pid) {
            notifySoundProfileHelper(ProfileModes.UPDATE, profileId, profile, null, null, uid, pid);
        }

        private void notifyOnSoundProfileRemoved(String profileId, SoundProfile profile,
                int uid, int pid) {
            notifySoundProfileHelper(ProfileModes.REMOVE, profileId, profile, null, null, uid, pid);
        }

        private void notifyOnSoundProfileError(String profileId, int errorCode, int uid, int pid) {
            notifySoundProfileHelper(ProfileModes.ERROR, profileId, null, errorCode, null, uid,
                    pid);
        }

        private void notifyOnSoundProfileParameterCapabilitiesChanged(Long profileId,
                ParamCapability[] caps, int uid, int pid) {
            String uuid = mSoundProfileTempIdMap.getValue(profileId);
            List<ParameterCapability> paramCaps = new ArrayList<>();
            for (ParamCapability cap: caps) {
                String name = MediaQualityUtils.getParameterName(cap.name);
                boolean isSupported = cap.isSupported;
                int type = cap.defaultValue == null ? 0 : cap.defaultValue.getTag() + 1;
                Bundle bundle = MediaQualityUtils.convertToCaps(type, cap.range);
                putParamCapDefaultValueIntoBundle(bundle, cap.defaultValue);

                paramCaps.add(new ParameterCapability(name, isSupported, type, bundle));
            }
            notifySoundProfileHelper(ProfileModes.PARAMETER_CAPABILITY_CHANGED, uuid,
                    null, null, paramCaps , uid, pid);
        }

        private void notifySoundProfileHelper(int mode, String profileId,
                SoundProfile profile, Integer errorCode,
                List<ParameterCapability> paramCaps, int uid, int pid) {
            UserState userState = getOrCreateUserState(UserHandle.USER_SYSTEM);
            int n = userState.mSoundProfileCallbacks.beginBroadcast();

            for (int i = 0; i < n; ++i) {
                try {
                    ISoundProfileCallback callback = userState.mSoundProfileCallbacks
                            .getBroadcastItem(i);
                    Pair<Integer, Integer> pidUid = userState.mSoundProfileCallbackPidUidMap
                            .get(callback);

                    if ((pidUid.first == pid && pidUid.second == uid)
                            || (hasGlobalSoundQualityServicePermission(
                            pidUid.second, pidUid.first))) {
                        if (profile != null
                                && profile.getProfileType() == SoundProfile.TYPE_SYSTEM) {
                            switch (mode) {
                                case ProfileModes.ADD ->
                                        callback.onSoundProfileAdded(profileId, profile);
                                case ProfileModes.UPDATE ->
                                        callback.onSoundProfileUpdated(profileId, profile);
                                case ProfileModes.REMOVE ->
                                        callback.onSoundProfileRemoved(profileId, profile);
                            }
                        } else {
                            switch (mode) {
                                case ProfileModes.ERROR -> callback.onError(profileId, errorCode);
                                case ProfileModes.PARAMETER_CAPABILITY_CHANGED ->
                                    callback.onParameterCapabilitiesChanged(profileId, paramCaps);
                            }
                        }
                    }
                } catch (RemoteException e) {
                    if (mode == ProfileModes.ADD) {
                        Slog.e(TAG, "Failed to report added sound profile to callback", e);
                    } else if (mode == ProfileModes.UPDATE) {
                        Slog.e(TAG, "Failed to report updated sound profile to callback", e);
                    } else if (mode == ProfileModes.REMOVE) {
                        Slog.e(TAG, "Failed to report removed sound profile to callback", e);
                    } else if (mode == ProfileModes.ERROR) {
                        Slog.e(TAG, "Failed to report sound profile error to callback", e);
                    } else if (mode == ProfileModes.PARAMETER_CAPABILITY_CHANGED) {
                        Slog.e(TAG, "Failed to report sound profile parameter capability change "
                                + "to callback", e);
                    }
                }
            }
            userState.mSoundProfileCallbacks.finishBroadcast();
        }

        private MqManagerNotifier() {

        }
    }

    private final class HalNotifier {

        private void notifyHalOnPictureProfileChange(Long dbId, PersistableBundle params) {
            // TODO: only notify HAL when the profile is active / being used
            if (mPpChangedListener != null) {
                try {
                    Long idForHal = dbId;
                    if (DEBUG) {
                        Slog.d(TAG, "notifyHalOnPictureProfileChange: id is " + idForHal);
                        Collection<Long> values = mCurrentPictureHandleToOriginal.getValues();
                        for (Long value: values) {
                            Slog.d(TAG, "key: " + mCurrentPictureHandleToOriginal.getKey(value)
                                    + " value: " + value);
                        }
                    }
                    synchronized (mPictureProfileLock) {
                        Long originalHandle = mCurrentPictureHandleToOriginal.getValue(dbId);
                        if (originalHandle != null) {
                            // the original id is used in HAL because of status change
                            idForHal = originalHandle;
                            if (DEBUG) {
                                Slog.d(TAG, "Back to original handle with " + idForHal);
                            }
                        }
                    }
                    mPpChangedListener.onPictureProfileChanged(convertToHalPictureProfile(idForHal,
                            params));
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to notify HAL on picture profile change.", e);
                }
            }
        }

        private android.hardware.tv.mediaquality.PictureProfile convertToHalPictureProfile(Long id,
                PersistableBundle params) {
            PictureParameters pictureParameters = new PictureParameters();
            pictureParameters.pictureParameters =
                    MediaQualityUtils.convertPersistableBundleToPictureParameterList(
                            params);

            Parcel parcel = Parcel.obtain();
            if (params != null) {
                setVendorPictureParameters(pictureParameters, parcel, params);
            }

            android.hardware.tv.mediaquality.PictureProfile toReturn =
                    new android.hardware.tv.mediaquality.PictureProfile();
            toReturn.pictureProfileId = id;
            toReturn.parameters = pictureParameters;

            parcel.recycle();
            return toReturn;
        }

        private void notifyHalOnSoundProfileChange(Long dbId, PersistableBundle params) {
            // TODO: only notify HAL when the profile is active / being used
            if (mSpChangedListener != null) {
                try {
                    mSpChangedListener
                            .onSoundProfileChanged(convertToHalSoundProfile(dbId, params));
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to notify HAL on sound profile change.", e);
                }
            }
        }

        private android.hardware.tv.mediaquality.SoundProfile convertToHalSoundProfile(Long id,
                PersistableBundle params) {
            SoundParameters soundParameters = new SoundParameters();
            soundParameters.soundParameters =
                    MediaQualityUtils.convertPersistableBundleToSoundParameterList(params);

            Parcel parcel = Parcel.obtain();
            if (params != null) {
                setVendorSoundParameters(soundParameters, parcel, params);
            }

            android.hardware.tv.mediaquality.SoundProfile toReturn =
                    new android.hardware.tv.mediaquality.SoundProfile();
            toReturn.soundProfileId = id;
            toReturn.parameters = soundParameters;

            return toReturn;
        }

        private HalNotifier() {

        }
    }

    private final class AmbientBacklightCallbackRecord implements IBinder.DeathRecipient {
        final String mPackageName;
        final IAmbientBacklightCallback mCallback;

        AmbientBacklightCallbackRecord(@NonNull String pkgName,
                @NonNull IAmbientBacklightCallback cb) {
            mPackageName = pkgName;
            mCallback = cb;
            try {
                mCallback.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to link to death", e);
            }
        }

        void release() {
            try {
                mCallback.asBinder().unlinkToDeath(this, 0);
            } catch (NoSuchElementException e) {
                Slog.e(TAG, "Failed to unlink to death", e);
            }
        }

        @Override
        public void binderDied() {
            synchronized (mCallbackRecords) {
                mCallbackRecords.remove(mPackageName);
            }
        }
    }

    private final class PictureProfileAdjustmentListenerImpl extends
            IPictureProfileAdjustmentListener.Stub {

        @Override
        public void onPictureProfileAdjusted(
                android.hardware.tv.mediaquality.PictureProfile pictureProfile)
                throws RemoteException {
            Long dbId = pictureProfile.pictureProfileId;
            if (dbId != null) {
                android.hardware.tv.mediaquality.PictureParameter[] params =
                        pictureProfile.parameters.pictureParameters;
                for (android.hardware.tv.mediaquality.PictureParameter param : params) {
                    if (param.getTag() == PictureParameter.activeProfile
                            && !param.getActiveProfile()) {
                        synchronized (mPictureProfileLock) {
                            mOriginalHandleToCurrentPictureProfile.remove(dbId);
                            mCurrentPictureHandleToOriginal.removeValue(dbId);
                        }
                        break;
                    }
                }
                updatePictureProfileFromHal(dbId,
                        MediaQualityUtils.convertPictureParameterListToPersistableBundle(params));
            }
        }

        @Override
        public void onParamCapabilityChanged(long pictureProfileId, ParamCapability[] caps)
                throws RemoteException {
            List<ParameterCapability> paramCaps = new ArrayList<>();
            for (ParamCapability cap: caps) {
                String name = MediaQualityUtils.getParameterName(cap.name);
                boolean isSupported = cap.isSupported;
                //Reason for +1: please see getParameterCapabilityList()
                int type = cap.defaultValue == null ? 0 : cap.defaultValue.getTag() + 1;
                Bundle bundle = MediaQualityUtils.convertToCaps(type, cap.range);
                putParamCapDefaultValueIntoBundle(bundle, cap.defaultValue);

                paramCaps.add(new ParameterCapability(name, isSupported, type, bundle));
            }
            mMqManagerNotifier.notifyOnPictureProfileParameterCapabilitiesChanged(
                    pictureProfileId, paramCaps, Binder.getCallingUid(), Binder.getCallingPid());
        }

        @Override
        public void onVendorParamCapabilityChanged(long pictureProfileId,
                VendorParamCapability[] caps) throws RemoteException {
            List<ParameterCapability> vendorParamCaps = new ArrayList<>();
            for (VendorParamCapability vpcHal: caps) {
                String name = MediaQualityUtils.getVendorParameterName(vpcHal);
                boolean isSupported = vpcHal.isSupported;
                //Reason for +1: please see getParameterCapabilityList()
                int type = vpcHal.defaultValue
                        == null ? 0 : vpcHal.defaultValue.getTag() + 1;
                Bundle paramRangeBundle = MediaQualityUtils.convertToCaps(
                        type, vpcHal.range);
                putParamCapDefaultValueIntoBundle(paramRangeBundle, vpcHal.defaultValue);
                MediaQualityUtils.convertToVendorCaps(vpcHal, paramRangeBundle);
                vendorParamCaps.add(new ParameterCapability(
                        name, isSupported, type, paramRangeBundle));
            }
            mMqManagerNotifier.notifyOnPictureProfileParameterCapabilitiesChanged(
                    pictureProfileId,
                    vendorParamCaps,
                    Binder.getCallingUid(),
                    Binder.getCallingPid());
        }

        @Override
        public void requestPictureParameters(long pictureProfileId) throws RemoteException {
            if (DEBUG) {
                Log.d(TAG, "requestPictureParameters for picture profile id "
                        + pictureProfileId);
            }
            PictureProfile profile = mOriginalHandleToCurrentPictureProfile.get(pictureProfileId);
            if (profile == null) {
                profile = mMqDatabaseUtils.getPictureProfile(pictureProfileId, true);
            }
            if (profile != null) {
                mHalNotifier.notifyHalOnPictureProfileChange(pictureProfileId,
                        profile.getParameters());
            }
        }

        @Override
        public void onStreamStatusChanged(long profileHandle, byte status)
                throws RemoteException {
            if (DEBUG) {
                String streamStatus = toPictureProfileStatus(status);
                Log.d(TAG, "Called onStreamStatusChanged with profileHandle: " + profileHandle
                        + " and status is " + streamStatus);
            }
            mHandler.post(() -> {
                synchronized (mPictureProfileLock) {
                    // get from map if exists
                    PictureProfile previous = mOriginalHandleToCurrentPictureProfile
                            .get(profileHandle);
                    if (previous == null) {
                        Slog.d(TAG, "Previous profile not in the map");
                        // get from DB if not exists
                        previous = mMqDatabaseUtils.getPictureProfile(profileHandle);
                        if (previous == null) {
                            Slog.d(TAG, "Previous profile not in the database");
                            return;
                        }
                    }
                    String[] arr = splitNameAndStatus(previous.getName());
                    String profileName = arr[0];
                    String profileStatus = arr[1];
                    if (status != StreamStatus.SDR) {
                        // TODO: merge SDR handling
                        if (isSameStatus(profileStatus, status)) {
                            Slog.d(TAG, "The current status is the same as new status");
                            return;
                        }

                        // to new status
                        String newStatus = toPictureProfileStatus(status);
                        if (newStatus.isEmpty()) {
                            Slog.d(TAG, "new status is not a supported status");
                            return;
                        }
                        Slog.d(TAG, "The new status is " + newStatus);
                        String selection = BaseParameters.PARAMETER_TYPE + " = ? AND "
                                + BaseParameters.PARAMETER_PACKAGE + " = ? AND "
                                + BaseParameters.PARAMETER_NAME + " = ?";
                        List<String> selectionArguments = new ArrayList<>();
                        selectionArguments.add(Integer.toString(previous.getProfileType()));
                        selectionArguments.add(previous.getPackageName());
                        selectionArguments.add(profileName + "/" + newStatus);
                        if (previous.getInputId() != null) {
                            if (DEBUG) {
                                Slog.d(TAG, "onStreamStatusChanged: "
                                        + "The input is not null for previous picture profile");
                            }
                            selection += " AND " + BaseParameters.PARAMETER_INPUT_ID + " = ?";
                            selectionArguments.add(previous.getInputId());
                        }
                        if (DEBUG) {
                            Slog.d(TAG, "onStreamStatusChanged: "
                                    + "The selection is " + selection
                                    + " The selection argument is " + selectionArguments);
                        }
                        List<PictureProfile> list =
                                mMqDatabaseUtils.getPictureProfilesBasedOnConditions(
                                        MediaQualityUtils.getMediaProfileColumns(true),
                                        selection,
                                        selectionArguments.toArray(new String[0]));
                        if (list.isEmpty()) {
                            if (DEBUG) {
                                Slog.d(TAG, "The picture profile list is empty");
                            }
                            // Short term solution for b/422302653.
                            // Signal the HAL when the request stream status is not created by the
                            // APK.
                            PictureProfile currentSdr = getSdrPictureProfile(profileName, previous);
                            if (currentSdr == null) {
                                Slog.d(TAG, "The current SDR profile is null");
                                return;
                            }
                            PersistableBundle currentSdrParameter = currentSdr.getParameters();
                            currentSdrParameter.putString(
                                    STREAM_STATUS_NOT_CREATED, newStatus);
                            currentSdrParameter.putString(STREAM_STATUS, PictureProfile.STATUS_SDR);
                            // Add previous stream status information so that application can use
                            // this flag to indicate that there is a onStreamStatusChange.
                            currentSdrParameter.putString(PREVIOUS_STREAM_STATUS, profileStatus);
                            currentSdr.addStringParameter(STREAM_STATUS, PictureProfile.STATUS_SDR);
                            // PREVIOUS_STREAM_STATUS is used for one time, so copy the current
                            // profile
                            PictureProfile currentCopy = PictureProfile.copyFrom(currentSdr);
                            currentCopy.addStringParameter(PREVIOUS_STREAM_STATUS, profileStatus);
                            putCurrentPictureProfile(profileHandle, currentSdr.getHandle().getId(),
                                    currentSdr);
                            mMqManagerNotifier.notifyOnPictureProfileUpdated(
                                    currentCopy.getProfileId(), currentCopy, Process.INVALID_UID,
                                    Process.INVALID_PID);

                            mPictureProfileForHal.add(profileHandle);
                            mPictureProfileForHal.add(currentSdr.getHandle().getId());
                            mHalNotifier.notifyHalOnPictureProfileChange(profileHandle,
                                    currentSdrParameter);

                            Slog.d(TAG, "Picture profile not found for status: " + newStatus);
                            return;
                        }
                        PictureProfile current = list.get(0);
                        PersistableBundle currentProfileParameters = current.getParameters();
                        currentProfileParameters.putString(STREAM_STATUS, newStatus);
                        // Add previous stream status information so that application can use this
                        // flag to indicate that there is a onStreamStatusChange.
                        currentProfileParameters.putString(PREVIOUS_STREAM_STATUS, profileStatus);
                        current.addStringParameter(STREAM_STATUS, newStatus);
                        // PREVIOUS_STREAM_STATUS is used for one time, so copy the current profile
                        PictureProfile currentCopy = PictureProfile.copyFrom(current);
                        currentCopy.addStringParameter(PREVIOUS_STREAM_STATUS, profileStatus);
                        putCurrentPictureProfile(profileHandle, current.getHandle().getId(),
                                current);
                        // TODO: use package name to notify
                        mMqManagerNotifier.notifyOnPictureProfileUpdated(
                                currentCopy.getProfileId(), currentCopy, Process.INVALID_UID,
                                Process.INVALID_PID);

                        mPictureProfileForHal.add(profileHandle);
                        mPictureProfileForHal.add(current.getHandle().getId());
                        mHalNotifier.notifyHalOnPictureProfileChange(profileHandle,
                                currentProfileParameters);
                    } else {
                        if (DEBUG) {
                            Slog.d(TAG, "Handle SDR change");
                        }
                        if (isSdr(profileStatus)) {
                            Slog.d(TAG, "Current status is already SDR");
                            return;
                        }

                        // to SDR
                        PictureProfile current = getSdrPictureProfile(profileName, previous);
                        if (current == null) {
                            Slog.e(TAG, "The current SDR profile is null");
                            return;
                        }
                        PersistableBundle currentProfileParameters = current.getParameters();
                        currentProfileParameters.putString(
                                STREAM_STATUS, PictureProfile.STATUS_SDR);
                        // Add previous stream status information so that application can use this
                        // flag to indicate that there is a onStreamStatusChange.
                        currentProfileParameters.putString(PREVIOUS_STREAM_STATUS, profileStatus);
                        current.addStringParameter(STREAM_STATUS, PictureProfile.STATUS_SDR);
                        // PREVIOUS_STREAM_STATUS is used for one time, so copy the current profile
                        PictureProfile currentCopy = PictureProfile.copyFrom(current);
                        currentCopy.addStringParameter(PREVIOUS_STREAM_STATUS, profileStatus);
                        putCurrentPictureProfile(profileHandle, current.getHandle().getId(),
                                current);
                        // TODO: use package name to notify
                        mMqManagerNotifier.notifyOnPictureProfileUpdated(
                                currentCopy.getProfileId(), currentCopy, Process.INVALID_UID,
                                Process.INVALID_PID);

                        mPictureProfileForHal.add(current.getHandle().getId());
                        mPictureProfileForHal.add(profileHandle);
                        mHalNotifier.notifyHalOnPictureProfileChange(profileHandle,
                                currentProfileParameters);
                    }
                }
            });

        }

        @NonNull
        private String[] splitNameAndStatus(@NonNull String nameAndStatus) {
            int index = nameAndStatus.lastIndexOf('/');
            if (index == -1 || index == nameAndStatus.length() - 1) {
                // no status in the original name
                return new String[] {nameAndStatus, ""};
            }
            return new String[] {
                    nameAndStatus.substring(0, index),
                    nameAndStatus.substring(index + 1)
            };

        }

        private String toPictureProfileStatus(int halStatus) {
            switch (halStatus) {
                case StreamStatus.SDR:
                    return PictureProfile.STATUS_SDR;
                case StreamStatus.HDR10:
                    return PictureProfile.STATUS_HDR10;
                case StreamStatus.TCH:
                    return PictureProfile.STATUS_TCH;
                case StreamStatus.DOLBYVISION:
                    return PictureProfile.STATUS_DOLBY_VISION;
                case StreamStatus.HLG:
                    return PictureProfile.STATUS_HLG;
                case StreamStatus.HDR10PLUS:
                    return PictureProfile.STATUS_HDR10_PLUS;
                case StreamStatus.HDRVIVID:
                    return PictureProfile.STATUS_HDR_VIVID;
                case StreamStatus.IMAXSDR:
                    return PictureProfile.STATUS_IMAX_SDR;
                case StreamStatus.IMAXHDR10:
                    return PictureProfile.STATUS_IMAX_HDR10;
                case StreamStatus.IMAXHDR10PLUS:
                    return PictureProfile.STATUS_IMAX_HDR10_PLUS;
                case StreamStatus.FMMSDR:
                    return PictureProfile.STATUS_FMM_SDR;
                case StreamStatus.FMMHDR10:
                    return PictureProfile.STATUS_FMM_HDR10;
                case StreamStatus.FMMHDR10PLUS:
                    return PictureProfile.STATUS_FMM_HDR10_PLUS;
                case StreamStatus.FMMHLG:
                    return PictureProfile.STATUS_FMM_HLG;
                case StreamStatus.FMMDOLBY:
                    return PictureProfile.STATUS_FMM_DOLBY;
                case StreamStatus.FMMTCH:
                    return PictureProfile.STATUS_FMM_TCH;
                case StreamStatus.FMMHDRVIVID:
                    return PictureProfile.STATUS_FMM_HDR_VIVID;
                default:
                    return "";
            }
        }

        private byte toHalStatus(@NonNull String profileStatus) {
            // TODO: use biMap
            switch (profileStatus) {
                case PictureProfile.STATUS_SDR:
                    return StreamStatus.SDR;
                case PictureProfile.STATUS_HDR10:
                    return StreamStatus.HDR10;
                case PictureProfile.STATUS_TCH:
                    return StreamStatus.TCH;
                case PictureProfile.STATUS_DOLBY_VISION:
                    return StreamStatus.DOLBYVISION;
                case PictureProfile.STATUS_HLG:
                    return StreamStatus.HLG;
                case PictureProfile.STATUS_HDR10_PLUS:
                    return StreamStatus.HDR10PLUS;
                case PictureProfile.STATUS_HDR_VIVID:
                    return StreamStatus.HDRVIVID;
                case PictureProfile.STATUS_IMAX_SDR:
                    return StreamStatus.IMAXSDR;
                case PictureProfile.STATUS_IMAX_HDR10:
                    return StreamStatus.IMAXHDR10;
                case PictureProfile.STATUS_IMAX_HDR10_PLUS:
                    return StreamStatus.IMAXHDR10PLUS;
                case PictureProfile.STATUS_FMM_SDR:
                    return StreamStatus.FMMSDR;
                case PictureProfile.STATUS_FMM_HDR10:
                    return StreamStatus.FMMHDR10;
                case PictureProfile.STATUS_FMM_HDR10_PLUS:
                    return StreamStatus.FMMHDR10PLUS;
                case PictureProfile.STATUS_FMM_HLG:
                    return StreamStatus.FMMHLG;
                case PictureProfile.STATUS_FMM_DOLBY:
                    return StreamStatus.FMMDOLBY;
                case PictureProfile.STATUS_FMM_TCH:
                    return StreamStatus.FMMTCH;
                case PictureProfile.STATUS_FMM_HDR_VIVID:
                    return StreamStatus.FMMHDRVIVID;
                default:
                    return (byte) 0;
            }
        }

        private boolean isSdr(@NonNull String profileStatus) {
            return profileStatus.equals(PictureProfile.STATUS_SDR)
                    || profileStatus.isEmpty();
        }

        private boolean isSameStatus(@NonNull String profileStatus, int halStatus) {
            if (halStatus == StreamStatus.SDR) {
                return isSdr(profileStatus);
            }
            return toPictureProfileStatus(halStatus).equals(profileStatus);
        }

        @Override
        public int getInterfaceVersion() throws RemoteException {
            return 0;
        }

        @Override
        public String getInterfaceHash() throws RemoteException {
            return null;
        }

        private PictureProfileAdjustmentListenerImpl() {

        }
    }

    private final class SoundProfileAdjustmentListenerImpl extends
            ISoundProfileAdjustmentListener.Stub {

        @Override
        public void onSoundProfileAdjusted(
                android.hardware.tv.mediaquality.SoundProfile soundProfile) throws RemoteException {
            Long dbId = soundProfile.soundProfileId;
            if (dbId != null) {
                updateSoundProfileFromHal(dbId,
                        MediaQualityUtils.convertSoundParameterListToPersistableBundle(
                                soundProfile.parameters.soundParameters));
            }
        }

        @Override
        public void onParamCapabilityChanged(long soundProfileId, ParamCapability[] caps)
                throws RemoteException {
            mMqManagerNotifier.notifyOnSoundProfileParameterCapabilitiesChanged(
                    soundProfileId, caps, Binder.getCallingUid(), Binder.getCallingPid());
        }

        @Override
        public void onVendorParamCapabilityChanged(long pictureProfileId,
                VendorParamCapability[] caps) throws RemoteException {
            // TODO
        }

        @Override
        public void requestSoundParameters(long soundProfileId) throws RemoteException {
            SoundProfile profile = mMqDatabaseUtils.getSoundProfile(soundProfileId);
            if (profile != null) {
                mHalNotifier.notifyHalOnSoundProfileChange(soundProfileId,
                        profile.getParameters());
            }
        }

        @Override
        public int getInterfaceVersion() throws RemoteException {
            return 0;
        }

        @Override
        public String getInterfaceHash() throws RemoteException {
            return null;
        }

        private SoundProfileAdjustmentListenerImpl() {

        }
    }

    private final class HalAmbientBacklightCallback
            extends android.hardware.tv.mediaquality.IMediaQualityCallback.Stub {
        private final Object mLock = new Object();
        private String mAmbientBacklightClientPackageName;

        void setAmbientBacklightClientPackageName(@NonNull String packageName) {
            synchronized (mLock) {
                if (TextUtils.equals(mAmbientBacklightClientPackageName, packageName)) {
                    return;
                }
                handleAmbientBacklightInterrupted();
                mAmbientBacklightClientPackageName = packageName;
            }
        }

        void handleAmbientBacklightInterrupted() {
            synchronized (mCallbackRecords) {
                if (mAmbientBacklightClientPackageName == null) {
                    Slog.e(TAG, "Invalid package name in interrupted event");
                    return;
                }
                AmbientBacklightCallbackRecord record = mCallbackRecords.get(
                        mAmbientBacklightClientPackageName);
                if (record == null) {
                    Slog.e(TAG, "Callback record not found for ambient backlight");
                    return;
                }
                AmbientBacklightEvent event =
                        new AmbientBacklightEvent(
                                AMBIENT_BACKLIGHT_EVENT_INTERRUPTED, null);
                try {
                    record.mCallback.onAmbientBacklightEvent(event);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Deliver ambient backlight interrupted event failed", e);
                }
            }
        }

        void handleAmbientBacklightEnabled(boolean enabled) {
            AmbientBacklightEvent event =
                    new AmbientBacklightEvent(
                            enabled ? AMBIENT_BACKLIGHT_EVENT_ENABLED :
                                    AMBIENT_BACKLIGHT_EVENT_DISABLED, null);
            synchronized (mCallbackRecords) {
                for (AmbientBacklightCallbackRecord record : mCallbackRecords.values()) {
                    try {
                        record.mCallback.onAmbientBacklightEvent(event);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Deliver ambient backlight enabled event failed", e);
                    }
                }
            }
        }

        void handleAmbientBacklightMetadataEvent(
                @NonNull android.hardware.tv.mediaquality.AmbientBacklightMetadata
                        halMetadata) {
            String halPackageName = mContext.getPackageManager()
                                    .getNameForUid(halMetadata.settings.uid);
            if (!TextUtils.equals(mAmbientBacklightClientPackageName, halPackageName)) {
                Slog.e(TAG, "Invalid package name in metadata event");
                return;
            }

            AmbientBacklightColorFormat[] zonesColorsUnion = halMetadata.zonesColors;
            int[] zonesColorsInt = new int[zonesColorsUnion.length];

            for (int i = 0; i < zonesColorsUnion.length; i++) {
                zonesColorsInt[i] = zonesColorsUnion[i].RGB888;
            }

            AmbientBacklightMetadata metadata =
                    new AmbientBacklightMetadata(
                            halPackageName,
                            halMetadata.compressAlgorithm,
                            halMetadata.settings.source,
                            halMetadata.settings.colorFormat,
                            halMetadata.settings.hZonesNumber,
                            halMetadata.settings.vZonesNumber,
                            zonesColorsInt);
            AmbientBacklightEvent event =
                    new AmbientBacklightEvent(
                            AMBIENT_BACKLIGHT_EVENT_METADATA_AVAILABLE, metadata);

            synchronized (mCallbackRecords) {
                AmbientBacklightCallbackRecord record = mCallbackRecords
                                                .get(halPackageName);
                if (record == null) {
                    Slog.e(TAG, "Callback record not found for ambient backlight metadata");
                    return;
                }

                try {
                    record.mCallback.onAmbientBacklightEvent(event);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Deliver ambient backlight metadata event failed", e);
                }
            }
        }

        @Override
        public void notifyAmbientBacklightEvent(
                android.hardware.tv.mediaquality.AmbientBacklightEvent halEvent) {
            synchronized (mLock) {
                if (halEvent.getTag() == android.hardware.tv.mediaquality
                                .AmbientBacklightEvent.Tag.enabled) {
                    boolean enabled = halEvent.getEnabled();
                    if (enabled) {
                        handleAmbientBacklightEnabled(true);
                    } else {
                        handleAmbientBacklightEnabled(false);
                    }
                } else if (halEvent.getTag() == android.hardware.tv.mediaquality
                                    .AmbientBacklightEvent.Tag.metadata) {
                    handleAmbientBacklightMetadataEvent(halEvent.getMetadata());
                } else {
                    Slog.e(TAG, "Invalid event type in ambient backlight event");
                }
            }
        }

        @Override
        public synchronized String getInterfaceHash() throws android.os.RemoteException {
            return android.hardware.tv.mediaquality.IMediaQualityCallback.Stub.HASH;
        }

        @Override
        public int getInterfaceVersion() throws android.os.RemoteException {
            return android.hardware.tv.mediaquality.IMediaQualityCallback.Stub.VERSION;
        }
    }

    private void setVendorPictureParameters(
            PictureParameters pictureParameters,
            Parcel parcel,
            PersistableBundle vendorPictureParameters) {
        vendorPictureParameters.writeToParcel(parcel, 0);
        byte[] vendorBundleToByteArray = parcel.marshall();
        DefaultExtension defaultExtension = new DefaultExtension();
        defaultExtension.bytes = Arrays.copyOf(
                vendorBundleToByteArray, vendorBundleToByteArray.length);
        pictureParameters.vendorPictureParameters.setParcelable(defaultExtension);
    }

    private void setVendorSoundParameters(
            SoundParameters soundParameters,
            Parcel parcel,
            PersistableBundle vendorSoundParameters) {
        vendorSoundParameters.writeToParcel(parcel, 0);
        byte[] vendorBundleToByteArray = parcel.marshall();
        DefaultExtension defaultExtension = new DefaultExtension();
        defaultExtension.bytes = Arrays.copyOf(
                vendorBundleToByteArray, vendorBundleToByteArray.length);
        soundParameters.vendorSoundParameters.setParcelable(defaultExtension);
    }

    private boolean isPackageDefaultPictureProfile(PictureProfile pp) {
        if (pp == null || pp.getProfileType() != PictureProfile.TYPE_SYSTEM
                || pp.getInputId() != null) {
            return false;
        }
        String pictureProfileName = pp.getName();
        String[] arr = mPictureProfileAdjListener.splitNameAndStatus(pictureProfileName);
        String profileName = arr[0];
        String profileStatus = arr[1];
        return profileName.equals(PictureProfile.NAME_DEFAULT)
                && (MediaQualityUtils.isValidStreamStatus(profileStatus)
                || profileStatus.isEmpty());
    }

    private boolean hasGlobalPictureQualityServicePermission(int uid, int pid) {
        return mContext.checkPermission(
                android.Manifest.permission.MANAGE_GLOBAL_PICTURE_QUALITY_SERVICE, pid,
                uid)
                == PackageManager.PERMISSION_GRANTED || uid == Process.SYSTEM_UID;
    }

    private boolean hasGlobalSoundQualityServicePermission(int uid, int pid) {
        return mContext.checkPermission(
                       android.Manifest.permission.MANAGE_GLOBAL_SOUND_QUALITY_SERVICE, pid, uid)
                == PackageManager.PERMISSION_GRANTED || uid == Process.SYSTEM_UID;
    }

    private PictureProfile getSdrPictureProfile(String profileName, PictureProfile previous) {
        Log.d(TAG, "getSdrPictureProfile: profileName = " + profileName
                + " previous profile name = " + previous.getName());
        String selection = BaseParameters.PARAMETER_TYPE + " = ? AND "
                + BaseParameters.PARAMETER_PACKAGE + " = ? AND ("
                + BaseParameters.PARAMETER_NAME + " = ? OR "
                + BaseParameters.PARAMETER_NAME + " = ?)";
        List<String> selectionArguments = new ArrayList<>();
        selectionArguments.add(Integer.toString(previous.getProfileType()));
        selectionArguments.add(previous.getPackageName());
        selectionArguments.add(profileName);
        selectionArguments.add(profileName + "/" + PictureProfile.STATUS_SDR);
        if (previous.getInputId() != null) {
            Log.d(TAG, "getSdrPictureProfile: "
                    + "The input is not null for previous picture profile");
            selection += " AND " + BaseParameters.PARAMETER_INPUT_ID + " = ?";
            selectionArguments.add(previous.getInputId());
        }
        Log.d(TAG, "getSdrPictureProfile: "
                + "The selection is " + selection
                + " The selection argument is " + selectionArguments);
        List<PictureProfile> list =
                mMqDatabaseUtils.getPictureProfilesBasedOnConditions(
                        MediaQualityUtils.getMediaProfileColumns(true),
                        selection,
                        selectionArguments.toArray(new String[0]));
        if (list.isEmpty()) {
            return null;
        }
        return list.getFirst();
    }

    private void putCurrentPictureProfile(Long originalHandle, Long currentHandle,
            PictureProfile currentPictureProfile) {
        mOriginalHandleToCurrentPictureProfile.put(originalHandle, currentPictureProfile);
        mCurrentPictureHandleToOriginal.removeValue(originalHandle);
        mCurrentPictureHandleToOriginal.put(currentHandle, originalHandle);
        if (DEBUG) {
            Collection<Long> values = mCurrentPictureHandleToOriginal.getValues();
            for (Long value: values) {
                Slog.d(TAG, "putCurrentPictureProfile: key: "
                        + mCurrentPictureHandleToOriginal.getKey(value) + " value: " + value);
            }
        }
    }

    private void putParamCapDefaultValueIntoBundle(
            Bundle bundle, ParameterDefaultValue defaultValue) {
        if (defaultValue == null) {
            return;
        }

        switch (defaultValue.getTag()) {
            case ParameterDefaultValue.intDefault:
                bundle.putInt(
                        ParameterCapability.CAPABILITY_DEFAULT, defaultValue.getIntDefault());
                break;
            case ParameterDefaultValue.longDefault:
                bundle.putLong(
                        ParameterCapability.CAPABILITY_DEFAULT, defaultValue.getLongDefault());
                break;
            case ParameterDefaultValue.doubleDefault:
                bundle.putDouble(
                        ParameterCapability.CAPABILITY_DEFAULT, defaultValue.getDoubleDefault());
                break;
            case ParameterDefaultValue.stringDefault:
                bundle.putString(
                        ParameterCapability.CAPABILITY_DEFAULT, defaultValue.getStringDefault());
                break;
            default:
                Log.d(TAG, "putParamCapDefaultValueIntoBundle: "
                        + "default value type is not supported for tag " + defaultValue.getTag());
        }
    }
}
