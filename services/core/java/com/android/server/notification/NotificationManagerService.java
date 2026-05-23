/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.server.notification;

import static android.Manifest.permission.CONTROL_KEYGUARD_SECURE_NOTIFICATIONS;
import static android.Manifest.permission.POST_PROMOTED_NOTIFICATIONS;
import static android.Manifest.permission.RECEIVE_SENSITIVE_NOTIFICATIONS;
import static android.Manifest.permission.STATUS_BAR_SERVICE;
import static android.Manifest.permission.UPDATE_APP_OPS_STATS;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
import static android.app.ActivityManagerInternal.ServiceNotificationPolicy.NOT_FOREGROUND_SERVICE;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_DEFAULT;
import static android.app.AppOpsManager.OP_POST_PROMOTED_NOTIFICATIONS;
import static android.app.AppOpsManager.OP_RECEIVE_SENSITIVE_NOTIFICATIONS;
import static android.app.Flags.nmContextualDisplayLaunch;
import static android.app.Flags.nmRemoveMustHaveFlags;
import static android.app.Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION;
import static android.app.Notification.EXTRA_APP_SUMMARIZATION;
import static android.app.Notification.EXTRA_BUILDER_APPLICATION_INFO;
import static android.app.Notification.EXTRA_LARGE_ICON_BIG;
import static android.app.Notification.EXTRA_SUB_TEXT;
import static android.app.Notification.EXTRA_TEXT;
import static android.app.Notification.EXTRA_TEXT_LINES;
import static android.app.Notification.EXTRA_TITLE;
import static android.app.Notification.EXTRA_TITLE_BIG;
import static android.app.Notification.FLAG_AUTOGROUP_SUMMARY;
import static android.app.Notification.FLAG_AUTO_CANCEL;
import static android.app.Notification.FLAG_BUBBLE;
import static android.app.Notification.FLAG_COMPUTER_CONTROL;
import static android.app.Notification.FLAG_FOREGROUND_SERVICE;
import static android.app.Notification.FLAG_FSI_REQUESTED_BUT_DENIED;
import static android.app.Notification.FLAG_GROUP_SUMMARY;
import static android.app.Notification.FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY;
import static android.app.Notification.FLAG_NO_CLEAR;
import static android.app.Notification.FLAG_NO_DISMISS;
import static android.app.Notification.FLAG_ONGOING_EVENT;
import static android.app.Notification.FLAG_ONLY_ALERT_ONCE;
import static android.app.Notification.FLAG_PROMOTED_ONGOING;
import static android.app.Notification.FLAG_USER_INITIATED_JOB;
import static android.app.NotificationLoggingConstants.DATA_TYPE_NLS_RESTRICTED;
import static android.app.NotificationLoggingConstants.DATA_TYPE_ZEN_CONFIG;
import static android.app.NotificationLoggingConstants.ERROR_XML_PARSING;
import static android.app.NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED;
import static android.app.NotificationManager.ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED;
import static android.app.NotificationManager.ACTION_CONSOLIDATED_NOTIFICATION_POLICY_CHANGED;
import static android.app.NotificationManager.ACTION_DYNAMIC_BUNDLE_MODIFIED;
import static android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED;
import static android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED_INTERNAL;
import static android.app.NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED;
import static android.app.NotificationManager.ACTION_NOTIFICATION_CHANNEL_GROUP_BLOCK_STATE_CHANGED;
import static android.app.NotificationManager.ACTION_NOTIFICATION_LISTENER_ENABLED_CHANGED;
import static android.app.NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED;
import static android.app.NotificationManager.ACTION_NOTIFICATION_POLICY_CHANGED;
import static android.app.NotificationManager.ALLOWED_NAS_ADJUSTMENT_KEYS_CHANGED;
import static android.app.NotificationManager.BUBBLE_PREFERENCE_ALL;
import static android.app.NotificationManager.DYNAMIC_BUNDLE_MODIFICATION_TYPE_ADDED;
import static android.app.NotificationManager.DYNAMIC_BUNDLE_MODIFICATION_TYPE_REMOVED;
import static android.app.NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_ID;
import static android.app.NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_STATUS;
import static android.app.NotificationManager.EXTRA_DYNAMIC_BUNDLE;
import static android.app.NotificationManager.EXTRA_DYNAMIC_BUNDLE_MODIFICATION_TYPE;
import static android.app.NotificationManager.EXTRA_NOTIFICATION_POLICY;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.IMPORTANCE_MIN;
import static android.app.NotificationManager.IMPORTANCE_NONE;
import static android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY;
import static android.app.NotificationManager.Policy.ALLOWED_INTERRUPTION_TYPE_UNSET;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECTS_UNSET;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_BADGE;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_OFF;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_ON;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR;
import static android.app.NotificationManager.SUPPORTED_NAS_ADJUSTMENT_KEYS_CHANGED;
import static android.app.NotificationManager.zenModeFromInterruptionFilter;
import static android.app.StatusBarManager.ACTION_KEYGUARD_PRIVATE_NOTIFICATIONS_CHANGED;
import static android.app.StatusBarManager.EXTRA_KM_PRIVATE_NOTIFS_ALLOWED;
import static android.content.Context.BIND_ALLOW_FREEZE;
import static android.content.Context.BIND_ALLOW_WHITELIST_MANAGEMENT;
import static android.content.Context.BIND_AUTO_CREATE;
import static android.content.Context.BIND_FOREGROUND_SERVICE;
import static android.content.Context.BIND_NOT_PERCEPTIBLE;
import static android.content.Context.BIND_SIMULATE_ALLOW_FREEZE;
import static android.content.pm.PackageManager.FEATURE_LEANBACK;
import static android.content.pm.PackageManager.FEATURE_TELECOM;
import static android.content.pm.PackageManager.FEATURE_TELEVISION;
import static android.content.pm.PackageManager.FLAG_PERMISSION_USER_SET;
import static android.content.pm.PackageManager.MATCH_ALL;
import static android.content.pm.PackageManager.MATCH_ANY_USER;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_CRITICAL;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_NORMAL;
import static android.os.PowerWhitelistManager.REASON_NOTIFICATION_SERVICE;
import static android.os.PowerWhitelistManager.TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED;
import static android.os.Process.INVALID_UID;
import static android.os.UserHandle.USER_ALL;
import static android.os.UserHandle.USER_NULL;
import static android.os.UserHandle.USER_SYSTEM;
import static android.os.UserHandle.getUserHandleForUid;
import static android.provider.DeviceConfig.NAMESPACE_SYSTEMUI;
import static android.provider.DeviceConfig.Properties;
import static android.security.Flags.secureLockDevice;
import static android.service.notification.Adjustment.KEY_GROUP_KEY;
import static android.service.notification.Adjustment.KEY_NOTIFICATION_RULES;
import static android.service.notification.Adjustment.KEY_SUMMARIZATION;
import static android.service.notification.Adjustment.KEY_TYPE;
import static android.service.notification.Adjustment.KEY_UNCLASSIFY;
import static android.service.notification.Adjustment.TYPE_CONTENT_RECOMMENDATION;
import static android.service.notification.Adjustment.TYPE_NEWS;
import static android.service.notification.Adjustment.TYPE_PROMOTION;
import static android.service.notification.Adjustment.TYPE_SOCIAL_MEDIA;
import static android.service.notification.Flags.FLAG_NOTIFICATION_CONVERSATION_CHANNEL_DELETION;
import static android.service.notification.Flags.FLAG_NOTIFICATION_CONVERSATION_CHANNEL_MANAGEMENT;
import static android.service.notification.Flags.callstyleCallbackApi;
import static android.service.notification.Flags.listenerHintExemptPackages;
import static android.service.notification.Flags.notificationBitmapOffloading;
import static android.service.notification.Flags.notificationRegroupOnClassification;
import static android.service.notification.Flags.redactSensitiveNotificationsBigTextStyle;
import static android.service.notification.Flags.redactSensitiveNotificationsFromUntrustedListeners;
import static android.service.notification.Flags.splitSoundVibrationForNotificationBreakthrough;
import static android.service.notification.NotificationListenerService.FLAG_FILTER_TYPE_ALERTING;
import static android.service.notification.NotificationListenerService.FLAG_FILTER_TYPE_CONVERSATIONS;
import static android.service.notification.NotificationListenerService.FLAG_FILTER_TYPE_ONGOING;
import static android.service.notification.NotificationListenerService.FLAG_FILTER_TYPE_SILENT;
import static android.service.notification.NotificationListenerService.HINT_HOST_DISABLE_CALL_EFFECTS;
import static android.service.notification.NotificationListenerService.HINT_HOST_DISABLE_EFFECTS;
import static android.service.notification.NotificationListenerService.HINT_HOST_DISABLE_NOTIFICATION_EFFECTS;
import static android.service.notification.NotificationListenerService.META_DATA_DEFAULT_FILTER_TYPES;
import static android.service.notification.NotificationListenerService.META_DATA_DISABLED_FILTER_TYPES;
import static android.service.notification.NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_ADDED;
import static android.service.notification.NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_DELETED;
import static android.service.notification.NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_UPDATED;
import static android.service.notification.NotificationListenerService.REASON_APP_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_APP_CANCEL_ALL;
import static android.service.notification.NotificationListenerService.REASON_ASSISTANT_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_BUNDLE_DISMISSED;
import static android.service.notification.NotificationListenerService.REASON_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_CANCEL_ALL;
import static android.service.notification.NotificationListenerService.REASON_CHANNEL_BANNED;
import static android.service.notification.NotificationListenerService.REASON_CHANNEL_REMOVED;
import static android.service.notification.NotificationListenerService.REASON_CLEAR_DATA;
import static android.service.notification.NotificationListenerService.REASON_CLICK;
import static android.service.notification.NotificationListenerService.REASON_ERROR;
import static android.service.notification.NotificationListenerService.REASON_GROUP_OPTIMIZATION;
import static android.service.notification.NotificationListenerService.REASON_GROUP_SUMMARY_CANCELED;
import static android.service.notification.NotificationListenerService.REASON_LISTENER_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_LISTENER_CANCEL_ALL;
import static android.service.notification.NotificationListenerService.REASON_LOCKDOWN;
import static android.service.notification.NotificationListenerService.REASON_PACKAGE_BANNED;
import static android.service.notification.NotificationListenerService.REASON_PACKAGE_CHANGED;
import static android.service.notification.NotificationListenerService.REASON_PACKAGE_SUSPENDED;
import static android.service.notification.NotificationListenerService.REASON_PROFILE_TURNED_OFF;
import static android.service.notification.NotificationListenerService.REASON_SNOOZED;
import static android.service.notification.NotificationListenerService.REASON_TIMEOUT;
import static android.service.notification.NotificationListenerService.REASON_UNAUTOBUNDLED;
import static android.service.notification.NotificationListenerService.REASON_USER_STOPPED;
import static android.service.notification.NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE;
import static android.service.notification.NotificationListenerService.TRIM_FULL;
import static android.service.notification.NotificationListenerService.TRIM_LIGHT;
import static android.service.personalcontext.Flags.enablePersonalContextService;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;
import static android.view.contentprotection.flags.Flags.rapidClearNotificationsByListenerAppOpEnabled;

import static com.android.server.notification.Flags.favoritesIncomingCallLights;
import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.NLS_COMPLETION_DURATION_MS;
import static com.android.internal.util.FrameworkStatsLog.DND_MODE_RULE;
import static com.android.internal.util.FrameworkStatsLog.NOTIFICATION_ADJUSTMENT_PREFERENCES;
import static com.android.internal.util.FrameworkStatsLog.PACKAGE_NOTIFICATION_CHANNEL_GROUP_PREFERENCES;
import static com.android.internal.util.FrameworkStatsLog.PACKAGE_NOTIFICATION_CHANNEL_PREFERENCES;
import static com.android.internal.util.FrameworkStatsLog.PACKAGE_NOTIFICATION_PREFERENCES;
import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.server.am.PendingIntentRecord.FLAG_ACTIVITY_SENDER;
import static com.android.server.am.PendingIntentRecord.FLAG_BROADCAST_SENDER;
import static com.android.server.am.PendingIntentRecord.FLAG_SERVICE_SENDER;
import static com.android.server.bitmapoffload.BitmapOffload.BITMAP_SOURCE_NOTIFICATIONS;
import static com.android.server.notification.Flags.favoritesIncomingCallLights;
import static com.android.server.notification.Flags.managedServicesConcurrentMultiuser;
import static com.android.server.notification.NotificationManagerService.NotificationPostEvent.NOTIFICATION_POSTED_CACHED;
import static com.android.server.policy.PhoneWindowManager.TOAST_WINDOW_ANIM_BUFFER;
import static com.android.server.policy.PhoneWindowManager.TOAST_WINDOW_TIMEOUT;
import static com.android.server.utils.PriorityDump.PRIORITY_ARG;
import static com.android.server.utils.PriorityDump.PRIORITY_ARG_CRITICAL;
import static com.android.server.utils.PriorityDump.PRIORITY_ARG_NORMAL;

import android.Manifest;
import android.Manifest.permission;
import android.annotation.DurationMillisLong;
import android.annotation.ElapsedRealtimeLong;
import android.annotation.EnforcePermission;
import android.annotation.FlaggedApi;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SpecialUsers.CanBeALL;
import android.annotation.SpecialUsers.CanBeCURRENT;
import android.annotation.SpecialUsers.CannotBeSpecialUser;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.annotation.WorkerThread;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityManagerInternal.ServiceNotificationPolicy;
import android.app.ActivityTaskManager;
import android.app.AlarmManager;
import android.app.AppGlobals;
import android.app.AppLockInternal;
import android.app.AppOpsManager;
import android.app.AutomaticZenRule;
import android.app.IActivityManager;
import android.app.IBinderSession;
import android.app.ICallNotificationEventCallback;
import android.app.INotificationManager;
import android.app.ITransientNotification;
import android.app.ITransientNotificationCallback;
import android.app.IUriGrantsManager;
import android.app.Notification;
import android.app.Notification.Action;
import android.app.Notification.MessagingStyle;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationHistory;
import android.app.NotificationHistory.HistoricalNotification;
import android.app.NotificationManager;
import android.app.NotificationManager.Policy;
import android.app.NotificationRule;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteServiceException.BadComputerControlNotificationException;
import android.app.RemoteServiceException.BadForegroundServiceNotificationException;
import android.app.RemoteServiceException.BadUserInitiatedJobNotificationException;
import android.app.StatsManager;
import android.app.UriGrantsManager;
import android.app.ZenBypassingApp;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.backup.BackupManager;
import android.app.backup.BackupRestoreEventLogger;
import android.app.compat.CompatChanges;
import android.app.role.OnRoleHoldersChangedListener;
import android.app.role.RoleManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManagerInternal;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.ICompanionDeviceManager;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.compat.annotation.EnabledSince;
import android.compat.annotation.LoggingOnly;
import android.content.AttributionSource;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.LauncherApps;
import android.content.pm.ModuleInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.ServiceInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutServiceInternal;
import android.content.pm.UserInfo;
import android.content.pm.VersionedPackage;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.DeviceIdleManager;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.WorkSource;
import android.permission.PermissionManager;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.provider.Settings.Secure;
import android.service.notification.Adjustment;
import android.service.notification.Condition;
import android.service.notification.ConversationChannelWrapper;
import android.service.notification.DeviceEffectsApplier;
import android.service.notification.DynamicBundle;
import android.service.notification.IConditionProvider;
import android.service.notification.IDispatchCompletionListener;
import android.service.notification.INotificationListener;
import android.service.notification.ListenersDisablingEffectsProto;
import android.service.notification.NotificationAssistantService;
import android.service.notification.NotificationListenerFilter;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationRankingUpdate;
import android.service.notification.NotificationRecordProto;
import android.service.notification.NotificationServiceDumpProto;
import android.service.notification.NotificationStats;
import android.service.notification.StatusBarNotification;
import android.service.notification.ZenDeviceEffects;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenModeProto;
import android.service.notification.ZenPolicy;
import android.service.personalcontext.hint.NotificationEvent;
import android.service.personalcontext.hint.NotificationEvent.NotificationEnqueuedEvent;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.text.Annotation;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.IndentingPrintWriter;
import android.util.IntArray;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.StatsEvent;
import android.util.TimeUtils;
import android.util.Xml;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.accessibility.AccessibilityManager;
import android.widget.RemoteViews;
import android.widget.Toast;
import android.window.DesktopExperienceFlags;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.compat.IPlatformCompat;
import com.android.internal.config.sysui.SystemUiSystemPropertiesFlags;
import com.android.internal.logging.InstanceId;
import com.android.internal.logging.InstanceIdSequence;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.UiEventLoggerImpl;
import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.messages.nano.SystemMessageProto;
import com.android.internal.notification.NotificationChannelGroupsHelper;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.SomeArgs;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.ConcurrentUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.Preconditions;
import com.android.internal.util.VibrationStatsWriter;
import com.android.internal.util.XmlUtils;
import com.android.internal.util.function.TriPredicate;
import com.android.internal.widget.LockPatternUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.DeviceIdleInternal;
import com.android.server.EventLogTags;
import com.android.server.IoThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.bitmapoffload.BitmapOffloadContract;
import com.android.server.bitmapoffload.BitmapOffloadInternal;
import com.android.server.job.JobSchedulerInternal;
import com.android.server.lights.LightsManager;
import com.android.server.notification.GroupHelper.NotificationAttributes;
import com.android.server.notification.ManagedServices.ManagedServiceInfo;
import com.android.server.notification.ManagedServices.UserProfiles;
import com.android.server.notification.NotificationRecordLogger.NotificationPullStatsEvent;
import com.android.server.notification.NotificationRecordLogger.NotificationReportedEvent;
import com.android.server.notification.toast.CustomToastRecord;
import com.android.server.notification.toast.TextToastRecord;
import com.android.server.notification.toast.ToastRecord;
import com.android.server.personalcontext.PersonalContextManagerInternal;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.UserManagerInternal;
import com.android.server.policy.PermissionPolicyInternal;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.uri.UriGrantsManagerInternal;
import com.android.server.utils.Slogf;
import com.android.server.utils.quota.MultiRateLimiter;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.BackgroundActivityStartCallback;
import com.android.server.wm.WindowManagerInternal;

import libcore.io.IoUtils;

import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** @hide */
public class NotificationManagerService extends SystemService {
    public static final String TAG = "NotificationService";
    public static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    public static final boolean ENABLE_CHILD_NOTIFICATIONS
            = SystemProperties.getBoolean("debug.child_notifs", true);

    // pullStats report request: undecorated remote view stats
    public static final int REPORT_REMOTE_VIEWS = 0x01;

    static final boolean DEBUG_INTERRUPTIVENESS = SystemProperties.getBoolean(
            "debug.notification.interruptiveness", false);

    static final int MAX_PACKAGE_NOTIFICATIONS = 50;
    static final float DEFAULT_MAX_NOTIFICATION_ENQUEUE_RATE = 5f;

    // To limit bad UX of seeing a toast many seconds after if was triggered.
    static final int MAX_PACKAGE_TOASTS = 5;

    // message codes
    static final int MESSAGE_DURATION_REACHED = 2;
    // 3: removed to a different handler
    static final int MESSAGE_SEND_RANKING_UPDATE = 4;
    static final int MESSAGE_LISTENER_HINTS_CHANGED = 5;
    static final int MESSAGE_LISTENER_NOTIFICATION_FILTER_CHANGED = 6;
    static final int MESSAGE_FINISH_TOKEN_TIMEOUT = 7;
    static final int MESSAGE_ON_PACKAGE_CHANGED = 8;

    static final Duration BITMAP_DURATION = Duration.ofHours(24);

    // ranking thread messages
    private static final int MESSAGE_RECONSIDER_RANKING = 1000;
    private static final int MESSAGE_RANKING_SORT = 1001;

    static final int LONG_DELAY = TOAST_WINDOW_TIMEOUT - TOAST_WINDOW_ANIM_BUFFER; // 3.5 seconds
    static final int SHORT_DELAY = 2000; // 2 seconds

    // 1 second past the ANR timeout.
    static final int FINISH_TOKEN_TIMEOUT = 11 * 1000;

    static final long SNOOZE_UNTIL_UNSPECIFIED = -1;

    /**
     *  The threshold, in milliseconds, to determine whether a notification has been
     * cleared too quickly.
     */
    private static final int NOTIFICATION_RAPID_CLEAR_THRESHOLD_MS = 5000;

    static final String ROOT_PKG = "root";

    static final String[] DEFAULT_ALLOWED_ADJUSTMENTS = new String[] {
            Adjustment.KEY_PEOPLE,
            Adjustment.KEY_SNOOZE_CRITERIA,
            Adjustment.KEY_USER_SENTIMENT,
            Adjustment.KEY_CONTEXTUAL_ACTIONS,
            Adjustment.KEY_TEXT_REPLIES,
            Adjustment.KEY_IMPORTANCE,
            Adjustment.KEY_IMPORTANCE_PROPOSAL,
            Adjustment.KEY_SENSITIVE_CONTENT,
            Adjustment.KEY_RANKING_SCORE,
            Adjustment.KEY_NOT_CONVERSATION,
            Adjustment.KEY_TYPE,
            Adjustment.KEY_SUMMARIZATION,
            KEY_NOTIFICATION_RULES
    };

    static final String[] NON_BLOCKABLE_DEFAULT_ROLES = new String[] {
            RoleManager.ROLE_DIALER,
            RoleManager.ROLE_EMERGENCY
    };

    // Used for rate limiting toasts by package.
    static final String TOAST_QUOTA_TAG = "toast_quota_tag";

    // This constant defines rate limits applied to showing toasts. The numbers are set in a way
    // such that an aggressive toast showing strategy would result in a roughly 1.5x longer wait
    // time (before the package is allowed to show toasts again) each time the toast rate limit is
    // reached. It's meant to protect the user against apps spamming them with toasts (either
    // accidentally or on purpose).
    private static final MultiRateLimiter.RateLimit[] TOAST_RATE_LIMITS = {
            MultiRateLimiter.RateLimit.create(3, Duration.ofSeconds(20)),
            MultiRateLimiter.RateLimit.create(5, Duration.ofSeconds(42)),
            MultiRateLimiter.RateLimit.create(6, Duration.ofSeconds(68)),
    };

    // When #matchesCallFilter is called from the ringer, wait at most
    // 3s to resolve the contacts. This timeout is required since
    // ContactsProvider might take a long time to start up.
    //
    // Return STARRED_CONTACT when the timeout is hit in order to avoid
    // missed calls in ZEN mode "Important".
    static final int MATCHES_CALL_FILTER_CONTACTS_TIMEOUT_MS = 3000;
    static final float MATCHES_CALL_FILTER_TIMEOUT_AFFINITY =
            ValidateNotificationPeople.STARRED_CONTACT;

    /** notification_enqueue status value for a newly enqueued notification. */
    private static final int EVENTLOG_ENQUEUE_STATUS_NEW = 0;

    /** notification_enqueue status value for an existing notification. */
    private static final int EVENTLOG_ENQUEUE_STATUS_UPDATE = 1;

    /** notification_enqueue status value for an ignored notification. */
    private static final int EVENTLOG_ENQUEUE_STATUS_IGNORED = 2;
    private static final long MIN_PACKAGE_OVERRATE_LOG_INTERVAL = 5000; // milliseconds

    static final long DELAY_FOR_ASSISTANT_TIME = 200;

    private static final long DELAY_FORCE_REGROUP_TIME = 3000;


    private static final String ACTION_NOTIFICATION_TIMEOUT =
            NotificationManagerService.class.getSimpleName() + ".TIMEOUT";
    private static final int REQUEST_CODE_TIMEOUT = 1;
    private static final String SCHEME_TIMEOUT = "timeout";
    private static final String EXTRA_KEY = "key";

    private static final int NOTIFICATION_INSTANCE_ID_MAX = (1 << 13);

    // States for the review permissions notification
    static final int REVIEW_NOTIF_STATE_UNKNOWN = -1;
    static final int REVIEW_NOTIF_STATE_SHOULD_SHOW = 0;
    static final int REVIEW_NOTIF_STATE_USER_INTERACTED = 1;
    static final int REVIEW_NOTIF_STATE_DISMISSED = 2;
    static final int REVIEW_NOTIF_STATE_RESHOWN = 3;

    // Action strings for review permissions notification
    static final String REVIEW_NOTIF_ACTION_REMIND = "REVIEW_NOTIF_ACTION_REMIND";
    static final String REVIEW_NOTIF_ACTION_DISMISS = "REVIEW_NOTIF_ACTION_DISMISS";
    static final String REVIEW_NOTIF_ACTION_CANCELED = "REVIEW_NOTIF_ACTION_CANCELED";

    /**
     * Apps that post custom toasts in the background will have those blocked. Apps can
     * still post toasts created with
     * {@link Toast#makeText(Context, CharSequence, int)} and its variants while
     * in the background.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.Q)
    private static final long CHANGE_BACKGROUND_CUSTOM_TOAST_BLOCK = 128611929L;

    /**
     * Activity starts coming from broadcast receivers or services in response to notification and
     * notification action clicks will be blocked for UX and performance reasons. Instead start the
     * activity directly from the PendingIntent.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.R)
    private static final long NOTIFICATION_TRAMPOLINE_BLOCK = 167676448L;

    /**
     * Activity starts coming from broadcast receivers or services in response to notification and
     * notification action clicks will be blocked for UX and performance reasons for previously
     * exempt role holders (browser).
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.S_V2)
    private static final long NOTIFICATION_TRAMPOLINE_BLOCK_FOR_EXEMPT_ROLES = 227752274L;

    /**
     * Whether a notification listeners can understand new, more specific, cancellation reasons.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.R)
    private static final long NOTIFICATION_CANCELLATION_REASONS = 175319604L;

    /**
     * Rate limit showing toasts, on a per package basis.
     *
     * It limits the number of {@link Toast#show()} calls to prevent overburdening
     * the user with too many toasts in a limited time. Any attempt to show more toasts than allowed
     * in a certain time frame will result in the toast being discarded.
     */
    @ChangeId
    @LoggingOnly
    private static final long RATE_LIMIT_TOASTS = 174840628L;

    /**
     * Whether listeners understand the more specific reason provided for notification
     * cancellations from an assistant, rather than using the more general REASON_LISTENER_CANCEL.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.S_V2)
    private static final long NOTIFICATION_LOG_ASSISTANT_CANCEL = 195579280L;

    /**
     * NO_CLEAR flag will be set for any media notification.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    static final long ENFORCE_NO_CLEAR_FLAG_ON_MEDIA_NOTIFICATION = 264179692L;

    /**
     * App calls to {@link NotificationManager#setInterruptionFilter} and
     * {@link NotificationManager#setNotificationPolicy} manage DND through the
     * creation and activation of an implicit {@link AutomaticZenRule}.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    static final long MANAGE_GLOBAL_ZEN_VIA_IMPLICIT_RULES = 308670109L;

    private static final Duration POST_WAKE_LOCK_TIMEOUT = Duration.ofSeconds(30);

    static final long NOTIFICATION_TTL = Duration.ofDays(3).toMillis();

    static final long NOTIFICATION_MAX_AGE_AT_POST = Duration.ofDays(14).toMillis();

    // Minium number of sparse groups for a package before autogrouping them
    @VisibleForTesting
    static final int AUTOGROUP_SPARSE_GROUPS_AT_COUNT = 6;
    // Minimum number notifications in a bundle section before autogrouping them
    private static final int AUTOGROUP_BUNDLE_SECTIONS_AT_COUNT = 1;

    private static final Duration ZEN_BROADCAST_DELAY = Duration.ofMillis(250);

    private IActivityManager mAm;
    private ActivityTaskManagerInternal mAtm;
    private ActivityManager mActivityManager;
    private ActivityManagerInternal mAmi;
    @VisibleForTesting
    IPackageManager mPackageManager;
    @VisibleForTesting
    PackageManager mPackageManagerClient;
    PackageManagerInternal mPackageManagerInternal;
    private PermissionManager mPermissionManager;
    private PermissionPolicyInternal mPermissionPolicyInternal;

    // Can be null for wear
    @Nullable StatusBarManagerInternal mStatusBar;
    private DisplayManager mDisplayManager;
    private WindowManagerInternal mWindowManagerInternal;
    @VisibleForTesting
    ComputerControlHelper mComputerControlHelper;
    private AlarmManager mAlarmManager;
    @VisibleForTesting
    ICompanionDeviceManager mCompanionManager;
    private AccessibilityManager mAccessibilityManager;
    private DeviceIdleManager mDeviceIdleManager;
    private IUriGrantsManager mUgm;
    private UriGrantsManagerInternal mUgmInternal;
    private volatile RoleObserver mRoleObserver;
    private UserManager mUm;
    private UserManagerInternal mUmInternal;
    private IPlatformCompat mPlatformCompat;
    private ShortcutHelper mShortcutHelper;
    private PermissionHelper mPermissionHelper;
    private UsageStatsManagerInternal mUsageStatsManagerInternal;
    private TelecomManager mTelecomManager;
    private PowerManager mPowerManager;
    private PostNotificationTrackerFactory mPostNotificationTrackerFactory;
    NotificationRuleManager mNotificationRuleManager;
    private NotificationManagerInternal mInternalService;
    NotificationManagerPrivate mNotificationManagerPrivate;

    private LockPatternUtils mLockUtils;
    // TODO(b/464052878): Make mAppLockInternal @NonNull when App Lock flags are removed.
    @Nullable private AppLockInternal mAppLockInternal;
    final AppLockInternal.PackageLockedStateListener mPackageLockedStateListener =
            new AppLockInternal.PackageLockedStateListener() {
                @Override
                public void onPackageLockedStateChanged(@NonNull String packageName, int userId,
                        boolean locked) {
                    Trace.beginSection(TAG + ".onPackageLockedStateChanged");
                    synchronized (mNotificationLock) {
                        if (locked == isPackageLockedByAppLockLocked(packageName, userId)) {
                            // Shouldn't happen, but this means it didn't change.
                            Slog.w(TAG,
                                    "onPackageLockedStateChanged called when the lock state "
                                            + "didn't change");
                            Trace.endSection();
                            return;
                        }
                        // Cache the new value
                        updateAppLockLockedPackagesLocked(packageName, userId, locked);

                        mListeners.notifyPackageAppLockStatedChanged(
                                findAppNotificationByListLocked(mNotificationList, packageName,
                                        userId));
                    }
                    Trace.endSection();
                }
            };

    /**
     * Tracks packages that are currently in a locked state from App Lock.
     *
     * <p>The outer {@link SparseArray} is keyed by userId, and the inner {@link ArraySet} contains
     * the package names of the locked packages for that user. This information is queried by
     * {@link #isPackageLockedByAppLockLocked(String, int)}.
     *
     * <p>This is initially populated when system services are ready (i.e. ActivityManager), and is
     * kept up to date with {@link #mPackageLockedStateListener}.
     */
    @GuardedBy("mNotificationLock")
    private final SparseArray<ArraySet<String>> mAppLockLockedPackages = new SparseArray<>();

    final IBinder mForegroundToken = new Binder();
    @VisibleForTesting
    WorkerHandler mHandler;
    private final HandlerThread mRankingThread = new HandlerThread("ranker",
            Process.THREAD_PRIORITY_BACKGROUND);
    private Handler mBroadcastsHandler;

    private final SparseArray<ArraySet<ComponentName>> mListenersDisablingEffects =
            new SparseArray<>();
    private List<ComponentName> mEffectsSuppressors = new ArrayList<>();
    private int mListenerHints;  // right now, all hints are global
    private int mInterruptionFilter = NotificationListenerService.INTERRUPTION_FILTER_UNKNOWN;

    private SystemUiSystemPropertiesFlags.FlagResolver mFlagResolver;

    // used as a mutex for access to all active notifications & listeners
    final Object mNotificationLock = new Object();
    @GuardedBy("mNotificationLock")
    final ArrayList<NotificationRecord> mNotificationList = new ArrayList<>();
    @GuardedBy("mNotificationLock")
    final ArrayMap<String, NotificationRecord> mNotificationsByKey = new ArrayMap<>();
    @GuardedBy("mNotificationLock")
    final ArrayMap<String, InlineReplyUriRecord> mInlineReplyRecordsByKey = new ArrayMap<>();
    @GuardedBy("mNotificationLock")
    final ArrayList<NotificationRecord> mEnqueuedNotifications = new ArrayList<>();
    @GuardedBy("mNotificationLock")
    final ArrayMap<Integer, ArrayMap<String, String>> mAutobundledSummaries = new ArrayMap<>();
    final ArrayList<ToastRecord> mToastQueue = new ArrayList<>();
    // set of uids for which toast rate limiting is disabled
    @GuardedBy("mToastQueue")
    private final Set<Integer> mToastRateLimitingDisabledUids = new ArraySet<>();
    final ArrayMap<String, NotificationRecord> mSummaryByGroupKey = new ArrayMap<>();

    // True if the toast that's on top of the queue is being shown at the moment.
    @GuardedBy("mToastQueue")
    private boolean mIsCurrentToastShown = false;

    // Used for rate limiting toasts by package.
    private MultiRateLimiter mToastRateLimiter;

    private AppOpsManager mAppOps;
    private UsageStatsManagerInternal mAppUsageStats;
    private DevicePolicyManagerInternal mDpm;
    private StatsManager mStatsManager;
    private StatsPullAtomCallbackImpl mPullAtomCallback;
    private UiEventLogger mUiEventLogger;

    private Archive mArchive;

    // Persistent storage for notification policy
    private AtomicFile mPolicyFile;
    // Persistent storage for notification rules
    private AtomicFile mRulesFile;

    private static final int DB_VERSION = 1;

    private static final String ADSERVICES_MODULE_PKG_NAME =
            "com.android.adservices";

    static final String TAG_NOTIFICATION_RULES = "notification-rules";
    private static final String TAG_NOTIFICATION_POLICY = "notification-policy";
    private static final String ATTR_VERSION = "version";

    private static final String LOCKSCREEN_ALLOW_SECURE_NOTIFICATIONS_TAG =
            "allow-secure-notifications-on-lockscreen";
    private static final String LOCKSCREEN_ALLOW_SECURE_NOTIFICATIONS_VALUE = "value";

    @VisibleForTesting
    RankingHelper mRankingHelper;
    @VisibleForTesting
    PreferencesHelper mPreferencesHelper;

    protected final UserProfiles mUserProfiles = new UserProfiles();
    private NotificationListeners mListeners;
    @VisibleForTesting
    NotificationAssistants mAssistants;
    private ConditionProviders mConditionProviders;
    private NotificationListenerStats mNotificationListenerStats;
    private NotificationUsageStats mUsageStats;
    private boolean mLockScreenAllowSecureNotifications = true;
    final ArrayMap<String, ArrayMap<Integer,
            RemoteCallbackList<ICallNotificationEventCallback>>>
            mCallNotificationEventCallbacks = new ArrayMap<>();

    private static final int MY_UID = Process.myUid();
    private static final int MY_PID = Process.myPid();
    static final IBinder ALLOWLIST_TOKEN = new Binder();
    protected RankingHandler mRankingHandler;
    private long mLastOverRateLogTime;
    private float mMaxPackageEnqueueRate = DEFAULT_MAX_NOTIFICATION_ENQUEUE_RATE;

    private boolean mRedactOtpNotifications = true;

    private NotificationHistoryManager mHistoryManager;
    protected SnoozeHelper mSnoozeHelper;
    private TimeToLiveHelper mTtlHelper;
    private GroupHelper mGroupHelper;
    private int mAutoGroupAtCount;
    private boolean mIsTelevision;
    protected NotificationAttentionHelper mAttentionHelper;

    private int mWarnRemoteViewsSizeBytes;
    private int mStripRemoteViewsSizeBytes;
    protected String[] mDefaultUnsupportedAdjustments;

    @VisibleForTesting
    protected boolean mShowReviewPermissionsNotification;

    private MetricsLogger mMetricsLogger;
    private NotificationChannelLogger mNotificationChannelLogger;
    private TriPredicate<String, Integer, String> mAllowedManagedServicePackages;

    private SaveFileRunnable mSavePolicyFile;
    private NotificationRecordLogger mNotificationRecordLogger;
    private InstanceIdSequence mNotificationInstanceIdSequence;
    private Set<String> mMsgPkgsAllowedAsConvos = new HashSet();
    private String mDefaultSearchSelectorPkg;

    // Broadcast intent receiver for notification permissions review-related intents
    private ReviewNotificationPermissionsReceiver mReviewNotificationPermissionsReceiver;

    private AppOpsManager.OnOpChangedListener mAppOpsListener;

    private ModuleInfo mAdservicesModuleInfo;

    private BitmapOffloadInternal mBitmapOffloader;
    private final Set<Uri> mOffloadedBitmapsPendingCleanup = new HashSet<>();
    private static final Duration OFFLOADED_BITMAP_CLEANUP_DELAY = Duration.ofHours(6);

    static class Archive {
        final SparseArray<Boolean> mEnabled;
        final int mBufferSize;
        final Object mBufferLock = new Object();
        @GuardedBy("mBufferLock")
        final LinkedList<Pair<StatusBarNotification, Integer>> mBuffer;

        public Archive(int size) {
            mBufferSize = size;
            mBuffer = new LinkedList<>();
            mEnabled = new SparseArray<>();
        }

        public String toString() {
            final StringBuilder sb = new StringBuilder();
            final int N = mBuffer.size();
            sb.append("Archive (");
            sb.append(N);
            sb.append(" notification");
            sb.append((N == 1) ? ")" : "s)");
            return sb.toString();
        }

        public void record(StatusBarNotification sbn, int reason) {
            if (!mEnabled.get(sbn.getNormalizedUserId(), false)) {
                return;
            }
            synchronized (mBufferLock) {
                if (mBuffer.size() == mBufferSize) {
                    mBuffer.removeFirst();
                }

                // We don't want to store the heavy bits of the notification in the archive,
                // but other clients in the system process might be using the object, so we
                // store a (lightened) copy.
                mBuffer.addLast(new Pair<>(sbn.cloneLight(), reason));
            }
        }

        public Iterator<Pair<StatusBarNotification, Integer>> descendingIterator() {
            return mBuffer.descendingIterator();
        }

        public StatusBarNotification[] getArray(UserManager um, int count, boolean includeSnoozed) {
            ArrayList<Integer> currentUsers = new ArrayList<>();
            currentUsers.add(USER_ALL);
            Binder.withCleanCallingIdentity(() -> {
                for (int user : um.getProfileIds(ActivityManager.getCurrentUser(), false)) {
                    currentUsers.add(user);
                }
            });
            synchronized (mBufferLock) {
                if (count == 0) count = mBufferSize;
                List<StatusBarNotification> a = new ArrayList();
                Iterator<Pair<StatusBarNotification, Integer>> iter = descendingIterator();
                int i = 0;
                while (iter.hasNext() && i < count) {
                    Pair<StatusBarNotification, Integer> pair = iter.next();
                    if (pair.second != REASON_SNOOZED || includeSnoozed) {
                        if (currentUsers.contains(pair.first.getUserId())) {
                            i++;
                            a.add(pair.first);
                        }
                    }
                }
                return a.toArray(new StatusBarNotification[a.size()]);
            }
        }

        public void updateHistoryEnabled(@UserIdInt int userId, boolean enabled) {
            mEnabled.put(userId, enabled);

            if (!enabled) {
                synchronized (mBufferLock) {
                    for (int i = mBuffer.size() - 1; i >= 0; i--) {
                        if (userId == mBuffer.get(i).first.getNormalizedUserId()) {
                            mBuffer.remove(i);
                        }
                    }
                }
            }
        }

        // Remove notifications with the specified user & channel ID.
        public void removeChannelNotifications(String pkg, @UserIdInt int userId,
                String channelId) {
            synchronized (mBufferLock) {
                Iterator<Pair<StatusBarNotification, Integer>> bufferIter = descendingIterator();
                while (bufferIter.hasNext()) {
                    final Pair<StatusBarNotification, Integer> pair = bufferIter.next();
                    if (pair.first != null
                            && userId == pair.first.getNormalizedUserId()
                            && pkg != null && pkg.equals(pair.first.getPackageName())
                            && pair.first.getNotification() != null
                            && Objects.equals(channelId,
                            pair.first.getNotification().getChannelId())) {
                        bufferIter.remove();
                    }
                }
            }
        }

        // Removes all notifications with the specified user & package.
        public void removePackageNotifications(String pkg, @UserIdInt int userId) {
            synchronized (mBufferLock) {
                Iterator<Pair<StatusBarNotification, Integer>> bufferIter = descendingIterator();
                while (bufferIter.hasNext()) {
                    final Pair<StatusBarNotification, Integer> pair = bufferIter.next();
                    if (pair.first != null
                            && userId == pair.first.getNormalizedUserId()
                            && pkg != null && pkg.equals(pair.first.getPackageName())
                            && pair.first.getNotification() != null) {
                        bufferIter.remove();
                    }
                }
            }
        }

        void dumpImpl(PrintWriter pw, @NonNull DumpFilter filter) {
            synchronized (mBufferLock) {
                Iterator<Pair<StatusBarNotification, Integer>> iter = descendingIterator();
                int i = 0;
                while (iter.hasNext()) {
                    final StatusBarNotification sbn = iter.next().first;
                    if (filter != null && !filter.matches(sbn)) continue;
                    pw.println("    " + sbn);
                    if (++i >= 5) {
                        if (iter.hasNext()) pw.println("    ...");
                        break;
                    }
                }
            }
        }
    }

    void loadDefaultApprovedServices(int userId) {
        mListeners.loadDefaultsFromConfig();

        mConditionProviders.loadDefaultsFromConfig();

        mAssistants.loadDefaultsFromConfig();
    }

    protected void allowDefaultApprovedServices(int userId, boolean isProfile) {
        if (!isProfile) {
            ArraySet<ComponentName> defaultListeners = mListeners.getDefaultComponents();
            for (int i = 0; i < defaultListeners.size(); i++) {
                ComponentName cn = defaultListeners.valueAt(i);
                setNotificationListenerAccessGrantedForUserInternal(cn, userId, true, true);
            }

            allowDndPackages(userId);
        }

        setDefaultAssistantForUser(userId);
    }

    @VisibleForTesting
    void allowDndPackages(int userId) {
        ArraySet<String> defaultDnds = mConditionProviders.getDefaultPackages();
        for (int i = 0; i < defaultDnds.size(); i++) {
            setNotificationPolicyAccessGrantedForUserInternal(defaultDnds.valueAt(i), userId, true);
        }
        if (!isDNDMigrationDone(userId)) {
            setDNDMigrationDone(userId);
        }
    }

    @VisibleForTesting
    boolean isDNDMigrationDone(int userId) {
        return Secure.getIntForUser(getContext().getContentResolver(),
                Secure.DND_CONFIGS_MIGRATED, 0, userId) == 1;
    }

    @VisibleForTesting
    void setDNDMigrationDone(int userId) {
        Secure.putIntForUser(getContext().getContentResolver(),
                Secure.DND_CONFIGS_MIGRATED, 1, userId);
    }

    protected void migrateDefaultNAS() {
        final List<UserInfo> activeUsers = mUm.getUsers();
        for (UserInfo userInfo : activeUsers) {
            int userId = userInfo.getUserHandle().getIdentifier();
            if (isNASMigrationDone(userId) || isProfileUser(userInfo)) {
                continue;
            }
            List<ComponentName> allowedComponents = mAssistants.getAllowedComponents(userId);
            if (allowedComponents.size() == 0) { // user set to none
                Slog.d(TAG, "NAS Migration: user set to none, disable new NAS setting");
                setNASMigrationDone(userId);
                mAssistants.clearDefaults();
            } else {
                Slog.d(TAG, "Reset NAS setting and migrate to new default for " + userId);
                resetAssistantUserSet(userId);
                // migrate to new default and set migration done
                mAssistants.resetDefaultAssistantsIfNecessary();
            }
        }
    }

    @VisibleForTesting
    void setNASMigrationDone(int baseUserId) {
        for (int profileId : mUm.getProfileIds(baseUserId, false)) {
            Secure.putIntForUser(getContext().getContentResolver(),
                    Secure.NAS_SETTINGS_UPDATED, 1, profileId);
        }
    }

    @VisibleForTesting
    boolean isNASMigrationDone(int userId) {
        return (Secure.getIntForUser(getContext().getContentResolver(),
                Secure.NAS_SETTINGS_UPDATED, 0, userId) == 1);
    }

    boolean isProfileUser(UserInfo userInfo) {
        return userInfo.isProfile() && hasParent(userInfo);
    }

    boolean hasParent(UserInfo profile) {
        return mUmInternal.getProfileParentId(profile.id) != profile.id;
    }

    protected void setDefaultAssistantForUser(int userId) {
        ArraySet<ComponentName> defaults = mAssistants.getDefaultComponents();
        // We should have only one default assistant by default
        // allowAssistant should execute once in practice
        for (int i = 0; i < defaults.size(); i++) {
            ComponentName cn = defaults.valueAt(i);
            if (allowAssistant(userId, cn)) return;
        }
    }

    /**
     * This method will update the flags and/or the icon of the summary.
     * It will set it to FLAG_ONGOING_EVENT if any of its group members
     * has the same flag. It will delete the flag otherwise.
     * It will update the summary notification icon if the group children's
     * icons are different.
     * @param userId user id of the autogroup summary
     * @param pkg package of the autogroup summary
     * @param groupKey group key of the autogroup summary
     * @param summaryAttr the new flags and/or icon & color for this summary
     * @param isAppForeground true if the app is currently in the foreground.
     */
    @GuardedBy("mNotificationLock")
    protected void updateAutobundledSummaryLocked(int userId, String pkg, String groupKey,
                NotificationAttributes summaryAttr, boolean isAppForeground) {
        ArrayMap<String, String> summaries = mAutobundledSummaries.get(userId);
        if (summaries == null) {
            return;
        }
        final String autbundledGroupKey = groupKey;

        String summaryKey = summaries.get(autbundledGroupKey);
        if (summaryKey == null) {
            return;
        }
        NotificationRecord summary = mNotificationsByKey.get(summaryKey);
        if (summary == null) {
            return;
        }

        int oldFlags = summary.getSbn().getNotification().flags;
        int newFlags = summaryAttr.flags != GroupHelper.FLAG_INVALID ? summaryAttr.flags : oldFlags;

        boolean attributesUpdated =
                !summaryAttr.icon.sameAs(summary.getSbn().getNotification().getSmallIcon())
                || summaryAttr.iconColor != summary.getSbn().getNotification().color
                || summaryAttr.visibility != summary.getSbn().getNotification().visibility
                || summaryAttr.groupAlertBehavior !=
                        summary.getSbn().getNotification().getGroupAlertBehavior();

        newFlags |= Notification.FLAG_SILENT;
        if (!summary.getChannel().getId().equals(summaryAttr.channelId)) {
            NotificationChannel newChannel = mPreferencesHelper.getNotificationChannel(pkg,
                    summary.getUid(), summaryAttr.channelId, false);
            if (newChannel != null) {
                summary.updateSystemNotificationChannel(newChannel);
                attributesUpdated = true;
            }
        }

        if (oldFlags != newFlags || attributesUpdated) {
            summary.getSbn().getNotification().flags = newFlags;
            summary.getSbn().getNotification().setSmallIcon(summaryAttr.icon);
            summary.getSbn().getNotification().color = summaryAttr.iconColor;
            summary.getSbn().getNotification().visibility = summaryAttr.visibility;
            summary.getSbn().getNotification()
                    .setGroupAlertBehavior(summaryAttr.groupAlertBehavior);
            mHandler.post(new EnqueueNotificationRunnable(userId, summary, isAppForeground,
                    /* isAppProvided= */ false, mPostNotificationTrackerFactory.newTracker(null)));
        }
    }

    private void setNotificationPolicyAccessGrantedForUserInternal(String pkg,
            @UserIdInt int userId, boolean granted) {
        final long identity = Binder.clearCallingIdentity();
        try {
            if (mAllowedManagedServicePackages.test(
                    pkg, userId, mConditionProviders.getRequiredPermission())) {
                boolean changed = mConditionProviders.setPackageOrComponentEnabled(pkg, userId,
                        /* isPrimary= */ true, granted);
                if (!changed) {
                    return;
                }

                getContext().sendBroadcastAsUser(new Intent(
                                ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED)
                                .setPackage(pkg)
                                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT),
                        UserHandle.of(userId), null);
                handleSavePolicyFile();
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void setNotificationListenerAccessGrantedForUserInternal(ComponentName listener,
            @CannotBeSpecialUser @UserIdInt int userId, boolean granted, boolean userSet) {
        if (!managedServicesConcurrentMultiuser()
                && mUmInternal.isVisibleBackgroundFullUser(userId)) {
            // The main use case for visible background users is the Automotive multi-display
            // configuration where a passenger can use a secondary display while the driver is
            // using the main display. NotificationListeners is designed only for the current
            // user and work profile. We added a condition to prevent visible background users
            // from updating the data managed within the NotificationListeners object.
            return;
        }
        checkNotificationListenerAccess();
        if (granted && listener.flattenToString().getBytes().length
                > NotificationManager.MAX_SERVICE_COMPONENT_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Component name too long: " + listener.flattenToString());
        }
        if (!userSet && isNotificationListenerAccessUserSet(listener, userId)) {
            // Don't override user's choice
            return;
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            if (mAllowedManagedServicePackages.test(
                    listener.getPackageName(), userId, mListeners.getRequiredPermission())) {
                boolean changed = mListeners.setPackageOrComponentEnabled(
                        listener.flattenToString(), userId, /* isPrimary= */ true, granted,
                        userSet);
                if (!changed) {
                    return;
                }

                mConditionProviders.setPackageOrComponentEnabled(listener.flattenToString(),
                        userId, false, granted, userSet);

                getContext().sendBroadcastAsUser(new Intent(
                                ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED)
                                .setPackage(listener.getPackageName())
                                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY),
                        UserHandle.of(userId), null);

                handleSavePolicyFile();
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private boolean isNotificationListenerAccessUserSet(ComponentName listener, int userId) {
        return mListeners.isPackageOrComponentUserSet(listener.flattenToString(), userId);
    }

    private boolean allowAssistant(int userId, ComponentName candidate) {
        Set<ComponentName> validAssistants =
                mAssistants.queryPackageForServices(
                        null,
                        MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE, userId);
        if (candidate != null && validAssistants.contains(candidate)) {
            setNotificationAssistantAccessGrantedForUserInternal(candidate, userId, true, false);
            return true;
        }
        return false;
    }

    void readRulesXml(TypedXmlPullParser parser, boolean forRestore, int userId,
            @Nullable BackupRestoreEventLogger logger)
            throws XmlPullParserException, NumberFormatException, IOException {
        if (!nmContextualDisplayLaunch()) {
            return;
        }
        XmlUtils.beginDocument(parser, TAG_NOTIFICATION_RULES);
        mNotificationRuleManager.readXml(parser, forRestore, userId, logger);
    }

    void readPolicyXml(TypedXmlPullParser parser, boolean forRestore, int userId,
            @Nullable BackupRestoreEventLogger logger)
            throws XmlPullParserException, NumberFormatException, IOException {
        XmlUtils.beginDocument(parser, TAG_NOTIFICATION_POLICY);
        boolean migratedManagedServices = false;
        UserInfo userInfo = mUmInternal.getUserInfo(userId);
        boolean ineligibleForManagedServices = forRestore && isProfileUser(userInfo);
        int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            if (ZenModeConfig.ZEN_TAG.equals(parser.getName())) {
                int successfulReads = 0;
                int unsuccessfulReads = 0;
                try {
                    boolean loadedCorrectly =
                            mZenModeHelper.readXml(parser, forRestore, userId, logger);
                    if (loadedCorrectly)
                        successfulReads++;
                    else
                        unsuccessfulReads++;
                } catch (Exception e) {
                    Slog.wtf(TAG, "failed to read config", e);
                    unsuccessfulReads++;
                }
                if (logger != null) {
                    logger.logItemsRestored(DATA_TYPE_ZEN_CONFIG, successfulReads);
                    if (unsuccessfulReads > 0) {
                        logger.logItemsRestoreFailed(
                                DATA_TYPE_ZEN_CONFIG, unsuccessfulReads, ERROR_XML_PARSING);
                    }
                }

            } else if (PreferencesHelper.TAG_RANKING.equals(parser.getName())){
                mPreferencesHelper.readXml(parser, forRestore, userId, logger);
            }
            if (mListeners.getConfig().xmlTag.equals(parser.getName())) {
                if (ineligibleForManagedServices) {
                    continue;
                }
                mListeners.readXml(
                        parser, mAllowedManagedServicePackages, forRestore, userId, logger);
                migratedManagedServices = true;
            } else if (mAssistants.getConfig().xmlTag.equals(parser.getName())) {
                if (ineligibleForManagedServices) {
                    continue;
                }
                mAssistants.readXml(
                        parser, mAllowedManagedServicePackages, forRestore, userId, logger);
                migratedManagedServices = true;
            } else if (mConditionProviders.getConfig().xmlTag.equals(parser.getName())) {
                if (ineligibleForManagedServices) {
                    continue;
                }
                mConditionProviders.readXml(
                        parser, mAllowedManagedServicePackages, forRestore, userId, logger);
                migratedManagedServices = true;
            } else if (mSnoozeHelper.XML_TAG_NAME.equals(parser.getName())) {
                mSnoozeHelper.readXml(parser, System.currentTimeMillis(), logger);
            }
            if (LOCKSCREEN_ALLOW_SECURE_NOTIFICATIONS_TAG.equals(parser.getName())) {
                if (forRestore && userId != USER_SYSTEM) {
                    continue;
                }
                mLockScreenAllowSecureNotifications = parser.getAttributeBoolean(null,
                        LOCKSCREEN_ALLOW_SECURE_NOTIFICATIONS_VALUE, true);
            }
            if (NotificationListenerStats.isXmlTag(parser.getName())) {
                mNotificationListenerStats.readXml(parser);
            }
        }
        if (!migratedManagedServices) {
            mListeners.migrateToXml();
            mAssistants.migrateToXml();
            mConditionProviders.migrateToXml();
            handleSavePolicyFile();
        }

        mAssistants.resetDefaultAssistantsIfNecessary();
        mPreferencesHelper.syncHasPriorityChannels();
    }

    @VisibleForTesting
    void resetDefaultDndIfNecessary() {
        boolean removed = false;
        final List<UserInfo> activeUsers = mUm.getAliveUsers();
        for (UserInfo userInfo : activeUsers) {
            int userId = userInfo.getUserHandle().getIdentifier();
            if (isDNDMigrationDone(userId)) {
                continue;
            }
            removed |= mConditionProviders.removeDefaultFromConfig(userId);
            mConditionProviders.resetDefaultFromConfig();
            allowDndPackages(userId);
        }
        if (removed) {
            handleSavePolicyFile();
        }
    }

    @VisibleForTesting
    protected void loadRulesFile() {
        if (DBG) Slog.d(TAG, "loadRulesFile");
        synchronized (mRulesFile) {
            InputStream infile = null;
            try {
                infile = mRulesFile.openRead();
                final TypedXmlPullParser parser = Xml.resolvePullParser(infile);
                readRulesXml(parser, false /*forRestore*/, USER_ALL, null);
            } catch (FileNotFoundException e) {
                // No data yet
                // Load default rules for all current users
                for (UserInfo userInfo : mUm.getUsers()) {
                    mNotificationRuleManager.onUserAdded(userInfo.id);
                }
                handleSaveRulesFile();
            } catch (IOException | NumberFormatException | XmlPullParserException e) {
                Log.wtf(TAG, "Unable to read notification rules", e);
            } finally {
                IoUtils.closeQuietly(infile);
            }
        }
    }

    @VisibleForTesting
    protected void loadPolicyFile() {
        if (DBG) Slog.d(TAG, "loadPolicyFile");
        synchronized (mPolicyFile) {
            InputStream infile = null;
            try {
                infile = mPolicyFile.openRead();
                final TypedXmlPullParser parser = Xml.resolvePullParser(infile);
                readPolicyXml(parser, false /*forRestore*/, USER_ALL, null);

                // We re-load the default dnd packages to allow the newly added and denined.
                final boolean isWatch = mPackageManagerClient.hasSystemFeature(
                        PackageManager.FEATURE_WATCH);
                if (isWatch) {
                    resetDefaultDndIfNecessary();
                }
            } catch (FileNotFoundException e) {
                // No data yet
                // Load default managed services approvals
                loadDefaultApprovedServices(USER_SYSTEM);
                allowDefaultApprovedServices(USER_SYSTEM, /*isProfile*/ false);
            } catch (IOException | NumberFormatException | XmlPullParserException e) {
                Log.wtf(TAG, "Unable to read notification policy", e);
            } finally {
                IoUtils.closeQuietly(infile);
            }
        }
    }

    @VisibleForTesting
    protected void handleSavePolicyFile() {
        if (!IoThread.getHandler().hasCallbacks(mSavePolicyFile)) {
            IoThread.getHandler().postDelayed(mSavePolicyFile, 250);
        }
    }

    @VisibleForTesting
    protected void handleSaveRulesFile() {
        if (nmContextualDisplayLaunch()) {
            IoThread.getHandler().post(
                    new SaveFileRunnable(SaveFileRunnable.RULES_FILE, mRulesFile));
        }
    }

    final class SaveFileRunnable implements Runnable {
        static final int POLICY_FILE = 1;
        static final int RULES_FILE = 2;
        private final int mFileType;
        private final AtomicFile mAtomicFile;

        SaveFileRunnable() {
            mFileType = POLICY_FILE;
            mAtomicFile = mPolicyFile;
        }

        SaveFileRunnable(int fileType, AtomicFile atomicFile) {
            mFileType = fileType;
            mAtomicFile = atomicFile;
        }

        @Override
        public void run() {
            synchronized (mAtomicFile) {
                final FileOutputStream stream;
                try {
                    stream = mAtomicFile.startWrite();
                } catch (IOException e) {
                    Slog.w(TAG, "Failed to save file", e);
                    return;
                }

                try {
                    TypedXmlSerializer out = Xml.resolveSerializer(stream);
                    out.startDocument(null, true);
                    if (mFileType == POLICY_FILE) {
                        writePolicyXml(out, false /*forBackup*/, USER_ALL, null);
                    } else if (mFileType == RULES_FILE) {
                        writeRulesXml(out, false /*forBackup*/, USER_ALL, null);
                    }
                    out.endDocument();
                    mAtomicFile.finishWrite(stream);
                } catch (IOException e) {
                    Slog.w(TAG, "Failed to save file, restoring backup", e);
                    mAtomicFile.failWrite(stream);
                }
            }
            BackupManager.dataChanged(getContext().getPackageName());
        }
    }

    void writePolicyXml(TypedXmlSerializer out, boolean forBackup, int userId,
            BackupRestoreEventLogger logger)  throws IOException {
        out.startTag(null, TAG_NOTIFICATION_POLICY);
        out.attributeInt(null, ATTR_VERSION, DB_VERSION);
        mZenModeHelper.writeXml(out, forBackup, null, userId, logger);
        mPreferencesHelper.writeXml(out, forBackup, userId, logger);
        mListeners.writeXml(out, forBackup, userId, logger);
        mAssistants.writeXml(out, forBackup, userId, logger);
        mSnoozeHelper.writeXml(out, logger);
        mConditionProviders.writeXml(out, forBackup, userId, logger);
        if (!forBackup || userId == USER_SYSTEM) {
            writeSecureNotificationsPolicy(out);
        }
        if (!forBackup) {
            mNotificationListenerStats.writeXml(out);
        }
        out.endTag(null, TAG_NOTIFICATION_POLICY);
    }

    void writeRulesXml(TypedXmlSerializer out, boolean forBackup, int userId,
            BackupRestoreEventLogger logger)  throws IOException {
        mNotificationRuleManager.writeXml(out, forBackup, userId, logger);
    }

    @VisibleForTesting
    final NotificationDelegate mNotificationDelegate = new NotificationDelegate() {

        @Override
        public void prepareForPossibleShutdown() {
            mHistoryManager.triggerWriteToDisk();
        }

        @Override
        public void onSetDisabled(int status) {
            synchronized (mNotificationLock) {
                mAttentionHelper.updateDisableNotificationEffectsLocked(status);
            }
        }

        @Override
        public void onClearAll(int callingUid, int callingPid, int userId) {
            synchronized (mNotificationLock) {
                cancelAllLocked(callingUid, callingPid, userId, REASON_CANCEL_ALL, null,
                        /*includeCurrentProfiles*/ true, FLAG_ONGOING_EVENT | FLAG_NO_CLEAR);
            }
        }

        @Override
        public void onNotificationClick(int callingUid, int callingPid, String key,
                NotificationVisibility nv) {
            exitIdle();
            synchronized (mNotificationLock) {
                NotificationRecord r = mNotificationsByKey.get(key);
                if (r == null) {
                    Slog.w(TAG, "No notification with key: " + key);
                    return;
                }
                final long now = System.currentTimeMillis();
                MetricsLogger.action(r.getItemLogMaker()
                        .setType(MetricsEvent.TYPE_ACTION)
                        .addTaggedData(MetricsEvent.NOTIFICATION_SHADE_INDEX, nv.rank)
                        .addTaggedData(MetricsEvent.NOTIFICATION_SHADE_COUNT, nv.count));
                mNotificationRecordLogger.log(
                        NotificationRecordLogger.NotificationEvent.NOTIFICATION_CLICKED, r);
                EventLogTags.writeNotificationClicked(key,
                        r.getLifespanMs(now), r.getFreshnessMs(now), r.getExposureMs(now),
                        nv.rank, nv.count);

                StatusBarNotification sbn = r.getSbn();
                Notification notification = sbn.getNotification();
                FlagChecker flagChecker = FlagChecker.mustNotHave(
                        FLAG_FOREGROUND_SERVICE | FLAG_USER_INITIATED_JOB | FLAG_BUBBLE);
                if (nmRemoveMustHaveFlags()) {
                    // Notifications which have been lifetime extended should be cancelled on click,
                    // regardless of presence or absence of FLAG_AUTO_CANCEL.
                    if (hasFlag(notification.flags, FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY)) {
                        cancelNotification(callingUid, callingPid, sbn.getPackageName(),
                                sbn.getTag(), sbn.getId(), 0, flagChecker,
                                false, r.getUserId(), REASON_CLICK, nv.rank, nv.count, null);

                    } else if (hasFlag(notification.flags, FLAG_AUTO_CANCEL)) {
                        // Otherwise, only FLAG_AUTO_CANCEL notifications (and their children)
                        // should be canceled on click.
                        cancelNotification(callingUid, callingPid, sbn.getPackageName(),
                                sbn.getTag(), sbn.getId(), 0, flagChecker,
                                false, r.getUserId(), REASON_CLICK, nv.rank, nv.count, null);
                    }
                } else {
                    // Notifications should be cancelled on click if they have been lifetime extended,
                    // regardless of presence or absence of FLAG_AUTO_CANCEL.
                    if ((notification.flags & FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY) != 0) {
                        cancelNotification(callingUid, callingPid, sbn.getPackageName(),
                                sbn.getTag(), sbn.getId(), FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY,
                                flagChecker,
                                false, r.getUserId(), REASON_CLICK, nv.rank, nv.count, null);

                    } else {
                        // Otherwise, only FLAG_AUTO_CANCEL notifications should be canceled on click.
                        cancelNotification(callingUid, callingPid, sbn.getPackageName(), sbn.getTag(),
                                sbn.getId(), FLAG_AUTO_CANCEL,
                                flagChecker,
                                false, r.getUserId(), REASON_CLICK, nv.rank, nv.count, null);
                    }
                }
                nv.recycle();
                reportUserInteraction(r);
                mAssistants.notifyAssistantNotificationClicked(r);
            }
        }

        @Override
        public void onNotificationActionClick(int callingUid, int callingPid, String key,
                int actionIndex, Notification.Action action, NotificationVisibility nv,
                boolean generatedByAssistant) {
            exitIdle();
            synchronized (mNotificationLock) {
                NotificationRecord r = mNotificationsByKey.get(key);
                if (r == null) {
                    Slog.w(TAG, "No notification with key: " + key);
                    return;
                }
                final long now = System.currentTimeMillis();
                boolean isAnimatedAction = false;
                if (action != null && action.getExtras() != null) {
                    isAnimatedAction = action.getExtras()
                    .getBoolean(Notification.Action.EXTRA_IS_ANIMATED, false);
                }
                MetricsLogger.action(r.getLogMaker(now)
                        .setCategory(MetricsEvent.NOTIFICATION_ITEM_ACTION)
                        .setType(MetricsEvent.TYPE_ACTION)
                        .setSubtype(actionIndex)
                        .addTaggedData(MetricsEvent.NOTIFICATION_SHADE_INDEX, nv.rank)
                        .addTaggedData(MetricsEvent.NOTIFICATION_SHADE_COUNT, nv.count)
                        .addTaggedData(MetricsEvent.NOTIFICATION_ACTION_IS_SMART,
                                action.isContextual() ? 1 : 0)
                        .addTaggedData(
                                MetricsEvent.NOTIFICATION_SMART_SUGGESTION_ASSISTANT_GENERATED,
                                generatedByAssistant ? 1 : 0)
                        .addTaggedData(MetricsEvent.NOTIFICATION_LOCATION,
                                nv.location.toMetricsEventEnum()));
                mNotificationRecordLogger.log(
                        NotificationRecordLogger.NotificationEvent.fromAction(actionIndex,
                                generatedByAssistant, action.isContextual(),
                                isAnimatedAction), r);
                EventLogTags.writeNotificationActionClicked(key,
                        action.actionIntent.getTarget().toString(),
                        action.actionIntent.getIntent().toString(), actionIndex,
                        r.getLifespanMs(now), r.getFreshnessMs(now), r.getExposureMs(now),
                        nv.rank, nv.count);
                nv.recycle();
                reportUserInteraction(r);
                mAssistants.notifyAssistantActionClicked(r, action, generatedByAssistant);
                // Notifications that have been interacted with should no longer be lifetime
                // extended. This cancellation should only work if
                // the notification still has FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY
                // We wait for 200 milliseconds before posting the cancel, to allow the app
                // time to update the notification in response instead.
                // If that update goes through, the notification won't have the lifetime
                // extended flag, and this cancellation will be dropped.
                mHandler.scheduleCancelNotification(
                        new CancelNotificationRunnable(
                                callingUid,
                                callingPid,
                                r.getSbn().getPackageName(),
                                r.getSbn().getTag(),
                                r.getSbn().getId(),
                                 /*=mustHaveFlags*/ nmRemoveMustHaveFlags()
                                        ? 0 : FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY,
                                FlagChecker.mustHaveAndMustNotHave(
                                        /* mustHaveFlags= */ nmRemoveMustHaveFlags()
                                                ? FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY : 0,
                                        /* mustNotHaveFlags= */ FLAG_NO_DISMISS),
                                false /*=sendDelete*/,
                                r.getUserId(),
                                REASON_CLICK,
                                -1 /*=rank*/,
                                -1 /*=count*/,
                                null /*=listener*/,
                                SystemClock.elapsedRealtime()),
                        200);
            }
        }

        @Override
        public void onNotificationClear(int callingUid, int callingPid,
                String pkg, int userId, String key,
                @NotificationStats.DismissalSurface int dismissalSurface,
                @NotificationStats.DismissalSentiment int dismissalSentiment,
                NotificationVisibility nv, boolean fromBundle) {
            String tag = null;
            int id = 0;
            synchronized (mNotificationLock) {
                NotificationRecord r = mNotificationsByKey.get(key);
                if (r != null) {
                    r.recordDismissalSurface(dismissalSurface);
                    r.recordDismissalSentiment(dismissalSentiment);
                    tag = r.getSbn().getTag();
                    id = r.getSbn().getId();
                }
            }

            int mustNotHaveFlags = FLAG_NO_DISMISS;
            cancelNotification(callingUid, callingPid, pkg, tag, id,
                    /* mustHaveFlags= */ 0,
                    /* flagChecker= */ FlagChecker.mustNotHave(mustNotHaveFlags),
                    /* sendDelete= */ true,
                    userId, fromBundle ? REASON_BUNDLE_DISMISSED : REASON_CANCEL, nv.rank, nv.count,
                    /* listener= */ null);
            nv.recycle();
        }

        @Override
        public void onPanelRevealed(boolean clearEffects, int items) {
            MetricsLogger.visible(getContext(), MetricsEvent.NOTIFICATION_PANEL);
            MetricsLogger.histogram(getContext(), "note_load", items);
            mNotificationRecordLogger.log(
                    NotificationRecordLogger.NotificationPanelEvent.NOTIFICATION_PANEL_OPEN);
            EventLogTags.writeNotificationPanelRevealed(items);
            if (clearEffects) {
                clearEffects();
            }
            mAssistants.onPanelRevealed(items);
        }

        @Override
        public void onPanelHidden() {
            MetricsLogger.hidden(getContext(), MetricsEvent.NOTIFICATION_PANEL);
            mNotificationRecordLogger.log(
                    NotificationRecordLogger.NotificationPanelEvent.NOTIFICATION_PANEL_CLOSE);
            EventLogTags.writeNotificationPanelHidden();
            mAssistants.onPanelHidden();
        }

        @Override
        public void clearEffects() {
            synchronized (mNotificationLock) {
                if (DBG) Slog.d(TAG, "clearEffects");
                mAttentionHelper.clearAttentionEffects();
            }
        }

        @Override
        public void onNotificationError(int callingUid, int callingPid, String pkg, String tag,
                int id, int uid, int initialPid, String message, int userId) {
            final boolean fgService;
            final boolean uiJob;
            final boolean computerControl;
            synchronized (mNotificationLock) {
                NotificationRecord r = findNotificationLocked(pkg, tag, id, userId);
                fgService = r != null && r.getNotification().isForegroundService();
                uiJob = r != null && r.getNotification().isUserInitiatedJob();
                computerControl =
                        android.companion.virtualdevice.flags.Flags.computerControlAccess()
                                && r != null
                                && r.getNotification().isComputerControl();
            }
            cancelNotification(callingUid, callingPid, pkg, tag, id, 0, null, false, userId,
                    REASON_ERROR, null);
            if (fgService || uiJob || computerControl) {
                // Still crash for foreground services or user-initiated jobs or computer control
                // sessions, preventing the not-crash behaviour abused by apps to give us a garbage
                // notification and silently start a fg service or user-initiated job or a computer
                // control session.
                final int exceptionTypeId = computerControl
                        ? BadComputerControlNotificationException.TYPE_ID
                        : (fgService ? BadForegroundServiceNotificationException.TYPE_ID
                                : BadUserInitiatedJobNotificationException.TYPE_ID);
                Binder.withCleanCallingIdentity(
                        () -> mAm.crashApplicationWithType(uid, initialPid, pkg, -1,
                            "Bad notification(tag=" + tag + ", id=" + id + ") posted from package "
                                + pkg + ", crashing app(uid=" + uid + ", pid=" + initialPid + "): "
                                + message, true /* force */, exceptionTypeId));
            }
        }

        @Override
        public void onNotificationVisibilityChanged(NotificationVisibility[] newlyVisibleKeys,
                NotificationVisibility[] noLongerVisibleKeys) {
            synchronized (mNotificationLock) {
                for (NotificationVisibility nv : newlyVisibleKeys) {
                    NotificationRecord r = mNotificationsByKey.get(nv.key);
                    if (r == null) continue;
                    if (!r.isSeen()) {
                        // Report to usage stats that notification was made visible
                        if (DBG) Slog.d(TAG, "Marking notification as visible " + nv.key);
                        reportSeen(r);
                    }
                    r.setVisibility(true, nv.rank, nv.count, mNotificationRecordLogger);
                    mAssistants.notifyAssistantVisibilityChangedLocked(r, true);
                    boolean isHun = (nv.location
                            == NotificationVisibility.NotificationLocation.LOCATION_FIRST_HEADS_UP);
                    // hasBeenVisiblyExpanded must be called after updating the expansion state of
                    // the NotificationRecord to ensure the expansion state is up-to-date.
                    if (isHun || r.hasBeenVisiblyExpanded()) {
                        logSmartSuggestionsVisible(r, nv.location.toMetricsEventEnum());
                    }
                    maybeRecordInterruptionLocked(r);
                    nv.recycle();
                }
                // Note that we might receive this event after notifications
                // have already left the system, e.g. after dismissing from the
                // shade. Hence not finding notifications in
                // mNotificationsByKey is not an exceptional condition.
                for (NotificationVisibility nv : noLongerVisibleKeys) {
                    NotificationRecord r = mNotificationsByKey.get(nv.key);
                    if (r == null) continue;
                    r.setVisibility(false, nv.rank, nv.count, mNotificationRecordLogger);
                    mAssistants.notifyAssistantVisibilityChangedLocked(r, false);
                    nv.recycle();
                }
            }
        }

        @Override
        public void onNotificationExpansionChanged(String key,
                boolean userAction, boolean expanded, int notificationLocation) {
            synchronized (mNotificationLock) {
                NotificationRecord r = mNotificationsByKey.get(key);
                if (r != null) {
                    r.stats.onExpansionChanged(userAction, expanded);
                    // hasBeenVisiblyExpanded must be called after updating the expansion state of
                    // the NotificationRecord to ensure the expansion state is up-to-date.
                    if (r.hasBeenVisiblyExpanded()) {
                        logSmartSuggestionsVisible(r, notificationLocation);
                    }
                    if (userAction) {
                        MetricsLogger.action(r.getItemLogMaker()
                                .setType(expanded ? MetricsEvent.TYPE_DETAIL
                                        : MetricsEvent.TYPE_COLLAPSE));
                        mNotificationRecordLogger.log(
                                NotificationRecordLogger.NotificationEvent.fromExpanded(expanded,
                                        userAction),
                                r);
                    }
                    if (expanded && userAction) {
                        r.recordExpanded();
                        reportUserInteraction(r);
                    }
                    mAssistants.notifyAssistantExpansionChangedLocked(
                            r.getSbn(), r.getNotificationType(), userAction, expanded);
                }
            }
        }

        @Override
        public void onNotificationDirectReplied(String key) {
            exitIdle();
            String packageName = null;
            final int packageImportance;
            synchronized (mNotificationLock) {
                NotificationRecord r = mNotificationsByKey.get(key);
                if (r != null) {
                    packageName = r.getSbn().getPackageName();
                }
            }
            if (packageName != null) {
                packageImportance = getPackageImportanceWithIdentity(packageName);
            } else {
                packageImportance = IMPORTANCE_NONE;
            }
            synchronized (mNotificationLock) {
                NotificationRecord r = mNotificationsByKey.get(key);
                if (r != null) {
                    // If the notification is already marked as lifetime extended before we record
                    // the new direct reply, there must have been a previous lifetime extension
                    // event, and the app has already cancelled the notification, or does not
                    // respond to direct replies with updates. So we need to update System UI
                    // immediately.
                    // We need to reset this to allow the notif to be updated again.
                    r.setCanceledAfterLifetimeExtension(false);
                    maybeNotifySystemUiListenerLifetimeExtendedLocked(
                            r, r.getSbn().getPackageName(), packageImportance);

                    r.recordDirectReplied();
                    mMetricsLogger.write(r.getLogMaker()
                            .setCategory(MetricsEvent.NOTIFICATION_DIRECT_REPLY_ACTION)
                            .setType(MetricsEvent.TYPE_ACTION));
                    mNotificationRecordLogger.log(
                            NotificationRecordLogger.NotificationEvent.NOTIFICATION_DIRECT_REPLIED,
                            r);
                    reportUserInteraction(r);
                    mAssistants.notifyAssistantNotificationDirectReplyLocked(r);
                }
            }
        }

        @Override
        public void onNotificationSmartSuggestionsAdded(String key, int smartReplyCount,
                int smartActionCount, boolean generatedByAssistant, boolean editBeforeSending) {
            synchronized (mNotificationLock) {
                NotificationRecord r = mNotificationsByKey.get(key);
                if (r != null) {
                    r.setNumSmartRepliesAdded(smartReplyCount);
                    r.setNumSmartActionsAdded(smartActionCount);
                    r.setSuggestionsGeneratedByAssistant(generatedByAssistant);
                    r.setEditChoicesBeforeSending(editBeforeSending);
                }
            }
        }

        @Override
        public void onNotificationSmartReplySent(String key, int replyIndex, CharSequence reply,
                int notificationLocation, boolean modifiedBeforeSending) {
            String packageName = null;
            final int packageImportance;
            synchronized (mNotificationLock) {
                NotificationRecord r = mNotificationsByKey.get(key);
                if (r != null) {
                    packageName = r.getSbn().getPackageName();
                }
            }
            if (packageName != null) {
                packageImportance = getPackageImportanceWithIdentity(packageName);
            } else {
                packageImportance = IMPORTANCE_NONE;
            }
            synchronized (mNotificationLock) {
                NotificationRecord r = mNotificationsByKey.get(key);
                if (r != null) {
                    // If the notification is already marked as lifetime extended before we record
                    // the new direct reply, there must have been a previous lifetime extension
                    // event, and the app has already cancelled the notification, or does not
                    // respond to direct replies with updates. So we need to update System UI
                    // immediately.
                    // We need to reset this to allow the notif to be updated again.
                    r.setCanceledAfterLifetimeExtension(false);
                    maybeNotifySystemUiListenerLifetimeExtendedLocked(
                            r, r.getSbn().getPackageName(), packageImportance);

                    r.recordSmartReplied();
                    LogMaker logMaker = r.getLogMaker()
                            .setCategory(MetricsEvent.SMART_REPLY_ACTION)
                            .setSubtype(replyIndex)
                            .addTaggedData(
                                    MetricsEvent.NOTIFICATION_SMART_SUGGESTION_ASSISTANT_GENERATED,
                                    r.getSuggestionsGeneratedByAssistant() ? 1 : 0)
                            .addTaggedData(MetricsEvent.NOTIFICATION_LOCATION,
                                    notificationLocation)
                            .addTaggedData(
                                    MetricsEvent.NOTIFICATION_SMART_REPLY_EDIT_BEFORE_SENDING,
                                    r.getEditChoicesBeforeSending() ? 1 : 0)
                            .addTaggedData(
                                    MetricsEvent.NOTIFICATION_SMART_REPLY_MODIFIED_BEFORE_SENDING,
                                    modifiedBeforeSending ? 1 : 0);
                    mMetricsLogger.write(logMaker);
                    if (r.getSmartReplies() != null
                            && r.getSmartReplies().size() > replyIndex
                            && isAnimatedReply(r.getSmartReplies().get(replyIndex))) {
                        mNotificationRecordLogger.log(
                                NotificationRecordLogger.NotificationEvent
                                    .NOTIFICATION_ANIMATED_REPLIED, r);
                    }
                    mNotificationRecordLogger.log(
                            NotificationRecordLogger.NotificationEvent.NOTIFICATION_SMART_REPLIED,
                            r);
                    // Treat clicking on a smart reply as a user interaction.
                    reportUserInteraction(r);
                    mAssistants.notifyAssistantSuggestedReplySent(
                            r.getSbn(), r.getNotificationType(), reply,
                            r.getSuggestionsGeneratedByAssistant());
                }
            }
        }

        @Override
        public void onNotificationSettingsViewed(String key) {
            synchronized (mNotificationLock) {
                NotificationRecord r = mNotificationsByKey.get(key);
                if (r != null) {
                    r.recordViewedSettings();
                }
            }
        }

        @Override
        public void onNotificationBubbleChanged(String key, boolean isBubble, int bubbleFlags) {
            synchronized (mNotificationLock) {
                NotificationRecord r = mNotificationsByKey.get(key);
                if (r != null) {
                    if (!isBubble) {
                        // This happens if the user has dismissed the bubble but the notification
                        // is still active in the shade, enqueuing would create a bubble since
                        // the notification is technically allowed. Flip the flag so that
                        // apps querying noMan will know that their notification is not showing
                        // as a bubble.
                        r.getNotification().flags &= ~FLAG_BUBBLE;
                        r.setFlagBubbleRemoved(true);
                    } else {
                        // Enqueue will trigger resort & if the flag is allowed to be true it'll
                        // be applied there.
                        r.getNotification().flags |= FLAG_ONLY_ALERT_ONCE;
                        r.setFlagBubbleRemoved(false);
                        if (r.getNotification().getBubbleMetadata() != null) {
                            r.getNotification().getBubbleMetadata().setFlags(bubbleFlags);
                        }
                        // Force isAppForeground true here, because for sysui's purposes we
                        // want to adjust the flag behaviour.
                        mHandler.post(new EnqueueNotificationRunnable(r.getUser().getIdentifier(),
                                r, /* isAppForeground= */ true , /* isAppProvided= */ false,
                                mPostNotificationTrackerFactory.newTracker(null)));
                    }
                }
            }
        }

        @Override
        public void onBubbleMetadataFlagChanged(String key, int flags) {
            synchronized (mNotificationLock) {
                NotificationRecord r = mNotificationsByKey.get(key);
                if (r != null) {
                    Notification.BubbleMetadata data = r.getNotification().getBubbleMetadata();
                    if (data == null) {
                        // No data, do nothing
                        return;
                    }

                    if (flags != data.getFlags()) {
                        int changedFlags = data.getFlags() ^ flags;
                        if ((changedFlags & FLAG_SUPPRESS_NOTIFICATION) != 0) {
                            // Suppress notification flag changed, clear any effects
                            mAttentionHelper.clearEffectsLocked(key);
                        }
                        data.setFlags(flags);
                        // Shouldn't alert again just because of a flag change.
                        r.getNotification().flags |= FLAG_ONLY_ALERT_ONCE;
                        // Force isAppForeground true here, because for sysui's purposes we
                        // want to be able to adjust the flag behaviour.
                        mHandler.post(
                                new EnqueueNotificationRunnable(r.getUser().getIdentifier(), r,
                                        /* foreground= */ true, /* isAppProvided= */ false,
                                        mPostNotificationTrackerFactory.newTracker(null)));
                    }
                }
            }
        }

        /**
         * Grant permission to read the specified URI to the package specified in the
         * NotificationRecord associated with the given key. The callingUid represents the UID of
         * SystemUI from which this method is being called.
         *
         * For this to work, SystemUI must have permission to read the URI when running under the
         * user associated with the NotificationRecord, and this grant will fail when trying
         * to grant URI permissions across users.
         */
        @Override
        public void grantInlineReplyUriPermission(String key, Uri uri, UserHandle user,
                String packageName, int callingUid) {
            synchronized (mNotificationLock) {
                InlineReplyUriRecord r = mInlineReplyRecordsByKey.get(key);
                if (r == null) {
                    InlineReplyUriRecord newRecord = new InlineReplyUriRecord(
                            mUgmInternal.newUriPermissionOwner("INLINE_REPLY:" + key),
                            user,
                            packageName,
                            key);
                    r = newRecord;
                    mInlineReplyRecordsByKey.put(key, r);
                }
                IBinder owner = r.getPermissionOwner();
                int uid = callingUid;
                int userId = r.getUserId();
                if (UserHandle.getUserId(uid) != userId) {
                    try {
                        final String[] pkgs = mPackageManager.getPackagesForUid(callingUid);
                        if (pkgs == null) {
                            Log.e(TAG, "Cannot grant uri permission to unknown UID: "
                                    + callingUid);
                        }
                        final String pkg = pkgs[0]; // Get the SystemUI package
                        // Find the UID for SystemUI for the correct user
                        uid =  mPackageManager.getPackageUid(pkg, 0, userId);
                    } catch (RemoteException re) {
                        Log.e(TAG, "Cannot talk to package manager", re);
                    }
                }
                r.addUri(uri);
                grantUriPermission(owner, uri, uid, r.getPackageName(), userId);
            }
        }

        @Override
        /**
         * Clears inline URI permission grants by destroying the permission owner for the specified
         * notification.
         */
        public void clearInlineReplyUriPermissions(String key, int callingUid) {
            synchronized (mNotificationLock) {
                InlineReplyUriRecord uriRecord = mInlineReplyRecordsByKey.get(key);
                if (uriRecord != null) {
                    destroyPermissionOwner(uriRecord.getPermissionOwner(), uriRecord.getUserId(),
                            "INLINE_REPLY: " + uriRecord.getKey());
                    mInlineReplyRecordsByKey.remove(key);
                }
            }
        }

        @Override
        public void onNotificationFeedbackReceived(String key, Bundle feedback) {
            exitIdle();
            synchronized (mNotificationLock) {
                NotificationRecord r = mNotificationsByKey.get(key);
                if (r == null) {
                    if (DBG) Slog.w(TAG, "No notification with key: " + key);
                    return;
                }
                mAssistants.notifyAssistantFeedbackReceived(r, feedback);
            }
        }
    };

    // Returns whether the notifUser is either the same user as targetUser or a profile of
    // targetUser (if targetUser is a full user).
    private boolean isSameUserOrProfile(@UserIdInt int notifUser, @UserIdInt int targetUser) {
        return notifUser == targetUser || mUmInternal.getProfileParentId(notifUser) == targetUser;
    }

    private void applyNotificationUpdateForUser(final int userId,
            NotificationUpdate notificationUpdate) {
        applyUpdateForNotificationsFiltered((r) -> r.getUserId() == userId,
                notificationUpdate);
    }

    private void applyNotificationUpdateForUserProfiles(final int userId,
            NotificationUpdate notificationUpdate) {
        applyUpdateForNotificationsFiltered(
                (r) -> isSameUserOrProfile(r.getUserId(), userId), notificationUpdate);
    }

    private void applyNotificationUpdateForUid(final int userId, @NonNull final String pkg,
            NotificationUpdate notificationUpdate) {
        applyUpdateForNotificationsFiltered((r) ->
                r.getUserId() == userId
                && Objects.equals(r.getSbn().getPackageName(), pkg),
                notificationUpdate);
    }

    private void applyNotificationUpdateForUserProfilesAndChannelType(final int userId,
            final int bundleType, NotificationUpdate notificationUpdate) {
        final String bundleChannelId = NotificationChannel.getChannelIdForBundleType(bundleType);
        applyUpdateForNotificationsFiltered(
                (r) -> isSameUserOrProfile(r.getUserId(), userId)
                                && r.getChannel() != null
                                && Objects.equals(bundleChannelId, r.getChannel().getId()),
                notificationUpdate);
    }

    private void applyNotificationUpdateForUserProfilesAndType(final int userId,
            final int bundleType, NotificationUpdate notificationUpdate) {
        applyUpdateForNotificationsFiltered(
                (r) -> isSameUserOrProfile(r.getUserId(), userId)
                        && r.getBundleType() == bundleType,
                notificationUpdate);
    }

    private static boolean isAnimatedReply(CharSequence reply) {
        if (reply instanceof Spanned) {
            Spanned spanned = (Spanned) reply;
            Annotation[] annotations = spanned.getSpans(0, reply.length(), Annotation.class);
            if (annotations != null) { // Add null check
                for (Annotation annotation : annotations) {
                    if ("isAnimatedReply".equals(annotation.getKey())
                            && "1".equals(annotation.getValue())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @VisibleForTesting
    void unclassifyNotification(final String key) {
        if (!notificationRegroupOnClassification()) {
            return;
        }
        synchronized (mNotificationLock) {
            NotificationRecord r = mNotificationsByKey.get(key);
            if (r == null) {
                return;
            }
            unclassifyNotificationLocked(r, true);
        }
    }

    @VisibleForTesting
    void reclassifyNotification(String key) {
        if (!notificationRegroupOnClassification()) {
            return;
        }
        synchronized (mNotificationLock) {
            NotificationRecord r = mNotificationsByKey.get(key);
            if (r == null) {
                return;
            }
            reclassifyNotificationLocked(r, true);
        }
    }

    @GuardedBy("mNotificationLock")
    private void unclassifyNotificationLocked(@NonNull final NotificationRecord r,
            boolean isPosted) {
        if (DBG) {
            Slog.v(TAG, "unclassifyNotification: " + r);
        }
        // Only NotificationRecord's mChannel is updated when bundled, the Notification
        // mChannelId will always be the original channel.
        String origChannelId = r.getNotification().getChannelId();
        NotificationChannel originalChannel = mPreferencesHelper.getNotificationChannel(
                r.getSbn().getPackageName(), r.getUid(), origChannelId, false);
        String currChannelId = r.getChannel().getId();
        boolean isClassified = r.getChannel().isBundleChannel();
        if (originalChannel != null && !origChannelId.equals(currChannelId) && isClassified) {
            final Bundle signals = new Bundle();
            signals.putParcelable(KEY_UNCLASSIFY, originalChannel);
            Adjustment adjustment = new Adjustment(r.getSbn().getPackageName(), r.getKey(), signals,
                    "unclassify", r.getSbn().getUserId());
            r.addAdjustment(adjustment);
            r.setHadGroupSummaryWhenUnclassified(
                    GroupHelper.isOriginalGroupSummaryPresent(r, mSummaryByGroupKey));
            mRankingHandler.requestSort();
        }
    }

    @GuardedBy("mNotificationLock")
    private void unsummarizeNotificationLocked(@NonNull final NotificationRecord r,
            boolean isPosted) {
        Bundle signals = new Bundle();
        signals.putString(KEY_SUMMARIZATION, null);
        Adjustment adjustment = new Adjustment(r.getSbn().getPackageName(), r.getKey(), signals, "",
                r.getSbn().getUserId());
        r.addAdjustment(adjustment);
        mRankingHandler.requestSort();

    }

    @GuardedBy("mNotificationLock")
    private void reclassifyNotificationLocked(@NonNull final NotificationRecord r,
            final boolean isPosted) {
        if (DBG) {
            Slog.v(TAG, "reclassifyNotification: " + r);
        }

        boolean isClassified = r.getChannel().isBundleChannel();
        if (r.getBundleType() != Adjustment.TYPE_OTHER && !isClassified) {
            final Bundle classifBundle = new Bundle();
            classifBundle.putInt(KEY_TYPE, r.getBundleType());
            Adjustment adj = new Adjustment(r.getSbn().getPackageName(), r.getKey(),
                    classifBundle, "reclassify", r.getUserId());
            applyAdjustmentLocked(r, adj, isPosted);
            mRankingHandler.requestSort();
        } else {
            if (DBG) {
                Slog.w(TAG, "Can't reclassify. No valid bundle type or already bundled: " + r);
            }
        }
    }

    /**
     * Given a filter and a function to update a notification record, runs that function on all
     * enqueued and posted notifications that match the filter
     */
    private void applyUpdateForNotificationsFiltered(Predicate<NotificationRecord> filter,
            NotificationUpdate notificationUpdate) {
        synchronized (mNotificationLock) {
            for (int i = 0; i < mEnqueuedNotifications.size(); i++) {
                final NotificationRecord r = mEnqueuedNotifications.get(i);
                if (filter.test(r)) {
                    notificationUpdate.apply(r, false);
                }
            }

            for (int i = 0; i < mNotificationList.size(); i++) {
                final NotificationRecord r = mNotificationList.get(i);
                if (filter.test(r)) {
                    notificationUpdate.apply(r, true);
                }
            }
        }
    }

    /**
     * Returns {@code true} if the given package is currently in a locked state by App Lock.
     *
     * <p>This method checks the internal state of packages that are locked by App Lock, which is
     * stored in {@link #mAppLockLockedPackages}.
     *
     * @param packageName the package name to check for the App Lock locked state
     * @param userId the user for whom to check the locked state
     * @return {@code true} if the package is locked for the given user, {@code false} otherwise
     */
    @GuardedBy("mNotificationLock")
    public boolean isPackageLockedByAppLockLocked(@NonNull String packageName, int userId) {
        Objects.requireNonNull(packageName);

        return mAppLockLockedPackages.contains(userId) && mAppLockLockedPackages.get(
                userId).contains(packageName);
    }

    @GuardedBy("mNotificationLock")
    private void updateAppLockLockedPackagesLocked(String packageName, int userId, boolean locked) {
        Objects.requireNonNull(packageName);

        ArraySet<String> lockedPackages = mAppLockLockedPackages.get(userId);
        if (locked) {
            if (lockedPackages == null) {
                lockedPackages = new ArraySet<>();
                mAppLockLockedPackages.put(userId, lockedPackages);
            }
            lockedPackages.add(packageName);
        } else {
            if (lockedPackages == null) {
                // The package is not locked, so there is nothing to do.
                return;
            }
            lockedPackages.remove(packageName);
            if (lockedPackages.isEmpty()) {
                mAppLockLockedPackages.remove(userId);
            }
        }
    }

    private interface NotificationUpdate {
        void apply(NotificationRecord r, boolean isPosted);
    }

     private class NotificationManagerPrivateImpl implements NotificationManagerPrivate {
        @Nullable
        @Override
        public NotificationRecord getNotificationByKey(String key) {
            synchronized (mNotificationLock) {
                return mNotificationsByKey.get(key);
            }
        }

        @Override
        public void timeoutNotification(String key) {
            boolean foundNotification = false;
            int uid = 0;
            int pid = 0;
            String packageName = null;
            String tag = null;
            int id = 0;
            int userId = 0;

            synchronized (mNotificationLock) {
                NotificationRecord record = findNotificationByKeyLocked(key);
                if (record != null) {
                    foundNotification = true;
                    uid = record.getUid();
                    pid = record.getSbn().getInitialPid();
                    packageName = record.getSbn().getPackageName();
                    tag = record.getSbn().getTag();
                    id = record.getSbn().getId();
                    userId = record.getUserId();
                }
            }
            if (foundNotification) {
                cancelNotification(uid, pid, packageName, tag, id, 0,
                        FlagChecker.mustNotHave(FLAG_FOREGROUND_SERVICE | FLAG_USER_INITIATED_JOB
                                | FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY | FLAG_COMPUTER_CONTROL),
                        true, userId, REASON_TIMEOUT, null);
            }
        }

         @Override
         public void triggerPolicyFileWrite() {
            handleSavePolicyFile();
         }

         @Override
         public void triggerRulesFileWrite() {
            handleSaveRulesFile();
         }
     };

    @VisibleForTesting
    void logSmartSuggestionsVisible(NotificationRecord r, int notificationLocation) {
        // If the newly visible notification has smart suggestions
        // then log that the user has seen them.
        if ((r.getNumSmartRepliesAdded() > 0 || r.getNumSmartActionsAdded() > 0)
                && !r.hasSeenSmartReplies()) {
            boolean isAnimatedReply = false;
            boolean isAnimatedAction = false;
            r.setSeenSmartReplies(true);
            LogMaker logMaker = r.getLogMaker()
                    .setCategory(MetricsEvent.SMART_REPLY_VISIBLE)
                    .addTaggedData(MetricsEvent.NOTIFICATION_SMART_REPLY_COUNT,
                            r.getNumSmartRepliesAdded())
                    .addTaggedData(MetricsEvent.NOTIFICATION_SMART_ACTION_COUNT,
                            r.getNumSmartActionsAdded())
                    .addTaggedData(
                            MetricsEvent.NOTIFICATION_SMART_SUGGESTION_ASSISTANT_GENERATED,
                            r.getSuggestionsGeneratedByAssistant() ? 1 : 0)
                    // The fields in the NotificationVisibility.NotificationLocation enum map
                    // directly to the fields in the MetricsEvent.NotificationLocation enum.
                    .addTaggedData(MetricsEvent.NOTIFICATION_LOCATION, notificationLocation)
                    .addTaggedData(
                            MetricsEvent.NOTIFICATION_SMART_REPLY_EDIT_BEFORE_SENDING,
                            r.getEditChoicesBeforeSending() ? 1 : 0);
            mMetricsLogger.write(logMaker);
            // TODO (b/421296838): notification metrics log not accurate.
            if (r.getSmartReplies() != null) {
                for (CharSequence reply : r.getSmartReplies()) {
                    if (isAnimatedReply(reply)) {
                        isAnimatedReply = true;
                        break;
                    }
                }
            }
            if (r.getNotification() != null && r.getNotification().actions != null) {
                for (int actionIndex = 0;
                        actionIndex < r.getNotification().actions.length;
                        actionIndex++) {
                    Action action = r.getNotification().actions[actionIndex];
                    if (action != null
                            && action.getExtras() != null
                            && action.getExtras()
                                    .getBoolean(Notification.Action.EXTRA_IS_ANIMATED, false)) {
                        isAnimatedAction = true;
                        break;
                    }
                }
            }
            if (isAnimatedReply) {
                mNotificationRecordLogger.log(
                        NotificationRecordLogger.NotificationEvent
                            .NOTIFICATION_ANIMATED_REPLY_VISIBLE, r);
            }
            if (isAnimatedAction) {
              mNotificationRecordLogger.log(
                        NotificationRecordLogger.NotificationEvent
                            .NOTIFICATION_ANIMATED_ACTION_VISIBLE, r);
            }
            mNotificationRecordLogger.log(
                    NotificationRecordLogger.NotificationEvent.NOTIFICATION_SMART_REPLY_VISIBLE,
                    r);
        }
    }

    protected void logSensitiveAdjustmentReceived(boolean hasPosted,
            boolean hasSensitiveContent, int lifespanMs) {
        FrameworkStatsLog.write(FrameworkStatsLog.SENSITIVE_NOTIFICATION_REDACTION, hasPosted,
                hasSensitiveContent, lifespanMs);
    }

    protected void logClassificationChannelAdjustmentReceived(NotificationRecord r,
                                                              boolean hasPosted,
                                                              int classification) {
        // Note that this value of isAlerting does not fully indicate whether a notif
        // would make a sound or HUN on device; it is an approximation for metrics.
        boolean isAlerting = r.getChannel().getImportance() >= IMPORTANCE_DEFAULT;
        int instanceId = r.getSbn().getInstanceId() == null
                ? 0 : r.getSbn().getInstanceId().getId();

        FrameworkStatsLog.write(FrameworkStatsLog.NOTIFICATION_CHANNEL_CLASSIFICATION,
                hasPosted, isAlerting, classification,
                r.getLifespanMs(System.currentTimeMillis()),
                NotificationReportedEvent.NOTIFICATION_ADJUSTED.getId(),
                instanceId, r.getUid());
    }

    protected final BroadcastReceiver mLocaleChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_LOCALE_CHANGED.equals(intent.getAction())) {
                // update system notification channels
                SystemNotificationChannels.createAll(context);
                mZenModeHelper.updateZenRulesOnLocaleChange();
                mPreferencesHelper.onLocaleChanged(context, ActivityManager.getCurrentUser());
            }
        }
    };

    private final BroadcastReceiver mRestoreReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SETTING_RESTORED.equals(intent.getAction())) {
                try {
                    String element = intent.getStringExtra(Intent.EXTRA_SETTING_NAME);
                    String newValue = intent.getStringExtra(Intent.EXTRA_SETTING_NEW_VALUE);
                    int restoredFromSdkInt = intent.getIntExtra(
                            Intent.EXTRA_SETTING_RESTORED_FROM_SDK_INT, 0);
                    mListeners.onSettingRestored(
                            element, newValue, restoredFromSdkInt, getSendingUserId());
                    mConditionProviders.onSettingRestored(
                            element, newValue, restoredFromSdkInt, getSendingUserId());
                } catch (Exception e) {
                    Slog.wtf(TAG, "Cannot restore managed services from settings", e);
                }
            }
        }
    };

    private final BroadcastReceiver mPackageIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }

            boolean queryRemove = false;
            boolean packageChanged = false;
            boolean cancelNotifications = true;
            boolean hideNotifications = false;
            boolean unhideNotifications = false;
            int reason = REASON_PACKAGE_CHANGED;

            if (action.equals(Intent.ACTION_PACKAGE_ADDED)
                    || (queryRemove=action.equals(Intent.ACTION_PACKAGE_REMOVED))
                    || action.equals(Intent.ACTION_PACKAGE_RESTARTED)
                    || (packageChanged=action.equals(Intent.ACTION_PACKAGE_CHANGED))
                    || action.equals(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)
                    || action.equals(Intent.ACTION_PACKAGES_SUSPENDED)
                    || action.equals(Intent.ACTION_PACKAGES_UNSUSPENDED)
                    || action.equals(Intent.ACTION_DISTRACTING_PACKAGES_CHANGED)) {
                int changeUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                        USER_ALL);
                String pkgList[] = null;
                int uidList[] = null;
                boolean removingPackage = queryRemove &&
                        !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                if (DBG) Slog.i(TAG, "action=" + action + " removing=" + removingPackage);
                if (action.equals(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)) {
                    pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                    uidList = intent.getIntArrayExtra(Intent.EXTRA_CHANGED_UID_LIST);
                } else if (action.equals(Intent.ACTION_PACKAGES_SUSPENDED)) {
                    pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                    uidList = intent.getIntArrayExtra(Intent.EXTRA_CHANGED_UID_LIST);
                    cancelNotifications = false;
                    hideNotifications = true;
                } else if (action.equals(Intent.ACTION_PACKAGES_UNSUSPENDED)) {
                    pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                    uidList = intent.getIntArrayExtra(Intent.EXTRA_CHANGED_UID_LIST);
                    cancelNotifications = false;
                    unhideNotifications = true;
                } else if (action.equals(Intent.ACTION_DISTRACTING_PACKAGES_CHANGED)) {
                    final int distractionRestrictions =
                            intent.getIntExtra(Intent.EXTRA_DISTRACTION_RESTRICTIONS,
                                    PackageManager.RESTRICTION_NONE);
                    if ((distractionRestrictions
                            & PackageManager.RESTRICTION_HIDE_NOTIFICATIONS) != 0) {
                        pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                        uidList = intent.getIntArrayExtra(Intent.EXTRA_CHANGED_UID_LIST);
                        cancelNotifications = false;
                        hideNotifications = true;
                    } else {
                        pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                        uidList = intent.getIntArrayExtra(Intent.EXTRA_CHANGED_UID_LIST);
                        cancelNotifications = false;
                        unhideNotifications = true;
                    }
                } else {
                    Uri uri = intent.getData();
                    if (uri == null) {
                        return;
                    }
                    String pkgName = uri.getSchemeSpecificPart();
                    if (pkgName == null) {
                        return;
                    }
                    if (packageChanged) {
                        // We cancel notifications for packages which have just been disabled
                        try {
                            final int enabled = mPackageManager.getApplicationEnabledSetting(
                                    pkgName,
                                    changeUserId != USER_ALL ? changeUserId :
                                            USER_SYSTEM);
                            if (enabled == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                    || enabled == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                                cancelNotifications = false;
                            }
                        } catch (IllegalArgumentException e) {
                            // Package doesn't exist; probably racing with uninstall.
                            // cancelNotifications is already true, so nothing to do here.
                            if (DBG) {
                                Slog.i(TAG, "Exception trying to look up app enabled setting", e);
                            }
                        } catch (RemoteException e) {
                            // Failed to talk to PackageManagerService Should never happen!
                        }
                    }
                    pkgList = new String[]{pkgName};
                    uidList = new int[] {intent.getIntExtra(Intent.EXTRA_UID, -1)};
                }
                if (pkgList != null && (pkgList.length > 0)) {
                    if (cancelNotifications) {
                        for (String pkgName : pkgList) {
                            cancelAllNotificationsInt(MY_UID, MY_PID, pkgName, null, 0, 0,
                                    changeUserId, reason);
                        }
                    } else if (hideNotifications && uidList != null && (uidList.length > 0)) {
                        hideNotificationsForPackages(pkgList, uidList);
                    } else if (unhideNotifications && uidList != null && (uidList.length > 0)) {
                        unhideNotificationsForPackages(pkgList, uidList);
                    }
                }

                if (queryRemove && !removingPackage) {
                    // For PACKAGE_REMOVED with EXTRA_REPLACING, this will be immediately
                    // followed by a PACKAGE_ADDED, so this one is safe to ignore.
                    return;
                }

                mHandler.scheduleOnPackageChanged(removingPackage, changeUserId, pkgList, uidList);
            }
        }
    };

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals(Intent.ACTION_USER_STOPPED)) {
                int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                if (userHandle >= 0) {
                    cancelAllNotificationsInt(MY_UID, MY_PID, null, null, 0, 0, userHandle,
                            REASON_USER_STOPPED);
                    mConditionProviders.onUserStopped(userHandle);
                    mListeners.onUserStopped(userHandle);
                    mAssistants.onUserStopped(userHandle);
                }
            } else if (action.equals(Intent.ACTION_PROFILE_UNAVAILABLE)) {
                int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                if (userHandle >= 0) {
                    cancelAllNotificationsInt(MY_UID, MY_PID, null, null, 0, 0, userHandle,
                            REASON_PROFILE_TURNED_OFF);
                    List<NotificationRecord> snoozed = mSnoozeHelper.clearData(userHandle);
                    for (NotificationRecord r : snoozed) {
                        markOffloadedBitmapsForDeletion(r);
                    }
                }
            } else if (action.equals(Intent.ACTION_USER_SWITCHED)) {
                if (!Flags.useSsmUserSwitchSignal()) {
                    final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, USER_NULL);
                    mUserProfiles.updateCache(context);
                    if (!mUserProfiles.isProfileUser(userId, context)) {
                        // reload per-user settings
                        mSettingsObserver.update(null);
                        // Refresh managed services
                        mConditionProviders.onUserSwitched(userId);
                        mListeners.onUserSwitched(userId);
                        mZenModeHelper.onUserSwitched(userId);
                        mPreferencesHelper.syncHasPriorityChannels();
                    }
                    // assistant is the only thing that cares about managed profiles specifically
                    mAssistants.onUserSwitched(userId);
                }
            } else if (action.equals(Intent.ACTION_USER_ADDED)) {
                final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, USER_NULL);
                if (userId != USER_NULL) {
                    mUserProfiles.updateCache(context);
                    allowDefaultApprovedServices(userId,
                            mUserProfiles.isProfileUser(userId, context));
                    mHistoryManager.onUserAdded(userId);
                    mSettingsObserver.update(null, userId);
                    if (nmContextualDisplayLaunch()) {
                        mNotificationRuleManager.onUserAdded(userId);
                    }
                }
            } else if (action.equals(Intent.ACTION_USER_REMOVED)) {
                final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, USER_NULL);
                mUserProfiles.updateCache(context);
                mZenModeHelper.onUserRemoved(userId);
                mPreferencesHelper.onUserRemoved(userId);
                mListeners.onUserRemoved(userId);
                mConditionProviders.onUserRemoved(userId);
                mAssistants.onUserRemoved(userId);
                mHistoryManager.onUserRemoved(userId);
                mPreferencesHelper.syncHasPriorityChannels();
                handleSavePolicyFile();
            } else if (action.equals(Intent.ACTION_USER_UNLOCKED)) {
                final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, USER_NULL);
                mUserProfiles.updateCache(context);
                mAssistants.onUserUnlocked(userId);
                if (!mUserProfiles.isProfileUser(userId, context)) {
                    mConditionProviders.onUserUnlocked(userId);
                    mListeners.onUserUnlocked(userId);
                }
            }
        }
    };

    private final class SettingsObserver extends ContentObserver {
        private final Uri NOTIFICATION_BADGING_URI
                = Secure.getUriFor(Secure.NOTIFICATION_BADGING);
        private final Uri NOTIFICATION_BUBBLES_URI
                = Secure.getUriFor(Secure.NOTIFICATION_BUBBLES);
        private final Uri NOTIFICATION_RATE_LIMIT_URI
                = Settings.Global.getUriFor(Settings.Global.MAX_NOTIFICATION_ENQUEUE_RATE);
        private final Uri NOTIFICATION_HISTORY_ENABLED
                = Secure.getUriFor(Secure.NOTIFICATION_HISTORY_ENABLED);
        private final Uri NOTIFICATION_SHOW_MEDIA_ON_QUICK_SETTINGS_URI
                = Settings.Global.getUriFor(Settings.Global.SHOW_MEDIA_ON_QUICK_SETTINGS);
        private final Uri LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS
                = Secure.getUriFor(
                        Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS);
        private final Uri LOCK_SCREEN_SHOW_NOTIFICATIONS
                = Secure.getUriFor(Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS);
        private final Uri SHOW_NOTIFICATION_SNOOZE
                = Secure.getUriFor(Secure.SHOW_NOTIFICATION_SNOOZE);
        private final Uri REDACT_OTP_NOTIFICATIONS = Settings.Global.getUriFor(
                Settings.Global.REDACT_OTP_NOTIFICATIONS_FROM_UNTRUSTED_LISTENERS);

        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = getContext().getContentResolver();
            resolver.registerContentObserver(NOTIFICATION_BADGING_URI,
                    false, this, USER_ALL);
            resolver.registerContentObserver(NOTIFICATION_RATE_LIMIT_URI,
                    false, this, USER_ALL);
            resolver.registerContentObserver(NOTIFICATION_BUBBLES_URI,
                    false, this, USER_ALL);
            resolver.registerContentObserver(NOTIFICATION_HISTORY_ENABLED,
                    false, this, USER_ALL);
            resolver.registerContentObserver(NOTIFICATION_SHOW_MEDIA_ON_QUICK_SETTINGS_URI,
                    false, this, USER_ALL);

            resolver.registerContentObserver(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS,
                    false, this, USER_ALL);
            resolver.registerContentObserver(LOCK_SCREEN_SHOW_NOTIFICATIONS,
                    false, this, USER_ALL);

            resolver.registerContentObserver(SHOW_NOTIFICATION_SNOOZE,
                    false, this, USER_ALL);
            resolver.registerContentObserver(REDACT_OTP_NOTIFICATIONS,
                    false, this, USER_ALL);

            update(null);
        }

        void destroy() {
            getContext().getContentResolver().unregisterContentObserver(this);
        }

        @Override public void onChange(boolean selfChange, Uri uri, int userId) {
            update(uri);
        }

        public void update(Uri uri) {
            ContentResolver resolver = getContext().getContentResolver();
            if (uri == null || NOTIFICATION_RATE_LIMIT_URI.equals(uri)) {
                mMaxPackageEnqueueRate = Settings.Global.getFloat(resolver,
                            Settings.Global.MAX_NOTIFICATION_ENQUEUE_RATE, mMaxPackageEnqueueRate);
            }
            if (uri == null || NOTIFICATION_BADGING_URI.equals(uri)) {
                mPreferencesHelper.updateBadgingEnabled();
            }
            if (uri == null || NOTIFICATION_BUBBLES_URI.equals(uri)) {
                mPreferencesHelper.updateBubblesEnabled();
            }
            if (uri == null || NOTIFICATION_HISTORY_ENABLED.equals(uri)) {
                for (UserInfo userInfo : mUm.getUsers()) {
                    update(uri, userInfo.id);
                }
            }
            if (uri == null || NOTIFICATION_SHOW_MEDIA_ON_QUICK_SETTINGS_URI.equals(uri)) {
                mPreferencesHelper.updateMediaNotificationFilteringEnabled();
            }
            if (uri == null || LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS.equals(uri)) {
                mPreferencesHelper.updateLockScreenPrivateNotifications();
            }
            if (uri == null || LOCK_SCREEN_SHOW_NOTIFICATIONS.equals(uri)) {
                mPreferencesHelper.updateLockScreenShowNotifications();
            }
            if (SHOW_NOTIFICATION_SNOOZE.equals(uri)) {
                final boolean snoozeEnabled = Secure.getIntForUser(resolver,
                        Secure.SHOW_NOTIFICATION_SNOOZE, 0, UserHandle.USER_CURRENT)
                        != 0;
                if (!snoozeEnabled) {
                    unsnoozeAll();
                }
            }
            if (REDACT_OTP_NOTIFICATIONS.equals(uri)) {
                mRedactOtpNotifications = Settings.Global.getInt(resolver,
                        Settings.Global.REDACT_OTP_NOTIFICATIONS_FROM_UNTRUSTED_LISTENERS, 1) != 0;
            }
        }

        public void update(Uri uri, int userId) {
            ContentResolver resolver = getContext().getContentResolver();
            if (uri == null || NOTIFICATION_HISTORY_ENABLED.equals(uri)) {
                mArchive.updateHistoryEnabled(userId,
                        Secure.getIntForUser(resolver,
                                Secure.NOTIFICATION_HISTORY_ENABLED, 0,
                                userId) == 1);
                // note: this setting is also handled in NotificationHistoryManager
            }
        }
    }

    private final ConfigurableParameters mConfigurableParameters = new ConfigurableParameters();
    private SettingsObserver mSettingsObserver;
    protected ZenModeHelper mZenModeHelper;

    protected class StrongAuthTracker extends LockPatternUtils.StrongAuthTracker {

        SparseBooleanArray mUserInLockDownMode = new SparseBooleanArray();

        StrongAuthTracker(Context context) {
            super(context);
        }

        private boolean containsFlag(int haystack, int needle) {
            return (haystack & needle) != 0;
        }

        // Return whether the user is in lockdown mode.
        // If the flag is not set, we assume the user is not in lockdown.
        public boolean isInLockDownMode(int userId) {
            return mUserInLockDownMode.get(userId, false);
        }

        @Override
        public synchronized void onStrongAuthRequiredChanged(int userId) {
            boolean userInSecureLockDevice = false;
            if (secureLockDevice()) {
                userInSecureLockDevice = containsFlag(getStrongAuthForUser(userId),
                        PRIMARY_AUTH_REQUIRED_FOR_SECURE_LOCK_DEVICE)
                        || containsFlag(getStrongAuthForUser(userId),
                        STRONG_BIOMETRIC_AUTH_REQUIRED_FOR_SECURE_LOCK_DEVICE);
            }

            boolean userInLockDownModeNext = containsFlag(getStrongAuthForUser(userId),
                    STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN) || userInSecureLockDevice;

            // Nothing happens if the lockdown mode of userId keeps the same.
            if (userInLockDownModeNext == isInLockDownMode(userId)) {
                return;
            }

            // When the lockdown mode is changed, we perform the following steps.
            // If the userInLockDownModeNext is true, all the function calls to
            // notifyPostedLocked and notifyRemovedLocked will not be executed.
            // The cancelNotificationsWhenEnterLockDownMode calls notifyRemovedLocked
            // and postNotificationsWhenExitLockDownMode calls notifyPostedLocked.
            // So we shall call cancelNotificationsWhenEnterLockDownMode before
            // we set mUserInLockDownMode as true.
            // On the other hand, if the userInLockDownModeNext is false, we shall call
            // postNotificationsWhenExitLockDownMode after we put false into mUserInLockDownMode
            if (userInLockDownModeNext) {
                cancelNotificationsWhenEnterLockDownMode(userId);
            }
            mUserInLockDownMode.put(userId, userInLockDownModeNext);

            if (!userInLockDownModeNext) {
                postNotificationsWhenExitLockDownMode(userId);
            }
        }
    }

    private StrongAuthTracker mStrongAuthTracker;

    public NotificationManagerService(Context context) {
        super(context);
        if (com.android.server.flags.Flags.parallelizeOnbootphase()) {
            setBootPhaseSerial(SystemService.PHASE_SYSTEM_SERVICES_READY);
        }
        Notification.processAllowlistToken = ALLOWLIST_TOKEN;

    }

    // TODO - replace these methods with new fields in the VisibleForTesting constructor
    @VisibleForTesting
    void setStrongAuthTracker(StrongAuthTracker strongAuthTracker) {
        mStrongAuthTracker = strongAuthTracker;
    }

    @VisibleForTesting
    void setLockPatternUtils(LockPatternUtils lockUtils) {
        mLockUtils = lockUtils;
    }

    @VisibleForTesting
    ShortcutHelper getShortcutHelper() {
        return mShortcutHelper;
    }

    @VisibleForTesting
    void setShortcutHelper(ShortcutHelper helper) {
        mShortcutHelper = helper;
    }

    @VisibleForTesting
    int getNotificationRecordCount() {
        synchronized (mNotificationLock) {
            int count = mNotificationList.size() + mNotificationsByKey.size()
                    + mSummaryByGroupKey.size() + mEnqueuedNotifications.size();
            // subtract duplicates
            for (NotificationRecord posted : mNotificationList) {
                if (mNotificationsByKey.containsKey(posted.getKey())) {
                    count--;
                }
                if (posted.getSbn().isGroup() && posted.getNotification().isGroupSummary()) {
                    count--;
                }
            }

            return count;
        }
    }

    @VisibleForTesting
    void clearNotifications() {
        synchronized (mNotificationLock) {
            mEnqueuedNotifications.clear();
            mNotificationList.clear();
            mNotificationsByKey.clear();
            mSummaryByGroupKey.clear();
        }
    }

    @VisibleForTesting
    void addNotification(NotificationRecord r) {
        synchronized (mNotificationLock) {
            mNotificationList.add(r);
            mNotificationsByKey.put(r.getSbn().getKey(), r);
            if (r.getSbn().isGroup()) {
                mSummaryByGroupKey.put(r.getGroupKey(), r);
            }
        }
    }

    @VisibleForTesting
    void addEnqueuedNotification(NotificationRecord r) {
        synchronized (mNotificationLock) {
            mEnqueuedNotifications.add(r);
        }
    }

    @VisibleForTesting
    NotificationRecord getNotificationRecord(String key) {
        synchronized (mNotificationLock) {
            return mNotificationsByKey.get(key);
        }
    }

    @VisibleForTesting
    void setPreferencesHelper(PreferencesHelper prefHelper) { mPreferencesHelper = prefHelper; }

    @VisibleForTesting
    void setZenHelper(ZenModeHelper zenHelper) {
        mZenModeHelper = zenHelper;
    }

    @VisibleForTesting
    void setAttentionHelper(NotificationAttentionHelper nah) {
        mAttentionHelper = nah;
    }

    @VisibleForTesting
    void setIsTelevision(boolean isTelevision) {
        mIsTelevision = isTelevision;
    }

    @VisibleForTesting
    void setTelecomManager(TelecomManager tm) {
        mTelecomManager = tm;
    }

    @VisibleForTesting
    void setNotificationRuleManager(NotificationRuleManager ruleManager) {
        mNotificationRuleManager = ruleManager;
    }

    enum NotificationPostEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "An app posted a notification while cached")
        NOTIFICATION_POSTED_CACHED(2237);

        private final int mId;

        NotificationPostEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }

    // TODO: All tests should use this init instead of the one-off setters above.
    @VisibleForTesting
    void init(WorkerHandler handler, RankingHandler rankingHandler, Handler broadcastsHandler,
            IPackageManager packageManager, PackageManager packageManagerClient,
            LightsManager lightsManager, NotificationListeners notificationListeners,
            NotificationAssistants notificationAssistants, ConditionProviders conditionProviders,
            ICompanionDeviceManager companionManager, SnoozeHelper snoozeHelper,
            NotificationUsageStats usageStats, AtomicFile policyFile, AtomicFile rulesFile,
            ActivityManager activityManager, GroupHelper groupHelper, IActivityManager am,
            ActivityTaskManagerInternal atm, UsageStatsManagerInternal appUsageStats,
            DevicePolicyManagerInternal dpm, IUriGrantsManager ugm,
            UriGrantsManagerInternal ugmInternal, AppOpsManager appOps,
            NotificationHistoryManager historyManager, StatsManager statsManager,
            ActivityManagerInternal ami,
            MultiRateLimiter toastRateLimiter, PermissionHelper permissionHelper,
            UsageStatsManagerInternal usageStatsManagerInternal,
            TelecomManager telecomManager, NotificationChannelLogger channelLogger,
            SystemUiSystemPropertiesFlags.FlagResolver flagResolver,
            PermissionManager permissionManager, PowerManager powerManager,
            PostNotificationTrackerFactory postNotificationTrackerFactory,
            UiEventLogger uiEventLogger, BitmapOffloadInternal bitmapOffloader,
            NotificationListenerStats notificationListenerStats,
            NotificationRecordLogger notificationRecordLogger,
            InstanceIdSequence instanceIdSequence,
            PreferencesHelperFactory preferencesHelperFactory) {
        mHandler = handler;
        mBroadcastsHandler = broadcastsHandler;
        Resources resources = getContext().getResources();
        mMaxPackageEnqueueRate = Settings.Global.getFloat(getContext().getContentResolver(),
                Settings.Global.MAX_NOTIFICATION_ENQUEUE_RATE,
                DEFAULT_MAX_NOTIFICATION_ENQUEUE_RATE);

        mAccessibilityManager =
                (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        mAm = am;
        mAtm = atm;
        mAtm.setBackgroundActivityStartCallback(new NotificationTrampolineCallback());
        mUgm = ugm;
        mUgmInternal = ugmInternal;
        mPackageManager = packageManager;
        mPackageManagerClient = packageManagerClient;
        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        mPermissionManager = permissionManager;
        mPermissionPolicyInternal = LocalServices.getService(PermissionPolicyInternal.class);
        mUmInternal = LocalServices.getService(UserManagerInternal.class);
        mUsageStatsManagerInternal = usageStatsManagerInternal;
        mAppOps = appOps;
        mAppUsageStats = appUsageStats;
        mAlarmManager = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
        mCompanionManager = companionManager;
        mActivityManager = activityManager;
        mAmi = ami;
        mDeviceIdleManager = getContext().getSystemService(DeviceIdleManager.class);
        mDpm = dpm;
        mUm = getContext().getSystemService(UserManager.class);
        mTelecomManager = telecomManager;
        mPowerManager = powerManager;
        mPostNotificationTrackerFactory = postNotificationTrackerFactory;
        mPlatformCompat = IPlatformCompat.Stub.asInterface(
                ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE));
        mUiEventLogger = uiEventLogger;
        mNotificationRecordLogger = notificationRecordLogger;
        mNotificationInstanceIdSequence = instanceIdSequence;

        mStrongAuthTracker = new StrongAuthTracker(getContext());
        String[] extractorNames;
        try {
            extractorNames = resources.getStringArray(R.array.config_notificationSignalExtractors);
        } catch (Resources.NotFoundException e) {
            extractorNames = new String[0];
        }
        mUsageStats = usageStats;
        mMetricsLogger = new MetricsLogger();
        mRankingHandler = rankingHandler;
        mConditionProviders = conditionProviders;
        mNotificationListenerStats = notificationListenerStats;
        mNotificationManagerPrivate = new NotificationManagerPrivateImpl();
        mZenModeHelper = new ZenModeHelper(getContext(), mHandler.getLooper(), Clock.systemUTC(),
                mConditionProviders, flagResolver, new ZenModeEventLogger(mPackageManagerClient));
        mZenModeHelper.addCallback(new ZenModeHelper.Callback() {
            @Override
            public void onConfigApplied() {
                handleSavePolicyFile();
                getContext().sendBroadcastAsUser(
                        new Intent(
                                NotificationManager.ACTION_ZEN_CONFIGURATION_CHANGED_INTERNAL)
                                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT),
                        UserHandle.ALL, android.Manifest.permission.MANAGE_NOTIFICATIONS);
            }

            @Override
            public void onConfigChanged(UserHandle user) {
                handleSavePolicyFile();
            }

            @Override
            public void onZenModeChanged() {
                Binder.withCleanCallingIdentity(() -> {
                    sendRegisteredOnlyBroadcast(ACTION_INTERRUPTION_FILTER_CHANGED);
                    getContext().sendBroadcastAsUser(
                            new Intent(ACTION_INTERRUPTION_FILTER_CHANGED_INTERNAL)
                                    .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT),
                            UserHandle.ALL, android.Manifest.permission.MANAGE_NOTIFICATIONS);
                    synchronized (mNotificationLock) {
                        updateInterruptionFilterLocked();
                    }
                    mRankingHandler.requestSort();
                });
            }

            @Override
            public void onPolicyChanged(Policy newPolicy) {
                Binder.withCleanCallingIdentity(() -> {
                    Intent intent = new Intent(ACTION_NOTIFICATION_POLICY_CHANGED);
                    intent.putExtra(EXTRA_NOTIFICATION_POLICY, newPolicy);
                    sendRegisteredOnlyBroadcast(intent);
                    mRankingHandler.requestSort();
                });
            }

            @Override
            public void onConsolidatedPolicyChanged(Policy newConsolidatedPolicy) {
                Binder.withCleanCallingIdentity(() -> {
                    Intent intent = new Intent(ACTION_CONSOLIDATED_NOTIFICATION_POLICY_CHANGED);
                    intent.putExtra(EXTRA_NOTIFICATION_POLICY, newConsolidatedPolicy);
                    sendRegisteredOnlyBroadcast(intent);

                    mRankingHandler.requestSort();
                });
            }

            @Override
            public void onAutomaticRuleStatusChanged(
                    int userId, String pkg, String id, int status) {
                Binder.withCleanCallingIdentity(() -> {
                    Intent intent = new Intent(ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED);
                    intent.setPackage(pkg);
                    intent.putExtra(EXTRA_AUTOMATIC_ZEN_RULE_ID, id);
                    intent.putExtra(EXTRA_AUTOMATIC_ZEN_RULE_STATUS, status);
                    intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                    getContext().sendBroadcastAsUser(intent, UserHandle.of(userId));
                });
            }
        });
        mPermissionHelper = permissionHelper;
        mNotificationChannelLogger = channelLogger;
        mUserProfiles.updateCache(getContext());
        mPreferencesHelper = preferencesHelperFactory.newHelper(getContext(),
                mPackageManagerClient,
                mRankingHandler,
                mZenModeHelper,
                mPermissionHelper,
                mPermissionManager,
                mNotificationChannelLogger,
                mAppOps,
                mUserProfiles,
                mUgmInternal,
                mShowReviewPermissionsNotification,
                Clock.systemUTC(),
                mNotificationManagerPrivate);
        mNotificationRuleManager = new NotificationRuleManager(
                getContext(), mNotificationManagerPrivate);
        mRankingHelper = new RankingHelper(getContext(), mRankingHandler, mPreferencesHelper,
                mZenModeHelper, mUsageStats, extractorNames, mPlatformCompat, groupHelper,
                mNotificationRuleManager);
        mSnoozeHelper = snoozeHelper;
        mGroupHelper = groupHelper;
        mHistoryManager = historyManager;
        mTtlHelper = new TimeToLiveHelper(mNotificationManagerPrivate, getContext());

        // This is a ManagedServices object that keeps track of the listeners.
        mListeners = notificationListeners;

        // This is a MangedServices object that keeps track of the assistant.
        mAssistants = notificationAssistants;

        // Needs to be set before loadPolicyFile
        mAllowedManagedServicePackages = this::canUseManagedServices;

        mPolicyFile = policyFile;
        mSavePolicyFile = new SaveFileRunnable();
        loadPolicyFile();

        mRulesFile = rulesFile;
        if (nmContextualDisplayLaunch()) {
            loadRulesFile();
        } else {
            mRulesFile.delete();
        }


        mStatusBar = getLocalService(StatusBarManagerInternal.class);
        if (mStatusBar != null) {
            mStatusBar.setNotificationDelegate(mNotificationDelegate);
        }

        mZenModeHelper.initZenMode();
        mInterruptionFilter = mZenModeHelper.getZenModeListenerInterruptionFilter();

        mSettingsObserver = new SettingsObserver(mHandler);

        mArchive = new Archive(resources.getInteger(
                R.integer.config_notificationServiceArchiveSize));

        mIsTelevision = mPackageManagerClient.hasSystemFeature(FEATURE_LEANBACK)
                || mPackageManagerClient.hasSystemFeature(FEATURE_TELEVISION);

        if (listenerHintExemptPackages()) {
            mZenModeHelper.setExemptPackages(
                    getContext().getResources().getStringArray(
                        com.android.internal.R.array.config_priorityOnlyDndExemptPackages),
                    getContext().getResources().getStringArray(
                        com.android.internal.R.array.config_listenerHintsExemptPackages));
        } else {
            mZenModeHelper.setPriorityOnlyDndExemptPackages(
                    getContext().getResources().getStringArray(
                        com.android.internal.R.array.config_priorityOnlyDndExemptPackages));
        }

        mWarnRemoteViewsSizeBytes = getContext().getResources().getInteger(
                com.android.internal.R.integer.config_notificationWarnRemoteViewSizeBytes);
        mStripRemoteViewsSizeBytes = getContext().getResources().getInteger(
                com.android.internal.R.integer.config_notificationStripRemoteViewSizeBytes);

        mMsgPkgsAllowedAsConvos = Set.of(getStringArrayResource(
                com.android.internal.R.array.config_notificationMsgPkgsAllowedAsConvos));
        mDefaultSearchSelectorPkg = getContext().getString(getContext().getResources()
                .getIdentifier("config_defaultSearchSelectorPackageName", "string", "android"));

        mFlagResolver = flagResolver;

        mStatsManager = statsManager;

        mToastRateLimiter = toastRateLimiter;

        mAttentionHelper = new NotificationAttentionHelper(getContext(), mNotificationLock,
                lightsManager, mAccessibilityManager, mPackageManagerClient, mUm,
                usageStats, mNotificationManagerPrivate, mZenModeHelper, flagResolver,
                VibrationStatsWriter.getInstance(getContext()));

        // register for various Intents.
        // If this is called within a test, make sure to unregister the intent receivers by
        // calling onDestroy()
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_STOPPED);
        if (!Flags.useSsmUserSwitchSignal()) {
            filter.addAction(Intent.ACTION_USER_SWITCHED);
        }
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
        filter.addAction(Intent.ACTION_PROFILE_UNAVAILABLE);
        getContext().registerReceiverAsUser(mIntentReceiver, UserHandle.ALL, filter, null, null);

        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_RESTARTED);
        pkgFilter.addDataScheme("package");
        getContext().registerReceiverAsUser(mPackageIntentReceiver, UserHandle.ALL, pkgFilter, null,
                null);

        IntentFilter suspendedPkgFilter = new IntentFilter();
        suspendedPkgFilter.addAction(Intent.ACTION_PACKAGES_SUSPENDED);
        suspendedPkgFilter.addAction(Intent.ACTION_PACKAGES_UNSUSPENDED);
        suspendedPkgFilter.addAction(Intent.ACTION_DISTRACTING_PACKAGES_CHANGED);
        getContext().registerReceiverAsUser(mPackageIntentReceiver, UserHandle.ALL,
                suspendedPkgFilter, null, null);

        IntentFilter sdFilter = new IntentFilter(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        getContext().registerReceiverAsUser(mPackageIntentReceiver, UserHandle.ALL, sdFilter, null,
                null);

        IntentFilter settingsRestoredFilter = new IntentFilter(Intent.ACTION_SETTING_RESTORED);
        getContext().registerReceiver(mRestoreReceiver, settingsRestoredFilter);

        IntentFilter localeChangedFilter = new IntentFilter(Intent.ACTION_LOCALE_CHANGED);
        getContext().registerReceiver(mLocaleChangeReceiver, localeChangedFilter);

        mReviewNotificationPermissionsReceiver = new ReviewNotificationPermissionsReceiver();
        getContext().registerReceiver(mReviewNotificationPermissionsReceiver,
                ReviewNotificationPermissionsReceiver.getFilter(),
                Context.RECEIVER_NOT_EXPORTED);

        mAppOpsListener = new AppOpsManager.OnOpChangedInternalListener() {
            @Override
            public void onOpChanged(@NonNull String op, @NonNull String packageName,
                    int userId) {
                mHandler.post(
                        () -> handleNotificationPermissionChange(packageName, userId));
            }
        };

        mAppOps.startWatchingMode(AppOpsManager.OP_POST_NOTIFICATION, null, mAppOpsListener);

        mBitmapOffloader = bitmapOffloader;
        if (mBitmapOffloader != null) {
            mBitmapOffloader.registerPermissionHandler(BITMAP_SOURCE_NOTIFICATIONS,
                    new BitmapAccessHandler());
        }
        mService = new Stub();
        mInternalService = new NotificationManagerInternalImpl();
    }

    /**
     * Cleanup broadcast receivers change listeners.
     */
    public void onDestroy() {
        if (mIntentReceiver != null) {
            getContext().unregisterReceiver(mIntentReceiver);
        }
        if (mPackageIntentReceiver != null) {
            getContext().unregisterReceiver(mPackageIntentReceiver);
        }
        if (mTtlHelper != null) {
            mTtlHelper.destroy();
        }
        if (mRestoreReceiver != null) {
            getContext().unregisterReceiver(mRestoreReceiver);
        }
        if (mLocaleChangeReceiver != null) {
            getContext().unregisterReceiver(mLocaleChangeReceiver);
        }
        if (mSettingsObserver != null) {
            mSettingsObserver.destroy();
        }
        if (mRoleObserver != null) {
            mRoleObserver.destroy();
        }
        if (mShortcutHelper != null) {
            mShortcutHelper.destroy();
        }
        if (mStatsManager != null) {
            mStatsManager.clearPullAtomCallback(PACKAGE_NOTIFICATION_PREFERENCES);
            mStatsManager.clearPullAtomCallback(PACKAGE_NOTIFICATION_CHANNEL_PREFERENCES);
            mStatsManager.clearPullAtomCallback(PACKAGE_NOTIFICATION_CHANNEL_GROUP_PREFERENCES);
            mStatsManager.clearPullAtomCallback(NOTIFICATION_ADJUSTMENT_PREFERENCES);
            mStatsManager.clearPullAtomCallback(DND_MODE_RULE);
        }
        if (mAppOps != null) {
            mAppOps.stopWatchingMode(mAppOpsListener);
        }
        if (mAlarmManager != null) {
            mAlarmManager.cancelAll();
        }
    }

    protected String[] getStringArrayResource(int key) {
        return getContext().getResources().getStringArray(key);
    }

    @Override
    public void onStart() {
        SnoozeHelper snoozeHelper = new SnoozeHelper(getContext(), (userId, r, muteOnReturn) -> {
            try {
                if (DBG) {
                    Slog.d(TAG, "Reposting " + r.getKey() + " " + muteOnReturn);
                }
                enqueueNotificationInternal(r.getSbn().getPackageName(), r.getSbn().getOpPkg(),
                        r.getSbn().getUid(), r.getSbn().getInitialPid(), r.getSbn().getTag(),
                        r.getSbn().getId(),  r.getSbn().getNotification(), userId, muteOnReturn,
                        /* byForegroundService= */ false, /* isAppProvided= */ false);
            } catch (Exception e) {
                Slog.e(TAG, "Cannot un-snooze notification", e);
            }
        }, mUserProfiles);

        final File systemDir = new File(Environment.getDataDirectory(), "system");
        mRankingThread.start();

        WorkerHandler handler = new WorkerHandler(Looper.myLooper());

        HandlerThread broadcastsThread = new HandlerThread("NMS Broadcasts");
        broadcastsThread.start();
        Handler broadcastsHandler = new Handler(broadcastsThread.getLooper());

        mShowReviewPermissionsNotification = getContext().getResources().getBoolean(
                R.bool.config_notificationReviewPermissions);

        mDefaultUnsupportedAdjustments = getContext().getResources().getStringArray(
                R.array.config_notificationDefaultUnsupportedAdjustments);

        BitmapOffloadInternal bitmapOffloader = null;
        if (notificationBitmapOffloading()) {
            bitmapOffloader = LocalServices.getService(BitmapOffloadInternal.class);
        }

        init(handler, new RankingHandlerWorker(mRankingThread.getLooper()), broadcastsHandler,
                AppGlobals.getPackageManager(), getContext().getPackageManager(),
                getLocalService(LightsManager.class),
                new NotificationListeners(getContext(), mNotificationLock, mUserProfiles,
                        AppGlobals.getPackageManager(), mConfigurableParameters),
                new NotificationAssistants(getContext(), AppGlobals.getPackageManager()),
                new ConditionProviders(getContext(), mUserProfiles, AppGlobals.getPackageManager()),
                null /*CDM is not initialized yet*/, snoozeHelper,
                new NotificationUsageStats(getContext()),
                new AtomicFile(new File(
                        systemDir, "notification_policy.xml"), "notification-policy"),
                new AtomicFile(new File(
                        systemDir, "notification_rules.xml"), "notification-rules"),
                (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE),
                getGroupHelper(), ActivityManager.getService(),
                LocalServices.getService(ActivityTaskManagerInternal.class),
                LocalServices.getService(UsageStatsManagerInternal.class),
                LocalServices.getService(DevicePolicyManagerInternal.class),
                UriGrantsManager.getService(),
                LocalServices.getService(UriGrantsManagerInternal.class),
                getContext().getSystemService(AppOpsManager.class),
                new NotificationHistoryManager(getContext(), handler),
                mStatsManager = (StatsManager) getContext().getSystemService(
                        Context.STATS_MANAGER),
                LocalServices.getService(ActivityManagerInternal.class),
                createToastRateLimiter(), new PermissionHelper(getContext(),
                        AppGlobals.getPackageManager(),
                        AppGlobals.getPermissionManager()),
                LocalServices.getService(UsageStatsManagerInternal.class),
                getContext().getSystemService(TelecomManager.class),
                new NotificationChannelLoggerImpl(), SystemUiSystemPropertiesFlags.getResolver(),
                getContext().getSystemService(PermissionManager.class),
                getContext().getSystemService(PowerManager.class),
                new PostNotificationTrackerFactory() {
                }, new UiEventLoggerImpl(),
                bitmapOffloader, new NotificationListenerStats(),
                new NotificationRecordLoggerImpl(),
                new InstanceIdSequence(NOTIFICATION_INSTANCE_ID_MAX),
                new PreferencesHelperFactory() {});

        publishBinderService(Context.NOTIFICATION_SERVICE, mService, /* allowIsolated= */ false,
                DUMP_FLAG_PRIORITY_CRITICAL | DUMP_FLAG_PRIORITY_NORMAL);
        publishLocalService(NotificationManagerInternal.class, mInternalService);
    }

    private void registerNotificationPreferencesPullers() {
        mPullAtomCallback = new StatsPullAtomCallbackImpl();
        mStatsManager.setPullAtomCallback(
                PACKAGE_NOTIFICATION_PREFERENCES,
                null, // use default PullAtomMetadata values
                ConcurrentUtils.DIRECT_EXECUTOR,
                mPullAtomCallback
        );
        mStatsManager.setPullAtomCallback(
                PACKAGE_NOTIFICATION_CHANNEL_PREFERENCES,
                null, // use default PullAtomMetadata values
                ConcurrentUtils.DIRECT_EXECUTOR,
                mPullAtomCallback
        );
        mStatsManager.setPullAtomCallback(
                PACKAGE_NOTIFICATION_CHANNEL_GROUP_PREFERENCES,
                null, // use default PullAtomMetadata values
                ConcurrentUtils.DIRECT_EXECUTOR,
                mPullAtomCallback
        );
        mStatsManager.setPullAtomCallback(
                DND_MODE_RULE,
                null, // use default PullAtomMetadata values
                ConcurrentUtils.DIRECT_EXECUTOR,
                mPullAtomCallback
        );
        mStatsManager.setPullAtomCallback(
                NOTIFICATION_ADJUSTMENT_PREFERENCES,
                null, // use default PullAtomMetadata values
                ConcurrentUtils.DIRECT_EXECUTOR,
                mPullAtomCallback
        );
    }

    /**
     * Implements access control for Notification bitmaps offloaded to disk
     */
    private class BitmapAccessHandler implements BitmapOffloadInternal.PermissionHandler {
        @Override
        public boolean isAllowedToOpen(Uri uri, int callingUid, int owningUid) {
            if (callingUid == owningUid) {
                return true;
            }
            int opMode = mAppOps.noteOpNoThrow(AppOpsManager.OP_ACCESS_NOTIFICATIONS, callingUid,
                    null, null, null);
            if (opMode == MODE_ALLOWED || opMode == MODE_DEFAULT) {
                return true;
            }
            if (mListeners.isUidAllowed(callingUid)) {
                return true;
            }
            if (getContext().checkCallingPermission(Manifest.permission.STATUS_BAR_SERVICE)
                    == PERMISSION_GRANTED) {
                return true;
            }
            return false;
        }
    };

    private class StatsPullAtomCallbackImpl implements StatsManager.StatsPullAtomCallback {
        @Override
        public int onPullAtom(int atomTag, List<StatsEvent> data) {
            switch (atomTag) {
                case PACKAGE_NOTIFICATION_PREFERENCES:
                case PACKAGE_NOTIFICATION_CHANNEL_PREFERENCES:
                case PACKAGE_NOTIFICATION_CHANNEL_GROUP_PREFERENCES:
                case NOTIFICATION_ADJUSTMENT_PREFERENCES:
                case DND_MODE_RULE:
                    return pullNotificationStates(atomTag, data);
                default:
                    throw new UnsupportedOperationException("Unknown tagId=" + atomTag);
            }
        }
    }

    private int pullNotificationStates(int atomTag, List<StatsEvent> data) {
        switch(atomTag) {
            case PACKAGE_NOTIFICATION_PREFERENCES:
                Map<Integer, Map<String, List<String>>> adjustmentDeniedPkgs =
                        mAssistants.getDeniedKeysForUsersAndPackages();
                mNotificationRuleManager.getClassificationDeniedPkgsForUsersAndPackages(
                        adjustmentDeniedPkgs);
                mPreferencesHelper.pullPackagePreferencesStats(data,
                        getAllUsersNotificationPermissions(),
                        adjustmentDeniedPkgs);
                break;
            case PACKAGE_NOTIFICATION_CHANNEL_PREFERENCES:
                mPreferencesHelper.pullPackageChannelPreferencesStats(data);
                break;
            case PACKAGE_NOTIFICATION_CHANNEL_GROUP_PREFERENCES:
                mPreferencesHelper.pullPackageChannelGroupPreferencesStats(data);
                break;
            case NOTIFICATION_ADJUSTMENT_PREFERENCES:
                mAssistants.pullAdjustmentPreferencesStats(data);
                break;
            case DND_MODE_RULE:
                mZenModeHelper.pullRules(data);
                break;
        }
        return StatsManager.PULL_SUCCESS;
    }

    @VisibleForTesting
    protected GroupHelper getGroupHelper() {
        mAutoGroupAtCount =
                getContext().getResources().getInteger(R.integer.config_autoGroupAtCount);
        return new GroupHelper(getContext(), getContext().getPackageManager(),
                mAutoGroupAtCount, AUTOGROUP_BUNDLE_SECTIONS_AT_COUNT,
                AUTOGROUP_SPARSE_GROUPS_AT_COUNT, new GroupHelper.Callback() {
            @Override
            public void addAutoGroup(String key, String groupName, boolean requestSort) {
                synchronized (mNotificationLock) {
                    convertSummaryToNotificationLocked(key);
                    addAutogroupKeyLocked(key, groupName, requestSort);
                }
            }

            @Override
            public void removeAutoGroup(String key) {
                synchronized (mNotificationLock) {
                    removeAutogroupKeyLocked(key);
                }
            }

            @Override
            public void addAutoGroupSummary(int userId, String pkg, String triggeringKey,
                    String groupName, int summaryId, NotificationAttributes summaryAttr) {
                NotificationRecord r = createAutoGroupSummary(userId, pkg, triggeringKey,
                        groupName, summaryId, summaryAttr);
                if (r != null) {
                    final boolean isAppForeground =
                            mActivityManager.getPackageImportance(pkg) == IMPORTANCE_FOREGROUND;
                    mHandler.post(new EnqueueNotificationRunnable(userId, r, isAppForeground,
                            /* isAppProvided= */ false,
                            mPostNotificationTrackerFactory.newTracker(null)));
                }
            }

            @Override
            public void removeAutoGroupSummary(int userId, String pkg, String groupKey) {
                synchronized (mNotificationLock) {
                    clearAutogroupSummaryLocked(userId, pkg, groupKey);
                }
            }

            @Override
            public void updateAutogroupSummary(int userId, String pkg, String groupKey,
                    NotificationAttributes summaryAttr) {
                boolean isAppForeground = pkg != null
                        && mActivityManager.getPackageImportance(pkg) == IMPORTANCE_FOREGROUND;
                synchronized (mNotificationLock) {
                    updateAutobundledSummaryLocked(userId, pkg, groupKey, summaryAttr,
                            isAppForeground);
                }
            }

            @Override
            public void removeAppProvidedSummary(String key) {
                synchronized (mNotificationLock) {
                    removeAppSummaryLocked(key);
                }
            }

            @Override
            public void sendAppProvidedSummaryDeleteIntent(String pkg, PendingIntent deleteIntent) {
                sendDeleteIntent(deleteIntent, pkg);
            }

            @Override
            public void removeNotificationFromCanceledGroup(int userId, String pkg,
                    String groupKey, int cancelReason) {
                synchronized (mNotificationLock) {
                    final int mustNotHaveFlags;
                    // Also don't allow client apps to cancel FGS, UIJ, computer control or lifetime
                    // extended notifs.
                    mustNotHaveFlags = (FLAG_FOREGROUND_SERVICE | FLAG_USER_INITIATED_JOB
                                | FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY | FLAG_COMPUTER_CONTROL);

                    FlagChecker childrenFlagChecker = (flags) -> {
                            if (cancelReason == REASON_CANCEL
                                    || cancelReason == REASON_CLICK
                                    || cancelReason == REASON_CANCEL_ALL
                                    || cancelReason == REASON_BUNDLE_DISMISSED) {
                                if ((flags & FLAG_BUBBLE) != 0) {
                                    return false;
                                }
                            }
                            return (flags & mustNotHaveFlags) == 0;
                    };
                    cancelGroupChildrenLocked(userId, pkg, Binder.getCallingUid(),
                            Binder.getCallingPid(), null,
                            false, childrenFlagChecker,
                            NotificationManagerService::wasChildOfForceRegroupedGroupChecker,
                            groupKey, REASON_APP_CANCEL, SystemClock.elapsedRealtime());
                }
            }

            @Override
            @Nullable
            public NotificationRecord removeAppProvidedSummaryOnClassification(String triggeringKey,
                    @Nullable String oldGroupKey) {
                synchronized (mNotificationLock) {
                    return removeAppProvidedSummaryOnClassificationLocked(triggeringKey,
                            oldGroupKey);
                }
            }
        });
    }

    //Enables tests running in TH mode to be exempted from forced grouping of notifications
    void setTestHarnessExempted(boolean isExempted) {
        mGroupHelper.setTestHarnessExempted(isExempted);
    }

    private void sendRegisteredOnlyBroadcast(String action) {
        sendRegisteredOnlyBroadcast(new Intent(action));
    }

    /**
     * Schedules a broadcast to be sent to runtime receivers and DND-policy-access packages. The
     * broadcast will be sent after {@link #ZEN_BROADCAST_DELAY}, unless a new broadcast is
     * scheduled in the interim, in which case the previous one is dropped and the waiting period
     * is <em>restarted</em>.
     *
     * <p>Note that this uses <em>equality of the {@link Intent#getAction}</em> as the criteria for
     * deduplicating pending broadcasts, ignoring the extras and anything else. This is intentional
     * so that e.g. rapidly changing some value A -> B -> C will only produce a broadcast for C
     * (instead of every time because the extras are different).
     */
    private void sendZenBroadcastWithDelay(Intent intent) {
        String token = "zen_broadcast:" + intent.getAction();
        mBroadcastsHandler.removeCallbacksAndEqualMessages(token);
        mBroadcastsHandler.postDelayed(() -> sendRegisteredOnlyBroadcast(intent), token,
                ZEN_BROADCAST_DELAY.toMillis());
    }

    private void sendRegisteredOnlyBroadcast(Intent baseIntent) {
        int[] userIds = mUmInternal.getProfileIds(mAmi.getCurrentUserId(), true);
        for (int userId : userIds) {
            Context userContext = getContext().createContextAsUser(UserHandle.of(userId), 0);
            String[] dndPackages = mConditionProviders.getAllowedPackages(userId)
                    .toArray(new String[0]);

            // We send the broadcast to all DND packages in the second step, so leave them out
            // of this first broadcast for *running* receivers. That ensures each package only
            // receives it once.
            Intent registeredOnlyIntent = new Intent(baseIntent)
                    .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            userContext.sendBroadcastMultiplePermissions(registeredOnlyIntent,
                    /* receiverPermissions= */ new String[0],
                    /* excludedPermissions= */ new String[0],
                    /* excludedPackages= */ dndPackages);

            for (String pkg : dndPackages) {
                Intent pkgIntent = new Intent(baseIntent).setPackage(pkg)
                        .setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                userContext.sendBroadcast(pkgIntent);
            }
        }
    }

    @Override
    public void onBootPhase(int phase) {
        onBootPhase(phase, Looper.getMainLooper());
    }

    @VisibleForTesting
    void onBootPhase(int phase, Looper mainLooper) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            mDisplayManager = getContext().getSystemService(DisplayManager.class);
            mWindowManagerInternal = LocalServices.getService(WindowManagerInternal.class);
            if (mComputerControlHelper == null) {
                mComputerControlHelper = ComputerControlHelper.forLocalService();
            }
            mZenModeHelper.onSystemReady();
            RoleObserver roleObserver = new RoleObserver(getContext(),
                    getContext().getSystemService(RoleManager.class),
                    mPackageManager, mainLooper);
            roleObserver.init();
            mRoleObserver = roleObserver;
            LauncherApps launcherApps =
                    (LauncherApps) getContext().getSystemService(Context.LAUNCHER_APPS_SERVICE);
            UserManager userManager = (UserManager) getContext().getSystemService(
                    Context.USER_SERVICE);
            mShortcutHelper = new ShortcutHelper(launcherApps, mShortcutListener, getLocalService(
                    ShortcutServiceInternal.class), userManager);
            BubbleExtractor bubbsExtractor = mRankingHelper.findExtractor(BubbleExtractor.class);
            if (bubbsExtractor != null) {
                bubbsExtractor.setShortcutHelper(mShortcutHelper);
                bubbsExtractor.setPackageManager(mPackageManagerClient);
            }
            registerNotificationPreferencesPullers();
            if (mLockUtils == null) {
                mLockUtils = new LockPatternUtils(getContext());
            }
            mLockUtils.registerStrongAuthTracker(mStrongAuthTracker);
            mAttentionHelper.onSystemReady();
            mConfigurableParameters.initialize(BackgroundThread.getExecutor());
        } else if (phase == SystemService.PHASE_THIRD_PARTY_APPS_CAN_START) {
            // This observer will force an update when observe is called, causing us to
            // bind to listener services.
            mSettingsObserver.observe();
            mListeners.onBootPhaseAppsCanStart();
            mAssistants.onBootPhaseAppsCanStart();
            mConditionProviders.onBootPhaseAppsCanStart();
            mHistoryManager.onBootPhaseAppsCanStart();
            mPreferencesHelper.onBootPhaseAppsCanStart();
            migrateDefaultNAS();
            maybeShowInitialReviewPermissionsNotification();

            if (!mZenModeHelper.hasDeviceEffectsApplier()) {
                // Cannot be done earlier, as some services aren't ready until this point.
                mZenModeHelper.setDeviceEffectsApplier(
                        new DefaultDeviceEffectsApplier(getContext()));
            }
            List<ModuleInfo> moduleInfoList =
            mPackageManagerClient.getInstalledModules(
                PackageManager.MATCH_DEBUG_TRIAGED_MISSING);
            // Cache adservices module info
            for (ModuleInfo mi : moduleInfoList) {
                if (Objects.equals(mi.getApexModuleName(), ADSERVICES_MODULE_PKG_NAME)) {
                    mAdservicesModuleInfo = mi;
                }
            }
        } else if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
            mSnoozeHelper.scheduleRepostsForPersistedNotifications(System.currentTimeMillis());
            if (android.security.Flags.appLockApis() && android.security.Flags.appLockCore()) {
                Trace.beginSection(TAG + ".onBootPhase_AMReady_appLock");
                // App Lock services gets registered by the ActivityManagerService, and then needs
                // to initialize the map of App Lock locked states. Wait until it's ready.
                mAppLockInternal = LocalServices.getService(AppLockInternal.class);
                if (mAppLockInternal == null) {
                    Slog.wtf(TAG, "AppLockInternal is null");
                } else {
                    synchronized (mNotificationLock) {
                        final SparseArray<Set<String>> appLockEnabledPackages =
                                mAppLockInternal.getAppLockEnabledPackages();
                        mAppLockLockedPackages.clear();
                        for (int i = 0; i < appLockEnabledPackages.size(); i++) {
                            final int userId = appLockEnabledPackages.keyAt(i);
                            final Set<String> packages = appLockEnabledPackages.valueAt(i);
                            if (packages != null) {
                                mAppLockLockedPackages.put(userId, new ArraySet<>(packages));
                            }
                        }
                    }
                    mAppLockInternal.registerPackageLockedStateListener(
                            mPackageLockedStateListener);
                }
                Trace.endSection();
            }
        } else if (phase == SystemService.PHASE_DEVICE_SPECIFIC_SERVICES_READY) {
            mPreferencesHelper.updateFixedImportance(mUm.getUsers());
            mPreferencesHelper.migrateNotificationPermissions(mUm.getUsers());
        } else if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            NotificationBitmapJobService.scheduleJob(getContext());
        }
    }

    @Override
    public void onUserUnlocked(@NonNull TargetUser user) {
        mHandler.post(() -> {
            Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "notifHistoryUnlockUser");
            try {
                mHistoryManager.onUserUnlocked(user.getUserIdentifier());
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }
        });
    }

    /**
     * Class used to host values that can be configured via {@link DeviceConfig}. Once aconfig
     * functionality attains parity with device config, this class can be removed.
     */
    static class ConfigurableParameters implements DeviceConfig.OnPropertiesChangedListener {
        static final long DEFAULT_NLS_COMPLETION_DURATION_MS = TimeUnit.SECONDS.toMillis(10);

        volatile long mNlsCompletionDurationMs = DEFAULT_NLS_COMPLETION_DURATION_MS;

        @Override
        public void onPropertiesChanged(@androidx.annotation.NonNull Properties properties) {
            mNlsCompletionDurationMs = properties.getLong(NLS_COMPLETION_DURATION_MS,
                    DEFAULT_NLS_COMPLETION_DURATION_MS);
        }

        @SuppressLint("MissingPermission")
        void initialize(Executor executor) {
            DeviceConfig.addOnPropertiesChangedListener(NAMESPACE_SYSTEMUI, executor, this);
            // Fetch and load previously written properties at startup.
            onPropertiesChanged(
                    DeviceConfig.getProperties(NAMESPACE_SYSTEMUI, NLS_COMPLETION_DURATION_MS));
        }

        void dump(IndentingPrintWriter ipw) {
            ipw.println("Configurable parameters:");
            ipw.increaseIndent();

            ipw.print(NLS_COMPLETION_DURATION_MS,
                    TimeUtils.formatDuration(mNlsCompletionDurationMs));
            ipw.println();

            ipw.decreaseIndent();
        }
    }

    private void sendAppBlockStateChangedBroadcast(String pkg, int uid, boolean blocked) {
        // From Android T, revoking the notification permission will cause the app to be killed.
        // delay this broadcast so it doesn't race with that process death
        mHandler.postDelayed(() -> {
            try {
                getContext().sendBroadcastAsUser(
                        new Intent(ACTION_APP_BLOCK_STATE_CHANGED)
                                .putExtra(NotificationManager.EXTRA_BLOCKED_STATE, blocked)
                                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                                .setPackage(pkg),
                        UserHandle.of(UserHandle.getUserId(uid)), null);
            } catch (SecurityException e) {
                Slog.w(TAG, "Can't notify app about app block change", e);
            }
        }, 500);
    }

    @Override
    public void onUserSwitching(@Nullable TargetUser from, @NonNull TargetUser to) {
        if (!Flags.useSsmUserSwitchSignal()) {
            return;
        }
        final int userId = to.getUserIdentifier();
        mUserProfiles.updateCache(getContext());
        if (!mUserProfiles.isProfileUser(userId, getContext())) {
            // reload per-user settings
            mSettingsObserver.update(null);
            // Refresh managed services
            mConditionProviders.onUserSwitched(userId);
            mListeners.onUserSwitched(userId);
            mZenModeHelper.onUserSwitched(userId);
            mPreferencesHelper.syncHasPriorityChannels();
        }
        // assistant is the only thing that cares about managed profiles specifically
        mAssistants.onUserSwitched(userId);
    }

    @Override
    public void onUserStopping(@NonNull TargetUser user) {
        mHandler.post(() -> {
            Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "notifHistoryStopUser");
            try {
                mHistoryManager.onUserStopped(user.getUserIdentifier());
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }
        });
    }

    @GuardedBy("mNotificationLock")
    private void updateListenerHintsLocked() {
        final int hints = calculateHints();
        if (hints == mListenerHints) return;
        ZenLog.traceListenerHintsChanged(mListenerHints, hints, mEffectsSuppressors.size());
        mListenerHints = hints;
        scheduleListenerHintsChanged(hints);
    }

    @GuardedBy("mNotificationLock")
    private void updateEffectsSuppressorLocked() {
        final long oldSuppressedEffects = mZenModeHelper.getSuppressedEffects();
        final long updatedSuppressedEffects = calculateSuppressedEffects();
        if (updatedSuppressedEffects == oldSuppressedEffects) return;

        final List<ComponentName> suppressors = getSuppressors();
        ZenLog.traceEffectsSuppressorChanged(
                mEffectsSuppressors, suppressors, oldSuppressedEffects, updatedSuppressedEffects);
        mZenModeHelper.setSuppressedEffects(updatedSuppressedEffects);

        if (!suppressors.equals(mEffectsSuppressors)) {
            mEffectsSuppressors = suppressors;
            sendZenBroadcastWithDelay(
                    new Intent(NotificationManager.ACTION_EFFECTS_SUPPRESSOR_CHANGED));
        }
    }

    private void exitIdle() {
        if (mDeviceIdleManager != null) {
            mDeviceIdleManager.endIdle("notification interaction");
        }
    }

    void updateNotificationChannelInt(String pkg, int uid, NotificationChannel channel,
            boolean fromListener) {
        if (channel.getImportance() == IMPORTANCE_NONE) {
            // cancel
            cancelAllNotificationsInt(MY_UID, MY_PID, pkg, channel.getId(), 0, 0,
                    UserHandle.getUserId(uid), REASON_CHANNEL_BANNED
            );
            if (isUidSystemOrPhone(uid)) {
                IntArray profileIds = mUserProfiles.getCurrentProfileIds();
                int N = profileIds.size();
                for (int i = 0; i < N; i++) {
                    int profileId = profileIds.get(i);
                    cancelAllNotificationsInt(MY_UID, MY_PID, pkg, channel.getId(), 0, 0,
                            profileId, REASON_CHANNEL_BANNED
                    );
                }
            }
        }
        final NotificationChannel preUpdate =
                mPreferencesHelper.getNotificationChannel(pkg, uid, channel.getId(), true);

        mPreferencesHelper.updateNotificationChannel(pkg, uid, channel, true,
                Binder.getCallingUid(), isCallerSystemOrSystemUi());
        if (mPreferencesHelper.onlyHasDefaultChannel(pkg, uid)) {
            mPermissionHelper.setNotificationPermission(pkg, UserHandle.getUserId(uid),
                    channel.getImportance() != IMPORTANCE_NONE, true);
        }
        maybeNotifyChannelOwner(pkg, uid, preUpdate, channel);

        if (!fromListener) {
            final NotificationChannel modifiedChannel = mPreferencesHelper.getNotificationChannel(
                    pkg, uid, channel.getId(), false);
            mListeners.notifyNotificationChannelChanged(
                    pkg, UserHandle.getUserHandleForUid(uid),
                    modifiedChannel, NOTIFICATION_CHANNEL_OR_GROUP_UPDATED);
        }
        mHandler.postDelayed(() -> {
            final NotificationChannel updatedChannel = mPreferencesHelper
                    .getNotificationChannel(pkg, uid, channel.getId(), false);
            synchronized (mNotificationLock) {
                if (updatedChannel != null) {
                    mGroupHelper.onChannelUpdated(
                            UserHandle.getUserHandleForUid(uid).getIdentifier(), pkg,
                            updatedChannel, mNotificationList, mSummaryByGroupKey);
                }
            }
        }, DELAY_FORCE_REGROUP_TIME);

        handleSavePolicyFile();
    }

    private void maybeNotifyChannelOwner(String pkg, int uid, NotificationChannel preUpdate,
            NotificationChannel update) {
        try {
            if ((preUpdate.getImportance() == IMPORTANCE_NONE
                    && update.getImportance() != IMPORTANCE_NONE)
                    || (preUpdate.getImportance() != IMPORTANCE_NONE
                    && update.getImportance() == IMPORTANCE_NONE)) {
                getContext().sendBroadcastAsUser(
                        new Intent(ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED)
                                .putExtra(NotificationManager.EXTRA_NOTIFICATION_CHANNEL_ID,
                                        update.getId())
                                .putExtra(NotificationManager.EXTRA_BLOCKED_STATE,
                                        update.getImportance() == IMPORTANCE_NONE)
                                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                                .setPackage(pkg),
                        UserHandle.of(UserHandle.getUserId(uid)), null);
            }
        } catch (SecurityException e) {
            Slog.w(TAG, "Can't notify app about channel change", e);
        }
    }

    void createNotificationChannelGroup(String pkg, int uid, NotificationChannelGroup group,
            boolean fromApp, boolean fromListener) {
        Objects.requireNonNull(group);
        Objects.requireNonNull(pkg);

        final NotificationChannelGroup preUpdate =
                mPreferencesHelper.getNotificationChannelGroup(group.getId(), pkg, uid);
        mPreferencesHelper.createNotificationChannelGroup(pkg, uid, group,
                fromApp, Binder.getCallingUid(), isCallerSystemOrSystemUi());
        if (!fromApp) {
            maybeNotifyChannelGroupOwner(pkg, uid, preUpdate, group);
        }
        if (!fromListener) {
            mListeners.notifyNotificationChannelGroupChanged(pkg,
                    UserHandle.of(UserHandle.getCallingUserId()), group,
                    NOTIFICATION_CHANNEL_OR_GROUP_ADDED);
        }
    }

    private void maybeNotifyChannelGroupOwner(String pkg, int uid,
            @Nullable NotificationChannelGroup preUpdate, NotificationChannelGroup update) {
        try {
            if (preUpdate != null && preUpdate.isBlocked() != update.isBlocked()) {
                getContext().sendBroadcastAsUser(
                        new Intent(ACTION_NOTIFICATION_CHANNEL_GROUP_BLOCK_STATE_CHANGED)
                                .putExtra(NotificationManager.EXTRA_NOTIFICATION_CHANNEL_GROUP_ID,
                                        update.getId())
                                .putExtra(NotificationManager.EXTRA_BLOCKED_STATE,
                                        update.isBlocked())
                                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                                .setPackage(pkg),
                        UserHandle.of(UserHandle.getUserId(uid)), null);
            }
        } catch (SecurityException e) {
            Slog.w(TAG, "Can't notify app about group change", e);
        }
    }

    private ArrayList<ComponentName> getSuppressors() {
        ArrayList<ComponentName> names = new ArrayList<>();
        for (int i = mListenersDisablingEffects.size() - 1; i >= 0; --i) {
            ArraySet<ComponentName> serviceInfoList = mListenersDisablingEffects.valueAt(i);

            for (ComponentName info : serviceInfoList) {
                if (!names.contains(info)) {
                    names.add(info);
                }
            }
        }

        return names;
    }

    private boolean removeDisabledHints(ManagedServiceInfo info) {
        return removeDisabledHints(info, 0);
    }

    private boolean removeDisabledHints(ManagedServiceInfo info, int hints) {
        boolean removed = false;

        for (int i = mListenersDisablingEffects.size() - 1; i >= 0; --i) {
            final int hint = mListenersDisablingEffects.keyAt(i);
            final ArraySet<ComponentName> listeners = mListenersDisablingEffects.valueAt(i);

            if (hints == 0 || (hint & hints) == hint) {
                removed |= listeners.remove(info.component);
            }
        }

        return removed;
    }

    private void addDisabledHints(ManagedServiceInfo info, int hints) {
        if ((hints & HINT_HOST_DISABLE_EFFECTS) != 0) {
            addDisabledHint(info, HINT_HOST_DISABLE_EFFECTS);
        }

        if ((hints & HINT_HOST_DISABLE_NOTIFICATION_EFFECTS) != 0) {
            addDisabledHint(info, HINT_HOST_DISABLE_NOTIFICATION_EFFECTS);
        }

        if ((hints & HINT_HOST_DISABLE_CALL_EFFECTS) != 0) {
            addDisabledHint(info, HINT_HOST_DISABLE_CALL_EFFECTS);
        }
    }

    private void addDisabledHint(ManagedServiceInfo info, int hint) {
        if (mListenersDisablingEffects.indexOfKey(hint) < 0) {
            mListenersDisablingEffects.put(hint, new ArraySet<>());
        }

        ArraySet<ComponentName> hintListeners = mListenersDisablingEffects.get(hint);
        hintListeners.add(info.component);
    }

    private int calculateHints() {
        int hints = 0;
        for (int i = mListenersDisablingEffects.size() - 1; i >= 0; --i) {
            int hint = mListenersDisablingEffects.keyAt(i);
            ArraySet<ComponentName> serviceInfoList = mListenersDisablingEffects.valueAt(i);

            if (!serviceInfoList.isEmpty()) {
                hints |= hint;
            }
        }

        return hints;
    }

    private long calculateSuppressedEffects() {
        int hints = calculateHints();
        long suppressedEffects = 0;

        if ((hints & HINT_HOST_DISABLE_EFFECTS) != 0) {
            suppressedEffects |= ZenModeHelper.SUPPRESSED_EFFECT_ALL;
        }

        if ((hints & HINT_HOST_DISABLE_NOTIFICATION_EFFECTS) != 0) {
            suppressedEffects |= ZenModeHelper.SUPPRESSED_EFFECT_NOTIFICATIONS;
        }

        if ((hints & HINT_HOST_DISABLE_CALL_EFFECTS) != 0) {
            suppressedEffects |= ZenModeHelper.SUPPRESSED_EFFECT_CALLS;
        }

        return suppressedEffects;
    }

    @GuardedBy("mNotificationLock")
    private void updateInterruptionFilterLocked() {
        int interruptionFilter = mZenModeHelper.getZenModeListenerInterruptionFilter();
        if (interruptionFilter == mInterruptionFilter) return;
        mInterruptionFilter = interruptionFilter;
        scheduleInterruptionFilterChanged(interruptionFilter);
    }

    int correctCategory(int requestedCategoryList, int categoryType,
            int currentCategoryList) {
        if ((requestedCategoryList & categoryType) != 0
                && (currentCategoryList & categoryType) == 0) {
            requestedCategoryList &= ~categoryType;
        } else if ((requestedCategoryList & categoryType) == 0
                && (currentCategoryList & categoryType) != 0){
            requestedCategoryList |= categoryType;
        }
        return requestedCategoryList;
    }

    @VisibleForTesting
    INotificationManager getBinderService() {
       return mService;
    }

    /**
     * Report to usage stats that the notification was seen.
     * @param r notification record
     */
    @GuardedBy("mNotificationLock")
    protected void reportSeen(NotificationRecord r) {
        if (!r.isProxied()) {
            mAppUsageStats.reportEvent(r.getSbn().getPackageName(),
                    getRealUserId(r.getSbn().getUserId()),
                    UsageEvents.Event.NOTIFICATION_SEEN);
        }
    }

    protected int calculateSuppressedVisualEffects(Policy incomingPolicy, Policy currPolicy,
            int targetSdkVersion) {
        if (incomingPolicy.suppressedVisualEffects == SUPPRESSED_EFFECTS_UNSET) {
            return incomingPolicy.suppressedVisualEffects;
        }
        final int[] effectsIntroducedInP = {
                SUPPRESSED_EFFECT_FULL_SCREEN_INTENT,
                SUPPRESSED_EFFECT_LIGHTS,
                SUPPRESSED_EFFECT_PEEK,
                SUPPRESSED_EFFECT_STATUS_BAR,
                SUPPRESSED_EFFECT_BADGE,
                SUPPRESSED_EFFECT_AMBIENT,
                SUPPRESSED_EFFECT_NOTIFICATION_LIST
        };

        int newSuppressedVisualEffects = incomingPolicy.suppressedVisualEffects;
        if (targetSdkVersion < Build.VERSION_CODES.P) {
            // unset higher order bits introduced in P, maintain the user's higher order bits
            for (int i = 0; i < effectsIntroducedInP.length ; i++) {
                newSuppressedVisualEffects &= ~effectsIntroducedInP[i];
                newSuppressedVisualEffects |=
                        (currPolicy.suppressedVisualEffects & effectsIntroducedInP[i]);
            }
            // set higher order bits according to lower order bits
            if ((newSuppressedVisualEffects & SUPPRESSED_EFFECT_SCREEN_OFF) != 0) {
                newSuppressedVisualEffects |= SUPPRESSED_EFFECT_LIGHTS;
                newSuppressedVisualEffects |= SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
            }
            if ((newSuppressedVisualEffects & SUPPRESSED_EFFECT_SCREEN_ON) != 0) {
                newSuppressedVisualEffects |= SUPPRESSED_EFFECT_PEEK;
            }
        } else {
            boolean hasNewEffects = (newSuppressedVisualEffects
                    - SUPPRESSED_EFFECT_SCREEN_ON - SUPPRESSED_EFFECT_SCREEN_OFF) > 0;
            // if any of the new effects introduced in P are set
            if (hasNewEffects) {
                // clear out the deprecated effects
                newSuppressedVisualEffects &= ~ (SUPPRESSED_EFFECT_SCREEN_ON
                        | SUPPRESSED_EFFECT_SCREEN_OFF);

                // set the deprecated effects according to the new more specific effects
                if ((newSuppressedVisualEffects & SUPPRESSED_EFFECT_PEEK) != 0) {
                    newSuppressedVisualEffects |= SUPPRESSED_EFFECT_SCREEN_ON;
                }
                if ((newSuppressedVisualEffects & SUPPRESSED_EFFECT_LIGHTS) != 0
                        && (newSuppressedVisualEffects
                        & SUPPRESSED_EFFECT_FULL_SCREEN_INTENT) != 0
                        && (newSuppressedVisualEffects
                        & SUPPRESSED_EFFECT_AMBIENT) != 0) {
                    newSuppressedVisualEffects |= SUPPRESSED_EFFECT_SCREEN_OFF;
                }
            } else {
                // set higher order bits according to lower order bits
                if ((newSuppressedVisualEffects & SUPPRESSED_EFFECT_SCREEN_OFF) != 0) {
                    newSuppressedVisualEffects |= SUPPRESSED_EFFECT_LIGHTS;
                    newSuppressedVisualEffects |= SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
                    newSuppressedVisualEffects |= SUPPRESSED_EFFECT_AMBIENT;
                }
                if ((newSuppressedVisualEffects & SUPPRESSED_EFFECT_SCREEN_ON) != 0) {
                    newSuppressedVisualEffects |= SUPPRESSED_EFFECT_PEEK;
                }
            }
        }

        return newSuppressedVisualEffects;
    }

    @GuardedBy("mNotificationLock")
    protected void maybeRecordInterruptionLocked(NotificationRecord r) {
        if (r.isInterruptive() && !r.hasRecordedInterruption()) {
            String channelId = r.getNotification().getChannelId();
            mAppUsageStats.reportInterruptiveNotification(r.getSbn().getPackageName(),
                    channelId,
                    getRealUserId(r.getSbn().getUserId()));
            Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "notifHistoryAddItem");
            try {
                if (r.getNotification().getSmallIcon() != null) {
                    final HistoricalNotification.Builder builder
                            = new HistoricalNotification.Builder()
                            .setPackage(r.getSbn().getPackageName())
                            .setUid(r.getSbn().getUid())
                            .setUserId(r.getSbn().getNormalizedUserId())
                            .setChannelId(channelId)
                            .setPostedTimeMs(System.currentTimeMillis())
                            .setTitle(r.getNotification().getHistoryTitle(getContext()))
                            .setText(r.getNotification().getHistoryText(getContext()))
                            .setIcon(r.getNotification().getSmallIcon());
                    mHistoryManager.addNotification(builder.build());
                }
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }
            r.setRecordedInterruption(true);
        }
    }

    protected void reportForegroundServiceUpdate(boolean shown,
            final Notification notification, final int id, final String pkg, final int userId) {
        mHandler.post(() -> {
            mAmi.onForegroundServiceNotificationUpdate(shown, notification, id, pkg, userId);
        });
    }

    protected void maybeReportForegroundServiceUpdate(final NotificationRecord r, boolean shown) {
        if (r.isForegroundService()) {
            // snapshot live state for the asynchronous operation
            final StatusBarNotification sbn = r.getSbn();
            reportForegroundServiceUpdate(shown, sbn.getNotification(), sbn.getId(),
                    sbn.getPackageName(), sbn.getUser().getIdentifier());
        }
    }

    protected void maybeRegisterMessageSent(NotificationRecord r) {
        if (r.isConversation()) {
            if (r.getShortcutInfo() != null) {
                if (mPreferencesHelper.setValidMessageSent(
                        r.getSbn().getPackageName(), r.getUid())) {
                    handleSavePolicyFile();
                } else if (r.getNotification().getBubbleMetadata() != null) {
                    // If bubble metadata is present it is valid (if invalid it's removed
                    // via BubbleExtractor).
                    if (mPreferencesHelper.setValidBubbleSent(
                            r.getSbn().getPackageName(), r.getUid())) {
                        handleSavePolicyFile();
                    }
                }
            } else {
                if (mPreferencesHelper.setInvalidMessageSent(
                        r.getSbn().getPackageName(), r.getUid())) {
                    handleSavePolicyFile();
                }
            }
        } else if (r.getNotification().isStyle(Notification.MessagingStyle.class)) {
            // If the notification is determined not to be a conversation, but still uses messaging
            // style, consider it to be an "invalid" message, so that the sending app still gets
            // identified as one that sends some form of messages.
            if (mPreferencesHelper.setInvalidMessageSent(
                    r.getSbn().getPackageName(), r.getUid())) {
                handleSavePolicyFile();
            }
        }
    }

    /**
     * Report to usage stats that the user interacted with the notification.
     * @param r notification record
     */
    protected void reportUserInteraction(NotificationRecord r) {
        mAppUsageStats.reportEvent(r.getSbn().getPackageName(),
                getRealUserId(r.getSbn().getUserId()),
                UsageEvents.Event.USER_INTERACTION);

        if (Flags.politeNotifications()) {
            mAttentionHelper.onUserInteraction(r);
        }
    }

    private int getRealUserId(int userId) {
        return userId == USER_ALL ? USER_SYSTEM : userId;
    }

    private ToastRecord getToastRecord(int uid, int pid, String packageName, boolean isSystemToast,
            IBinder token, @Nullable CharSequence text, @Nullable ITransientNotification callback,
            int duration, Binder windowToken, int displayId,
            @Nullable ITransientNotificationCallback textCallback) {
        if (callback == null) {
            return new TextToastRecord(this, mStatusBar, uid, pid, packageName,
                    isSystemToast, token, text, duration, windowToken, displayId, textCallback);
        } else {
            return new CustomToastRecord(this, uid, pid, packageName,
                    isSystemToast, token, callback, duration, windowToken, displayId);
        }
    }

    @VisibleForTesting
    NotificationManagerInternal getInternalService() {
        return mInternalService;
    }

    private MultiRateLimiter createToastRateLimiter() {
        return new MultiRateLimiter.Builder(getContext()).addRateLimits(TOAST_RATE_LIMITS).build();
    }

    protected int checkComponentPermission(String permission, int uid, int owningUid,
            boolean exported) {
        return ActivityManager.checkComponentPermission(permission, uid, owningUid, exported);
    }

    @VisibleForTesting
    INotificationManager.Stub mService;

    private final class Stub extends INotificationManager.Stub {
        // Toasts
        // ============================================================================

        @Override
        public boolean enqueueTextToast(String pkg, IBinder token, CharSequence text, int duration,
                boolean isUiContext, int displayId,
                @Nullable ITransientNotificationCallback textCallback) {
            return enqueueToast(pkg, token, text, /* callback= */ null, duration, isUiContext,
                    displayId, textCallback);
        }

        @Override
        public boolean enqueueToast(String pkg, IBinder token, ITransientNotification callback,
                int duration, boolean isUiContext, int displayId) {
            return enqueueToast(pkg, token, /* text= */ null, callback, duration, isUiContext,
                    displayId, /* textCallback= */ null);
        }

        private boolean enqueueToast(String pkg, IBinder token, @Nullable CharSequence text,
                @Nullable ITransientNotification callback, int duration, boolean isUiContext,
                int displayId, @Nullable ITransientNotificationCallback textCallback) {
            if (DBG) {
                Slog.i(TAG, "enqueueToast pkg=" + pkg + " token=" + token + " duration=" + duration
                        + " isUiContext=" + isUiContext + " displayId=" + displayId);
            }

            if (pkg == null || (text == null && callback == null)
                    || (text != null && callback != null) || token == null) {
                Slog.e(TAG, "Not enqueuing toast. pkg=" + pkg + " text=" + text + " callback="
                        + " token=" + token);
                return false;
            }

            final int callingUid = Binder.getCallingUid();
            if (!isUiContext && displayId == Display.DEFAULT_DISPLAY
                    && mUm.isVisibleBackgroundUsersSupported()) {
                // When the caller is a visible background user using a non-UI context (like the
                // application context), the Toast must be displayed in the display the user was
                // started visible on.
                int userId = UserHandle.getUserId(callingUid);
                int userDisplayId = mUmInternal.getMainDisplayAssignedToUser(userId);
                if (displayId != userDisplayId) {
                    if (DBG) {
                        Slogf.d(TAG, "Changing display id from %d to %d on user %d", displayId,
                                userDisplayId, userId);
                    }
                    displayId = userDisplayId;
                }
            }

            // If the display cannot host tasks (such as a display used for mirroring), show the
            // toast on default display instead.
            if (DesktopExperienceFlags.ENABLE_DISPLAY_CONTENT_MODE_MANAGEMENT.isTrue()) {
                Display display = mDisplayManager.getDisplay(displayId);
                if (display != null && !display.canHostTasks()) {
                    if (DBG) {
                        Slogf.d(TAG, "Changing display id from %d to %d, because display %d "
                                        + "cannot host tasks",
                                displayId, Display.DEFAULT_DISPLAY, displayId);
                    }
                    displayId = Display.DEFAULT_DISPLAY;
                }
            }

            checkCallerIsSameApp(pkg);
            final boolean isSystemToast = isCallerSystemOrSystemUi()
                    || PackageManagerService.PLATFORM_PACKAGE_NAME.equals(pkg);
            boolean isAppRenderedToast = (callback != null);
            if (!checkCanEnqueueToast(pkg, callingUid, displayId, isAppRenderedToast,
                    isSystemToast)) {
                return false;
            }

            synchronized (mToastQueue) {
                int callingPid = Binder.getCallingPid();
                final long callingId = Binder.clearCallingIdentity();
                try {
                    ToastRecord record;
                    int index = indexOfToastLocked(pkg, token);
                    // If it's already in the queue, we update it in place, we don't
                    // move it to the end of the queue.
                    if (index >= 0) {
                        record = mToastQueue.get(index);
                        record.update(duration);
                    } else {
                        // Limit the number of toasts that any given package can enqueue.
                        // Prevents DOS attacks and deals with leaks.
                        int count = 0;
                        final int N = mToastQueue.size();
                        for (int i = 0; i < N; i++) {
                            final ToastRecord r = mToastQueue.get(i);
                            if (r.pkg.equals(pkg)) {
                                count++;
                                if (count >= MAX_PACKAGE_TOASTS) {
                                    Slog.e(TAG, "Package has already queued " + count
                                            + " toasts. Not showing more. Package=" + pkg);
                                    return false;
                                }
                            }
                        }

                        Binder windowToken = new Binder();
                        mWindowManagerInternal.addWindowToken(windowToken, TYPE_TOAST, displayId,
                                null /* options */);
                        record = getToastRecord(callingUid, callingPid, pkg, isSystemToast, token,
                                text, callback, duration, windowToken, displayId, textCallback);

                        // Insert system toasts at the front of the queue
                        int systemToastInsertIdx = mToastQueue.size();
                        if (isSystemToast) {
                            systemToastInsertIdx = getInsertIndexForSystemToastLocked();
                        }
                        if (systemToastInsertIdx < mToastQueue.size()) {
                            index = systemToastInsertIdx;
                            mToastQueue.add(index, record);
                        } else {
                            mToastQueue.add(record);
                            index = mToastQueue.size() - 1;
                        }
                        keepProcessAliveForToastIfNeededLocked(callingPid);
                    }
                    // If it's at index 0, it's the current toast.  It doesn't matter if it's
                    // new or just been updated, show it.
                    // If the callback fails, this will remove it from the list, so don't
                    // assume that it's valid after this.
                    if (index == 0) {
                        showNextToastLocked(false);
                    }
                } finally {
                    Binder.restoreCallingIdentity(callingId);
                }
            }
            return true;
        }

        @GuardedBy("mToastQueue")
        private int getInsertIndexForSystemToastLocked() {
            // If there are other system toasts: insert after the last one
            int idx = 0;
            for (ToastRecord r : mToastQueue) {
                if (idx == 0 && mIsCurrentToastShown) {
                    idx++;
                    continue;
                }
                if (!r.isSystemToast) {
                    return idx;
                }
                idx++;
            }
            return idx;
        }

        private boolean checkCanEnqueueToast(String pkg, int callingUid, int displayId,
                boolean isAppRenderedToast, boolean isSystemToast) {
            final boolean isPackageSuspended = isPackagePaused(pkg);
            final boolean notificationsDisabledForPackage = !areNotificationsEnabledForPackage(pkg,
                    callingUid);

            final boolean appIsForeground;
            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                appIsForeground = mActivityManager.getUidImportance(callingUid)
                        == IMPORTANCE_FOREGROUND;
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
            }

            if (!isSystemToast && ((notificationsDisabledForPackage && !appIsForeground)
                    || isPackageSuspended)) {
                Slog.e(TAG, "Suppressing toast from package " + pkg
                        + (isPackageSuspended ? " due to package suspended."
                        : " by user request."));
                return false;
            }

            if (blockToast(callingUid, isSystemToast, isAppRenderedToast,
                    isPackageInForegroundForToast(callingUid))) {
                Slog.w(TAG, "Blocking custom toast from package " + pkg
                        + " due to package not in the foreground at time the toast was posted");
                return false;
            }

            int userId = UserHandle.getUserId(callingUid);
            if (!isSystemToast && !mUmInternal.isUserVisible(userId, displayId)) {
                Slog.e(TAG, "Suppressing toast from package " + pkg + "/" + callingUid + " as user "
                        + userId + " is not visible on display " + displayId);
                return false;
            }

            return true;
        }

        @Override
        public void cancelToast(String pkg, IBinder token) {
            Slog.i(TAG, "cancelToast pkg=" + pkg + " token=" + token);

            if (pkg == null || token == null) {
                Slog.e(TAG, "Not cancelling notification. pkg=" + pkg + " token=" + token);
                return;
            }

            synchronized (mToastQueue) {
                final long callingId = Binder.clearCallingIdentity();
                try {
                    int index = indexOfToastLocked(pkg, token);
                    if (index >= 0) {
                        cancelToastLocked(index);
                    } else {
                        Slog.w(TAG, "Toast already cancelled. pkg=" + pkg
                                + " token=" + token);
                    }
                } finally {
                    Binder.restoreCallingIdentity(callingId);
                }
            }
        }

        @Override
        @EnforcePermission(android.Manifest.permission.MANAGE_TOAST_RATE_LIMITING)
        public void setToastRateLimitingEnabled(boolean enable) {

            super.setToastRateLimitingEnabled_enforcePermission();

            synchronized (mToastQueue) {
                int uid = Binder.getCallingUid();
                int userId = UserHandle.getUserId(uid);
                if (enable) {
                    mToastRateLimitingDisabledUids.remove(uid);
                    try {
                        String[] packages = mPackageManager.getPackagesForUid(uid);
                        if (packages == null) {
                            Slog.e(TAG, "setToastRateLimitingEnabled method haven't found any "
                                    + "packages for the  given uid: " + uid + ", toast rate "
                                    + "limiter not reset for that uid.");
                            return;
                        }
                        for (String pkg : packages) {
                            mToastRateLimiter.clear(userId, pkg);
                        }
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Failed to reset toast rate limiter for given uid", e);
                    }
                } else {
                    mToastRateLimitingDisabledUids.add(uid);
                }
            }
        }

        @Override
        public void finishToken(String pkg, IBinder token) {
            synchronized (mToastQueue) {
                final long callingId = Binder.clearCallingIdentity();
                try {
                    int index = indexOfToastLocked(pkg, token);
                    if (index >= 0) {
                        ToastRecord record = mToastQueue.get(index);
                        finishWindowTokenLocked(record.windowToken, record.displayId);
                    } else {
                        Slog.w(TAG, "Toast already killed. pkg=" + pkg
                                + " token=" + token);
                    }
                } finally {
                    Binder.restoreCallingIdentity(callingId);
                }
            }
        }

        @Override
        public void enqueueNotificationWithTag(String pkg, String opPkg, String tag, int id,
                Notification notification,
                @CanBeALL @CanBeCURRENT @UserIdInt int userId) throws RemoteException {
            enqueueNotificationInternal(pkg, opPkg, Binder.getCallingUid(),
                    Binder.getCallingPid(), tag, id, notification, userId,
                    /* byForegroundService= */ false, /* isAppProvided= */ true);
        }

        @Override
        public void cancelNotificationWithTag(String pkg, String opPkg, String tag, int id,
                @CanBeALL @CanBeCURRENT @UserIdInt int userId) {
            // Don't allow client applications to cancel foreground service notifs, user-initiated
            // job notifs, computer control notifs, autobundled summaries, or notifs that have been
            // replied to.
            int mustNotHaveFlags = isCallingUidSystem() ? 0 :
                    (FLAG_FOREGROUND_SERVICE | FLAG_USER_INITIATED_JOB | FLAG_AUTOGROUP_SUMMARY
                            | FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY | FLAG_COMPUTER_CONTROL);

            cancelNotificationInternal(pkg, opPkg, Binder.getCallingUid(), Binder.getCallingPid(),
                    tag, id, userId, mustNotHaveFlags);
        }

        @Override
        public void cancelAllNotifications(
                String pkg, @CanBeALL @CanBeCURRENT @UserIdInt int userId) {
            checkCallerIsSystemOrSameApp(pkg);

            userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, true, false, "cancelAllNotifications", pkg);

            // Don't allow the app to cancel active FGS, UIJ or computer control notifications.
            cancelAllNotificationsInt(Binder.getCallingUid(), Binder.getCallingPid(),
                    pkg, null, 0, FLAG_FOREGROUND_SERVICE | FLAG_USER_INITIATED_JOB
                            | FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY | FLAG_COMPUTER_CONTROL,
                    userId, REASON_APP_CANCEL_ALL);
            final int packageImportance = getPackageImportanceWithIdentity(pkg);
            // If cancellation will be prevented due to lifetime extension, we send updates
            // to system UI.
            synchronized (mNotificationLock) {
                maybeNotifySystemUiListenerLifetimeExtendedListLocked(mNotificationList,
                        packageImportance);
                maybeNotifySystemUiListenerLifetimeExtendedListLocked(mEnqueuedNotifications,
                        packageImportance);
            }
        }

        @Override
        public void silenceNotificationSound() {
            checkCallerIsSystem();

            mNotificationDelegate.clearEffects();
        }

        @Override
        public void setNotificationsEnabledForPackage(String pkg, int uid, boolean enabled) {
            enforceSystemOrSystemUI("setNotificationsEnabledForPackage");
            boolean wasEnabled = mPermissionHelper.hasPermission(uid);
            if (wasEnabled == enabled) {
                return;
            }
            mPermissionHelper.setNotificationPermission(
                    pkg, UserHandle.getUserId(uid), enabled, true);
            sendAppBlockStateChangedBroadcast(pkg, uid, !enabled);

            mMetricsLogger.write(new LogMaker(MetricsEvent.ACTION_BAN_APP_NOTES)
                    .setType(MetricsEvent.TYPE_ACTION)
                    .setPackageName(pkg)
                    .setSubtype(enabled ? 1 : 0));
            mNotificationChannelLogger.logAppNotificationsAllowed(uid, pkg, enabled);

            // Outstanding notifications from this package will be cancelled as soon as we get the
            // callback from AppOpsManager.
        }

        /**
         * Updates the enabled state for notifications for the given package (and uid).
         * Additionally, this method marks the app importance as locked by the user, which
         * means
         * that notifications from the app will <b>not</b> be considered for showing a
         * blocking helper.
         *
         * @param pkg     package that owns the notifications to update
         * @param uid     uid of the app providing notifications
         * @param enabled whether notifications should be enabled for the app
         * @see #setNotificationsEnabledForPackage(String, int, boolean)
         */
        @Override
        public void setNotificationsEnabledWithImportanceLockForPackage(
                String pkg, int uid, boolean enabled) {
            setNotificationsEnabledForPackage(pkg, uid, enabled);
        }

        /**
         * Use this when you just want to know if notifications are OK for this package.
         */
        @Override
        public boolean areNotificationsEnabledForPackage(String pkg, int uid) {
            if (Process.isSdkSandboxUid(uid)) {
                return false;
            }
            enforceSystemOrSystemUIOrSamePackage(pkg,
                    "Caller not system or systemui or same package");
            if (UserHandle.getCallingUserId() != UserHandle.getUserId(uid)) {
                getContext().enforceCallingPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS,
                        "canNotifyAsPackage for uid " + uid);
            }

            return areNotificationsEnabledForPackageInt(uid);
        }

        /**
         * @return true if and only if "all" bubbles are allowed from the provided package.
         */
        @Override
        public boolean areBubblesAllowed(String pkg) {
            return getBubblePreferenceForPackage(pkg, Binder.getCallingUid())
                    == BUBBLE_PREFERENCE_ALL;
        }

        /**
         * @return true if this user has bubbles enabled at the feature-level.
         */
        @Override
        public boolean areBubblesEnabled(UserHandle user) {
            if (UserHandle.getCallingUserId() != user.getIdentifier()) {
                getContext().enforceCallingPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS,
                        "areBubblesEnabled for user " + user.getIdentifier());
            }
            return mPreferencesHelper.bubblesEnabled(user);
        }

        @Override
        public int getBubblePreferenceForPackage(String pkg, int uid) {
            enforceSystemOrSystemUIOrSamePackage(pkg,
                    "Caller not system or systemui or same package");

            if (UserHandle.getCallingUserId() != UserHandle.getUserId(uid)) {
                getContext().enforceCallingPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS,
                        "getBubblePreferenceForPackage for uid " + uid);
            }

            return mPreferencesHelper.getBubblePreference(pkg, uid);
        }

        @Override
        public void setBubblesAllowed(String pkg, int uid, int bubblePreference) {
            assertCallerIsSystemOrSystemUiOrShell();
            mPreferencesHelper.setBubblesAllowed(pkg, uid, bubblePreference);
            handleSavePolicyFile();
        }

        @Override
        public boolean shouldHideSilentStatusIcons(String callingPkg) {
            checkCallerIsSameApp(callingPkg);

            if (isCallerSystemOrPhone()
                    || mListeners.isListenerPackage(callingPkg)) {
                return mPreferencesHelper.shouldHideSilentStatusIcons();
            } else {
                throw new SecurityException("Only available for notification listeners");
            }
        }

        @Override
        public void setHideSilentStatusIcons(boolean hide) {
            checkCallerIsSystem();

            mPreferencesHelper.setHideSilentStatusIcons(hide);
            handleSavePolicyFile();

            mListeners.onStatusBarIconsBehaviorChanged(hide);
        }

        @Override
        public void deleteNotificationHistoryItem(String pkg, int uid, long postedTime) {
            checkCallerIsSystem();
            mHistoryManager.deleteNotificationHistoryItem(pkg, uid, postedTime);
        }

        @Override
        public NotificationListenerFilter getListenerFilter(ComponentName cn, int userId) {
            checkCallerIsSystem();
            return mListeners.getNotificationListenerFilter(Pair.create(cn, userId));
        }

        @Override
        public void setListenerFilter(ComponentName cn, int userId,
                NotificationListenerFilter nlf) {
            checkCallerIsSystem();
            mListeners.setNotificationListenerFilter(Pair.create(cn, userId), nlf);
            // TODO (b/173052211): cancel notifications for listeners that can no longer see them
            handleSavePolicyFile();
        }

        @Override
        public int getPackageImportance(String pkg) {
            checkCallerIsSystemOrSameApp(pkg);
            if (mPermissionHelper.hasPermission(Binder.getCallingUid())) {
                return IMPORTANCE_DEFAULT;
            } else {
                return IMPORTANCE_NONE;
            }
        }

        @Override
        public boolean isImportanceLocked(String pkg, int uid) {
            checkCallerIsSystem();
            return mPreferencesHelper.isImportanceLocked(pkg, uid);
        }

        @Override
        public boolean canShowBadge(String pkg, int uid) {
            checkCallerIsSystem();
            return mPreferencesHelper.canShowBadge(pkg, uid);
        }

        @Override
        public void setShowBadge(String pkg, int uid, boolean showBadge) {
            checkCallerIsSystem();
            mPreferencesHelper.setShowBadge(pkg, uid, showBadge);
            handleSavePolicyFile();
        }

        // Returns a list of the enabled profile IDs for the given userId, optionally filtered
        // to those matching the given filter.
        // For full users, this returns the (filtered) list of all profiles associated with this
        // user. For profile users, this method will always return a list of only that profile user
        // (regardless of whether or not a filter is passed in).
        private @NonNull List<Integer> getEnabledProfileIdsFiltered(@UserIdInt int userId,
                @Nullable Predicate<Integer> filter) {
            if (mUm.isProfile(userId)) {
                return List.of(userId);
            }

            List<Integer> userIds = new ArrayList<>();
            for (int id : mUm.getEnabledProfileIds(userId)) {
                if (filter == null || filter.test(id)) {
                    userIds.add(id);
                }
            }
            return userIds;
        }

        @Override
        public void allowAssistantAdjustment(@UserIdInt int userId, String adjustmentType) {
            assertCallerIsSystemOrSystemUiOrShell();
            if (!nmContextualDisplayLaunch() || !KEY_TYPE.equals(adjustmentType)) {
                mAssistants.allowAdjustmentKey(userId, adjustmentType);
            }
            if (KEY_TYPE.equals(adjustmentType)) {
                mNotificationRuleManager.setClassificationAdjustmentState(userId, true);
                // restore any existing channels if they previously existed, for user & any
                // associated profiles that are also independently enabled for this type
                mPreferencesHelper.updateReservedChannels(
                        getEnabledProfileIdsFiltered(userId,
                                id -> mAssistants.isAdjustmentAllowed(id, adjustmentType)),
                        mNotificationRuleManager.getAllowedClassificationTypes(userId), true);
                if (notificationRegroupOnClassification()) {
                    // Consider reclassifying for all profiles of this user. If the adjustment is
                    // disallowed for that profile, it will be removed at a later stage.
                    applyNotificationUpdateForUserProfiles(userId,
                            NotificationManagerService.this::reclassifyNotificationLocked);
                }
                if (nmContextualDisplayLaunch()) {
                    handleSaveRulesFile();
                }
            }
            Binder.withCleanCallingIdentity(() -> {
                getContext().sendBroadcastAsUser(
                    new Intent(ALLOWED_NAS_ADJUSTMENT_KEYS_CHANGED)
                            .putExtra(Intent.EXTRA_USER_ID, userId)
                            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT),
                    UserHandle.SYSTEM, STATUS_BAR_SERVICE);
            });
            handleSavePolicyFile();
        }

        @Override
        public void disallowAssistantAdjustment(@UserIdInt int userId, String adjustmentType) {
            assertCallerIsSystemOrSystemUiOrShell();
            if (!nmContextualDisplayLaunch() || !KEY_TYPE.equals(adjustmentType)) {
                mAssistants.disallowAdjustmentKey(userId, adjustmentType);
            }
            if (KEY_TYPE.equals(adjustmentType)) {
                mNotificationRuleManager.setClassificationAdjustmentState(userId, false);
                // mark any existing channels for all currently allowed types as deleted,
                // including all associated profiles for this user
                mPreferencesHelper.updateReservedChannels(
                        getEnabledProfileIdsFiltered(userId, null),
                        mNotificationRuleManager.getAllowedClassificationTypes(userId), false);
                if (notificationRegroupOnClassification()) {
                    applyNotificationUpdateForUserProfiles(userId,
                            NotificationManagerService.this::unclassifyNotificationLocked);
                }
                if (nmContextualDisplayLaunch()) {
                    handleSaveRulesFile();
                }
            }
            if (KEY_SUMMARIZATION.equals(adjustmentType)) {
                applyNotificationUpdateForUserProfiles(userId,
                        NotificationManagerService.this::unsummarizeNotificationLocked);
            }
            Binder.withCleanCallingIdentity(() -> {
                getContext().sendBroadcastAsUser(
                    new Intent(ALLOWED_NAS_ADJUSTMENT_KEYS_CHANGED)
                            .putExtra(Intent.EXTRA_USER_ID, userId)
                            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT),
                    UserHandle.SYSTEM, STATUS_BAR_SERVICE);
            });
            handleSavePolicyFile();
        }

        @Override
        public void requestSystemAdjustments(List<Adjustment> adjustments) {
            assertCallerIsSystemOrSystemUiOrShell();
            if (enablePersonalContextService()) {
                synchronized (mNotificationLock) {
                    requestSystemAdjustmentsLocked(adjustments);
                }
            }
        }

        @Override
        public void setAdjustmentTypeSupportedState(INotificationListener token,
                @Adjustment.Keys String key, boolean supported) {
            int userId = Binder.getCallingUserHandle().getIdentifier();
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    final ManagedServiceInfo info = mAssistants.checkServiceTokenLocked(token);
                    if (key == null) {
                        return;
                    }
                    userId = info.userid;
                    mAssistants.setAdjustmentKeySupportedState(info.userid,  key, supported);
                }
                if (!supported) {
                    if (KEY_TYPE.equals(key)) {
                        // mark any existing channels for all currently allowed types as deleted,
                        // including all associated profiles for this user
                        mPreferencesHelper.updateReservedChannels(
                                getEnabledProfileIdsFiltered(userId, null),
                                mNotificationRuleManager.getAllowedClassificationTypes(userId),
                                false);
                        if (notificationRegroupOnClassification()) {
                            applyNotificationUpdateForUserProfiles(userId,
                                    NotificationManagerService.this::unclassifyNotificationLocked);
                        }
                    } else if (KEY_SUMMARIZATION.equals(key)) {
                        applyNotificationUpdateForUserProfiles(userId,
                                NotificationManagerService.this::unsummarizeNotificationLocked);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public @NonNull List<String> getUnsupportedAdjustmentTypes() {
            assertCallerIsSystemOrSystemUiOrShell();
            synchronized (mNotificationLock) {
                return new ArrayList<>(mAssistants.getUnsupportedAdjustments(
                        UserHandle.getUserId(Binder.getCallingUid())));
            }
        }

        @Override
        public @NonNull int[] getAllowedClassificationTypes() {
            assertCallerIsSystemOrSystemUiOrShell();
            return mNotificationRuleManager.getAllowedClassificationTypes(
                    UserHandle.getCallingUserId()).stream()
                    .mapToInt(Integer::intValue).toArray();
        }

        @Override
        public void setAssistantClassificationTypeState(int type, boolean enabled) {
            @UserIdInt int userId = UserHandle.getCallingUserId();
            setAssistantClassificationTypeStateForUser(userId, type, enabled);
        }

        @Override
        public void setAssistantClassificationTypeStateForUser(@UserIdInt int userId, int type,
                boolean enabled) {
            assertCallerIsSystemOrSystemUiOrShell();
            mNotificationRuleManager.setAssistantClassificationTypeState(userId, type, enabled);
            // This should only be called for full users; adjustments to the full user's
            // settings also change associated profiles.
            mPreferencesHelper.updateReservedChannels(
                    getEnabledProfileIdsFiltered(userId, null), List.of(type), enabled);

            if (notificationRegroupOnClassification()) {
                if (enabled) {
                    applyNotificationUpdateForUserProfilesAndType(userId, type,
                            NotificationManagerService.this::reclassifyNotificationLocked);
                } else {
                    applyNotificationUpdateForUserProfilesAndChannelType(userId, type,
                            NotificationManagerService.this::unclassifyNotificationLocked);
                }
            }
            handleSavePolicyFile();
            if (nmContextualDisplayLaunch()) {
                handleSaveRulesFile();
            }
        }

        @Override
        public String[] getAdjustmentDeniedPackages(@UserIdInt int userId,
                @Adjustment.Keys String key) {
            assertCallerIsSystemOrSystemUiOrShell();
            if (KEY_TYPE.equals(key)) {
                return mNotificationRuleManager.getClassificationDeniedPackages(userId)
                        .toArray(new String[0]);
            }
            return mAssistants.getAdjustmentDeniedPackages(userId, key);
        }

        @Override
        public boolean isAdjustmentSupportedForPackage(@UserIdInt int userId, String key,
                String pkg) {
            assertCallerIsSystemOrSystemUiOrShell();
            if (KEY_TYPE.equals(key)) {
                return mNotificationRuleManager.isClassificationAllowedForPackage(userId, pkg);
            }
            return mAssistants.isAdjustmentAllowedForPackage(userId, key, pkg);
        }

        @Override
        public void setAdjustmentSupportedForPackage(@UserIdInt int userId,
                @Adjustment.Keys String key, String pkg, boolean enabled) {
            assertCallerIsSystemOrSystemUiOrShell();
            if (KEY_TYPE.equals(key)) {
                mNotificationRuleManager.setClassificationSupportedForPackage(userId, pkg, enabled);
                if (notificationRegroupOnClassification()) {
                    if (enabled) {
                        applyNotificationUpdateForUid(userId,
                                pkg, NotificationManagerService.this::reclassifyNotificationLocked);
                    } else {
                        applyNotificationUpdateForUid(userId,
                                pkg, NotificationManagerService.this::unclassifyNotificationLocked);
                    }
                }
                if (nmContextualDisplayLaunch()) {
                    handleSaveRulesFile();
                }
            } else {
                mAssistants.setAdjustmentSupportedForPackage(userId, key, pkg, enabled);

                if (KEY_SUMMARIZATION.equals(key) && !enabled) {
                    applyNotificationUpdateForUid(userId,
                            pkg, NotificationManagerService.this::unsummarizeNotificationLocked);
                }
            }
            handleSavePolicyFile();
        }

        @Override
        public boolean appCanBePromoted(String pkg, int uid) {
            assertCallerIsSystemOrSystemUiOrShell();
            return checkPostPromotedNotificationPermission(
                    pkg, uid);
        }

        @Override
        public boolean canBePromoted(String callingPkg) {
            checkCallerIsSameApp(callingPkg);
            return checkPostPromotedNotificationPermission(
                    callingPkg, Binder.getCallingUid());
        }


        /**
         * Any changes from SystemUI or Settings should be fromUser == true. Any changes the
         * allowlist should be fromUser == false.
         *
         * Changes from allowlist should be made directly through AppOpMgr.
         *
         */
        @Override
        public void setCanBePromoted(
                String pkg, int uid, boolean promote, boolean fromUser) {
            // Only the OS is allowed to change this permission
            assertCallerIsSystemOrSystemUiOrShell();

            final boolean changed;

            // Use permission backend for allowing promotion per app

            if (!fromUser) {
                Log.e(TAG, "Use PackageManager directly to interact with permission"
                        + "without direct user input");
                return;
            }

            boolean wasPromoted = checkPostPromotedNotificationPermission(pkg, uid);

            int mode = promote ? AppOpsManager.MODE_ALLOWED : AppOpsManager.MODE_IGNORED;

            final long identity = Binder.clearCallingIdentity();
            try {
                mAppOps.setUidMode(OP_POST_PROMOTED_NOTIFICATIONS, uid, mode);
                mPackageManagerClient.updatePermissionFlags(POST_PROMOTED_NOTIFICATIONS, pkg,
                        FLAG_PERMISSION_USER_SET, FLAG_PERMISSION_USER_SET,
                        getUserHandleForUid(uid));
                Log.i(TAG, "Set promoted permission: " + pkg + ", " + uid + "," + mode);
                changed = wasPromoted != promote;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }

            // Update any notifications that are queued or shown
            if (changed) {
                // check for pending/posted notifs from this app and update the flag
                synchronized (mNotificationLock) {
                    // for enqueued we just need to update the flag
                    List<NotificationRecord> enqueued = findAppNotificationByListLocked(
                            mEnqueuedNotifications, pkg, UserHandle.getUserId(uid));
                    for (NotificationRecord r : enqueued) {
                        if (promote && isPromotable(r)) {
                            r.getNotification().flags |= FLAG_PROMOTED_ONGOING;
                        } else if (!promote) {
                            r.getNotification().flags &= ~FLAG_PROMOTED_ONGOING;
                        }
                    }
                    // if the notification is posted we need to update the flag and tell listeners
                    List<NotificationRecord> posted = findAppNotificationByListLocked(
                            mNotificationList, pkg, UserHandle.getUserId(uid));
                    for (NotificationRecord r : posted) {
                        if (promote
                                && !hasFlag(r.getNotification().flags, FLAG_PROMOTED_ONGOING)
                                && isPromotable(r)) {
                            r.getNotification().flags |= FLAG_PROMOTED_ONGOING;
                            // we could set a wake lock here but this value should only change
                            // in response to user action, so the device should be awake long enough
                            // to post
                            PostNotificationTracker tracker =
                                    mPostNotificationTrackerFactory.newTracker(null);
                            // Set false for isAppForeground because that field is only used
                            // for bubbles and messagingstyle can not be promoted
                            mHandler.post(new EnqueueNotificationRunnable(
                                    r.getUser().getIdentifier(),
                                    r, /* isAppForeground */ false, /* isAppProvided= */ false,
                                    tracker));
                        } else if (!promote
                                && hasFlag(r.getNotification().flags, FLAG_PROMOTED_ONGOING)){
                            r.getNotification().flags &= ~FLAG_PROMOTED_ONGOING;
                            PostNotificationTracker tracker =
                                    mPostNotificationTrackerFactory.newTracker(null);
                            mHandler.post(new EnqueueNotificationRunnable(
                                    r.getUser().getIdentifier(),
                                    r, /* isAppForeground */ false, /* isAppProvided= */ false,
                                    tracker));
                        }
                    }
                }
                handleSavePolicyFile();
            }
        }

        @Override
        public boolean hasSentValidMsg(String pkg, int uid) {
            checkCallerIsSystem();
            return mPreferencesHelper.hasSentValidMsg(pkg, uid);
        }

        @Override
        public boolean isInInvalidMsgState(String pkg, int uid) {
            checkCallerIsSystem();
            return mPreferencesHelper.isInInvalidMsgState(pkg, uid);
        }

        @Override
        public boolean hasUserDemotedInvalidMsgApp(String pkg, int uid) {
            checkCallerIsSystem();
            return mPreferencesHelper.hasUserDemotedInvalidMsgApp(pkg, uid);
        }

        @Override
        public void setInvalidMsgAppDemoted(String pkg, int uid, boolean isDemoted) {
            checkCallerIsSystem();
            mPreferencesHelper.setInvalidMsgAppDemoted(pkg, uid, isDemoted);
            handleSavePolicyFile();
        }

        @Override
        public boolean hasSentValidBubble(String pkg, int uid) {
            checkCallerIsSystem();
            return mPreferencesHelper.hasSentValidBubble(pkg, uid);
        }

        @Override
        public void setNotificationDelegate(String callingPkg, String delegate) {
            checkCallerIsSameApp(callingPkg);
            final int callingUid = Binder.getCallingUid();
            UserHandle user = UserHandle.getUserHandleForUid(callingUid);
            if (delegate == null) {
                mPreferencesHelper.revokeNotificationDelegate(callingPkg, Binder.getCallingUid());
                handleSavePolicyFile();
            } else {
                try {
                    ApplicationInfo info =
                            mPackageManager.getApplicationInfo(delegate,
                                    MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                                    user.getIdentifier());
                    if (info != null) {
                        mPreferencesHelper.setNotificationDelegate(
                                callingPkg, callingUid, delegate, info.uid);
                        handleSavePolicyFile();
                    }
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }
            }
        }

        @Override
        public String getNotificationDelegate(String callingPkg) {
            // callable by Settings also
            checkCallerIsSystemOrSameApp(callingPkg);
            return mPreferencesHelper.getNotificationDelegate(callingPkg, Binder.getCallingUid());
        }

        @Override
        public boolean canNotifyAsPackage(String callingPkg, String targetPkg, int userId) {
            checkCallerIsSameApp(callingPkg);
            final int callingUid = Binder.getCallingUid();
            UserHandle user = UserHandle.getUserHandleForUid(callingUid);
            if (user.getIdentifier() != userId) {
                getContext().enforceCallingPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS,
                        "canNotifyAsPackage for user " + userId);
            }
            if (callingPkg.equals(targetPkg)) {
                return true;
            }
            try {
                ApplicationInfo info =
                        mPackageManager.getApplicationInfo(targetPkg,
                                MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                                userId);
                if (info != null) {
                    return mPreferencesHelper.isDelegateAllowed(
                            targetPkg, info.uid, callingPkg, callingUid);
                }
            } catch (RemoteException e) {
                // :(
            }
            return false;
        }

        @Override
        public boolean canUseFullScreenIntent(@NonNull AttributionSource attributionSource) {
            final String packageName = attributionSource.getPackageName();
            final int uid = attributionSource.getUid();
            final int userId = UserHandle.getUserId(uid);
            checkCallerIsSameApp(packageName, uid, userId);

            final ApplicationInfo applicationInfo;
            try {
                applicationInfo = mPackageManagerClient.getApplicationInfoAsUser(
                        packageName, PackageManager.MATCH_DIRECT_BOOT_AUTO, userId);
            } catch (NameNotFoundException e) {
                Slog.e(TAG, "Failed to getApplicationInfo() in canUseFullScreenIntent()", e);
                return false;
            }
            return checkUseFullScreenIntentPermission(attributionSource, applicationInfo,
                    false /* forDataDelivery */);
        }

        @Override
        public void updateNotificationChannelGroupForPackage(String pkg, int uid,
                NotificationChannelGroup group) throws RemoteException {
            enforceSystemOrSystemUI("Caller not system or systemui");
            createNotificationChannelGroup(pkg, uid, group, false, false);
            handleSavePolicyFile();
        }

        @Override
        public void createNotificationChannelGroups(String pkg,
                ParceledListSlice channelGroupList) throws RemoteException {
            checkCallerIsSystemOrSameApp(pkg);
            List<NotificationChannelGroup> groups = channelGroupList.getList();
            final int groupSize = groups.size();
            for (int i = 0; i < groupSize; i++) {
                final NotificationChannelGroup group = groups.get(i);
                createNotificationChannelGroup(pkg, Binder.getCallingUid(), group, true, false);
            }
            handleSavePolicyFile();
        }

        private void createNotificationChannelsImpl(String pkg, int uid,
                ParceledListSlice channelsList) {
            createNotificationChannelsImpl(pkg, uid, channelsList,
                    ActivityTaskManager.INVALID_TASK_ID);
        }

        private void createNotificationChannelsImpl(String pkg, int uid,
                ParceledListSlice channelsList, int startingTaskId) {
            List<NotificationChannel> channels = channelsList.getList();
            final int channelsSize = channels.size();
            ParceledListSlice<NotificationChannel> oldChannels =
                    mPreferencesHelper.getNotificationChannels(pkg, uid, true, false);
            final boolean hadNonBundleChannel =
                    oldChannels != null && !oldChannels.getList().isEmpty();
            boolean needsPolicyFileChange = false;
            boolean hasRequestedNotificationPermission = false;
            for (int i = 0; i < channelsSize; i++) {
                final NotificationChannel channel = channels.get(i);
                Objects.requireNonNull(channel, "channel in list is null");
                needsPolicyFileChange = mPreferencesHelper.createNotificationChannel(pkg, uid,
                        channel, true /* fromTargetApp */,
                        mConditionProviders.isPackageOrComponentAllowed(
                                pkg, UserHandle.getUserId(uid)), Binder.getCallingUid(),
                        isCallerSystemOrSystemUi());
                if (needsPolicyFileChange) {
                    mListeners.notifyNotificationChannelChanged(pkg,
                            UserHandle.getUserHandleForUid(uid),
                            mPreferencesHelper.getNotificationChannel(pkg, uid, channel.getId(),
                                    false),
                            NOTIFICATION_CHANNEL_OR_GROUP_ADDED);
                    boolean hasNonBundleChannel =
                            hadNonBundleChannel || hasRequestedNotificationPermission;
                    if (!hasNonBundleChannel) {
                        ParceledListSlice<NotificationChannel> currChannels =
                                mPreferencesHelper.getNotificationChannels(pkg, uid, true, false);
                        hasNonBundleChannel =
                                currChannels != null && !currChannels.getList().isEmpty();
                    }
                    // show perm prompt if new non-bundle channel added and the user has not
                    // seen the prompt
                    if (!hadNonBundleChannel && hasNonBundleChannel
                            && !hasRequestedNotificationPermission
                            && startingTaskId != ActivityTaskManager.INVALID_TASK_ID) {
                        hasRequestedNotificationPermission = true;
                        if (mPermissionPolicyInternal == null) {
                            mPermissionPolicyInternal =
                                    LocalServices.getService(PermissionPolicyInternal.class);
                        }
                        mHandler.post(new ShowNotificationPermissionPromptRunnable(pkg,
                                UserHandle.getUserId(uid), startingTaskId,
                                mPermissionPolicyInternal));
                    }
                }
            }
            if (needsPolicyFileChange) {
                handleSavePolicyFile();
            }
        }

        @Override
        public void createNotificationChannels(String pkg, ParceledListSlice channelsList) {
            checkCallerIsSystemOrSameApp(pkg);
            int taskId = ActivityTaskManager.INVALID_TASK_ID;
            try {
                int uid = mPackageManager.getPackageUid(pkg, 0,
                        UserHandle.getUserId(Binder.getCallingUid()));
                taskId = mAtm.getTaskToShowPermissionDialogOn(pkg, uid);
            } catch (RemoteException e) {
                // Do nothing
            }
            createNotificationChannelsImpl(pkg, Binder.getCallingUid(), channelsList, taskId);
        }

        @Override
        public void createNotificationChannelsForPackage(String pkg, int uid,
                ParceledListSlice channelsList) {
            enforceSystemOrSystemUI("only system can call this");
            createNotificationChannelsImpl(pkg, uid, channelsList);
        }

        @Override
        public void createConversationNotificationChannelForPackage(String pkg, int uid,
                NotificationChannel parentChannel, String conversationId) {
            enforceSystemOrSystemUI("only system can call this");
            checkNotNull(parentChannel);
            checkNotNull(conversationId);
            String parentId = parentChannel.getId();
            if (parentChannel.isBundleChannel()) {
                Log.v(TAG, "Cannot create conversation for classified notification with pkg:"
                        + pkg + " parentId:" + parentId + " conversationId:" + conversationId);
                return;
            }

            NotificationChannel conversationChannel = parentChannel;
            conversationChannel.setId(UUID.randomUUID().toString());
            conversationChannel.setConversationId(parentId, conversationId);
            createNotificationChannelsImpl(
                    pkg, uid, new ParceledListSlice(Arrays.asList(conversationChannel)));
            mRankingHandler.requestSort();
            handleSavePolicyFile();
        }

        @Override
        public NotificationChannel getNotificationChannel(String callingPkg,
                @CannotBeSpecialUser @UserIdInt int userId, String targetPkg, String channelId) {
            return getConversationNotificationChannel(
                    callingPkg, userId, targetPkg, channelId, true, null);
        }

        @Override
        public NotificationChannel getConversationNotificationChannel(String callingPkg, int userId,
                String targetPkg, String channelId, boolean returnParentIfNoConversationChannel,
                String conversationId) {
            if (isCallerSystemOrSystemUiOrShell()
                    || canNotifyAsPackage(callingPkg, targetPkg, userId)) {
                int targetUid = INVALID_UID;
                try {
                    targetUid = mPackageManagerClient.getPackageUidAsUser(targetPkg, userId);
                } catch (NameNotFoundException e) {
                    /* ignore */
                }
                return mPreferencesHelper.getConversationNotificationChannel(
                        targetPkg, targetUid, channelId, conversationId,
                        returnParentIfNoConversationChannel, false /* includeDeleted */);
            }
            throw new SecurityException("Pkg " + callingPkg
                    + " cannot read channels for " + targetPkg + " in " + userId);
        }

        @Override
        public NotificationChannel getNotificationChannelForPackage(String pkg, int uid,
                String channelId, String conversationId, boolean includeDeleted) {
            checkCallerIsSystem();
            return mPreferencesHelper.getConversationNotificationChannel(
                    pkg, uid, channelId, conversationId, true, includeDeleted);
        }

        // Returns 'true' if the given channel has a notification associated
        // with an active foreground service.
        private void enforceDeletingChannelHasNoFgService(String pkg, int userId,
                String channelId) {
            if (mAmi.hasForegroundServiceNotification(pkg, userId, channelId)) {
                Slog.w(TAG, "Package u" + userId + "/" + pkg
                        + " may not delete notification channel '"
                        + channelId + "' with fg service");
                throw new SecurityException("Not allowed to delete channel " + channelId
                        + " with a foreground service");
            }
        }

        // Throws a security exception if the given channel has a notification associated
        // with an active user-initiated job.
        private void enforceDeletingChannelHasNoUserInitiatedJob(String pkg, int userId,
                String channelId) {
            final JobSchedulerInternal js = LocalServices.getService(JobSchedulerInternal.class);
            if (js != null && js.isNotificationChannelAssociatedWithAnyUserInitiatedJobs(
                    channelId, userId, pkg)) {
                Slog.w(TAG, "Package u" + userId + "/" + pkg
                        + " may not delete notification channel '"
                        + channelId + "' with user-initiated job");
                throw new SecurityException("Not allowed to delete channel " + channelId
                        + " with a user-initiated job");
            }
        }

        @Override
        public void deleteNotificationChannel(String pkg, String channelId) {
            checkCallerIsSystemOrSameApp(pkg);
            final int callingUid = Binder.getCallingUid();
            final boolean isSystemOrSystemUi = isCallerSystemOrSystemUi();
            final int callingUser = UserHandle.getUserId(callingUid);
            if (NotificationChannel.DEFAULT_CHANNEL_ID.equals(channelId)) {
                throw new IllegalArgumentException("Cannot delete default channel");
            }
            // Check for all reserved channels, but do not throw because it's a common
            // preexisting pattern for apps to (try to) delete all channels that don't match
            //  their current desired channel structure
            NotificationChannel exists = mPreferencesHelper.getNotificationChannel(
                    pkg, callingUid, channelId, false);
            if (exists != null && exists.isBundleChannel()) {
                Log.v(TAG, "Package " + pkg + " cannot delete a reserved channel");
                return;
            }

            enforceDeletingChannelHasNoFgService(pkg, callingUser, channelId);
            enforceDeletingChannelHasNoUserInitiatedJob(pkg, callingUser, channelId);
            cancelAllNotificationsInt(MY_UID, MY_PID, pkg, channelId, 0, 0,
                    callingUser, REASON_CHANNEL_REMOVED);

            deleteNotificationChannelDirectly(pkg, callingUid, callingUser,
                    channelId, callingUid,isSystemOrSystemUi);
        }

        @Override
        public NotificationChannelGroup getNotificationChannelGroup(String pkg, String groupId) {
            checkCallerIsSystemOrSameApp(pkg);
            return mPreferencesHelper.getNotificationChannelGroupWithChannels(
                    pkg, Binder.getCallingUid(), groupId, false);
        }

        @Override
        public ParceledListSlice<NotificationChannelGroup> getNotificationChannelGroups(
                String pkg) {
            checkCallerIsSystemOrSameApp(pkg);
            return mPreferencesHelper.getNotificationChannelGroups(pkg, Binder.getCallingUid(),
                    NotificationChannelGroupsHelper.Params.forAllGroups());
        }

        @Override
        public ParceledListSlice<NotificationChannelGroup>
                getNotificationChannelGroupsWithoutChannels(String pkg) {
            checkCallerIsSystemOrSameApp(pkg);
            List<NotificationChannelGroup> groups = new ArrayList<>();
            groups.addAll(mPreferencesHelper.getNotificationChannelGroupsWithoutChannels(pkg,
                    Binder.getCallingUid()));
            return new ParceledListSlice<>(groups);
        }

        @Override
        public void deleteNotificationChannelGroup(String pkg, String groupId) {
            checkCallerIsSystemOrSameApp(pkg);

            final int callingUid = Binder.getCallingUid();
            final boolean isSystemOrSystemUi = isCallerSystemOrSystemUi();
            NotificationChannelGroup groupToDelete =
                    mPreferencesHelper.getNotificationChannelGroupWithChannels(
                            pkg, callingUid, groupId, false);
            if (groupToDelete != null) {
                // Preflight for allowability
                final int userId = UserHandle.getUserId(callingUid);
                List<NotificationChannel> groupChannels = groupToDelete.getChannels();
                for (int i = 0; i < groupChannels.size(); i++) {
                    final String channelId = groupChannels.get(i).getId();
                    enforceDeletingChannelHasNoFgService(pkg, userId, channelId);
                    enforceDeletingChannelHasNoUserInitiatedJob(pkg, userId, channelId);
                }
                List<NotificationChannel> deletedChannels =
                        mPreferencesHelper.deleteNotificationChannelGroup(pkg, callingUid, groupId);
                for (int i = 0; i < deletedChannels.size(); i++) {
                    final NotificationChannel deletedChannel = deletedChannels.get(i);
                    cancelAllNotificationsInt(MY_UID, MY_PID, pkg, deletedChannel.getId(), 0, 0,
                            userId, REASON_CHANNEL_REMOVED
                    );
                    mListeners.notifyNotificationChannelChanged(pkg,
                            UserHandle.getUserHandleForUid(callingUid),
                            deletedChannel,
                            NOTIFICATION_CHANNEL_OR_GROUP_DELETED);
                }
                mListeners.notifyNotificationChannelGroupChanged(
                        pkg, UserHandle.getUserHandleForUid(callingUid), groupToDelete,
                        NOTIFICATION_CHANNEL_OR_GROUP_DELETED);
                handleSavePolicyFile();
            }
        }

        @Override
        public void updateNotificationChannelForPackage(String pkg, int uid,
                NotificationChannel channel) {
            assertCallerIsSystemOrSystemUiOrShell();
            Objects.requireNonNull(channel);
            updateNotificationChannelInt(pkg, uid, channel, false);
        }

        @Override
        public void unlockNotificationChannel(String pkg, int uid, String channelId) {
            assertCallerIsSystemOrSystemUiOrShell();
            mPreferencesHelper.unlockNotificationChannelImportance(pkg, uid, channelId);
            handleSavePolicyFile();
        }

        @Override
        public void unlockAllNotificationChannels() {
            checkCallerIsSystem();
            mPreferencesHelper.unlockAllNotificationChannels();
            handleSavePolicyFile();
        }

        @Override
        public ParceledListSlice<NotificationChannel> getNotificationChannelsForPackage(String pkg,
                int uid, boolean includeDeleted) {
            enforceSystemOrSystemUI("getNotificationChannelsForPackage");
            return mPreferencesHelper.getNotificationChannels(pkg, uid, includeDeleted, true);
        }

        @Override
        public int getNumNotificationChannelsForPackage(String pkg, int uid,
                boolean includeDeleted) {
            enforceSystemOrSystemUI("getNumNotificationChannelsForPackage");
            return NotificationManagerService.this
                    .getNumNotificationChannelsForPackage(pkg, uid, includeDeleted);
        }

        @Override
        public boolean onlyHasDefaultChannel(String pkg, int uid) {
            enforceSystemOrSystemUI("onlyHasDefaultChannel");
            return mPreferencesHelper.onlyHasDefaultChannel(pkg, uid);
        }

        @Override
        public int getDeletedChannelCount(String pkg, int uid) {
            enforceSystemOrSystemUI("getDeletedChannelCount");
            return mPreferencesHelper.getDeletedChannelCount(pkg, uid);
        }

        @Override
        public int getBlockedChannelCount(String pkg, int uid) {
            enforceSystemOrSystemUI("getBlockedChannelCount");
            return mPreferencesHelper.getBlockedChannelCount(pkg, uid);
        }

        @Override
        public ParceledListSlice<ConversationChannelWrapper> getConversations(
                boolean onlyImportant) {
            enforceSystemOrSystemUI("getConversations");
            IntArray userIds = mUserProfiles.getCurrentProfileIds();
            ArrayList<ConversationChannelWrapper> conversations =
                    mPreferencesHelper.getConversations(userIds, onlyImportant);
            for (ConversationChannelWrapper conversation : conversations) {
                if (mShortcutHelper == null) {
                    conversation.setShortcutInfo(null);
                } else {
                    conversation.setShortcutInfo(mShortcutHelper.getValidShortcutInfo(
                            conversation.getNotificationChannel().getConversationId(),
                            conversation.getPkg(),
                            UserHandle.of(UserHandle.getUserId(conversation.getUid()))));
                }
            }
            return new ParceledListSlice<>(conversations);
        }

        @Override
        public ParceledListSlice<NotificationChannelGroup> getNotificationChannelGroupsForPackage(
                String pkg, int uid, boolean includeDeleted) {
            enforceSystemOrSystemUI("getNotificationChannelGroupsForPackage");
            return mPreferencesHelper.getNotificationChannelGroups(pkg, uid,
                    new NotificationChannelGroupsHelper.Params(includeDeleted, true, false, true,
                            null));
        }

        @Override
        public ParceledListSlice<NotificationChannelGroup>
                getRecentBlockedNotificationChannelGroupsForPackage(String pkg, int uid) {
            enforceSystemOrSystemUI("getRecentBlockedNotificationChannelGroupsForPackage");
            Set<String> recentlySentChannels = new HashSet<>();
            long now = System.currentTimeMillis();
            long startTime = now - (DateUtils.DAY_IN_MILLIS * 14);
            UsageEvents events = mUsageStatsManagerInternal.queryEventsForUser(
                UserHandle.getUserId(uid),  startTime, now, UsageEvents.SHOW_ALL_EVENT_DATA);
            // get all channelids that sent notifs in the past 2 weeks
            if (events != null) {
                UsageEvents.Event event = new UsageEvents.Event();
                while (events.hasNextEvent()) {
                    events.getNextEvent(event);
                    if (event.getEventType() == UsageEvents.Event.NOTIFICATION_INTERRUPTION) {
                        if (pkg.equals(event.mPackage)) {
                            String channelId = event.mNotificationChannelId;
                            if (channelId != null) {
                                recentlySentChannels.add(channelId);
                            }
                        }
                    }
                }
            }

            return mPreferencesHelper.getNotificationChannelGroups(pkg, uid,
                    NotificationChannelGroupsHelper.Params.onlySpecifiedOrBlockedChannels(
                            recentlySentChannels));
        }

        @Override
        public ParceledListSlice<ConversationChannelWrapper> getConversationsForPackage(String pkg,
                int uid) {
            enforceSystemOrSystemUI("getConversationsForPackage");
            ArrayList<ConversationChannelWrapper> conversations =
                    mPreferencesHelper.getConversations(pkg, uid);
            for (ConversationChannelWrapper conversation : conversations) {
                if (mShortcutHelper == null) {
                    conversation.setShortcutInfo(null);
                } else {
                    conversation.setShortcutInfo(mShortcutHelper.getValidShortcutInfo(
                            conversation.getNotificationChannel().getConversationId(),
                            pkg,
                            UserHandle.of(UserHandle.getUserId(uid))));
                }
            }
            return new ParceledListSlice<>(conversations);
        }

        @Override
        public NotificationChannelGroup getPopulatedNotificationChannelGroupForPackage(
                String pkg, int uid, String groupId, boolean includeDeleted) {
            enforceSystemOrSystemUI("getPopulatedNotificationChannelGroupForPackage");
            return mPreferencesHelper.getNotificationChannelGroupWithChannels(
                    pkg, uid, groupId, includeDeleted);
        }

        @Override
        public NotificationChannelGroup getNotificationChannelGroupForPackage(
                String groupId, String pkg, int uid) {
            enforceSystemOrSystemUI("getNotificationChannelGroupForPackage");
            return mPreferencesHelper.getNotificationChannelGroup(groupId, pkg, uid);
        }

        @Override
        public ParceledListSlice<NotificationChannel> getNotificationChannels(String callingPkg,
                String targetPkg, @CannotBeSpecialUser @UserIdInt int userId) {
            if (canNotifyAsPackage(callingPkg, targetPkg, userId)
                || isCallingUidSystem()) {
                int targetUid = INVALID_UID;
                try {
                    targetUid = mPackageManagerClient.getPackageUidAsUser(targetPkg, userId);
                } catch (NameNotFoundException e) {
                    /* ignore */
                }
                return mPreferencesHelper.getNotificationChannels(
                        targetPkg, targetUid, false /* includeDeleted */, true);
            }
            throw new SecurityException("Pkg " + callingPkg
                    + " cannot read channels for " + targetPkg + " in " + userId);
        }

        @Override
        public ParceledListSlice<NotificationChannel> getNotificationChannelsBypassingDnd(
                String pkg, int uid) {
            checkCallerIsSystem();
            if (!areNotificationsEnabledForPackage(pkg, uid)) {
                return ParceledListSlice.emptyList();
            }
            return mPreferencesHelper.getNotificationChannelsBypassingDnd(pkg, uid);
        }

        @Override
        public ParceledListSlice<ZenBypassingApp> getPackagesBypassingDnd(int userId)
                throws RemoteException {
            checkCallerIsSystem();

            UserHandle user = UserHandle.of(userId);
            ArrayList<ZenBypassingApp> bypassing =
                    mPreferencesHelper.getPackagesBypassingDnd(userId);
            for (int i = bypassing.size() - 1; i >= 0; i--) {
                String pkg = bypassing.get(i).getPkg();
                if (!areNotificationsEnabledForPackage(pkg, getUidForPackageAndUser(pkg, user))) {
                    bypassing.remove(i);
                }
            }
            return new ParceledListSlice<>(bypassing);
        }

        @Override
        public boolean areChannelsBypassingDnd() {
            return mZenModeHelper.getConsolidatedNotificationPolicy().allowPriorityChannels()
                    && mPreferencesHelper.hasPriorityChannels();
        }

        @Override
        public List<String> getPackagesWithAnyChannels(int userId) throws RemoteException {
            checkCallerIsSystem();
            UserHandle user = UserHandle.of(userId);
            List<String> packages = mPreferencesHelper.getPackagesWithAnyChannels(userId);
            for (int i = packages.size() - 1; i >= 0; i--) {
                String pkg = packages.get(i);
                if (!areNotificationsEnabledForPackage(pkg, getUidForPackageAndUser(pkg, user))) {
                    packages.remove(i);
                }
            }
            return packages;
        }

        @Override
        public void clearData(String packageName, int uid, boolean fromApp) throws RemoteException {
            boolean packagesChanged = false;
            checkCallerIsSystem();
            // Cancel posted notifications
            final int userId = UserHandle.getUserId(uid);
            cancelAllNotificationsInt(MY_UID, MY_PID, packageName, null, 0, 0,
                    UserHandle.getUserId(Binder.getCallingUid()), REASON_CLEAR_DATA);

            // Zen
            packagesChanged |=
                    mConditionProviders.resetPackage(packageName, userId);

            // Listener
            ArrayMap<Boolean, ArrayList<ComponentName>> changedListeners =
                    mListeners.resetComponents(packageName, userId);
            packagesChanged |= changedListeners.get(true).size() > 0
                    || changedListeners.get(false).size() > 0;

            // When a listener is enabled, we enable the dnd package as a secondary
            for (int i = 0; i < changedListeners.get(true).size(); i++) {
                mConditionProviders.setPackageOrComponentEnabled(
                        changedListeners.get(true).get(i).getPackageName(),
                        userId, false, true);
            }

            // Assistant
            ArrayMap<Boolean, ArrayList<ComponentName>> changedAssistants =
                    mAssistants.resetComponents(packageName, userId);
            packagesChanged |= changedAssistants.get(true).size() > 0
                    || changedAssistants.get(false).size() > 0;

            // we want only one assistant enabled
            for (int i = 1; i < changedAssistants.get(true).size(); i++) {
                mAssistants.setPackageOrComponentEnabled(
                        changedAssistants.get(true).get(i).flattenToString(),
                        userId, true, false);
            }

            // When the default assistant is enabled, we enable the dnd package as a secondary
            if (changedAssistants.get(true).size() > 0) {
                //we want only one assistant active
                mConditionProviders
                        .setPackageOrComponentEnabled(
                                changedAssistants.get(true).get(0).getPackageName(),
                                userId, false, true);

            }

            // Snoozing
            List<NotificationRecord> snoozed = mSnoozeHelper.clearData(UserHandle.getUserId(uid),
                    packageName);
            for (NotificationRecord r : snoozed) {
                markOffloadedBitmapsForDeletion(r);
            }

            // Reset notification preferences
            if (!fromApp) {
                mPreferencesHelper.clearData(packageName, uid);
            }

            if (packagesChanged) {
                getContext().sendBroadcastAsUser(new Intent(
                                ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED)
                                .setPackage(packageName)
                                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT),
                        UserHandle.of(userId), null);
            }

            handleSavePolicyFile();
        }

        @Override
        public List<String> getAllowedAssistantAdjustments(String pkg) {
            checkCallerIsSystemOrSameApp(pkg);

            if (!isCallerSystemOrPhone()
                    && !mAssistants.isPackageAllowed(pkg, UserHandle.getCallingUserId())) {
                    throw new SecurityException("Not currently an assistant");
            }
            int userId = UserHandle.getCallingUserId();
            List<String> allowed =
                    new ArrayList<>(mAssistants.getAllowedAssistantAdjustments(userId));
            if (nmContextualDisplayLaunch()
                    && mNotificationRuleManager.isClassificationAdjustmentAllowed(userId)) {
                allowed.add(KEY_TYPE);
            }
            return allowed;
        }

        @Override
        public List<String> getAllowedAssistantAdjustmentsForUser(@UserIdInt int userId) {
            checkCallerIsSystemOrSystemUi();
            List<String> allowed =
                    new ArrayList<>(mAssistants.getAllowedAssistantAdjustments(userId));
            if (nmContextualDisplayLaunch()
                    && mNotificationRuleManager.isClassificationAdjustmentAllowed(userId)) {
                allowed.add(KEY_TYPE);
            }
            return allowed;
        }

        /**
         * @deprecated Use {@link #getActiveNotificationsWithAttribution(String, String)} instead.
         */
        @Deprecated
        @Override
        public StatusBarNotification[] getActiveNotifications(String callingPkg) {
            return getActiveNotificationsWithAttribution(callingPkg, null);
        }

        /**
         * System-only API for getting a list of current (i.e. not cleared) notifications.
         *
         * Requires ACCESS_NOTIFICATIONS which is signature|system.
         * @returns A list of all the notifications, in natural order.
         */
        @Override
        @EnforcePermission(android.Manifest.permission.ACCESS_NOTIFICATIONS)
        public StatusBarNotification[] getActiveNotificationsWithAttribution(String callingPkg,
                String callingAttributionTag) {
            // enforce() will ensure the calling uid has the correct permission
            getActiveNotificationsWithAttribution_enforcePermission();

            ArrayList<StatusBarNotification> tmp = new ArrayList<>();
            int uid = Binder.getCallingUid();

            ArrayList<Integer> currentUsers = new ArrayList<>();
            currentUsers.add(USER_ALL);
            Binder.withCleanCallingIdentity(() -> {
                for (int user : mUm.getProfileIds(ActivityManager.getCurrentUser(), false)) {
                    currentUsers.add(user);
                }
            });

            // noteOp will check to make sure the callingPkg matches the uid
            int mode = mAppOps.noteOpNoThrow(AppOpsManager.OP_ACCESS_NOTIFICATIONS, uid, callingPkg,
                        callingAttributionTag, null);
            if (mode == MODE_ALLOWED || mode == MODE_DEFAULT) {
                synchronized (mNotificationLock) {
                    final int N = mNotificationList.size();
                    for (int i = 0; i < N; i++) {
                        final StatusBarNotification sbn = mNotificationList.get(i).getSbn();
                        if (currentUsers.contains(sbn.getUserId())) {
                            tmp.add(sbn);
                        }
                    }
                }
            }
            return tmp.toArray(new StatusBarNotification[tmp.size()]);
        }

        /**
         * Public API for getting a list of current notifications for the calling package/uid.
         *
         * Note that since notification posting is done asynchronously, this will not return
         * notifications that are in the process of being posted.
         *
         * From {@link Build.VERSION_CODES#Q}, will also return notifications you've posted as
         * an app's notification delegate via
         * {@link NotificationManager#notifyAsPackage(String, String, int, Notification)}.
         *
         * @returns A list of all the package's notifications, in natural order.
         */
        @Override
        public ParceledListSlice<StatusBarNotification> getAppActiveNotifications(String pkg,
                @CanBeALL @CanBeCURRENT @UserIdInt int incomingUserId) {
            checkCallerIsSystemOrSameApp(pkg);
            int userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), incomingUserId, true, false,
                    "getAppActiveNotifications", pkg);
            synchronized (mNotificationLock) {
                final ArrayMap<String, StatusBarNotification> map
                        = new ArrayMap<>(mNotificationList.size() + mEnqueuedNotifications.size());
                final int N = mNotificationList.size();
                for (int i = 0; i < N; i++) {
                    StatusBarNotification sbn = sanitizeSbn(pkg, userId,
                            mNotificationList.get(i).getSbn());
                    if (sbn != null) {
                        map.put(sbn.getKey(), sbn);
                    }
                }
                for(NotificationRecord snoozed: mSnoozeHelper.getSnoozed(userId, pkg)) {
                    StatusBarNotification sbn = sanitizeSbn(pkg, userId, snoozed.getSbn());
                    if (sbn != null) {
                        map.put(sbn.getKey(), sbn);
                    }
                }
                final int M = mEnqueuedNotifications.size();
                for (int i = 0; i < M; i++) {
                    StatusBarNotification sbn = sanitizeSbn(pkg, userId,
                            mEnqueuedNotifications.get(i).getSbn());
                    if (sbn != null) {
                        map.put(sbn.getKey(), sbn); // pending update overwrites existing post here
                    }
                }
                final ArrayList<StatusBarNotification> list = new ArrayList<>(map.size());
                list.addAll(map.values());
                return new ParceledListSlice<StatusBarNotification>(list);
            }
        }

        /** Notifications returned here will have allowlistToken stripped from them. */
        private StatusBarNotification sanitizeSbn(String pkg, int userId,
                StatusBarNotification sbn) {
            if (sbn.getUserId() == userId) {
                if (sbn.getPackageName().equals(pkg) || sbn.getOpPkg().equals(pkg)) {
                    // We could pass back a cloneLight() but clients might get confused and
                    // try to send this thing back to notify() again, which would not work
                    // very well.
                    Notification notification = sbn.getNotification().clone();
                    // Remove background token before returning notification to untrusted app, this
                    // ensures the app isn't able to perform background operations that are
                    // associated with notification interactions.
                    notification.overrideAllowlistToken(null);
                    return new StatusBarNotification(
                            sbn.getPackageName(),
                            sbn.getOpPkg(),
                            sbn.getId(), sbn.getTag(), sbn.getUid(), sbn.getInitialPid(),
                            notification,
                            sbn.getUser(), sbn.getOverrideGroupKey(), sbn.getPostTime());
                }
            }
            return null;
        }

        /**
         * @deprecated Use {@link #getHistoricalNotificationsWithAttribution} instead.
         */
        @Deprecated
        @Override
        @RequiresPermission(android.Manifest.permission.ACCESS_NOTIFICATIONS)
        public StatusBarNotification[] getHistoricalNotifications(String callingPkg, int count,
                boolean includeSnoozed) {
            return getHistoricalNotificationsWithAttribution(callingPkg, null, count,
                    includeSnoozed);
        }

        /**
         * System-only API for getting a list of recent (cleared, no longer shown) notifications.
         */
        @Override
        @RequiresPermission(android.Manifest.permission.ACCESS_NOTIFICATIONS)
        @EnforcePermission(android.Manifest.permission.ACCESS_NOTIFICATIONS)
        public StatusBarNotification[] getHistoricalNotificationsWithAttribution(String callingPkg,
                String callingAttributionTag, int count, boolean includeSnoozed) {
            // enforce() will ensure the calling uid has the correct permission
            getHistoricalNotificationsWithAttribution_enforcePermission();

            StatusBarNotification[] tmp = null;
            int uid = Binder.getCallingUid();

            // noteOp will check to make sure the callingPkg matches the uid
            int mode = mAppOps.noteOpNoThrow(AppOpsManager.OP_ACCESS_NOTIFICATIONS, uid, callingPkg,
                        callingAttributionTag, null);
            if (mode == MODE_ALLOWED || mode == MODE_DEFAULT) {
                synchronized (mArchive) {
                    tmp = mArchive.getArray(mUm, count, includeSnoozed);
                }
            }
            return tmp;
        }

        /**
         * System-only API for getting a list of historical notifications. May contain multiple days
         * of notifications.
         */
        @Override
        @WorkerThread
        @RequiresPermission(android.Manifest.permission.ACCESS_NOTIFICATIONS)
        @EnforcePermission(android.Manifest.permission.ACCESS_NOTIFICATIONS)
        public NotificationHistory getNotificationHistory(String callingPkg,
                String callingAttributionTag) {
            // enforce() will ensure the calling uid has the correct permission
            getNotificationHistory_enforcePermission();
            int uid = Binder.getCallingUid();

            // noteOp will check to make sure the callingPkg matches the uid
            int mode = mAppOps.noteOpNoThrow(AppOpsManager.OP_ACCESS_NOTIFICATIONS, uid, callingPkg,
                        callingAttributionTag, null);
            if (mode == MODE_ALLOWED || mode == MODE_DEFAULT) {
                IntArray currentUserIds = mUserProfiles.getCurrentProfileIds();
                Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "notifHistoryReadHistory");
                try {
                    return mHistoryManager.readNotificationHistory(currentUserIds.toArray());
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
                }
            }
            return new NotificationHistory();
        }

        /**
         * Register a listener to be notified when a call notification is posted or removed
         * for a specific package and user.
         * @param packageName Which package to monitor
         * @param userHandle Which user to monitor
         * @param listener Listener to register
         */
        @Override
        @EnforcePermission(allOf = {
                android.Manifest.permission.INTERACT_ACROSS_USERS,
                android.Manifest.permission.ACCESS_NOTIFICATIONS})
        public void registerCallNotificationEventListener(String packageName, UserHandle userHandle,
                ICallNotificationEventCallback listener) {
            registerCallNotificationEventListener_enforcePermission();

            final int userId = userHandle.getIdentifier() != UserHandle.USER_CURRENT
                    ? userHandle.getIdentifier() : mAmi.getCurrentUserId();

            synchronized (mCallNotificationEventCallbacks) {
                ArrayMap<Integer, RemoteCallbackList<ICallNotificationEventCallback>>
                        callbacksForPackage =
                        mCallNotificationEventCallbacks.getOrDefault(packageName, new ArrayMap<>());
                RemoteCallbackList<ICallNotificationEventCallback> callbackList =
                        callbacksForPackage.getOrDefault(userId, new RemoteCallbackList<>());

                if (callbackList.register(listener)) {
                    callbacksForPackage.put(userId, callbackList);
                    mCallNotificationEventCallbacks.put(packageName, callbacksForPackage);
                } else {
                    Log.e(TAG,
                            "registerCallNotificationEventListener failed to register listener: "
                                + packageName + " " + userHandle + " " + listener);
                    return;
                }
            }

            synchronized (mNotificationLock) {
                for (NotificationRecord r : mNotificationList) {
                    if (r.getNotification().isStyle(Notification.CallStyle.class)
                            && notificationMatchesUserId(r, userId, false)
                            && r.getSbn().getPackageName().equals(packageName)) {
                        try {
                            listener.onCallNotificationPosted(packageName, r.getUser());
                        } catch (RemoteException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
        }

        /**
         * Unregister a listener that was previously
         * registered with {@link #registerCallNotificationEventListener}
         *
         * @param packageName Which package to stop monitoring
         * @param userHandle Which user to stop monitoring
         * @param listener Listener to unregister
         */
        @Override
        @EnforcePermission(allOf = {
            android.Manifest.permission.INTERACT_ACROSS_USERS,
            android.Manifest.permission.ACCESS_NOTIFICATIONS})
        public void unregisterCallNotificationEventListener(String packageName,
                    UserHandle userHandle, ICallNotificationEventCallback listener) {
            unregisterCallNotificationEventListener_enforcePermission();
            synchronized (mCallNotificationEventCallbacks) {
                final int userId = userHandle.getIdentifier() != UserHandle.USER_CURRENT
                        ? userHandle.getIdentifier() : mAmi.getCurrentUserId();

                ArrayMap<Integer, RemoteCallbackList<ICallNotificationEventCallback>>
                        callbacksForPackage = mCallNotificationEventCallbacks.get(packageName);
                if (callbacksForPackage == null) {
                    return;
                }
                RemoteCallbackList<ICallNotificationEventCallback> callbackList =
                        callbacksForPackage.get(userId);
                if (callbackList == null) {
                    return;
                }
                if (!callbackList.unregister(listener)) {
                    Log.e(TAG,
                            "unregisterCallNotificationEventListener listener not found for: "
                            + packageName + " " + userHandle + " " + listener);
                }
            }
        }

        /**
         * Register a listener binder directly with the notification manager.
         *
         * Only works with system callers. Apps should extend
         * {@link NotificationListenerService}.
         */
        @Override
        public void registerListener(final INotificationListener listener,
                final ComponentName component, final int userid) {
            enforceSystemOrSystemUI("INotificationManager.registerListener");
            mListeners.registerSystemService(listener, component, userid, Binder.getCallingUid());
        }

        /**
         * Remove a listener binder directly
         */
        @Override
        public void unregisterListener(INotificationListener token, int userid) {
            mListeners.unregisterService(token, userid);
        }

        /**
         * Allow an INotificationListener to simulate a "clear all" operation.
         *
         * @param token The binder for the listener, to check that the caller is allowed
         *
         * @see com.android.server.StatusBarManagerService.NotificationCallbacks#onClearAllNotifications
         */
        @Override
        public void cancelNotificationsFromListener(INotificationListener token, String[] keys) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final long identity = Binder.clearCallingIdentity();
            boolean notificationsRapidlyCleared = false;
            final String pkg;
            final int packageImportance;
            final ManagedServiceInfo info;
            try {
                synchronized (mNotificationLock) {
                    info = mListeners.checkServiceTokenLocked(token);
                    pkg = info.component.getPackageName();
                }
                packageImportance = getPackageImportanceWithIdentity(pkg);
                synchronized (mNotificationLock) {
                    // Cancellation reason. If the token comes from assistant, label the
                    // cancellation as coming from the assistant; default to LISTENER_CANCEL.
                    int reason = REASON_LISTENER_CANCEL;
                    if (mAssistants.isServiceTokenValidLocked(token)) {
                        reason = REASON_ASSISTANT_CANCEL;
                    }

                    if (keys != null) {
                        final int N = keys.length;
                        for (int i = 0; i < N; i++) {
                            NotificationRecord r = mNotificationsByKey.get(keys[i]);
                            if (r == null) continue;
                            final int userId = r.getSbn().getUserId();
                            if (!isVisibleToListener(r.getSbn(), r.getNotificationType(), info)) {
                                continue;
                            }
                            notificationsRapidlyCleared = notificationsRapidlyCleared
                                    || isNotificationRecent(r.getUpdateTimeMs());
                            cancelNotificationFromListenerLocked(info, callingUid, callingPid,
                                    r.getSbn().getPackageName(), r.getSbn().getTag(),
                                    r.getSbn().getId(), userId, reason);
                        }
                    } else {
                        for (NotificationRecord notificationRecord : mNotificationList) {
                            if (isNotificationRecent(notificationRecord.getUpdateTimeMs())) {
                                notificationsRapidlyCleared = true;
                                break;
                            }
                        }
                        cancelAllLocked(callingUid, callingPid, info.userid,
                                REASON_LISTENER_CANCEL_ALL, info, info.supportsProfiles(),
                                FLAG_ONGOING_EVENT | FLAG_NO_CLEAR
                                        | FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY);
                        // If cancellation will be prevented due to lifetime extension, we send
                        // an update to system UI.
                        maybeNotifySystemUiListenerLifetimeExtendedListLocked(
                                mNotificationList, packageImportance);
                        maybeNotifySystemUiListenerLifetimeExtendedListLocked(
                                mEnqueuedNotifications, packageImportance);
                    }
                }
                if (notificationsRapidlyCleared) {
                    mAppOps.noteOpNoThrow(AppOpsManager.OP_RAPID_CLEAR_NOTIFICATIONS_BY_LISTENER,
                            callingUid, pkg, /* attributionTag= */ null, /* message= */ null);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
         * Handle request from an approved listener to re-enable itself.
         *
         * @param component The componenet to be re-enabled, caller must match package.
         */
        @Override
        public void requestBindListener(ComponentName component) {
            checkCallerIsSystemOrSameApp(component.getPackageName());
            int uid = Binder.getCallingUid();
            int userId = UserHandle.getUserId(uid);
            final long identity = Binder.clearCallingIdentity();
            try {
                boolean isAssistantEnabled = managedServicesConcurrentMultiuser()
                        ? mAssistants.isComponentEnabledForUser(component, userId)
                        : mAssistants.isComponentEnabledForCurrentProfiles(component);
                ManagedServices manager = isAssistantEnabled ? mAssistants : mListeners;
                manager.setComponentState(component, UserHandle.getUserId(uid), true);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void requestUnbindListener(INotificationListener token) {
            int uid = Binder.getCallingUid();
            final long identity = Binder.clearCallingIdentity();
            try {
                // allow bound services to disable themselves
                synchronized (mNotificationLock) {
                    final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                    info.getOwner().setComponentState(
                            info.component, UserHandle.getUserId(uid), false);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void requestUnbindListenerComponent(ComponentName component) {
            checkCallerIsSameApp(component.getPackageName());
            int uid = Binder.getCallingUid();
            int userId = UserHandle.getUserId(uid);
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    boolean isAssistantEnabled = managedServicesConcurrentMultiuser()
                            ? mAssistants.isComponentEnabledForUser(component, userId)
                            : mAssistants.isComponentEnabledForCurrentProfiles(component);
                    ManagedServices manager = isAssistantEnabled ? mAssistants : mListeners;
                    if (manager.isPackageOrComponentAllowed(component.flattenToString(), userId)) {
                        manager.setComponentState(component, userId, false);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void setNotificationsShownFromListener(INotificationListener token, String[] keys) {
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                    if (keys == null) {
                        return;
                    }
                    ArrayList<NotificationRecord> seen = new ArrayList<>();
                    final int n = keys.length;
                    for (int i = 0; i < n; i++) {
                        NotificationRecord r = mNotificationsByKey.get(keys[i]);
                        if (r == null) continue;
                        if (!isVisibleToListener(r.getSbn(), r.getNotificationType(), info)) {
                            continue;
                        }
                        seen.add(r);
                        if (!r.isSeen()) {
                            if (DBG) Slog.d(TAG, "Marking notification as seen " + keys[i]);
                            reportSeen(r);
                            r.setSeen();
                            maybeRecordInterruptionLocked(r);
                        }
                    }
                    if (!seen.isEmpty()) {
                        mAssistants.onNotificationsSeenLocked(seen);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
         * Allow an INotificationListener to simulate clearing (dismissing) a single notification.
         *
         * @param info The binder for the listener, to check that the caller is allowed
         *
         * @see com.android.server.StatusBarManagerService.NotificationCallbacks#onNotificationClear
         */
        @GuardedBy("mNotificationLock")
        private void cancelNotificationFromListenerLocked(ManagedServiceInfo info,
                int callingUid, int callingPid, String pkg, String tag, int id, int userId,
                int reason) {
            final FlagChecker flagChecker = (flags) -> {
                // must not have the direct reply flag
                if ((flags & FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY) != 0) {
                    return false;
                }
                // ongoing can only be cancelled by listeners if also promoted.
                return (flags & FLAG_ONGOING_EVENT) == 0 || (flags & FLAG_PROMOTED_ONGOING) != 0;
            };
            cancelNotification(callingUid, callingPid, pkg, tag, id, 0 /* mustHaveFlags */,
                    flagChecker,
                    true,
                    userId, reason, info);
        }

        /**
         * Allow an INotificationListener to snooze a single notification until a context.
         *
         * @param token The binder for the listener, to check that the caller is allowed
         */
        @Override
        public void snoozeNotificationUntilContextFromListener(INotificationListener token,
                String key, String snoozeCriterionId) {
            final int callingUid = Binder.getCallingUid();
            final long identity = Binder.clearCallingIdentity();
            try {
                snoozeNotificationInt(callingUid, token, key, SNOOZE_UNTIL_UNSPECIFIED,
                        snoozeCriterionId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
         * Allow an INotificationListener to snooze a single notification until a time.
         *
         * @param token The binder for the listener, to check that the caller is allowed
         */
        @Override
        public void snoozeNotificationUntilFromListener(INotificationListener token, String key,
                long duration) {
            final int callingUid = Binder.getCallingUid();
            final long identity = Binder.clearCallingIdentity();
            try {
                snoozeNotificationInt(callingUid, token, key, duration, null);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
         * Allows the notification assistant to un-snooze a single notification.
         *
         * @param token The binder for the assistant, to check that the caller is allowed
         */
        @Override
        public void unsnoozeNotificationFromAssistant(INotificationListener token, String key) {
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    final ManagedServiceInfo info =
                            mAssistants.checkServiceTokenLocked(token);
                    unsnoozeNotificationInt(key, info, false);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
         * Allows the notification assistant to un-snooze a single notification.
         *
         * @param token The binder for the listener, to check that the caller is allowed
         */
        @Override
        public void unsnoozeNotificationFromSystemListener(INotificationListener token,
                String key) {
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    final ManagedServiceInfo info =
                            mListeners.checkServiceTokenLocked(token);
                    if (!info.isSystem) {
                        throw new SecurityException("Not allowed to unsnooze before deadline");
                    }
                    unsnoozeNotificationInt(key, info, true);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
         * Allows an app to set an initial notification listener filter
         *
         * @param token The binder for the listener, to check that the caller is allowed
         */
        @Override
        public void migrateNotificationFilter(INotificationListener token, int defaultTypes,
                List<String> disallowedApps) {
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);

                    Pair key = Pair.create(info.component, info.userid);

                    NotificationListenerFilter nlf = mListeners.getNotificationListenerFilter(key);
                    if (nlf == null) {
                        nlf = new NotificationListenerFilter();
                    }
                    if (nlf.getDisallowedPackages().isEmpty() && disallowedApps != null) {
                        for (String pkg : disallowedApps) {
                            // block the current user's version and any work profile versions
                            for (int userId : mUm.getProfileIds(info.userid, false)) {
                                try {
                                    int uid = getUidForPackageAndUser(pkg, UserHandle.of(userId));
                                    if (uid != INVALID_UID) {
                                        VersionedPackage vp = new VersionedPackage(pkg, uid);
                                        nlf.addPackage(vp);
                                    }
                                } catch (Exception e) {
                                    // pkg doesn't exist on that user; skip
                                }
                            }
                        }
                    }
                    if (nlf.areAllTypesAllowed()) {
                        nlf.setTypes(defaultTypes);
                    }
                    mListeners.setNotificationListenerFilter(key, nlf);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
         * Allow an INotificationListener to simulate clearing (dismissing) a single notification.
         *
         * @param token The binder for the listener, to check that the caller is allowed
         *
         * @see com.android.server.StatusBarManagerService.NotificationCallbacks#onNotificationClear
         */
        @Override
        public void cancelNotificationFromListener(INotificationListener token, String pkg,
                String tag, int id) {
            Slog.e(TAG, "Ignoring deprecated cancelNotification(pkg, tag, id) use " +
                    "cancelNotification(key) instead.");
        }

        /**
         * Allow an INotificationListener to request the list of outstanding notifications seen by
         * the current user. Useful when starting up, after which point the listener callbacks
         * should be used.
         *
         * @param token The binder for the listener, to check that the caller is allowed
         * @param keys An array of notification keys to fetch, or null to fetch everything
         * @returns The return value will contain the notifications specified in keys, in that
         *      order, or if keys is null, all the notifications, in natural order.
         */
        @Override
        public ParceledListSlice<StatusBarNotification> getActiveNotificationsFromListener(
                INotificationListener token, String[] keys, int trim) {
            synchronized (mNotificationLock) {
                final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                final boolean getKeys = keys != null;
                final int N = getKeys ? keys.length : mNotificationList.size();
                final ArrayList<StatusBarNotification> list = new ArrayList<>(N);
                for (int i=0; i<N; i++) {
                    final NotificationRecord r = getKeys
                            ? mNotificationsByKey.get(keys[i])
                            : mNotificationList.get(i);
                    addToListIfNeeded(r, info, list, trim);
                }
                return new ParceledListSlice<>(list);
            }
        }

        /**
         * Allow an INotificationListener to request the list of outstanding snoozed notifications
         * seen by the current user. Useful when starting up, after which point the listener
         * callbacks should be used.
         *
         * @param token The binder for the listener, to check that the caller is allowed
         * @returns The return value will contain the notifications specified in keys, in that
         *      order, or if keys is null, all the notifications, in natural order.
         */
        @Override
        public ParceledListSlice<StatusBarNotification> getSnoozedNotificationsFromListener(
                INotificationListener token, int trim) {
            synchronized (mNotificationLock) {
                final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                List<NotificationRecord> snoozedRecords = mSnoozeHelper.getSnoozed();
                final int N = snoozedRecords.size();
                final ArrayList<StatusBarNotification> list = new ArrayList<>(N);
                for (int i=0; i < N; i++) {
                    addToListIfNeeded(snoozedRecords.get(i), info, list, trim);
                }
                return new ParceledListSlice<>(list);
            }
        }

        private void addToListIfNeeded(NotificationRecord r, ManagedServiceInfo info,
                ArrayList<StatusBarNotification> notifications, int trim) {
            if (r == null) return;
            StatusBarNotification sbn = r.getSbn();
            if (!isVisibleToListener(sbn, r.getNotificationType(), info)) return;
            if (mListeners.hasSensitiveContent(r) && !mListeners.isUidTrusted(info.uid)) {
                notifications.add(mListeners.redactSbnForOtp(sbn));
            } else {
                notifications.add((trim == TRIM_FULL) ? sbn : sbn.cloneLight());
            }

        }

        @Override
        public void clearRequestedListenerHints(INotificationListener token) {
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                    removeDisabledHints(info);
                    updateListenerHintsLocked();
                    updateEffectsSuppressorLocked();
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void requestHintsFromListener(INotificationListener token, int hints) {
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                    final int disableEffectsMask = HINT_HOST_DISABLE_EFFECTS
                            | HINT_HOST_DISABLE_NOTIFICATION_EFFECTS
                            | HINT_HOST_DISABLE_CALL_EFFECTS;
                    final boolean disableEffects = (hints & disableEffectsMask) != 0;
                    if (disableEffects) {
                        addDisabledHints(info, hints);
                    } else {
                        removeDisabledHints(info, hints);
                    }
                    updateListenerHintsLocked();
                    updateEffectsSuppressorLocked();
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public int getHintsFromListener(INotificationListener token) {
            synchronized (mNotificationLock) {
                return mListenerHints;
            }
        }

        @Override
        public int getHintsFromListenerNoToken() {
            synchronized (mNotificationLock) {
                return mListenerHints;
            }
        }

        @Override
        public void requestInterruptionFilterFromListener(INotificationListener token,
                int interruptionFilter) throws RemoteException {
            final int callingUid = Binder.getCallingUid();
            ManagedServiceInfo info;
            synchronized (mNotificationLock) {
                info = mListeners.checkServiceTokenLocked(token);
            }

            final int zenMode = zenModeFromInterruptionFilter(interruptionFilter, -1);
            if (zenMode == -1) return;

            UserHandle zenUser = getCallingZenUser();
            if (!canManageGlobalZenPolicy(info.component.getPackageName(), callingUid)) {
                mZenModeHelper.applyGlobalZenModeAsImplicitZenRule(
                        zenUser, info.component.getPackageName(), callingUid, zenMode);
            } else {
                int origin = computeZenOrigin(/* fromUser= */ false);
                Binder.withCleanCallingIdentity(() -> {
                    mZenModeHelper.setManualZenMode(zenUser, zenMode, /* conditionId= */ null,
                            origin, "listener:" + info.component.flattenToShortString(),
                            /* caller= */ info.component.getPackageName(),
                            callingUid);
                });
            }
        }

        @Override
        public int getInterruptionFilterFromListener(INotificationListener token)
                throws RemoteException {
            synchronized (mNotificationLock) {
                return mInterruptionFilter;
            }
        }

        @Override
        public void setOnNotificationPostedTrimFromListener(INotificationListener token, int trim)
                throws RemoteException {
            synchronized (mNotificationLock) {
                final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                if (info == null) return;
                mListeners.setOnNotificationPostedTrimLocked(info, trim);
            }
        }

        @Override
        public int getZenMode() {
            return mZenModeHelper.getZenMode();
        }

        @Override
        public ZenModeConfig getZenModeConfig() {
            enforceSystemOrSystemUI("INotificationManager.getZenModeConfig");
            return mZenModeHelper.getConfig();
        }

        @Override
        public void setZenMode(int mode, Uri conditionId, String reason, boolean fromUser) {
            enforceSystemOrSystemUI("INotificationManager.setZenMode");
            enforceUserOriginOnlyFromSystem(fromUser, "setZenMode");
            UserHandle zenUser = getCallingZenUser();

            @ZenModeConfig.ConfigOrigin int origin = computeZenOrigin(fromUser);
            final int callingUid = Binder.getCallingUid();
            final long identity = Binder.clearCallingIdentity();
            try {
                mZenModeHelper.setManualZenMode(zenUser, mode, conditionId, origin, reason,
                        /* caller= */ null, callingUid);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public ParceledListSlice getAutomaticZenRules() {
            int callingUid = Binder.getCallingUid();
            enforcePolicyAccess(callingUid, "getAutomaticZenRules");
            List<AutomaticZenRule.AzrWithId> ruleList = new ArrayList<>();
            for (Map.Entry<String, AutomaticZenRule> rule : mZenModeHelper.getAutomaticZenRules(
                    getCallingZenUser(), callingUid).entrySet()) {
                ruleList.add(new AutomaticZenRule.AzrWithId(rule.getKey(), rule.getValue()));
            }
            return new ParceledListSlice<>(ruleList);
        }

        @Override
        public AutomaticZenRule getAutomaticZenRule(String id) throws RemoteException {
            Objects.requireNonNull(id, "Id is null");
            int callingUid = Binder.getCallingUid();
            enforcePolicyAccess(callingUid, "getAutomaticZenRule");
            return mZenModeHelper.getAutomaticZenRule(getCallingZenUser(), id, callingUid);
        }

        @Override
        public String addAutomaticZenRule(AutomaticZenRule automaticZenRule, String pkg,
                boolean fromUser) {
            automaticZenRule = validateAutomaticZenRule(/* updateId= */ null, automaticZenRule);
            checkCallerIsSameApp(pkg);
            if (automaticZenRule.getZenPolicy() != null
                    && automaticZenRule.getInterruptionFilter() != INTERRUPTION_FILTER_PRIORITY) {
                throw new IllegalArgumentException("ZenPolicy is only applicable to "
                        + "INTERRUPTION_FILTER_PRIORITY filters");
            }
            enforcePolicyAccess(Binder.getCallingUid(), "addAutomaticZenRule");
            enforceUserOriginOnlyFromSystem(fromUser, "addAutomaticZenRule");
            UserHandle zenUser = getCallingZenUser();

            // Allow the system (and crucially, Settings) to choose an arbitrary package as owner;
            // otherwise forcibly use the calling package.
            String rulePkg = pkg;
            if (isCallingAppIdSystem()) {
                if (automaticZenRule.getOwner() != null) {
                    rulePkg = automaticZenRule.getOwner().getPackageName();
                }
            }

            return mZenModeHelper.addAutomaticZenRule(zenUser, rulePkg, automaticZenRule,
                    computeZenOrigin(fromUser), "addAutomaticZenRule", Binder.getCallingUid());
        }

        @Override
        public void setManualZenRuleDeviceEffects(ZenDeviceEffects effects) throws RemoteException {
            checkCallerIsSystem();
            UserHandle zenUser = getCallingZenUser();

            mZenModeHelper.setManualZenRuleDeviceEffects(zenUser, effects,
                    computeZenOrigin(true), "Update manual mode non-policy settings",
                    Binder.getCallingUid());
        }

        @Override
        public boolean updateAutomaticZenRule(String id, AutomaticZenRule automaticZenRule,
                boolean fromUser) throws RemoteException {
            automaticZenRule = validateAutomaticZenRule(id, automaticZenRule);
            enforcePolicyAccess(Binder.getCallingUid(), "updateAutomaticZenRule");
            enforceUserOriginOnlyFromSystem(fromUser, "updateAutomaticZenRule");
            UserHandle zenUser = getCallingZenUser();

            return mZenModeHelper.updateAutomaticZenRule(zenUser, id, automaticZenRule,
                    computeZenOrigin(fromUser), "updateAutomaticZenRule", Binder.getCallingUid());
        }

        /**
         * Validate and potentially "fix" a rule supplied to {@link #addAutomaticZenRule} or
         * {@link #updateAutomaticZenRule}.
         */
        @NonNull
        private AutomaticZenRule validateAutomaticZenRule(@Nullable String updateId,
                AutomaticZenRule rule) {
            Objects.requireNonNull(rule, "automaticZenRule is null");
            Objects.requireNonNull(rule.getName(), "Name is null");
            Objects.requireNonNull(rule.getConditionId(), "ConditionId is null");
            rule.validate();

            // Implicit rules have no ConditionProvider or Activity. We allow the user to customize
            // them (via Settings), but not the owner app. Should the app want to start using it as
            // a "normal" rule, it must provide a CP/ConfigActivity too.
            boolean isImplicitRuleUpdateFromSystem = updateId != null
                    && ZenModeConfig.isImplicitRuleId(updateId)
                    && isCallerSystemOrSystemUi();
            if (!isImplicitRuleUpdateFromSystem
                    && rule.getOwner() == null
                    && rule.getConfigurationActivity() == null) {
                throw new IllegalArgumentException(
                        "Rule must have a ConditionProviderService and/or configuration "
                                + "activity");
            }

            // If supplied, both CPS and ConfigurationActivity must be accessible to the calling
            // package. Clear them out if invalid -- but at least one must remain.
            if (Flags.strictZenRuleComponentValidation() && !isCallerSystemOrSystemUi()) {
                ComponentName ruleOwner = rule.getOwner();
                if (ruleOwner != null) {
                    PackageItemInfo ownerInfo = mZenModeHelper.getServiceInfo(ruleOwner);
                    if (ownerInfo == null) {
                        Slog.e(TAG, "AZR.owner " + ruleOwner
                                + " is not valid. This might throw in a future release.");
                        rule = new AutomaticZenRule.Builder(rule).setOwner(null).build();
                    }
                }
                ComponentName ruleActivity = rule.getConfigurationActivity();
                if (ruleActivity != null) {
                    PackageItemInfo activityInfo = mZenModeHelper.getActivityInfo(ruleActivity);
                    if (activityInfo == null) {
                        Slog.e(TAG, "AZR.configurationActivity " + ruleActivity
                                + " is not valid. This might throw in a future release.");
                        rule = new AutomaticZenRule.Builder(rule)
                                .setConfigurationActivity(null)
                                .build();
                    }
                }
                if (rule.getOwner() == null && rule.getConfigurationActivity() == null) {
                    throw new IllegalArgumentException(
                            "Rule must have a valid (enabled) ConditionProviderService or "
                                    + "configurationActivity");
                }
            }

            if (isCallerSystemOrSystemUi()) {
                return rule; // System callers can use any type.
            }
            int uid = Binder.getCallingUid();
            int userId = UserHandle.getUserId(uid);
            if (rule.getType() == AutomaticZenRule.TYPE_MANAGED) {
                boolean isDeviceOwner = Binder.withCleanCallingIdentity(
                        () -> mDpm.isActiveDeviceOwner(uid));
                if (!isDeviceOwner) {
                    throw new IllegalArgumentException(
                            "Only Device Owners can use AutomaticZenRules with TYPE_MANAGED");
                }
            } else if (rule.getType() == AutomaticZenRule.TYPE_BEDTIME) {
                String wellbeingPackage = getContext().getResources().getString(
                        com.android.internal.R.string.config_systemWellbeing);
                boolean isCallerWellbeing = !TextUtils.isEmpty(wellbeingPackage)
                        && isCallerSameApp(wellbeingPackage, uid, userId);
                if (!isCallerWellbeing) {
                    throw new IllegalArgumentException(
                            "Only the 'Wellbeing' package can use AutomaticZenRules with "
                                    + "TYPE_BEDTIME");
                }
            }

            return rule;
        }

        @Override
        public boolean removeAutomaticZenRule(String id, boolean fromUser) throws RemoteException {
            Objects.requireNonNull(id, "Id is null");
            // Verify that they can modify zen rules.
            enforcePolicyAccess(Binder.getCallingUid(), "removeAutomaticZenRule");
            enforceUserOriginOnlyFromSystem(fromUser, "removeAutomaticZenRule");
            UserHandle zenUser = getCallingZenUser();

            return mZenModeHelper.removeAutomaticZenRule(zenUser, id, computeZenOrigin(fromUser),
                    "removeAutomaticZenRule", Binder.getCallingUid());
        }

        @Override
        public boolean removeAutomaticZenRules(String packageName, boolean fromUser)
                throws RemoteException {
            Objects.requireNonNull(packageName, "Package name is null");
            enforceSystemOrSystemUI("removeAutomaticZenRules");
            enforceUserOriginOnlyFromSystem(fromUser, "removeAutomaticZenRules");
            UserHandle zenUser = getCallingZenUser();

            return mZenModeHelper.removeAutomaticZenRules(zenUser, packageName,
                    computeZenOrigin(fromUser), packageName + "|removeAutomaticZenRules",
                    Binder.getCallingUid());
        }

        @Override
        public int getRuleInstanceCount(ComponentName owner) throws RemoteException {
            Objects.requireNonNull(owner, "Owner is null");
            enforceSystemOrSystemUI("getRuleInstanceCount");

            return mZenModeHelper.getCurrentInstanceCount(getCallingZenUser(), owner);
        }

        @Override
        @Condition.State
        public int getAutomaticZenRuleState(@NonNull String id) {
            Objects.requireNonNull(id, "id is null");
            int callingUid = Binder.getCallingUid();
            enforcePolicyAccess(callingUid, "getAutomaticZenRuleState");
            return mZenModeHelper.getAutomaticZenRuleState(getCallingZenUser(), id, callingUid);
        }

        @Override
        public void setAutomaticZenRuleState(String id, Condition condition) {
            Objects.requireNonNull(id, "id is null");
            Objects.requireNonNull(condition, "Condition is null");
            condition.validate();

            enforcePolicyAccess(Binder.getCallingUid(), "setAutomaticZenRuleState");
            boolean fromUser = (condition.source == Condition.SOURCE_USER_ACTION);
            UserHandle zenUser = getCallingZenUser();

            mZenModeHelper.setAutomaticZenRuleState(zenUser, id, condition,
                    computeZenOrigin(fromUser), Binder.getCallingUid());
        }

        /**
         * Returns the {@link UserHandle} corresponding to the caller that is performing a
         * zen-related operation (such as {@link #setInterruptionFilter},
         * {@link #addAutomaticZenRule}, {@link #setAutomaticZenRuleState}, etc). The user is
         * {@link UserHandle#USER_CURRENT} if the caller is the system or SystemUI (assuming
         * that all interactions in SystemUI are for the "current" user); otherwise it's the user
         * associated to the binder call.
         */
        private UserHandle getCallingZenUser() {
            if (isCallerSystemOrSystemUiOrShell()) {
                return UserHandle.CURRENT;
            } else {
                return Binder.getCallingUserHandle();
            }
        }

        @ZenModeConfig.ConfigOrigin
        private int computeZenOrigin(boolean fromUser) {
            if (fromUser) {
                if (isCallerSystemOrSystemUi()) {
                    return ZenModeConfig.ORIGIN_USER_IN_SYSTEMUI;
                } else {
                    return ZenModeConfig.ORIGIN_USER_IN_APP;
                }
            } else if (isCallerSystemOrSystemUi()) {
                return ZenModeConfig.ORIGIN_SYSTEM;
            } else {
                return ZenModeConfig.ORIGIN_APP;
            }
        }

        private void enforceUserOriginOnlyFromSystem(boolean fromUser, String method) {
            if (fromUser && !isCallerSystemOrSystemUiOrShell()) {
                throw new SecurityException(TextUtils.formatSimple(
                        "Calling %s with fromUser == true is only allowed for system", method));
            }
        }

        @Override
        public void setInterruptionFilter(String pkg, int filter, boolean fromUser) {
            enforcePolicyAccess(pkg, "setInterruptionFilter");
            final int zen = zenModeFromInterruptionFilter(filter, -1);
            if (zen == -1) throw new IllegalArgumentException("Invalid filter: " + filter);
            final int callingUid = Binder.getCallingUid();
            enforceUserOriginOnlyFromSystem(fromUser, "setInterruptionFilter");
            UserHandle zenUser = getCallingZenUser();

            if (!canManageGlobalZenPolicy(pkg, callingUid)) {
                mZenModeHelper.applyGlobalZenModeAsImplicitZenRule(zenUser, pkg, callingUid, zen);
                return;
            }

            @ZenModeConfig.ConfigOrigin int origin = computeZenOrigin(fromUser);
            final long identity = Binder.clearCallingIdentity();
            try {
                mZenModeHelper.setManualZenMode(zenUser, zen, null, origin,
                        /* reason= */ "setInterruptionFilter", /* caller= */ pkg,
                        callingUid);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void notifyConditions(final String pkg, IConditionProvider provider,
                final Condition[] conditions) {
            final ManagedServiceInfo info = mConditionProviders.checkServiceToken(provider);
            checkCallerIsSystemOrSameApp(pkg);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mConditionProviders.notifyConditions(pkg, info, conditions);
                }
            });
        }

        @Override
        public void requestUnbindProvider(IConditionProvider provider) {
            int uid = Binder.getCallingUid();
            final long identity = Binder.clearCallingIdentity();
            try {
                // allow bound services to disable themselves
                final ManagedServiceInfo info = mConditionProviders.checkServiceToken(provider);
                info.getOwner().setComponentState(info.component, UserHandle.getUserId(uid), false);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void requestBindProvider(ComponentName component) {
            checkCallerIsSystemOrSameApp(component.getPackageName());
            int uid = Binder.getCallingUid();
            final long identity = Binder.clearCallingIdentity();
            try {
                mConditionProviders.setComponentState(component, UserHandle.getUserId(uid), true);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        private void enforceSystemOrSystemUI(String message) {
            if (isCallerSystemOrPhone()) return;
            getContext().enforceCallingPermission(STATUS_BAR_SERVICE,
                    message);
        }

        private void enforceSystemOrSystemUIOrSamePackage(String pkg, String message) {
            try {
                checkCallerIsSystemOrSameApp(pkg);
            } catch (SecurityException e) {
                getContext().enforceCallingPermission(
                        STATUS_BAR_SERVICE,
                        message);
            }
        }

        private void enforcePolicyAccess(int uid, String method) {
            if (PERMISSION_GRANTED == getContext().checkCallingOrSelfPermission(
                    android.Manifest.permission.MANAGE_NOTIFICATIONS)) {
                return;
            }
            boolean accessAllowed = false;
            String[] packages = mPackageManagerClient.getPackagesForUid(uid);
            final int packageCount = packages.length;
            for (int i = 0; i < packageCount; i++) {
                if (mConditionProviders.isPackageOrComponentAllowed(
                        packages[i], UserHandle.getUserId(uid))) {
                    accessAllowed = true;
                }
            }
            if (!accessAllowed) {
                Slog.w(TAG, "Notification policy access denied calling " + method);
                throw new SecurityException("Notification policy access denied");
            }
        }

        private boolean canManageGlobalZenPolicy(String callingPkg, int callingUid) {
            boolean isCompatChangeEnabled = Binder.withCleanCallingIdentity(
                    () -> CompatChanges.isChangeEnabled(MANAGE_GLOBAL_ZEN_VIA_IMPLICIT_RULES,
                            callingUid));
            return !isCompatChangeEnabled
                    || isCallerSystemOrSystemUi()
                    || hasCompanionDevice(callingPkg, UserHandle.getUserId(callingUid),
                            Set.of(AssociationRequest.DEVICE_PROFILE_WATCH,
                                    AssociationRequest.DEVICE_PROFILE_AUTOMOTIVE_PROJECTION,
                                    AssociationRequest.DEVICE_PROFILE_GLASSES,
                                    AssociationRequest.DEVICE_PROFILE_MEDICAL));
        }

        private void enforcePolicyAccess(String pkg, String method) {
            if (PERMISSION_GRANTED == getContext().checkCallingOrSelfPermission(
                    android.Manifest.permission.MANAGE_NOTIFICATIONS)) {
                return;
            }
            checkCallerIsSameApp(pkg);
            if (!checkPolicyAccess(pkg)) {
                Slog.w(TAG, "Notification policy access denied calling " + method);
                throw new SecurityException("Notification policy access denied");
            }
        }

        private boolean checkPolicyAccess(String pkg) {
            final int uid;
            final int userId = UserHandle.getCallingUserId();
            try {
                uid = getContext().getPackageManager().getPackageUidAsUser(pkg, userId);
                if (PERMISSION_GRANTED == checkComponentPermission(
                        android.Manifest.permission.MANAGE_NOTIFICATIONS, uid,
                        -1, true)) {
                    return true;
                }
            } catch (NameNotFoundException e) {
                return false;
            }

            // TODO(b/169395065) Figure out if this flow makes sense in Device Owner mode.
            return mConditionProviders.isPackageOrComponentAllowed(pkg, userId)
                    || (mDpm != null
                        && (mDpm.isActiveProfileOwner(uid) || mDpm.isActiveDeviceOwner(uid)));
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpAndUsageStatsPermission(getContext(), TAG, pw)) return;
            final DumpFilter filter = DumpFilter.parseFromArguments(args);
            final long token = Binder.clearCallingIdentity();
            try {
                final ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> pkgPermissions =
                        getAllUsersNotificationPermissions();
                if (filter.stats) {
                    dumpJson(pw, filter, pkgPermissions);
                } else if (filter.rvStats) {
                    dumpRemoteViewStats(pw, filter);
                } else if (filter.proto) {
                    dumpProto(fd, filter, pkgPermissions);
                } else if (filter.criticalPriority) {
                    dumpNotificationRecords(pw, filter);
                } else {
                    dumpImpl(pw, filter, pkgPermissions);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public ComponentName getEffectsSuppressor() {
            ComponentName suppressor = !mEffectsSuppressors.isEmpty()
                    ? mEffectsSuppressors.get(0)
                    : null;
            if (isCallerSystemOrSystemUiOrShell() || suppressor == null
                    || isCallerSameApp(suppressor.getPackageName(),
                    Binder.getCallingUid(), UserHandle.getUserId(Binder.getCallingUid()))) {
                return suppressor;
            }

            return null;
        }

        @Override
        public boolean matchesCallFilter(Bundle extras) {
            // Because matchesCallFilter may use contact data to filter calls, the callers of this
            // method need to either have notification listener access or permission to read
            // contacts.
            boolean systemAccess = false;
            try {
                enforceSystemOrSystemUI("INotificationManager.matchesCallFilter");
                systemAccess = true;
            } catch (SecurityException e) {
            }

            boolean listenerAccess = false;
            try {
                String[] pkgNames = mPackageManager.getPackagesForUid(Binder.getCallingUid());
                for (int i = 0; i < pkgNames.length; i++) {
                    // in most cases there should only be one package here
                    listenerAccess |= mListeners.hasAllowedListener(pkgNames[i],
                            Binder.getCallingUserHandle().getIdentifier());
                }
            } catch (RemoteException e) {
            } finally {
                if (!systemAccess && !listenerAccess) {
                    getContext().enforceCallingPermission(Manifest.permission.READ_CONTACTS,
                            "matchesCallFilter requires listener permission, contacts read access,"
                            + " or system level access");
                }
            }

            return mZenModeHelper.matchesCallFilter(
                    Binder.getCallingUserHandle(),
                    extras,
                    mRankingHelper.findExtractor(ValidateNotificationPeople.class),
                    MATCHES_CALL_FILTER_CONTACTS_TIMEOUT_MS,
                    MATCHES_CALL_FILTER_TIMEOUT_AFFINITY,
                    Binder.getCallingUid());
        }

        @Override
        public void cleanUpCallersAfter(long timeThreshold) {
            enforceSystemOrSystemUI("INotificationManager.cleanUpCallersAfter");
            mZenModeHelper.cleanUpCallersAfter(timeThreshold);
        }

        @Override
        public boolean isSystemConditionProviderEnabled(String path) {
            enforceSystemOrSystemUI("INotificationManager.isSystemConditionProviderEnabled");
            return mConditionProviders.isSystemProviderEnabled(path);
        }

        // Backup/restore interface
        @Override
        public byte[] getBackupPayload(int user) {
            checkCallerIsSystem();
            if (DBG) Slog.d(TAG, "getBackupPayload u=" + user);
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            final TypedXmlSerializer out = Xml.newFastSerializer();
            try {
                out.setOutput(baos, StandardCharsets.UTF_8.name());
                out.startDocument(null, true);
                // for backwards compatibility with pre nmContextualDisplayLaunch() releases,
                // the notification policy block must be written first
                writePolicyXml(out, true /*forBackup*/, user, null);
                if (nmContextualDisplayLaunch()) {
                    writeRulesXml(out, true /*forBackup*/, user, null);
                }
                out.endDocument();
                return baos.toByteArray();
            } catch (IOException e) {
                Slog.w(TAG, "getBackupPayload: error writing payload for user " + user, e);
            }
            return null;
        }

        @Override
        public void applyRestore(byte[] payload, int user) {
            checkCallerIsSystem();
            if (DBG) Slog.d(TAG, "applyRestore u=" + user + " payload="
                    + (payload != null ? new String(payload, StandardCharsets.UTF_8) : null));
            if (payload == null) {
                Slog.w(TAG, "applyRestore: no payload to restore for user " + user);
                return;
            }
            final ByteArrayInputStream bais = new ByteArrayInputStream(payload);
            try {
                // for backwards compatibility with pre nmContextualDisplayLaunch() releases,
                // the notification policy block must be read first
                final TypedXmlPullParser parser = Xml.newFastPullParser();
                parser.setInput(bais, StandardCharsets.UTF_8.name());

                readPolicyXml(parser, true /*forRestore*/, user, null);
                handleSavePolicyFile();
                if (nmContextualDisplayLaunch()) {
                    readRulesXml(parser, true /*forRestore*/, user, null);
                    handleSaveRulesFile();
                }
                handleSavePolicyFile();
            } catch (NumberFormatException | XmlPullParserException | IOException e) {
                Slog.w(TAG, "applyRestore: error reading payload", e);
            }
        }

        @Override
        public boolean isNotificationPolicyAccessGranted(String pkg) {
            return checkPolicyAccess(pkg);
        }

        @Override
        public boolean isNotificationPolicyAccessGrantedForPackage(String pkg) {
            enforceSystemOrSystemUIOrSamePackage(pkg,
                    "request policy access status for another package");
            return checkPolicyAccess(pkg);
        }

        @Override
        public void setNotificationPolicyAccessGranted(String pkg, boolean granted) {
            setNotificationPolicyAccessGrantedForUser(
                    pkg, getCallingUserHandle().getIdentifier(), granted);
        }

        @Override
        public void setNotificationPolicyAccessGrantedForUser(
                String pkg, int userId, boolean granted) {
            if (UserHandle.getCallingUserId() != userId) {
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS,
                        "setNotificationPolicyAccessGrantedForUser for user " + userId);
            }
            if (!isCallerSystemOrSystemUiOrShell()) {
                getContext().enforceCallingPermission(
                        android.Manifest.permission.MANAGE_NOTIFICATIONS,
                        "setNotificationPolicyAccessGrantedForUser");
            }
            setNotificationPolicyAccessGrantedForUserInternal(pkg, userId, granted);
        }

        @Override
        public Policy getNotificationPolicy(String pkg) {
            final int callingUid = Binder.getCallingUid();
            UserHandle zenUser = getCallingZenUser();
            if (!canManageGlobalZenPolicy(pkg, callingUid)) {
                return mZenModeHelper.getNotificationPolicyFromImplicitZenRule(zenUser, pkg);
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                return mZenModeHelper.getNotificationPolicy(zenUser);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public Policy getConsolidatedNotificationPolicy() {
            final long identity = Binder.clearCallingIdentity();
            try {
                return mZenModeHelper.getConsolidatedNotificationPolicy();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
         * Sets the notification policy.  Apps that target API levels below
         * {@link Build.VERSION_CODES#P} cannot change user-designated values to
         * allow or disallow {@link Policy#PRIORITY_CATEGORY_ALARMS},
         * {@link Policy#PRIORITY_CATEGORY_SYSTEM} and
         * {@link Policy#PRIORITY_CATEGORY_MEDIA} from bypassing dnd
         */
        @Override
        public void setNotificationPolicy(String pkg, Policy policy, boolean fromUser) {
            enforcePolicyAccess(pkg, "setNotificationPolicy");
            enforceUserOriginOnlyFromSystem(fromUser, "setNotificationPolicy");
            int callingUid = Binder.getCallingUid();
            @ZenModeConfig.ConfigOrigin int origin = computeZenOrigin(fromUser);
            UserHandle zenUser = getCallingZenUser();

            boolean isSystemCaller = isCallerSystemOrSystemUiOrShell();
            boolean shouldApplyAsImplicitRule = !canManageGlobalZenPolicy(pkg, callingUid);

            final long identity = Binder.clearCallingIdentity();
            try {
                final ApplicationInfo applicationInfo = mPackageManager.getApplicationInfo(pkg,
                        0, UserHandle.getUserId(callingUid));
                Policy currPolicy = mZenModeHelper.getNotificationPolicy(zenUser);

                if (applicationInfo.targetSdkVersion < Build.VERSION_CODES.P) {
                    int priorityCategories = policy.priorityCategories;
                    // ignore alarm and media values from new policy
                    priorityCategories &= ~Policy.PRIORITY_CATEGORY_ALARMS;
                    priorityCategories &= ~Policy.PRIORITY_CATEGORY_MEDIA;
                    priorityCategories &= ~Policy.PRIORITY_CATEGORY_SYSTEM;
                    // use user-designated values
                    priorityCategories |= currPolicy.priorityCategories
                            & Policy.PRIORITY_CATEGORY_ALARMS;
                    priorityCategories |= currPolicy.priorityCategories
                            & Policy.PRIORITY_CATEGORY_MEDIA;
                    priorityCategories |= currPolicy.priorityCategories
                            & Policy.PRIORITY_CATEGORY_SYSTEM;

                    policy = new Policy(priorityCategories,
                            policy.priorityCallSenders, policy.priorityMessageSenders,
                            policy.suppressedVisualEffects);
                }
                if (applicationInfo.targetSdkVersion < Build.VERSION_CODES.R) {
                    int priorityCategories = correctCategory(policy.priorityCategories,
                            Policy.PRIORITY_CATEGORY_CONVERSATIONS,
                            currPolicy.priorityCategories);

                    policy = new Policy(priorityCategories,
                            policy.priorityCallSenders, policy.priorityMessageSenders,
                            policy.suppressedVisualEffects, currPolicy.priorityConversationSenders);
                }

                int newVisualEffects = calculateSuppressedVisualEffects(
                        policy, currPolicy, applicationInfo.targetSdkVersion);

                // 1. Callers should not modify STATE_CHANNELS_BYPASSING_DND, which is
                // internally calculated and only indicates whether channels that want to bypass
                // DND _exist_.
                // 2. Only system callers should modify STATE_PRIORITY_CHANNELS_BLOCKED because
                // it is @hide.
                // 3. If the policy has been modified by the targetSdkVersion checks above then
                // it has lost its state flags and that's fine (STATE_PRIORITY_CHANNELS_BLOCKED
                // didn't exist until V).
                int newState = Policy.STATE_UNSET;
                if (isSystemCaller && policy.state != Policy.STATE_UNSET) {
                    newState = Policy.policyState(
                            currPolicy.hasPriorityChannels(),
                            policy.allowPriorityChannels());
                }

                final int priorityCategories = policy.priorityCategories;
                if (splitSoundVibrationForNotificationBreakthrough()) {
                    int allowSound;
                    int allowVibration;

                    if (policy.allowSoundForPriorityCategory == ALLOWED_INTERRUPTION_TYPE_UNSET
                            || policy.allowVibrationForPriorityCategory
                            == ALLOWED_INTERRUPTION_TYPE_UNSET) {
                        // Caller did not specify granular control. So, we preserve the existing
                        // granular settings if the new setting does not change the corresponding
                        // priority bit and we discard the granular settings if the corresponding
                        // priority bit is changed.

                        // if new priority bit is 0, we disable both sound and vibration.
                        allowSound = currPolicy.allowSoundForPriorityCategory
                                & priorityCategories;
                        allowVibration = currPolicy.allowVibrationForPriorityCategory
                                & priorityCategories;

                        // if new priority bit is switched from 0 -> 1, we enable both sound
                        // and vibration.
                        int newlyAllowedCategories =
                                (priorityCategories ^ currPolicy.priorityCategories)
                                        & priorityCategories;
                        allowSound |= newlyAllowedCategories;
                        allowVibration |= newlyAllowedCategories;
                    } else {
                        // Caller specified granular control. So, the passed-in policy's allow mask
                        // will override the existing setting.
                        allowSound = policy.allowSoundForPriorityCategory;
                        allowVibration = policy.allowVibrationForPriorityCategory;
                    }

                    policy = new Policy(priorityCategories,
                            policy.priorityCallSenders, policy.priorityMessageSenders,
                            newVisualEffects, newState, policy.priorityConversationSenders,
                            allowSound, allowVibration);
                } else {
                    policy = new Policy(priorityCategories,
                            policy.priorityCallSenders, policy.priorityMessageSenders,
                            newVisualEffects, newState, policy.priorityConversationSenders);
                }

                if (shouldApplyAsImplicitRule) {
                    mZenModeHelper.applyGlobalPolicyAsImplicitZenRule(zenUser, pkg, callingUid,
                            policy);
                } else {
                    ZenLog.traceSetNotificationPolicy(pkg, applicationInfo.targetSdkVersion,
                            policy);
                    mZenModeHelper.setNotificationPolicy(zenUser, policy, origin, callingUid);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to set notification policy", e);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
         * Gets the device-default zen policy as a ZenPolicy.
         */
        @Override
        public ZenPolicy getDefaultZenPolicy() {
            enforceSystemOrSystemUI("INotificationManager.getDefaultZenPolicy");
            final long identity = Binder.clearCallingIdentity();
            try {
                return mZenModeHelper.getDefaultZenPolicy();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public List<String> getEnabledNotificationListenerPackages() {
            checkCallerIsSystem();
            return mListeners.getAllowedPackages(getCallingUserHandle().getIdentifier());
        }

        @Override
        public List<String> getEnabledZenPackages() {
            checkCallerIsSystem();
            return mConditionProviders.getAllowedPackages(getCallingUserHandle().getIdentifier());
        }

        @Override
        public List<ComponentName> getEnabledNotificationListeners(
                @CannotBeSpecialUser @UserIdInt int userId) {
            checkNotificationListenerAccess();
            return mListeners.getAllowedComponents(userId);
        }

        @Override
        public ComponentName getAllowedNotificationAssistantForUser(int userId) {
            assertCallerIsSystemOrSystemUiOrShell();
            List<ComponentName> allowedComponents = mAssistants.getAllowedComponents(userId);
            if (allowedComponents.size() > 1) {
                throw new IllegalStateException(
                        "At most one NotificationAssistant: " + allowedComponents.size());
            }
            return CollectionUtils.firstOrNull(allowedComponents);
        }

        @Override
        public ComponentName getAllowedNotificationAssistant() {
            return getAllowedNotificationAssistantForUser(getCallingUserHandle().getIdentifier());
        }

        @Override
        public ComponentName getDefaultNotificationAssistant() {
            checkCallerIsSystem();
            return mAssistants.getDefaultFromConfig();
        }

        @Override
        public void setNASMigrationDoneAndResetDefault(int userId, boolean loadFromConfig) {
            checkCallerIsSystem();
            setNASMigrationDone(userId);
            if (loadFromConfig) {
                mAssistants.resetDefaultFromConfig();
            } else {
                mAssistants.clearDefaults();
            }
        }


        @Override
        public boolean hasEnabledNotificationListener(String packageName, int userId) {
            checkCallerIsSystem();
            return mListeners.isPackageAllowed(packageName, userId);
        }

        @Override
        public boolean isNotificationListenerAccessGranted(ComponentName listener) {
            Objects.requireNonNull(listener);
            checkCallerIsSystemOrSameApp(listener.getPackageName());
            return mListeners.isPackageOrComponentAllowed(listener.flattenToString(),
                    getCallingUserHandle().getIdentifier());
        }

        @Override
        public boolean isNotificationListenerAccessGrantedForUser(ComponentName listener,
                int userId) {
            Objects.requireNonNull(listener);
            checkCallerIsSystem();
            return mListeners.isPackageOrComponentAllowed(listener.flattenToString(),
                    userId);
        }

        @Override
        public boolean isNotificationAssistantAccessGranted(ComponentName assistant) {
            Objects.requireNonNull(assistant);
            checkCallerIsSystemOrSameApp(assistant.getPackageName());
            return mAssistants.isPackageOrComponentAllowed(assistant.flattenToString(),
                    getCallingUserHandle().getIdentifier());
        }

        @Override
        public void setNotificationListenerAccessGranted(ComponentName listener,
                boolean granted, boolean userSet) throws RemoteException {
            setNotificationListenerAccessGrantedForUser(
                    listener, getCallingUserHandle().getIdentifier(), granted, userSet);
        }

        @Override
        public void setNotificationAssistantAccessGranted(ComponentName assistant,
                boolean granted) {
            setNotificationAssistantAccessGrantedForUser(
                    assistant, getCallingUserHandle().getIdentifier(), granted);
        }

        @Override
        public void setNotificationListenerAccessGrantedForUser(ComponentName listener,
                @CannotBeSpecialUser @UserIdInt int userId, boolean granted, boolean userSet) {
            Objects.requireNonNull(listener);
            if (UserHandle.getCallingUserId() != userId) {
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS,
                        "setNotificationListenerAccessGrantedForUser for user " + userId);
            }
            setNotificationListenerAccessGrantedForUserInternal(listener, userId, granted, userSet);
        }

        @Override
        public void setNotificationAssistantAccessGrantedForUser(ComponentName assistant,
                int userId, boolean granted) {
            assertCallerIsSystemOrSystemUiOrShell();
            for (UserInfo ui : mUm.getEnabledProfiles(userId)) {
                mAssistants.setUserSet(ui.id, true);
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                setNotificationAssistantAccessGrantedForUserInternal(assistant, userId, granted,
                        true);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public List<DynamicBundle> getDynamicBundles(INotificationListener token, UserHandle user) {
            if (token == null) {
                assertCallerIsSystemOrSystemUiOrShell();
            } else {
                user = Binder.getCallingUserHandle();
                final long identity = Binder.clearCallingIdentity();
                try {
                    synchronized (mNotificationLock) {
                        mAssistants.checkServiceTokenLocked(token);
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            return new ArrayList<>(mAssistants.getDynamicBundles(user.getIdentifier()));
        }

        @Override
        public void deleteDynamicBundle(INotificationListener token, int dynamicBundleId) {
            final long identity = Binder.clearCallingIdentity();
            try {
                // STOPSHIP(b/438704204): remove this check before teamfood
                if (!isCallerSystemOrSystemUi()) {
                    synchronized (mNotificationLock) {
                        mAssistants.checkServiceTokenLocked(token);
                    }
                }
                DynamicBundle existed = mAssistants.deleteDynamicBundle(
                        Binder.getCallingUserHandle().getIdentifier(), dynamicBundleId);
                if (existed != null) {
                    setAssistantClassificationTypeState(dynamicBundleId, false);

                    // TODO (b/452679429): Add event log?
                    handleSavePolicyFile();
                    // TODO (b/452679429): Handle multiuser
                    getContext().sendBroadcastAsUser(
                            new Intent(ACTION_DYNAMIC_BUNDLE_MODIFIED)
                                    .putExtra(EXTRA_DYNAMIC_BUNDLE, existed)
                                    .putExtra(EXTRA_DYNAMIC_BUNDLE_MODIFICATION_TYPE,
                                            DYNAMIC_BUNDLE_MODIFICATION_TYPE_REMOVED)
                                    .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT),
                            UserHandle.ALL, STATUS_BAR_SERVICE);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void createDynamicBundle(INotificationListener token, int dynamicBundleType,
                String bundleName) {
            Preconditions.checkStringNotEmpty(bundleName);
            Preconditions.checkArgumentInRange(dynamicBundleType,
                    DynamicBundle.DYNAMIC_RANGE_START, DynamicBundle.DYNAMIC_RANGE_END,
                    "Provided id outside of allowed range");

            // limit length for legibility in the UI
            if (bundleName.length() > 25) {
                bundleName = bundleName.substring(0, 25);
            }

            final long identity = Binder.clearCallingIdentity();
            try {
                // STOPSHIP(b/438704204): remove this check before teamfood
                if (!isCallerSystemOrSystemUi()) {
                    synchronized (mNotificationLock) {
                        mAssistants.checkServiceTokenLocked(token);
                    }
                }
                DynamicBundle created = mAssistants.createDynamicBundle(
                        Binder.getCallingUserHandle().getIdentifier(),
                        dynamicBundleType,
                        bundleName);
                if (created != null) {
                    // TODO (b/452679429): Add event log?
                    handleSavePolicyFile();
                    // TODO (b/452679429): Handle multiuser
                    getContext().sendBroadcastAsUser(
                            new Intent(ACTION_DYNAMIC_BUNDLE_MODIFIED)
                                    .putExtra(EXTRA_DYNAMIC_BUNDLE, created)
                                    .putExtra(EXTRA_DYNAMIC_BUNDLE_MODIFICATION_TYPE,
                                            DYNAMIC_BUNDLE_MODIFICATION_TYPE_ADDED)
                                    .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT),
                            UserHandle.ALL, STATUS_BAR_SERVICE);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void applyEnqueuedAdjustmentFromAssistant(INotificationListener token,
                Adjustment adjustment) {
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    mAssistants.checkServiceTokenLocked(token);
                    ArrayList<NotificationRecord> enqueuedRecords = new ArrayList<>();
                    for (int i = 0; i < mEnqueuedNotifications.size(); i++) {
                        final NotificationRecord r = mEnqueuedNotifications.get(i);
                        if (Objects.equals(adjustment.getKey(), r.getKey())
                                && adjustment.getUser() == r.getUserId()
                                && mAssistants.isSameUser(token, r.getUserId())) {
                            enqueuedRecords.add(r);
                        }
                    }

                    for (NotificationRecord r : enqueuedRecords) {
                        // Adjustment might be tweaked when applying, and is also associated
                        // with the particular NotificationRecord, so don't share them.
                        Adjustment recordAdjustment = enqueuedRecords.size() > 1
                                ? new Adjustment(adjustment)
                                : adjustment;
                        applyAdjustmentLocked(r, recordAdjustment, false);
                        if (!nmContextualDisplayLaunch()) {
                            r.applyAdjustments();
                            // importance is checked at the beginning of the
                            // PostNotificationRunnable, before the signal extractors are run, so
                            // calculate the final importance here
                            r.calculateImportance();
                        }
                    }

                    if (enqueuedRecords.isEmpty()) {
                        // Notification was posted before the NAS came with the adjustment, so
                        // adjust that one.
                        applyAdjustmentsFromAssistant(token, List.of(adjustment));
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void applyAdjustmentFromAssistant(INotificationListener token,
                Adjustment adjustment) {
            List<Adjustment> adjustments = new ArrayList<>();
            adjustments.add(adjustment);
            applyAdjustmentsFromAssistant(token, adjustments);
        }

        @Override
        public void applyAdjustmentsFromAssistant(INotificationListener token,
                List<Adjustment> adjustments) {

            boolean needsSort = false;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    mAssistants.checkServiceTokenLocked(token);
                    for (Adjustment adjustment : adjustments) {
                        NotificationRecord r = mNotificationsByKey.get(adjustment.getKey());
                        if (r != null
                                && adjustment.getUser() == r.getUserId()
                                && mAssistants.isSameUser(token, r.getUserId())) {
                            applyAdjustmentLocked(r, adjustment, true);
                            // If the assistant has blocked the notification, cancel it
                            // This will trigger a sort, so we don't have to explicitly ask for
                            // one here.
                            if (adjustment.getSignals().containsKey(Adjustment.KEY_IMPORTANCE)
                                    && adjustment.getSignals().getInt(Adjustment.KEY_IMPORTANCE)
                                    == IMPORTANCE_NONE) {
                                cancelNotificationsFromListener(token, new String[]{r.getKey()});
                            } else {
                                r.setPendingLogUpdate(true);
                                needsSort = true;
                            }
                        }
                    }
                }
                if (needsSort) {
                    mRankingHandler.requestSort();
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        @FlaggedApi(FLAG_NOTIFICATION_CONVERSATION_CHANNEL_MANAGEMENT)
        public NotificationChannel createConversationNotificationChannelForPackageFromPrivilegedListener(
                INotificationListener token, String pkg, UserHandle user,
                String parentId, String conversationId) throws RemoteException {
            Objects.requireNonNull(pkg);
            Objects.requireNonNull(user);
            Objects.requireNonNull(parentId);
            Objects.requireNonNull(conversationId);

            ManagedServiceInfo nlsInfo = verifyPrivilegedListener(token, user, true);
            if (!nlsInfo.isSystemUi()
                    && !isNotificationAssistant(nlsInfo.service)
                    && !mNotificationListenerStats.isAllowedToCreateChannel(nlsInfo)) {
                Slog.e(TAG, "NLS " + nlsInfo + " has created too many channels already! "
                        + "Rejecting " + pkg + "/" + user + "/" + parentId + "/" + conversationId);
                return null;
            }

            int uid = getUidForPackageAndUser(pkg, user);
            NotificationChannel parentChannel =
                    mPreferencesHelper.getNotificationChannel(pkg, uid, parentId, false);
            if (parentChannel == null) {
                return null;
            }
            if (parentChannel.isBundleChannel()) {
                Log.v(TAG, "Cannot create conversation for classified notif from privileged " +
                        "listener with pkg:" + pkg + " user:" + user + " parentId:" + parentId
                        + " conversationId:" + conversationId);
                return null;
            }

            NotificationChannel previous = mPreferencesHelper.getConversationNotificationChannel(
                    pkg, uid, parentId, conversationId, false, false);
            if (previous != null) {
                // If the conversation already exists, we're done. Continuing is worse since
                // it would override any conversation customizations with the parent's values.
                return previous;
            }

            NotificationChannel conversationChannel = parentChannel.copy();
            conversationChannel.setId(UUID.randomUUID().toString());
            conversationChannel.setConversationId(parentId, conversationId);
            createNotificationChannelsImpl(
                    pkg, uid, new ParceledListSlice<>(Arrays.asList(conversationChannel)));

            NotificationChannel created = mPreferencesHelper.getConversationNotificationChannel(
                    pkg, uid, parentId, conversationId, false, false);
            if (created != null) {
                mNotificationListenerStats.logCreatedChannels(nlsInfo, /* increase= */ 1);
                handleSavePolicyFile();
            }

            return created;
        }

        @Override
        @FlaggedApi(FLAG_NOTIFICATION_CONVERSATION_CHANNEL_DELETION)
        public void deleteConversationNotificationChannelFromPrivilegedListener(
                INotificationListener token, String pkg, UserHandle user, String channelId)
                throws RemoteException {
            Objects.requireNonNull(pkg);
            Objects.requireNonNull(user);
            Objects.requireNonNull(channelId);

            verifyPrivilegedListener(token, user, true);
            int uid = getUidForPackageAndUser(pkg, user);

            NotificationChannel channel = mPreferencesHelper.getNotificationChannel(pkg,
                    uid, channelId, false);
            if (channel == null || !channel.isConversation()) {
                return;
            }

            cancelAllNotificationsInt(MY_UID, MY_PID, pkg, channelId, 0, 0,
                    user.getIdentifier(), REASON_CHANNEL_REMOVED);

            deleteNotificationChannelDirectly(pkg, uid, user.getIdentifier(),
                    channelId,MY_UID,true);
        }

        private void deleteNotificationChannelDirectly(String pkg, int uid, int channelUserId,
                String channelId, int callingUid, boolean fromSystemOrSystemUi){
            // temp change for upload
            boolean previouslyExisted = mPreferencesHelper.deleteNotificationChannel(pkg,
                    uid, channelId, callingUid, fromSystemOrSystemUi);
            if (previouslyExisted) {
                // Remove from both recent notification archive (recently dismissed notifications)
                // and notification history
                mArchive.removeChannelNotifications(pkg, channelUserId, channelId);
                mHistoryManager.deleteNotificationChannel(pkg, uid, channelId);
                mListeners.notifyNotificationChannelChanged(pkg,
                        UserHandle.getUserHandleForUid(uid),
                        mPreferencesHelper.getNotificationChannel(pkg, uid, channelId, true),
                        NOTIFICATION_CHANNEL_OR_GROUP_DELETED);
                handleSavePolicyFile();
            }
        }

        @Override
        public void updateNotificationChannelFromPrivilegedListener(INotificationListener token,
                String pkg, UserHandle user, NotificationChannel channel) throws RemoteException {
            Objects.requireNonNull(channel);
            Objects.requireNonNull(pkg);
            Objects.requireNonNull(user);

            verifyPrivilegedListener(token, user, true);

            final NotificationChannel originalChannel = mPreferencesHelper.getNotificationChannel(
                    pkg, getUidForPackageAndUser(pkg, user), channel.getId(), true);
            verifyPrivilegedListenerUriPermission(Binder.getCallingUid(), channel, originalChannel);
            updateNotificationChannelInt(pkg, getUidForPackageAndUser(pkg, user), channel, true);
        }

        @Override
        public ParceledListSlice<NotificationChannel> getNotificationChannelsFromPrivilegedListener(
                INotificationListener token, String pkg, UserHandle user) throws RemoteException {
            Objects.requireNonNull(pkg);
            Objects.requireNonNull(user);
            verifyPrivilegedListener(token, user, true);

            return mPreferencesHelper.getNotificationChannels(pkg,
                    getUidForPackageAndUser(pkg, user), false /* includeDeleted */, true);
        }

        @Override
        public ParceledListSlice<NotificationChannelGroup>
                getNotificationChannelGroupsFromPrivilegedListener(
                INotificationListener token, String pkg, UserHandle user) throws RemoteException {
            Objects.requireNonNull(pkg);
            Objects.requireNonNull(user);
            verifyPrivilegedListener(token, user, true);

            List<NotificationChannelGroup> groups = new ArrayList<>();
            groups.addAll(mPreferencesHelper.getNotificationChannelGroupsWithoutChannels(
                    pkg, getUidForPackageAndUser(pkg, user)));
            return new ParceledListSlice<>(groups);
        }

        @Override
        public boolean isInCall(String pkg, int uid) {
            assertCallerIsSystemOrSystemUiOrShell();
            return isCallNotification(pkg, uid);
        }

        @Override
        public void setPrivateNotificationsAllowed(boolean allow) {
            if (PERMISSION_GRANTED
                    != getContext().checkCallingPermission(CONTROL_KEYGUARD_SECURE_NOTIFICATIONS)) {
                throw new SecurityException(
                        "Requires CONTROL_KEYGUARD_SECURE_NOTIFICATIONS permission");
            }
            if (allow != mLockScreenAllowSecureNotifications) {
                mLockScreenAllowSecureNotifications = allow;
                getContext().sendBroadcast(
                        new Intent(ACTION_KEYGUARD_PRIVATE_NOTIFICATIONS_CHANGED)
                                .putExtra(EXTRA_KM_PRIVATE_NOTIFS_ALLOWED,
                                        mLockScreenAllowSecureNotifications),
                        STATUS_BAR_SERVICE);

                handleSavePolicyFile();
            }
        }

        @Override
        public boolean getPrivateNotificationsAllowed() {
            if (PERMISSION_GRANTED
                    != getContext().checkCallingPermission(CONTROL_KEYGUARD_SECURE_NOTIFICATIONS)) {
                throw new SecurityException(
                        "Requires CONTROL_KEYGUARD_SECURE_NOTIFICATIONS permission");
            }
            return mLockScreenAllowSecureNotifications;
        }

        @Override
        public boolean isPackagePaused(String pkg) {
            Objects.requireNonNull(pkg);
            checkCallerIsSameApp(pkg);

            return isPackagePausedOrSuspended(pkg, Binder.getCallingUid());
        }

        @Override
        public boolean isPermissionFixed(String pkg, @UserIdInt int userId) {
            enforceSystemOrSystemUI("isPermissionFixed");
            return mPermissionHelper.isPermissionFixed(pkg, userId);
        }

        @NonNull
        private ManagedServiceInfo verifyPrivilegedListener(INotificationListener token,
                UserHandle user, boolean assistantAllowed) {
            ManagedServiceInfo info;
            synchronized (mNotificationLock) {
                info = mListeners.checkServiceTokenLocked(token);
            }
            if (!hasCompanionDevice(info)) {
                synchronized (mNotificationLock) {
                    if (!assistantAllowed || !mAssistants.isServiceTokenValidLocked(info.service)) {
                        throw new SecurityException(info + " does not have access");
                    }
                }
            }
            if (!info.enabledAndUserMatches(user.getIdentifier())) {
                throw new SecurityException(info + " does not have access");
            }
            return info;
        }

        private void verifyPrivilegedListenerUriPermission(int sourceUid,
                @NonNull NotificationChannel updateChannel,
                @Nullable NotificationChannel originalChannel) {
            // Check that the NLS has the required permissions to access the channel
            final Uri soundUri = updateChannel.getSound();
            final Uri originalSoundUri =
                    (originalChannel != null) ? originalChannel.getSound() : null;
            if (soundUri != null && !Objects.equals(originalSoundUri, soundUri)) {
                PermissionHelper.grantUriPermission(mUgmInternal, soundUri, sourceUid);
            }
        }

        private int getUidForPackageAndUser(String pkg, UserHandle user) throws RemoteException {
            final long identity = Binder.clearCallingIdentity();
            try {
                return mPackageManager.getPackageUid(pkg, 0, user.getIdentifier());
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                String[] args, ShellCallback callback, ResultReceiver resultReceiver)
                throws RemoteException {
            new NotificationShellCmd(NotificationManagerService.this)
                    .exec(this, in, out, err, args, callback, resultReceiver);
        }

        /**
         * Get stats committed after startNs
         *
         * @param startNs Report stats committed after this time in nanoseconds.
         * @param report  Indicatess which section to include in the stats.
         * @param doAgg   Whether to aggregate the stats or keep them separated.
         * @param out   List of protos of individual commits or one representing the
         *                aggregate.
         * @return the report time in nanoseconds, or 0 on error.
         */
        @Override
        public long pullStats(long startNs, int report, boolean doAgg,
                List<ParcelFileDescriptor> out) {
            checkCallerIsSystemOrShell();
            long startMs = TimeUnit.MILLISECONDS.convert(startNs, TimeUnit.NANOSECONDS);

            final long identity = Binder.clearCallingIdentity();
            try {
                switch (report) {
                    case REPORT_REMOTE_VIEWS:
                        Slog.e(TAG, "pullStats REPORT_REMOTE_VIEWS from: "
                                + startMs + "  with " + doAgg);
                        PulledStats stats = mUsageStats.remoteViewStats(startMs, doAgg);
                        if (stats != null) {
                            out.add(stats.toParcelFileDescriptor(report));
                            Slog.e(TAG, "exiting pullStats with: " + out.size());
                            long endNs = TimeUnit.NANOSECONDS
                                    .convert(stats.endTimeMs(), TimeUnit.MILLISECONDS);
                            return endNs;
                        }
                        Slog.e(TAG, "null stats for: " + report);
                }
            } catch (IOException e) {

                Slog.e(TAG, "exiting pullStats: on error", e);
                return 0;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            Slog.e(TAG, "exiting pullStats: bad request");
            return 0;
        }

        @Override
        public NotificationRule addNotificationRule(@UserIdInt int userId, NotificationRule rule,
                int pos) {
            assertCallerIsSystemOrSystemUiOrShell();
            Objects.requireNonNull(rule);

            if (!nmContextualDisplayLaunch()) {
                return null;
            }

            if (mNotificationRuleManager.addNotificationRule(userId, pos, rule)) {
                handleSaveRulesFile();
                mAssistants.notifyNotificationRuleAdded(userId, rule);
                return mNotificationRuleManager.getNotificationRule(userId, rule.getId());
            } else {
                return null;
            }
        }

        @Override
        public NotificationRule updateNotificationRule(@UserIdInt int userId,
                NotificationRule rule) {
            assertCallerIsSystemOrSystemUiOrShell();
            Objects.requireNonNull(rule);

            if (!nmContextualDisplayLaunch()) {
                return null;
            }

            if (mNotificationRuleManager.updateNotificationRule(userId, rule)) {
                handleSaveRulesFile();
                mAssistants.notifyNotificationRuleModified(userId, rule);
                return mNotificationRuleManager.getNotificationRule(userId, rule.getId());
            } else {
                return null;
            }
        }

        @Override
        public boolean removeNotificationRule(@UserIdInt int userId, int ruleId) {
            assertCallerIsSystemOrSystemUiOrShell();

            if (!nmContextualDisplayLaunch()) {
                return false;
            }

            if (mNotificationRuleManager.removeNotificationRule(userId, ruleId)) {
                onNotificationRuleRemoved(userId, ruleId);
                handleSaveRulesFile();
                mAssistants.notifyNotificationRuleRemoved(userId, ruleId);
                return true;
            } else {
                return false;
            }
        }

        @Override
        public ParceledListSlice<NotificationRule> getNotificationRules(
                INotificationListener token, @UserIdInt int userId) {
            if (token == null) {
                assertCallerIsSystemOrSystemUiOrShell();
            } else {
                mAssistants.checkServiceTokenLocked(token);
            }
            if (!nmContextualDisplayLaunch()) {
                return new ParceledListSlice<>(new ArrayList<>());
            }

            return new ParceledListSlice<>(mNotificationRuleManager.getNotificationRules(userId));
        }

        @Override
        public NotificationRule getNotificationRule(@UserIdInt int userId, int ruleId) {
            assertCallerIsSystemOrSystemUiOrShell();

            if (!nmContextualDisplayLaunch()) {
                return null;
            }

            return mNotificationRuleManager.getNotificationRule(userId, ruleId);
        }

        // Suppressing warning otherwise we'd need to add a new method (called
        // logHsuNotificationPostStatus_enforcePermission) just to make ErrorProne happy.
        @SuppressWarnings("MissingEnforcePermissionHelper")
        @Override
        @EnforcePermission(android.Manifest.permission.STATUS_BAR_SERVICE)
        public void logHsuNotificationPostStatus(StatusBarNotification sbn, int status) {
            checkCallerIsSystemOrSystemUi();
            mUmInternal.logNotificationPostStatus(sbn, UserHandle.USER_SYSTEM, status);
        }
    }

    private void handleNotificationPermissionChange(String pkg, @UserIdInt int userId) {
        if (!mUmInternal.isUserInitialized(userId)) {
            return; // App-op "updates" are sent when starting a new user the first time.
        }
        int uid = mPackageManagerInternal.getPackageUid(pkg, 0, userId);
        if (uid == INVALID_UID) {
            Log.e(TAG, String.format("No uid found for %s, %s!", pkg, userId));
            return;
        }
        boolean hasPermission = mPermissionHelper.hasPermission(uid);
        if (!hasPermission) {
            cancelAllNotificationsInt(MY_UID, MY_PID, pkg, /* channelId= */ null,
                    /* mustHaveFlags= */ 0, /* mustNotHaveFlags= */ 0, userId,
                    REASON_PACKAGE_BANNED);
        }
    }

    protected void checkNotificationListenerAccess() {
        if (!isCallerSystemOrPhone()) {
            // Safe to check calling permission as caller is already not system or phone
            getContext().enforceCallingPermission(
                    permission.MANAGE_NOTIFICATION_LISTENERS,
                    "Caller must hold " + permission.MANAGE_NOTIFICATION_LISTENERS);
        }
    }

    @VisibleForTesting
    protected void setNotificationAssistantAccessGrantedForUserInternal(
            ComponentName assistant, int baseUserId, boolean granted, boolean userSet) {
        List<UserInfo> users = mUm.getEnabledProfiles(baseUserId);
        if (users != null) {
            for (UserInfo user : users) {
                int userId = user.id;
                ComponentName allowedAssistant = CollectionUtils.firstOrNull(
                        mAssistants.getAllowedComponents(userId));
                if (assistant == null) {
                    if (allowedAssistant != null) {
                        setNotificationAssistantAccessGrantedForUserInternal(
                                allowedAssistant, userId, false, userSet);
                    }
                    continue;
                }
                if (granted && assistant.equals(allowedAssistant)) {
                    continue;
                }
                if (!granted || mAllowedManagedServicePackages.test(assistant.getPackageName(),
                        userId, mAssistants.getRequiredPermission())) {
                    mConditionProviders.setPackageOrComponentEnabled(assistant.flattenToString(),
                            userId, false, granted);
                    mAssistants.setPackageOrComponentEnabled(assistant.flattenToString(),
                            userId, true, granted, userSet);
                    mAssistants.setNasUnsupportedDefaults(userId);

                    getContext().sendBroadcastAsUser(
                            new Intent(ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED)
                                    .setPackage(assistant.getPackageName())
                                    .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY),
                            UserHandle.of(userId), null);

                    handleSavePolicyFile();
                }
                if (!granted) {
                    if (notificationRegroupOnClassification()) {
                        applyNotificationUpdateForUserProfiles(userId,
                                NotificationManagerService.this::unclassifyNotificationLocked);
                    }
                    applyNotificationUpdateForUserProfiles(userId,
                            NotificationManagerService.this::unsummarizeNotificationLocked);
                }
            }
        }
    }

    @GuardedBy("mNotificationLock")
    private void applyAdjustmentLocked(NotificationRecord r, Adjustment adjustment,
            boolean isPosted) {
        if (r == null) {
            return;
        }
        if (adjustment.getSignals() != null) {
            final @UserIdInt int userId =
                    adjustment.getUser() == UserHandle.USER_ALL ? UserHandle.USER_SYSTEM
                            : adjustment.getUser();
            final Bundle adjustments = adjustment.getSignals();
            Bundle.setDefusable(adjustments, true);
            // Save classification even if the adjustment is disabled, in case user enables it later
            if (adjustments.containsKey(KEY_TYPE)) {
                r.setBundleType(adjustments.getInt(KEY_TYPE));
            }
            List<String> toRemove = new ArrayList<>();
            for (String potentialKey : adjustments.keySet()) {
                if (!mAssistants.isAdjustmentAllowed(userId, potentialKey)) {
                    toRemove.add(potentialKey);
                }
                if (potentialKey.equals(KEY_TYPE)) {
                    mAssistants.setAdjustmentKeySupportedState(userId, potentialKey, true);
                    if (!mNotificationRuleManager.isClassificationTypeAllowed(userId,
                            adjustments.getInt(KEY_TYPE))) {
                        toRemove.add(potentialKey);
                    } else if (!mNotificationRuleManager.isClassificationAllowedForPackage(
                            userId, r.getSbn().getPackageName())) {
                        toRemove.add(potentialKey);
                    }
                }
                if (potentialKey.equals(KEY_SUMMARIZATION)) {
                    mAssistants.setAdjustmentKeySupportedState(userId, potentialKey, true);
                    if (!mAssistants.isAdjustmentAllowedForPackage(userId, KEY_SUMMARIZATION,
                            r.getSbn().getPackageName())) {
                        toRemove.add(potentialKey);
                    }
                }
                if (nmContextualDisplayLaunch()) {
                    if (potentialKey.equals(KEY_NOTIFICATION_RULES)) {
                        mAssistants.setAdjustmentKeySupportedState(userId, potentialKey, true);
                        mAssistants.setAdjustmentKeySupportedState(userId, KEY_TYPE, true);

                        // this adjustment is not directly applied like other adjustments
                        // so log it specially to help with debugging
                        EventLogTags.writeNotificationAdjusted(adjustment.getKey(),
                                potentialKey, adjustments.getIntegerArrayList(
                                Adjustment.KEY_NOTIFICATION_RULES).toString());
                    }
                }
            }
            for (String removeKey : toRemove) {
                adjustments.remove(removeKey);
            }
            if (adjustments.containsKey(KEY_TYPE)) {
                final NotificationChannel newChannel = getClassificationChannelLocked(r,
                        adjustments);
                if (newChannel == null || newChannel.getId().equals(r.getChannel().getId())) {
                    adjustments.remove(KEY_TYPE);
                } else if (hasFlag(r.getNotification().flags, FLAG_PROMOTED_ONGOING)) {
                    // Don't bundle any promoted ongoing notifications
                    adjustments.remove(KEY_TYPE);
                } else {
                    // Save the app-provided type for logging.
                    int classification = adjustments.getInt(KEY_TYPE);
                    // swap app provided type with the real thing
                    adjustments.putParcelable(KEY_TYPE, newChannel);
                    logClassificationChannelAdjustmentReceived(r, isPosted, classification);
                }
            }
            r.addAdjustment(adjustment);
            if (adjustment.getSignals().containsKey(Adjustment.KEY_SENSITIVE_CONTENT)) {
                logSensitiveAdjustmentReceived(isPosted,
                        adjustment.getSignals().getBoolean(Adjustment.KEY_SENSITIVE_CONTENT),
                        r.getLifespanMs(System.currentTimeMillis()));
            }
        }
    }

    @GuardedBy("mNotificationLock")
    @Nullable
    private NotificationChannel getClassificationChannelLocked(NotificationRecord r,
            Bundle adjustments) {
        int type = adjustments.getInt(KEY_TYPE);
        if ((type >= TYPE_PROMOTION && type <= TYPE_CONTENT_RECOMMENDATION)
                || android.app.Flags.nmContextualDisplay()) {
            NotificationChannel channel = mPreferencesHelper.getReservedChannel(
                    r.getSbn().getPackageName(), r.getUid(), type);
            if (channel == null) {
                String label = getClassificationChannelName(r.getUserId(), type);
                if (!TextUtils.isEmpty(label)) {
                    channel = mPreferencesHelper.createReservedChannel(
                            r.getSbn().getPackageName(), r.getUid(), type, label);
                    handleSavePolicyFile();
                }
            }
            return channel;
        }
        return null;
    }

    private String getClassificationChannelName(@UserIdInt int userId, int type) {
        return switch (type) {
            case TYPE_PROMOTION ->
                    getContext().getString(R.string.promotional_notification_channel_label);
            case TYPE_CONTENT_RECOMMENDATION ->
                    getContext().getString(R.string.recs_notification_channel_label);
            case TYPE_NEWS ->
                    getContext().getString(R.string.news_notification_channel_label);
            case TYPE_SOCIAL_MEDIA ->
                    getContext().getString(R.string.social_notification_channel_label);
            default -> mAssistants.getDynamicBundleName(userId, type);
        };
    }

    @SuppressWarnings("GuardedBy")
    @GuardedBy("mNotificationLock")
    void addAutogroupKeyLocked(String key, String groupName, boolean requestSort) {
        NotificationRecord r = mNotificationsByKey.get(key);
        if (r == null) {
            return;
        }
        if (r.getSbn().getOverrideGroupKey() == null) {
            if (r.getSbn().isAppGroup()) {
                // Override group key early for forced grouped notifications
                r.setOverrideGroupKey(groupName);
                r.getNotification().flags |= Notification.FLAG_SILENT;
            }

            addAutoGroupAdjustment(r, groupName);
            EventLogTags.writeNotificationAutogrouped(key);

            if (requestSort) {
                mRankingHandler.requestSort();
            }

            if (r.getSbn().isAppGroup()) {
                mListeners.notifyPostedLocked(r, r);

                mNotificationRecordLogger.log(
                        NotificationRecordLogger.NotificationEvent.NOTIFICATION_FORCE_GROUP, r);
            }
        }
    }

    @GuardedBy("mNotificationLock")
    void removeAutogroupKeyLocked(String key) {
        NotificationRecord r = mNotificationsByKey.get(key);
        if (r == null) {
            Slog.w(TAG, "Failed to remove autogroup " + key);
            return;
        }
        if (r.getSbn().getOverrideGroupKey() != null) {
            addAutoGroupAdjustment(r, null);
            EventLogTags.writeNotificationUnautogrouped(key);
            mRankingHandler.requestSort();
        }
    }

    private void addAutoGroupAdjustment(NotificationRecord r, String overrideGroupKey) {
        Bundle signals = new Bundle();
        signals.putString(Adjustment.KEY_GROUP_KEY, overrideGroupKey);
        Adjustment adjustment = new Adjustment(r.getSbn().getPackageName(), r.getKey(), signals, "",
                r.getSbn().getUserId());
        r.addAdjustment(adjustment);
    }

    // Clears the 'fake' auto-group summary.
    @VisibleForTesting
    @GuardedBy("mNotificationLock")
    void clearAutogroupSummaryLocked(int userId, String pkg, String groupKey) {
        final String autbundledGroupKey = groupKey;

        ArrayMap<String, String> summaries = mAutobundledSummaries.get(userId);
        final NotificationRecord autogroupSummary;
        if (summaries != null && summaries.containsKey(autbundledGroupKey)) {
            autogroupSummary = findNotificationByKeyLocked(summaries.remove(autbundledGroupKey));
        } else {
            autogroupSummary = mSummaryByGroupKey.get(autbundledGroupKey);
        }
        if (autogroupSummary != null) {
            final StatusBarNotification sbn = autogroupSummary.getSbn();
            cancelNotification(MY_UID, MY_PID, pkg, sbn.getTag(), sbn.getId(), 0, null, false,
                    userId, REASON_UNAUTOBUNDLED, null);
        }
    }

    @GuardedBy("mNotificationLock")
    void removeAppSummaryLocked(String key) {
        NotificationRecord r = mNotificationsByKey.get(key);
        if (r == null) {
            return;
        }
        if (convertSummaryToNotificationLocked(key)) {
            r.isCanceled = true;
            cancelNotification(Binder.getCallingUid(),
                    Binder.getCallingPid(), r.getSbn().getPackageName(),
                    r.getSbn().getTag(), r.getSbn().getId(), 0, null,
                    false, r.getUserId(),
                    REASON_GROUP_OPTIMIZATION, null);
        }
    }

    @GuardedBy("mNotificationLock")
    @Nullable
    NotificationRecord removeAppProvidedSummaryOnClassificationLocked(String triggeringKey,
            @Nullable String oldGroupKey) {
        NotificationRecord canceledSummary = null;
        NotificationRecord r = mNotificationsByKey.get(triggeringKey);
        if (r == null || oldGroupKey == null) {
            return null;
        }

        if (r.getSbn().isAppGroup() && r.getNotification().isGroupChild()) {
            NotificationRecord groupSummary = mSummaryByGroupKey.get(oldGroupKey);
            // We only care about app-provided valid groups
            if (groupSummary != null && !GroupHelper.isAggregatedGroup(groupSummary)) {
                List<NotificationRecord> notificationsInGroup =
                        findGroupNotificationsLocked(r.getSbn().getPackageName(),
                            oldGroupKey, r.getUserId());
                // Remove the app-provided summary if only the summary is left in the
                // original group, or summary + triggering notification that will be
                // regrouped
                boolean isOnlySummaryLeft =
                        (notificationsInGroup.size() <= 1)
                            || (notificationsInGroup.size() == 2
                            && notificationsInGroup.contains(r)
                            && notificationsInGroup.contains(groupSummary));
                if (isOnlySummaryLeft) {
                    if (DBG) {
                        Slog.i(TAG, "Removing app summary (all children bundled): "
                                + groupSummary);
                    }
                    if (convertSummaryToNotificationLocked(groupSummary.getKey())) {
                        groupSummary.isCanceled = true;
                        canceledSummary = groupSummary;
                        mSummaryByGroupKey.remove(oldGroupKey);
                        cancelNotification(Binder.getCallingUid(), Binder.getCallingPid(),
                                groupSummary.getSbn().getPackageName(),
                                groupSummary.getSbn().getTag(),
                                groupSummary.getSbn().getId(), 0, null, false,
                                groupSummary.getUserId(), REASON_GROUP_OPTIMIZATION, null);
                    }
                }
            }
        }

        return canceledSummary;
    }

    @GuardedBy("mNotificationLock")
    private boolean hasAutoGroupSummaryLocked(NotificationRecord record) {
        final String autbundledGroupKey = GroupHelper.getFullAggregateGroupKey(record);


        ArrayMap<String, String> summaries = mAutobundledSummaries.get(record.getUserId());
        return summaries != null && summaries.containsKey(autbundledGroupKey);
    }

    // Creates a 'fake' summary for a package that has exceeded the solo-notification limit.
    NotificationRecord createAutoGroupSummary(int userId, String pkg, String triggeringKey,
            String groupKey, int summaryId, NotificationAttributes summaryAttr) {
        NotificationRecord summaryRecord = null;
        boolean isPermissionFixed = mPermissionHelper.isPermissionFixed(pkg, userId);
        synchronized (mNotificationLock) {
            NotificationRecord notificationRecord = mNotificationsByKey.get(triggeringKey);
            if (notificationRecord == null) {
                // The notification could have been cancelled again already. A successive
                // adjustment will post a summary if needed.
                return null;
            }
            final StatusBarNotification adjustedSbn = notificationRecord.getSbn();
            userId = adjustedSbn.getUser().getIdentifier();
            int uid =  adjustedSbn.getUid();
            ArrayMap<String, String> summaries = mAutobundledSummaries.get(userId);
            if (summaries == null) {
                summaries = new ArrayMap<>();
            }
            mAutobundledSummaries.put(userId, summaries);

            boolean hasSummary = summaries.containsKey(groupKey);
            String channelId = summaryAttr.channelId;

            if (!hasSummary) {
                // Add summary
                final ApplicationInfo appInfo =
                        adjustedSbn.getNotification().extras.getParcelable(
                                EXTRA_BUILDER_APPLICATION_INFO, ApplicationInfo.class);
                final Bundle extras = new Bundle();
                extras.putParcelable(EXTRA_BUILDER_APPLICATION_INFO, appInfo);


                final Notification summaryNotification =
                                new Notification.Builder(getContext(), channelId)
                                .setSmallIcon(summaryAttr.icon)
                                .setGroupSummary(true)
                                .setGroupAlertBehavior(summaryAttr.groupAlertBehavior)
                                .setGroup(groupKey)
                                .setFlag(summaryAttr.flags, true)
                                .setColor(summaryAttr.iconColor)
                                .setVisibility(summaryAttr.visibility)
                                .build();
                summaryNotification.extras.putAll(extras);
                Intent appIntent = getContext().getPackageManager().getLaunchIntentForPackage(pkg);
                if (appIntent != null) {
                    summaryNotification.contentIntent = mAmi.getPendingIntentActivityAsApp(
                            0, appIntent, PendingIntent.FLAG_IMMUTABLE, null,
                            pkg, appInfo.uid);
                }
                final StatusBarNotification summarySbn =
                        new StatusBarNotification(adjustedSbn.getPackageName(),
                                adjustedSbn.getOpPkg(),
                                summaryId,
                                groupKey, adjustedSbn.getUid(),
                                adjustedSbn.getInitialPid(), summaryNotification,
                                adjustedSbn.getUser(), groupKey,
                                System.currentTimeMillis());
                summaryRecord = new NotificationRecord(getContext(), summarySbn,
                        notificationRecord.getChannel());
                summaryRecord.setImportanceFixed(isPermissionFixed);
                summaryRecord.setIsAppImportanceLocked(
                        notificationRecord.getIsAppImportanceLocked());

                summaries.put(summarySbn.getGroupKey(), summarySbn.getKey());
            }
            if (summaryRecord != null && checkDisqualifyingFeatures(userId, uid,
                    summaryRecord.getSbn().getId(), summaryRecord.getSbn().getTag(), summaryRecord,
                    true, false)) {
                return summaryRecord;
            }
        }
        return null;
    }

    @GuardedBy("mNotificationLock")
    boolean convertSummaryToNotificationLocked(final String key) {
        NotificationRecord r = mNotificationsByKey.get(key);
        if (r == null) {
            return false;
        }
        // Convert summary to regular notification
        if (r.getSbn().isAppGroup() && r.getNotification().isGroupSummary()) {
            String oldGroupKey = r.getGroupKey();
            NotificationRecord groupSummary = mSummaryByGroupKey.get(oldGroupKey);
            if (groupSummary != null && groupSummary.getKey().equals(r.getKey())) {
                mSummaryByGroupKey.remove(oldGroupKey);
            }
            // Clear summary flag
            StatusBarNotification sbn = r.getSbn();
            sbn.getNotification().flags = (r.mOriginalFlags & ~FLAG_GROUP_SUMMARY);

            EventLogTags.writeNotificationSummaryConverted(key);
            mNotificationRecordLogger.log(
                NotificationRecordLogger.NotificationEvent.NOTIFICATION_FORCE_GROUP_SUMMARY, r);
            return true;
        }
        return false;
    }

    // Gets packages that have requested notification permission, and whether that has been
    // allowed/denied, for all users on the device.
    // Returns a single map containing that info keyed by (uid, package name) for all users.
    // Because this calls into mPermissionHelper, this method must never be called with a lock held.
    @VisibleForTesting
    protected ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>>
            getAllUsersNotificationPermissions() {
        ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> allPermissions = new ArrayMap<>();
        final List<UserInfo> allUsers = mUm.getUsers();
        // for each of these, get the package notification permissions that are associated
        // with this user and add it to the map
        for (UserInfo ui : allUsers) {
            ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> userPermissions =
                    mPermissionHelper.getNotificationPermissionValues(
                            ui.getUserHandle().getIdentifier());
            for (Pair<Integer, String> pair : userPermissions.keySet()) {
                allPermissions.put(pair, userPermissions.get(pair));
            }
        }
        return allPermissions;
    }

    private void dumpJson(PrintWriter pw, @NonNull DumpFilter filter,
            ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> pkgPermissions) {
        JSONObject dump = new JSONObject();
        try {
            dump.put("service", "Notification Manager");
            dump.put("bans", mPreferencesHelper.dumpBansJson(filter, pkgPermissions));
            dump.put("ranking", mPreferencesHelper.dumpJson(filter, pkgPermissions));
            dump.put("stats", mUsageStats.dumpJson(filter));
            dump.put("channels", mPreferencesHelper.dumpChannelsJson(filter));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        pw.println(dump);
    }

    private void dumpRemoteViewStats(PrintWriter pw, @NonNull DumpFilter filter) {
        PulledStats stats = mUsageStats.remoteViewStats(filter.since, true);
        if (stats == null) {
            pw.println("no remote view stats reported.");
            return;
        }
        stats.dump(REPORT_REMOTE_VIEWS, pw, filter);
    }

    private void dumpProto(FileDescriptor fd, @NonNull DumpFilter filter,
            ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> pkgPermissions) {
        final ProtoOutputStream proto = new ProtoOutputStream(fd);
        synchronized (mNotificationLock) {
            int N = mNotificationList.size();
            for (int i = 0; i < N; i++) {
                final NotificationRecord nr = mNotificationList.get(i);
                if (filter.filtered && !filter.matches(nr.getSbn())) continue;
                nr.dump(proto, NotificationServiceDumpProto.RECORDS, filter.redact,
                        NotificationRecordProto.POSTED);
            }
            N = mEnqueuedNotifications.size();
            for (int i = 0; i < N; i++) {
                final NotificationRecord nr = mEnqueuedNotifications.get(i);
                if (filter.filtered && !filter.matches(nr.getSbn())) continue;
                nr.dump(proto, NotificationServiceDumpProto.RECORDS, filter.redact,
                        NotificationRecordProto.ENQUEUED);
            }
            List<NotificationRecord> snoozed = mSnoozeHelper.getSnoozed();
            N = snoozed.size();
            for (int i = 0; i < N; i++) {
                final NotificationRecord nr = snoozed.get(i);
                if (filter.filtered && !filter.matches(nr.getSbn())) continue;
                nr.dump(proto, NotificationServiceDumpProto.RECORDS, filter.redact,
                        NotificationRecordProto.SNOOZED);
            }

            long zenLog = proto.start(NotificationServiceDumpProto.ZEN);
            mZenModeHelper.dump(proto);
            for (ComponentName suppressor : mEffectsSuppressors) {
                suppressor.dumpDebug(proto, ZenModeProto.SUPPRESSORS);
            }
            proto.end(zenLog);

            long listenersToken = proto.start(NotificationServiceDumpProto.NOTIFICATION_LISTENERS);
            mListeners.dump(proto, filter);
            proto.end(listenersToken);

            proto.write(NotificationServiceDumpProto.LISTENER_HINTS, mListenerHints);

            for (int i = 0; i < mListenersDisablingEffects.size(); ++i) {
                long effectsToken = proto.start(
                    NotificationServiceDumpProto.LISTENERS_DISABLING_EFFECTS);

                proto.write(
                    ListenersDisablingEffectsProto.HINT, mListenersDisablingEffects.keyAt(i));
                final ArraySet<ComponentName> listeners =
                    mListenersDisablingEffects.valueAt(i);
                for (int j = 0; j < listeners.size(); j++) {
                    final ComponentName componentName = listeners.valueAt(j);
                    componentName.dumpDebug(proto,
                            ListenersDisablingEffectsProto.LISTENER_COMPONENTS);
                }

                proto.end(effectsToken);
            }

            long assistantsToken = proto.start(
                NotificationServiceDumpProto.NOTIFICATION_ASSISTANTS);
            mAssistants.dump(proto, filter);
            proto.end(assistantsToken);

            long conditionsToken = proto.start(NotificationServiceDumpProto.CONDITION_PROVIDERS);
            mConditionProviders.dump(proto, filter);
            proto.end(conditionsToken);

            long rankingToken = proto.start(NotificationServiceDumpProto.RANKING_CONFIG);
            mRankingHelper.dump(proto, filter);
            mPreferencesHelper.dump(proto, filter, pkgPermissions);
            proto.end(rankingToken);
        }

        proto.flush();
    }

    private void dumpNotificationRecords(PrintWriter pw, @NonNull DumpFilter filter) {
        synchronized (mNotificationLock) {
            int N;
            N = mNotificationList.size();
            if (N > 0) {
                pw.println("  Notification List:");
                for (int i = 0; i < N; i++) {
                    final NotificationRecord nr = mNotificationList.get(i);
                    if (filter.filtered && !filter.matches(nr.getSbn())) continue;
                    nr.dump(pw, "    ", getContext(), filter.redact);
                }
                pw.println("  ");
            }
        }
    }

    void dumpImpl(PrintWriter pw, @NonNull DumpFilter filter,
            ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> pkgPermissions) {
        pw.print("Current Notification Manager state");
        if (filter.filtered) {
            pw.print(" (filtered to "); pw.print(filter); pw.print(")");
        }
        pw.println(':');
        int N;
        final boolean zenOnly = filter.filtered && filter.zen;

        if (!zenOnly) {
            synchronized (mToastQueue) {
                N = mToastQueue.size();
                if (N > 0) {
                    pw.println("  Toast Queue:");
                    for (int i=0; i<N; i++) {
                        mToastQueue.get(i).dump(pw, "    ", filter);
                    }
                    pw.println("  ");
                }
            }
        }

        synchronized (mNotificationLock) {
            if (!zenOnly) {
                // Priority filters are only set when called via bugreport. If set
                // skip sections that are part of the critical section.
                if (!filter.normalPriority) {
                    dumpNotificationRecords(pw, filter);
                }
                if (!filter.filtered) {
                    pw.println("  mMaxPackageEnqueueRate=" + mMaxPackageEnqueueRate);
                    pw.println("  hideSilentStatusBar="
                            + mPreferencesHelper.shouldHideSilentStatusIcons());
                    mAttentionHelper.dumpLocked(pw, "    ", filter);
                }
                pw.println("  mArchive=" + mArchive.toString());
                mArchive.dumpImpl(pw, filter);

                if (!zenOnly) {
                    N = mEnqueuedNotifications.size();
                    if (N > 0) {
                        pw.println("  Enqueued Notification List:");
                        for (int i = 0; i < N; i++) {
                            final NotificationRecord nr = mEnqueuedNotifications.get(i);
                            if (filter.filtered && !filter.matches(nr.getSbn())) continue;
                            nr.dump(pw, "    ", getContext(), filter.redact);
                        }
                        pw.println("  ");
                    }

                    mSnoozeHelper.dump(pw, filter);
                }
            }

            if (!zenOnly) {
                pw.println("\n  Ranking Config:");
                mRankingHelper.dump(pw, "    ", filter);

                if (nmContextualDisplayLaunch()) {
                    pw.println("\n Notification Rules:");
                    mNotificationRuleManager.dump(pw);
                }

                pw.println("\n Notification Preferences:");
                mPreferencesHelper.dump(pw, "    ", filter, pkgPermissions);

                pw.println("\n  Notification listeners:");
                mListeners.dump(pw, filter);
                pw.print("    mListenerHints: "); pw.println(mListenerHints);

                pw.print("    mListenersDisablingEffects: (");
                N = mListenersDisablingEffects.size();
                for (int i = 0; i < N; i++) {
                    final int hint = mListenersDisablingEffects.keyAt(i);
                    if (i > 0) pw.print(';');
                    pw.print("hint[" + hint + "]:");

                    final ArraySet<ComponentName> listeners = mListenersDisablingEffects.valueAt(i);
                    final int listenerSize = listeners.size();

                    for (int j = 0; j < listenerSize; j++) {
                        if (j > 0) pw.print(',');
                        final ComponentName listener = listeners.valueAt(j);
                        if (listener != null) {
                            pw.print(listener);
                        }
                    }
                }
                pw.println(')');

                pw.println("\n  NotificationListenerStats:");
                mNotificationListenerStats.dump(pw, "    ");

                pw.println("\n  Notification assistant services:");
                mAssistants.dump(pw, filter);
            }

            if (!filter.filtered || zenOnly) {
                pw.println("\n  Zen Mode:");
                pw.print("    mInterruptionFilter="); pw.println(mInterruptionFilter);
                mZenModeHelper.dump(pw, "    ");

                pw.println("\n  Zen Log:");
                ZenLog.dump(pw, "    ");
            }

            pw.println("\n  Condition providers:");
            mConditionProviders.dump(pw, filter);

            pw.println("\n  Group summaries:");
            for (Entry<String, NotificationRecord> entry : mSummaryByGroupKey.entrySet()) {
                NotificationRecord r = entry.getValue();
                pw.println("    " + entry.getKey() + " -> " + r.getKey());
                if (mNotificationsByKey.get(r.getKey()) != r) {
                    pw.println("!!!!!!LEAK: Record not found in mNotificationsByKey.");
                    r.dump(pw, "      ", getContext(), filter.redact);
                }
            }

            if (!zenOnly) {
                pw.println("\n  Usage Stats:");
                mUsageStats.dump(pw, "    ", filter);

                pw.println("\n  TimeToLive alarms:");
                mTtlHelper.dump(pw, "    ");
            }

            pw.println("\n  GroupHelper:");
            mGroupHelper.dump(pw, "    ");
        }
        mConfigurableParameters.dump(new IndentingPrintWriter(pw, "  ", "  "));
    }

    /**
     * The private API only accessible to the system process.
     */
    private class NotificationManagerInternalImpl implements NotificationManagerInternal {

        public byte[] getBackupPayload(int user, BackupRestoreEventLogger logger) {
            checkCallerIsSystem();
            if (DBG) Slog.d(TAG, "getBackupPayload u=" + user);
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            final TypedXmlSerializer out = Xml.newFastSerializer();
            try {
                out.setOutput(baos, StandardCharsets.UTF_8.name());
                out.startDocument(null, true);
                // for backwards compatibility with pre nmContextualDisplayLaunch() releases,
                // the notification policy block must be written first
                writePolicyXml(out, true /*forBackup*/, user, logger);
                if (nmContextualDisplayLaunch()) {
                    writeRulesXml(out, true /*forBackup*/, user, logger);
                }
                out.endDocument();
                return baos.toByteArray();
            } catch (IOException e) {
                Slog.w(TAG, "getBackupPayload: error writing payload for user " + user, e);
            }
            return null;
        }

        @Override
        public void applyRestore(byte[] payload, int user, BackupRestoreEventLogger logger) {
            checkCallerIsSystem();
            if (DBG) Slog.d(TAG, "applyRestore u=" + user + " payload="
                    + (payload != null ? new String(payload, StandardCharsets.UTF_8) : null));
            if (payload == null) {
                Slog.w(TAG, "applyRestore: no payload to restore for user " + user);
                return;
            }
            final ByteArrayInputStream bais = new ByteArrayInputStream(payload);
            try {
                // for backwards compatibility with pre nmContextualDisplayLaunch() releases,
                // the notification policy block must be written first
                final TypedXmlPullParser parser = Xml.newFastPullParser();
                parser.setInput(bais, StandardCharsets.UTF_8.name());

                readPolicyXml(parser, true /*forRestore*/, user, logger);
                handleSavePolicyFile();
                if (nmContextualDisplayLaunch()) {
                    readRulesXml(parser, true /*forRestore*/, user, logger);
                    handleSaveRulesFile();
                }
            } catch (NumberFormatException | XmlPullParserException | IOException e) {
                Slog.w(TAG, "applyRestore: error reading payload", e);
            }
        }

        @Override
        public NotificationChannel getNotificationChannel(String pkg, int uid, String
                channelId) {
            return mPreferencesHelper.getNotificationChannel(pkg, uid, channelId, false);
        }

        @Override
        public NotificationChannelGroup getNotificationChannelGroup(String pkg, int uid, String
                channelId) {
            return mPreferencesHelper.getGroupForChannel(pkg, uid, channelId);
        }

        @Override
        public void enqueueNotification(String pkg, String opPkg, int callingUid, int callingPid,
                String tag, int id, Notification notification, int userId) {
            enqueueNotificationInternal(pkg, opPkg, callingUid, callingPid, tag, id, notification,
                    userId, /* byForegroundService= */ false , /* isAppProvided= */ true);
        }

        @Override
        public void enqueueNotification(String pkg, String opPkg, int callingUid, int callingPid,
                String tag, int id, Notification notification, int userId,
                boolean byForegroundService) {
            enqueueNotificationInternal(pkg, opPkg, callingUid, callingPid, tag, id, notification,
                    userId, byForegroundService, /* isAppProvided= */ true);
        }

        @Override
        public void cancelNotification(String pkg, String opPkg, int callingUid, int callingPid,
                String tag, int id, int userId) {
            // Don't allow client applications to cancel foreground service notifs,
            // user-initiated job notifs, computer control notifs or autobundled summaries.
            final int mustNotHaveFlags = isCallingUidSystem() ? 0 :
                    (FLAG_FOREGROUND_SERVICE | FLAG_USER_INITIATED_JOB | FLAG_AUTOGROUP_SUMMARY
                            | FLAG_COMPUTER_CONTROL);
            cancelNotificationInternal(pkg, opPkg, callingUid, callingPid, tag, id, userId,
                    mustNotHaveFlags);
        }

        @Override
        public boolean isNotificationShown(String pkg, String tag, int notificationId, int userId) {
            return isNotificationShownInternal(pkg, tag, notificationId, userId);
        }

        @Override
        public void removeForegroundServiceFlagFromNotification(String pkg, int notificationId,
                int userId) {
            checkCallerIsSystem();
            mHandler.post(() -> {
                synchronized (mNotificationLock) {
                    removeFlagFromNotificationLocked(pkg, notificationId, userId,
                            FLAG_FOREGROUND_SERVICE);
                }
            });
        }

        @Override
        public void removeUserInitiatedJobFlagFromNotification(String pkg, int notificationId,
                int userId) {
            checkCallerIsSystem();
            mHandler.post(() -> {
                synchronized (mNotificationLock) {
                    removeFlagFromNotificationLocked(pkg, notificationId, userId,
                            FLAG_USER_INITIATED_JOB);
                }
            });
        }

        @Override
        public void removeComputerControlFlagFromNotification(String pkg, int notificationId,
                int userId) {
            checkCallerIsSystem();
            mHandler.post(() -> {
                synchronized (mNotificationLock) {
                    removeFlagFromNotificationLocked(pkg, notificationId, userId,
                            FLAG_COMPUTER_CONTROL);
                }
            });
        }

        @GuardedBy("mNotificationLock")
        private void removeFlagFromNotificationLocked(String pkg, int notificationId, int userId,
                int flag) {
            int count = getNotificationCount(pkg, userId);
            boolean removeFlagFromNotification = false;
            if (count > MAX_PACKAGE_NOTIFICATIONS) {
                mUsageStats.registerOverCountQuota(pkg);
                removeFlagFromNotification = true;
            }
            if (removeFlagFromNotification) {
                NotificationRecord r = findNotificationLocked(pkg, null, notificationId, userId);
                if (r != null) {
                    if (DBG) {
                        final String type;
                        if (flag == FLAG_FOREGROUND_SERVICE) {
                            type = "FGS";
                        } else if (flag == FLAG_USER_INITIATED_JOB) {
                            type = "UIJ";
                        } else if (flag == FLAG_COMPUTER_CONTROL) {
                            type = "Computer Control";
                        } else {
                            type = "Unknown";
                        }
                        Slog.d(TAG, "Remove " + type + " flag not allow. "
                                + "Cancel " + type + " notification");
                    }
                    removeFromNotificationListsLocked(r);
                    cancelNotificationLocked(r, false, REASON_APP_CANCEL, true,
                            null, SystemClock.elapsedRealtime());
                }
            } else {
                // Notifications with FLAG_COMPUTER_CONTROL are non-dismissible, so remove
                // FLAG_NO_DISMISS as well when removing FLAG_COMPUTER_CONTROL.
                if (flag == FLAG_COMPUTER_CONTROL) {
                    flag |= FLAG_NO_DISMISS;
                }
                List<NotificationRecord> enqueued = findNotificationsByListLocked(
                        mEnqueuedNotifications, pkg, null, notificationId, userId);
                for (int i = 0; i < enqueued.size(); i++) {
                    final NotificationRecord r = enqueued.get(i);
                    if (r != null) {
                        // strip flag from all enqueued notifications. listeners will be informed
                        // in post runnable.
                        StatusBarNotification sbn = r.getSbn();
                        sbn.getNotification().flags = (r.getFlags() & ~flag);
                    }
                }

                NotificationRecord r = findNotificationByListLocked(
                        mNotificationList, pkg, null, notificationId, userId);
                if (r != null) {
                    // if posted notification exists, strip its flag and tell listeners
                    StatusBarNotification sbn = r.getSbn();
                    sbn.getNotification().flags = (r.getFlags() & ~flag);
                    mRankingHelper.sort(mNotificationList);
                    mListeners.notifyPostedLocked(r, r);
                }
            }
        }

        @Override
        public void onConversationRemoved(String pkg, int uid, Set<String> shortcuts) {
            onConversationRemovedInternal(pkg, uid, shortcuts);
        }

        @Override
        public int getNumNotificationChannelsForPackage(String pkg, int uid,
                boolean includeDeleted) {
            return NotificationManagerService.this
                    .getNumNotificationChannelsForPackage(pkg, uid, includeDeleted);
        }

        @Override
        public boolean areNotificationsEnabledForPackage(String pkg, int uid) {
            return areNotificationsEnabledForPackageInt(uid);
        }

        @Override
        public void sendReviewPermissionsNotification() {
            if (!mShowReviewPermissionsNotification) {
                // don't show if this notification is turned off
                return;
            }

            // This method is meant to be called from the JobService upon running the job for this
            // notification having been rescheduled; so without checking any other state, it will
            // send the notification.
            checkCallerIsSystem();
            NotificationManager nm = getContext().getSystemService(NotificationManager.class);
            nm.notify(TAG,
                    SystemMessageProto.SystemMessage.NOTE_REVIEW_NOTIFICATION_PERMISSIONS,
                    createReviewPermissionsNotification());
            Settings.Global.putInt(getContext().getContentResolver(),
                    Settings.Global.REVIEW_PERMISSIONS_NOTIFICATION_STATE,
                    NotificationManagerService.REVIEW_NOTIF_STATE_RESHOWN);
        }

        @Override
        public void cleanupHistoryFiles() {
            checkCallerIsSystem();
            mHistoryManager.cleanupHistoryFiles();
        }

        @Override
        public void removeBitmaps() {
            // Check all NotificationRecords, remove expired bitmaps and icon URIs, repost silently.
            synchronized (mNotificationLock) {
                for (NotificationRecord r: mNotificationList) {

                    // System#currentTimeMillis when posted
                    final long timePostedMs = r.getSbn().getPostTime();
                    final long timeNowMs = System.currentTimeMillis();
                    if (isBitmapExpired(timePostedMs, timeNowMs, BITMAP_DURATION.toMillis())) {
                        removeBitmapAndRepost(r);
                    }
                }
            }
        }

        @Override
        public void setDeviceEffectsApplier(DeviceEffectsApplier applier) {
            // This can also throw IllegalStateException if called too late.
            requireZenModeHelper().setDeviceEffectsApplier(applier);
        }

        @Override
        public void onDisplayRemoveSystemDecorations(int displayId) {
            synchronized (mToastQueue) {
                for (int i = mToastQueue.size() - 1; i >= 0; i--) {
                    final ToastRecord toast = mToastQueue.get(i);
                    if (toast.displayId == displayId) {
                        cancelToastLocked(i);
                    }
                }
            }
        }

        @Override
        public void requestSystemAdjustments(@NonNull List<Adjustment> adjustments) {
            checkCallerIsSystem();
            if (enablePersonalContextService()) {
                synchronized (mNotificationLock) {
                    requestSystemAdjustmentsLocked(adjustments);
                }
            }
        }

        @Override
        public Map<String, AutomaticZenRule> getAutomaticZenRules(UserHandle userHandle) {
            if (!android.service.notification.Flags.enableDndSync()) {
                return Map.of();
            }
            return requireZenModeHelper().getAutomaticZenRules(userHandle, Binder.getCallingUid());
        }

        @Override
        public boolean isManualZenRuleActive(UserHandle userHandle) {
            if (!android.service.notification.Flags.enableDndSync()) {
                return false;
            }
            return requireZenModeHelper().getManualZenMode(userHandle) != Global.ZEN_MODE_OFF;
        }

        @Override
        public void setManualZenRuleActive(UserHandle userHandle, boolean active) {
            if (!android.service.notification.Flags.enableDndSync()) {
                return;
            }
            // This should act as if user toggles the systemui button.
            requireZenModeHelper().setManualZenMode(
                    userHandle,
                    active ? Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS : Global.ZEN_MODE_OFF,
                    /* conditionId= */ null,
                    ZenModeConfig.ORIGIN_USER_IN_SYSTEMUI,
                    /* reason= */ "NotificationManagerInternal.setManualZenRuleActive",
                    /* caller= */ null,
                    Binder.getCallingUid());
        }

        @Override
        public boolean isAutomaticZenRuleActive(UserHandle userHandle, String id) {
            if (!android.service.notification.Flags.enableDndSync()) {
                return false;
            }
            return requireZenModeHelper().getAutomaticZenRuleState(
                    userHandle, id, Binder.getCallingUid()) == Condition.STATE_TRUE;
        }

        @Override
        public void setAutomaticZenRuleActive(
                UserHandle userHandle, String id, boolean active) {
            if (!android.service.notification.Flags.enableDndSync()) {
                return;
            }
            ZenModeHelper zenModeHelper = requireZenModeHelper();
            int uid = Binder.getCallingUid();
            AutomaticZenRule rule = zenModeHelper.getAutomaticZenRule(userHandle, id, uid);
            if (rule == null) {
                return;
            }
            // This should act as if user toggles the systemui button.
            // TODO(b/461827745): allow callers to specify the origin, and possibly support cross-
            // device origins.
            Condition condition =
                    new Condition(
                            rule.getConditionId(),
                            /* summary= */ "",
                            active ? Condition.STATE_TRUE : Condition.STATE_FALSE,
                            Condition.SOURCE_USER_ACTION);
            zenModeHelper.setAutomaticZenRuleState(
                    userHandle, id, condition, ZenModeConfig.ORIGIN_USER_IN_SYSTEMUI, uid);
        }

        @Override
        public void addZenModeCallback(ZenModeHelper.Callback callback) {
            if (!android.service.notification.Flags.enableDndSync()) {
                return;
            }
            requireZenModeHelper().addCallback(callback);
        }

        @Override
        public boolean hasZenModeConfig(UserHandle userHandle) {
            if (!android.service.notification.Flags.enableDndSync()) {
                return false;
            }
            return requireZenModeHelper().hasZenModeConfig(userHandle);
        }

        @NonNull
        private ZenModeHelper requireZenModeHelper() {
            if (mZenModeHelper == null) {
                throw new IllegalStateException("ZenModeHelper is not yet ready!");
            }
            return mZenModeHelper;
        }
    };

    private static boolean isBigPictureWithBitmapOrIcon(Notification n) {
        final boolean isBigPicture = n.isStyle(Notification.BigPictureStyle.class);
        if (!isBigPicture) {
            return false;
        }

        final boolean hasBitmap = n.extras.containsKey(Notification.EXTRA_PICTURE)
                && n.extras.getParcelable(Notification.EXTRA_PICTURE) != null;
        if (hasBitmap) {
            return true;
        }

        final boolean hasIcon = n.extras.containsKey(Notification.EXTRA_PICTURE_ICON)
                && n.extras.getParcelable(Notification.EXTRA_PICTURE_ICON) != null;
        if (hasIcon) {
            return true;
        }
        return false;
    }

    private static boolean isBitmapExpired(long timePostedMs, long timeNowMs, long timeToLiveMs) {
        final long timeDiff = timeNowMs - timePostedMs;
        return timeDiff > timeToLiveMs;
    }

    private void removeBitmapAndRepost(NotificationRecord r) {
        if (!isBigPictureWithBitmapOrIcon(r.getNotification())) {
            return;
        }
        // Remove Notification object's reference to picture bitmap or URI. Leave the extras set to
        // null to avoid crashing apps that came to expect them to be present but null.
        r.getNotification().extras.putParcelable(Notification.EXTRA_PICTURE, null);
        r.getNotification().extras.putParcelable(Notification.EXTRA_PICTURE_ICON, null);

        // Make Notification silent
        r.getNotification().flags |= FLAG_ONLY_ALERT_ONCE;

        // Repost as the original app (even if it was posted by a delegate originally
        // because the delegate may now be revoked)
        enqueueNotificationInternal(r.getSbn().getPackageName(),
                r.getSbn().getPackageName(), r.getSbn().getUid(),
                MY_PID, r.getSbn().getTag(),
                r.getSbn().getId(), r.getNotification(),
                r.getSbn().getUserId(), /* postSilently= */ true,
                /* byForegroundService= */ false,
                /* isAppProvided= */ false);
    }

    int getNumNotificationChannelsForPackage(String pkg, int uid, boolean includeDeleted) {
        // don't show perm prompt if the only channels are bundle channels
        return mPreferencesHelper.getNotificationChannels(
                pkg, uid, includeDeleted, false).getList().size();
    }

    void cancelNotificationInternal(String pkg, String opPkg, int callingUid, int callingPid,
            String tag, int id, @CanBeALL @CanBeCURRENT @UserIdInt int userId,
            int mustNotHaveFlags) {
        userId = ActivityManager.handleIncomingUser(callingPid,
                callingUid, userId, true, false, "cancelNotificationWithTag", pkg);

        // ensure opPkg is delegate if does not match pkg

        int uid = INVALID_UID;

        try {
            uid = resolveNotificationUid(opPkg, pkg, callingUid, userId);
        } catch (NameNotFoundException e) {
            // package either never existed so there's no posted notification or it's being
            // uninstalled so we'll be cleaning it up soon. log and return immediately below.
        }

        if (uid == INVALID_UID) {
            Slog.w(TAG, opPkg + ":" + callingUid + " trying to cancel notification "
                    + "for nonexistent pkg " + pkg + " in user " + userId);
            return;
        }

        // if opPkg is not the same as pkg, make sure the notification given was posted
        // by opPkg
        if (!Objects.equals(pkg, opPkg)) {
            synchronized (mNotificationLock) {
                // Look for the notification, searching both the posted and enqueued lists.
                NotificationRecord r = findNotificationLocked(pkg, tag, id, userId);
                if (r != null) {
                    if (!Objects.equals(opPkg, r.getSbn().getOpPkg())) {
                        throw new SecurityException(opPkg + " does not have permission to "
                                + "cancel a notification they did not post " + tag + " " + id);
                    }
                }
            }
        }
        if (Flags.traceCancelEvents()) {
            Trace.instant(Trace.TRACE_TAG_SYSTEM_SERVER, "cancelNotificationInternal: " +
                    SmallHash.hash(Objects.hashCode(tag) ^ id));
        }

        cancelNotification(uid, callingPid, pkg, tag, id, 0,
                FlagChecker.mustNotHave(mustNotHaveFlags),
                false, userId, REASON_APP_CANCEL, null);
    }

    boolean isNotificationShownInternal(String pkg, String tag, int notificationId, int userId) {
        synchronized (mNotificationLock) {
            return findNotificationLocked(pkg, tag, notificationId, userId) != null;
        }
    }

    void enqueueNotificationInternal(final String pkg, final String opPkg, final int callingUid,
            final int callingPid, final String tag, final int id, final Notification notification,
            int incomingUserId, boolean byForegroundService, boolean isAppProvided) {
        enqueueNotificationInternal(pkg, opPkg, callingUid, callingPid, tag, id, notification,
                incomingUserId, false /* postSilently */, byForegroundService, isAppProvided);
    }

    void enqueueNotificationInternal(final String pkg, final String opPkg, final int callingUid,
            final int callingPid, final String tag, final int id, final Notification notification,
            int incomingUserId, boolean postSilently, boolean byForegroundService,
            boolean isAppProvided) {
        final int packageImportance = getPackageImportanceWithIdentity(callingUid);
        if (packageImportance == IMPORTANCE_CACHED) {
            mUiEventLogger.log(NOTIFICATION_POSTED_CACHED, callingUid, opPkg);
        }
        PostNotificationTracker tracker = acquireWakeLockForPost(pkg, callingUid);
        boolean enqueued = false;
        try {
            enqueued = enqueueNotificationInternal(pkg, opPkg, callingUid, callingPid, tag, id,
                    notification, incomingUserId, postSilently, tracker, byForegroundService,
                    isAppProvided);
        } finally {
            if (!enqueued) {
                tracker.cancel();
            }
        }
    }

    private PostNotificationTracker acquireWakeLockForPost(String pkg, int uid) {
        // The package probably doesn't have WAKE_LOCK permission and should not require it.
        return Binder.withCleanCallingIdentity(() -> {
            WakeLock wakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "NotificationManagerService:post:" + pkg);
            wakeLock.setWorkSource(new WorkSource(uid, pkg));
            wakeLock.acquire(POST_WAKE_LOCK_TIMEOUT.toMillis());
            return mPostNotificationTrackerFactory.newTracker(wakeLock);
        });
    }

    /**
     * @param notification The notification to consider
     * @return True if we should try to offload bitmaps in the enclosed notification to disk
     */
    private boolean shouldOffloadBitmap(Notification notification) {
        if (!notificationBitmapOffloading() || mBitmapOffloader == null) {
            return false;
        }
        Icon icon = Notification.BigPictureStyle.getPictureIcon(notification.extras);
        if (icon == null || icon.getType() != Icon.TYPE_BITMAP) {
            return false;
        }

        return true;
    }

    /**
     * @return True if we successfully processed the notification and handed off the task of
     * enqueueing it to a background thread; false otherwise.
     */
    private boolean enqueueNotificationInternal(final String pkg, final String opPkg,  //HUI
            final int callingUid, final int callingPid, final String tag, final int id,
            final Notification notification, @CanBeALL @CanBeCURRENT @UserIdInt int incomingUserId,
            boolean postSilently, PostNotificationTracker tracker, boolean byForegroundService,
            boolean isAppProvided) {
        if (DBG) {
            Slog.v(TAG, "enqueueNotificationInternal: pkg=" + pkg + " id=" + id
                    + " notification=" + notification);
        }

        if (pkg == null || notification == null) {
            throw new IllegalArgumentException("null not allowed: pkg=" + pkg
                    + " id=" + id + " notification=" + notification);
        }

        final int userId = ActivityManager.handleIncomingUser(callingPid,
                callingUid, incomingUserId, true, false, "enqueueNotification", pkg);
        final UserHandle user = UserHandle.of(userId);

        // Can throw a SecurityException if the calling uid doesn't have permission to post
        // as "pkg"
        int notificationUid = INVALID_UID;

        try {
            notificationUid = resolveNotificationUid(opPkg, pkg, callingUid, userId);
        } catch (NameNotFoundException e) {
            // not great -  throw immediately below
        }

        if (notificationUid == INVALID_UID) {
            throw new SecurityException("Caller " + opPkg + ":" + callingUid
                    + " trying to post for invalid pkg " + pkg + " in user " + incomingUserId);
        }

        IBinder allowlistToken = notification.getAllowlistToken();
        if (allowlistToken != null && allowlistToken != ALLOWLIST_TOKEN) {
            throw new SecurityException(
                    "Unexpected allowlist token received from " + callingUid);
        }
        // allowlistToken is populated by unparceling, so it can be null if the notification was
        // posted from inside system_server. Ensure it's the expected value.
        notification.overrideAllowlistToken(ALLOWLIST_TOKEN);

        checkRestrictedCategories(notification);

        // Notifications passed to setForegroundService() have FLAG_FOREGROUND_SERVICE,
        // but it's also possible that the app has called notify() with an update to an
        // FGS notification that hasn't yet been displayed.  Make sure we check for any
        // FGS-related situation up front, outside of any locks so it's safe to call into
        // the Activity Manager.
        final ServiceNotificationPolicy policy = mAmi.applyForegroundServiceNotification(
                notification, tag, id, pkg, userId);

        boolean stripUijFlag = true;
        final JobSchedulerInternal js = LocalServices.getService(JobSchedulerInternal.class);
        if (js != null) {
            stripUijFlag = !js.isNotificationAssociatedWithAnyUserInitiatedJobs(id, userId, pkg);
        }
        if (mComputerControlHelper == null) {
            mComputerControlHelper = ComputerControlHelper.forLocalService();
        }
        final boolean stripComputerControlFlag =
                !android.companion.virtualdevice.flags.Flags.computerControlAccess()
                        || mComputerControlHelper == null
                        || !mComputerControlHelper.isUidEligibleToSetComputerControlFlag(
                                callingUid);

        // Fix the notification as best we can.
        try {
            fixNotification(notification, pkg, tag, id, userId, notificationUid,
                    policy, stripUijFlag, stripComputerControlFlag);
        } catch (Exception e) {
            if (notification.isForegroundService()) {
                throw new SecurityException("Invalid FGS notification", e);
            }
            Slog.e(TAG, "Cannot fix notification", e);
            return false;
        }

        if (policy == ServiceNotificationPolicy.UPDATE_ONLY) {
            // Proceed if the notification is already showing/known, otherwise ignore
            // because the service lifecycle logic has retained responsibility for its
            // handling.
            if (!isNotificationShownInternal(pkg, tag, id, userId)) {
                reportForegroundServiceUpdate(false, notification, id, pkg, userId);
                return false;
            }
        }

        mUsageStats.registerEnqueuedByApp(pkg);

        final StatusBarNotification n = new StatusBarNotification(
                pkg, opPkg, id, tag, notificationUid, callingPid, notification,
                user, null, System.currentTimeMillis());

        // setup local book-keeping
        String channelId = notification.getChannelId();
        if (mIsTelevision && (new Notification.TvExtender(notification)).getChannelId() != null) {
            channelId = (new Notification.TvExtender(notification)).getChannelId();
        }
        String shortcutId = n.getShortcutId();
        final NotificationChannel channel = getNotificationChannelRestoreDeleted(pkg,
                callingUid, notificationUid, channelId, shortcutId);
        if (channel == null) {
            final String noChannelStr = "No Channel found for "
                    + "pkg=" + pkg
                    + ", channelId=" + channelId
                    + ", id=" + id
                    + ", tag=" + tag
                    + ", opPkg=" + opPkg
                    + ", callingUid=" + callingUid
                    + ", userId=" + userId
                    + ", incomingUserId=" + incomingUserId
                    + ", notificationUid=" + notificationUid
                    + ", notification=" + notification;
            Slog.e(TAG, noChannelStr);
            boolean appNotificationsOff = !mPermissionHelper.hasPermission(notificationUid);


            if (!appNotificationsOff) {
                doChannelWarningToast(notificationUid,
                        "Developer warning for package \"" + pkg + "\"\n" +
                        "Failed to post notification on channel \"" + channelId + "\"\n" +
                        "See log for more details");
            }
            return false;
        }

        fixNotificationWithChannel(notification, channel, notificationUid, pkg);

        final NotificationRecord r = new NotificationRecord(getContext(), n, channel);
        r.setIsAppImportanceLocked(mPermissionHelper.isPermissionUserSet(pkg, userId));
        r.setPostSilently(postSilently);
        r.setFlagBubbleRemoved(false);
        r.setPkgAllowedAsConvo(mMsgPkgsAllowedAsConvos.contains(pkg));
        boolean isImportanceFixed = mPermissionHelper.isPermissionFixed(pkg, userId);
        r.setImportanceFixed(isImportanceFixed);
        if (notification.isFgsOrUij()) {
            if (((channel.getUserLockedFields() & NotificationChannel.USER_LOCKED_IMPORTANCE) == 0
                        || !channel.isUserVisibleTaskShown())
                    && (r.getImportance() == IMPORTANCE_MIN
                            || r.getImportance() == IMPORTANCE_NONE)) {
                // Increase the importance of fgs/uij notifications unless the user had
                // an opinion otherwise (and the channel hasn't yet shown a fgs/uij).
                channel.setImportance(IMPORTANCE_LOW);
                r.setSystemImportance(IMPORTANCE_LOW);
                if (!channel.isUserVisibleTaskShown()) {
                    channel.unlockFields(NotificationChannel.USER_LOCKED_IMPORTANCE);
                    channel.setUserVisibleTaskShown(true);
                }
                mPreferencesHelper.updateNotificationChannel(
                        pkg, notificationUid, channel, false, callingUid,
                        isCallerSystemOrSystemUi());
                r.updateSystemNotificationChannel(channel);
            } else if (!channel.isUserVisibleTaskShown() && !TextUtils.isEmpty(channelId)
                    && !NotificationChannel.DEFAULT_CHANNEL_ID.equals(channelId)) {
                channel.setUserVisibleTaskShown(true);
                r.updateSystemNotificationChannel(channel);
            }
        }

        ShortcutInfo info = mShortcutHelper != null
                ? mShortcutHelper.getValidShortcutInfo(notification.getShortcutId(), pkg, user)
                : null;
        if (notification.getShortcutId() != null && info == null) {
            Slog.w(TAG, "notification " + r.getKey() + " added an invalid shortcut");
        }
        r.setShortcutInfo(info);
        r.setHasSentValidMsg(mPreferencesHelper.hasSentValidMsg(pkg, notificationUid));
        r.userDemotedAppFromConvoSpace(
                mPreferencesHelper.hasUserDemotedInvalidMsgApp(pkg, notificationUid));

        if (!checkDisqualifyingFeatures(userId, notificationUid, id, tag, r,
                r.getSbn().getOverrideGroupKey() != null, byForegroundService)) {
            synchronized (mNotificationLock) {
                markOffloadedBitmapsForDeletion(r);
            }
            return false;
        }

        mUsageStats.registerEnqueuedByAppAndAccepted(pkg);

        if (info != null) {
            // Cache the shortcut synchronously after the associated notification is posted in case
            // the app unpublishes this shortcut immediately after posting the notification. If the
            // user does not modify the notification settings on this conversation, the shortcut
            // will be uncached by People Service when all the associated notifications are removed.
            mShortcutHelper.cacheShortcut(info, user);
        }

        // temporarily allow apps to perform extra work when their pending intents are launched
        if (notification.allPendingIntents != null) {
            final int intentCount = notification.allPendingIntents.size();
            if (intentCount > 0) {
                final long duration = LocalServices.getService(
                        DeviceIdleInternal.class).getNotificationAllowlistDuration();
                for (int i = 0; i < intentCount; i++) {
                    PendingIntent pendingIntent = notification.allPendingIntents.valueAt(i);
                    if (pendingIntent != null) {
                        mAmi.setPendingIntentAllowlistDuration(pendingIntent.getTarget(),
                                ALLOWLIST_TOKEN, duration,
                                TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
                                REASON_NOTIFICATION_SERVICE,
                                "NotificationManagerService");
                        mAmi.setPendingIntentAllowBgActivityStarts(pendingIntent.getTarget(),
                                ALLOWLIST_TOKEN, (FLAG_ACTIVITY_SENDER | FLAG_BROADCAST_SENDER
                                        | FLAG_SERVICE_SENDER));
                    }
                }
            }
        }

        // Need escalated privileges to get package importance.
        final int packageImportance = getPackageImportanceWithIdentity(pkg);
        boolean isAppForeground = packageImportance == IMPORTANCE_FOREGROUND;
        mHandler.post(new EnqueueNotificationRunnable(userId, r, isAppForeground,
                /* isAppProvided= */ isAppProvided, tracker));
        return true;
    }

    /**
     * Returns a channel, if exists and is not a bundle channel, and restores deleted
     * conversation channels.
     */
    @Nullable
    private NotificationChannel getNotificationChannelRestoreDeleted(String pkg,
            int callingUid, int notificationUid, String channelId, String conversationId) {
        // Restore a deleted conversation channel, if exists. Otherwise use the parent channel.
        NotificationChannel channel = mPreferencesHelper.getConversationNotificationChannel(
                pkg, notificationUid, channelId, conversationId,
                true /* parent ok */, !TextUtils.isEmpty(conversationId) /* includeDeleted */);
        // Restore deleted conversation channel
        if (channel != null && channel.isDeleted()) {
            if (Objects.equals(conversationId, channel.getConversationId())) {
                boolean needsPolicyFileChange = mPreferencesHelper.createNotificationChannel(
                        pkg, notificationUid, channel, true /* fromTargetApp */,
                        mConditionProviders.isPackageOrComponentAllowed(pkg,
                        UserHandle.getUserId(notificationUid)), callingUid, true);
                // Update policy file if the conversation channel was restored
                if (needsPolicyFileChange) {
                    handleSavePolicyFile();
                }
            } else {
                // Do not restore parent channel
                channel = null;
            }
        }
        if (channel != null && channel.isBundleChannel()) {
            // apps cannot post to these channels directly, in case they post incorrect content
            return null;
        }
        return channel;
    }

    private void onConversationRemovedInternal(String pkg, int uid, Set<String> shortcuts) {
        checkCallerIsSystem();
        Preconditions.checkStringNotEmpty(pkg);

        mHistoryManager.deleteConversations(pkg, uid, shortcuts);
        List<String> deletedChannelIds =
                mPreferencesHelper.deleteConversations(pkg, uid, shortcuts);
        for (String channelId : deletedChannelIds) {
            cancelAllNotificationsInt(MY_UID, MY_PID, pkg, channelId, 0, 0,
                    UserHandle.getUserId(uid), REASON_CHANNEL_REMOVED
            );
        }
        handleSavePolicyFile();
    }

    private void makeStickyHun(Notification notification, String pkg, @UserIdInt int userId) {
        if (mPermissionHelper.hasRequestedPermission(
                Manifest.permission.USE_FULL_SCREEN_INTENT, pkg, userId)) {
            notification.flags |= FLAG_FSI_REQUESTED_BUT_DENIED;
        }
        if (notification.contentIntent == null) {
            // On notification click, if contentIntent is null, SystemUI launches the
            // fullScreenIntent instead.
            notification.contentIntent = notification.fullScreenIntent;
        }
        notification.fullScreenIntent = null;
    }

    @VisibleForTesting
    protected void fixNotification(Notification notification, String pkg, String tag, int id,
            @UserIdInt int userId, int notificationUid,
            ServiceNotificationPolicy fgsPolicy, boolean stripUijFlag,
            boolean stripComputerControlFlag) throws NameNotFoundException, RemoteException {
        final ApplicationInfo ai = mPackageManagerClient.getApplicationInfoAsUser(
                pkg, PackageManager.MATCH_DEBUG_TRIAGED_MISSING,
                (userId == USER_ALL) ? USER_SYSTEM : userId);
        Notification.addFieldsFromContext(ai, notification);

        // can't be set by an app
        notification.extras.remove(Notification.EXTRA_SUMMARIZED_CONTENT);

        if (notification.isForegroundService() && fgsPolicy == NOT_FOREGROUND_SERVICE) {
            notification.flags &= ~FLAG_FOREGROUND_SERVICE;
        }
        if (notification.isUserInitiatedJob() && stripUijFlag) {
            notification.flags &= ~FLAG_USER_INITIATED_JOB;
        }
        if (notification.isComputerControl() && stripComputerControlFlag) {
            notification.flags &= ~FLAG_COMPUTER_CONTROL;
        }

        // Remove FLAG_AUTO_CANCEL from notifications that are associated with a FGS or UIJ or
        // a computer control session.
        if (notification.isFgsOrUij() || notification.isComputerControl()) {
            notification.flags &= ~FLAG_AUTO_CANCEL;
        }

        // Only notifications that can be non-dismissible can have the flag FLAG_NO_DISMISS
        if (((notification.flags & FLAG_ONGOING_EVENT) > 0)
                && canBeNonDismissible(ai, notification, id, tag)) {
            notification.flags |= FLAG_NO_DISMISS;
        } else {
            notification.flags &= ~FLAG_NO_DISMISS;
        }

        int canColorize = getContext().checkPermission(
                android.Manifest.permission.USE_COLORIZED_NOTIFICATIONS, -1, notificationUid);

        if (canColorize == PERMISSION_GRANTED) {
            notification.flags |= Notification.FLAG_CAN_COLORIZE;
        } else {
            notification.flags &= ~Notification.FLAG_CAN_COLORIZE;
        }

        if (notification.extras.getBoolean(Notification.EXTRA_ALLOW_DURING_SETUP, false)) {
            int hasShowDuringSetupPerm = getContext().checkPermission(
                    android.Manifest.permission.NOTIFICATION_DURING_SETUP, -1, notificationUid);
            if (hasShowDuringSetupPerm != PERMISSION_GRANTED) {
                notification.extras.remove(Notification.EXTRA_ALLOW_DURING_SETUP);
                if (DBG) {
                    Slog.w(TAG, "warning: pkg " + pkg + " attempting to show during setup"
                            + " without holding perm "
                            + Manifest.permission.NOTIFICATION_DURING_SETUP);
                }
            }
        }

        if (!android.app.Flags.preferSmallIcon()) {
            if (notification.extras.getBoolean(Notification.EXTRA_PREFER_SMALL_ICON, false)) {
                int hasPackageVerifierAgentPerm = getContext().checkPermission(
                        Manifest.permission.PACKAGE_VERIFICATION_AGENT, -1, notificationUid);
                if (hasPackageVerifierAgentPerm != PERMISSION_GRANTED) {
                    notification.extras.remove(Notification.EXTRA_PREFER_SMALL_ICON);
                    if (DBG) {
                        Slog.w(TAG, "warning: pkg " + pkg + " attempting to show small icon"
                                + " without holding perm "
                                + Manifest.permission.PACKAGE_VERIFICATION_AGENT);
                    }
                }
            }
        }

        notification.flags &= ~FLAG_FSI_REQUESTED_BUT_DENIED;

        // Apps cannot post notifications that are lifetime extended.
        notification.flags &= ~FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY;

        if (notification.fullScreenIntent != null) {
            final AttributionSource attributionSource =
                    new AttributionSource.Builder(notificationUid).setPackageName(pkg).build();
            final boolean canUseFullScreenIntent = checkUseFullScreenIntentPermission(
                    attributionSource, ai, true /* forDataDelivery */);
            if (!canUseFullScreenIntent) {
                makeStickyHun(notification, pkg, userId);
            }
        }

        // Ensure all actions are present
        if (notification.actions != null) {
            boolean hasNullActions = false;
            int nActions = notification.actions.length;
            for (int i = 0; i < nActions; i++) {
                if (notification.actions[i] == null) {
                    hasNullActions = true;
                    break;
                }
            }
            if (hasNullActions) {
                ArrayList<Notification.Action> nonNullActions = new ArrayList<>();
                for (int i = 0; i < nActions; i++) {
                    if (notification.actions[i] != null) {
                        nonNullActions.add(notification.actions[i]);
                    }
                }
                if (nonNullActions.size() != 0) {
                    notification.actions = nonNullActions.toArray(new Notification.Action[0]);
                } else {
                    notification.actions = null;
                }
            }
        }

        // Apps cannot set this flag
         notification.flags &= ~FLAG_PROMOTED_ONGOING;

        // Ensure CallStyle has all the correct actions
        if (notification.isStyle(Notification.CallStyle.class)) {
            Notification.Builder builder =
                    Notification.Builder.recoverBuilder(getContext(), notification);
            Notification.CallStyle style = (Notification.CallStyle) builder.getStyle();
            List<Notification.Action> actions = style.getActionsListWithSystemActions();
            notification.actions = new Notification.Action[actions.size()];
            actions.toArray(notification.actions);
        }

        // Ensure MediaStyle has correct permissions for remote device extras
        if (notification.isStyle(Notification.MediaStyle.class)
                || notification.isStyle(Notification.DecoratedMediaCustomViewStyle.class)) {
            int hasMediaContentControlPermission = getContext().checkPermission(
                    android.Manifest.permission.MEDIA_CONTENT_CONTROL, -1, notificationUid);
            if (hasMediaContentControlPermission != PERMISSION_GRANTED) {
                notification.extras.remove(Notification.EXTRA_MEDIA_REMOTE_DEVICE);
                notification.extras.remove(Notification.EXTRA_MEDIA_REMOTE_ICON);
                notification.extras.remove(Notification.EXTRA_MEDIA_REMOTE_INTENT);
                if (DBG) {
                    Slog.w(TAG, "Package " + pkg + ": Use of setRemotePlayback requires the "
                            + "MEDIA_CONTENT_CONTROL permission");
                }
            }

            // Enforce NO_CLEAR flag on MediaStyle notification for apps with targetSdk >= V.
            if (CompatChanges.isChangeEnabled(ENFORCE_NO_CLEAR_FLAG_ON_MEDIA_NOTIFICATION,
                    notificationUid)) {
                notification.flags |= FLAG_NO_CLEAR;
            }
        }

        // Ensure only allowed packages have a substitute app name
        if (notification.extras.containsKey(Notification.EXTRA_SUBSTITUTE_APP_NAME)) {
            int hasSubstituteAppNamePermission = getContext().checkPermission(
                    permission.SUBSTITUTE_NOTIFICATION_APP_NAME, -1, notificationUid);
            if (hasSubstituteAppNamePermission != PERMISSION_GRANTED) {
                notification.extras.remove(Notification.EXTRA_SUBSTITUTE_APP_NAME);
                if (DBG) {
                    Slog.w(TAG, "warning: pkg " + pkg + " attempting to substitute app name"
                            + " without holding perm "
                            + Manifest.permission.SUBSTITUTE_NOTIFICATION_APP_NAME);
                }
            }
        }

        if (android.app.Flags.nmSummarizationAll()) {
            if (!notification.supportsSummarization()
                || !mAssistants.isAdjustmentAllowed(userId, KEY_SUMMARIZATION)
                || !mAssistants.isAdjustmentAllowedForPackage(userId, KEY_SUMMARIZATION, pkg)) {
                notification.extras.remove(EXTRA_APP_SUMMARIZATION);
            }
        }

        // Remote views? Are they too big?
        checkRemoteViews(pkg, tag, id, notification);

        if (notification.getTimeoutAfter() == 0) {
            notification.setTimeoutAfter(NOTIFICATION_TTL);
        }

        notification.fixSilentGroup();

        if (shouldOffloadBitmap(notification)) {
            Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "offloadNotificationBitmap");
            Icon icon = Notification.BigPictureStyle.getPictureIcon(notification.extras);

            Uri uri = mBitmapOffloader.offloadBitmap(BITMAP_SOURCE_NOTIFICATIONS, icon.getBitmap());
            if (uri != null) {
                icon = Icon.createWithContentUri(uri);
                notification.extras.putParcelable(Notification.EXTRA_PICTURE, null);
                notification.extras.putParcelable(Notification.EXTRA_PICTURE_ICON, icon);
            }
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
        }

        // Ensure only allowed packages can hide status bar notification icon
        if (notification.extras.containsKey(
                Notification.EXTRA_HIDE_STATUS_BAR_NOTIFICATION)) {
            int hasPermission = getContext().checkPermission(
                    permission.HIDE_STATUS_BAR_NOTIFICATION, -1, notificationUid);
            if (hasPermission != PERMISSION_GRANTED) {
                notification.extras.remove(Notification.EXTRA_HIDE_STATUS_BAR_NOTIFICATION);
                Slog.w(TAG, "warning: pkg " + pkg + " attempting to hide status bar"
                        + " notification without holding permission "
                        + "permission.HIDE_STATUS_BAR_NOTIFICATION");
            }
        }

        if (android.app.Flags.bridgedNotifications()) {
            // Ensure only allowed packages add bridged notification metadata.
            if (notification.getBridgedNotificationMetadata() != null) {
                int hasPermission = getContext().checkPermission(
                        permission.POST_BRIDGED_NOTIFICATIONS, -1, notificationUid);
                if (hasPermission != PERMISSION_GRANTED) {
                    notification.removeBridgedNotificationMetadata();
                    Slog.w(TAG, "warning: pkg " + pkg + " attempting to set bridged notification"
                            + " metadata without holding permission "
                            + "permission.POST_BRIDGED_NOTIFICATIONS");
                }
            }
        } else {
            notification.removeBridgedNotificationMetadata();
        }
    }

    private boolean isPromotable(NotificationRecord record) {
        return isPromotable(record.getNotification(), record.getChannel());
    }

    private static boolean isPromotable(Notification notification, NotificationChannel channel) {
        if (!notification.hasPromotableCharacteristics()) {
            return false;
        }
        if (channel.getImportance() <= IMPORTANCE_MIN) {
            return false;
        }
        if (channel.isBundleChannel()) {
            return false;
        }
        return true;
    }

    /**
     * Final notification fixup that can only be performed once channel info is available.
     * @param notification Notification to be operated on
     * @param channel Channel for notification
     * @param notificationUid Uid of package sending notification
     * @param pkg Name of package sending notification
     */
    @VisibleForTesting
    @RequiresPermission(UPDATE_APP_OPS_STATS)
    protected void fixNotificationWithChannel(Notification notification,
            NotificationChannel channel, int notificationUid, String pkg) {
        if (isPromotable(notification, channel)) {
            // Check permission last - after we make sure this is actually an attempted usage
            // of promotion - since AppOps tracks usage attempts.
            final AttributionSource attributionSource =
                    new AttributionSource.Builder(notificationUid)
                            .setPackageName(pkg).build();
            final boolean canPostPromoted = mPermissionManager.checkPermissionForDataDelivery(
                    permission.POST_PROMOTED_NOTIFICATIONS, attributionSource,
                    /* message= */ null) == PermissionManager.PERMISSION_GRANTED;
            if (canPostPromoted) {
                notification.flags |= FLAG_PROMOTED_ONGOING;
            }
        }
    }

    /**
     * Whether a notification can be non-dismissible.
     * A notification should be dismissible, unless it's exempted for some reason.
     */
    private boolean canBeNonDismissible(ApplicationInfo ai, Notification notification, int id,
            String tag) {
        return notification.isMediaNotification() || isEnterpriseExempted(ai)
                || notification.isStyle(Notification.CallStyle.class)
                || isDefaultSearchSelectorPackage(ai.packageName)
                || isDefaultAdservicesPackage(ai.packageName)
                || hasFlag(notification.flags, FLAG_COMPUTER_CONTROL)
                || isNotificationAttachedToComputerControlSession(id, tag, ai.packageName);
    }

    /**
     * Whether the given notification id and tag are associated with a computer control session
     * using the method {@link android.companion.virtual.computercontrol.ComputerControlSession#attachNotificationInfo(int, String)}.
     */
    private boolean isNotificationAttachedToComputerControlSession(int notificationId,
            String notificationTag, String packageName) {
        if (mComputerControlHelper == null) {
            mComputerControlHelper = ComputerControlHelper.forLocalService();
        }
        if (mComputerControlHelper != null) {
            return mComputerControlHelper.isComputerControlNotification(notificationId,
                    notificationTag, packageName);
        }
        return false;
    }

    private boolean isDefaultSearchSelectorPackage(String pkg) {
        return Objects.equals(mDefaultSearchSelectorPkg, pkg);
    }

    private boolean isDefaultAdservicesPackage(String pkg) {
        if (mAdservicesModuleInfo == null) {
            return false;
        }
        // Handles the special package structure for mainline modules
        for (String apkName : mAdservicesModuleInfo.getApkInApexPackageNames()) {
            if (Objects.equals(apkName, pkg)) {
                return true;
            }
        }
        return false;
    }

    private boolean isEnterpriseExempted(ApplicationInfo ai) {
        // Check if the app is an organization admin app
        // TODO(b/234609037): Replace with new DPM APIs to check if organization admin
        if (mDpm != null && (mDpm.isActiveProfileOwner(ai.uid)
                || mDpm.isActiveDeviceOwner(ai.uid))) {
            return true;
        }
        // Check if an app has been given system exemption
        if (ai.uid == Process.SYSTEM_UID) {
            return false;
        }
        return mAppOps.checkOpNoThrow(
                AppOpsManager.OP_SYSTEM_EXEMPT_FROM_DISMISSIBLE_NOTIFICATIONS, ai.uid,
                ai.packageName) == MODE_ALLOWED;
    }

    private boolean checkUseFullScreenIntentPermission(@NonNull AttributionSource attributionSource,
            @NonNull ApplicationInfo applicationInfo,
            boolean forDataDelivery) {
        if (applicationInfo.targetSdkVersion < Build.VERSION_CODES.Q) {
            return true;
        }
        final int permissionResult;
        if (forDataDelivery) {
            permissionResult = mPermissionManager.checkPermissionForDataDelivery(
                    permission.USE_FULL_SCREEN_INTENT, attributionSource, /* message= */ null);
        } else {
            permissionResult = mPermissionManager.checkPermissionForPreflight(
                    permission.USE_FULL_SCREEN_INTENT, attributionSource);
        }
        return permissionResult == PermissionManager.PERMISSION_GRANTED;
    }

    private boolean checkPostPromotedNotificationPermission(
            String pkg, int uid) {
        final AttributionSource attributionSource =
                new AttributionSource.Builder(uid).setPackageName(pkg).build();
        final int permissionResult;
        permissionResult = mPermissionManager.checkPermissionForPreflight(
                permission.POST_PROMOTED_NOTIFICATIONS, attributionSource);
        return permissionResult == PermissionManager.PERMISSION_GRANTED;
    }


    private void checkRemoteViews(String pkg, String tag, int id, Notification notification) {
        if (android.app.Flags.removeRemoteViews()) {
            if (notification.containsCustomViews()) {
                Slog.i(TAG, "Removed customViews for " + pkg);
                mUsageStats.registerImageRemoved(pkg);
            }
            notification.contentView = null;
            notification.bigContentView = null;
            notification.headsUpContentView = null;
            if (notification.publicVersion != null) {
                notification.publicVersion.contentView = null;
                notification.publicVersion.bigContentView = null;
                notification.publicVersion.headsUpContentView = null;
            }
        } else {
            if (removeRemoteView(pkg, tag, id, notification.contentView)) {
                notification.contentView = null;
            }
            if (removeRemoteView(pkg, tag, id, notification.bigContentView)) {
                notification.bigContentView = null;
            }
            if (removeRemoteView(pkg, tag, id, notification.headsUpContentView)) {
                notification.headsUpContentView = null;
            }
            if (notification.publicVersion != null) {
                if (removeRemoteView(pkg, tag, id, notification.publicVersion.contentView)) {
                    notification.publicVersion.contentView = null;
                }
                if (removeRemoteView(pkg, tag, id, notification.publicVersion.bigContentView)) {
                    notification.publicVersion.bigContentView = null;
                }
                if (removeRemoteView(pkg, tag, id, notification.publicVersion.headsUpContentView)) {
                    notification.publicVersion.headsUpContentView = null;
                }
            }
        }
    }

    private boolean removeRemoteView(String pkg, String tag, int id, RemoteViews contentView) {
        if (contentView == null) {
            return false;
        }
        final long contentViewSize = contentView.estimateMemoryUsage();
        if (contentViewSize > mWarnRemoteViewsSizeBytes
                && contentViewSize < mStripRemoteViewsSizeBytes) {
            Slog.w(TAG, "RemoteViews too large on pkg: " + pkg + " tag: " + tag + " id: " + id
                    + " this might be stripped in a future release");
        }
        if (contentViewSize >= mStripRemoteViewsSizeBytes) {
            mUsageStats.registerImageRemoved(pkg);
            Slog.w(TAG, "Removed too large RemoteViews (" + contentViewSize + " bytes) on pkg: "
                    + pkg + " tag: " + tag + " id: " + id);
            return true;
        }
        return false;
    }

    /**
     * Strips any flags from BubbleMetadata that wouldn't apply (e.g. app not foreground).
     */
    private void updateNotificationBubbleFlags(NotificationRecord r, boolean isAppForeground) {
        Notification notification = r.getNotification();
        Notification.BubbleMetadata metadata = notification.getBubbleMetadata();
        if (metadata == null) {
            // Nothing to update
            return;
        }
        if (!isAppForeground) {
            // Auto expand only works if foreground
            int flags = metadata.getFlags();
            flags &= ~Notification.BubbleMetadata.FLAG_AUTO_EXPAND_BUBBLE;
            metadata.setFlags(flags);
        }
        if (!metadata.isBubbleSuppressable()) {
            // If it's not suppressable remove the suppress flag
            int flags = metadata.getFlags();
            flags &= ~Notification.BubbleMetadata.FLAG_SUPPRESS_BUBBLE;
            metadata.setFlags(flags);
        }
    }

    private ShortcutHelper.ShortcutListener mShortcutListener =
            new ShortcutHelper.ShortcutListener() {
                @Override
                public void onShortcutRemoved(String key) {
                    String packageName;
                    synchronized (mNotificationLock) {
                        NotificationRecord r = mNotificationsByKey.get(key);
                        packageName = r != null ? r.getSbn().getPackageName() : null;
                    }
                    final int packageImportance = getPackageImportanceWithIdentity(packageName);
                    boolean isAppForeground = packageName != null
                            && packageImportance == IMPORTANCE_FOREGROUND;
                    synchronized (mNotificationLock) {
                        NotificationRecord r = mNotificationsByKey.get(key);
                        if (r != null) {
                            r.setShortcutInfo(null);
                            // Enqueue will trigger resort & flag is updated that way.
                            r.getNotification().flags |= FLAG_ONLY_ALERT_ONCE;
                            mHandler.post(
                                    new EnqueueNotificationRunnable(
                                            r.getUser().getIdentifier(), r, isAppForeground,
                                            /* isAppProvided= */ false,
                                            mPostNotificationTrackerFactory.newTracker(null)));
                        }
                    }
                }
            };

    protected void doChannelWarningToast(int forUid, CharSequence toastText) {
        Binder.withCleanCallingIdentity(() -> {
            final boolean warningEnabled = Settings.Global.getInt(getContext().getContentResolver(),
                    Settings.Global.SHOW_NOTIFICATION_CHANNEL_WARNINGS, 0) != 0;
            if (warningEnabled) {
                Toast toast = Toast.makeText(getContext(), mHandler.getLooper(), toastText,
                        Toast.LENGTH_SHORT);
                toast.show();
            }
        });
    }

    @VisibleForTesting
    int resolveNotificationUid(String callingPkg, String targetPkg, int callingUid, int userId)
            throws NameNotFoundException {
        if (userId == USER_ALL) {
            userId = UserHandle.getUserId(callingUid);
        }
        // posted from app A on behalf of app A
        if (isCallerSameApp(targetPkg, callingUid, userId)
                && (TextUtils.equals(callingPkg, targetPkg)
                || isCallerSameApp(callingPkg, callingUid, userId))) {
            return callingUid;
        }

        int targetUid = mPackageManagerClient.getPackageUidAsUser(targetPkg, userId);

        // posted from app A on behalf of app B
        if (isCallerAndroid(callingPkg, callingUid)
                || mPreferencesHelper.isDelegateAllowed(
                        targetPkg, targetUid, callingPkg, callingUid)) {
            return targetUid;
        }

        throw new SecurityException("Caller " + callingPkg + ":" + callingUid
                + " cannot post for pkg " + targetPkg + " in user " + userId);
    }

    public boolean hasFlag(final int flags, final int flag) {
        return (flags & flag) != 0;
    }
    /**
     * Checks if a notification can be posted. checks rate limiter, snooze helper, and blocking.
     *
     * Has side effects.
     */
    boolean checkDisqualifyingFeatures(int userId, int uid, int id, String tag,
            NotificationRecord r, boolean isAutogroup, boolean byForegroundService) {
        Notification n = r.getNotification();
        final String pkg = r.getSbn().getPackageName();
        final boolean isSystemNotification =
                isUidSystemOrPhone(uid) || ("android".equals(pkg));
        final boolean isNotificationFromListener = mListeners.isListenerPackage(pkg);

        // Limit the number of notifications that any given package except the android
        // package or a registered listener can enqueue.  Prevents DOS attacks and deals with leaks.
        if (!isSystemNotification && !isNotificationFromListener) {
            final int callingUid = Binder.getCallingUid();
            synchronized (mNotificationLock) {
                if (mNotificationsByKey.get(r.getSbn().getKey()) == null
                        && isCallerInstantApp(callingUid, userId)) {
                    // Ephemeral apps have some special constraints for notifications.
                    // They are not allowed to create new notifications however they are allowed to
                    // update notifications created by the system (e.g. a foreground service
                    // notification).
                    throw new SecurityException("Instant app " + pkg
                            + " cannot create notifications");
                }

                // Rate limit updates. Because this triggers often for progress notifications,
                // explicitly let through "important" progress updates (e.g. progress completed).
                // Search for the original one in the posted and not-yet-posted (enqueued) lists.
                NotificationRecord previous = findPreviousNotificationLocked(r.getKey());
                if (previous != null
                        && previous.getNotification().getProgressState()
                                == r.getNotification().getProgressState()
                        && !isAutogroup) {
                    final float appEnqueueRate = mUsageStats.getAppEnqueueRate(pkg);
                    if (appEnqueueRate > mMaxPackageEnqueueRate) {
                        mUsageStats.registerOverRateQuota(pkg);
                        final long now = SystemClock.elapsedRealtime();
                        if ((now - mLastOverRateLogTime) > MIN_PACKAGE_OVERRATE_LOG_INTERVAL) {
                            Slog.e(TAG, "Package enqueue rate is " + appEnqueueRate
                                    + ". Shedding " + r.getSbn().getKey() + ". package=" + pkg);
                            mLastOverRateLogTime = now;
                        }
                        return false;
                    }
                }
            }

            // Limit the number of non-fgs/uij outstanding notificationrecords an app can have
            // Do not count autogroup summaries
            if (!n.isFgsOrUij() && !GroupHelper.isAggregatedGroup(r)) {
                int count = getNotificationCount(pkg, userId, id, tag);
                if (count >= MAX_PACKAGE_NOTIFICATIONS) {
                    mUsageStats.registerOverCountQuota(pkg);
                    Slog.e(TAG, "Package has already posted or enqueued " + count
                            + " notifications.  Not showing more.  package=" + pkg);
                    return false;
                }
            }
        }

        // bubble or inline reply that's immutable?
        if (n.getBubbleMetadata() != null
                && n.getBubbleMetadata().getIntent() != null
                && hasFlag(mAmi.getPendingIntentFlags(
                        n.getBubbleMetadata().getIntent().getTarget()),
                        PendingIntent.FLAG_IMMUTABLE)) {
            throw new IllegalArgumentException(r.getKey() + " Not posted."
                    + " PendingIntents attached to bubbles must be mutable");
        }

        if (n.actions != null) {
            for (Notification.Action action : n.actions) {
                if ((action.getRemoteInputs() != null || action.getDataOnlyRemoteInputs() != null)
                        && hasFlag(mAmi.getPendingIntentFlags(action.actionIntent.getTarget()),
                        PendingIntent.FLAG_IMMUTABLE)) {
                    throw new IllegalArgumentException(r.getKey() + " Not posted."
                            + " PendingIntents attached to actions with remote"
                            + " inputs must be mutable");
                }
            }
        }

        if (r.getSystemGeneratedSmartActions() != null) {
            for (Notification.Action action : r.getSystemGeneratedSmartActions()) {
                if ((action.getRemoteInputs() != null || action.getDataOnlyRemoteInputs() != null)
                        && hasFlag(mAmi.getPendingIntentFlags(action.actionIntent.getTarget()),
                        PendingIntent.FLAG_IMMUTABLE)) {
                    throw new IllegalArgumentException(r.getKey() + " Not posted."
                            + " PendingIntents attached to contextual actions with remote inputs"
                            + " must be mutable");
                }
            }
        }

        if (n.isStyle(Notification.CallStyle.class)) {
            boolean hasFullScreenIntent = n.fullScreenIntent != null;
            boolean requestedFullScreenIntent = (n.flags & FLAG_FSI_REQUESTED_BUT_DENIED) != 0;
            if (!n.isFgsOrUij() && !hasFullScreenIntent && !requestedFullScreenIntent
                    && !byForegroundService) {
                throw new IllegalArgumentException(r.getKey() + " Not posted."
                        + " CallStyle notifications must be for a foreground service or"
                        + " user initated job or use a fullScreenIntent.");
            }
        }

        // snoozed apps
        if (mSnoozeHelper.isSnoozed(userId, pkg, r.getKey())) {
            MetricsLogger.action(r.getLogMaker()
                    .setType(MetricsProto.MetricsEvent.TYPE_UPDATE)
                    .setCategory(MetricsProto.MetricsEvent.NOTIFICATION_SNOOZED));
            mNotificationRecordLogger.log(
                    NotificationRecordLogger.NotificationEvent.NOTIFICATION_NOT_POSTED_SNOOZED,
                    r);
            if (DBG) {
                Slog.d(TAG, "Ignored enqueue for snoozed notification " + r.getKey());
            }
            mSnoozeHelper.update(userId, r);
            handleSavePolicyFile();
            return false;
        }

        // blocked apps
        boolean isBlocked = !areNotificationsEnabledForPackageInt(uid);
        synchronized (mNotificationLock) {
            isBlocked |= isRecordBlockedLocked(r);
        }


        if (isBridgedNotificationBlocked(r)) {
            isBlocked = true;
            if (DBG) {
                Slog.e(TAG, "Suppressing bridged notification on behalf of package "
                        + r.getBridgedPackageName());
            }
        }

        if (isBlocked && !(n.isMediaNotification() || isCallNotification(pkg, uid, n))) {
            if (DBG) {
                Slog.e(TAG, "Suppressing notification from package " + r.getSbn().getPackageName()
                        + " by user request.");
            }
            mUsageStats.registerBlocked(r);
            return false;
        }

        if (n.hasAppProvidedWhen() && n.getWhen() > 0
                && (System.currentTimeMillis() - n.getWhen()) > NOTIFICATION_MAX_AGE_AT_POST) {
            Slog.d(TAG, "Ignored enqueue for old " + n.getWhen() + " notification " + r.getKey());
            mUsageStats.registerTooOldBlocked(r);
            return false;
        }

        return true;
    }

    private boolean isCallNotification(String pkg, int uid, Notification n) {
        if (n.isStyle(Notification.CallStyle.class)) {
            return isCallNotification(pkg, uid);
        }
        return false;
    }

    private boolean isCallNotification(String pkg, int uid) {
        final long identity = Binder.clearCallingIdentity();
        try {
            if (mPackageManagerClient.hasSystemFeature(FEATURE_TELECOM)
                    && mTelecomManager != null) {
                try {
                    return mTelecomManager.isInManagedCall()
                            || mTelecomManager.isInSelfManagedCall(pkg,
                            UserHandle.ALL);
                } catch (IllegalStateException ise) {
                    // Telecom is not ready (this is likely early boot), so there are no calls.
                    return false;
                }
            }
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private boolean areNotificationsEnabledForPackageInt(int uid) {
        return mPermissionHelper.hasPermission(uid);
    }

    private int getNotificationCount(String pkg, int userId) {
        int count = 0;
        synchronized (mNotificationLock) {
            final int numListSize = mNotificationList.size();
            for (int i = 0; i < numListSize; i++) {
                final NotificationRecord existing = mNotificationList.get(i);
                if (existing.getSbn().getPackageName().equals(pkg)
                        && existing.getSbn().getUserId() == userId) {
                    count++;
                }
            }
            final int numEnqSize = mEnqueuedNotifications.size();
            for (int i = 0; i < numEnqSize; i++) {
                final NotificationRecord existing = mEnqueuedNotifications.get(i);
                if (existing.getSbn().getPackageName().equals(pkg)
                        && existing.getSbn().getUserId() == userId) {
                    count++;
                }
            }
        }
        return count;
    }

    protected int getNotificationCount(String pkg, int userId, int excludedId,
            String excludedTag) {
        int count = 0;
        synchronized (mNotificationLock) {
            final int N = mNotificationList.size();
            for (int i = 0; i < N; i++) {
                final NotificationRecord existing = mNotificationList.get(i);
                if (existing.getSbn().getPackageName().equals(pkg)
                        && existing.getSbn().getUserId() == userId) {
                    if (existing.getSbn().getId() == excludedId
                            && TextUtils.equals(existing.getSbn().getTag(), excludedTag)) {
                        continue;
                    }
                    count++;
                }
            }
            final int M = mEnqueuedNotifications.size();
            for (int i = 0; i < M; i++) {
                final NotificationRecord existing = mEnqueuedNotifications.get(i);
                if (existing.getSbn().getPackageName().equals(pkg)
                        && existing.getSbn().getUserId() == userId) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Checks whether a notification is banned at a group or channel level or if the NAS or system
     * has blocked the notification.
     */
    @GuardedBy("mNotificationLock")
    boolean isRecordBlockedLocked(NotificationRecord r) {
        final String pkg = r.getSbn().getPackageName();
        final int callingUid = r.getSbn().getUid();
        boolean groupBlocked = mPreferencesHelper.isGroupBlocked(
                pkg, callingUid, r.getChannel().getGroup());
        if (nmContextualDisplayLaunch()) {
            return groupBlocked || r.getImportance() == IMPORTANCE_NONE
                    || r.hasPendingBlockAdjustment(mNotificationRuleManager);
        } else {
            return groupBlocked || r.getImportance() == IMPORTANCE_NONE;
        }
    }

    /**
     * Checks whether a bridged notification is banned at a group or channel level or if the NAS or
     * system has blocked the notification.
     */
    boolean isBridgedNotificationBlocked(NotificationRecord r) {
        // If this is a bridged notification check if the associated package or channel are
        // blocked.
        if (r.getBridgedAppUid() != android.os.Process.INVALID_UID
                && r.getBridgedPackageName() != null
                && r.getBridgedChannelId() != null) {
            if (!areNotificationsEnabledForPackageInt(
                    r.getBridgedAppUid())) {
                return true;
            }
            NotificationChannel bridgedChannel = mPreferencesHelper.getNotificationChannel(
                    r.getBridgedPackageName(), r.getBridgedAppUid(),
                    r.getBridgedChannelId(), /*includeDeleted=*/ false);
            if (bridgedChannel != null) {
                return mPreferencesHelper.isGroupBlocked(
                        r.getBridgedPackageName(), r.getBridgedAppUid(),
                        bridgedChannel.getGroup())
                                || bridgedChannel.getImportance() == IMPORTANCE_NONE;
            }
        }
        return false;
    }

    protected class SnoozeNotificationRunnable implements Runnable {
        private final String mKey;
        private final long mDuration;
        private final String mSnoozeCriterionId;

        SnoozeNotificationRunnable(String key, long duration, String snoozeCriterionId) {
            mKey = key;
            mDuration = duration;
            mSnoozeCriterionId = snoozeCriterionId;
        }

        @Override
        public void run() {
            synchronized (mNotificationLock) {
                final NotificationRecord r = findInCurrentAndSnoozedNotificationByKeyLocked(mKey);
                if (r != null) {
                    snoozeLocked(r);
                }
            }
        }

        @GuardedBy("mNotificationLock")
        void snoozeLocked(NotificationRecord r) {
            final List<NotificationRecord> recordsToSnooze = new ArrayList<>();
            if (r.getSbn().isGroup()) {
                final List<NotificationRecord> groupNotifications =
                        findCurrentAndSnoozedGroupNotificationsLocked(
                        r.getSbn().getPackageName(),
                                r.getSbn().getGroupKey(), r.getSbn().getUserId());
                if (r.getNotification().isGroupSummary()) {
                    // snooze all children
                    for (int i = 0; i < groupNotifications.size(); i++) {
                        if (!mKey.equals(groupNotifications.get(i).getKey())) {
                            recordsToSnooze.add(groupNotifications.get(i));
                        }
                    }
                } else {
                    // if there is a valid summary for this group, and we are snoozing the only
                    // child, also snooze the summary
                    if (mSummaryByGroupKey.containsKey(r.getSbn().getGroupKey())) {
                        if (groupNotifications.size() == 2) {
                            // snooze summary and the one child
                            for (int i = 0; i < groupNotifications.size(); i++) {
                                if (!mKey.equals(groupNotifications.get(i).getKey())) {
                                    recordsToSnooze.add(groupNotifications.get(i));
                                }
                            }
                        }
                    }
                }
            }
            // snooze the notification
            recordsToSnooze.add(r);

            if (mSnoozeHelper.canSnooze(recordsToSnooze.size())) {
                for (int i = 0; i < recordsToSnooze.size(); i++) {
                    snoozeNotificationLocked(recordsToSnooze.get(i));
                }
            } else {
                Log.w(TAG, "Cannot snooze " + r.getKey() + ": too many snoozed notifications");
            }
        }

        @GuardedBy("mNotificationLock")
        void snoozeNotificationLocked(NotificationRecord r) {
            MetricsLogger.action(r.getLogMaker()
                    .setCategory(MetricsEvent.NOTIFICATION_SNOOZED)
                    .setType(MetricsEvent.TYPE_CLOSE)
                    .addTaggedData(MetricsEvent.FIELD_NOTIFICATION_SNOOZE_DURATION_MS,
                            mDuration)
                    .addTaggedData(MetricsEvent.NOTIFICATION_SNOOZED_CRITERIA,
                            mSnoozeCriterionId == null ? 0 : 1));
            mNotificationRecordLogger.log(
                    NotificationRecordLogger.NotificationEvent.NOTIFICATION_SNOOZED, r);
            reportUserInteraction(r);
            boolean wasPosted = removeFromNotificationListsLocked(r);
            cancelNotificationLocked(r, false, REASON_SNOOZED, wasPosted, null,
                    SystemClock.elapsedRealtime());
            mAttentionHelper.updateLightsLocked();
            if (isSnoozable(r)) {
                if (mSnoozeCriterionId != null) {
                    mAssistants.notifyAssistantSnoozedLocked(r, mSnoozeCriterionId);
                    mSnoozeHelper.snooze(r, mSnoozeCriterionId);
                } else {
                    mSnoozeHelper.snooze(r, mDuration);
                }
                r.recordSnoozed();
                handleSavePolicyFile();
            }
        }

        /**
         * Autogroup summaries are not snoozable
         * They will be recreated as needed when the group children are unsnoozed
         */
        private boolean isSnoozable(NotificationRecord record) {
            boolean isExemptedSummary =
                    ((record.getFlags() & FLAG_AUTOGROUP_SUMMARY) != 0
                            || GroupHelper.isAggregatedGroup(record));
            return !(record.getNotification().isGroupSummary() && isExemptedSummary);
        }
    }

    private void unsnoozeAll() {
        synchronized (mNotificationLock) {
            mSnoozeHelper.repostAll(mUserProfiles.getCurrentProfileIds());
            handleSavePolicyFile();
        }
    }

    protected class CancelNotificationRunnable implements Runnable {
        private final int mCallingUid;
        private final int mCallingPid;
        private final String mPkg;
        private final String mTag;
        private final int mId;
        private final int mMustHaveFlags;
        private final FlagChecker mFlagChecker;
        private final boolean mSendDelete;
        private final int mUserId;
        private final int mReason;
        private final int mRank;
        private final int mCount;
        private final ManagedServiceInfo mListener;
        private final long mCancellationElapsedTimeMs;

        CancelNotificationRunnable(final int callingUid, final int callingPid,
                final String pkg, final String tag, final int id,
                final int mustHaveFlags, final FlagChecker flagChecker,
                final boolean sendDelete,
                final int userId, final int reason, int rank, int count,
                final ManagedServiceInfo listener,
                @ElapsedRealtimeLong long cancellationElapsedTimeMs) {
            this.mCallingUid = callingUid;
            this.mCallingPid = callingPid;
            this.mPkg = pkg;
            this.mTag = tag;
            this.mId = id;
            this.mMustHaveFlags = mustHaveFlags;
            this.mFlagChecker = flagChecker;
            this.mSendDelete = sendDelete;
            this.mUserId = userId;
            this.mReason = reason;
            this.mRank = rank;
            this.mCount = count;
            this.mListener = listener;
            this.mCancellationElapsedTimeMs = cancellationElapsedTimeMs;

            if (nmRemoveMustHaveFlags() && mustHaveFlags != 0) {
                throw new IllegalArgumentException(
                    "mustHaveFlags must be 0 because nmRemoveMustHaveFlags() is enabled");
            }
        }

        @Override
        public void run() {
            String listenerName = mListener == null ? null : mListener.component.toShortString();
            if (DBG) {
                EventLogTags.writeNotificationCancel(mCallingUid, mCallingPid, mPkg, mId, mTag,
                        mUserId, mMustHaveFlags, /* mustNotHaveFlags= */ 0, mReason, listenerName);
            }
            int packageImportance = getPackageImportanceWithIdentity(mPkg);

            synchronized (mNotificationLock) {
                // Look for the notification, searching both the posted and enqueued lists.
                NotificationRecord r = findNotificationLocked(mPkg, mTag, mId, mUserId);

                if (r != null) {
                    // The notification was found, check if it should be removed.
                    // Ideally we'd do this in the caller of this method. However, that would
                    // require the caller to also find the notification.
                    if (mReason == REASON_CLICK) {
                        mUsageStats.registerClickedByUser(r);
                    }

                    if ((mReason == REASON_LISTENER_CANCEL
                            && r.getNotification().isBubbleNotification())
                            || (mReason == REASON_CLICK && r.canBubble()
                            && r.isFlagBubbleRemoved())) {
                        int flags = 0;
                        if (r.getNotification().getBubbleMetadata() != null) {
                            flags = r.getNotification().getBubbleMetadata().getFlags();
                        }
                        flags |= FLAG_SUPPRESS_NOTIFICATION;
                        mNotificationDelegate.onBubbleMetadataFlagChanged(r.getKey(), flags);
                        return;
                    }
                    if (!nmRemoveMustHaveFlags()) {
                        if ((r.getNotification().flags & mMustHaveFlags) != mMustHaveFlags) {
                            return;
                        }
                    }
                    if (mFlagChecker != null && !mFlagChecker.apply(r.getNotification().flags)) {
                        // If cancellation will be prevented due to lifetime extension,
                        // we need to send an update to system UI first.
                        maybeNotifySystemUiListenerLifetimeExtendedLocked(
                                r, mPkg, packageImportance);
                        return;
                    }

                    FlagChecker childrenFlagChecker = (flags) -> {
                            if (mReason == REASON_CANCEL
                                    || mReason == REASON_CLICK
                                    || mReason == REASON_CANCEL_ALL
                                    || mReason == REASON_BUNDLE_DISMISSED) {
                                // Bubbled children get to stick around if the summary was manually
                                // cancelled (user removed) from systemui.
                                if ((flags & FLAG_BUBBLE) != 0) {
                                    return false;
                                }
                            } else if (mReason == REASON_APP_CANCEL) {
                                if ((flags & FLAG_FOREGROUND_SERVICE) != 0
                                        || (flags & FLAG_USER_INITIATED_JOB) != 0) {
                                    return false;
                                }
                            }
                            if (mFlagChecker != null && !mFlagChecker.apply(flags)) {
                                return false;
                            }
                            return true;
                        };

                    // Cancel the notification.
                    boolean wasPosted = removeFromNotificationListsLocked(r);
                    cancelNotificationLocked(
                            r, mSendDelete, mReason, mRank, mCount, wasPosted, listenerName,
                            mCancellationElapsedTimeMs);
                    if (r.getNotification().isGroupSummary()) {
                        cancelGroupChildrenLocked(mUserId, mPkg, mCallingUid, mCallingPid,
                                listenerName, mSendDelete, childrenFlagChecker,
                                NotificationManagerService::isChildOfCurrentGroupChecker,
                                r.getGroupKey(), mReason, mCancellationElapsedTimeMs);
                    }
                    mAttentionHelper.updateLightsLocked();
                    if (mShortcutHelper != null) {
                        mShortcutHelper.maybeListenForShortcutChangesForBubbles(r,
                                true /* isRemoved */);
                    }
                } else {
                    // No notification was found => maybe it was canceled by forced grouping
                    mGroupHelper.maybeCancelGroupChildrenForCanceledSummary(mPkg, mTag,
                            mId, mUserId, mReason);

                    // No notification was found, assume that it is snoozed and cancel it.
                    if (mReason != REASON_SNOOZED) {
                        final NotificationRecord wasSnoozed = mSnoozeHelper.cancel(mUserId, mPkg,
                                mTag, mId);
                        if (wasSnoozed != null) {
                            markOffloadedBitmapsForDeletion(wasSnoozed);
                            handleSavePolicyFile();
                        }
                    }
                }
            }
        }
    }

    protected static class ShowNotificationPermissionPromptRunnable implements Runnable {
        private final String mPkgName;
        private final int mUserId;
        private final int mTaskId;
        private final PermissionPolicyInternal mPpi;

        ShowNotificationPermissionPromptRunnable(String pkg, int user, int task,
                PermissionPolicyInternal pPi) {
            mPkgName = pkg;
            mUserId = user;
            mTaskId = task;
            mPpi = pPi;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ShowNotificationPermissionPromptRunnable)) {
                return false;
            }

            ShowNotificationPermissionPromptRunnable other =
                    (ShowNotificationPermissionPromptRunnable) o;

            return Objects.equals(mPkgName, other.mPkgName) && mUserId == other.mUserId
                    && mTaskId == other.mTaskId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mPkgName, mUserId, mTaskId);
        }

        @Override
        public void run() {
            mPpi.showNotificationPromptIfNeeded(mPkgName, mUserId, mTaskId);
        }
    }

    protected class EnqueueNotificationRunnable implements Runnable {
        private final NotificationRecord r;
        private final int userId;
        private final boolean isAppForeground;
        private final boolean isAppProvided;
        private final PostNotificationTracker mTracker;

        EnqueueNotificationRunnable(int userId, NotificationRecord r, boolean foreground,
                boolean isAppProvided, PostNotificationTracker tracker) {
            this.userId = userId;
            this.r = r;
            this.isAppForeground = foreground;
            this.isAppProvided = isAppProvided;
            this.mTracker = checkNotNull(tracker);
        }

        @Override
        public void run() {
            boolean enqueued = false;
            try {
                enqueued = enqueueNotification();
            } finally {
                if (!enqueued) {
                    mTracker.cancel();
                    synchronized (mNotificationLock) {
                        markOffloadedBitmapsForDeletion(r);
                    }
                }
            }
        }

        /**
         * @return True if we successfully enqueued the notification and handed off the task of
         * posting it to a background thread; false otherwise.
         */
        private boolean enqueueNotification() {
            synchronized (mNotificationLock) {
                // allowlistToken is populated by unparceling, so it will be absent if the
                // EnqueueNotificationRunnable is created directly by NMS (as we do for group
                // summaries) instead of via notify(). Fix that.
                r.getNotification().overrideAllowlistToken(ALLOWLIST_TOKEN);

                final long snoozeAt =
                        mSnoozeHelper.getSnoozeTimeForUnpostedNotification(
                                r.getUser().getIdentifier(),
                                r.getSbn().getPackageName(), r.getSbn().getKey());
                final long currentTime = System.currentTimeMillis();
                if (snoozeAt > currentTime) {
                    (new SnoozeNotificationRunnable(r.getSbn().getKey(),
                            snoozeAt - currentTime, null)).snoozeLocked(r);
                    return false;
                }

                final String contextId =
                        mSnoozeHelper.getSnoozeContextForUnpostedNotification(
                                r.getUser().getIdentifier(),
                                r.getSbn().getPackageName(), r.getSbn().getKey());
                if (contextId != null) {
                    (new SnoozeNotificationRunnable(r.getSbn().getKey(),
                            0, contextId)).snoozeLocked(r);
                    return false;
                }

                final StatusBarNotification n = r.getSbn();
                if (DBG) Slog.d(TAG, "EnqueueNotificationRunnable.run for: " + n.getKey());
                NotificationRecord old = mNotificationsByKey.get(n.getKey());
                if (old != null) {
                    // Retain ranking information from previous record
                    r.copyRankingInformation(old);
                }

                // If we don't have a previous record, before adding this record to enqueued list,
                // see if we have a previously enqueued version of this notification so we can share
                // instance ID if necessary.
                NotificationRecord previouslyEnqueued = null;
                if (old == null) {
                    previouslyEnqueued = findNotificationByListLocked(mEnqueuedNotifications,
                            n.getKey());
                }

                mEnqueuedNotifications.add(r);
                mTtlHelper.scheduleTimeoutLocked(r, SystemClock.elapsedRealtime());

                // Either initialize instance ID for statsd logging, or carry over from old SBN.
                if (old != null && old.getSbn().getInstanceId() != null) {
                    n.setInstanceId(old.getSbn().getInstanceId());
                } else if (previouslyEnqueued != null
                        && previouslyEnqueued.getSbn().getInstanceId() != null) {
                    n.setInstanceId(previouslyEnqueued.getSbn().getInstanceId());
                } else {
                    n.setInstanceId(mNotificationInstanceIdSequence.newInstanceId());
                }

                final int callingUid = n.getUid();
                final int callingPid = n.getInitialPid();
                final Notification notification = n.getNotification();
                final String pkg = n.getPackageName();
                final int id = n.getId();
                final String tag = n.getTag();

                // We need to fix the notification up a little for bubbles
                updateNotificationBubbleFlags(r, isAppForeground);

                // Handle grouped notifications and bail out early if we
                // can to avoid extracting signals.
                handleGroupedNotificationLocked(r, old, callingUid, callingPid);

                if (enablePersonalContextService()) {
                    final PersonalContextManagerInternal pcmi =
                            getLocalService(PersonalContextManagerInternal.class);
                    if (pcmi != null) {
                        final NotificationRankingUpdate update = makeRankingUpdateLocked(null);
                        final NotificationEvent event =
                                new NotificationEnqueuedEvent(
                                        r.getSbn(), r.getChannel(), update.getRankingMap());
                        pcmi.onNotificationEvent(event);
                    }
                }

                // if this is a group child, unsnooze parent summary
                if (n.isGroup() && notification.isGroupChild()) {
                    mSnoozeHelper.repostGroupSummary(pkg, r.getUserId(), n.getGroupKey());
                }

                // This conditional is a dirty hack to limit the logging done on
                //     behalf of the download manager without affecting other apps.
                if (!pkg.equals("com.android.providers.downloads")
                        || Log.isLoggable("DownloadManager", Log.VERBOSE)) {
                    int enqueueStatus = EVENTLOG_ENQUEUE_STATUS_NEW;
                    if (old != null) {
                        enqueueStatus = EVENTLOG_ENQUEUE_STATUS_UPDATE;
                    }
                    int appProvided = isAppProvided ? 1 : 0;
                    EventLogTags.writeNotificationEnqueue(callingUid, callingPid,
                            pkg, id, tag, userId, notification.toString(),
                            enqueueStatus, appProvided);
                }

                // tell the assistant service about the notification
                if (mAssistants.isEnabled()) {
                    mAssistants.onNotificationEnqueuedLocked(r);
                    mHandler.postDelayed(
                            new PostNotificationRunnable(r.getKey(), r.getSbn().getPackageName(),
                                    r.getUid(), mTracker),
                            DELAY_FOR_ASSISTANT_TIME);
                } else {
                    mHandler.post(
                            new PostNotificationRunnable(r.getKey(), r.getSbn().getPackageName(),
                                    r.getUid(), mTracker));
                }
                return true;
            }
        }
    }

    @GuardedBy("mNotificationLock")
    boolean isPackagePausedOrSuspended(String pkg, int uid) {
        boolean isPaused;

        final PackageManagerInternal pmi = LocalServices.getService(
                PackageManagerInternal.class);
        int flags = pmi.getDistractingPackageRestrictions(
                pkg, Binder.getCallingUserHandle().getIdentifier());
        isPaused = ((flags & PackageManager.RESTRICTION_HIDE_NOTIFICATIONS) != 0);

        isPaused |= isPackageSuspendedForUser(pkg, uid);

        return isPaused;
    }

    protected class PostNotificationRunnable implements Runnable {
        private final String key;
        private final String pkg;
        private final int uid;
        private final PostNotificationTracker mTracker;

        PostNotificationRunnable(String key, String pkg, int uid, PostNotificationTracker tracker) {
            this.key = key;
            this.pkg = pkg;
            this.uid = uid;
            this.mTracker = checkNotNull(tracker);
        }

        @Override
        public void run() {
            boolean posted = false;
            try {
                posted = postNotification();
            }  catch (Exception e) {
                Slog.e(TAG, "Error posting", e);
            } finally {
                if (!posted) {
                    mTracker.cancel();
                }
            }
        }

        /**
         * @return True if we successfully processed the notification and handed off the task of
         * notifying all listeners to a background thread; false otherwise.
         */
        private boolean postNotification() {
            boolean appBanned = !areNotificationsEnabledForPackageInt(uid);
            boolean isCallNotification = isCallNotification(pkg, uid);
            boolean posted = false;
            synchronized (NotificationManagerService.this.mNotificationLock) {
                try {
                    NotificationRecord r = findNotificationByListLocked(mEnqueuedNotifications,
                            key);
                    if (r == null) {
                        Slog.i(TAG, "Cannot find enqueued record for key: " + key);
                        return false;
                    }

                    final StatusBarNotification n = r.getSbn();
                    final Notification notification = n.getNotification();
                    boolean isCallNotificationAndCorrectStyle = isCallNotification
                            && notification.isStyle(Notification.CallStyle.class);

                    if (favoritesIncomingCallLights()) {
                        int callType = notification.extras.getInt(Notification.EXTRA_CALL_TYPE, -1);
                        boolean isIncomingCall =
                                callType == Notification.CallStyle.CALL_TYPE_INCOMING;
                        r.setIsRealCallIncomingNotification(
                                isCallNotificationAndCorrectStyle && isIncomingCall);
                    }

                    if (!(notification.isMediaNotification() || isCallNotificationAndCorrectStyle)
                            && (appBanned || isRecordBlockedLocked(r))) {
                        mUsageStats.registerBlocked(r);
                        if (DBG) {
                            Slog.e(TAG, "Suppressing notification from package " + pkg);
                        }
                        return false;
                    }

                    if (isBridgedNotificationBlocked(r)) {
                        mUsageStats.registerBlocked(r);
                        if (DBG) {
                            Slog.e(TAG, "Suppressing bridged notification on behalf of package "
                                    + r.getBridgedPackageName());
                        }
                        return false;
                    }

                    // Check if this is an updated for a summary for an aggregated sparse
                    // group and remove it because that summary has been canceled
                    if (mGroupHelper.isUpdateForCanceledSummary(r)) {
                        if (DBG) {
                            Log.w(TAG,
                                    "Suppressing notification because summary was canceled: "
                                            + r);
                        }
                        String groupKey = r.getGroupKey();
                        NotificationRecord groupSummary = mSummaryByGroupKey.get(groupKey);
                        if (groupSummary != null && groupSummary.getKey().equals(r.getKey())) {
                            mSummaryByGroupKey.remove(groupKey);
                        }
                        return false;
                    }

                    final boolean isPackageSuspended =
                            isPackagePausedOrSuspended(r.getSbn().getPackageName(), r.getUid());
                    r.setHidden(isPackageSuspended);
                    if (isPackageSuspended) {
                        mUsageStats.registerSuspendedByAdmin(r);
                    }
                    NotificationRecord old = mNotificationsByKey.get(key);

                    int index = indexOfNotificationLocked(n.getKey());
                    if (index < 0) {
                        mNotificationList.add(r);
                        mUsageStats.registerPostedByApp(r);
                        mUsageStatsManagerInternal.reportNotificationPosted(r.getSbn().getOpPkg(),
                                r.getSbn().getUser(), mTracker.getStartTime());
                        final boolean isInterruptive = isVisuallyInterruptive(null, r);
                        r.setInterruptive(isInterruptive);
                        r.setTextChanged(isInterruptive);
                    } else {
                        old = mNotificationList.get(index);  // Potentially *changes* old
                        mNotificationList.set(index, r);
                        mUsageStats.registerUpdatedByApp(r, old);
                        mUsageStatsManagerInternal.reportNotificationUpdated(r.getSbn().getOpPkg(),
                                r.getSbn().getUser(), mTracker.getStartTime());
                        // Make sure we don't lose the foreground service state.
                        notification.flags |=
                                old.getNotification().flags & FLAG_FOREGROUND_SERVICE;
                        // Make sure we don't lose the computer control flag state.
                        if (android.companion.virtualdevice.flags.Flags.computerControlAccess()) {
                            notification.flags |=
                                    old.getNotification().flags & FLAG_COMPUTER_CONTROL;
                        }
                        r.isUpdate = true;
                        final boolean isInterruptive = isVisuallyInterruptive(old, r);
                        r.setTextChanged(isInterruptive);
                        if (isInterruptive) {
                            r.resetRankingTime();
                        }
                        markOffloadedBitmapsForDeletion(old);
                    }

                    mNotificationsByKey.put(n.getKey(), r);

                    // Ensure if this is a foreground service that the proper additional
                    // flags are set.
                    if (notification.isForegroundService()) {
                        notification.flags |= FLAG_NO_CLEAR;
                    }

                    // Ensure if this is a computer control notification that the proper additional
                    // flags are set.
                    if (android.companion.virtualdevice.flags.Flags.computerControlAccess()
                            && notification.isComputerControl()) {
                        notification.flags |= FLAG_NO_CLEAR | FLAG_NO_DISMISS;
                        notification.flags &= ~FLAG_AUTO_CANCEL;
                    }

                    // Posts the notification if it has a small icon, and potentially autogroup
                    // the new notification.
                    if (notification.getSmallIcon() != null && !isCritical(r)) {
                        StatusBarNotification oldSbn = (old != null) ? old.getSbn() : null;
                        if (oldSbn == null || !Objects.equals(oldSbn.getGroup(), n.getGroup())
                                || !Objects.equals(oldSbn.getNotification().getGroup(),
                                    n.getNotification().getGroup())
                                || oldSbn.getNotification().flags
                                != n.getNotification().flags
                                || !old.getChannel().getId().equals(r.getChannel().getId())
                                || old.hasAdjustment(KEY_GROUP_KEY)) {
                            synchronized (mNotificationLock) {
                                final String autogroupName
                                        = GroupHelper.getFullAggregateGroupKey(r);
                                boolean willBeAutogrouped =
                                        mGroupHelper.onNotificationPosted(r,
                                            hasAutoGroupSummaryLocked(r));
                                if (willBeAutogrouped) {
                                    // The newly posted notification will be autogrouped, but
                                    // was not autogrouped onPost, to avoid an unnecessary sort.
                                    // We add the autogroup key to the notification without a
                                    // sort here, and it'll be sorted below with extractSignals.
                                    addAutogroupKeyLocked(key,
                                            autogroupName, /*requestSort=*/false);
                                } else {
                                    // Wait 3 seconds so that the app has a chance to post
                                    // a group summary or children (complete a group)
                                    mHandler.postDelayed(() -> {
                                        synchronized (mNotificationLock) {
                                            NotificationRecord record =
                                                    mNotificationsByKey.get(key);
                                            if (record != null) {
                                                mGroupHelper.onNotificationPostedWithDelay(
                                                        record, mNotificationList,
                                                        mSummaryByGroupKey);
                                            }
                                        }
                                    }, key, DELAY_FORCE_REGROUP_TIME);
                                }
                             }
                        }
                    }

                    mRankingHelper.extractSignals(r);
                    mRankingHelper.sort(mNotificationList);
                    final int position = mRankingHelper.indexOf(mNotificationList, r);

                    int buzzBeepBlinkLoggingCode = 0;
                    if (!r.isHidden()) {
                        if (mGroupHelper.isSummaryWithAllChildrenBundled(r, mNotificationList,
                                mEnqueuedNotifications)) {
                            notification.flags |= Notification.FLAG_SILENT;
                        }

                        buzzBeepBlinkLoggingCode = mAttentionHelper.buzzBeepBlinkLocked(r,
                                new NotificationAttentionHelper.Signals(
                                        mUserProfiles.isCurrentProfile(r.getUserId()),
                                        mListenerHints));
                    }

                    if (notification.getSmallIcon() != null) {
                        NotificationRecordLogger.NotificationReported maybeReport =
                                mNotificationRecordLogger.prepareToLogNotificationPosted(r, old,
                                        position, buzzBeepBlinkLoggingCode,
                                        getGroupInstanceId(r.getSbn().getGroupKey()));
                        notifyListenersPostedAndLogLocked(r, old, mTracker, maybeReport);
                        posted = true;
                    } else {
                        Slog.e(TAG, "Not posting notification without small icon: " + notification);
                        if (old != null && !old.isCanceled) {
                            mListeners.notifyRemovedLocked(r, REASON_ERROR, r.getStats());
                            mHandler.post(() -> {
                                synchronized (mNotificationLock) {
                                    mGroupHelper.onNotificationRemoved(r, mNotificationList,
                                            /* sendingDelete= */ false);
                                }
                            });
                        }

                        if (callstyleCallbackApi()) {
                            notifyCallNotificationEventListenerOnRemoved(r);
                        }

                        // ATTENTION: in a future release we will bail out here
                        // so that we do not play sounds, show lights, etc. for invalid
                        // notifications
                        Slog.e(TAG, "WARNING: In a future release this will crash the app: "
                                + n.getPackageName());
                    }

                    if (mShortcutHelper != null) {
                        mShortcutHelper.maybeListenForShortcutChangesForBubbles(r,
                                false /* isRemoved */);
                    }

                    maybeRecordInterruptionLocked(r);
                    maybeRegisterMessageSent(r);
                    maybeReportForegroundServiceUpdate(r, true);
                } finally {
                    int N = mEnqueuedNotifications.size();
                    for (int i = 0; i < N; i++) {
                        final NotificationRecord enqueued = mEnqueuedNotifications.get(i);
                        if (Objects.equals(key, enqueued.getKey())) {
                            mEnqueuedNotifications.remove(i);
                            break;
                        }
                    }
                }
            }
            return posted;
        }
    }

    /**
     *
     */
    @GuardedBy("mNotificationLock")
    InstanceId getGroupInstanceId(String groupKey) {
        if (groupKey == null) {
            return null;
        }
        NotificationRecord group = mSummaryByGroupKey.get(groupKey);
        if (group == null) {
            return null;
        }
        return group.getSbn().getInstanceId();
    }

    /**
     * If the notification differs enough visually, consider it a new interruptive notification.
     */
    @GuardedBy("mNotificationLock")
    @VisibleForTesting
    protected boolean isVisuallyInterruptive(@Nullable NotificationRecord old,
            @NonNull NotificationRecord r) {
        // Ignore summary updates because we don't display most of the information.
        if (r.getSbn().isGroup() && r.getSbn().getNotification().isGroupSummary()) {
            if (DEBUG_INTERRUPTIVENESS) {
                Slog.v(TAG, "INTERRUPTIVENESS: "
                        +  r.getKey() + " is not interruptive: summary");
            }
            return false;
        }

        if (old == null) {
            if (DEBUG_INTERRUPTIVENESS) {
                Slog.v(TAG, "INTERRUPTIVENESS: "
                        +  r.getKey() + " is interruptive: new notification");
            }
            return true;
        }

        Notification oldN = old.getSbn().getNotification();
        Notification newN = r.getSbn().getNotification();
        if (oldN.extras == null || newN.extras == null) {
            if (DEBUG_INTERRUPTIVENESS) {
                Slog.v(TAG, "INTERRUPTIVENESS: "
                        +  r.getKey() + " is not interruptive: no extras");
            }
            return false;
        }

        // Ignore visual interruptions from FGS/UIJs because users
        // consider them one 'session'. Count them for everything else.
        if (r.getSbn().getNotification().isFgsOrUij()) {
            if (DEBUG_INTERRUPTIVENESS) {
                Slog.v(TAG, "INTERRUPTIVENESS: "
                        + r.getKey() + " is not interruptive: FGS/UIJ");
            }
            return false;
        }

        final String oldTitle = String.valueOf(oldN.extras.get(EXTRA_TITLE));
        final String newTitle = String.valueOf(newN.extras.get(EXTRA_TITLE));
        if (!Objects.equals(oldTitle, newTitle)) {
            if (DEBUG_INTERRUPTIVENESS) {
                Slog.v(TAG, "INTERRUPTIVENESS: "
                        +  r.getKey() + " is interruptive: changed title");
                Slog.v(TAG, "INTERRUPTIVENESS: " + String.format("   old title: %s (%s@0x%08x)",
                        oldTitle, oldTitle.getClass(), oldTitle.hashCode()));
                Slog.v(TAG, "INTERRUPTIVENESS: " + String.format("   new title: %s (%s@0x%08x)",
                        newTitle, newTitle.getClass(), newTitle.hashCode()));
            }
            return true;
        }

        // Do not compare Spannables (will always return false); compare unstyled Strings
        final String oldText = String.valueOf(oldN.extras.get(EXTRA_TEXT));
        final String newText = String.valueOf(newN.extras.get(EXTRA_TEXT));
        if (!Objects.equals(oldText, newText)) {
            if (DEBUG_INTERRUPTIVENESS) {
                Slog.v(TAG, "INTERRUPTIVENESS: "
                        + r.getKey() + " is interruptive: changed text");
                Slog.v(TAG, "INTERRUPTIVENESS: " + String.format("   old text: %s (%s@0x%08x)",
                        oldText, oldText.getClass(), oldText.hashCode()));
                Slog.v(TAG, "INTERRUPTIVENESS: " + String.format("   new text: %s (%s@0x%08x)",
                        newText, newText.getClass(), newText.hashCode()));
            }
            return true;
        }

        if (oldN.getProgressState() != newN.getProgressState()) {
            if (DEBUG_INTERRUPTIVENESS) {
                Slog.v(TAG, "INTERRUPTIVENESS: "
                        + r.getKey() + " is interruptive: significantly changed progress");
            }
            return true;
        }

        if (Notification.areIconsDifferent(oldN, newN)) {
            if (DEBUG_INTERRUPTIVENESS) {
                Slog.v(TAG, "INTERRUPTIVENESS: "
                        +  r.getKey() + " is interruptive: icons differ");
            }
            return true;
        }

        // Fields below are invisible to bubbles.
        if (r.canBubble()) {
            if (DEBUG_INTERRUPTIVENESS) {
                Slog.v(TAG, "INTERRUPTIVENESS: "
                        +  r.getKey() + " is not interruptive: bubble");
            }
            return false;
        }

        // Actions
        if (Notification.areActionsVisiblyDifferent(oldN, newN)) {
            if (DEBUG_INTERRUPTIVENESS) {
                Slog.v(TAG, "INTERRUPTIVENESS: "
                        +  r.getKey() + " is interruptive: changed actions");
            }
            return true;
        }

        try {
            Notification.Builder oldB = Notification.Builder.recoverBuilder(getContext(), oldN);
            Notification.Builder newB = Notification.Builder.recoverBuilder(getContext(), newN);

            // Style based comparisons
            if (Notification.areStyledNotificationsVisiblyDifferent(oldB, newB)) {
                if (DEBUG_INTERRUPTIVENESS) {
                    Slog.v(TAG, "INTERRUPTIVENESS: "
                            +  r.getKey() + " is interruptive: styles differ");
                }
                return true;
            }

            // Remote views
            if (Notification.areRemoteViewsChanged(oldB, newB)) {
                if (DEBUG_INTERRUPTIVENESS) {
                    Slog.v(TAG, "INTERRUPTIVENESS: "
                            +  r.getKey() + " is interruptive: remoteviews differ");
                }
                return true;
            }
        } catch (Exception e) {
            Slog.w(TAG, "error recovering builder", e);
        }
        return false;
    }

    /**
     * Check if the notification is classified as critical.
     *
     * @param record the record to test for criticality
     * @return {@code true} if notification is considered critical
     *
     * @see CriticalNotificationExtractor for criteria
     */
    private boolean isCritical(NotificationRecord record) {
        // 0 is the most critical
        return record.getCriticality() < CriticalNotificationExtractor.NORMAL;
    }

    /**
     *  Check if the notification was a summary that has been auto-grouped
     * @param r the current notification record
     * @param old the previous notification record
     * @return true if the notification record was a summary that was auto-grouped
     */
    @GuardedBy("mNotificationLock")
    private boolean wasSummaryAutogrouped(NotificationRecord r, NotificationRecord old) {
        boolean wasAutogrouped = false;
        if (old != null) {
            boolean wasSummary = (old.mOriginalFlags & FLAG_GROUP_SUMMARY) != 0;
            boolean wasForcedGrouped = (old.getFlags() & FLAG_GROUP_SUMMARY) == 0
                    && old.getSbn().getOverrideGroupKey() != null;
            boolean isNotAutogroupSummary = (r.getFlags() & FLAG_AUTOGROUP_SUMMARY) == 0
                    && (r.getFlags() & FLAG_GROUP_SUMMARY) != 0;
            if ((wasSummary && wasForcedGrouped) || (wasForcedGrouped && isNotAutogroupSummary)) {
                wasAutogrouped = true;
            }
        }
        return wasAutogrouped;
    }

    /**
     * Ensures that grouped notification receive their special treatment.
     *
     * <p>Cancels group children if the new notification causes a group to lose
     * its summary.</p>
     *
     * <p>Updates mSummaryByGroupKey.</p>
     */
    @GuardedBy("mNotificationLock")
    private void handleGroupedNotificationLocked(NotificationRecord r, NotificationRecord old,
            int callingUid, int callingPid) {
        StatusBarNotification sbn = r.getSbn();
        Notification n = sbn.getNotification();
        if (n.isGroupSummary() && !sbn.isAppGroup())  {
            // notifications without a group shouldn't be a summary, otherwise autobundling can
            // lead to bugs
            n.flags &= ~Notification.FLAG_GROUP_SUMMARY;
        }

        // If this is an update to a summary that was forced grouped => remove summary flag
        if (wasSummaryAutogrouped(r, old)) {
            n.flags &= ~FLAG_GROUP_SUMMARY;
        }

        String group = sbn.getGroupKey();
        boolean isSummary = n.isGroupSummary();

        Notification oldN = old != null ? old.getSbn().getNotification() : null;
        String oldGroup = old != null ? old.getSbn().getGroupKey() : null;
        boolean oldIsSummary = old != null && oldN.isGroupSummary();

        if (oldIsSummary) {
            NotificationRecord removedSummary = mSummaryByGroupKey.remove(oldGroup);
            if (removedSummary != old) {
                String removedKey =
                        removedSummary != null ? removedSummary.getKey() : "<null>";
                Slog.w(TAG, "Removed summary didn't match old notification: old=" + old.getKey() +
                        ", removed=" + removedKey);
            }
        }
        if (isSummary) {
            mSummaryByGroupKey.put(group, r);
        }

        FlagChecker childrenFlagChecker = (flags) -> ((flags & FLAG_FOREGROUND_SERVICE) == 0)
                && ((flags & FLAG_USER_INITIATED_JOB) == 0)
                && ((flags & FLAG_COMPUTER_CONTROL) == 0);

        // Clear out group children of the old notification if the update
        // causes the group summary to go away. This happens when the old
        // notification was a summary and the new one isn't, or when the old
        // notification was a summary and its group key changed.
        if (oldIsSummary && (!isSummary || !oldGroup.equals(group))) {
            cancelGroupChildrenLocked(old.getUserId(), old.getSbn().getPackageName(), callingUid,
                    callingPid, null, false /* sendDelete */, childrenFlagChecker,
                    NotificationManagerService::isChildOfCurrentGroupChecker, old.getGroupKey(),
                    REASON_APP_CANCEL, SystemClock.elapsedRealtime());
        }
    }

    private PendingIntent getNotificationTimeoutPendingIntent(NotificationRecord record,
            int flags) {
        flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(getContext(),
                REQUEST_CODE_TIMEOUT,
                new Intent(ACTION_NOTIFICATION_TIMEOUT)
                        .setPackage(PackageManagerService.PLATFORM_PACKAGE_NAME)
                        .setData(new Uri.Builder().scheme(SCHEME_TIMEOUT)
                                .appendPath(record.getKey()).build())
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        .putExtra(EXTRA_KEY, record.getKey()),
                flags);
    }

    @GuardedBy("mToastQueue")
    void showNextToastLocked(boolean lastToastWasTextRecord) {
        if (mIsCurrentToastShown) {
            return; // Don't show the same toast twice.
        }

        ToastRecord record = mToastQueue.get(0);
        while (record != null) {
            int userId = UserHandle.getUserId(record.uid);
            boolean rateLimitingEnabled =
                    !mToastRateLimitingDisabledUids.contains(record.uid);
            boolean isWithinQuota =
                    mToastRateLimiter.isWithinQuota(userId, record.pkg, TOAST_QUOTA_TAG)
                            || isExemptFromRateLimiting(record.pkg, userId);
            boolean isPackageInForeground = isPackageInForegroundForToast(record.uid);

            if (tryShowToast(
                    record, rateLimitingEnabled, isWithinQuota, isPackageInForeground)) {
                scheduleDurationReachedLocked(record, lastToastWasTextRecord);
                mIsCurrentToastShown = true;
                if (rateLimitingEnabled && !isPackageInForeground) {
                    mToastRateLimiter.noteEvent(userId, record.pkg, TOAST_QUOTA_TAG);
                }
                return;
            }

            int index = mToastQueue.indexOf(record);
            if (index >= 0) {
                ToastRecord toast = mToastQueue.remove(index);
                mWindowManagerInternal.removeWindowToken(
                        toast.windowToken, true /* removeWindows */, toast.displayId);
            }
            record = (mToastQueue.size() > 0) ? mToastQueue.get(0) : null;
        }
    }

    /** Returns true if it successfully showed the toast. */
    private boolean tryShowToast(ToastRecord record, boolean rateLimitingEnabled,
            boolean isWithinQuota, boolean isPackageInForeground) {
        if (rateLimitingEnabled && !isWithinQuota && !isPackageInForeground) {
            reportCompatRateLimitingToastsChange(record.uid);
            Slog.w(TAG, "Package " + record.pkg + " is above allowed toast quota, the "
                    + "following toast was blocked and discarded: " + record);
            return false;
        }
        if (blockToast(record.uid, record.isSystemToast, record.isAppRendered(),
                isPackageInForeground)) {
            Slog.w(TAG, "Blocking custom toast from package " + record.pkg
                    + " due to package not in the foreground at the time of showing the toast");
            return false;
        }
        return record.show();
    }

    private boolean isExemptFromRateLimiting(String pkg, int userId) {
        boolean isExemptFromRateLimiting = false;
        try {
            isExemptFromRateLimiting = mPackageManager.checkPermission(
                    android.Manifest.permission.UNLIMITED_TOASTS, pkg, userId)
                    == PERMISSION_GRANTED;
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to connect with package manager");
        }
        return isExemptFromRateLimiting;
    }

    /** Reports rate limiting toasts compat change (used when the toast was blocked). */
    private void reportCompatRateLimitingToastsChange(int uid) {
        final long id = Binder.clearCallingIdentity();
        try {
            mPlatformCompat.reportChangeByUid(RATE_LIMIT_TOASTS, uid);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unexpected exception while reporting toast was blocked due to rate"
                    + " limiting", e);
        } finally {
            Binder.restoreCallingIdentity(id);
        }
    }

    @GuardedBy("mToastQueue")
    void cancelToastLocked(int index) {
        ToastRecord record = mToastQueue.get(index);
        record.hide();

        if (index == 0) {
            mIsCurrentToastShown = false;
        }

        ToastRecord lastToast = mToastQueue.remove(index);

        // We need to schedule a timeout to make sure the token is eventually killed
        scheduleKillTokenTimeout(lastToast);

        keepProcessAliveForToastIfNeededLocked(record.pid);
        if (mToastQueue.size() > 0) {
            // Show the next one. If the callback fails, this will remove
            // it from the list, so don't assume that the list hasn't changed
            // after this point.
            showNextToastLocked(lastToast instanceof TextToastRecord);
        }
    }

    void finishWindowTokenLocked(IBinder t, int displayId) {
        mHandler.removeCallbacksAndMessages(t);
        // We pass 'true' for 'removeWindows' to let the WindowManager destroy any
        // remaining surfaces as either the client has called finishToken indicating
        // it has successfully removed the views, or the client has timed out
        // at which point anything goes.
        mWindowManagerInternal.removeWindowToken(t, true /* removeWindows */, displayId);
    }

    @GuardedBy("mToastQueue")
    private void scheduleDurationReachedLocked(ToastRecord r, boolean lastToastWasTextRecord)
    {
        mHandler.removeCallbacksAndMessages(r);
        Message m = Message.obtain(mHandler, MESSAGE_DURATION_REACHED, r);
        int delay = r.getDuration() == Toast.LENGTH_LONG ? LONG_DELAY : SHORT_DELAY;
        // Accessibility users may need longer timeout duration. This api compares original delay
        // with user's preference and return longer one. It returns original delay if there's no
        // preference.
        delay = mAccessibilityManager.getRecommendedTimeoutMillis(delay,
                AccessibilityManager.FLAG_CONTENT_TEXT);

        if (lastToastWasTextRecord) {
            delay += 250; // delay to account for previous toast's "out" animation
        }
        if (r instanceof TextToastRecord) {
            delay += 333; // delay to account for this toast's "in" animation
        }

        mHandler.sendMessageDelayed(m, delay);
    }

    private void handleDurationReached(ToastRecord record)
    {
        if (DBG) Slog.d(TAG, "Timeout pkg=" + record.pkg + " token=" + record.token);
        synchronized (mToastQueue) {
            int index = indexOfToastLocked(record.pkg, record.token);
            if (index >= 0) {
                cancelToastLocked(index);
            }
        }
    }

    @GuardedBy("mToastQueue")
    private void scheduleKillTokenTimeout(ToastRecord r)
    {
        mHandler.removeCallbacksAndMessages(r);
        Message m = Message.obtain(mHandler, MESSAGE_FINISH_TOKEN_TIMEOUT, r);
        mHandler.sendMessageDelayed(m, FINISH_TOKEN_TIMEOUT);
    }

    private void handleKillTokenTimeout(ToastRecord record)
    {
        if (DBG) Slog.d(TAG, "Kill Token Timeout token=" + record.windowToken);
        synchronized (mToastQueue) {
            finishWindowTokenLocked(record.windowToken, record.displayId);
        }
    }

    @GuardedBy("mToastQueue")
    int indexOfToastLocked(String pkg, IBinder token) {
        ArrayList<ToastRecord> list = mToastQueue;
        int len = list.size();
        for (int i=0; i<len; i++) {
            ToastRecord r = list.get(i);
            if (r.pkg.equals(pkg) && r.token == token) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Adjust process {@code pid} importance according to whether it has toasts in the queue or not.
     */
    public void keepProcessAliveForToastIfNeeded(int pid) {
        synchronized (mToastQueue) {
            keepProcessAliveForToastIfNeededLocked(pid);
        }
    }

    @GuardedBy("mToastQueue")
    private void keepProcessAliveForToastIfNeededLocked(int pid) {
        int toastCount = 0; // toasts from this pid, rendered by the app
        ArrayList<ToastRecord> list = mToastQueue;
        int n = list.size();
        for (int i = 0; i < n; i++) {
            ToastRecord r = list.get(i);
            if (r.pid == pid && r.keepProcessAlive()) {
                toastCount++;
            }
        }
        if (com.android.server.am.Flags.simplifyToastImportance()) {
            mAmi.setIsToastActive(pid, toastCount > 0);
        } else {
            try {
                mAm.setProcessImportant(mForegroundToken, pid, toastCount > 0, "toast");
            } catch (RemoteException e) {
                // Shouldn't happen.
            }
        }
    }

    /**
     * Implementation note: Our definition of foreground for toasts is an implementation matter
     * and should strike a balance between functionality and anti-abuse effectiveness. We
     * currently worry about the following cases:
     * <ol>
     *     <li>App with fullscreen activity: Allow toasts
     *     <li>App behind translucent activity from other app: Block toasts
     *     <li>App in multi-window: Allow toasts
     *     <li>App with expanded bubble: Allow toasts
     *     <li>App posting toasts on onCreate(), onStart(), onResume(): Allow toasts
     *     <li>App posting toasts on onPause(), onStop(), onDestroy(): Block toasts
     * </ol>
     * Checking if the UID has any resumed activities satisfy use-cases above.
     *
     * <p>Checking if {@code mActivityManager.getUidImportance(callingUid) ==
     * IMPORTANCE_FOREGROUND} does not work because it considers the app in foreground if it has
     * any visible activities, failing case 2 in list above.
     */
    private boolean isPackageInForegroundForToast(int callingUid) {
        return mAtm.hasResumedActivity(callingUid);
    }

    /**
     * True if the toast should be blocked. It will return true if all of the following conditions
     * apply: it's a custom toast, it's not a system toast, the package that sent the toast is in
     * the background and CHANGE_BACKGROUND_CUSTOM_TOAST_BLOCK is enabled.
     *
     * CHANGE_BACKGROUND_CUSTOM_TOAST_BLOCK is gated on targetSdk, so it will return false for apps
     * with targetSdk < R. For apps with targetSdk R+, text toasts are not app-rendered, so
     * isAppRenderedToast == true means it's a custom toast.
     */
    private boolean blockToast(int uid, boolean isSystemToast, boolean isAppRenderedToast,
            boolean isPackageInForeground) {
        return isAppRenderedToast
                && !isSystemToast
                && !isPackageInForeground
                && CompatChanges.isChangeEnabled(CHANGE_BACKGROUND_CUSTOM_TOAST_BLOCK, uid);
    }

    @VisibleForTesting
    void handleRankingReconsideration(Message message) {
        if (!(message.obj instanceof RankingReconsideration)) return;
        RankingReconsideration recon = (RankingReconsideration) message.obj;
        recon.run();
        boolean changed;
        synchronized (mNotificationLock) {
            final NotificationRecord record = mNotificationsByKey.get(recon.getKey());
            if (record == null) {
                return;
            }
            int indexBefore = findNotificationRecordIndexLocked(record);
            boolean interceptBefore = record.isIntercepted();
            int visibilityBefore = record.getPackageVisibilityOverride();
            boolean interruptiveBefore = record.isInterruptive();
            float affinityBefore = record.getContactAffinity();

            recon.applyChangesLocked(record);
            applyZenModeLocked(record);

            mRankingHelper.sort(mNotificationList);
            boolean indexChanged = indexBefore != findNotificationRecordIndexLocked(record);
            boolean interceptChanged = interceptBefore != record.isIntercepted();
            boolean visibilityChanged = visibilityBefore != record.getPackageVisibilityOverride();
            boolean affinityChanged = affinityBefore != record.getContactAffinity();

            // Broadcast isInterruptive changes for bubbles.
            boolean interruptiveChanged =
                    record.canBubble() && (interruptiveBefore != record.isInterruptive());

            changed = indexChanged || interceptChanged || visibilityChanged || interruptiveChanged;
            if (interceptBefore
                    && !record.isIntercepted()
                    && record.isNewEnoughForAlerting(System.currentTimeMillis())) {

                mAttentionHelper.buzzBeepBlinkLocked(
                        record,
                        new NotificationAttentionHelper.Signals(
                                mUserProfiles.isCurrentProfile(record.getUserId()),
                                mListenerHints));

                // Log alert after change in intercepted state to Zen Log as well
                ZenLog.traceAlertOnUpdatedIntercept(record);
            }

            if (favoritesIncomingCallLights() &&
                    record.isRealCallIncomingNotification() &&
                    affinityChanged &&
                    record.isNewEnoughForAlerting(System.currentTimeMillis())) {
                mAttentionHelper.evaluateLateCallLightLocked(
                        record,
                        new NotificationAttentionHelper.Signals(
                                mUserProfiles.isCurrentProfile(record.getUserId()),
                                mListenerHints));
            }
        }
        if (changed) {
            mHandler.scheduleSendRankingUpdate();
        }
    }

    void handleRankingSort() {
        if (mRankingHelper == null) return;
        synchronized (mNotificationLock) {
            final int N = mNotificationList.size();
            // Any field that can change via one of the extractors needs to be added here.
            ArrayMap<String, NotificationRecordExtractorData> extractorDataBefore =
                    new ArrayMap<>(N);
            for (int i = 0; i < N; i++) {
                final NotificationRecord r = mNotificationList.get(i);
                NotificationRecordExtractorData extractorData = new NotificationRecordExtractorData(
                        i,
                        r.getPackageVisibilityOverride(),
                        r.canShowBadge(),
                        r.canBubble(),
                        r.getNotification().isBubbleNotification(),
                        r.getChannel(),
                        r.getGroupKey(),
                        r.getPeopleOverride(),
                        r.getSnoozeCriteria(),
                        r.getUserSentiment(),
                        r.getSuppressedVisualEffects(),
                        r.getSystemGeneratedSmartActions(),
                        r.getSmartReplies(),
                        r.getImportance(),
                        r.getRankingScore(),
                        r.isConversation(),
                        r.getProposedImportance(),
                        r.hasSensitiveContent(),
                        r.getSummarization());
                extractorDataBefore.put(r.getKey(), extractorData);
                mRankingHelper.extractSignals(r);
            }
            mRankingHelper.sort(mNotificationList);
            for (int i = 0; i < N; i++) {
                final NotificationRecord r = mNotificationList.get(i);
                if (!extractorDataBefore.containsKey(r.getKey())) {
                    // This shouldn't happen given that we just built this with all the
                    // notifications, but check just to be safe.
                    Slog.wtf(TAG, "Missing extractor data");
                    continue;
                }
                NotificationRecordExtractorData before = extractorDataBefore.get(r.getKey());

                if (nmContextualDisplayLaunch() && before.hasBeenUnbundled(r)) {
                    mGroupHelper.onNotificationUnbundled(r, true);
                }

                if (before.hasDiffForRankingLocked(r, i)) {
                    mHandler.scheduleSendRankingUpdate();
                }

                // If this notification is one for which we wanted to log an update, and
                // sufficient relevant bits are different, log update.
                if (r.hasPendingLogUpdate()) {
                    // We need to acquire the previous data associated with this specific
                    // notification, as the one at the current index may be unrelated if
                    // notification order has changed.
                    NotificationRecordExtractorData prevData = extractorDataBefore.get(r.getKey());
                    if (prevData.hasDiffForLoggingLocked(r, i)) {
                        mNotificationRecordLogger.logNotificationAdjusted(r, i, 0,
                                getGroupInstanceId(r.getSbn().getGroupKey()));
                    }

                    // Remove whether there was a diff or not; we've sorted the key, so if it
                    // turns out there was nothing to log, that's fine too.
                    r.setPendingLogUpdate(false);
                }
            }
        }
    }

    @GuardedBy("mNotificationLock")
    private void recordCallerLocked(NotificationRecord record) {
        if (mZenModeHelper.isCall(record)) {
            mZenModeHelper.recordCaller(record);
        }
    }

    // let zen mode evaluate this record
    @GuardedBy("mNotificationLock")
    private void applyZenModeLocked(NotificationRecord record) {
        record.setIntercepted(mZenModeHelper.shouldIntercept(record));
        if (record.isIntercepted()) {
            record.setSuppressedVisualEffects(
                    mZenModeHelper.getConsolidatedNotificationPolicy().suppressedVisualEffects);
        } else {
            record.setSuppressedVisualEffects(0);
        }
    }

    @GuardedBy("mNotificationLock")
    private int findNotificationRecordIndexLocked(NotificationRecord target) {
        return mRankingHelper.indexOf(mNotificationList, target);
    }

    private void handleSendRankingUpdate() {
        synchronized (mNotificationLock) {
            mListeners.notifyRankingUpdateLocked(null);
        }
    }

    private void scheduleListenerHintsChanged(int state) {
        mHandler.removeMessages(MESSAGE_LISTENER_HINTS_CHANGED);
        mHandler.obtainMessage(MESSAGE_LISTENER_HINTS_CHANGED, state, 0).sendToTarget();
    }

    private void scheduleInterruptionFilterChanged(int listenerInterruptionFilter) {
        mHandler.removeMessages(MESSAGE_LISTENER_NOTIFICATION_FILTER_CHANGED);
        mHandler.obtainMessage(
                MESSAGE_LISTENER_NOTIFICATION_FILTER_CHANGED,
                listenerInterruptionFilter,
                0).sendToTarget();
    }

    private void handleListenerHintsChanged(int hints) {
        synchronized (mNotificationLock) {
            mListeners.notifyListenerHintsChangedLocked(hints);
        }
    }

    private void handleListenerInterruptionFilterChanged(int interruptionFilter) {
        synchronized (mNotificationLock) {
            mListeners.notifyInterruptionFilterChanged(interruptionFilter);
        }
    }

    void handleOnPackageChanged(boolean removingPackage, int changeUserId,
            String[] pkgList, int[] uidList) {
        boolean preferencesChanged = removingPackage;
        mListeners.onPackagesChanged(removingPackage, pkgList, uidList);
        mAssistants.onPackagesChanged(removingPackage, pkgList, uidList);
        mConditionProviders.onPackagesChanged(removingPackage, pkgList, uidList);
        preferencesChanged |= mPreferencesHelper.onPackagesChanged(
                removingPackage, changeUserId, pkgList, uidList);
        if (removingPackage) {
            int size = Math.min(pkgList.length, uidList.length);
            for (int i = 0; i < size; i++) {
                final String pkg = pkgList[i];
                final int uid = uidList[i];
                final int userHandle = UserHandle.getUserId(uid);
                // Removes this package's notifications from both recent notification archive
                // (recently dismissed notifications) and notification history.
                mArchive.removePackageNotifications(pkg, userHandle);
                mHistoryManager.onPackageRemoved(userHandle, pkg);
                // Remove from NLS Stats (in case the package included an NLS).
                mNotificationListenerStats.onPackageRemoved(uid, pkg);
            }
        }
        if (preferencesChanged) {
            handleSavePolicyFile();
        }
    }

    protected class WorkerHandler extends Handler
    {
        public WorkerHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg)
        {
            switch (msg.what)
            {
                case MESSAGE_DURATION_REACHED:
                    handleDurationReached((ToastRecord) msg.obj);
                    break;
                case MESSAGE_FINISH_TOKEN_TIMEOUT:
                    handleKillTokenTimeout((ToastRecord) msg.obj);
                    break;
                case MESSAGE_SEND_RANKING_UPDATE:
                    handleSendRankingUpdate();
                    break;
                case MESSAGE_LISTENER_HINTS_CHANGED:
                    handleListenerHintsChanged(msg.arg1);
                    break;
                case MESSAGE_LISTENER_NOTIFICATION_FILTER_CHANGED:
                    handleListenerInterruptionFilterChanged(msg.arg1);
                    break;
                case MESSAGE_ON_PACKAGE_CHANGED:
                    SomeArgs args = (SomeArgs) msg.obj;
                    handleOnPackageChanged((boolean) args.arg1, args.argi1, (String[]) args.arg2,
                            (int[]) args.arg3);
                    args.recycle();
                    break;
            }
        }

        protected void scheduleSendRankingUpdate() {
            if (!hasMessages(MESSAGE_SEND_RANKING_UPDATE)) {
                Message m = Message.obtain(this, MESSAGE_SEND_RANKING_UPDATE);
                sendMessage(m);
            }
        }

        protected void scheduleCancelNotification(CancelNotificationRunnable cancelRunnable,
                                                  int delay) {
            sendMessageDelayed(Message.obtain(this, cancelRunnable), delay);
        }

        protected void scheduleOnPackageChanged(boolean removingPackage, int changeUserId,
                String[] pkgList, int[] uidList) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = removingPackage;
            args.argi1 = changeUserId;
            args.arg2 = pkgList;
            args.arg3 = uidList;
            sendMessage(Message.obtain(this, MESSAGE_ON_PACKAGE_CHANGED, args));
        }
    }

    @VisibleForTesting
    final class RankingHandlerWorker extends Handler implements RankingHandler
    {
        public RankingHandlerWorker(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_RECONSIDER_RANKING:
                    handleRankingReconsideration(msg);
                    break;
                case MESSAGE_RANKING_SORT:
                    handleRankingSort();
                    break;
            }
        }

        public void requestSort() {
            removeMessages(MESSAGE_RANKING_SORT);
            Message msg = Message.obtain();
            msg.what = MESSAGE_RANKING_SORT;
            sendMessage(msg);
        }

        public void requestReconsideration(RankingReconsideration recon) {
            Message m = Message.obtain(this,
                    NotificationManagerService.MESSAGE_RECONSIDER_RANKING, recon);
            long delay = recon.getDelay(TimeUnit.MILLISECONDS);
            sendMessageDelayed(m, delay);
        }
    }

    // Notifications
    // ============================================================================
    static int clamp(int x, int low, int high) {
        return (x < low) ? low : ((x > high) ? high : x);
    }

    /**
     * Removes all NotificationsRecords with the same key as the given notification record
     * from both lists. Do not call this method while iterating over either list.
     */
    @GuardedBy("mNotificationLock")
    private boolean removeFromNotificationListsLocked(NotificationRecord r) {
        // Remove from both lists, either list could have a separate Record for what is
        // effectively the same notification.
        boolean wasPosted = false;
        NotificationRecord recordInList = null;
        if ((recordInList = findNotificationByListLocked(mNotificationList, r.getKey()))
                != null) {
            mNotificationList.remove(recordInList);
            mNotificationsByKey.remove(recordInList.getSbn().getKey());
            wasPosted = true;
        }
        while ((recordInList = findNotificationByListLocked(mEnqueuedNotifications, r.getKey()))
                != null) {
            mEnqueuedNotifications.remove(recordInList);
        }
        return wasPosted;
    }

    @GuardedBy("mNotificationLock")
    private void markOffloadedBitmapsForDeletion(NotificationRecord r) {
        if (mBitmapOffloader == null) {
            return;
        }
        synchronized (mOffloadedBitmapsPendingCleanup) {
            r.getNotification().visitUris((uri) -> {
                if (uri != null && BitmapOffloadContract.AUTHORITY.equals(uri.getAuthority())) {
                    if (mOffloadedBitmapsPendingCleanup.isEmpty()) {
                        mHandler.postDelayed(mCleanupOffloadedBitmaps,
                                OFFLOADED_BITMAP_CLEANUP_DELAY.toMillis());
                    }
                    mOffloadedBitmapsPendingCleanup.add(uri);
                }
            });
        }
    }

    @VisibleForTesting
    final Runnable mCleanupOffloadedBitmaps = new Runnable() {
        @Override
        public void run() {
            Set<Uri> candidates;
            synchronized (mOffloadedBitmapsPendingCleanup) {
                candidates = new HashSet<>(mOffloadedBitmapsPendingCleanup);
                mOffloadedBitmapsPendingCleanup.clear();
            }
            if (candidates.isEmpty() || mBitmapOffloader == null) {
                return;
            }

            synchronized (mNotificationLock) {
                Consumer<Uri> visitor = (uri) -> {
                    if (uri != null && BitmapOffloadContract.AUTHORITY.equals(uri.getAuthority())) {
                        candidates.remove(uri);
                    }
                };
                // Check active notifications
                for (NotificationRecord r : mNotificationList) {
                    r.getNotification().visitUris(visitor);
                }
                // Check enqueued notifications
                for (NotificationRecord r : mEnqueuedNotifications) {
                    r.getNotification().visitUris(visitor);
                }
                // Check snoozed notifications
                mSnoozeHelper.visitUris(visitor);
            }

            for (Uri uri : candidates) {
                mBitmapOffloader.removeBitmap(uri);
            }
        }
    };

    @GuardedBy("mNotificationLock")
    private void cancelNotificationLocked(NotificationRecord r, boolean sendDelete,
            @NotificationListenerService.NotificationCancelReason int reason,
            boolean wasPosted, String listenerName,
            @ElapsedRealtimeLong long cancellationElapsedTimeMs) {
        cancelNotificationLocked(r, sendDelete, reason, -1, -1, wasPosted, listenerName,
                cancellationElapsedTimeMs);
    }

    @GuardedBy("mNotificationLock")
    private void cancelNotificationLocked(NotificationRecord r, boolean sendDelete,
            @NotificationListenerService.NotificationCancelReason int reason,
            int rank, int count, boolean wasPosted, String listenerName,
            @ElapsedRealtimeLong long cancellationElapsedTimeMs) {
        final String canceledKey = r.getKey();
        mTtlHelper.cancelScheduledTimeoutLocked(r);

        // Record caller.
        recordCallerLocked(r);

        if (r.getStats().getDismissalSurface() == NotificationStats.DISMISSAL_NOT_DISMISSED) {
            r.recordDismissalSurface(NotificationStats.DISMISSAL_OTHER);
        }

        // tell the app
        if (sendDelete) {
            sendDeleteIntent(r.getNotification().deleteIntent, r.getSbn().getPackageName());
        }

        // Only cancel these if this notification actually got to be posted.
        if (wasPosted) {
            // status bar
            if (r.getNotification().getSmallIcon() != null) {
                if (reason != REASON_SNOOZED) {
                    r.isCanceled = true;
                }
                mListeners.notifyRemovedLocked(r, reason, r.getStats());
                mHandler.removeCallbacksAndEqualMessages(r.getKey());
                mHandler.post(() -> {
                    synchronized (NotificationManagerService.this.mNotificationLock) {
                        mGroupHelper.onNotificationRemoved(r, mNotificationList, sendDelete);
                    }
                });

                // Wait 3 seconds so that the app has a chance to cancel/post
                // a group summary or children
                final NotificationRecord groupSummary = mSummaryByGroupKey.get(r.getGroupKey());
                if (groupSummary != null
                        && !GroupHelper.isAggregatedGroup(groupSummary)
                        && !groupSummary.getKey().equals(canceledKey)) {
                    // We only care about app-provided valid group summaries
                    final String summaryKey = groupSummary.getKey();
                    mHandler.removeCallbacksAndEqualMessages(summaryKey);
                    mHandler.postDelayed(() -> {
                        synchronized (mNotificationLock) {
                            NotificationRecord summaryRecord = mNotificationsByKey.get(
                                    summaryKey);
                            if (summaryRecord != null) {
                                mGroupHelper.onGroupedNotificationRemovedWithDelay(
                                        summaryRecord, mNotificationList, mSummaryByGroupKey);
                            }
                        }
                    }, summaryKey, DELAY_FORCE_REGROUP_TIME);
                }

                if (callstyleCallbackApi()) {
                    notifyCallNotificationEventListenerOnRemoved(r);
                }
            }

            mAttentionHelper.clearEffectsLocked(canceledKey);
        }

        // Record usage stats
        // TODO: add unbundling stats?
        switch (reason) {
            case REASON_CANCEL:
            case REASON_CANCEL_ALL:
            case REASON_LISTENER_CANCEL:
            case REASON_LISTENER_CANCEL_ALL:
            case REASON_BUNDLE_DISMISSED:
                mUsageStats.registerDismissedByUser(r);
                break;
            case REASON_APP_CANCEL:
            case REASON_APP_CANCEL_ALL:
                mUsageStats.registerRemovedByApp(r);
                mUsageStatsManagerInternal.reportNotificationRemoved(r.getSbn().getOpPkg(),
                        r.getUser(), cancellationElapsedTimeMs);
                break;
        }

        String groupKey = r.getGroupKey();
        NotificationRecord groupSummary = mSummaryByGroupKey.get(groupKey);
        if (groupSummary != null && groupSummary.getKey().equals(canceledKey)) {
            mSummaryByGroupKey.remove(groupKey);
        }
        final ArrayMap<String, String> summaries =
                mAutobundledSummaries.get(r.getSbn().getUserId());
        final String autbundledGroupKey = groupKey;
        if (summaries != null && r.getSbn().getKey().equals(
                summaries.get(autbundledGroupKey))) {
            summaries.remove(autbundledGroupKey);
        }

        // Save it for users of getHistoricalNotifications(), unless the whole channel was deleted
        if (reason != REASON_CHANNEL_REMOVED) {
            mArchive.record(getSbnForArchive(r, reason), reason);
        }

        if (reason != REASON_SNOOZED) {
            markOffloadedBitmapsForDeletion(r);
        }

        final long now = System.currentTimeMillis();
        final LogMaker logMaker = r.getItemLogMaker()
                .setType(MetricsEvent.TYPE_DISMISS)
                .setSubtype(reason);
        if (rank != -1 && count != -1) {
            logMaker.addTaggedData(MetricsEvent.NOTIFICATION_SHADE_INDEX, rank)
                    .addTaggedData(MetricsEvent.NOTIFICATION_SHADE_COUNT, count);
        }
        MetricsLogger.action(logMaker);
        EventLogTags.writeNotificationCanceled(canceledKey, reason,
                r.getLifespanMs(now), r.getFreshnessMs(now), r.getExposureMs(now),
                rank, count, listenerName);
        if (wasPosted) {
            mNotificationRecordLogger.logNotificationCancelled(r, reason,
                    r.getStats().getDismissalSurface());
        }
    }

    @GuardedBy("mNotificationLock")
    private StatusBarNotification getSbnForArchive(@NonNull NotificationRecord r, int reason) {
        final StatusBarNotification sbn = r.getSbn();
        if (reason == REASON_GROUP_OPTIMIZATION && mGroupHelper.wasSummaryBeforeAutoGrouping(r)) {
            StatusBarNotification sbnClone = sbn.cloneLight();
            sbnClone.getNotification().flags |= FLAG_GROUP_SUMMARY;
            return sbnClone;
        }
        return sbn;
    }

    private static void sendDeleteIntent(@Nullable PendingIntent deleteIntent, String fromPkg) {
        if (deleteIntent != null) {
            try {
                // make sure deleteIntent cannot be used to start activities from background
                LocalServices.getService(ActivityManagerInternal.class)
                        .clearPendingIntentAllowBgActivityStarts(deleteIntent.getTarget(),
                                ALLOWLIST_TOKEN);
                deleteIntent.send();
            } catch (PendingIntent.CanceledException ex) {
                // There's no relevant way to recover, and no reason to let this propagate
                Slog.w(TAG, "canceled PendingIntent for " + fromPkg, ex);
            }
        }
    }

    @VisibleForTesting
    void updateUriPermissions(@Nullable NotificationRecord newRecord,
            @Nullable NotificationRecord oldRecord, String targetPkg, int targetUserId) {
        updateUriPermissions(newRecord, oldRecord, targetPkg, targetUserId, false);
    }

    @VisibleForTesting
    void updateUriPermissions(@Nullable NotificationRecord newRecord,
            @Nullable NotificationRecord oldRecord, String targetPkg, int targetUserId,
            boolean onlyRevokeCurrentTarget) {
        final String key = (newRecord != null) ? newRecord.getKey() : oldRecord.getKey();
        if (DBG) Slog.d(TAG, key + ": updating permissions");

        final ArraySet<Uri> newUris = (newRecord != null) ? newRecord.getGrantableUris() : null;
        final ArraySet<Uri> oldUris = (oldRecord != null) ? oldRecord.getGrantableUris() : null;

        // Shortcut when no Uris involved
        if (newUris == null && oldUris == null) {
            return;
        }

        // Inherit any existing owner
        IBinder permissionOwner = null;
        if (newRecord != null && permissionOwner == null) {
            permissionOwner = newRecord.permissionOwner;
        }
        if (oldRecord != null && permissionOwner == null) {
            permissionOwner = oldRecord.permissionOwner;
        }

        // If we have Uris to grant, but no owner yet, go create one
        if (newUris != null && permissionOwner == null) {
            if (DBG) Slog.d(TAG, key + ": creating owner");
            permissionOwner = mUgmInternal.newUriPermissionOwner("NOTIF:" + key);
        }

        // If we have no Uris to grant, but an existing owner, go destroy it
        // When revoking permissions of a single listener, destroying the owner will revoke
        // permissions of other listeners who need to keep access.
        if (newUris == null && permissionOwner != null && !onlyRevokeCurrentTarget) {
            destroyPermissionOwner(permissionOwner, UserHandle.getUserId(oldRecord.getUid()), key);
            permissionOwner = null;
        }

        // Grant access to new Uris
        if (newUris != null && permissionOwner != null) {
            for (int i = 0; i < newUris.size(); i++) {
                final Uri uri = newUris.valueAt(i);
                if (oldUris == null || !oldUris.contains(uri)) {
                    if (DBG) {
                        Slog.d(TAG, key + ": granting " + uri);
                    }
                    grantUriPermission(permissionOwner, uri, newRecord.getUid(), targetPkg,
                            targetUserId);
                }
            }
        }

        // Revoke access to old Uris
        if (oldUris != null && permissionOwner != null) {
            for (int i = 0; i < oldUris.size(); i++) {
                final Uri uri = oldUris.valueAt(i);
                if (newUris == null || !newUris.contains(uri)) {
                    if (DBG) Slog.d(TAG, key + ": revoking " + uri);
                    if (onlyRevokeCurrentTarget) {
                        // We're revoking permission from one listener only; other listeners may
                        // still need access because the notification may still exist
                        revokeUriPermission(permissionOwner, uri,
                                UserHandle.getUserId(oldRecord.getUid()), targetPkg, targetUserId);
                    } else {
                        // This is broad to unilaterally revoke permissions to this Uri as granted
                        // by this notification.  But this code-path can only be used when the
                        // reason for revoking is that the notification posted again without this
                        // Uri, not when removing an individual listener.
                        revokeUriPermission(permissionOwner, uri,
                                UserHandle.getUserId(oldRecord.getUid()),
                                null, USER_ALL);
                    }
                }
            }
        }

        if (newRecord != null) {
            newRecord.permissionOwner = permissionOwner;
        }
    }

    private void grantUriPermission(IBinder owner, Uri uri, int sourceUid, String targetPkg,
            int targetUserId) {
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) return;
        final long ident = Binder.clearCallingIdentity();
        try {
            mUgm.grantUriPermissionFromOwner(owner, sourceUid, targetPkg,
                    ContentProvider.getUriWithoutUserId(uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    ContentProvider.getUserIdFromUri(uri, UserHandle.getUserId(sourceUid)),
                    targetUserId);
        } catch (RemoteException ignored) {
            // Ignored because we're in same process
        } catch (SecurityException e) {
            Slog.e(TAG, "Cannot grant uri access; " + sourceUid + " does not own " + uri);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void revokeUriPermission(IBinder owner, Uri uri, int sourceUserId, String targetPkg,
            int targetUserId) {
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) return;
        int userId = ContentProvider.getUserIdFromUri(uri, sourceUserId);

        final long ident = Binder.clearCallingIdentity();
        try {
            mUgmInternal.revokeUriPermissionFromOwner(
                    owner,
                    ContentProvider.getUriWithoutUserId(uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    userId, targetPkg, targetUserId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void destroyPermissionOwner(IBinder owner, int userId, String logKey) {
        final long ident = Binder.clearCallingIdentity();
        try {
            if (DBG) Slog.d(TAG, logKey + ": destroying owner");
            mUgmInternal.revokeUriPermissionFromOwner(owner, null, ~0, userId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Cancels a notification ONLY if it matches all the given constraints.
     */
    void cancelNotification(final int callingUid, final int callingPid,
            final String pkg, final String tag, int id,
            final int mustHaveFlags, final FlagChecker flagChecker,
            final boolean sendDelete,
            final int userId, final int reason, final ManagedServiceInfo listener) {
        cancelNotification(callingUid, callingPid, pkg, tag, id,
                mustHaveFlags, flagChecker,
                sendDelete, userId, reason, -1 /* rank */, -1 /* count */, listener);
    }

    /**
     * Cancels a notification ONLY if it matches all the given constraints.
     */
    void cancelNotification(final int callingUid, final int callingPid,
            final String pkg, final String tag, final int id,
            final int mustHaveFlags, final FlagChecker flagChecker,
            final boolean sendDelete, final int userId, final int reason, int rank, int count,
            final ManagedServiceInfo listener) {
        // In enqueueNotificationInternal notifications are added by scheduling the
        // work on the worker handler. Hence, we also schedule the cancel on this
        // handler to avoid a scenario where an add notification call followed by a
        // remove notification call ends up in not removing the notification.
        mHandler.scheduleCancelNotification(new CancelNotificationRunnable(callingUid, callingPid,
                pkg, tag, id, mustHaveFlags, flagChecker,
                sendDelete, userId, reason, rank,
                count, listener, SystemClock.elapsedRealtime()), 0);
    }

    /**
     * Determine whether the userId applies to the notification in question, either because
     * they match exactly, or one of them is USER_ALL (which is treated as a wildcard).
     */
    private static boolean notificationMatchesUserId(NotificationRecord r, int userId,
            boolean isAutogroupSummary) {
        if (isAutogroupSummary) {
            return r.getUserId() == userId;
        } else {
            return
                // looking for USER_ALL notifications? match everything
                userId == USER_ALL
                        // a notification sent to USER_ALL matches any query
                        || r.getUserId() == USER_ALL
                        // an exact user match
                        || r.getUserId() == userId;
        }
    }

    /**
     * Determine whether the userId applies to the notification in question, either because
     * they match exactly, or one of them is USER_ALL (which is treated as a wildcard) or
     * because it matches one of the users profiles.
     */
    private boolean notificationMatchesCurrentProfiles(NotificationRecord r, int userId) {
        return notificationMatchesUserId(r, userId, false)
                || mUserProfiles.isCurrentProfile(r.getUserId());
    }

    /**
     * Cancels all notifications from a given package (or, optionally, in a specific channel from
     * said package) that have all of the {@code mustHaveFlags} and none of the
     * {@code mustNotHaveFlags}.
     */
    void cancelAllNotificationsInt(int callingUid, int callingPid, String pkg,
            @Nullable String channelId, int mustHaveFlags, int mustNotHaveFlags, int userId,
            int reason) {
        final long cancellationElapsedTimeMs = SystemClock.elapsedRealtime();
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                EventLogTags.writeNotificationCancelAll(callingUid, callingPid,
                        pkg, userId, mustHaveFlags, mustNotHaveFlags, reason,
                        /* listener= */ null);

                synchronized (mNotificationLock) {
                    FlagChecker flagChecker =
                            FlagChecker.mustHaveAndMustNotHave(mustHaveFlags, mustNotHaveFlags);
                    cancelAllNotificationsByListLocked(mNotificationList, pkg,
                            true /*nullPkgIndicatesUserSwitch*/, channelId, flagChecker,
                            false /*includeCurrentProfiles*/, userId, false /*sendDelete*/, reason,
                            null /* listenerName */, true /* wasPosted */,
                            cancellationElapsedTimeMs);
                    cancelAllNotificationsByListLocked(mEnqueuedNotifications, pkg,
                            true /*nullPkgIndicatesUserSwitch*/, channelId, flagChecker,
                            false /*includeCurrentProfiles*/, userId, false /*sendDelete*/, reason,
                            null /* listenerName */, false /* wasPosted */,
                            cancellationElapsedTimeMs);
                    List<NotificationRecord> snoozed = mSnoozeHelper.cancel(userId, pkg);
                    for (NotificationRecord r : snoozed) {
                        markOffloadedBitmapsForDeletion(r);
                    }
                }
            }
        });
    }

    @GuardedBy("mNotificationLock")
    void requestSystemAdjustmentsLocked(@NonNull List<Adjustment> adjustments) {
        if (adjustments.isEmpty()) {
            return;
        }

        // Group the adjustments by notification record.
        final ArrayMap<NotificationRecord, List<Adjustment>> adjustmentsByRecord = new ArrayMap<>();
        for (Adjustment adjustment : adjustments) {
            // Search both enqueued and posted notifications, as the adjustment could have come in
            // before the notification was posted.
            final NotificationRecord r = findNotificationByKeyLocked(adjustment.getKey());
            if (r == null) {
                Slog.w(
                        TAG,
                        "Cannot find notification to request system adjustment: "
                                + adjustment.getKey());
                continue;
            }
            adjustmentsByRecord.computeIfAbsent(r, k -> new ArrayList<>()).add(adjustment);
        }

        // Notify the assistant for each notification record, to ensure the assistant is allowed
        // to adjust the notification.
        for (Map.Entry<NotificationRecord, List<Adjustment>> entry :
                adjustmentsByRecord.entrySet()) {
            final NotificationRecord r = entry.getKey();
            final List<Adjustment> recordAdjustments = entry.getValue();
            mAssistants.notifyAssistantOfSystemAdjustments(r, recordAdjustments);
        }
    }

    private interface FlagChecker {
        // Returns false if these flags do not pass the defined flag test.
        public boolean apply(int flags);

        /** construct a flag checker with mustNotHaveFlags */
        public static FlagChecker mustNotHave(int mustNotHaveFlags) {
            return mustHaveAndMustNotHave(0, mustNotHaveFlags);
        }

        /** construct a flag checker with mustHaveFlags and mustNotHaveFlags */
        public static FlagChecker mustHaveAndMustNotHave(int mustHaveFlags, int mustNotHaveFlags) {
            return (int flags) -> {
                if ((flags & mustHaveFlags) != mustHaveFlags) {
                    return false;
                }
                if ((flags & mustNotHaveFlags) != 0) {
                    return false;
                }
                return true;
            };
        }
    }

    @FunctionalInterface
    private interface GroupChildChecker {
        // Returns true if the childRecord is a child of the group defined
        // by the rest of the parameters
        boolean apply(NotificationRecord childRecord, int userId, String pkg, String groupKey);
    }

    /**
     * Checks that the notification is currently a child of the group
     * @param childRecord the notification to check
     * @param userId userId of the group
     * @param pkg package name of the group
     * @param groupKey group key for a current group
     * @return true if the childRecord is currently a child of the group
     */
    private static boolean isChildOfCurrentGroupChecker(NotificationRecord childRecord, int userId,
            String pkg, String groupKey) {
        return (childRecord.getUser().getIdentifier() == userId
            && childRecord.getSbn().getPackageName().equals(pkg)
            && childRecord.getSbn().isGroup()
            && !childRecord.getNotification().isGroupSummary()
            && TextUtils.equals(groupKey, childRecord.getGroupKey()));
    }

    /**
     * Checks that the notification was originally a child of the group
     * @param childRecord the notification to check
     * @param userId userId of the group
     * @param pkg package name of the group
     * @param groupKey original/initial group key for a group that was force grouped
     * @return true if the childRecord was originally a child of the group
     */
    private static boolean wasChildOfForceRegroupedGroupChecker(NotificationRecord childRecord,
            int userId, String pkg, String groupKey) {
        return (childRecord.getUser().getIdentifier() == userId
            && childRecord.getSbn().getPackageName().equals(pkg)
            && childRecord.getSbn().isGroup()
            && !childRecord.getNotification().isGroupSummary()
            && TextUtils.equals(groupKey, childRecord.getOriginalGroupKey()));
    }

    @GuardedBy("mNotificationLock")
    private void cancelAllNotificationsByListLocked(ArrayList<NotificationRecord> notificationList,
            @Nullable String pkg, boolean nullPkgIndicatesUserSwitch, @Nullable String channelId,
            FlagChecker flagChecker, boolean includeCurrentProfiles, int userId, boolean sendDelete,
            int reason, String listenerName, boolean wasPosted,
            @ElapsedRealtimeLong long cancellationElapsedTimeMs) {
        Set<String> childNotifications = null;
        for (int i = notificationList.size() - 1; i >= 0; --i) {
            NotificationRecord r = notificationList.get(i);
            if (includeCurrentProfiles) {
                if (!notificationMatchesCurrentProfiles(r, userId)) {
                    continue;
                }
            } else if (!notificationMatchesUserId(r, userId, false)) {
                continue;
            }
            // Don't remove notifications to all, if there's no package name specified
            if (nullPkgIndicatesUserSwitch && pkg == null && r.getUserId() == USER_ALL) {
                continue;
            }
            if (!flagChecker.apply(r.getFlags())) {
                continue;
            }

            if (pkg != null && !r.getSbn().getPackageName().equals(pkg)
                    && !TextUtils.equals(r.getBridgedPackageName(), pkg)) {
                continue;
            }
            if (channelId != null // Compare against possibly bundled channel AND original channel
                    && !channelId.equals(r.getChannel().getId())
                    && !channelId.equals(r.getNotification().getChannelId())
                    && !TextUtils.equals(r.getBridgedChannelId(), channelId)) {
                continue;
            }
            if (r.getSbn().isGroup() && r.getNotification().isGroupChild()) {
                if (childNotifications == null) {
                    childNotifications = new HashSet<>();
                }
                childNotifications.add(r.getKey());
                continue;
            }
            notificationList.remove(i);
            mNotificationsByKey.remove(r.getKey());
            r.recordDismissalSentiment(NotificationStats.DISMISS_SENTIMENT_NEUTRAL);
            cancelNotificationLocked(r, sendDelete, reason, wasPosted, listenerName,
                    cancellationElapsedTimeMs);
        }
        if (childNotifications != null) {
            final int M = notificationList.size();
            for (int i = M - 1; i >= 0; i--) {
                NotificationRecord r = notificationList.get(i);
                if (childNotifications.contains(r.getKey())) {
                    // dismiss conditions were checked in the first loop and so don't need to be
                    // checked again
                    notificationList.remove(i);
                    mNotificationsByKey.remove(r.getKey());
                    r.recordDismissalSentiment(NotificationStats.DISMISS_SENTIMENT_NEUTRAL);
                    cancelNotificationLocked(r, sendDelete, reason, wasPosted, listenerName,
                            cancellationElapsedTimeMs);
                }
            }
            mAttentionHelper.updateLightsLocked();
        }
    }

    void snoozeNotificationInt(int callingUid, INotificationListener token, String key,
            long duration, String snoozeCriterionId) {
        final String packageName;
        final long notificationUpdateTimeMs;

        synchronized (mNotificationLock) {
            final ManagedServiceInfo listener = mListeners.checkServiceTokenLocked(token);
            if (listener == null) {
                return;
            }
            packageName = listener.component.getPackageName();
            String listenerName = listener.component.toShortString();
            if ((duration <= 0 && snoozeCriterionId == null) || key == null) {
                return;
            }

            final NotificationRecord r = findInCurrentAndSnoozedNotificationByKeyLocked(key);
            if (r == null) {
                return;
            }
            if (!listener.enabledAndUserMatches(r.getSbn().getNormalizedUserId())){
                return;
            }
            notificationUpdateTimeMs = r.getUpdateTimeMs();

            if (DBG) {
                Slog.d(TAG, String.format("snooze event(%s, %d, %s, %s)", key, duration,
                        snoozeCriterionId, listenerName));
            }
            // Needs to post so that it can cancel notifications not yet enqueued.
            mHandler.post(new SnoozeNotificationRunnable(key, duration, snoozeCriterionId));
        }

        if (isNotificationRecent(notificationUpdateTimeMs)) {
            mAppOps.noteOpNoThrow(AppOpsManager.OP_RAPID_CLEAR_NOTIFICATIONS_BY_LISTENER,
                    callingUid, packageName, /* attributionTag= */ null, /* message= */ null);
        }
    }

    void unsnoozeNotificationInt(String key, ManagedServiceInfo listener, boolean muteOnReturn) {
        String listenerName = listener == null ? null : listener.component.toShortString();
        if (DBG) {
            Slog.d(TAG, String.format("unsnooze event(%s, %s)", key, listenerName));
        }
        mSnoozeHelper.repost(key, muteOnReturn);
        handleSavePolicyFile();
    }

    private boolean isNotificationRecent(long notificationUpdateTimeMs) {
        if (!rapidClearNotificationsByListenerAppOpEnabled()) {
            return false;
        }
        return System.currentTimeMillis() - notificationUpdateTimeMs
                < NOTIFICATION_RAPID_CLEAR_THRESHOLD_MS;
    }

    @GuardedBy("mNotificationLock")
    void cancelAllLocked(int callingUid, int callingPid, int userId, int reason,
            ManagedServiceInfo listener, boolean includeCurrentProfiles, int mustNotHaveFlags) {
        final long cancellationElapsedTimeMs = SystemClock.elapsedRealtime();
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (mNotificationLock) {
                    String listenerName =
                            listener == null ? null : listener.component.toShortString();
                    EventLogTags.writeNotificationCancelAll(callingUid, callingPid,
                            null, userId, 0, 0, reason, listenerName);

                    FlagChecker flagChecker = (int flags) -> {
                        int flagsToCheck = mustNotHaveFlags;
                        if (REASON_LISTENER_CANCEL_ALL == reason
                                || REASON_CANCEL_ALL == reason) {
                            flagsToCheck |= FLAG_BUBBLE;
                        }
                        if ((flags & flagsToCheck) != 0) {
                            return false;
                        }
                        return true;
                    };

                    cancelAllNotificationsByListLocked(mNotificationList,
                            null, false /*nullPkgIndicatesUserSwitch*/, null, flagChecker,
                            includeCurrentProfiles, userId, true /*sendDelete*/, reason,
                            listenerName, true, cancellationElapsedTimeMs);
                    cancelAllNotificationsByListLocked(mEnqueuedNotifications,
                            null, false /*nullPkgIndicatesUserSwitch*/, null,
                            flagChecker, includeCurrentProfiles, userId, true /*sendDelete*/,
                            reason, listenerName, false, cancellationElapsedTimeMs);
                    List<NotificationRecord> snoozed = mSnoozeHelper.cancel(userId,
                            includeCurrentProfiles);
                    for (NotificationRecord r : snoozed) {
                        markOffloadedBitmapsForDeletion(r);
                    }
                }
            }
        });
    }

    // Warning: The caller is responsible for invoking updateLightsLocked().
    @GuardedBy("mNotificationLock")
    private void cancelGroupChildrenLocked(int userId, String pkg, int callingUid, int callingPid,
            String listenerName, boolean sendDelete, FlagChecker flagChecker,
            GroupChildChecker groupChildChecker, String groupKey, int reason,
            @ElapsedRealtimeLong long cancellationElapsedTimeMs) {
        if (pkg == null) {
            if (DBG) Slog.e(TAG, "No package for group summary");
            return;
        }

        cancelGroupChildrenByListLocked(mNotificationList, userId, pkg, callingUid, callingPid,
                listenerName, sendDelete, true, flagChecker, groupChildChecker, groupKey,
                reason, cancellationElapsedTimeMs);
        cancelGroupChildrenByListLocked(mEnqueuedNotifications, userId, pkg, callingUid, callingPid,
                listenerName, sendDelete, false, flagChecker, groupChildChecker, groupKey,
                reason, cancellationElapsedTimeMs);
    }

    @GuardedBy("mNotificationLock")
    private void cancelGroupChildrenByListLocked(ArrayList<NotificationRecord> notificationList,
            int userId, String pkg, int callingUid, int callingPid,
            String listenerName, boolean sendDelete, boolean wasPosted, FlagChecker flagChecker,
            GroupChildChecker grouChildChecker, String groupKey, int reason,
            @ElapsedRealtimeLong long cancellationElapsedTimeMs) {
        final int childReason = REASON_GROUP_SUMMARY_CANCELED;
        for (int i = notificationList.size() - 1; i >= 0; i--) {
            final NotificationRecord childR = notificationList.get(i);
            final StatusBarNotification childSbn = childR.getSbn();
            if (grouChildChecker.apply(childR, userId, pkg, groupKey)
                && (flagChecker == null || flagChecker.apply(childR.getFlags()))
                && (!isPromotedOutOfGroup(childR) || reason != REASON_CANCEL)) {
                EventLogTags.writeNotificationCancel(callingUid, callingPid, pkg, childSbn.getId(),
                        childSbn.getTag(), userId, 0, 0, childReason, listenerName);
                notificationList.remove(i);
                mNotificationsByKey.remove(childR.getKey());
                cancelNotificationLocked(childR, sendDelete, childReason, wasPosted, listenerName,
                        cancellationElapsedTimeMs);
            }
        }
    }

    /**
     * Certain notifications have attributes that causes SystemUI to *always* promote them out of
     * their group, i.e. make them a top-level notification in the shade. These notifications should
     * not be cancelled when the group is.
     */
    private boolean isPromotedOutOfGroup(NotificationRecord r) {
        return r.getChannel().isImportantConversation() || r.getNotification().isPromotedOngoing();
    }

    /**
     * Updates all notifications potentially affected by a rule when that rule is deleted.
     */
    private void onNotificationRuleRemoved(@UserIdInt int userId, int ruleId) {
        synchronized (mNotificationLock) {
            List<NotificationRecord> affectedRecords = findNotificationsLocked(
                    notificationRecord -> {
                        if (notificationRecord.getUserId() == userId) {
                            Adjustment a = notificationRecord.getMatchingRulesAdjustment();
                            return a != null && a.getSignals().containsKey(KEY_NOTIFICATION_RULES)
                                    && a.getSignals().getIntegerArrayList(
                                    KEY_NOTIFICATION_RULES).contains(ruleId);
                        }
                        return false;
                    });
            if (!affectedRecords.isEmpty()) {
                for (NotificationRecord record : affectedRecords) {
                    record.getMatchingRulesAdjustment().getSignals().getIntegerArrayList(
                            KEY_NOTIFICATION_RULES).remove(new Integer(ruleId));
                }
                mRankingHandler.requestSort();
            }
        }
    }

    @GuardedBy("mNotificationLock")
    @NonNull
    List<NotificationRecord> findNotificationsLocked(Predicate<NotificationRecord> filter) {
        List<NotificationRecord> records = new ArrayList<>();
        for (NotificationRecord record : mEnqueuedNotifications) {
            if (filter.test(record)) {
                records.add(record);
            }
        }
        for (NotificationRecord record : mNotificationList) {
            if (filter.test(record)) {
                records.add(record);
            }
        }

        return records;
    }

    @GuardedBy("mNotificationLock")
    @NonNull
    List<NotificationRecord> findCurrentAndSnoozedGroupNotificationsLocked(String pkg,
            String groupKey, int userId) {
        List<NotificationRecord> records = mSnoozeHelper.getNotifications(pkg, groupKey, userId);
        records.addAll(findGroupNotificationsLocked(pkg, groupKey, userId));
        return records;
    }

    @GuardedBy("mNotificationLock")
    @NonNull List<NotificationRecord> findGroupNotificationsLocked(String pkg,
            String groupKey, int userId) {
        List<NotificationRecord> records = new ArrayList<>();
        records.addAll(findGroupNotificationByListLocked(mNotificationList, pkg, groupKey, userId));
        records.addAll(
                findGroupNotificationByListLocked(mEnqueuedNotifications, pkg, groupKey, userId));
        return records;
    }

    @GuardedBy("mNotificationLock")
    private NotificationRecord findInCurrentAndSnoozedNotificationByKeyLocked(String key) {
        NotificationRecord r = findNotificationByKeyLocked(key);
        if (r == null) {
            r = mSnoozeHelper.getNotification(key);
        }
        return r;

    }

    @GuardedBy("mNotificationLock")
    private @NonNull List<NotificationRecord> findAppNotificationByListLocked(
            ArrayList<NotificationRecord> list, String pkg, int userId) {
        List<NotificationRecord> records = new ArrayList<>();
        final int len = list.size();
        for (int i = 0; i < len; i++) {
            NotificationRecord r = list.get(i);
            if (notificationMatchesUserId(r, userId, false)
                    && r.getSbn().getPackageName().equals(pkg)) {
                records.add(r);
            }
        }
        return records;
    }

    @GuardedBy("mNotificationLock")
    private @NonNull List<NotificationRecord> findGroupNotificationByListLocked(
            ArrayList<NotificationRecord> list, String pkg, String groupKey, int userId) {
        List<NotificationRecord> records = new ArrayList<>();
        final int len = list.size();
        for (int i = 0; i < len; i++) {
            NotificationRecord r = list.get(i);
            if (notificationMatchesUserId(r, userId, false) && r.getGroupKey().equals(groupKey)
                    && r.getSbn().getPackageName().equals(pkg)) {
                records.add(r);
            }
        }
        return records;
    }

    /**
     * Returns the "previous" version of a NotificationRecord, given its key. Searches both posted
     * notifications and enqueued updates, prioritizing the latter (and among updates to the same
     * notification, prioritizing the last to be enqueued).
     */
    @Nullable
    @GuardedBy("mNotificationLock")
    private NotificationRecord findPreviousNotificationLocked(String key) {
        for (NotificationRecord enqueued : mEnqueuedNotifications.reversed()) {
            if (enqueued.getKey().equals(key)) {
                return enqueued;
            }
        }
        return mNotificationsByKey.get(key); // or null if not present
    }

    // Searches both enqueued and posted notifications by key.
    // TODO: need to combine a bunch of these getters with slightly different behavior.
    // TODO: Should enqueuing just add to mNotificationsByKey instead?
    @GuardedBy("mNotificationLock")
    private NotificationRecord findNotificationByKeyLocked(String key) {
        NotificationRecord r;
        if ((r = findNotificationByListLocked(mNotificationList, key)) != null) {
            return r;
        }
        if ((r = findNotificationByListLocked(mEnqueuedNotifications, key)) != null) {
            return r;
        }
        return null;
    }

    @GuardedBy("mNotificationLock")
    NotificationRecord findNotificationLocked(String pkg, String tag, int id, int userId) {
        NotificationRecord r;
        if ((r = findNotificationByListLocked(mNotificationList, pkg, tag, id, userId)) != null) {
            return r;
        }
        if ((r = findNotificationByListLocked(mEnqueuedNotifications, pkg, tag, id, userId))
                != null) {
            return r;
        }

        return null;
    }

    @Nullable
    private static NotificationRecord findNotificationByListLocked(
            ArrayList<NotificationRecord> list, String pkg, String tag, int id, int userId) {
        final int len = list.size();
        for (int i = 0; i < len; i++) {
            NotificationRecord r = list.get(i);
            if (notificationMatchesUserId(r, userId, (r.getFlags() & GroupHelper.BASE_FLAGS) != 0)
                    && r.getSbn().getId() == id && TextUtils.equals(r.getSbn().getTag(), tag)
                    && r.getSbn().getPackageName().equals(pkg)) {
                return r;
            }
        }
        return null;
    }

    private static List<NotificationRecord> findNotificationsByListLocked(
            ArrayList<NotificationRecord> list, String pkg, String tag, int id, int userId) {
        List<NotificationRecord> matching = new ArrayList<>();
        final int len = list.size();
        for (int i = 0; i < len; i++) {
            NotificationRecord r = list.get(i);
            if (notificationMatchesUserId(r, userId, false) && r.getSbn().getId() == id
                    && TextUtils.equals(r.getSbn().getTag(), tag)
                    && r.getSbn().getPackageName().equals(pkg)) {
                matching.add(r);
            }
        }
        return matching;
    }

    @Nullable
    private static NotificationRecord findNotificationByListLocked(
            ArrayList<NotificationRecord> list, String key) {
        final int N = list.size();
        for (int i = 0; i < N; i++) {
            if (key.equals(list.get(i).getKey())) {
                return list.get(i);
            }
        }
        return null;
    }

    @GuardedBy("mNotificationLock")
    int indexOfNotificationLocked(String key) {
        final int N = mNotificationList.size();
        for (int i = 0; i < N; i++) {
            if (key.equals(mNotificationList.get(i).getKey())) {
                return i;
            }
        }
        return -1;
    }

    private void hideNotificationsForPackages(@NonNull String[] pkgs, @NonNull int[] uidList) {
        synchronized (mNotificationLock) {
            Set<Integer> uidSet = Arrays.stream(uidList).boxed().collect(Collectors.toSet());
            List<String> pkgList = Arrays.asList(pkgs);
            List<NotificationRecord> changedNotifications = new ArrayList<>();
            int numNotifications = mNotificationList.size();
            for (int i = 0; i < numNotifications; i++) {
                NotificationRecord rec = mNotificationList.get(i);
                if (pkgList.contains(rec.getSbn().getPackageName())
                        && uidSet.contains(rec.getUid())) {
                    rec.setHidden(true);
                    changedNotifications.add(rec);
                }
            }

            mListeners.notifyHiddenLocked(changedNotifications);
        }
    }

    private void unhideNotificationsForPackages(@NonNull String[] pkgs,
            @NonNull int[] uidList) {
        synchronized (mNotificationLock) {
            Set<Integer> uidSet = Arrays.stream(uidList).boxed().collect(Collectors.toSet());
            List<String> pkgList = Arrays.asList(pkgs);
            List<NotificationRecord> changedNotifications = new ArrayList<>();
            int numNotifications = mNotificationList.size();
            for (int i = 0; i < numNotifications; i++) {
                NotificationRecord rec = mNotificationList.get(i);
                if (pkgList.contains(rec.getSbn().getPackageName())
                        && uidSet.contains(rec.getUid())) {
                    rec.setHidden(false);
                    changedNotifications.add(rec);
                }
            }

            mListeners.notifyUnhiddenLocked(changedNotifications);
        }
    }

    private void cancelNotificationsWhenEnterLockDownMode(int userId) {
        synchronized (mNotificationLock) {
            int numNotifications = mNotificationList.size();
            for (int i = 0; i < numNotifications; i++) {
                NotificationRecord rec = mNotificationList.get(i);
                if (rec.getUser().getIdentifier() != userId) {
                    continue;
                }
                mListeners.notifyRemovedLocked(rec, REASON_LOCKDOWN,
                        rec.getStats());
            }

        }
    }

    private void postNotificationsWhenExitLockDownMode(int userId) {
        synchronized (mNotificationLock) {
            int numNotifications = mNotificationList.size();
            // Set the delay to spread out the burst of notifications.
            long delay = 0;
            for (int i = 0; i < numNotifications; i++) {
                NotificationRecord rec = mNotificationList.get(i);
                if (rec.getUser().getIdentifier() != userId) {
                    continue;
                }
                mHandler.postDelayed(() -> {
                    synchronized (mNotificationLock) {
                        mListeners.notifyPostedLocked(rec, rec);
                    }
                }, delay);
                delay += 20;
            }
        }
    }

    protected boolean isCallingUidSystem() {
        final int uid = Binder.getCallingUid();
        return uid == Process.SYSTEM_UID;
    }

    protected boolean isCallingAppIdSystem() {
        final int uid = Binder.getCallingUid();
        final int appid = UserHandle.getAppId(uid);
        return appid == Process.SYSTEM_UID;
    }

    protected boolean isUidSystemOrPhone(int uid) {
        final int appid = UserHandle.getAppId(uid);
        return (appid == Process.SYSTEM_UID || appid == Process.PHONE_UID
                || uid == Process.ROOT_UID);
    }

    // TODO: Most calls should probably move to isCallerSystem.
    protected boolean isCallerSystemOrPhone() {
        return isUidSystemOrPhone(Binder.getCallingUid());
    }

    @VisibleForTesting
    protected boolean isCallerSystemOrSystemUi() {
        if (isCallerSystemOrPhone()) {
            return true;
        }
        return getContext().checkCallingPermission(STATUS_BAR_SERVICE)
                == PERMISSION_GRANTED;
    }

    private boolean isCallerSystemOrSystemUiOrShell() {
        int callingUid = Binder.getCallingUid();
        if (callingUid == Process.SHELL_UID || callingUid == Process.ROOT_UID) {
            return true;
        }
        return isCallerSystemOrSystemUi();
    }

    private void checkCallerIsSystemOrShell() {
        int callingUid = Binder.getCallingUid();
        if (callingUid == Process.SHELL_UID || callingUid == Process.ROOT_UID) {
            return;
        }
        checkCallerIsSystem();
    }

    private void checkCallerIsSystem() {
        if (isCallerSystemOrPhone()) {
            return;
        }
        throw new SecurityException("Disallowed call for uid " + Binder.getCallingUid());
    }

    private void checkCallerIsSystemOrSystemUi() {
        if (isCallerSystemOrSystemUi()) {
            return;
        }
        throw new SecurityException("Disallowed call for uid " + Binder.getCallingUid());
    }

    private void assertCallerIsSystemOrSystemUiOrShell() {
        int callingUid = Binder.getCallingUid();
        if (callingUid == Process.SHELL_UID || callingUid == Process.ROOT_UID) {
            return;
        }
        if (isCallerSystemOrPhone()) {
            return;
        }
        getContext().enforceCallingPermission(STATUS_BAR_SERVICE,
                "Caller not system or sysui or shell");
    }

    private void checkCallerIsSystemOrSameApp(String pkg) {
        if (isCallerSystemOrPhone()) {
            return;
        }
        checkCallerIsSameApp(pkg);
    }

    private boolean isCallerAndroid(String callingPkg, int uid) {
        return isUidSystemOrPhone(uid) && callingPkg != null
                && PackageManagerService.PLATFORM_PACKAGE_NAME.equals(callingPkg);
    }

    /**
     * Check if the notification is of a category type that is restricted to system use only,
     * if so throw SecurityException
     */
    private void checkRestrictedCategories(final Notification notification) {
        try {
            if (!mPackageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE, 0)) {
                return;
            }
        } catch (RemoteException re) {
            if (DBG) Slog.e(TAG, "Unable to confirm if it's safe to skip category "
                    + "restrictions check thus the check will be done anyway");
        }
        if (Notification.CATEGORY_CAR_EMERGENCY.equals(notification.category)
                || Notification.CATEGORY_CAR_WARNING.equals(notification.category)
                || Notification.CATEGORY_CAR_INFORMATION.equals(notification.category)) {
            getContext().enforceCallingPermission(
                    android.Manifest.permission.SEND_CATEGORY_CAR_NOTIFICATIONS,
                    String.format("Notification category %s restricted",
                            notification.category));
        }
    }

    @VisibleForTesting
    boolean isCallerInstantApp(int callingUid, int userId) {
        // System is always allowed to act for ephemeral apps.
        if (isUidSystemOrPhone(callingUid)) {
            return false;
        }

        if (userId == USER_ALL) {
            userId = USER_SYSTEM;
        }

        try {
            final String[] pkgs = mPackageManager.getPackagesForUid(callingUid);
            if (pkgs == null) {
                throw new SecurityException("Unknown uid " + callingUid);
            }
            final String pkg = pkgs[0];
            mAppOps.checkPackage(callingUid, pkg);

            ApplicationInfo ai = mPackageManager.getApplicationInfo(pkg, 0, userId);
            if (ai == null) {
                throw new SecurityException("Unknown package " + pkg);
            }
            return ai.isInstantApp();
        } catch (RemoteException re) {
            throw new SecurityException("Unknown uid " + callingUid, re);
        }
    }

    private void checkCallerIsSameApp(String pkg) {
        checkCallerIsSameApp(pkg, Binder.getCallingUid(), UserHandle.getCallingUserId());
    }

    private void checkCallerIsSameApp(String pkg, int uid, int userId) {
        if (uid == Process.ROOT_UID && ROOT_PKG.equals(pkg)) {
            return;
        }
        // malicious sdks could make use of sdksandbox uid to cause DoS b/396667508
        if (Process.isSdkSandboxUid(uid)
                || !mPackageManagerInternal.isSameApp(pkg, 0L, uid, userId)) {
            throw new SecurityException("Package " + pkg + " is not owned by uid " + uid);
        }
    }

    private boolean isCallerSameApp(String pkg, int uid, int userId) {
        try {
            checkCallerIsSameApp(pkg, uid, userId);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    private static String callStateToString(int state) {
        switch (state) {
            case TelephonyManager.CALL_STATE_IDLE: return "CALL_STATE_IDLE";
            case TelephonyManager.CALL_STATE_RINGING: return "CALL_STATE_RINGING";
            case TelephonyManager.CALL_STATE_OFFHOOK: return "CALL_STATE_OFFHOOK";
            default: return "CALL_STATE_UNKNOWN_" + state;
        }
    }

    /**
     * Generates a NotificationRankingUpdate from 'sbns', considering only notifications visible to
     * the given listener.
     */
    @GuardedBy("mNotificationLock")
    NotificationRankingUpdate makeRankingUpdateLocked(@Nullable ManagedServiceInfo info) {
        final int N = mNotificationList.size();
        final ArrayList<NotificationListenerService.Ranking> rankings = new ArrayList<>();

        for (int i = 0; i < N; i++) {
            NotificationRecord record = mNotificationList.get(i);
            if (isInLockDownMode(record.getUser().getIdentifier())) {
                continue;
            }
            if (info != null
                    && !isVisibleToListener(record.getSbn(), record.getNotificationType(), info)) {
                continue;
            }
            final String key = record.getSbn().getKey();
            final NotificationListenerService.Ranking ranking =
                    new NotificationListenerService.Ranking();
            ArrayList<Notification.Action> smartActions = record.getSystemGeneratedSmartActions();
            ArrayList<CharSequence> smartReplies = record.getSmartReplies();
            boolean hasSensitiveContent = record.hasSensitiveContent();
            if (info != null && redactSensitiveNotificationsFromUntrustedListeners()) {
                if (!mListeners.isUidTrusted(info.uid) && mListeners.hasSensitiveContent(record)) {
                    smartActions = null;
                    smartReplies = null;
                }
            }
            NotificationChannel effectiveChannel = record.getChannel();
            // special handling for a notification's channel visibility when bundled: if the
            // notification's original channel had a more strict visibility than the current
            // channel, or if the current channel has an unspecified visibility, patch that
            // original visibility into the channel stored in Ranking.
            if (record.getOriginalChannelVisibility() != VISIBILITY_NO_OVERRIDE) {
                int currentChannelVis = record.getChannel().getLockscreenVisibility();
                if (currentChannelVis == VISIBILITY_NO_OVERRIDE
                        || record.getOriginalChannelVisibility() < currentChannelVis) {
                    effectiveChannel = record.getChannel().copy();
                    effectiveChannel.setLockscreenVisibility(
                            record.getOriginalChannelVisibility());
                }
            }

            String summarization = record.getSummarization();
            if (android.security.Flags.appLockCore()) {
                StatusBarNotification sbn = record.getSbn();
                // remove Smart actions/replies if the package is locked and it's not the NAS
                if (isPackageLockedByAppLockLocked(sbn.getPackageName(), sbn.getNormalizedUserId())
                        && !(info != null && mAssistants.isServiceTokenValidLocked(
                        info.getService()))) {
                    smartActions = null;
                    smartReplies = null;
                    summarization = null;
                }
            }

            ranking.populate(
                    key,
                    rankings.size(),
                    !record.isIntercepted(),
                    record.getPackageVisibilityOverride(),
                    record.getSuppressedVisualEffects(),
                    record.getImportance(),
                    record.getImportanceExplanation(),
                    record.getSbn().getOverrideGroupKey(),
                    effectiveChannel,
                    record.getSnoozeCriteria(),
                    record.canShowBadge(),
                    record.getUserSentiment(),
                    record.isHidden(),
                    record.getLastAudiblyAlertedMs(),
                    smartActions,
                    smartReplies,
                    record.canBubble(),
                    record.isTextChanged(),
                    record.isConversation(),
                    record.getShortcutInfo(),
                    record.getNotification().isBubbleNotification(),
                    record.getProposedImportance(),
                    hasSensitiveContent,
                    summarization
            );
            rankings.add(ranking);
        }

        return new NotificationRankingUpdate(
                rankings.toArray(new NotificationListenerService.Ranking[0]));
    }

    boolean isInLockDownMode(int userId) {
        return mStrongAuthTracker.isInLockDownMode(userId);
    }

    boolean hasCompanionDevice(ManagedServiceInfo info) {
        if (info == null) {
            return false;
        }
        return hasCompanionDevice(info.component.getPackageName(),
                info.userid, /* withDeviceProfile= */ null);
    }

    private boolean hasCompanionDevice(String pkg, @UserIdInt int userId,
            @Nullable Set</* @AssociationRequest.DeviceProfile */ String> withDeviceProfiles) {
        if (mCompanionManager == null) {
            mCompanionManager = getCompanionManager();
        }
        // Companion mgr doesn't exist on all device types
        if (mCompanionManager == null) {
            return false;
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            List<AssociationInfo> associations = mCompanionManager.getAssociations(pkg, userId);
            for (AssociationInfo association : associations) {
                if (withDeviceProfiles == null || withDeviceProfiles.contains(
                        association.getDeviceProfile())) {
                    return true;
                }
            }
        } catch (SecurityException se) {
            // Not a privileged listener
        } catch (RemoteException re) {
            Slog.e(TAG, "Cannot reach companion device service", re);
        } catch (Exception e) {
            Slog.e(TAG, "Cannot verify caller pkg=" + pkg + ", userId=" + userId, e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return false;
    }

    protected ICompanionDeviceManager getCompanionManager() {
        return ICompanionDeviceManager.Stub.asInterface(
                ServiceManager.getService(Context.COMPANION_DEVICE_SERVICE));
    }

    @VisibleForTesting
    boolean isVisibleToListener(StatusBarNotification sbn, int notificationType,
            ManagedServiceInfo listener) {
        if (!listener.enabledAndUserMatches(sbn.getUserId())) {
            return false;
        }
        if (!isInteractionVisibleToListener(listener, sbn.getUserId())) {
            return false;
        }
        NotificationListenerFilter nls = mListeners.getNotificationListenerFilter(listener.mKey);
        if (nls != null
                && (!nls.isTypeAllowed(notificationType)
                || !nls.isPackageAllowed(
                        new VersionedPackage(sbn.getPackageName(), sbn.getUid())))) {
            return false;
        }
        return true;
    }

    /**
     * Returns whether the given assistant should be informed about interactions on the given user.
     *
     * Normally an assistant would be able to see all interactions on the current user and any
     * associated profiles because they are notification listeners, but since NASes have one
     * instance per user, we want to filter out interactions that are not for the user that the
     * given NAS is bound in.
     */
    @VisibleForTesting
    boolean isInteractionVisibleToListener(ManagedServiceInfo info, int userId) {
        boolean isAssistantService = isNotificationAssistant(info.getService());
        return !isAssistantService || info.isSameUser(userId);
    }

    private boolean isNotificationAssistant(IInterface service) {
        synchronized (mNotificationLock) {
            return mAssistants.isServiceTokenValidLocked(service);
        }
    }

    private boolean isPackageSuspendedForUser(String pkg, int uid) {
        final long identity = Binder.clearCallingIdentity();
        int userId = UserHandle.getUserId(uid);
        try {
            return mPackageManager.isPackageSuspendedForUser(pkg, userId);
        } catch (RemoteException re) {
            throw new SecurityException("Could not talk to package manager service");
        } catch (IllegalArgumentException ex) {
            // Package not found.
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @VisibleForTesting
    boolean canUseManagedServices(String pkg, Integer userId, String requiredPermission) {
        boolean canUseManagedServices = true;
        if (requiredPermission != null) {
            try {
                if (mPackageManager.checkPermission(requiredPermission, pkg, userId)
                        != PERMISSION_GRANTED) {
                    canUseManagedServices = false;
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "can't talk to pm", e);
            }
        }

        return canUseManagedServices;
    }

    private class TrimCache {
        StatusBarNotification heavy;
        StatusBarNotification sbnClone;
        StatusBarNotification sbnCloneLight;

        TrimCache(StatusBarNotification sbn) {
            heavy = sbn;
        }

        StatusBarNotification ForListener(ManagedServiceInfo info) {
            if (mListeners.getOnNotificationPostedTrim(info) == TRIM_LIGHT) {
                if (sbnCloneLight == null) {
                    sbnCloneLight = heavy.cloneLight();
                }
                return sbnCloneLight;
            } else {
                if (sbnClone == null) {
                    sbnClone = heavy.clone();
                }
                return sbnClone;
            }
        }
    }

    public class NotificationAssistants extends ManagedServices {
        static final String TAG_ENABLED_NOTIFICATION_ASSISTANTS = "enabled_assistants";

        static final String ATT_TYPES = "types";
        static final String TAG_DENIED = "denied_adjustment_keys";
        static final String TAG_DENIED_KEY = "adjustment";
        static final String ATT_DENIED_KEY = "key";
        static final String ATT_DENIED_KEY_APPS = "denied_apps";
        static final String TAG_ENABLED_TYPES = "enabled_bundle_types";
        private static final String ATT_NAS_UNSUPPORTED = "unsupported_adjustments";
        private static final String ATT_USER_ID = "user";
        // for classification only, but named a bit more generally in case this ever gets expanded
        static final String TAG_SET_BY_USERS = "adjustment_pref_set_by_users";
        static final String ATT_USER_LIST = "users";
        private static final String TAG_BUNDLE = "bundle";
        private static final String ATT_TYPE = "type";
        private static final String ATT_NAME = "name";

        private final Object mLock = new Object();

        // Map of user ID -> the set of adjustment keys that are denied for that user.
        @GuardedBy("mLock")
        private Map<Integer, Set<String>> mDeniedAdjustments = new ArrayMap<>();

        @GuardedBy("mLock")
        private Map<Integer, HashSet<String>> mNasUnsupported = new ArrayMap<>();

        // Map of user ID -> the disallowed packages for each adjustment key.
        // Inner map key: Adjustment key. value - list of pkgs that we shouldn't apply
        // adjustments with that key to
        @GuardedBy("mLock")
        private Map<Integer, Map<String, Set<String>>> mAdjustmentKeyDeniedPackages =
                new ArrayMap<>();

        protected ComponentName mDefaultFromConfig = null;

        // Map of user Id -> [dynamic bundle id -> DynamicBundle] for that user. User profiles share
        // values with their parent user.
        @GuardedBy("mLock")
        private Map<Integer, ArrayMap<Integer,DynamicBundle>> mDynamicBundleMap = new ArrayMap<>();

        @Override
        protected void loadDefaultsFromConfig() {
            loadDefaultsFromConfig(true);
        }

        protected void loadDefaultsFromConfig(boolean addToDefault) {
            ArraySet<String> assistants = new ArraySet<>();
            assistants.addAll(Arrays.asList(mContext.getResources().getString(
                    com.android.internal.R.string.config_defaultAssistantAccessComponent)
                    .split(ManagedServices.ENABLED_SERVICES_SEPARATOR)));
            for (int i = 0; i < assistants.size(); i++) {
                ComponentName assistantCn = ComponentName
                        .unflattenFromString(assistants.valueAt(i));
                String packageName = assistants.valueAt(i);
                if (assistantCn != null) {
                    packageName = assistantCn.getPackageName();
                }
                if (TextUtils.isEmpty(packageName)) {
                    continue;
                }
                ArraySet<ComponentName> approved = queryPackageForServices(packageName,
                        MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE, USER_SYSTEM);
                if (approved.contains(assistantCn)) {
                    if (addToDefault) {
                        // add the default loaded from config file to mDefaultComponents and
                        // mDefaultPackages
                        addDefaultComponentOrPackage(assistantCn.flattenToString());
                    } else {
                        // otherwise, store in the mDefaultFromConfig for NAS settings migration
                        mDefaultFromConfig = assistantCn;
                    }
                }
            }
        }

        ComponentName getDefaultFromConfig() {
            if (mDefaultFromConfig == null) {
                loadDefaultsFromConfig(false);
            }
            return mDefaultFromConfig;
        }

        @Override
        protected void upgradeUserSet() {
            for (int userId: mApproved.keySet()) {
                ArraySet<String> userSetServices = mUserSetServices.get(userId);
                mIsUserChanged.put(userId, (userSetServices != null && userSetServices.size() > 0));
            }
        }

        @Override
        protected int addApprovedList(String approved, int userId, boolean isPrimary,
                String userSet) {
            if (!TextUtils.isEmpty(approved)) {
                String[] approvedArray = approved.split(ENABLED_SERVICES_SEPARATOR);
                if (approvedArray.length > 1) {
                    Slog.d(TAG, "More than one approved assistants");
                    approved = approvedArray[0];
                }
            }
            return super.addApprovedList(approved, userId, isPrimary, userSet);
        }

        public NotificationAssistants(Context context, IPackageManager iPackageManager) {
            super(context, mNotificationLock, mUserProfiles, iPackageManager);
        }

        @Override
        protected Config getConfig() {
            Config c = new Config();
            c.caption = "notification assistant";
            c.serviceInterface = NotificationAssistantService.SERVICE_INTERFACE;
            c.xmlTag = TAG_ENABLED_NOTIFICATION_ASSISTANTS;
            c.secureSettingName = Secure.ENABLED_NOTIFICATION_ASSISTANT;
            c.bindPermission = Manifest.permission.BIND_NOTIFICATION_ASSISTANT_SERVICE;
            c.settingsAction = Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS;
            c.clientLabel = R.string.notification_ranker_binding_label;
            return c;
        }

        @Override
        protected IInterface asInterface(IBinder binder) {
            return INotificationListener.Stub.asInterface(binder);
        }

        @Override
        protected boolean checkType(IInterface service) {
            return service instanceof INotificationListener;
        }

        @Override
        protected void onServiceAdded(ManagedServiceInfo info) {
            mListeners.registerGuestService(info);
        }

        @Override
        protected void ensureFilters(ServiceInfo si, int userId) {
            // nothing to filter; no user visible settings for types/packages like other
            // listeners
        }

        @Override
        @GuardedBy("mNotificationLock")
        protected void onServiceRemovedLocked(ManagedServiceInfo removed) {
            mListeners.unregisterService(removed.service, removed.userid);
        }

        @Override
        public void onUserUnlocked(int user) {
            if (DEBUG) Slog.d(TAG, "onUserUnlocked u=" + user);
            // force rebind the assistant, as it might be keeping its own state in user locked
            // storage
            rebindServices(true, user);
        }

        @Override
        protected boolean allowRebindForParentUser() {
            return false;
        }

        @Override
        protected String getRequiredPermission() {
            // only signature/privileged apps can be bound.
            return android.Manifest.permission.REQUEST_NOTIFICATION_ASSISTANT_SERVICE;
        }

        @Override
        public void dump(PrintWriter pw, DumpFilter filter) {
            super.dump(pw, filter);
            pw.println("    Unsupported Adjustment keys: ");
            for (int userId : mNasUnsupported.keySet()) {
                pw.println("      " + userId + ": " + mNasUnsupported.get(userId));
            }
            pw.println("    (user) Denied Adjustment keys (see rules for KEY_TYPE): ");
            for (int userId : mDeniedAdjustments.keySet()) {
                pw.println("      user " + userId + ": " + deniedAdjustmentsForUser(userId));
            }

            if (android.app.Flags.nmContextualDisplay()) {
                pw.println("    Dynamic bundle types: ");
                for (int userId : mDynamicBundleMap.keySet()) {
                    pw.println("      user " + userId + ": " + mDynamicBundleMap.get(userId));
                }
            }

            pw.println("    Disallowed adjustment pkg count: ");
            for (int userId : mAdjustmentKeyDeniedPackages.keySet()) {
                pw.println("      user: " + userId + ": ");
                for (String type : mAdjustmentKeyDeniedPackages.get(userId).keySet()) {
                    pw.println("          " + type + ": "
                            + mAdjustmentKeyDeniedPackages.get(userId).size());
                }
            }
        }

        // Convenience method to return the effective list of denied adjustments for the given user.
        // For full users, this is just the list of denied adjustments contained in
        // mDeniedAdjustments for that user.
        // KEY_SUMMARIZATION is denied by default.
        // For profile users, this method checks additional criteria that may cause an adjustment to
        // be effectively denied for that user. In particular:
        // - if an adjustment is denied for that profile user's parent, then it is also effectively
        //   denied for that profile regardless of what the profile's current setting is.
        // - for classification (KEY_TYPE) only, if a user hasn't explicitly enabled the adjustment
        //   for their managed/work profile, default the setting to off.
        @GuardedBy("mLock")
        private @NonNull Set<String> deniedAdjustmentsForUser(@UserIdInt int userId) {
            Set<String> denied = new HashSet<>();
            if (!mDeniedAdjustments.containsKey(userId)) {
                mDeniedAdjustments.put(userId, new ArraySet<>(List.of(KEY_SUMMARIZATION)));
            }
            denied.addAll(mDeniedAdjustments.get(userId));
            if (getUserProfiles().isProfileUser(userId, mContext)) {
                final @UserIdInt int parentId = getUserProfiles().getProfileParentId(userId,
                        mContext);
                if (mDeniedAdjustments.containsKey(parentId)) {
                    denied.addAll(mDeniedAdjustments.get(parentId));
                }
                if (!nmContextualDisplayLaunch()) {
                    // Managed profiles only: if the setting hasn't been explicitly set for this
                    // profile, then also consider KEY_TYPE (classification) denied.
                    if (getUserProfiles().isManagedProfileUser(userId)
                            && !mNotificationRuleManager.isClassificationAllowedForManagedProfile(
                            userId)) {
                        denied.add(KEY_TYPE);
                    }
                }
            }
            return denied;
        }

        protected Set<String> getAllowedAssistantAdjustments(@UserIdInt int userId) {
            synchronized (mLock) {
                Set<String> types = new HashSet<>(Set.of(DEFAULT_ALLOWED_ADJUSTMENTS));
                types.removeAll(deniedAdjustmentsForUser(userId));
                if (nmContextualDisplayLaunch()) {
                    if (!mNotificationRuleManager.isClassificationAdjustmentAllowed(userId)) {
                        types.remove(KEY_TYPE);
                    }
                }
                return types;
            }
        }

        protected boolean isAdjustmentAllowed(@UserIdInt int userId, String type) {
            synchronized (mLock) {
                if (nmContextualDisplayLaunch() && KEY_TYPE.equals(type)) {
                    return List.of(DEFAULT_ALLOWED_ADJUSTMENTS).contains(type)
                            && mNotificationRuleManager.isClassificationAdjustmentAllowed(userId);
                }
                return List.of(DEFAULT_ALLOWED_ADJUSTMENTS).contains(type)
                        && !(deniedAdjustmentsForUser(userId).contains(type));
            }
        }

        protected @NonNull String[] getAdjustmentDeniedPackages(@UserIdInt int userId,
                @Adjustment.Keys String key) {
            synchronized (mLock) {
                if (KEY_TYPE.equals(key)) {
                    Slog.wtf(TAG, "Bundle information is in the rules manager");
                    return new String[]{};
                }
                if (mAdjustmentKeyDeniedPackages.containsKey(userId)) {
                    return mAdjustmentKeyDeniedPackages.get(userId).getOrDefault(
                            key, new ArraySet<>()).toArray(new String[0]);
                }
            }
            return new String[]{};
        }

        protected boolean isAdjustmentAllowedForPackage(@UserIdInt int userId,
                @Adjustment.Keys String key, String pkg) {
            synchronized (mLock) {
                if (KEY_TYPE.equals(key)) {
                    Slog.wtf(TAG, "Bundle information is in the rules manager");
                    return false;
                }
                if (mAdjustmentKeyDeniedPackages.containsKey(userId)) {
                    return !mAdjustmentKeyDeniedPackages.get(userId).getOrDefault(
                            key, new ArraySet<>()).contains(pkg);
                }
            }
            return true;
        }

        public void setAdjustmentSupportedForPackage(int userId, @Adjustment.Keys String key,
                String pkg, boolean enabled) {
            synchronized (mLock) {
                if (KEY_TYPE.equals(key)) {
                    Slog.wtf(TAG, "Bundle information is in the rules manager");
                    return;
                }
                mAdjustmentKeyDeniedPackages.putIfAbsent(userId, new ArrayMap<>());
                mAdjustmentKeyDeniedPackages.get(userId).putIfAbsent(key, new ArraySet<>());
                if (enabled) {
                    mAdjustmentKeyDeniedPackages.get(userId).get(key).remove(pkg);
                } else {
                    mAdjustmentKeyDeniedPackages.get(userId).get(key).add(pkg);
                }
            }
        }

        // For logging preferences: get a map of user id -> package name -> list of denied keys
        // This is essentially a reconfiguration of the contents of mAdjustmentKeyDeniedPackages.
        @NonNull Map<Integer, Map<String, List<String>>> getDeniedKeysForUsersAndPackages() {
            Map<Integer, Map<String, List<String>>> out = new ArrayMap<>();
            synchronized (mLock) {
                for (int userId : mAdjustmentKeyDeniedPackages.keySet()) {
                    Map<String, Set<String>> pkgsByType = mAdjustmentKeyDeniedPackages.get(userId);
                    if (!pkgsByType.isEmpty()) {
                        out.putIfAbsent(userId, new ArrayMap<>());
                        Map<String, List<String>> pkgMapForUser = out.get(userId);
                        for (String keyType : pkgsByType.keySet()) {
                            for (String pkgName : pkgsByType.get(keyType)) {
                                pkgMapForUser.putIfAbsent(pkgName, new ArrayList<>());
                                pkgMapForUser.get(pkgName).add(keyType);
                            }
                        }
                    }
                }
            }
            return out;
        }

        protected DynamicBundle deleteDynamicBundle(@UserIdInt int userId, int dynamicBundleType) {
            if (!android.app.Flags.nmContextualDisplay()) {
                return null;
            }
            // profile users share dynamic bundles with their parent user
            userId = getUserProfiles().getProfileParentId(userId, getContext());
            synchronized (mLock) {
                if (mDynamicBundleMap.containsKey(userId)) {
                    return mDynamicBundleMap.get(userId).remove(dynamicBundleType);
                }
                return null;
            }
        }

        protected DynamicBundle createDynamicBundle(@UserIdInt int userId, int dynamicBundleType,
                String bundleName) {
            if (!android.app.Flags.nmContextualDisplay()) {
                return null;
            }
            // profile users share dynamic bundles with their parent user
            userId = getUserProfiles().getProfileParentId(userId, getContext());
            synchronized (mLock) {
                mDynamicBundleMap.putIfAbsent(userId, new ArrayMap<>());
                DynamicBundle db = new DynamicBundle(dynamicBundleType, bundleName);
                if (mDynamicBundleMap.get(userId).containsKey(dynamicBundleType)) {
                    return null;
                }
                if (mDynamicBundleMap.get(userId).put(dynamicBundleType, db) == null) {
                    setAdjustmentKeySupportedState(userId, KEY_TYPE, true);
                }
                return db;
            }
        }

        protected @Nullable String getDynamicBundleName(@UserIdInt int userId,
                int dynamicBundleType) {
            if (!android.app.Flags.nmContextualDisplay()) {
                return null;
            }
            // profile users share dynamic bundles with their parent user
            userId = getUserProfiles().getProfileParentId(userId, getContext());
            synchronized (mLock) {
                DynamicBundle db = mDynamicBundleMap.getOrDefault(userId, new ArrayMap<>())
                        .get(dynamicBundleType);
                if (db == null) {
                    return null;
                } else {
                    return db.getBundleName();
                }
            }
        }

        protected Set<DynamicBundle> getDynamicBundles(@UserIdInt int userId) {
            if (!android.app.Flags.nmContextualDisplay()) {
                return new ArraySet<>();
            }
            // profile users share dynamic bundles with their parent user
            userId = getUserProfiles().getProfileParentId(userId, getContext());
            synchronized (mLock) {
                Map<Integer, DynamicBundle> dbs =
                        mDynamicBundleMap.getOrDefault(userId, new ArrayMap<>());
                return new ArraySet<>(dbs.values());
            }
        }

        protected void onNotificationsSeenLocked(ArrayList<NotificationRecord> records) {
            for (final ManagedServiceInfo info : NotificationAssistants.this.getServices()) {
                ArrayList<String> keys = new ArrayList<>(records.size());
                for (NotificationRecord r : records) {
                    boolean sbnVisible = isVisibleToListener(
                            r.getSbn(), r.getNotificationType(), info)
                            && info.isSameUser(r.getUserId());
                    if (sbnVisible) {
                        keys.add(r.getKey());
                    }
                }

                if (!keys.isEmpty()) {
                    mHandler.post(() -> notifySeen(info, keys));
                }
            }
        }

        protected void onPanelRevealed(int items) {
            // send to all currently bounds NASes since notifications from both users will appear in
            // the panel
            for (final ManagedServiceInfo info : NotificationAssistants.this.getServices()) {
                mHandler.post(() -> {
                    final INotificationListener assistant = (INotificationListener) info.service;
                    try {
                        assistant.onPanelRevealed(items);
                    } catch (RemoteException ex) {
                        Slog.e(TAG, "unable to notify assistant (panel revealed): " + info, ex);
                    }
                });
            }
        }

        protected void onPanelHidden() {
            // send to all currently bounds NASes since notifications from both users will appear in
            // the panel
            for (final ManagedServiceInfo info : NotificationAssistants.this.getServices()) {
                mHandler.post(() -> {
                    final INotificationListener assistant = (INotificationListener) info.service;
                    try {
                        assistant.onPanelHidden();
                    } catch (RemoteException ex) {
                        Slog.e(TAG, "unable to notify assistant (panel hidden): " + info, ex);
                    }
                });
            }
        }

        boolean hasUserSet(int userId) {
            Boolean userSet = mIsUserChanged.get(userId);
            return (userSet != null && userSet);
        }

        void setUserSet(int userId, boolean set) {
            mIsUserChanged.put(userId, set);
        }

        private void notifySeen(final ManagedServiceInfo info,
                final ArrayList<String> keys) {
            final INotificationListener assistant = (INotificationListener) info.service;
            try {
                assistant.onNotificationsSeen(keys);
            } catch (RemoteException ex) {
                Slog.e(TAG, "unable to notify assistant (seen): " + info, ex);
            }
        }

        protected void notifyNotificationRuleAdded(@UserIdInt int userId, NotificationRule rule) {
            if (rule == null) {
                return;
            }

            for (final ManagedServiceInfo info : NotificationAssistants.this.getServices()) {
                if (!info.isSameUser(userId)) {
                    continue;
                }

                mHandler.post(() -> {
                    final INotificationListener assistant = (INotificationListener) info.service;
                    try {
                        assistant.onNotificationRuleAdded(rule);
                    } catch (RemoteException ex) {
                        Slog.e(TAG, "unable to notify assistant (rule added): " + info, ex);
                    }
                });
            }
        }

        protected void notifyNotificationRuleModified(@UserIdInt int userId,
                NotificationRule rule) {
            if (rule == null) {
                return;
            }

            for (final ManagedServiceInfo info : NotificationAssistants.this.getServices()) {
                if (!info.isSameUser(userId)) {
                    continue;
                }

                mHandler.post(() -> {
                    final INotificationListener assistant = (INotificationListener) info.service;
                    try {
                        assistant.onNotificationRuleModified(rule);
                    } catch (RemoteException ex) {
                        Slog.e(TAG, "unable to notify assistant (rule modified): " + info, ex);
                    }
                });
            }
        }

        protected void notifyNotificationRuleRemoved(@UserIdInt int userId, int ruleId) {
            for (final ManagedServiceInfo info : NotificationAssistants.this.getServices()) {
                if (!info.isSameUser(userId)) {
                    continue;
                }

                mHandler.post(() -> {
                    final INotificationListener assistant = (INotificationListener) info.service;
                    try {
                        assistant.onNotificationRuleRemoved(ruleId);
                    } catch (RemoteException ex) {
                        Slog.e(TAG, "unable to notify assistant (rule removed): " + info, ex);
                    }
                });
            }
        }

        @GuardedBy("mNotificationLock")
        private void onNotificationEnqueuedLocked(final NotificationRecord r) {
            final boolean debug = isVerboseLogEnabled();
            if (debug) {
                Slog.v(TAG, "onNotificationEnqueuedLocked() called with: r = [" + r + "]");
            }
            final StatusBarNotification sbn = r.getSbn();

            for (final ManagedServiceInfo info : NotificationAssistants.this.getServices()) {
                boolean sbnVisible = isVisibleToListener(
                        sbn, r.getNotificationType(), info)
                        && info.isSameUser(r.getUserId());
                if (sbnVisible) {
                    TrimCache trimCache = new TrimCache(sbn);
                    final INotificationListener assistant = (INotificationListener) info.service;
                    final StatusBarNotification sbnToPost = trimCache.ForListener(info);
                    final NotificationRankingUpdate update = makeRankingUpdateLocked(info);

                    try {
                        assistant.onNotificationEnqueuedWithChannel(sbnToPost, r.getChannel(),
                                update);
                    } catch (DeadObjectException ex) {
                        Slog.wtf(TAG, "unable to notify assistant (enqueued): " + info, ex);
                    } catch (RemoteException ex) {
                        Slog.e(TAG, "unable to notify assistant (enqueued): " + info, ex);
                    }
                }
            }
        }

        @GuardedBy("mNotificationLock")
        void notifyAssistantOfSystemAdjustments(
                final NotificationRecord r, List<Adjustment> adjustments) {
            notifyAssistantLocked(
                    r.getSbn(),
                    r.getNotificationType(),
                    true /* sameUserOnly */,
                    (assistant, sbnToPost) -> {
                        try {
                            // Create a list of all the adjustment keys for logging. We group
                            // the keys by adjustment, where each adjustment is delimited by "|"
                            final List<String> adjustmentKeyStrings = new ArrayList<>();
                            for (Adjustment adjustment : adjustments) {
                                final Bundle signals = adjustment.getSignals();
                                if (signals != null && !signals.keySet().isEmpty()) {
                                    adjustmentKeyStrings.add(TextUtils.join(",", signals.keySet()));
                                }
                            }
                            EventLogTags.writeNotificationSystemAdjustmentsReceived(
                                    sbnToPost.getKey(), TextUtils.join("|", adjustmentKeyStrings));
                            assistant.onSystemAdjustmentsReceived(adjustments);
                        } catch (RemoteException ex) {
                            Slog.e(
                                    TAG,
                                    "unable to notify assistant (system adjustments): " + assistant,
                                    ex);
                        }
                    });
        }

        @GuardedBy("mNotificationLock")
        void notifyAssistantVisibilityChangedLocked(
                final NotificationRecord r,
                final boolean isVisible) {
            final String key = r.getSbn().getKey();
            if (DBG) {
                Slog.d(TAG, "notifyAssistantVisibilityChangedLocked: " + key);
            }
            notifyAssistantLocked(
                    r.getSbn(),
                    r.getNotificationType(),
                    true /* sameUserOnly */,
                    (assistant, unused) -> {
                        try {
                            assistant.onNotificationVisibilityChanged(key, isVisible);
                        } catch (RemoteException ex) {
                            Slog.e(TAG, "unable to notify assistant (visible): " + assistant, ex);
                        }
                    });
        }

        @GuardedBy("mNotificationLock")
        void notifyAssistantExpansionChangedLocked(
                final StatusBarNotification sbn,
                final int notificationType,
                final boolean isUserAction,
                final boolean isExpanded) {
            final String key = sbn.getKey();
            notifyAssistantLocked(
                    sbn,
                    notificationType,
                    true /* sameUserOnly */,
                    (assistant, unused) -> {
                        try {
                            assistant.onNotificationExpansionChanged(key, isUserAction, isExpanded);
                        } catch (RemoteException ex) {
                            Slog.e(TAG, "unable to notify assistant (expanded): " + assistant, ex);
                        }
                    });
        }

        @GuardedBy("mNotificationLock")
        void notifyAssistantNotificationDirectReplyLocked(
                final NotificationRecord r) {
            final String key = r.getKey();
            notifyAssistantLocked(
                    r.getSbn(),
                    r.getNotificationType(),
                    true /* sameUserOnly */,
                    (assistant, unused) -> {
                        try {
                            assistant.onNotificationDirectReply(key);
                        } catch (RemoteException ex) {
                            Slog.e(TAG, "unable to notify assistant (expanded): " + assistant, ex);
                        }
                    });
        }

        @GuardedBy("mNotificationLock")
        void notifyAssistantSuggestedReplySent(
                final StatusBarNotification sbn, int notificationType,
                CharSequence reply, boolean generatedByAssistant) {
            final String key = sbn.getKey();
            notifyAssistantLocked(
                    sbn,
                    notificationType,
                    true /* sameUserOnly */,
                    (assistant, unused) -> {
                        try {
                            assistant.onSuggestedReplySent(
                                    key,
                                    reply,
                                    generatedByAssistant
                                            ? NotificationAssistantService.SOURCE_FROM_ASSISTANT
                                            : NotificationAssistantService.SOURCE_FROM_APP);
                        } catch (RemoteException ex) {
                            Slog.e(TAG, "unable to notify assistant (snoozed): " + assistant, ex);
                        }
                    });
        }

        @GuardedBy("mNotificationLock")
        void notifyAssistantActionClicked(
                final NotificationRecord r, Notification.Action action,
                boolean generatedByAssistant) {
            final String key = r.getSbn().getKey();
            notifyAssistantLocked(
                    r.getSbn(),
                    r.getNotificationType(),
                    true /* sameUserOnly */,
                    (assistant, unused) -> {
                        try {
                            assistant.onActionClicked(
                                    key,
                                    action,
                                    generatedByAssistant
                                            ? NotificationAssistantService.SOURCE_FROM_ASSISTANT
                                            : NotificationAssistantService.SOURCE_FROM_APP);
                        } catch (RemoteException ex) {
                            Slog.e(TAG, "unable to notify assistant (snoozed): " + assistant, ex);
                        }
                    });
        }

        /**
         * asynchronously notify the assistant that a notification has been snoozed until a
         * context
         */
        @GuardedBy("mNotificationLock")
        private void notifyAssistantSnoozedLocked(
                final NotificationRecord r, final String snoozeCriterionId) {
            notifyAssistantLocked(
                    r.getSbn(),
                    r.getNotificationType(),
                    true /* sameUserOnly */,
                    (info, sbnToPost) -> {
                        try {
                            info.onNotificationSnoozedUntilContext(sbnToPost, snoozeCriterionId);
                        } catch (DeadObjectException ex) {
                            Slog.wtf(TAG, "unable to notify assistant (snoozed): " + info, ex);
                        } catch (RemoteException ex) {
                            Slog.e(TAG, "unable to notify assistant (snoozed): " + info, ex);
                        }
                    });
        }

        @GuardedBy("mNotificationLock")
        void notifyAssistantNotificationClicked(final NotificationRecord r) {
            final String key = r.getSbn().getKey();
            notifyAssistantLocked(
                    r.getSbn(),
                    r.getNotificationType(),
                    true /* sameUserOnly */,
                    (assistant, unused) -> {
                        try {
                            assistant.onNotificationClicked(key);
                        } catch (RemoteException ex) {
                            Slog.e(TAG, "unable to notify assistant (clicked): " + assistant, ex);
                        }
                    });
        }

        @GuardedBy("mNotificationLock")
        void notifyAssistantFeedbackReceived(final NotificationRecord r, Bundle feedback) {
            final StatusBarNotification sbn = r.getSbn();

            for (final ManagedServiceInfo info : NotificationAssistants.this.getServices()) {
                boolean sbnVisible = isVisibleToListener(
                        sbn, r.getNotificationType(), info)
                        && info.isSameUser(r.getUserId());
                if (sbnVisible) {
                    final INotificationListener assistant = (INotificationListener) info.service;
                    try {
                        final NotificationRankingUpdate update = makeRankingUpdateLocked(info);
                        assistant.onNotificationFeedbackReceived(sbn.getKey(), update, feedback);
                    } catch (RemoteException ex) {
                        Slog.e(TAG, "unable to notify assistant (feedback): " + assistant, ex);
                    }
                }
            }
        }

        /**
         * Notifies the assistant something about the specified notification, only assistant
         * that is visible to the notification will be notified.
         *
         * @param sbn          the notification object that the update is about.
         * @param sameUserOnly should the update  be sent to the assistant in the same user only.
         * @param callback     the callback that provides the assistant to be notified, executed
         *                     in WorkerHandler.
         */
        @GuardedBy("mNotificationLock")
        private void notifyAssistantLocked(
                final StatusBarNotification sbn,
                int notificationType,
                boolean sameUserOnly,
                BiConsumer<INotificationListener, StatusBarNotification> callback) {
            TrimCache trimCache = new TrimCache(sbn);
            // There should be only one, but it's a list, so while we enforce
            // singularity elsewhere, we keep it general here, to avoid surprises.

            final boolean debug = isVerboseLogEnabled();
            if (debug) {
                Slog.v(TAG,
                        "notifyAssistantLocked() called with: sbn = [" + sbn + "], sameUserOnly = ["
                                + sameUserOnly + "], callback = [" + callback + "]");
            }
            for (final ManagedServiceInfo info : NotificationAssistants.this.getServices()) {
                boolean sbnVisible = isVisibleToListener(sbn, notificationType, info)
                        && (!sameUserOnly || info.isSameUser(sbn.getUserId()));
                if (debug) {
                    Slog.v(TAG, "notifyAssistantLocked info=" + info + " snbVisible=" + sbnVisible);
                }
                if (!sbnVisible) {
                    continue;
                }
                final INotificationListener assistant = (INotificationListener) info.service;
                final StatusBarNotification sbnToPost = trimCache.ForListener(info);
                mHandler.post(() -> callback.accept(assistant, sbnToPost));
            }
        }

        public boolean isEnabled() {
            return !getServices().isEmpty();
        }

        protected void resetDefaultAssistantsIfNecessary() {
            final List<UserInfo> activeUsers = mUm.getAliveUsers();
            for (UserInfo userInfo : activeUsers) {
                int userId = userInfo.getUserHandle().getIdentifier();
                if (!hasUserSet(userId)) {
                    if (!isNASMigrationDone(userId)) {
                        resetDefaultFromConfig();
                        setNASMigrationDone(userId);
                    }
                    Slog.d(TAG, "Approving default notification assistant for user " + userId);
                    setDefaultAssistantForUser(userId);
                }
            }
        }

        protected void resetDefaultFromConfig() {
            clearDefaults();
            loadDefaultsFromConfig();
        }

        protected void clearDefaults() {
            mDefaultComponents.clear();
            mDefaultPackages.clear();
        }

        @Override
        protected boolean setPackageOrComponentEnabled(String pkgOrComponent, int userId,
                boolean isPrimary, boolean enabled, boolean userSet) {
            // Ensures that only one component is enabled at a time
            if (enabled) {
                List<ComponentName> allowedComponents = getAllowedComponents(userId);
                if (!allowedComponents.isEmpty()) {
                    ComponentName currentComponent = CollectionUtils.firstOrNull(allowedComponents);
                    if (currentComponent.flattenToString().equals(pkgOrComponent)) return false;
                    setNotificationAssistantAccessGrantedForUserInternal(
                            currentComponent, userId, false, userSet);
                }
            }
            return super.setPackageOrComponentEnabled(pkgOrComponent, userId, isPrimary, enabled,
                    userSet);
        }

        private boolean isVerboseLogEnabled() {
            return Log.isLoggable("notification_assistant", Log.VERBOSE);
        }

        @GuardedBy("mNotificationLock")
        public void allowAdjustmentKey(@UserIdInt int userId, @Adjustment.Keys String key) {
            synchronized (mLock) {
                if (mDeniedAdjustments.containsKey(userId)) {
                    mDeniedAdjustments.get(userId).remove(key);
                } else if (KEY_SUMMARIZATION.equals(key)) {
                    mDeniedAdjustments.put(userId, new ArraySet<>());
                }
            }
            for (final ManagedServiceInfo info : NotificationAssistants.this.getServices()) {
                mHandler.post(() -> notifyCapabilitiesChanged(info));
            }
        }

        @GuardedBy("mNotificationLock")
        public void disallowAdjustmentKey(@UserIdInt int userId, @Adjustment.Keys String key) {
            synchronized (mLock) {
                mDeniedAdjustments.putIfAbsent(userId, new ArraySet<>());
                mDeniedAdjustments.get(userId).add(key);
            }
            for (final ManagedServiceInfo info : NotificationAssistants.this.getServices()) {
                mHandler.post(() -> notifyCapabilitiesChanged(info));
            }
        }

        @GuardedBy("mNotificationLock")
        public void setAdjustmentKeySupportedState(@UserIdInt int userId,
                @Adjustment.Keys String key, boolean supported) {
            HashSet<String> disabledAdjustments =
                    mNasUnsupported.getOrDefault(userId, new HashSet<>());
            if (supported) {
                disabledAdjustments.remove(key);
            } else {
                disabledAdjustments.add(key);
            }
            mNasUnsupported.put(userId, disabledAdjustments);
            mContext.sendBroadcastAsUser(
                    new Intent(SUPPORTED_NAS_ADJUSTMENT_KEYS_CHANGED)
                            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT),
                    UserHandle.SYSTEM, STATUS_BAR_SERVICE);
            handleSavePolicyFile();
        }

        @GuardedBy("mNotificationLock")
        public @NonNull Set<String> getUnsupportedAdjustments(@UserIdInt int userId) {
            return mNasUnsupported.getOrDefault(userId, new HashSet<>());
        }

        void setNasUnsupportedDefaults(@UserIdInt int userId) {
            if (mNasUnsupported != null) {
                mNasUnsupported.put(userId, new HashSet(List.of(mDefaultUnsupportedAdjustments)));
                handleSavePolicyFile();
            }
        }

        @Override
        protected void writeExtraAttributes(TypedXmlSerializer out, @UserIdInt int approvedUserId)
                throws IOException {
            synchronized (mLock) {
                out.attribute(null, ATT_NAS_UNSUPPORTED, TextUtils.join(",",
                        mNasUnsupported.getOrDefault(approvedUserId, new HashSet<>())));
            }
        }

        @Override
        protected void readExtraAttributes(String tag, TypedXmlPullParser parser,
                @UserIdInt int approvedUserId) throws IOException {
            if (ManagedServices.TAG_MANAGED_SERVICES.equals(tag)) {
                final String types = XmlUtils.readStringAttribute(parser, ATT_NAS_UNSUPPORTED);
                synchronized (mLock) {
                    if (types == null) {
                        setNasUnsupportedDefaults(approvedUserId);
                    } else {
                        if (!TextUtils.isEmpty(types)) {
                            mNasUnsupported.put(approvedUserId,
                                    new HashSet(List.of(types.split(","))));
                        } else {
                            mNasUnsupported.put(approvedUserId, new HashSet());
                        }
                    }
                }
            }
        }

        @Override
        protected void writeExtraXmlTags(TypedXmlSerializer out,
                @Nullable BackupRestoreEventLogger logger) throws IOException {
            synchronized (mLock) {
                for (int user : mDeniedAdjustments.keySet()) {
                    Set<String> deniedKeys = mDeniedAdjustments.get(user);
                    if (nmContextualDisplayLaunch()) {
                        if (!mNotificationRuleManager.isClassificationAdjustmentAllowed(user)) {
                            deniedKeys.add(KEY_TYPE);
                        }
                    }
                    out.startTag(null, TAG_DENIED);
                    out.attributeInt(null, ATT_USER_ID, user);
                    out.attribute(null, ATT_TYPES,
                            TextUtils.join(",", deniedKeys));
                    out.endTag(null, TAG_DENIED);
                }

                for (int user : mAdjustmentKeyDeniedPackages.keySet()) {
                    Map<String, Set<String>> userDeniedPackages = mAdjustmentKeyDeniedPackages.get(
                            user);
                    for (String key : userDeniedPackages.keySet()) {
                        Set<String> pkgs = userDeniedPackages.get(key);
                        if (pkgs != null && !pkgs.isEmpty()) {
                            out.startTag(null, TAG_DENIED_KEY);
                            out.attributeInt(null, ATT_USER_ID, user);
                            out.attribute(null, ATT_DENIED_KEY, key);
                            out.attribute(null, ATT_DENIED_KEY_APPS, TextUtils.join(",", pkgs));
                            out.endTag(null, TAG_DENIED_KEY);
                        }
                    }
                }

                // TODO(b/438704204): Remove when android.app.Flags.nmContextualDisplayLaunch()
                //  is removed. Until then, this must only be called after the block that writes
                // TAG_DENIED so we can properly migrate whether KEY_TYPE is enabled for work
                // profiles
                mNotificationRuleManager.writeLegacyBundleStorageTags(out);

                if (android.app.Flags.nmContextualDisplay()) {
                    for (int user : mDynamicBundleMap.keySet()) {
                        for (DynamicBundle db : mDynamicBundleMap.get(user).values()) {
                            out.startTag(null, TAG_BUNDLE);
                            out.attributeInt(null, ATT_USER_ID, user);
                            out.attributeInt(null, ATT_TYPE, db.getDynamicBundleType());
                            out.attribute(null, ATT_NAME, db.getBundleName());
                            out.endTag(null, TAG_BUNDLE);
                        }
                    }
                }
            }
        }

        @Override
        protected void readExtraTag(String tag, TypedXmlPullParser parser,
                @Nullable BackupRestoreEventLogger logger) throws IOException {
            if (TAG_DENIED.equals(tag)) {
                if (nmContextualDisplayLaunch()) {
                    mNotificationRuleManager.readLegacyBundleStorageTag(parser, tag);
                }
                // default to current context user if this XML pre-dates user-specific settings.
                final int user = XmlUtils.readIntAttribute(parser, ATT_USER_ID,
                        mContext.getUserId());
                final String keys = XmlUtils.readStringAttribute(parser, ATT_TYPES);
                synchronized (mLock) {
                    Set<String> userDeniedAdjustments = mDeniedAdjustments.getOrDefault(user,
                            new ArraySet<>());
                    userDeniedAdjustments.clear();
                    if (!TextUtils.isEmpty(keys)) {
                        userDeniedAdjustments.addAll(Arrays.asList(keys.split(",")));
                        if (nmContextualDisplayLaunch()) {
                            userDeniedAdjustments.remove(KEY_TYPE);
                        }
                        mDeniedAdjustments.put(user, userDeniedAdjustments);
                    } else {
                        mDeniedAdjustments.put(user, new ArraySet<>());
                    }
                }
            } else if (TAG_ENABLED_TYPES.equals(tag) || TAG_SET_BY_USERS.equals(tag)) {
                mNotificationRuleManager.readLegacyBundleStorageTag(parser, tag);
            } else if (TAG_DENIED_KEY.equals(tag)) {
                final int user = XmlUtils.readIntAttribute(parser, ATT_USER_ID,
                        mContext.getUserId());
                final String key = XmlUtils.readStringAttribute(parser, ATT_DENIED_KEY);

                if (KEY_TYPE.equals(key)) {
                    mNotificationRuleManager.readLegacyBundleStorageTag(parser, tag);
                } else {
                    final String pkgs = XmlUtils.readStringAttribute(parser, ATT_DENIED_KEY_APPS);
                    if (!TextUtils.isEmpty(key) && !TextUtils.isEmpty(pkgs)) {
                        Map<String, Set<String>> userDeniedPackages =
                                mAdjustmentKeyDeniedPackages.getOrDefault(user, new ArrayMap<>());
                        List<String> pkgList = Arrays.asList(pkgs.split(","));
                        userDeniedPackages.put(key, new ArraySet<>(pkgList));
                        mAdjustmentKeyDeniedPackages.put(user, userDeniedPackages);
                    }
                }
            } else if (android.app.Flags.nmContextualDisplay() && TAG_BUNDLE.equals(tag)) {
                int user = XmlUtils.readIntAttribute(parser, ATT_USER_ID, USER_SYSTEM);
                int type = XmlUtils.readIntAttribute(parser, ATT_TYPE);
                String bundleName = XmlUtils.readStringAttribute(parser, ATT_NAME);
                createDynamicBundle(user, type, bundleName);
            }
        }

        private void notifyCapabilitiesChanged(final ManagedServiceInfo info) {
            final INotificationListener assistant = (INotificationListener) info.service;
            try {
                assistant.onAllowedAdjustmentsChanged();
            } catch (RemoteException ex) {
                Slog.e(TAG, "unable to notify assistant (capabilities): " + info, ex);
            }
        }

        /**
         * Fills out {@link NotificationAdjustmentPreferences} proto and wraps it in a
         * {@link StatsEvent}.
         */
        protected void pullAdjustmentPreferencesStats(List<StatsEvent> events) {
            final List<UserInfo> allUsers = mUm.getUsers();
            for (UserInfo ui : allUsers) {
                // only log for full users and managed profiles, which are the only users that have
                // separate switches available in settings
                if (!(ui.isFull() || ui.isManagedProfile())) {
                    continue;
                }

                int userId = ui.getUserHandle().getIdentifier();

                boolean bundlesSupported, summariesSupported;
                synchronized (mLock) {
                    List<String> unsupportedAdjustments = new ArrayList(
                            mNasUnsupported.getOrDefault(userId,
                                    new HashSet(List.of(mDefaultUnsupportedAdjustments)))
                    );
                    bundlesSupported = !unsupportedAdjustments.contains(Adjustment.KEY_TYPE);
                    summariesSupported = !unsupportedAdjustments.contains(
                            Adjustment.KEY_SUMMARIZATION);
                }

                if (bundlesSupported) {
                    boolean bundlesAllowed = nmContextualDisplayLaunch()
                            ? mNotificationRuleManager.isClassificationAdjustmentAllowed(userId)
                            : isAdjustmentAllowed(userId, KEY_TYPE);
                    int[] allowedBundleTypes =
                            mNotificationRuleManager.getAllowedClassificationTypes(userId).stream()
                            .mapToInt(Integer::intValue).toArray();
                    events.add(FrameworkStatsLog.buildStatsEvent(
                            NOTIFICATION_ADJUSTMENT_PREFERENCES,
                            /* optional int32 event_id = 1 */
                            NotificationPullStatsEvent.NOTIFICATION_BUNDLE_PREFERENCES_PULLED
                                    .getId(),
                            /* optional bool adjustment_allowed = 2 */ bundlesAllowed,
                            /* repeated BundleTypes allowed_bundle_types = 3 */ allowedBundleTypes,
                            /* optional android.stats.notification.AdjustmentKey key = 4 */
                            NotificationPullStatsEvent.adjustmentKeyEnum(KEY_TYPE),
                            /* optional int32 user_id = 5 */ userId));
                }

                if (summariesSupported) {
                    boolean summariesAllowed = isAdjustmentAllowed(userId, KEY_SUMMARIZATION);
                    events.add(FrameworkStatsLog.buildStatsEvent(
                            NOTIFICATION_ADJUSTMENT_PREFERENCES,
                            /* optional int32 event_id = 1 */
                            NotificationPullStatsEvent.NOTIFICATION_SUMMARIZATION_PREFERENCES_PULLED
                                    .getId(),
                            /* optional bool adjustment_allowed = 2 */ summariesAllowed,
                            /* repeated BundleTypes allowed_bundle_types = 3 */ new int[]{},
                            /* optional android.stats.notification.AdjustmentKey key = 4 */
                            NotificationPullStatsEvent.adjustmentKeyEnum(KEY_SUMMARIZATION),
                            /* optional int32 user_id = 5 */ userId));
                }
            }
        }
    }

    /**
     * Asynchronously notify all listeners about a posted (new or updated) notification. This
     * should be called from {@link PostNotificationRunnable} to "complete" the post (since SysUI is
     * one of the NLSes, and will display it to the user).
     *
     * <p>This method will call {@link PostNotificationTracker#finish} on the supplied tracker
     * when every {@link NotificationListenerService} has received the news.
     *
     * <p>Also takes care of removing a notification that has been visible to a listener before,
     * but isn't anymore.
     */
    @GuardedBy("mNotificationLock")
    private void notifyListenersPostedAndLogLocked(NotificationRecord r, NotificationRecord old,
            @NonNull PostNotificationTracker tracker,
            @Nullable NotificationRecordLogger.NotificationReported report) {
        List<Runnable> listenerCalls = mListeners.prepareNotifyPostedLocked(r, old, true);
        mHandler.post(() -> {
            for (Runnable listenerCall : listenerCalls) {
                listenerCall.run();
            }

            long postDurationMillis = tracker.finish();
            if (report != null) {
                report.post_duration_millis = postDurationMillis;
                mNotificationRecordLogger.logNotificationPosted(report);
            }
        });

        if (callstyleCallbackApi()) {
            notifyCallNotificationEventListenerOnPosted(r);
        }
    }

    @GuardedBy("mNotificationLock")
    private void maybeNotifySystemUiListenerLifetimeExtendedListLocked(
            List<NotificationRecord> notificationList, int packageImportance) {
        for (int i = notificationList.size() - 1; i >= 0; --i) {
            NotificationRecord record = notificationList.get(i);
            maybeNotifySystemUiListenerLifetimeExtendedLocked(record,
                    record.getSbn().getPackageName(), packageImportance);
        }
    }

    @GuardedBy("mNotificationLock")
    private void maybeNotifySystemUiListenerLifetimeExtendedLocked(NotificationRecord record,
            String pkg, int packageImportance) {
        if (record != null && (record.getSbn().getNotification().flags
                & FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY) > 0
                && !record.isCanceledAfterLifetimeExtension()) {
            // Mark that the notification is being updated due to cancelation, so it won't
            // be updated again if the app cancels multiple times.
            record.setCanceledAfterLifetimeExtension(true);

            boolean isAppForeground = pkg != null && packageImportance == IMPORTANCE_FOREGROUND;

            // Save the original Record's post silently value, so we can restore it after we send
            // the SystemUI specific silent update.
            boolean savedPostSilentlyState = record.shouldPostSilently();
            boolean savedOnlyAlertOnceState = (record.getNotification().flags
                    & FLAG_ONLY_ALERT_ONCE) > 0;
            // Lifetime extended notifications don't need to alert on new state change.
            record.setPostSilently(true);
            // We also set FLAG_ONLY_ALERT_ONCE to avoid the notification from HUN-ing again.
            record.getNotification().flags |= FLAG_ONLY_ALERT_ONCE;

            PostNotificationTracker tracker = mPostNotificationTrackerFactory.newTracker(null);
            tracker.addCleanupRunnable(() -> {
                synchronized (mNotificationLock) {
                    // Set the post silently status to the record's previous value.
                    record.setPostSilently(savedPostSilentlyState);
                    // Remove FLAG_ONLY_ALERT_ONCE if the notification did not previously have it.
                    if (!savedOnlyAlertOnceState) {
                        record.getNotification().flags &= ~FLAG_ONLY_ALERT_ONCE;
                    }
                }
            });

            mHandler.post(new EnqueueNotificationRunnable(record.getUser().getIdentifier(),
                    record, isAppForeground, /* isAppProvided= */ false, tracker));

            EventLogTags.writeNotificationCancelPrevented(record.getKey());
        }
    }

    private int getPackageImportanceWithIdentity(String pkg) {
        final long token = Binder.clearCallingIdentity();
        final int packageImportance;
        try {
            packageImportance = mActivityManager.getPackageImportance(pkg);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return packageImportance;
    }

    private int getPackageImportanceWithIdentity(int uid) {
        final int packageImportance;
        final long token = Binder.clearCallingIdentity();
        try {
            packageImportance = mActivityManager.getUidImportance(uid);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return packageImportance;
    }

    public class NotificationListeners extends ManagedServices {
        static final String TAG_ENABLED_NOTIFICATION_LISTENERS = "enabled_listeners";
        static final String TAG_REQUESTED_LISTENERS = "request_listeners";
        static final String TAG_REQUESTED_LISTENER = "listener";
        static final String ATT_COMPONENT = "component";
        static final String ATT_TYPES = "types";
        static final String ATT_PKG = "pkg";
        static final String ATT_UID = "uid";
        static final String TAG_APPROVED = "allowed";
        static final String TAG_DISALLOWED= "disallowed";
        static final String XML_SEPARATOR = ",";
        static final String FLAG_SEPARATOR = "\\|";

        static final String BINDER_TAG_ON_LISTENER_CONNECTED = "onListenerConnected";
        static final String BINDER_TAG_ON_NOTIFICATION_POSTED = "onNotificationPostedFull";
        static final String BINDER_TAG_ON_STATUS_BAR_ICONS_BEHAVIOR_CHANGED =
                "onStatusBarIconsBehaviorChanged";
        static final String BINDER_TAG_ON_NOTIFICATION_REMOVED = "onNotificationRemovedFull";
        static final String BINDER_TAG_ON_NOTIFICATION_RANKING_UPDATE =
                "onNotificationRankingUpdate";
        static final String BINDER_TAG_ON_LISTENER_HINTS_CHANGED = "onListenerHintsChanged";
        static final String BINDER_TAG_ON_INTERRUPTION_FILTER_CHANGED =
                "onInterruptionFilterChanged";
        static final String BINDER_TAG_ON_NOTIFICATION_CHANNEL_MODIFICATION =
                "onNotificationChannelModification";
        static final String BINDER_TAG_ON_NOTIFICATION_CHANNEL_GROUP_MODIFICATION =
                "onNotificationChannelGroupModification";

        private final ArraySet<ManagedServiceInfo> mLightTrimListeners = new ArraySet<>();

        @GuardedBy("mTrustedListenerUids")
        private final ArraySet<Integer> mTrustedListenerUids = new ArraySet<>();
        @GuardedBy("mRequestedNotificationListeners")
        private final ArrayMap<Pair<ComponentName, Integer>, NotificationListenerFilter>
                mRequestedNotificationListeners = new ArrayMap<>();
        private final boolean mIsHeadlessSystemUserMode;
        private final ConfigurableParameters mConfigurableParameters;

        public NotificationListeners(Context context, Object lock, UserProfiles userProfiles,
                IPackageManager pm, ConfigurableParameters configurableParams) {
            this(context, lock, userProfiles, pm, UserManager.isHeadlessSystemUserMode(),
                    configurableParams);
        }

        @VisibleForTesting
        public NotificationListeners(Context context, Object lock, UserProfiles userProfiles,
                IPackageManager pm, boolean isHeadlessSystemUserMode,
                ConfigurableParameters configurableParams) {
            super(context, lock, userProfiles, pm);
            this.mConfigurableParameters = configurableParams;
            this.mIsHeadlessSystemUserMode = isHeadlessSystemUserMode;
        }

        @Override
        protected boolean setPackageOrComponentEnabled(String pkgOrComponent, int userId,
                boolean isPrimary, boolean enabled, boolean userSet) {
            boolean changed = super.setPackageOrComponentEnabled(pkgOrComponent, userId, isPrimary,
                    enabled, userSet);
            if (!changed) {
                return false;
            }

            String pkgName = getPackageName(pkgOrComponent);
            if (redactSensitiveNotificationsFromUntrustedListeners()) {
                int uid = mPackageManagerInternal.getPackageUid(pkgName, 0, userId);
                if (!enabled && uid >= 0) {
                    synchronized (mTrustedListenerUids) {
                        mTrustedListenerUids.remove(uid);
                    }
                }
                if (enabled && uid >= 0 && isAppTrustedNotificationListenerService(uid, pkgName)) {
                    synchronized (mTrustedListenerUids) {
                        mTrustedListenerUids.add(uid);
                    }
                }
            }

            mContext.sendBroadcastAsUser(
                    new Intent(ACTION_NOTIFICATION_LISTENER_ENABLED_CHANGED)
                            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY),
                    UserHandle.of(userId), null);

            return true;
        }

        @Override
        protected void loadDefaultsFromConfig() {
            String defaultListenerAccess = mContext.getResources().getString(
                    R.string.config_defaultListenerAccessPackages);
            if (defaultListenerAccess != null) {
                String[] listeners =
                        defaultListenerAccess.split(ManagedServices.ENABLED_SERVICES_SEPARATOR);
                for (int i = 0; i < listeners.length; i++) {
                    if (TextUtils.isEmpty(listeners[i])) {
                        continue;
                    }
                    int packageQueryFlags = MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE;
                    // In the headless system user mode, packages might not be installed for the
                    // system user. Match packages for any user since apps can be installed only for
                    // non-system users and would be considering uninstalled for the system user.
                    if (mIsHeadlessSystemUserMode) {
                        packageQueryFlags += MATCH_ANY_USER;
                    }
                    ArraySet<ComponentName> approvedListeners =
                            this.queryPackageForServices(listeners[i], packageQueryFlags,
                                    USER_SYSTEM);
                    for (int k = 0; k < approvedListeners.size(); k++) {
                        ComponentName cn = approvedListeners.valueAt(k);
                        addDefaultComponentOrPackage(cn.flattenToString());
                    }
                }
            }
        }

        @Override
        protected long getBindFlags() {
            long freezeFlags = BIND_SIMULATE_ALLOW_FREEZE;
            if (Flags.allowFreezingIdleNls()) {
                freezeFlags |= BIND_ALLOW_FREEZE;
            }
            // Most of the same flags as the base, but also add BIND_NOT_PERCEPTIBLE
            // because too many 3P apps could be kept in memory as notification listeners and
            // cause extreme memory pressure.
            // TODO: Change the binding lifecycle of NotificationListeners to avoid this situation.
            return BIND_AUTO_CREATE | BIND_FOREGROUND_SERVICE
                    | BIND_NOT_PERCEPTIBLE | BIND_ALLOW_WHITELIST_MANAGEMENT | freezeFlags;
        }

        @Override
        protected Config getConfig() {
            Config c = new Config();
            c.caption = "notification listener";
            c.serviceInterface = NotificationListenerService.SERVICE_INTERFACE;
            c.xmlTag = TAG_ENABLED_NOTIFICATION_LISTENERS;
            c.secureSettingName = Secure.ENABLED_NOTIFICATION_LISTENERS;
            c.bindPermission = android.Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE;
            c.settingsAction = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS;
            c.clientLabel = R.string.notification_listener_binding_label;
            return c;
        }

        @Override
        protected IInterface asInterface(IBinder binder) {
            return INotificationListener.Stub.asInterface(binder);
        }

        @Override
        protected boolean checkType(IInterface service) {
            return service instanceof INotificationListener;
        }

        private long reportBinderTransactionStarting(@NonNull IBinderSession session,
                @NonNull String tag) {
            try {
                return session.binderTransactionStarting(tag);
            } catch (RemoteException re) {
                Slog.wtf(TAG, "Local call threw a remote exception", re);
            }
            return 0;
        }

        @Override
        public void onServiceAdded(ManagedServiceInfo info) {
            // Generally, only System or System UI should have the permissions to call
            // registerSystemService.
            // isCallerSystemOrPhone tells us whether the caller is System. We negate this,
            // to eliminate cases where the service was added by the system. This leaves
            // services registered by system server.
            // To identify system UI, we explicitly check the status bar permission for the
            // uid in the info object.
            // We can't use the calling uid here because it belongs to system server.
            // Note that this will also return true for the shell, but we deem this
            // acceptable, for the purposes of testing.
            info.isSystemUi = !isCallerSystemOrPhone() && getContext().checkPermission(
                    android.Manifest.permission.STATUS_BAR_SERVICE, -1, info.uid)
                    == PERMISSION_GRANTED;
            final INotificationListener listener = (INotificationListener) info.service;
            final NotificationRankingUpdate update;
            synchronized (mNotificationLock) {
                update = makeRankingUpdateLocked(info);
                updateUriPermissionsForActiveNotificationsLocked(info, true);
            }
            if (redactSensitiveNotificationsFromUntrustedListeners()
                    && isAppTrustedNotificationListenerService(
                    info.uid, info.component.getPackageName())) {
                synchronized (mTrustedListenerUids) {
                    mTrustedListenerUids.add(info.uid);
                }
            }
            final IDispatchCompletionListener completionListener;
            final long token;
            if (info.mBinderSession != null) {
                final IBinderSession session = info.mBinderSession;
                token = reportBinderTransactionStarting(session, BINDER_TAG_ON_LISTENER_CONNECTED);
                completionListener = new IDispatchCompletionListener.Stub() {
                    @Override
                    public void notifyDispatchComplete(long dispatchToken) {
                        mHandler.postDelayed(() -> {
                            try {
                                session.binderTransactionCompleted(dispatchToken);
                            } catch (RemoteException e) {
                                // Local call
                            }
                        }, mConfigurableParameters.mNlsCompletionDurationMs);
                    }
                };
            } else {
                completionListener = null;
                token = 0; // Will never be used as there is no completionListener.
            }
            try {
                listener.onListenerConnected(update, completionListener, token);
            } catch (RemoteException e) {
                // we tried
            }
        }

        @Override
        @GuardedBy("mNotificationLock")
        protected void onServiceRemovedLocked(ManagedServiceInfo removed) {
            updateUriPermissionsForActiveNotificationsLocked(removed, false);
            if (removeDisabledHints(removed)) {
                updateListenerHintsLocked();
                updateEffectsSuppressorLocked();
            }
            if (redactSensitiveNotificationsFromUntrustedListeners()) {
                synchronized (mTrustedListenerUids) {
                    mTrustedListenerUids.remove(removed.uid);
                }
            }
            mLightTrimListeners.remove(removed);
        }

        @Override
        public void onUserRemoved(int user) {
            super.onUserRemoved(user);
            synchronized (mRequestedNotificationListeners) {
                for (int i = mRequestedNotificationListeners.size() - 1; i >= 0; i--) {
                    if (mRequestedNotificationListeners.keyAt(i).second == user) {
                        mRequestedNotificationListeners.removeAt(i);
                    }
                }
            }
        }

        @Override
        public void onUserUnlocked(int user) {
            if (!managedServicesConcurrentMultiuser()
                    && mUmInternal.isVisibleBackgroundFullUser(user)) {
                // The main use case for visible background users is the Automotive
                // multi-display configuration where a passenger can use a secondary
                // display while the driver is using the main display.
                // NotificationListeners is designed only for the current user and work
                // profile. We added a condition to prevent visible background users from
                // updating the data managed within the NotificationListeners object.
                return;
            }
            super.onUserUnlocked(user);
        }

        @Override
        protected boolean allowRebindForParentUser() {
            return true;
        }

        @Override
        public void onPackagesChanged(boolean removingPackage, String[] pkgList, int[] uidList) {
            super.onPackagesChanged(removingPackage, pkgList, uidList);

            synchronized (mRequestedNotificationListeners) {
                // Since the default behavior is to allow everything, we don't need to explicitly
                // handle package add or update. they will be added to the xml file on next boot or
                // when the user tries to change the settings.
                if (removingPackage) {
                    for (int i = 0; i < pkgList.length; i++) {
                        String pkg = pkgList[i];
                        int userId = UserHandle.getUserId(uidList[i]);
                        for (int j = mRequestedNotificationListeners.size() - 1; j >= 0; j--) {
                            Pair<ComponentName, Integer> key =
                                    mRequestedNotificationListeners.keyAt(j);
                            if (key.second == userId && key.first.getPackageName().equals(pkg)) {
                                mRequestedNotificationListeners.removeAt(j);
                            }
                        }
                    }

                    // Clean up removed package from the disallowed packages list
                    for (int i = 0; i < pkgList.length; i++) {
                        String pkg = pkgList[i];
                        for (int j = mRequestedNotificationListeners.size() - 1; j >= 0; j--) {
                            NotificationListenerFilter nlf =
                                    mRequestedNotificationListeners.valueAt(j);
                            VersionedPackage ai = new VersionedPackage(pkg, uidList[i]);
                            nlf.removePackage(ai);
                        }
                    }
                }
            }
        }

        @Override
        protected String getRequiredPermission() {
            return null;
        }

        @Override
        protected boolean shouldReflectToSettings() {
            // androidx has a public method that reads the approved set of listeners from
            // Settings so we have to continue writing this list for this type of service
            return true;
        }

        @Override
        protected void readExtraTag(String tag, TypedXmlPullParser parser,
                @Nullable BackupRestoreEventLogger logger)
                throws IOException, XmlPullParserException {
            if (TAG_REQUESTED_LISTENERS.equals(tag)) {
                int count = 0;
                int errorCount = 0;

                final int listenersOuterDepth = parser.getDepth();
                while (XmlUtils.nextElementWithin(parser, listenersOuterDepth)) {
                    try {
                        if (!TAG_REQUESTED_LISTENER.equals(parser.getName())) {
                            continue;
                        }
                        final int userId = XmlUtils.readIntAttribute(parser, ATT_USER_ID);
                        final ComponentName cn = ComponentName.unflattenFromString(
                                XmlUtils.readStringAttribute(parser, ATT_COMPONENT));
                        int approved = FLAG_FILTER_TYPE_CONVERSATIONS | FLAG_FILTER_TYPE_ALERTING
                                | FLAG_FILTER_TYPE_SILENT | FLAG_FILTER_TYPE_ONGOING;

                        ArraySet<VersionedPackage> disallowedPkgs = new ArraySet<>();
                        final int listenerOuterDepth = parser.getDepth();
                        while (XmlUtils.nextElementWithin(parser, listenerOuterDepth)) {
                            if (TAG_APPROVED.equals(parser.getName())) {
                                approved = XmlUtils.readIntAttribute(parser, ATT_TYPES);
                            } else if (TAG_DISALLOWED.equals(parser.getName())) {
                                String pkg = XmlUtils.readStringAttribute(parser, ATT_PKG);
                                int uid = XmlUtils.readIntAttribute(parser, ATT_UID);
                                if (!TextUtils.isEmpty(pkg)) {
                                    VersionedPackage ai = new VersionedPackage(pkg, uid);
                                    disallowedPkgs.add(ai);
                                }
                            }
                        }
                        NotificationListenerFilter nlf =
                                new NotificationListenerFilter(approved, disallowedPkgs);
                        synchronized (mRequestedNotificationListeners) {
                            mRequestedNotificationListeners.put(Pair.create(cn, userId), nlf);
                        }
                        count++;
                    } catch (Exception e) {
                        Slog.e(TAG, "Failed to restore NLS restriction", e);
                        errorCount++;
                    }
                }
                if (logger != null) {
                    logger.logItemsRestored(DATA_TYPE_NLS_RESTRICTED, count);
                    logger.logItemsRestoreFailed(
                            DATA_TYPE_NLS_RESTRICTED, errorCount, ERROR_XML_PARSING);
                }
            }
        }

        @Override
        protected void writeExtraXmlTags(TypedXmlSerializer out,
                @Nullable BackupRestoreEventLogger logger) throws IOException {
            int count = 0;
            out.startTag(null, TAG_REQUESTED_LISTENERS);
            synchronized (mRequestedNotificationListeners) {
                for (Pair<ComponentName, Integer> listener :
                        mRequestedNotificationListeners.keySet()) {
                    NotificationListenerFilter nlf = mRequestedNotificationListeners.get(listener);
                    out.startTag(null, TAG_REQUESTED_LISTENER);
                    XmlUtils.writeStringAttribute(
                            out, ATT_COMPONENT, listener.first.flattenToString());
                    XmlUtils.writeIntAttribute(out, ATT_USER_ID, listener.second);

                    out.startTag(null, TAG_APPROVED);
                    XmlUtils.writeIntAttribute(out, ATT_TYPES, nlf.getTypes());
                    out.endTag(null, TAG_APPROVED);

                    for (VersionedPackage ai : nlf.getDisallowedPackages()) {
                        if (!TextUtils.isEmpty(ai.getPackageName())) {
                            out.startTag(null, TAG_DISALLOWED);
                            XmlUtils.writeStringAttribute(out, ATT_PKG, ai.getPackageName());
                            XmlUtils.writeIntAttribute(out, ATT_UID, ai.getVersionCode());
                            out.endTag(null, TAG_DISALLOWED);
                        }
                    }

                    out.endTag(null, TAG_REQUESTED_LISTENER);
                    count++;
                }
            }

            out.endTag(null, TAG_REQUESTED_LISTENERS);
            if (logger != null) {
                logger.logItemsBackedUp(DATA_TYPE_NLS_RESTRICTED, count);
            }
        }

        @Nullable protected NotificationListenerFilter getNotificationListenerFilter(
                Pair<ComponentName, Integer> pair) {
            synchronized (mRequestedNotificationListeners) {
                return mRequestedNotificationListeners.get(pair);
            }
        }

        protected void setNotificationListenerFilter(Pair<ComponentName, Integer> pair,
                NotificationListenerFilter nlf) {
            synchronized (mRequestedNotificationListeners) {
                mRequestedNotificationListeners.put(pair, nlf);
            }
        }

        @Override
        protected void ensureFilters(ServiceInfo si, int userId) {
            Pair<ComponentName, Integer> listener = Pair.create(si.getComponentName(), userId);
            synchronized (mRequestedNotificationListeners) {
                NotificationListenerFilter existingNlf =
                        mRequestedNotificationListeners.get(listener);
                if (si.metaData != null) {
                    if (existingNlf == null) {
                        // no stored filters for this listener; see if they provided a default
                        if (si.metaData.containsKey(META_DATA_DEFAULT_FILTER_TYPES)) {
                            String typeList =
                                    si.metaData.get(META_DATA_DEFAULT_FILTER_TYPES).toString();
                            if (typeList != null) {
                                int types = getTypesFromStringList(typeList);
                                NotificationListenerFilter nlf =
                                        new NotificationListenerFilter(types, new ArraySet<>());
                                mRequestedNotificationListeners.put(listener, nlf);
                            }
                        }
                    }

                    // also check the types they never want bridged
                    if (si.metaData.containsKey(META_DATA_DISABLED_FILTER_TYPES)) {
                        int neverBridge = getTypesFromStringList(si.metaData.get(
                                META_DATA_DISABLED_FILTER_TYPES).toString());
                        if (neverBridge != 0) {
                            NotificationListenerFilter nlf =
                                    mRequestedNotificationListeners.getOrDefault(
                                            listener, new NotificationListenerFilter());
                            nlf.setTypes(nlf.getTypes() & ~neverBridge);
                            mRequestedNotificationListeners.put(listener, nlf);
                        }
                    }
                }
            }
        }

        private int getTypesFromStringList(String typeList) {
            int types = 0;
            if (typeList != null) {
                String[] typeStrings = typeList.split(FLAG_SEPARATOR);
                for (int i = 0; i < typeStrings.length; i++) {
                    final String typeString = typeStrings[i];
                    if (TextUtils.isEmpty(typeString)) {
                        continue;
                    }
                    if (typeString.equalsIgnoreCase("ONGOING")) {
                        types |= FLAG_FILTER_TYPE_ONGOING;
                    } else if (typeString.equalsIgnoreCase("CONVERSATIONS")) {
                        types |= FLAG_FILTER_TYPE_CONVERSATIONS;
                    } else if (typeString.equalsIgnoreCase("SILENT")) {
                        types |= FLAG_FILTER_TYPE_SILENT;
                    } else if (typeString.equalsIgnoreCase("ALERTING")) {
                        types |= FLAG_FILTER_TYPE_ALERTING;
                    } else {
                        try {
                            types |= Integer.parseInt(typeString);
                        } catch (NumberFormatException e) {
                            // skip
                        }
                    }
                }
            }
            return types;
        }

        @GuardedBy("mNotificationLock")
        public void setOnNotificationPostedTrimLocked(ManagedServiceInfo info, int trim) {
            if (trim == TRIM_LIGHT) {
                mLightTrimListeners.add(info);
            } else {
                mLightTrimListeners.remove(info);
            }
        }

        public int getOnNotificationPostedTrim(ManagedServiceInfo info) {
            return mLightTrimListeners.contains(info) ? TRIM_LIGHT : TRIM_FULL;
        }

        public void onStatusBarIconsBehaviorChanged(boolean hideSilentStatusIcons) {
            // send to all currently bounds NASes since notifications from both users will appear in
            // the status bar
            for (final ManagedServiceInfo info : getServices()) {
                final long token = getDispatchReportingToken(info,
                        BINDER_TAG_ON_STATUS_BAR_ICONS_BEHAVIOR_CHANGED);
                mHandler.post(() -> {
                    final INotificationListener listener = (INotificationListener) info.service;
                    try {
                        listener.onStatusBarIconsBehaviorChanged(hideSilentStatusIcons, token);
                    } catch (RemoteException ex) {
                        Slog.e(TAG, "unable to notify listener "
                                + "(hideSilentStatusIcons): " + info, ex);
                    }
                });
            }
        }

        private long getDispatchReportingToken(ManagedServiceInfo info, String input) {
            if (info.mBinderSession != null) {
                return reportBinderTransactionStarting(info.mBinderSession, input);
            } else {
                return 0; // Will be unused because dispatch completion is disabled.
            }
        }

        /**
         * Asynchronously notify all listeners about a new or updated notification. Note that the
         * notification is new or updated from the point of view of the NLS, but might not be
         * "strictly new" <em>from the point of view of NMS itself</em> -- for example, this method
         * is also invoked after exiting lockdown mode.
         *
         * <p>
         * Also takes care of removing a notification that has been visible to a listener before,
         * but isn't anymore.
         */
        @VisibleForTesting
        @GuardedBy("mNotificationLock")
        void notifyPostedLocked(NotificationRecord r, NotificationRecord old) {
            notifyPostedLocked(r, old, true);
        }

        /**
         * Asynchronously notify all listeners about a new or updated notification. Note that the
         * notification is new or updated from the point of view of the NLS, but might not be
         * "strictly new" <em>from the point of view of NMS itself</em> -- for example, this method
         * is invoked after exiting lockdown mode.
         *
         * @param notifyAllListeners notifies all listeners if true, else only notifies listeners
         *                           targeting <= O_MR1
         */
        @VisibleForTesting
        @GuardedBy("mNotificationLock")
        void notifyPostedLocked(NotificationRecord r, NotificationRecord old,
                boolean notifyAllListeners) {
            for (Runnable listenerCall : prepareNotifyPostedLocked(r, old, notifyAllListeners)) {
                mHandler.post(listenerCall);
            }
        }

        /**
         * "Prepares" to notify all listeners about the posted notification.
         *
         * <p>This method <em>does not invoke</em> the listeners; the caller should post each
         * returned {@link Runnable} on a suitable thread to do so.
         *
         * @param notifyAllListeners notifies all listeners if true, else only notifies listeners
         *                           targeting <= O_MR1
         * @return A list of {@link Runnable} operations to notify all listeners about the posted
         * notification.
         */
        @VisibleForTesting
        @GuardedBy("mNotificationLock")
        List<Runnable> prepareNotifyPostedLocked(NotificationRecord r,
                NotificationRecord old, boolean notifyAllListeners) {
            if (isInLockDownMode(r.getUser().getIdentifier())) {
                return new ArrayList<>();
            }

            ArrayList<Runnable> listenerCalls = new ArrayList<>();
            try {
                // Lazily initialized snapshots of the notification.
                StatusBarNotification sbn = r.getSbn();
                StatusBarNotification oldSbn = (old != null) ? old.getSbn() : null;
                TrimCache trimCache = new TrimCache(sbn);
                TrimCache redactedCache = null;
                StatusBarNotification redactedSbn = null;
                StatusBarNotification oldRedactedSbn = null;
                boolean isNewSensitive = hasSensitiveContent(r);
                boolean isOldSensitive = hasSensitiveContent(old);
                boolean redactionEnabled = redactSensitiveNotificationsFromUntrustedListeners()
                        && mRedactOtpNotifications;
                boolean appLockRedactionEnabled = shouldCreateAppLockRedactedSbn(sbn);

                for (final ManagedServiceInfo info : getServices()) {
                    boolean isTrusted = isUidTrusted(info.uid);
                    boolean sendRedacted = redactionEnabled && isNewSensitive && !isTrusted;
                    // Send App Lock redacted notification to all listeners except for the NAS
                    // which gets the full notification
                    boolean sendAppLockRedacted = appLockRedactionEnabled
                            && !mAssistants.isServiceTokenValidLocked(info.getService());
                    boolean sendOldRedacted = redactionEnabled && isOldSensitive && !isTrusted;
                    boolean sbnVisible = isVisibleToListener(sbn, r.getNotificationType(), info);
                    boolean oldSbnVisible = (oldSbn != null)
                            && isVisibleToListener(oldSbn, old.getNotificationType(), info);
                    // This notification hasn't been and still isn't visible -> ignore.
                    if (!oldSbnVisible && !sbnVisible) {
                        continue;
                    }
                    // If the notification is hidden, don't notifyPosted listeners targeting < P.
                    // Instead, those listeners will receive notifyPosted when the notification is
                    // unhidden.
                    if (r.isHidden() && info.targetSdkVersion < Build.VERSION_CODES.P) {
                        continue;
                    }

                    if (redactedSbn == null) {
                        // Only create one redacted SBN
                        if (sendAppLockRedacted) {
                            redactedSbn = redactSbnForAppLock(sbn);
                        } else if (sendRedacted) {
                            redactedSbn = redactSbnForOtp(sbn);
                        }
                        if (redactedSbn != null) {
                            redactedCache = new TrimCache(redactedSbn);
                        }
                    }

                    final StatusBarNotification sbnToPost = sendRedacted || sendAppLockRedacted
                            ? redactedCache.ForListener(info) : trimCache.ForListener(info);

                    // Checks if this is a request to notify system UI about a notification that
                    // has been lifetime extended.
                    // We check both old and new for the flag, to avoid catching updates
                    // (where new will not have the flag).
                    // If it is such a request, and this is the system UI listener, we send
                    // the post request. If it's any other listener, we skip it.
                    if (old != null && old.getNotification() != null
                            && (old.getNotification().flags
                            & FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY) > 0
                            && sbn != null && sbn.getNotification() != null
                            && (sbn.getNotification().flags
                            & FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY) > 0) {
                        if (info.isSystemUi()) {
                            final NotificationRankingUpdate update =
                                    makeRankingUpdateLocked(info);
                            listenerCalls.add(() -> notifyPosted(info, sbnToPost, update));
                            break;
                        } else {
                            // Skipping because this is the direct-reply "update" and we only
                            // need to send it to sysui, so we immediately continue, before it
                            // can get sent to other listeners below.
                            if (DBG) {
                                Slog.d(TAG, "prepareNotifyPostedLocked: direct reply update, "
                                        + "skipping post to " + info.toString());
                            }
                            continue;
                        }
                    }

                    // If we shouldn't notify all listeners, this means the hidden state of
                    // a notification was changed.  Don't notifyPosted listeners targeting >= P.
                    // Instead, those listeners will receive notifyRankingUpdate.
                    if (!notifyAllListeners && info.targetSdkVersion >= Build.VERSION_CODES.P) {
                        continue;
                    }

                    final NotificationRankingUpdate update = makeRankingUpdateLocked(info);

                    // This notification became invisible -> remove the old one.
                    if (oldSbnVisible && !sbnVisible) {
                        if (sendOldRedacted && oldRedactedSbn == null) {
                            oldRedactedSbn = redactSbnForOtp(oldSbn);
                        }
                        final StatusBarNotification oldSbnLightClone =
                                sendOldRedacted ? oldRedactedSbn.cloneLight() : oldSbn.cloneLight();
                        listenerCalls.add(() -> notifyRemoved(
                                info, oldSbnLightClone, update, null, REASON_USER_STOPPED));

                        continue;
                    }
                    // Grant access before listener is notified
                    final int targetUserId = (info.userid == USER_ALL)
                            ? USER_SYSTEM : info.userid;
                    updateUriPermissions(r, old, info.component.getPackageName(), targetUserId);

                    mPackageManagerInternal.grantImplicitAccess(
                            targetUserId, null /* intent */,
                            UserHandle.getAppId(info.uid),
                            sbn.getUid(),
                            false /* direct */, false /* retainOnUpdate */);

                    listenerCalls.add(() -> notifyPosted(info, sbnToPost, update));
                }
            } catch (Exception e) {
                Slog.e(TAG, "Could not notify listeners for " + r.getKey(), e);
            }
            return listenerCalls;
        }

        boolean isAppTrustedNotificationListenerService(int uid, String pkg) {
            if (!redactSensitiveNotificationsFromUntrustedListeners()) {
                return true;
            }

            long token = Binder.clearCallingIdentity();
            try {
                if (mPackageManager.checkUidPermission(RECEIVE_SENSITIVE_NOTIFICATIONS, uid)
                        == PERMISSION_GRANTED || mPackageManagerInternal.isPlatformSigned(pkg)
                        || mAppOps
                        .noteOpNoThrow(OP_RECEIVE_SENSITIVE_NOTIFICATIONS, uid, pkg, null, null)
                        == MODE_ALLOWED) {
                    return true;
                }

                // check if there is a CDM association with the listener
                // We don't listen for changes because if an association is lost, the app loses
                // NLS access
                List<AssociationInfo> cdmAssocs = new ArrayList<>();
                if (mCompanionManager == null) {
                    mCompanionManager = getCompanionManager();
                }
                if (mCompanionManager != null) {
                    cdmAssocs =
                            mCompanionManager.getAllAssociationsForUser(UserHandle.getUserId(uid));
                }
                for (int i = 0; i < cdmAssocs.size(); i++) {
                    AssociationInfo assocInfo = cdmAssocs.get(i);
                    if (!assocInfo.isRevoked() && pkg.equals(assocInfo.getPackageName())
                            && assocInfo.getUserId() == UserHandle.getUserId(uid)) {
                        return true;
                    }
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to check trusted status of listener", e);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            return false;
        }

        @GuardedBy("mNotificationLock")
        void notifyPackageAppLockStatedChanged(List<NotificationRecord> changedNotifications) {
            if (changedNotifications == null || changedNotifications.size() == 0) {
                return;
            }

            int numChangedNotifications = changedNotifications.size();
            for (int i = 0; i < numChangedNotifications; i++) {
                NotificationRecord rec = changedNotifications.get(i);
                rec.getNotification().flags |= Notification.FLAG_SILENT;
                // Remove old notification, and send the new "redacted ish" one
                notifyPostedLocked(rec, rec, true);
            }
        }

        /**
         * Creates a redacted version of a {@link StatusBarNotification} for an app that is locked.
         *
         * <p>This redaction process replaces the notification's title with the application's label
         * and the content text with a generic message, while removing all actions.
         *
         * <p>Special handling is applied to bubbled {@link MessagingStyle} notifications to
         * preserve the sender's identity, which maintains the context of the conversation while
         * hiding the message content. For all other notification styles, the style is removed
         * entirely.
         *
         * @param sbn The original {@link StatusBarNotification} to redact.
         * @return A new, redacted {@link StatusBarNotification}.
         */
        StatusBarNotification redactSbnForAppLock(StatusBarNotification sbn) {
            Trace.beginSection(TAG + ".redactSbnForAppLock");
            try {
                if (!android.security.Flags.appLockCore()) {
                    Slog.wtf(TAG, "redactSbnForAppLock called while flag is off");
                    return sbn;
                }

                String redactedText = sbn.getNotification().isStyle(MessagingStyle.class)
                        ? mContext.getString(R.string.app_locked_notification_message)
                        : mContext.getString(R.string.app_locked_new_notification);
                Notification.Builder redactedNotifBuilder = createBaseRedactedNotification(sbn,
                        redactedText, /* isAppLocked= */ true);

                Notification redacted = redactedNotifBuilder.build();
                return sbn.cloneShallow(redacted);
            } finally {
                Trace.endSection();
            }
        }

        /**
         * Creates a redacted copy of a {@link StatusBarNotification} for listeners that are not
         * trusted to receive sensitive information such as OTPs.
         *
         * <p>This redaction process involves:
         * <ul>
         *     <li>Replacing the notification's title with the application's label.</li>
         *     <li>Replacing the content text, big text, and sub-text with a generic
         *     "Redacted message" string.</li>
         *     <li>Preserving notification actions but redacting their titles.</li>
         *     <li>For {@link android.app.Notification.MessagingStyle} notifications, replacing the
         *     messages with a single redacted message.</li>
         *     <li>For {@link android.app.Notification.BigTextStyle} notifications, redacting the
         *     big text content.</li>
         *     <li>Removing other potentially sensitive information from the notification's
         *     extras.</li>
         * </ul>
         *
         * @param sbn The original {@link StatusBarNotification} to redact.
         * @return A new, redacted {@link StatusBarNotification}.
         */
        StatusBarNotification redactSbnForOtp(StatusBarNotification sbn) {
            if (!redactSensitiveNotificationsFromUntrustedListeners()) {
                throw new RuntimeException("redactSbnForOtp called while flag is off");
            }

            String redactedText = mContext.getString(R.string.redacted_notification_message);
            final Notification originalNotification = sbn.getNotification();
            Notification.Builder redactedNotifBuilder = createBaseRedactedNotification(sbn,
                    redactedText, /* isAppLocked= */ false);
            if (originalNotification.actions != null) {
                for (int i = 0; i < originalNotification.actions.length; i++) {
                    Notification.Action act = new Notification.Action.Builder(
                            originalNotification.actions[i]).build();
                    act.title = mContext.getString(R.string.redacted_notification_action_title);
                    redactedNotifBuilder.addAction(act);
                }
            }

            if (redactSensitiveNotificationsBigTextStyle()
                    && originalNotification.isStyle(Notification.BigTextStyle.class)) {
                Notification.BigTextStyle bigTextStyle = new Notification.BigTextStyle();
                bigTextStyle.bigText(mContext.getString(R.string.redacted_notification_message));
                bigTextStyle.setBigContentTitle("");
                bigTextStyle.setSummaryText("");
                redactedNotifBuilder.setStyle(bigTextStyle);
            }

            Notification redacted = redactedNotifBuilder.build();
            if (redacted.extras.containsKey(EXTRA_TITLE_BIG)) {
                // EXTRA_TITLE is set to the package label with setContentTitle earlier
                redacted.extras.putString(EXTRA_TITLE_BIG, redacted.extras.getString(EXTRA_TITLE));
            }
            redacted.extras.remove(EXTRA_SUB_TEXT);
            redacted.extras.remove(EXTRA_TEXT_LINES);
            redacted.extras.remove(EXTRA_LARGE_ICON_BIG);
            return sbn.cloneShallow(redacted);
        }

        private String getPkgLabelFromSbn(StatusBarNotification sbn) {
            ApplicationInfo appInfo = sbn.getNotification().extras.getParcelable(
                    EXTRA_BUILDER_APPLICATION_INFO, ApplicationInfo.class);
            if (appInfo != null) {
                return appInfo.loadLabel(mPackageManagerClient).toString();
            } else {
                Slog.w(TAG, "StatusBarNotification " + sbn + " does not have ApplicationInfo."
                        + " Did you pass in a 'cloneLight' notification?");
                return sbn.getPackageName();
            }
        }

        private Notification.Builder createBaseRedactedNotification(StatusBarNotification sbn,
                String redactedText, boolean isAppLocked) {
            Notification originalNotification = sbn.getNotification();
            Notification oldClone = new Notification();
            originalNotification.cloneInto(oldClone, /* heavy= */ false);
            String pkgLabel = getPkgLabelFromSbn(sbn);
            if (isAppLocked) {
                oldClone.actions = null;
            }
            Notification.Builder redactedNotifBuilder =
                    new Notification.Builder(getContext(), oldClone)
                        .setContentTitle(pkgLabel)
                        .setContentText(redactedText)
                        .setSubText(null)
                        .setActions();

            if (originalNotification.isStyle(MessagingStyle.class) && (
                    (originalNotification.isBubbleNotification() && isAppLocked) || !isAppLocked)) {
                Person sender;
                if (isAppLocked) {
                    // Once an locked notification has been bubbled, keep the sender.
                    MessagingStyle.Message latestMessage = MessagingStyle.findLatestIncomingMessage(
                            ((MessagingStyle) redactedNotifBuilder.getStyle()).getMessages());

                    sender = (latestMessage != null) ? latestMessage.getSenderPerson()
                            : new Person.Builder().setName("").build();
                } else {
                    sender = new Person.Builder().setName("").build();
                }

                MessagingStyle messageStyle = new MessagingStyle(sender);
                messageStyle.addMessage(
                        new MessagingStyle.Message(redactedText, System.currentTimeMillis(),
                                sender));
                redactedNotifBuilder.setStyle(messageStyle);
            } else if (isAppLocked) {
                redactedNotifBuilder.setStyle(null);
            }

            return redactedNotifBuilder;
        }

        boolean shouldCreateAppLockRedactedSbn(StatusBarNotification sbn) {
            return android.security.Flags.appLockCore()
                    && NotificationManagerService.this.isPackageLockedByAppLockLocked(
                    sbn.getPackageName(), sbn.getNormalizedUserId())
                    && !sbn.getNotification().isStyle(Notification.CallStyle.class);
        }

        boolean hasSensitiveContent(NotificationRecord r) {
            if (r == null || !redactSensitiveNotificationsFromUntrustedListeners()) {
                return false;
            }
            return r.hasSensitiveContent();
        }

        boolean isUidTrusted(int uid) {
            synchronized (mTrustedListenerUids) {
                return !redactSensitiveNotificationsFromUntrustedListeners()
                        || mTrustedListenerUids.contains(uid);
            }
        }

        /**
         * Synchronously grant or revoke permissions to Uris for all active and visible
         * notifications to just the NotificationListenerService provided.
         */
        @GuardedBy("mNotificationLock")
        private void updateUriPermissionsForActiveNotificationsLocked(
                ManagedServiceInfo info, boolean grant) {
            try {
                for (final NotificationRecord r : mNotificationList) {
                    // When granting permissions, ignore notifications which are invisible.
                    // When revoking permissions, all notifications are invisible, so process all.
                    if (grant && !isVisibleToListener(r.getSbn(), r.getNotificationType(), info)) {
                        continue;
                    }
                    // If the notification is hidden, permissions are not required by the listener.
                    if (r.isHidden() && info.targetSdkVersion < Build.VERSION_CODES.P) {
                        continue;
                    }
                    // Grant or revoke access synchronously
                    final int targetUserId = (info.userid == USER_ALL)
                            ? USER_SYSTEM : info.userid;
                    if (grant) {
                        // Grant permissions by passing arguments as if the notification is new.
                        updateUriPermissions(/* newRecord */ r, /* oldRecord */ null,
                                info.component.getPackageName(), targetUserId);
                    } else {
                        // Revoke permissions by passing arguments as if the notification was
                        // removed, but set `onlyRevokeCurrentTarget` to avoid revoking permissions
                        // granted to *other* targets by this notification's URIs.
                        updateUriPermissions(/* newRecord */ null, /* oldRecord */ r,
                                info.component.getPackageName(), targetUserId,
                                /* onlyRevokeCurrentTarget */ true);
                    }
                }
            } catch (Exception e) {
                Slog.e(TAG, "Could not " + (grant ? "grant" : "revoke") + " Uri permissions to "
                        + info.component, e);
            }
        }

        /**
         * asynchronously notify all listeners about a removed notification
         */
        @GuardedBy("mNotificationLock")
        public void notifyRemovedLocked(NotificationRecord r, int reason,
                NotificationStats notificationStats) {
            if (isInLockDownMode(r.getUser().getIdentifier())) {
                return;
            }

            final StatusBarNotification sbn = r.getSbn();

            // make a copy in case changes are made to the underlying Notification object
            // NOTE: this copy is lightweight: it doesn't include heavyweight parts of the
            // notification
            final StatusBarNotification sbnLight = sbn.cloneLight();
            StatusBarNotification redactedSbn = null;
            boolean hasSensitiveContent = hasSensitiveContent(r);
            final boolean appLockRedactionEnabled = shouldCreateAppLockRedactedSbn(sbn);

            for (final ManagedServiceInfo info : getServices()) {
                if (!isVisibleToListener(sbn, r.getNotificationType(), info)) {
                    continue;
                }

                // don't notifyRemoved for listeners targeting < P
                // if not for reason package suspended
                if (r.isHidden() && reason != REASON_PACKAGE_SUSPENDED
                        && info.targetSdkVersion < Build.VERSION_CODES.P) {
                    continue;
                }

                // don't notifyRemoved for listeners targeting >= P
                // if the reason is package suspended
                if (reason == REASON_PACKAGE_SUSPENDED
                        && info.targetSdkVersion >= Build.VERSION_CODES.P) {
                    continue;
                }
                // Send App Lock redacted notification to all listeners except for the NAS
                // which gets the full notification
                boolean sendAppLockRedacted = appLockRedactionEnabled
                        && !mAssistants.isServiceTokenValidLocked(info.getService());

                boolean sendRedacted = redactSensitiveNotificationsFromUntrustedListeners()
                        && hasSensitiveContent && !isUidTrusted(info.uid);

                if (redactedSbn == null) {
                    // No need to create the OTP redacted SBN if we're using the package locked SBN
                    if (appLockRedactionEnabled) {
                        redactedSbn = redactSbnForAppLock(sbn);
                    } else if (sendRedacted) {
                        redactedSbn = redactSbnForOtp(sbn);
                    }
                }

                // Only assistants can get stats
                final NotificationStats stats = mAssistants.isServiceTokenValidLocked(
                        info.service)
                        ? notificationStats : null;
                final StatusBarNotification sbnToSend =
                        (sendAppLockRedacted || sendRedacted) ? redactedSbn : sbnLight;
                final NotificationRankingUpdate update = makeRankingUpdateLocked(info);
                mHandler.post(() -> notifyRemoved(info, sbnToSend, update, stats, reason));
            }

            // Revoke access after all listeners have been updated
            mHandler.post(() -> updateUriPermissions(null, r, null, USER_SYSTEM));
        }

        /**
         * Asynchronously notify all listeners about a reordering of notifications
         * unless changedHiddenNotifications is populated.
         * If changedHiddenNotifications is populated, there was a change in the hidden state
         * of the notifications.  In this case, we only send updates to listeners that
         * target >= P.
         */
        @GuardedBy("mNotificationLock")
        public void notifyRankingUpdateLocked(List<NotificationRecord> changedHiddenNotifications) {
            boolean isHiddenRankingUpdate = changedHiddenNotifications != null
                    && changedHiddenNotifications.size() > 0;

            // TODO (b/73052211): if the ranking update changed the notification type,
            // cancel notifications for NLSes that can't see them anymore
            for (final ManagedServiceInfo serviceInfo : getServices()) {
                if (!serviceInfo.isEnabledForUser() || !isInteractionVisibleToListener(
                        serviceInfo, ActivityManager.getCurrentUser())) {
                    continue;
                }

                boolean notifyThisListener = false;
                if (isHiddenRankingUpdate && serviceInfo.targetSdkVersion >=
                        Build.VERSION_CODES.P) {
                    for (NotificationRecord rec : changedHiddenNotifications) {
                        if (isVisibleToListener(
                                rec.getSbn(), rec.getNotificationType(), serviceInfo)) {
                            notifyThisListener = true;
                            break;
                        }
                    }
                }

                if (notifyThisListener || !isHiddenRankingUpdate) {
                    final NotificationRankingUpdate update = makeRankingUpdateLocked(
                            serviceInfo);
                    mHandler.post(() -> notifyRankingUpdate(serviceInfo, update));
                }
            }
        }

        @GuardedBy("mNotificationLock")
        public void notifyListenerHintsChangedLocked(final int hints) {
            for (final ManagedServiceInfo serviceInfo : getServices()) {
                if (!serviceInfo.isEnabledForUser() || !isInteractionVisibleToListener(
                        serviceInfo, ActivityManager.getCurrentUser())) {
                    continue;
                }
                mHandler.post(() -> notifyListenerHintsChanged(serviceInfo, hints));
            }
        }

        /**
         * asynchronously notify relevant listeners their notification is hidden
         * NotificationListenerServices that target P+:
         *      NotificationListenerService#notifyRankingUpdateLocked()
         * NotificationListenerServices that target <= P:
         *      NotificationListenerService#notifyRemovedLocked() with REASON_PACKAGE_SUSPENDED.
         */
        @GuardedBy("mNotificationLock")
        public void notifyHiddenLocked(List<NotificationRecord> changedNotifications) {
            if (changedNotifications == null || changedNotifications.size() == 0) {
                return;
            }

            notifyRankingUpdateLocked(changedNotifications);

            // for listeners that target < P, notifyRemoveLocked
            int numChangedNotifications = changedNotifications.size();
            for (int i = 0; i < numChangedNotifications; i++) {
                NotificationRecord rec = changedNotifications.get(i);
                mListeners.notifyRemovedLocked(rec, REASON_PACKAGE_SUSPENDED, rec.getStats());
            }
        }

        /**
         * asynchronously notify relevant listeners their notification is unhidden
         * NotificationListenerServices that target P+:
         *      NotificationListenerService#notifyRankingUpdateLocked()
         * NotificationListenerServices that target <= P:
         *      NotificationListeners#notifyPostedLocked()
         */
        @GuardedBy("mNotificationLock")
        public void notifyUnhiddenLocked(List<NotificationRecord> changedNotifications) {
            if (changedNotifications == null || changedNotifications.size() == 0) {
                return;
            }

            notifyRankingUpdateLocked(changedNotifications);

            // for listeners that target < P, notifyPostedLocked
            int numChangedNotifications = changedNotifications.size();
            for (int i = 0; i < numChangedNotifications; i++) {
                NotificationRecord rec = changedNotifications.get(i);
                notifyPostedLocked(rec, rec, false);
            }
        }

        public void notifyInterruptionFilterChanged(final int interruptionFilter) {
            for (final ManagedServiceInfo serviceInfo : getServices()) {
                if (!serviceInfo.isEnabledForUser() || !isInteractionVisibleToListener(
                        serviceInfo, ActivityManager.getCurrentUser())) {
                    continue;
                }
                mHandler.post(
                        () -> notifyInterruptionFilterChanged(serviceInfo, interruptionFilter));
            }
        }

        protected void notifyNotificationChannelChanged(final String pkg, final UserHandle user,
                final NotificationChannel channel, final int modificationType) {
            if (channel == null) {
                return;
            }
            for (final ManagedServiceInfo info : getServices()) {
                if (!info.enabledAndUserMatches(UserHandle.getCallingUserId())
                        || !isInteractionVisibleToListener(info, UserHandle.getCallingUserId())) {
                    continue;
                }

                BackgroundThread.getHandler().post(() -> {
                    if (info.isSystem
                            || hasCompanionDevice(info)
                            || isNotificationAssistant(info.service)) {
                        notifyNotificationChannelChanged(
                                info, pkg, user, channel, modificationType);
                    }
                });
            }
        }

        protected void notifyNotificationChannelGroupChanged(
                final String pkg, final UserHandle user, final NotificationChannelGroup group,
                final int modificationType) {
            if (group == null) {
                return;
            }
            for (final ManagedServiceInfo info : getServices()) {
                if (!info.enabledAndUserMatches(UserHandle.getCallingUserId())
                        || !isInteractionVisibleToListener(info, UserHandle.getCallingUserId())) {
                    continue;
                }

                BackgroundThread.getHandler().post(() -> {
                    if (info.isSystem() || hasCompanionDevice(info)) {
                        notifyNotificationChannelGroupChanged(
                                info, pkg, user, group, modificationType);
                    }
                });
            }
        }

        @VisibleForTesting
        void notifyPosted(final ManagedServiceInfo info,
                final StatusBarNotification sbn, NotificationRankingUpdate rankingUpdate) {
            final INotificationListener listener = (INotificationListener) info.service;
            final long token = getDispatchReportingToken(info, BINDER_TAG_ON_NOTIFICATION_POSTED);
            try {
                listener.onNotificationPosted(sbn, rankingUpdate, token);
            } catch (DeadObjectException ex) {
                Slog.wtf(TAG, "unable to notify listener (posted): " + info, ex);
            } catch (RemoteException ex) {
                Slog.e(TAG, "unable to notify listener (posted): " + info, ex);
            }
        }

        @VisibleForTesting
        void notifyRemoved(ManagedServiceInfo info, StatusBarNotification sbn,
                NotificationRankingUpdate rankingUpdate, NotificationStats stats, int reason) {
            final INotificationListener listener = (INotificationListener) info.service;
            try {
                if (!CompatChanges.isChangeEnabled(NOTIFICATION_CANCELLATION_REASONS, info.uid)
                        && (reason == REASON_CHANNEL_REMOVED || reason == REASON_CLEAR_DATA)) {
                    reason = REASON_CHANNEL_BANNED;
                }
                // apps before T don't know about REASON_ASSISTANT, so replace it with the
                // previously-used case, REASON_LISTENER_CANCEL
                if (!CompatChanges.isChangeEnabled(NOTIFICATION_LOG_ASSISTANT_CANCEL, info.uid)
                        && reason == REASON_ASSISTANT_CANCEL) {
                    reason = REASON_LISTENER_CANCEL;
                }
                final long token = getDispatchReportingToken(info,
                        BINDER_TAG_ON_NOTIFICATION_REMOVED);
                listener.onNotificationRemoved(sbn, rankingUpdate, stats, reason, token);
            } catch (DeadObjectException ex) {
                Slog.wtf(TAG, "unable to notify listener (removed): " + info, ex);
            } catch (RemoteException ex) {
                Slog.e(TAG, "unable to notify listener (removed): " + info, ex);
            }
        }

        @VisibleForTesting
        void notifyRankingUpdate(ManagedServiceInfo info,
                                         NotificationRankingUpdate rankingUpdate) {
            final INotificationListener listener = (INotificationListener) info.service;
            final long token = getDispatchReportingToken(info,
                    BINDER_TAG_ON_NOTIFICATION_RANKING_UPDATE);
            try {
                listener.onNotificationRankingUpdate(rankingUpdate, token);
            } catch (DeadObjectException ex) {
                Slog.wtf(TAG, "unable to notify listener (ranking update): " + info, ex);
            } catch (RemoteException ex) {
                Slog.e(TAG, "unable to notify listener (ranking update): " + info, ex);
            }
        }

        @VisibleForTesting
        void notifyListenerHintsChanged(ManagedServiceInfo info, int hints) {
            final INotificationListener listener = (INotificationListener) info.service;
            final long token = getDispatchReportingToken(info,
                    BINDER_TAG_ON_LISTENER_HINTS_CHANGED);
            try {
                listener.onListenerHintsChanged(hints, token);
            } catch (RemoteException ex) {
                Slog.e(TAG, "unable to notify listener (listener hints): " + info, ex);
            }
        }

        @VisibleForTesting
        void notifyInterruptionFilterChanged(ManagedServiceInfo info,
                int interruptionFilter) {
            final INotificationListener listener = (INotificationListener) info.service;
            final long token = getDispatchReportingToken(info,
                    BINDER_TAG_ON_INTERRUPTION_FILTER_CHANGED);
            try {
                listener.onInterruptionFilterChanged(interruptionFilter, token);
            } catch (RemoteException ex) {
                Slog.e(TAG, "unable to notify listener (interruption filter): " + info, ex);
            }
        }

        void notifyNotificationChannelChanged(ManagedServiceInfo info,
                final String pkg, final UserHandle user, final NotificationChannel channel,
                final int modificationType) {
            final INotificationListener listener = (INotificationListener) info.service;
            final long token = getDispatchReportingToken(info,
                    BINDER_TAG_ON_NOTIFICATION_CHANNEL_MODIFICATION);
            try {
                listener.onNotificationChannelModification(pkg, user, channel, modificationType,
                        token);
            } catch (RemoteException ex) {
                Slog.e(TAG, "unable to notify listener (channel changed): " + info, ex);
            }
        }

        @VisibleForTesting
        void notifyNotificationChannelGroupChanged(ManagedServiceInfo info,
                final String pkg, final UserHandle user, final NotificationChannelGroup group,
                final int modificationType) {
            final INotificationListener listener = (INotificationListener) info.getService();
            final long token = getDispatchReportingToken(info,
                    BINDER_TAG_ON_NOTIFICATION_CHANNEL_GROUP_MODIFICATION);
            try {
                listener.onNotificationChannelGroupModification(pkg, user, group, modificationType,
                        token);
            } catch (RemoteException ex) {
                Slog.e(TAG, "unable to notify listener (channel group changed): " + info, ex);
            }
        }

        public boolean isListenerPackage(String packageName) {
            if (packageName == null) {
                return false;
            }
            // TODO: clean up locking object later
            synchronized (mNotificationLock) {
                for (final ManagedServiceInfo serviceInfo : getServices()) {
                    if (packageName.equals(serviceInfo.component.getPackageName())) {
                        return true;
                    }
                }
            }
            return false;
        }

        // Returns whether there is a component with listener access granted that is associated
        // with the given package name / user ID.
        boolean hasAllowedListener(String packageName, int userId) {
            if (packageName == null) {
                return false;
            }

            // Loop through allowed components to compare package names
            List<ComponentName> allowedComponents = getAllowedComponents(userId);
            for (int i = 0; i < allowedComponents.size(); i++) {
                if (allowedComponents.get(i).getPackageName().equals(packageName)) {
                    return true;
                }
            }
            return false;
        }
    }

    @GuardedBy("mNotificationLock")
    private void broadcastToCallNotificationEventCallbacks(
            final RemoteCallbackList<ICallNotificationEventCallback> callbackList,
            final NotificationRecord r,
            boolean isPosted) {
        if (callbackList != null) {
            int numCallbacks = callbackList.beginBroadcast();
            try {
                for (int i = 0; i < numCallbacks; i++) {
                    if (isPosted) {
                        callbackList.getBroadcastItem(i)
                                .onCallNotificationPosted(r.getSbn().getPackageName(), r.getUser());
                    } else {
                        callbackList.getBroadcastItem(i)
                                .onCallNotificationRemoved(r.getSbn().getPackageName(),
                                    r.getUser());
                    }
                }
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
            callbackList.finishBroadcast();
        }
    }

    @GuardedBy("mNotificationLock")
    void notifyCallNotificationEventListenerOnPosted(final NotificationRecord r) {
        if (!r.getNotification().isStyle(Notification.CallStyle.class)) {
            return;
        }

        synchronized (mCallNotificationEventCallbacks) {
            ArrayMap<Integer, RemoteCallbackList<ICallNotificationEventCallback>>
                    callbacksForPackage =
                    mCallNotificationEventCallbacks.get(r.getSbn().getPackageName());
            if (callbacksForPackage == null) {
                return;
            }

            if (!r.getUser().equals(UserHandle.ALL)) {
                broadcastToCallNotificationEventCallbacks(
                        callbacksForPackage.get(r.getUser().getIdentifier()), r, true);
                // Also notify the listeners registered for USER_ALL
                broadcastToCallNotificationEventCallbacks(callbacksForPackage.get(USER_ALL), r,
                        true);
            } else {
                // Notify listeners registered for any userId
                for (RemoteCallbackList<ICallNotificationEventCallback> callbackList
                        : callbacksForPackage.values()) {
                    broadcastToCallNotificationEventCallbacks(callbackList, r, true);
                }
            }
        }
    }

    @GuardedBy("mNotificationLock")
    void notifyCallNotificationEventListenerOnRemoved(final NotificationRecord r) {
        if (!r.getNotification().isStyle(Notification.CallStyle.class)) {
            return;
        }

        synchronized (mCallNotificationEventCallbacks) {
            ArrayMap<Integer, RemoteCallbackList<ICallNotificationEventCallback>>
                    callbacksForPackage =
                    mCallNotificationEventCallbacks.get(r.getSbn().getPackageName());
            if (callbacksForPackage == null) {
                return;
            }

            if (!r.getUser().equals(UserHandle.ALL)) {
                broadcastToCallNotificationEventCallbacks(
                        callbacksForPackage.get(r.getUser().getIdentifier()), r, false);
                // Also notify the listeners registered for USER_ALL
                broadcastToCallNotificationEventCallbacks(callbacksForPackage.get(USER_ALL), r,
                        false);
            } else {
                // Notify listeners registered for any userId
                for (RemoteCallbackList<ICallNotificationEventCallback> callbackList
                        : callbacksForPackage.values()) {
                    broadcastToCallNotificationEventCallbacks(callbackList, r, false);
                }
            }
        }
    }

    // TODO (b/194833441): remove when we've fully migrated to a permission
    class RoleObserver implements OnRoleHoldersChangedListener {
        // Role name : user id : list of approved packages
        private ArrayMap<String, ArrayMap<Integer, ArraySet<String>>> mNonBlockableDefaultApps;

        /**
         * Writes should be pretty rare (only when default browser changes) and reads are done
         * during activity start code-path, so we're optimizing for reads. This means this set is
         * immutable once written and we'll recreate the set every time there is a role change and
         * then assign that new set to the volatile below, so reads can be done without needing to
         * hold a lock. Every write is done on the main-thread, so write atomicity is guaranteed.
         *
         * Didn't use unmodifiable set to enforce immutability to avoid iterating via iterators.
         */
        private volatile ArraySet<Integer> mTrampolineExemptUids = new ArraySet<>();

        private final RoleManager mRm;
        private final IPackageManager mPm;
        private final Executor mExecutor;
        private final Looper mMainLooper;

        RoleObserver(Context context, @NonNull RoleManager roleManager,
                @NonNull IPackageManager pkgMgr, @NonNull Looper mainLooper) {
            mRm = roleManager;
            mPm = pkgMgr;
            mExecutor = context.getMainExecutor();
            mMainLooper = mainLooper;
        }

        /** Should be called from the main-thread. */
        @MainThread
        public void init() {
            List<UserHandle> users = mUm.getUserHandles(/* excludeDying */ true);
            mNonBlockableDefaultApps = new ArrayMap<>();
            for (int i = 0; i < NON_BLOCKABLE_DEFAULT_ROLES.length; i++) {
                String role = NON_BLOCKABLE_DEFAULT_ROLES[i];
                final ArrayMap<Integer, ArraySet<String>> userToApprovedList = new ArrayMap<>();
                mNonBlockableDefaultApps.put(role, userToApprovedList);
                for (int j = 0; j < users.size(); j++) {
                    int userId = users.get(j).getIdentifier();
                    ArraySet<String> approvedForUserId = new ArraySet<>(mRm.getRoleHoldersAsUser(
                            role, UserHandle.of(userId)));
                    ArraySet<Pair<String, Integer>> approvedAppUids = new ArraySet<>();
                    for (String pkg : approvedForUserId) {
                        int uid = getUidForPackage(pkg, userId);
                        if (uid != INVALID_UID) {
                            approvedAppUids.add(new Pair<>(pkg, uid));
                        } else {
                            Slog.e(TAG, "init: Invalid package for role " + role
                                    + " (user " + userId + "): " + pkg);
                        }
                    }
                    userToApprovedList.put(userId, approvedForUserId);
                    mPreferencesHelper.updateDefaultApps(userId, null, approvedAppUids);
                }
            }
            updateTrampolineExemptUidsForUsers(users.toArray(new UserHandle[0]));
            mRm.addOnRoleHoldersChangedListenerAsUser(mExecutor, this, UserHandle.ALL);
        }

        void destroy() {
            mRm.removeOnRoleHoldersChangedListenerAsUser(this, UserHandle.ALL);
        }

        @VisibleForTesting
        public boolean isApprovedPackageForRoleForUser(String role, String pkg, int userId) {
            return mNonBlockableDefaultApps.get(role).get(userId).contains(pkg);
        }

        @VisibleForTesting
        public boolean isUidExemptFromTrampolineRestrictions(int uid) {
            return mTrampolineExemptUids.contains(uid);
        }

        /**
         * Convert the assistant-role holder into settings. The rest of the system uses the
         * settings.
         *
         * @param roleName the name of the role whose holders are changed
         * @param user the user for this role holder change
         */
        @Override
        public void onRoleHoldersChanged(@NonNull String roleName, @NonNull UserHandle user) {
            onRoleHoldersChangedForNonBlockableDefaultApps(roleName, user);
            onRoleHoldersChangedForTrampolines(roleName, user);
        }

        private void onRoleHoldersChangedForNonBlockableDefaultApps(@NonNull String roleName,
                @NonNull UserHandle user) {
            // we only care about a couple of the roles they'll tell us about
            boolean relevantChange = false;
            for (int i = 0; i < NON_BLOCKABLE_DEFAULT_ROLES.length; i++) {
                if (NON_BLOCKABLE_DEFAULT_ROLES[i].equals(roleName)) {
                    relevantChange = true;
                    break;
                }
            }

            if (!relevantChange) {
                return;
            }

            ArraySet<String> roleHolders = new ArraySet<>(mRm.getRoleHoldersAsUser(roleName, user));

            // find the diff
            ArrayMap<Integer, ArraySet<String>> prevApprovedForRole =
                    mNonBlockableDefaultApps.getOrDefault(roleName, new ArrayMap<>());
            ArraySet<String> previouslyApproved =
                    prevApprovedForRole.getOrDefault(user.getIdentifier(), new ArraySet<>());

            ArraySet<String> toRemove = new ArraySet<>();
            ArraySet<Pair<String, Integer>> toAdd = new ArraySet<>();

            for (String previous : previouslyApproved) {
                if (!roleHolders.contains(previous)) {
                    toRemove.add(previous);
                }
            }
            for (String nowApproved : roleHolders) {
                if (!previouslyApproved.contains(nowApproved)) {
                    int uid = getUidForPackage(nowApproved, user.getIdentifier());
                    if (uid != INVALID_UID) {
                        toAdd.add(new Pair<>(nowApproved, uid));
                    } else {
                        Slog.e(TAG, "onRoleHoldersChanged: Invalid package for role " + roleName
                                + " (user " + user.getIdentifier() + "): " + nowApproved);
                    }
                }
            }

            // store newly approved apps
            prevApprovedForRole.put(user.getIdentifier(), roleHolders);
            mNonBlockableDefaultApps.put(roleName, prevApprovedForRole);

            // update what apps can be blocked
            mPreferencesHelper.updateDefaultApps(user.getIdentifier(), toRemove, toAdd);

            // RoleManager is the source of truth for this data so we don't need to trigger a
            // write of the notification policy xml for this change
        }

        private void onRoleHoldersChangedForTrampolines(@NonNull String roleName,
                @NonNull UserHandle user) {
            if (!RoleManager.ROLE_BROWSER.equals(roleName)) {
                return;
            }
            updateTrampolineExemptUidsForUsers(user);
        }

        private void updateTrampolineExemptUidsForUsers(UserHandle... users) {
            Preconditions.checkState(mMainLooper.isCurrentThread());
            ArraySet<Integer> oldUids = mTrampolineExemptUids;
            ArraySet<Integer> newUids = new ArraySet<>();
            // Add the uids from previous set for the users that we won't update.
            for (int i = 0, n = oldUids.size(); i < n; i++) {
                int uid = oldUids.valueAt(i);
                UserHandle user = UserHandle.of(UserHandle.getUserId(uid));
                if (!ArrayUtils.contains(users, user)) {
                    newUids.add(uid);
                }
            }
            // Now lookup the new uids for the users that we want to update.
            for (int i = 0, n = users.length; i < n; i++) {
                UserHandle user = users[i];
                for (String pkg : mRm.getRoleHoldersAsUser(RoleManager.ROLE_BROWSER, user)) {
                    int uid = getUidForPackage(pkg, user.getIdentifier());
                    if (uid != INVALID_UID) {
                        newUids.add(uid);
                    } else {
                        Slog.e(TAG, "updateTrampoline: Invalid package for role "
                                + RoleManager.ROLE_BROWSER + " (user " + user.getIdentifier()
                                + "): " + pkg);
                    }
                }
            }
            mTrampolineExemptUids = newUids;
        }

        private int getUidForPackage(String pkg, int userId) {
            try {
                return mPm.getPackageUid(pkg, MATCH_ALL, userId);
            } catch (RemoteException e) {
                Slog.e(TAG, "role manager has bad default " + pkg + " " + userId);
            }
            return INVALID_UID;
        }
    }

    public static final class DumpFilter {
        public boolean filtered = false;
        public String pkgFilter;
        public boolean zen;
        public long since;
        public boolean stats;
        public boolean rvStats;
        public boolean redact = true;
        public boolean proto = false;
        public boolean criticalPriority = false;
        public boolean normalPriority = false;

        @NonNull
        public static DumpFilter parseFromArguments(String[] args) {
            final DumpFilter filter = new DumpFilter();
            for (int ai = 0; ai < args.length; ai++) {
                final String a = args[ai];
                if ("--proto".equals(a)) {
                    filter.proto = true;
                } else if ("--noredact".equals(a) || "--reveal".equals(a)) {
                    filter.redact = false;
                } else if ("p".equals(a) || "pkg".equals(a) || "--package".equals(a)) {
                    if (ai < args.length-1) {
                        ai++;
                        filter.pkgFilter = args[ai].trim().toLowerCase();
                        if (filter.pkgFilter.isEmpty()) {
                            filter.pkgFilter = null;
                        } else {
                            filter.filtered = true;
                        }
                    }
                } else if ("--zen".equals(a) || "zen".equals(a)) {
                    filter.filtered = true;
                    filter.zen = true;
                } else if ("--stats".equals(a)) {
                    filter.stats = true;
                    if (ai < args.length-1) {
                        ai++;
                        filter.since = Long.parseLong(args[ai]);
                    } else {
                        filter.since = 0;
                    }
                } else if ("--remote-view-stats".equals(a)) {
                    filter.rvStats = true;
                    if (ai < args.length-1) {
                        ai++;
                        filter.since = Long.parseLong(args[ai]);
                    } else {
                        filter.since = 0;
                    }
                } else if (PRIORITY_ARG.equals(a)) {
                    // Bugreport will call the service twice with priority arguments, first to dump
                    // critical sections and then non critical ones. Set appropriate filters
                    // to generate the desired data.
                    if (ai < args.length - 1) {
                        ai++;
                        switch (args[ai]) {
                            case PRIORITY_ARG_CRITICAL:
                                filter.criticalPriority = true;
                                break;
                            case PRIORITY_ARG_NORMAL:
                                filter.normalPriority = true;
                                break;
                        }
                    }
                }
            }
            return filter;
        }

        public boolean matches(StatusBarNotification sbn) {
            if (!filtered) return true;
            return zen ? true : sbn != null
                    && (matches(sbn.getPackageName()) || matches(sbn.getOpPkg()));
        }

        public boolean matches(ComponentName component) {
            if (!filtered) return true;
            return zen ? true : component != null && matches(component.getPackageName());
        }

        public boolean matches(String pkg) {
            if (!filtered) return true;
            return zen ? true : pkg != null && pkg.toLowerCase().contains(pkgFilter);
        }

        @Override
        public String toString() {
            return stats ? "stats" : zen ? "zen" : ('\'' + pkgFilter + '\'');
        }
    }

    @VisibleForTesting
    void resetAssistantUserSet(int userId) {
        checkCallerIsSystemOrShell();
        mAssistants.setUserSet(userId, false);
        handleSavePolicyFile();
    }

    @VisibleForTesting
    @Nullable
    ComponentName getApprovedAssistant(int userId) {
        checkCallerIsSystemOrShell();
        List<ComponentName> allowedComponents = mAssistants.getAllowedComponents(userId);
        return CollectionUtils.firstOrNull(allowedComponents);
    }

    private void writeSecureNotificationsPolicy(TypedXmlSerializer out) throws IOException {
        out.startTag(null, LOCKSCREEN_ALLOW_SECURE_NOTIFICATIONS_TAG);
        out.attributeBoolean(null, LOCKSCREEN_ALLOW_SECURE_NOTIFICATIONS_VALUE,
                mLockScreenAllowSecureNotifications);
        out.endTag(null, LOCKSCREEN_ALLOW_SECURE_NOTIFICATIONS_TAG);
    }

    // Creates a notification that informs the user about changes due to the migration to
    // use permissions for notifications.
    protected Notification createReviewPermissionsNotification() {
        int title = R.string.review_notification_settings_title;
        int content = R.string.review_notification_settings_text;

        // Tapping on the notification leads to the settings screen for managing app notifications,
        // using the intent reserved for system services to indicate it comes from this notification
        Intent tapIntent = new Intent(Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS_FOR_REVIEW);
        Intent remindIntent = new Intent(REVIEW_NOTIF_ACTION_REMIND);
        Intent dismissIntent = new Intent(REVIEW_NOTIF_ACTION_DISMISS);
        Intent swipeIntent = new Intent(REVIEW_NOTIF_ACTION_CANCELED);

        // Both "remind me" and "dismiss" actions will be actions received by the BroadcastReceiver
        final Notification.Action remindMe = new Notification.Action.Builder(null,
                getContext().getResources().getString(
                        R.string.review_notification_settings_remind_me_action),
                PendingIntent.getBroadcast(
                        getContext(), 0, remindIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .build();
        final Notification.Action dismiss = new Notification.Action.Builder(null,
                getContext().getResources().getString(
                        R.string.review_notification_settings_dismiss),
                PendingIntent.getBroadcast(
                        getContext(), 0, dismissIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .build();

        return new Notification.Builder(getContext(), SystemNotificationChannels.SYSTEM_CHANGES)
                .setSmallIcon(R.drawable.stat_sys_adb)
                .setContentTitle(getContext().getResources().getString(title))
                .setContentText(getContext().getResources().getString(content))
                .setContentIntent(PendingIntent.getActivity(getContext(), 0, tapIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setStyle(new Notification.BigTextStyle())
                .setFlag(FLAG_NO_CLEAR, true)
                .setAutoCancel(true)
                .addAction(remindMe)
                .addAction(dismiss)
                .setDeleteIntent(PendingIntent.getBroadcast(getContext(), 0, swipeIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .build();
    }

    protected void maybeShowInitialReviewPermissionsNotification() {
        if (!mShowReviewPermissionsNotification) {
            // if this notification is disabled by settings do not ever show it
            return;
        }

        int currentState = Settings.Global.getInt(getContext().getContentResolver(),
                Settings.Global.REVIEW_PERMISSIONS_NOTIFICATION_STATE,
                REVIEW_NOTIF_STATE_UNKNOWN);

        // now check the last known state of the notification -- this determination of whether the
        // user is in the correct target audience occurs elsewhere, and will have written the
        // REVIEW_NOTIF_STATE_SHOULD_SHOW to indicate it should be shown in the future.
        //
        // alternatively, if the user has rescheduled the notification (so it has been shown
        // again) but not yet interacted with the new notification, then show it again on boot,
        // as this state indicates that the user had the notification open before rebooting.
        //
        // sending the notification here does not record a new state for the notification;
        // that will be written by parts of the system further down the line if at any point
        // the user interacts with the notification.
        if (currentState == REVIEW_NOTIF_STATE_SHOULD_SHOW
                || currentState == REVIEW_NOTIF_STATE_RESHOWN) {
            NotificationManager nm = getContext().getSystemService(NotificationManager.class);
            nm.notify(TAG,
                    SystemMessageProto.SystemMessage.NOTE_REVIEW_NOTIFICATION_PERMISSIONS,
                    createReviewPermissionsNotification());
        }
    }

    /**
     * Shows a warning on logcat. Shows the toast only once per package. This is to avoid being too
     * aggressive and annoying the user.
     *
     * TODO(b/161957908): Remove dogfooder toast.
     */
    private class NotificationTrampolineCallback implements BackgroundActivityStartCallback {
        @Override
        public BackgroundActivityStartCallbackResult isActivityStartAllowed(
                Collection<IBinder> tokens, int uid, String packageName) {
            checkArgument(!tokens.isEmpty());
            for (IBinder token : tokens) {
                if (token != ALLOWLIST_TOKEN) {
                    // We only block or warn if the start is exclusively due to notification
                    return RESULT_TRUE;
                }
            }
            String logcatMessage =
                    "Indirect notification activity start (trampoline) from " + packageName;
            if (blockTrampoline(uid)) {
                Slog.e(TAG, logcatMessage + " blocked");
                return RESULT_FALSE;
            } else {
                Slog.w(TAG, logcatMessage + ", this should be avoided for performance reasons");
                return new BackgroundActivityStartCallbackResult(true, ALLOWLIST_TOKEN);
            }
        }

        private boolean blockTrampoline(int uid) {
            if (mRoleObserver != null && mRoleObserver.isUidExemptFromTrampolineRestrictions(uid)) {
                return CompatChanges.isChangeEnabled(NOTIFICATION_TRAMPOLINE_BLOCK_FOR_EXEMPT_ROLES,
                        uid);
            }
            return CompatChanges.isChangeEnabled(NOTIFICATION_TRAMPOLINE_BLOCK, uid);
        }

        @Override
        public boolean canCloseSystemDialogs(Collection<IBinder> tokens, int uid) {
            // If the start is allowed via notification, we allow the app to close system dialogs
            // only if their targetSdk < S, otherwise they have no valid reason to do this since
            // trampolines are blocked.
            return tokens.contains(ALLOWLIST_TOKEN)
                    && !CompatChanges.isChangeEnabled(NOTIFICATION_TRAMPOLINE_BLOCK, uid);
        }
    }

    interface PreferencesHelperFactory {
        default PreferencesHelper newHelper(Context context, PackageManager pm,
                RankingHandler rankingHandler, ZenModeHelper zenHelper, PermissionHelper permHelper,
                PermissionManager permManager, NotificationChannelLogger notificationChannelLogger,
                AppOpsManager appOpsManager, ManagedServices.UserProfiles userProfiles,
                UriGrantsManagerInternal ugmInternal, boolean showReviewPermissionsNotification,
                Clock clock, NotificationManagerPrivate nmPrivate) {
            return new PreferencesHelper(context, pm, rankingHandler, zenHelper, permHelper,
                    permManager, notificationChannelLogger, appOpsManager, userProfiles,
                    ugmInternal, showReviewPermissionsNotification, clock, nmPrivate);
        }
    }

    interface PostNotificationTrackerFactory {
        default PostNotificationTracker newTracker(@Nullable WakeLock optionalWakelock) {
            return new PostNotificationTracker(optionalWakelock);
        }
        default PostNotificationTracker newTracker(@Nullable WakeLock optionalWakelock,
                String key) {
            return new PostNotificationTracker(optionalWakelock, key);
        }
    }

    static class PostNotificationTracker {
        @ElapsedRealtimeLong private final long mStartTime;
        @Nullable private final WakeLock mWakeLock;
        private boolean mOngoing;
        String mKey;
        private final List<Runnable> mCleanupRunnables;

        @VisibleForTesting
        PostNotificationTracker(@Nullable WakeLock wakeLock, String key) {
            this(wakeLock);
            mKey = key;
        }

        @VisibleForTesting
        PostNotificationTracker(@Nullable WakeLock wakeLock) {
            mStartTime = SystemClock.elapsedRealtime();
            mWakeLock = wakeLock;
            mOngoing = true;
            mCleanupRunnables = new ArrayList<Runnable>();
            if (DBG) {
                Slog.d(TAG, "PostNotification: Started");
            }
        }

        void addCleanupRunnable(Runnable runnable) {
            mCleanupRunnables.add(runnable);
        }

        @ElapsedRealtimeLong
        long getStartTime() {
            return mStartTime;
        }

        @VisibleForTesting
        boolean isOngoing() {
            return mOngoing;
        }

        /**
         * Cancels the tracker (releasing the acquired WakeLock) and runs any set cleanup runnables.
         * Either {@link #finish} or {@link #cancel} (exclusively) should be called on this object
         * before it's discarded.
         */
        void cancel() {
            if (!isOngoing()) {
                Log.wtfStack(TAG, "cancel() called on already-finished tracker");
                return;
            }
            mOngoing = false;
            if (mWakeLock != null) {
                Binder.withCleanCallingIdentity(() -> mWakeLock.release());
            }
            for (Runnable r : mCleanupRunnables) {
                r.run();
            }
            if (DBG) {
                long elapsedTime = SystemClock.elapsedRealtime() - mStartTime;
                Slog.d(TAG, TextUtils.formatSimple("PostNotification: Abandoned after %d ms",
                        elapsedTime));
            }
        }

        /**
         * Finishes the tracker (releasing the acquired WakeLock), runs any set cleanup runnables,
         * and returns the time elapsed since the operation started, in milliseconds.
         * Either {@link #finish} or {@link #cancel} (exclusively) should be called on this object
         * before it's discarded.
         */
        @DurationMillisLong
        long finish() {
            long elapsedTime = SystemClock.elapsedRealtime() - mStartTime;
            if (!isOngoing()) {
                Log.wtfStack(TAG, "finish() called on already-finished tracker");
                return elapsedTime;
            }
            mOngoing = false;
            if (mWakeLock != null) {
                Binder.withCleanCallingIdentity(() -> mWakeLock.release());
            }
            for (Runnable r : mCleanupRunnables) {
                r.run();
            }
            if (DBG) {
                Slog.d(TAG,
                        TextUtils.formatSimple("PostNotification: Finished in %d ms", elapsedTime));
            }
            return elapsedTime;
        }
    }
}
