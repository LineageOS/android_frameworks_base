/*
 * Copyright (C) 2026 The Android Open Source Project
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

package android.telephony;

import static android.os.Process.BLUETOOTH_UID;
import static android.service.messaging.AlternativeMessageTransportService.UPGRADE_STATUS_REJECTED;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Binder;
import android.provider.Telephony;
import android.service.messaging.AlternativeMessageTransportService;
import android.service.messaging.AlternativeMessageTransportServiceWrapper;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.SmsApplication;
import com.android.internal.telephony.TelephonyStatsLog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

/**
 * A worker class to place a message upgrade request with the default SMS app for user. This class
 * is also responsible for caching the default SMS data i.e. package and message upgrade support
 * status.
 * @hide
 */
// TODO(b/474304887): Make this class thread-safe
public class MessageUpgradeWorker {
    private static final String TAG = MessageUpgradeWorker.class.getSimpleName();
    private static final String AUTHORITY_SMS = "sms";
    private static final String AUTHORITY_MMS = "mms";
    private static final int MAX_PENDING_INTENTS = 50;
    private static final boolean IS_DEBUG = false;
    private final ScheduledExecutorService mScheduler =
            Executors.newSingleThreadScheduledExecutor();
    private final Object mMessageUpgradeLock = new Object();
    private final Context mContext;
    private final BroadcastReceiver mDefaultSmsAppChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (SmsApplication.ACTION_DEFAULT_SMS_PACKAGE_CHANGED_INTERNAL.equals(
                    intent.getAction())) {
                mScheduler.execute(() -> {
                    updateCachedDefaultSmsPackageData();
                });
            }
        }
    };

    private final PendingIntentCache mSmsPendingSentIntents =
            new PendingIntentCache(MAX_PENDING_INTENTS);
    private final PendingIntentCache mSmsPendingDeliveryIntents =
            new PendingIntentCache(MAX_PENDING_INTENTS);
    private final PendingIntentCache mMmsPendingSentIntents =
            new PendingIntentCache(MAX_PENDING_INTENTS);

    private final AlternativeMessageTransportServiceWrapper mServiceWrapper;

    @GuardedBy("mMessageUpgradeLock")
    private String mCachedDefaultSmsPackage;
    @GuardedBy("mMessageUpgradeLock")
    private boolean mCachedIsUpgradeSupported = false;

    /** @hide */
    public MessageUpgradeWorker(@NonNull Context context) {
        mContext = Objects.requireNonNull(context);
        mServiceWrapper = new AlternativeMessageTransportServiceWrapper(context, mScheduler);
        updateCachedDefaultSmsPackageData();
        registerOnSmsAppChangedReceiver(context);
    }

    /**
     * Upgrades a SMS/MMS message.
     *
     * @param messageUri The uri of the message in the telephony db.
     * @param sentIntents A list of {@link PendingIntent}s to broadcast when the message is
     * successfully sent or failed. For multipart messages, these intents
     * are all broadcast at once after the last part is sent or failed.
     * @param deliveryIntents A list of {@link PendingIntent}s to broadcast when the message is
     * delivered to the recipient. For multipart messages, these intents
     * are all broadcast at once after the last part is delivered or failed.
     * @param clientCallbackExecutor The executor to run the callback on.
     * @param clientCallback The callback to report the upgrade status.
     */
    @VisibleForTesting
    public void upgradeMessage(
            @NonNull Uri messageUri,
            @Nullable List<PendingIntent> sentIntents,
            @Nullable List<PendingIntent> deliveryIntents,
            @NonNull Executor clientCallbackExecutor,
            @NonNull Consumer<Integer> clientCallback) {
        Objects.requireNonNull(messageUri, "messageUri cannot be null");
        Objects.requireNonNull(clientCallbackExecutor, "clientCallbackExecutor cannot be null");
        Objects.requireNonNull(clientCallback, "clientCallback cannot be null");

        if (!isMessageUpgradeSupported()) {
            rejectUpgradeRequest(clientCallbackExecutor, clientCallback);
            return;
        }

        String smsAppPackage = getCachedDefaultSmsAppPackage();
        if (TextUtils.isEmpty(smsAppPackage)) {
            Log.e(TAG, "No default SMS app found for user");
            rejectUpgradeRequest(clientCallbackExecutor, clientCallback);
            return;
        }

        long messageId = parseMessageId(messageUri);
        MessageType messageType = parseMessageType(messageUri);

        if (messageId == -1 || messageType == MessageType.UNKNOWN) {
            Log.e(TAG, "Invalid message URI: " + messageUri);
            rejectUpgradeRequest(clientCallbackExecutor, clientCallback);
            return;
        }

        storePendingIntents(messageType, messageId, sentIntents, deliveryIntents);

        long upgradeStartTimeMs = android.os.SystemClock.elapsedRealtime();

        // Discard pending intents upon upgrade rejection
        Consumer<Integer> clientCallbackWrapper = status -> {
            int latencyMs = (int) (android.os.SystemClock.elapsedRealtime()
                    - upgradeStartTimeMs);
            boolean hasMultipleSentIntents = sentIntents != null && sentIntents.size() > 1;
            if (status == UPGRADE_STATUS_REJECTED) {
                logMessageUpgradeResolved(TelephonyStatsLog
                        .MESSAGE_UPGRADE_RESOLVED__STATUS__STATUS_FAILED,
                        messageType, latencyMs, hasMultipleSentIntents);
                discardAllPendingIntents(messageType, messageId);
            } else {
                logMessageUpgradeResolved(TelephonyStatsLog
                                .MESSAGE_UPGRADE_RESOLVED__STATUS__STATUS_SUCCEEDED,
                        messageType, latencyMs, hasMultipleSentIntents);
            }
            clientCallback.accept(status);
        };

        mServiceWrapper.upgradeMessage(
                messageUri, smsAppPackage, clientCallbackExecutor, clientCallbackWrapper);
    }

    /**
     * Helper to log the MESSAGE_UPGRADE_RESOLVED atom.
     */
    private void logMessageUpgradeResolved(
            int resolveStatus, MessageType messageType, int latencyMs, boolean isMultipart) {

        int sourceType = Binder.getCallingUid() == BLUETOOTH_UID
                ? TelephonyStatsLog
                .MESSAGE_UPGRADE_RESOLVED__SOURCE_TYPE__SOURCE_TYPE_BLUETOOTH
                : TelephonyStatsLog
                        .MESSAGE_UPGRADE_RESOLVED__SOURCE_TYPE__SOURCE_TYPE_THIRD_PARTY_APP;

        int originalMessageType = switch (messageType) {
            case SMS -> isMultipart
                    ? TelephonyStatsLog
                        .MESSAGE_UPGRADE_RESOLVED__ORIGINAL_MESSAGE_TYPE__ORIGINAL_MESSAGE_TYPE_SMS_MULTIPART
                    : TelephonyStatsLog
                        .MESSAGE_UPGRADE_RESOLVED__ORIGINAL_MESSAGE_TYPE__ORIGINAL_MESSAGE_TYPE_SMS;
            case MMS -> TelephonyStatsLog
                    .MESSAGE_UPGRADE_RESOLVED__ORIGINAL_MESSAGE_TYPE__ORIGINAL_MESSAGE_TYPE_MMS;
            case UNKNOWN -> TelephonyStatsLog
                    .MESSAGE_UPGRADE_RESOLVED__ORIGINAL_MESSAGE_TYPE__ORIGINAL_MESSAGE_TYPE_UNKNOWN;
        };

        int dmaUid = 0;
        if (mCachedDefaultSmsPackage != null) {
            try {
                int currentUserId = mContext.getUserId();
                dmaUid = mContext.getPackageManager().getPackageUid(
                        mCachedDefaultSmsPackage, currentUserId);
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "DMA package not found for UID mapping.");
            }
        }

        TelephonyStatsLog.write(
                TelephonyStatsLog.MESSAGE_UPGRADE_RESOLVED,
                sourceType,
                originalMessageType,
                resolveStatus,
                latencyMs,
                dmaUid);
    }

    /**
     * Checks if the message upgrade feature is supported for a specific calling package.
     *
     * <p>A message upgrade is supported if the default SMS app has implemented the
     * {@link android.service.messaging.AlternativeMessageTransportService} and the
     * requesting package is not the default SMS app itself (to prevent self-upgrading).
     *
     * @param callingPkg The package name of the app requesting to send a message.
     * @param shouldLog Whether to log the event in case of upgrade not supported.
     * @return {@code true} if the upgrade service is available and the calling package
     *         is eligible for upgrade.
     */
    @VisibleForTesting
    public boolean isMessageUpgradeSupportedForPackage(String callingPkg, boolean shouldLog) {
        if (TextUtils.isEmpty(callingPkg)) {
            Log.e(TAG, "callingPkg is null or empty.");
            return false;
        }

        boolean isSupported;
        synchronized (mMessageUpgradeLock) {
            isSupported = mCachedIsUpgradeSupported && !callingPkg.equals(mCachedDefaultSmsPackage);
        }

        if (shouldLog && !isSupported) {
            logMessageUpgradeResolved(
                    TelephonyStatsLog.MESSAGE_UPGRADE_RESOLVED__STATUS__STATUS_SUCCEEDED,
                    MessageType.UNKNOWN, 0, false);
        }
        return isSupported;
    }

    /**
     * Checks if the default SMS app has implemented the
     * {@link android.service.messaging.AlternativeMessageTransportService}.
     *
     * @return {@code true} if the default SMS app has a AlternativeMessageTransportService.
     */
    private boolean isMessageUpgradeSupported() {
        synchronized (mMessageUpgradeLock) {
            return mCachedIsUpgradeSupported;
        }
    }

    /**
     * Clean up resources held by this worker instance. This should be called when the
     * corresponding user or profile is removed.
     */
    @VisibleForTesting
    public void close() {
        Log.i(TAG, "Closing MessageUpgradeWorker for user " + mContext.getUserId());
        mContext.unregisterReceiver(mDefaultSmsAppChangedReceiver);
        mServiceWrapper.close();
        mScheduler.shutdown();
    }

    @Nullable
    private String getCachedDefaultSmsAppPackage() {
        synchronized (mMessageUpgradeLock) {
            return mCachedDefaultSmsPackage;
        }
    }

    private void registerOnSmsAppChangedReceiver(Context context) {
        IntentFilter smsAppChangedFilter = new IntentFilter(
                SmsApplication.ACTION_DEFAULT_SMS_PACKAGE_CHANGED_INTERNAL);
        context.registerReceiver(mDefaultSmsAppChangedReceiver, smsAppChangedFilter);
    }

    private void updateCachedDefaultSmsPackageData() {
        String cachedSmsPackage = getCachedDefaultSmsAppPackage();
        String currentSmsPackage = null;
        ComponentName component = SmsApplication.getDefaultSmsApplicationAsUser(
                mContext, false, mContext.getUser());
        if (component != null) {
            currentSmsPackage = component.getPackageName();
        }

        if (Objects.equals(cachedSmsPackage, currentSmsPackage)) {
            return;
        }

        Log.d(TAG, "SMS app changed, updating cache. current sms app:" + currentSmsPackage);
        boolean isSupported = false;
        if (!TextUtils.isEmpty(currentSmsPackage)) {
            Intent intent = new Intent(AlternativeMessageTransportService.SERVICE_INTERFACE);
            intent.setPackage(currentSmsPackage);

            List<ResolveInfo> services = mContext.getPackageManager().queryIntentServices(
                    intent, PackageManager.GET_META_DATA);

            for (ResolveInfo info : services) {
                if (info.serviceInfo != null
                        && Manifest.permission.BIND_ALTERNATIVE_MESSAGE_TRANSPORT_SERVICE.equals(
                        info.serviceInfo.permission)) {
                    isSupported = true;
                    break;
                }
            }
        } else {
            Log.e(TAG, "No default sms app found for user " + mContext.getUserId());
        }

        synchronized (mMessageUpgradeLock) {
            mCachedDefaultSmsPackage = currentSmsPackage;
            mCachedIsUpgradeSupported = isSupported;
            Log.i(TAG, "Updated cached data for user " + mContext.getUserId() + ": package="
                    + currentSmsPackage + ", supported=" + isSupported);
        }
    }

    private void rejectUpgradeRequest(
            Executor callbackExecutor, Consumer<Integer> callback) {
        callbackExecutor.execute(() -> callback.accept(UPGRADE_STATUS_REJECTED));
    }

    /**
     * Called when an SMS message is updated in the Telephony provider.
     *
     * <p>Checks if the updated SMS corresponds to an upgraded message and dispatches the associated
     * PendingIntents (sent/delivery) if the status or type has changed.
     *
     * @param messageUri The URI of the updated SMS message.
     * @param values     The updated values of the SMS message.
     */
    @VisibleForTesting
    public void dispatchSmsPendingIntentsIfUpgraded(Uri messageUri, ContentValues values) {
        if (!isMessageUpgradeSupported()) return;

        long messageId = parseMessageId(messageUri);
        if (messageId == -1) return;

        List<PendingIntent> sentIntents = getUpgradedMessagePendingIntents(
                mSmsPendingSentIntents, messageId);
        List<PendingIntent> deliveryIntents = getUpgradedMessagePendingIntents(
                mSmsPendingDeliveryIntents, messageId);

        if (IS_DEBUG && (sentIntents != null || deliveryIntents != null)) {
            Log.d(TAG, "dispatchSmsPendingIntentsIfUpgraded: processing URI: "
                    + messageUri
                    + " with values: "
                    + values);
        }

        if (sentIntents != null) {
            Integer typeObj = values.getAsInteger(Telephony.Sms.TYPE);
            if (typeObj != null) {
                int type = typeObj;
                if (type == Telephony.Sms.MESSAGE_TYPE_SENT) {
                    dispatchSentIntents(mContext, sentIntents, Activity.RESULT_OK, 0);
                    discardUpgradedMessagePendingIntents(mSmsPendingSentIntents,
                            messageId, /* isSuccessfullyDispatched= */ true);
                } else if (type == Telephony.Sms.MESSAGE_TYPE_FAILED) {
                    Integer errorCodeObj = values.getAsInteger(Telephony.Sms.ERROR_CODE);
                    int errorCode = (errorCodeObj != null) ? errorCodeObj : 0;
                    dispatchSentIntents(mContext, sentIntents, Activity.RESULT_CANCELED, errorCode);

                    // A local send failure means no delivery report will arrive.
                    discardAllPendingIntents(MessageType.SMS, messageId);
                    return;
                }
            }
        }

        if (deliveryIntents != null) {
            Integer statusObj = values.getAsInteger(Telephony.Sms.STATUS);
            if (statusObj != null) {
                int status = statusObj;
                if (status == Telephony.Sms.STATUS_COMPLETE) {
                    dispatchDeliveryIntents(deliveryIntents, Activity.RESULT_OK);
                    discardUpgradedMessagePendingIntents(mSmsPendingDeliveryIntents,
                            messageId, /* isSuccessfullyDispatched= */ true);
                } else if (status == Telephony.Sms.STATUS_FAILED) {
                    dispatchDeliveryIntents(deliveryIntents, Activity.RESULT_CANCELED);
                    discardUpgradedMessagePendingIntents(mSmsPendingDeliveryIntents,
                            messageId, /* isSuccessfullyDispatched= */ true);
                }
            }
        }
    }

    /**
     * Called when an MMS message is updated in the Telephony provider.
     *
     * <p>Checks if the updated MMS corresponds to an upgraded message and dispatches the associated
     * PendingIntents (sent) if the message box has changed.
     *
     * @param messageUri The URI of the updated MMS message.
     * @param values     The updated values of the MMS message.
     */
    @VisibleForTesting
    public void dispatchMmsPendingIntentsIfUpgraded(Uri messageUri, ContentValues values) {
        if (!isMessageUpgradeSupported()) return;

        long messageId = parseMessageId(messageUri);
        if (messageId == -1) return;

        List<PendingIntent> sentIntents = getUpgradedMessagePendingIntents(
                mMmsPendingSentIntents, messageId);
        if (sentIntents == null) return;

        if (IS_DEBUG) {
            Rlog.d(TAG, "dispatchMmsPendingIntentsIfUpgraded: processing URI: "
                    + messageUri + " with values: " + values);
        }

        Integer msgBoxObj = values.getAsInteger(Telephony.Mms.MESSAGE_BOX);
        if (msgBoxObj != null) {
            int msgBox = msgBoxObj;
            if (msgBox == Telephony.Mms.MESSAGE_BOX_SENT) {
                dispatchSentIntents(mContext, sentIntents, Activity.RESULT_OK, 0);
                discardUpgradedMessagePendingIntents(
                        mMmsPendingSentIntents, messageId, /* isSuccessfullyDispatched= */ true);
            } else if (msgBox == Telephony.Mms.MESSAGE_BOX_FAILED) {
                Integer respStatusObj = values.getAsInteger(Telephony.Mms.RESPONSE_STATUS);
                int errorCode = (respStatusObj != null) ? respStatusObj : 0;
                dispatchSentIntents(mContext, sentIntents, Activity.RESULT_CANCELED, errorCode);
                discardUpgradedMessagePendingIntents(
                        mMmsPendingSentIntents, messageId, /* isSuccessfullyDispatched= */ true);
            }
        }
    }

    private void storePendingIntents(MessageType type, long id, List<PendingIntent> sent,
            List<PendingIntent> delivery) {

        int sourceType = Binder.getCallingUid() == BLUETOOTH_UID
                ? TelephonyStatsLog
                .MESSAGE_UPGRADE_INTENT_CACHED__SOURCE_TYPE__SOURCE_TYPE_BLUETOOTH
                : TelephonyStatsLog
                        .MESSAGE_UPGRADE_INTENT_CACHED__SOURCE_TYPE__SOURCE_TYPE_THIRD_PARTY_APP;
        int messageTypeSms = TelephonyStatsLog
                .MESSAGE_UPGRADE_INTENT_CACHED__UPGRADE_TYPE__ORIGINAL_MESSAGE_TYPE_SMS;
        int messageTypeMms = TelephonyStatsLog
                .MESSAGE_UPGRADE_INTENT_CACHED__UPGRADE_TYPE__ORIGINAL_MESSAGE_TYPE_MMS;
        int intentTypeSent = TelephonyStatsLog
                .MESSAGE_UPGRADE_INTENT_CACHED__INTENT_TYPE__UPGRADE_INTENT_SENT;
        int intentTypeDelivery = TelephonyStatsLog
                .MESSAGE_UPGRADE_INTENT_CACHED__INTENT_TYPE__UPGRADE_INTENT_DELIVERY;
        synchronized (mMessageUpgradeLock) {
            if (sent != null && !sent.isEmpty()) {
                switch (type) {
                    case SMS -> {
                        PendingIntentRecord record = new PendingIntentRecord(sent,
                                messageTypeSms, sourceType, intentTypeSent);
                        mSmsPendingSentIntents.put(id, record);
                    }
                    case MMS -> {
                        PendingIntentRecord record = new PendingIntentRecord(sent,
                                messageTypeMms, sourceType, intentTypeSent);
                        mMmsPendingSentIntents.put(id, record);
                    }
                }
            }
            if (delivery != null && !delivery.isEmpty() && type == MessageType.SMS) {
                PendingIntentRecord record = new PendingIntentRecord(delivery,
                        messageTypeSms, sourceType, intentTypeDelivery);
                mSmsPendingDeliveryIntents.put(id, record);
            }
        }
    }

    private void discardAllPendingIntents(MessageType type, long id) {
        synchronized (mMessageUpgradeLock) {
            if (type == MessageType.SMS) {
                discardUpgradedMessagePendingIntents(
                        mSmsPendingSentIntents, id, /* isSuccessfullyDispatched= */ false);
                discardUpgradedMessagePendingIntents(
                        mSmsPendingDeliveryIntents, id, /* isSuccessfullyDispatched= */ false);
            } else {
                discardUpgradedMessagePendingIntents(
                        mMmsPendingSentIntents, id, /* isSuccessfullyDispatched= */ false);
            }
        }
    }

    private List<PendingIntent> getUpgradedMessagePendingIntents(
            PendingIntentCache pendingIntents,
            long messageId) {
        synchronized (mMessageUpgradeLock) {
            PendingIntentRecord record = pendingIntents.get(messageId);
            return (record != null) ? record.mIntents : null;
        }
    }

    private void discardUpgradedMessagePendingIntents(
            PendingIntentCache pendingIntents,
            long messageId, boolean isSuccessfullyDispatched) {
        PendingIntentRecord record;
        synchronized (mMessageUpgradeLock) {
            record = pendingIntents.remove(messageId);
        }
        if (record != null) {
            int timeInCacheMs = (int) (android.os.SystemClock.elapsedRealtime()
                    - record.mAddedTimeMs);
            int eventType = isSuccessfullyDispatched
                    ? TelephonyStatsLog
                        .MESSAGE_UPGRADE_INTENT_CACHED__CACHE_EVENT__CACHE_EVENT_REMOVED_DISPATCHED
                    : TelephonyStatsLog
                        .MESSAGE_UPGRADE_INTENT_CACHED__CACHE_EVENT__CACHE_EVENT_REMOVED_REJECTED;
            TelephonyStatsLog.write(
                    TelephonyStatsLog.MESSAGE_UPGRADE_INTENT_CACHED,
                    record.mSourceType,
                    record.mOriginalMessageType,
                    eventType,
                    record.mIntentType,
                    timeInCacheMs);
        }
    }


    private void dispatchSentIntents(Context context,
            List<PendingIntent> pendingIntents,
            int resultCode,
            int errorCode) {
        for (PendingIntent intent : pendingIntents) {
            if (intent == null) continue;
            try {
                Intent fillInIntent = new Intent();
                if (resultCode != Activity.RESULT_OK) {
                    fillInIntent.putExtra("errorCode", errorCode);
                }
                intent.send(context, resultCode, fillInIntent);
            } catch (PendingIntent.CanceledException e) {
                Log.w(TAG, "Exception while firing sent intent");
            }
        }
    }

    private void dispatchDeliveryIntents(List<PendingIntent> deliveryIntents, int resultCode) {
        for (PendingIntent intent : deliveryIntents) {
            if (intent == null) continue;
            try {
                intent.send(resultCode);
            } catch (PendingIntent.CanceledException e) {
                Log.w(TAG, "Exception while firing delivery intent");
            }
        }
    }

    private long parseMessageId(Uri messageUri) {
        long messageId = -1;
        try {
            messageId = ContentUris.parseId(messageUri);
        } catch (NumberFormatException | UnsupportedOperationException e) {
            if (IS_DEBUG) {
                Log.d(TAG, "parseMessageId: Invalid URI: " + messageUri);
            }
        }
        return messageId;
    }

    private enum MessageType {
        SMS, MMS, UNKNOWN
    }

    private MessageType parseMessageType(Uri contentUri) {
        if (contentUri == null || contentUri.getAuthority() == null) {
            return MessageType.UNKNOWN;
        }

        return switch (contentUri.getAuthority()) {
            case AUTHORITY_SMS -> MessageType.SMS;
            case AUTHORITY_MMS -> MessageType.MMS;
            default -> MessageType.UNKNOWN;
        };
    }

    private static class PendingIntentCache extends LinkedHashMap<Long, PendingIntentRecord> {
        private final int mMaxSize;

        PendingIntentCache(int maxSize) {
            super(maxSize + 1, 1.0f, false);
            this.mMaxSize = maxSize;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, PendingIntentRecord> eldest) {
            if (size() > mMaxSize) {
                PendingIntentRecord record = eldest.getValue();
                int timeInCacheMs = (int) (android.os.SystemClock.elapsedRealtime()
                        - record.mAddedTimeMs);
                int eventType = TelephonyStatsLog
                        .MESSAGE_UPGRADE_INTENT_CACHED__CACHE_EVENT__CACHE_EVENT_REMOVED_EVICTED;
                TelephonyStatsLog.write(
                        TelephonyStatsLog.MESSAGE_UPGRADE_INTENT_CACHED,
                        record.mSourceType,
                        record.mOriginalMessageType,
                        eventType,
                        record.mIntentType,
                        timeInCacheMs);
                return true;
            }
            return false;
        }
    }

    private static class PendingIntentRecord {
        final List<PendingIntent> mIntents;
        final long mAddedTimeMs;
        final int mOriginalMessageType;
        final int mSourceType;
        final int mIntentType;

        PendingIntentRecord(List<PendingIntent> intents, int originalMessageType, int sourceType,
                int intentType) {
            this.mIntents = intents;
            this.mAddedTimeMs = android.os.SystemClock.elapsedRealtime();
            this.mOriginalMessageType = originalMessageType;
            this.mSourceType = sourceType;
            this.mIntentType = intentType;
        }
    }
}
