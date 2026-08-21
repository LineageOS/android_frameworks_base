/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.servicewatcher;

import static android.content.pm.PackageManager.GET_META_DATA;
import static android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AUTO;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.location.provider.GeocodeProviderBase.ACTION_GEOCODE_PROVIDER;
import static android.location.provider.LocationProviderBase.ACTION_FUSED_PROVIDER;
import static android.location.provider.LocationProviderBase.ACTION_NETWORK_PROVIDER;
import static android.os.UserHandle.USER_SYSTEM;

import android.annotation.BoolRes;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.app.ActivityManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.content.res.Resources;
import android.location.flags.Flags;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;

import com.android.internal.util.Preconditions;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.servicewatcher.ServiceWatcher.ServiceChangedListener;
import com.android.server.servicewatcher.ServiceWatcher.ServiceSupplier;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Supplies services based on the current active user and version as defined in the service
 * manifest. This implementation uses {@link android.content.pm.PackageManager#MATCH_SYSTEM_ONLY} to
 * ensure only system (ie, privileged) services are matched. It also handles services that are not
 * direct boot aware, and will automatically pick the best service as the user's direct boot state
 * changes.
 *
 * <p>Optionally, two permissions may be specified: (1) a caller permission - any service that does
 * not require callers to hold this permission is rejected (2) a service permission - any service
 * whose package does not hold this permission is rejected.
 *
 * @hide
 */
public final class CurrentUserServiceSupplier extends BroadcastReceiver implements
        ServiceSupplier<CurrentUserServiceSupplier.BoundServiceInfo> {

    private static final String TAG = "CurrentUserServiceSupplier";

    private static final String EXTRA_SERVICE_VERSION = "serviceVersion";
    private static final String EXTRA_SERVICE_IS_MULTIUSER = "serviceIsMultiuser";

    // a package value that will never match against any package (we can't use null since this will
    // match against any package).
    private static final String NO_MATCH_PACKAGE = "";

    private static final Comparator<BoundServiceInfo> sBoundServiceInfoComparator = (o1, o2) -> {
        if (o1 == o2) {
            return 0;
        } else if (o1 == null) {
            return -1;
        } else if (o2 == null) {
            return 1;
        }

        // ServiceInfos with higher version numbers always win. if version numbers are equal
        // then we prefer components that work for all users vs components that only work for a
        // single user at a time. otherwise everything's equal.
        int ret = Integer.compare(o1.getVersion(), o2.getVersion());
        if (ret == 0) {
            if (o1.getUserId() != USER_SYSTEM && o2.getUserId() == USER_SYSTEM) {
                ret = -1;
            } else if (o1.getUserId() == USER_SYSTEM && o2.getUserId() != USER_SYSTEM) {
                ret = 1;
            }
        }
        return ret;
    };

    /** Bound service information with version information. */
    public static class BoundServiceInfo extends ServiceWatcher.BoundServiceInfo {

        private static int parseUid(ResolveInfo resolveInfo) {
            int uid = resolveInfo.serviceInfo.getUid();
            Bundle metadata = resolveInfo.serviceInfo.metaData;
            if (metadata != null && metadata.getBoolean(EXTRA_SERVICE_IS_MULTIUSER, false)) {
                // reconstruct a uid for the same app but with the system user - hope this exists
                uid = UserHandle.getUid(USER_SYSTEM, UserHandle.getAppId(uid));
            }
            return uid;
        }

        private static int parseVersion(ResolveInfo resolveInfo) {
            int version = Integer.MIN_VALUE;
            if (resolveInfo.serviceInfo.metaData != null) {
                version = resolveInfo.serviceInfo.metaData.getInt(EXTRA_SERVICE_VERSION, version);
            }
            return version;
        }

        private final int mVersion;
        private final @Nullable Bundle mMetadata;

        protected BoundServiceInfo(String action, ResolveInfo resolveInfo) {
            this(action, parseUid(resolveInfo), resolveInfo.serviceInfo.getComponentName(),
                    parseVersion(resolveInfo), resolveInfo.serviceInfo.metaData);
        }

        protected BoundServiceInfo(String action, int uid, ComponentName componentName, int version,
                @Nullable Bundle metadata) {
            super(action, uid, componentName);

            mVersion = version;
            mMetadata = metadata;
        }

        public int getVersion() {
            return mVersion;
        }

        public @Nullable Bundle getMetadata() {
            return mMetadata;
        }

        @Override
        public String toString() {
            return super.toString() + "@" + mVersion;
        }
    }

    /**
     * Creates an instance using package details retrieved from config.
     *
     * @see #create(Context, String, String, String, String)
     */
    public static CurrentUserServiceSupplier createFromConfig(Context context, String action,
            @BoolRes int enableOverlayResId, @StringRes int nonOverlayPackageResId) {
        String explicitPackage = retrieveExplicitPackage(context, enableOverlayResId,
                nonOverlayPackageResId);
        return CurrentUserServiceSupplier.create(context, action, explicitPackage,
                /*callerPermission=*/null, /*servicePermission=*/null);
    }

    /**
     * Creates an instance with the specific service details and permission requirements.
     *
     * @param context the context the supplier is to use
     * @param action the action the service must declare in its intent-filter
     * @param explicitPackage the package of the service, or {@code null} if the package of the
     *     service is not constrained
     * @param callerPermission a permission that the service forces callers (i.e.
     *     ServiceWatcher/system server) to hold, or {@code null} if there isn't one
     * @param servicePermission a permission that the service package should hold, or {@code null}
     *     if there isn't one
     */
    public static CurrentUserServiceSupplier create(Context context, String action,
            @Nullable String explicitPackage, @Nullable String callerPermission,
            @Nullable String servicePermission) {
        boolean matchSystemAppsOnly = true;
        return new CurrentUserServiceSupplier(context, action,
                explicitPackage, callerPermission, servicePermission, matchSystemAppsOnly);
    }

    /**
     * Creates an instance like {@link #create} except it allows connection to services that are not
     * supplied by system packages. Only intended for use during tests.
     *
     * @see #create(Context, String, String, String, String)
     */
    public static CurrentUserServiceSupplier createUnsafeForTestsOnly(Context context,
            String action, @Nullable String explicitPackage, @Nullable String callerPermission,
            @Nullable String servicePermission) {
        boolean matchSystemAppsOnly = false;
        return new CurrentUserServiceSupplier(context, action,
                explicitPackage, callerPermission, servicePermission, matchSystemAppsOnly);
    }

    private static @Nullable String retrieveExplicitPackage(Context context,
            @BoolRes int enableOverlayResId, @StringRes int nonOverlayPackageResId) {
        Resources resources = context.getResources();
        boolean enableOverlay = resources.getBoolean(enableOverlayResId);
        if (!enableOverlay) {
            if (Flags.fixServiceWatcher()) {
                // we don't use getText() or similar because it won't return null values
                TypedValue out = new TypedValue();
                resources.getValue(nonOverlayPackageResId, out, true);
                CharSequence explicitPackage = out.coerceToString();
                if (explicitPackage == null) {
                    return NO_MATCH_PACKAGE;
                } else {
                    return explicitPackage.toString();
                }
            } else {
                return resources.getString(nonOverlayPackageResId);
            }
        } else {
            return null;
        }
    }

    private final Context mContext;
    private final ActivityManagerInternal mActivityManager;
    private final Intent mIntent;
    // a permission that the service forces callers (ie ServiceWatcher/system server) to hold
    private final @Nullable String mCallerPermission;
    // a permission that the service package should hold
    private final @Nullable String mServicePermission;
    // whether to use MATCH_SYSTEM_ONLY in queries
    private final boolean mMatchSystemAppsOnly;

    private volatile ServiceChangedListener mListener;
    private @Nullable String mUnstableService;

    private CurrentUserServiceSupplier(Context context, String action,
            @Nullable String explicitPackage, @Nullable String callerPermission,
            @Nullable String servicePermission, boolean matchSystemAppsOnly) {
        mContext = context;
        mActivityManager = Objects.requireNonNull(
                LocalServices.getService(ActivityManagerInternal.class));
        mIntent = new Intent(action);

        if (explicitPackage != null) {
            mIntent.setPackage(explicitPackage);
        }

        mCallerPermission = callerPermission;
        mServicePermission = servicePermission;
        mMatchSystemAppsOnly = matchSystemAppsOnly;
    }

    private static final Signature MICROG_FAKE_SIGNATURE = new Signature("308204433082032ba003020102020900c2e08746644a308d300d06092a864886f70d01010405003074310b3009060355040613025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e205669657731143012060355040a130b476f6f676c6520496e632e3110300e060355040b1307416e64726f69643110300e06035504031307416e64726f6964301e170d3038303832313233313333345a170d3336303130373233313333345a3074310b3009060355040613025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e205669657731143012060355040a130b476f6f676c6520496e632e3110300e060355040b1307416e64726f69643110300e06035504031307416e64726f696430820120300d06092a864886f70d01010105000382010d00308201080282010100ab562e00d83ba208ae0a966f124e29da11f2ab56d08f58e2cca91303e9b754d372f640a71b1dcb130967624e4656a7776a92193db2e5bfb724a91e77188b0e6a47a43b33d9609b77183145ccdf7b2e586674c9e1565b1f4c6a5955bff251a63dabf9c55c27222252e875e4f8154a645f897168c0b1bfc612eabf785769bb34aa7984dc7e2ea2764cae8307d8c17154d7ee5f64a51a44a602c249054157dc02cd5f5c0e55fbef8519fbe327f0b1511692c5a06f19d18385f5c4dbc2d6b93f68cc2979c70e18ab93866b3bd5db8999552a0e3b4c99df58fb918bedc182ba35e003c1b4b10dd244a8ee24fffd333872ab5221985edab0fc0d0b145b6aa192858e79020103a381d93081d6301d0603551d0e04160414c77d8cc2211756259a7fd382df6be398e4d786a53081a60603551d2304819e30819b8014c77d8cc2211756259a7fd382df6be398e4d786a5a178a4763074310b3009060355040613025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e205669657731143012060355040a130b476f6f676c6520496e632e3110300e060355040b1307416e64726f69643110300e06035504031307416e64726f6964820900c2e08746644a308d300c0603551d13040530030101ff300d06092a864886f70d010104050003820101006dd252ceef85302c360aaace939bcff2cca904bb5d7a1661f8ae46b2994204d0ff4a68c7ed1a531ec4595a623ce60763b167297a7ae35712c407f208f0cb109429124d7b106219c084ca3eb3f9ad5fb871ef92269a8be28bf16d44c8d9a08e6cb2f005bb3fe2cb96447e868e731076ad45b33f6009ea19c161e62641aa99271dfd5228c5c587875ddb7f452758d661f6cc0cccb7352e424cc4365c523532f7325137593c4ae341f4db41edda0d0b1071a7c440f0fe9ea01cb627ca674369d084bd2fd911ff06cdbf2cfa10dc0f893ae35762919048c7efc64c7144178342f70581c9de573af55b390dd7fdb9418631895d5f759f30112687ff621410c069308a");

    private List<ResolveInfo> queryIntentServicesAsUser(Intent intent, int flags, int userId) {
        boolean isLocationAction = ACTION_FUSED_PROVIDER.equals(intent.getAction())
                || ACTION_GEOCODE_PROVIDER.equals(intent.getAction())
                || ACTION_NETWORK_PROVIDER.equals(intent.getAction());
        boolean systemOnly = (flags & MATCH_SYSTEM_ONLY) != 0;

        if (isLocationAction) {
            flags &= ~MATCH_SYSTEM_ONLY;
        }

        List<ResolveInfo> resolveInfos = mContext.getPackageManager()
                .queryIntentServicesAsUser(intent,
                        flags,
                        userId);

        if (isLocationAction) {
            resolveInfos.removeIf(resolveInfo -> {
                ServiceInfo serviceInfo = resolveInfo.serviceInfo;
                if (!systemOnly || serviceInfo.applicationInfo.isSystemApp()) {
                    return false;
                }
                if (!serviceInfo.packageName.equals("com.google.android.gms")) {
                    return true;
                }
                try {
                    PackageInfo packageInfo = mContext.getPackageManager().getPackageInfoAsUser(
                            serviceInfo.packageName,
                            GET_META_DATA | GET_SIGNING_CERTIFICATES,
                            userId);
                    if (packageInfo == null || packageInfo.signingInfo == null) {
                        return true;
                    }
                    Bundle metadata = packageInfo.applicationInfo.metaData;
                    String fakeSignatureStr = metadata.getString("fake-signature");
                    if (TextUtils.isEmpty(fakeSignatureStr)) {
                        return true;
                    }
                    Signature fakeSignature = new Signature(fakeSignatureStr);
                    if (!fakeSignature.equals(MICROG_FAKE_SIGNATURE)) {
                        return true;
                    }
                    return !Signature.areExactMatch(
                            packageInfo.signingInfo.getSigningDetails(),
                            new Signature[]{MICROG_FAKE_SIGNATURE});
                } catch (NameNotFoundException e) {
                    return true;
                }
            });
        }

        return resolveInfos;
    }

    @Override
    public boolean hasMatchingService() {
        if (Flags.fixServiceWatcher() && NO_MATCH_PACKAGE.equals(mIntent.getPackage())) {
            return false;
        }

        int intentQueryFlags = MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE;
        if (mMatchSystemAppsOnly) {
            intentQueryFlags |= MATCH_SYSTEM_ONLY;
        }
        List<ResolveInfo> resolveInfos =
                queryIntentServicesAsUser(mIntent,
                        intentQueryFlags,
                        UserHandle.USER_SYSTEM);
        return !resolveInfos.isEmpty();
    }

    @Override
    public void register(ServiceChangedListener listener) {
        Preconditions.checkState(mListener == null);

        mListener = listener;

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_SWITCHED);
        intentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
        intentFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mContext.registerReceiverAsUser(this, UserHandle.ALL, intentFilter, null,
                FgThread.getHandler());
    }

    @Override
    public void unregister() {
        Preconditions.checkArgument(mListener != null);

        mListener = null;
        mContext.unregisterReceiver(this);
    }

    @Override
    public BoundServiceInfo getServiceInfo() {
        if (Flags.fixServiceWatcher() && NO_MATCH_PACKAGE.equals(mIntent.getPackage())) {
            return null;
        }

        BoundServiceInfo bestServiceInfo = null;

        // only allow services in the correct direct boot state to match
        int intentQueryFlags = MATCH_DIRECT_BOOT_AUTO | GET_META_DATA;
        if (mMatchSystemAppsOnly) {
            intentQueryFlags |= MATCH_SYSTEM_ONLY;
        }
        int currentUserId = mActivityManager.getCurrentUserId();
        List<ResolveInfo> resolveInfos = queryIntentServicesAsUser(
                mIntent,
                intentQueryFlags,
                currentUserId);
        for (ResolveInfo resolveInfo : resolveInfos) {
            ServiceInfo service = Objects.requireNonNull(resolveInfo.serviceInfo);

            if (mCallerPermission != null) {
                if (!mCallerPermission.equals(service.permission)) {
                    Log.d(TAG, service.getComponentName().flattenToShortString()
                            + " disqualified due to not requiring " + mCallerPermission);
                    continue;
                }
            }

            BoundServiceInfo serviceInfo = new BoundServiceInfo(mIntent.getAction(), resolveInfo);

            if (mServicePermission != null) {
                if (mContext.checkPermission(mServicePermission, Process.INVALID_PID,
                        serviceInfo.mUid) != PERMISSION_GRANTED) {
                    Log.d(TAG, serviceInfo.getComponentName().flattenToShortString()
                            + " disqualified due to not holding " + mCallerPermission);
                    continue;
                }
            }

            // Prefer any service over the unstable service.
            if (mUnstableService != null && serviceInfo != null && bestServiceInfo != null) {
              if (mUnstableService.equals(serviceInfo.toString())) {
                  Log.d(TAG, "Not choosing unstable service " + mUnstableService
                          + " as we already have a service " + bestServiceInfo.toString());
                  continue;
              } else if (mUnstableService.equals(bestServiceInfo.toString())) {
                  Log.d(TAG, "Choosing service " + serviceInfo.toString()
                          + " over the unstable service " + mUnstableService);
                  bestServiceInfo = serviceInfo;
                  continue;
              }
            }

            if (sBoundServiceInfoComparator.compare(serviceInfo, bestServiceInfo) > 0) {
                bestServiceInfo = serviceInfo;
            }
        }

        return bestServiceInfo;
    }

    /**
     * Alerts the supplier that the given service is unstable.
     *
     * The service marked as unstable will be unpreferred over any other services,
     * which will last until the next device restart.
     */
    @Override
    public void alertUnstableService(String unstableService) {
        mUnstableService = unstableService;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) {
            return;
        }
        int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
        if (userId == UserHandle.USER_NULL) {
            return;
        }
        ServiceChangedListener listener = mListener;
        if (listener == null) {
            return;
        }

        switch (action) {
            case Intent.ACTION_USER_SWITCHED:
                listener.onServiceChanged();
                break;
            case Intent.ACTION_USER_UNLOCKED:
                // user unlocked implies direct boot mode may have changed
                if (userId == mActivityManager.getCurrentUserId()) {
                    listener.onServiceChanged();
                }
                break;
            default:
                break;
        }
    }
}
