/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.packageinstaller.v2.model

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.Flags
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstaller.DEVELOPER_VERIFICATION_USER_RESPONSE_ABORT
import android.content.pm.PackageInstaller.DEVELOPER_VERIFICATION_USER_RESPONSE_ERROR
import android.content.pm.PackageInstaller.DEVELOPER_VERIFICATION_USER_RESPONSE_INSTALL_ANYWAY
import android.content.pm.PackageInstaller.DEVELOPER_VERIFICATION_USER_RESPONSE_RETRY
import android.content.pm.PackageInstaller.DeveloperVerificationUserConfirmationInfo
import android.content.pm.PackageInstaller.SessionInfo
import android.content.pm.PackageInstaller.SessionParams
import android.content.pm.PackageManager
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.UserManager
import android.text.TextUtils
import android.util.EventLog
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.android.packageinstaller.common.EventResultPersister
import com.android.packageinstaller.common.EventResultPersister.OutOfIdsException
import com.android.packageinstaller.common.InstallEventReceiver
import com.android.packageinstaller.stats.StatsdLogger
import com.android.packageinstaller.stats.PiaStagesLatencyTracker
import com.android.packageinstaller.v2.model.InstallAborted.Companion.ABORT_REASON_DONE
import com.android.packageinstaller.v2.model.InstallAborted.Companion.ABORT_REASON_INTERNAL_ERROR
import com.android.packageinstaller.v2.model.InstallAborted.Companion.ABORT_REASON_POLICY
import com.android.packageinstaller.v2.model.InstallAborted.Companion.DLG_NONE
import com.android.packageinstaller.v2.model.InstallAborted.Companion.DLG_PACKAGE_ERROR
import com.android.packageinstaller.v2.model.InstallUserActionRequired.Companion.USER_ACTION_REASON_ANONYMOUS_SOURCE
import com.android.packageinstaller.v2.model.InstallUserActionRequired.Companion.USER_ACTION_REASON_INSTALL_CONFIRMATION
import com.android.packageinstaller.v2.model.InstallUserActionRequired.Companion.USER_ACTION_REASON_UNKNOWN_SOURCE
import com.android.packageinstaller.v2.model.InstallUserActionRequired.Companion.USER_ACTION_REASON_VERIFICATION_CONFIRMATION
import com.android.packageinstaller.v2.model.PackageUtil.canPackageQuery
import com.android.packageinstaller.v2.model.PackageUtil.generateStubPackageInfo
import com.android.packageinstaller.v2.model.PackageUtil.getAppSnippet
import com.android.packageinstaller.v2.model.PackageUtil.getPackageInfo
import com.android.packageinstaller.v2.model.PackageUtil.getPackageNameForUid
import com.android.packageinstaller.v2.model.PackageUtil.isCallerSessionOwner
import com.android.packageinstaller.v2.model.PackageUtil.isInstallPermissionGrantedOrRequested
import com.android.packageinstaller.v2.model.PackageUtil.isPermissionGranted
import com.android.packageinstaller.v2.model.PackageUtil.localLogv
import java.io.File
import java.io.IOException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
class InstallRepository(private val context: Context) : EventResultPersister.EventResultObserver {

    private val packageManager: PackageManager = context.packageManager
    private val packageInstaller: PackageInstaller = packageManager.packageInstaller
    private val userManager: UserManager? = context.getSystemService(UserManager::class.java)
    private val devicePolicyManager: DevicePolicyManager? =
        context.getSystemService(DevicePolicyManager::class.java)
    private val appOpsManager: AppOpsManager? = context.getSystemService(AppOpsManager::class.java)
    private var isSessionInstall = false
    private var isTrustedSource = false
    private var isAppUpdating = false
    private val _stagingResult = MutableLiveData<InstallStage>()
    val stagingResult: LiveData<InstallStage>
        get() = _stagingResult
    private val _installResult = MutableLiveData<InstallStage>()
    val installResult: LiveData<InstallStage>
        get() = _installResult

    val piaStagesLatencyTracker: PiaStagesLatencyTracker = PiaStagesLatencyTracker(
        StatsdLogger()
    )

    /**
     * Session ID for a session created when caller uses PackageInstaller APIs
     */
    private var sessionId = SessionInfo.INVALID_ID

    /**
     * Session ID for a session created by this app
     */
    var stagedSessionId = SessionInfo.INVALID_ID
        private set

    /**
     * UID of the last caller of Pia. This can point to a 3P installer if it uses intents to install
     * an APK, or receives a
     * [STATUS_PENDING_USER_ACTION][PackageInstaller.STATUS_PENDING_USER_ACTION] status code.
     * It may point to Pia, when it receives the STATUS_PENDING_USER_ACTION status code in case of
     * an update-ownership change.
     */
    private var callingUid = Process.INVALID_UID

    /**
     * UID of the origin of the installation. This UID is used to fetch the app-label of the
     * source of the install, and also check whether the source app has the AppOp to install other
     * apps.
     */
    private var originatingUid = Process.INVALID_UID
    /**
     * UID of the origin of the installation from the sessionInfo. This UID is used to fetch the
     * update-ownership app-label of the source of the install, and also check whether the source
     * app has the AppOp to install other apps.
     */
    private var originatingUidFromSessionInfo = Process.INVALID_UID
    private var callingPackage: String? = null
    private var sessionStager: SessionStager? = null
    private lateinit var intent: Intent
    private lateinit var appOpRequestInfo: AppOpRequestInfo
    private lateinit var appSnippet: PackageUtil.AppSnippet
    private lateinit var stagingJob: Job

    /**
     * PackageInfo of the app being installed on device.
     */
    private var newPackageInfo: PackageInfo? = null
    private var wasUserConfirmationTriggeredByPia = false

    /**
     * Extracts information from the incoming install intent, checks caller's permission to install
     * packages, verifies that the caller is the install session owner (in case of a session based
     * install) and checks if the current user has restrictions set that prevent app installation,
     *
     * @param intent the incoming [Intent] object for installing a package
     * @param callerInfo [CallerInfo] that holds the callingUid and callingPackageName
     * @return
     *  * [InstallAborted] if there are errors while performing the checks
     *  * [InstallStaging] after successfully performing the checks
     */
    fun performPreInstallChecks(intent: Intent, callerInfo: CallerInfo): InstallStage {
        this.intent = intent

        var callingAttributionTag: String? = null

        val isConfirmDeveloperVerificationAction = (Flags.verificationService()
                && PackageInstaller.ACTION_CONFIRM_DEVELOPER_VERIFICATION == intent.action)

        isSessionInstall =
            PackageInstaller.ACTION_CONFIRM_PRE_APPROVAL == intent.action
                || PackageInstaller.ACTION_CONFIRM_INSTALL == intent.action
                || isConfirmDeveloperVerificationAction

        if (isSessionInstall) {
            sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, SessionInfo.INVALID_ID)
            piaStagesLatencyTracker.setSessionId(sessionId)
        } else {
            sessionId = SessionInfo.INVALID_ID
        }

        stagedSessionId = intent.getIntExtra(EXTRA_STAGED_SESSION_ID, SessionInfo.INVALID_ID)

        if (stagedSessionId != SessionInfo.INVALID_ID) {
            piaStagesLatencyTracker.setSessionId(stagedSessionId)
        }

        callingPackage = callerInfo.packageName
        callingUid = callerInfo.uid

        val sourceInfo: ApplicationInfo? = getSourceInfo(callingPackage)
        if (callingUid == Process.INVALID_UID && sourceInfo == null) {
            // Caller's identity could not be determined. Abort the install
            Log.e(LOG_TAG, "Cannot determine caller since UID is invalid and sourceInfo is null")
            return InstallAborted(ABORT_REASON_INTERNAL_ERROR)
        }

        // By default, the originatingUid is callingUid. If the caller is the system download
        // provider or the documents manager, we parse the originatingUid from the
        // Intent.EXTRA_ORIGINATING_UID. And we check the appOps permission for the originatingUid
        // later.
        originatingUid = callingUid
        if (PackageUtil.isDocumentsManager(context, callingUid)
            || PackageUtil.getSystemDownloadsProviderInfo(
                context.packageManager, callingUid) != null) {
            // The originating uid from the intent. We only trust/use this if it comes from either
            // the document manager app or the downloads provider. It may be Process.INVALID_UID if
            // the original owner App is not installed on the device now.
            originatingUid = intent.getIntExtra(Intent.EXTRA_ORIGINATING_UID, Process.INVALID_UID)
        }

        val sessionInfo: SessionInfo? =
            if (sessionId != SessionInfo.INVALID_ID)
                packageInstaller.getSessionInfo(sessionId)
            else null

        if (sessionInfo != null) {
            // For session-based installs, the size is already known by the system
            piaStagesLatencyTracker.setApkSize(sessionInfo.size.toInt())
        }

        // This case is launching the extra intent that is included in the failure result received
        // by the installer when the installation failed because of developer verification.
        // For this case, the session is already finished so there is no valid SessionInfo.
        // Only show the developer verification dialog without app snippet.
        if (isConfirmDeveloperVerificationAction && sessionInfo == null) {
            val failureReason = intent.getIntExtra(
                PackageInstaller.EXTRA_DEVELOPER_VERIFICATION_FAILURE_REASON,
                PackageInstaller.DEVELOPER_VERIFICATION_FAILED_REASON_UNKNOWN
            )
            val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
            val packageInfo = generateStubPackageInfo(packageName)
            isAppUpdating = isAppUpdating(packageInfo)
            return InstallVerificationFailure(failureReason, isAppUpdating)
        }

        if (sessionInfo != null) {
            callingAttributionTag = sessionInfo.installerAttributionTag
            if (sessionInfo.originatingUid != Process.INVALID_UID) {
                originatingUidFromSessionInfo = sessionInfo.originatingUid
            }
        }

        appOpRequestInfo = AppOpRequestInfo(
            getPackageNameForUid(context, originatingUid, callingPackage),
            originatingUid,
            callingAttributionTag
        )

        if (localLogv) {
            Log.i(
                LOG_TAG, "Intent: $intent\n" +
                    "sessionId: $sessionId\n" +
                    "staged sessionId: $stagedSessionId\n" +
                    "calling package: $callingPackage\n" +
                    "callingUid: $callingUid\n" +
                    "originatingUid: $originatingUid\n" +
                    "originatingUidFromSessionInfo: $originatingUidFromSessionInfo\n" +
                    "sourceInfo: $sourceInfo"
            )
        }

        if ((sessionId != SessionInfo.INVALID_ID
                && !isCallerSessionOwner(packageInstaller, callingUid, sessionId))
            || (stagedSessionId != SessionInfo.INVALID_ID
                && !isCallerSessionOwner(packageInstaller, Process.myUid(), stagedSessionId))
        ) {
            Log.e(
                LOG_TAG, "UID is not the owner of the session:\n" +
                    "CallingUid: $callingUid | SessionId: $sessionId\n" +
                    "My UID: ${Process.myUid()} | StagedSessionId: $stagedSessionId"
            )
            return InstallAborted(ABORT_REASON_INTERNAL_ERROR)
        }

        val isPrivilegedAndKnown = sourceInfo != null && sourceInfo.isPrivilegedApp &&
                intent.getBooleanExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, false)
        val isInstallPkgPermissionGranted = originatingUid != Process.INVALID_UID &&
                isPermissionGranted(context, Manifest.permission.INSTALL_PACKAGES, originatingUid)

        // Bypass the unknown source user restrictions check when either of the following
        // two conditions is met:
        // 1. An installer with the INSTALL_PACKAGES permission initiated the
        // installation via the PackageInstaller APIs and not via an
        // ACTION_VIEW or ACTION_INSTALL_PACKAGE intent.
        // 2. An installer is a privileged app and it has set the
        // EXTRA_NOT_UNKNOWN_SOURCE flag to be true in the intent.
        val isIntentInstall =
            Intent.ACTION_VIEW == intent.action
                    || Intent.ACTION_INSTALL_PACKAGE == intent.action

        isTrustedSource =
            (!isIntentInstall && isInstallPkgPermissionGranted) || isPrivilegedAndKnown

        // In general case, the originatingUid is callingUid. If callingUid is INVALID_UID, return
        // InstallAborted in the check above. When the originatingUid is INVALID_UID here, it means
        // the originatingUid is from the system download manager or the system documents manager,
        // and the package doesn't exist on the device. For this case, we don't need to check the
        // permission for the originatingUid. The package doesn't exist.
        if (originatingUid != Process.INVALID_UID
            && !isInstallPermissionGrantedOrRequested(context, originatingUid, isTrustedSource)) {
            return InstallAborted(ABORT_REASON_INTERNAL_ERROR)
        }

        val restriction = getDevicePolicyRestrictions(isTrustedSource)
        if (restriction != null) {
            val adminSupportDetailsIntent =
                devicePolicyManager!!.createAdminSupportIntent(restriction)
            Log.e(LOG_TAG, "$restriction set in place. Cannot install.")
            return InstallAborted(
                ABORT_REASON_POLICY, message = restriction, resultIntent = adminSupportDetailsIntent
            )
        }

        maybeRemoveInvalidInstallerPackageName(callerInfo)

        return InstallStaging()
    }

    /**
     * @return the ApplicationInfo for the installation source (the calling package), if available
     */
    private fun getSourceInfo(callingPackage: String?): ApplicationInfo? {
        return try {
            callingPackage?.let { packageManager.getApplicationInfo(it, 0) }
        } catch (ignored: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun getDevicePolicyRestrictions(isTrustedSource: Boolean): String? {
        val restrictions: Array<String> = if (isTrustedSource) {
            arrayOf(UserManager.DISALLOW_INSTALL_APPS)
        } else {
            arrayOf(
                UserManager.DISALLOW_INSTALL_APPS,
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY
            )
        }

        for (restriction in restrictions) {
            if (!userManager!!.hasUserRestrictionForUser(restriction, Process.myUserHandle())) {
                continue
            }
            return restriction
        }
        return null
    }

    private fun maybeRemoveInvalidInstallerPackageName(callerInfo: CallerInfo) {
        val installerPackageNameFromIntent =
            intent.getStringExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME) ?: return

        if (!TextUtils.equals(installerPackageNameFromIntent, callerInfo.packageName)
            && callerInfo.packageName != null
            && !isPermissionGranted(
                packageManager, Manifest.permission.INSTALL_PACKAGES, callerInfo.packageName
            )
        ) {
            Log.e(
                LOG_TAG, "The given installer package name $installerPackageNameFromIntent"
                    + " is invalid. Remove it."
            )
            EventLog.writeEvent(
                0x534e4554, "236687884", callerInfo.uid,
                "Invalid EXTRA_INSTALLER_PACKAGE_NAME"
            )
            intent.removeExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun stageForInstall() {
        if (!this::intent.isInitialized) {
            Log.e(LOG_TAG, "intent not initialized")
            _stagingResult.value = InstallAborted(ABORT_REASON_INTERNAL_ERROR)
            return
        }
        val uri = intent.data
        val action = intent.action

        if (PackageInstaller.ACTION_CONFIRM_DEVELOPER_VERIFICATION == action) {
            _stagingResult.value = InstallVerificationConfirmationRequired()
            return
        }
        if (stagedSessionId != SessionInfo.INVALID_ID
            || isSessionInstall
            || (uri != null && SCHEME_PACKAGE == uri.scheme)
        ) {
            // For a session based install or installing with a package:// URI, there is no file
            // for us to stage.
            _stagingResult.value = InstallReady()
            return
        }
        if (uri != null
            && ContentResolver.SCHEME_CONTENT == uri.scheme
            && canPackageQuery(context, callingUid, uri)
        ) {
            if (stagedSessionId > 0) {
                val info: SessionInfo? = packageInstaller.getSessionInfo(stagedSessionId)
                if (info == null || !info.isActive || info.resolvedBaseApkPath == null) {
                    Log.w(LOG_TAG, "Session $stagedSessionId in funky state; ignoring")
                    if (info != null) {
                        cleanupStagingSession()
                    }
                    stagedSessionId = 0
                }
            }

            // Session does not exist, or became invalid.
            if (stagedSessionId <= 0) {
                // Create session here to be able to show error.
                try {
                    context.contentResolver.openAssetFileDescriptor(uri, "r").use { afd ->
                        val pfd: ParcelFileDescriptor? = afd?.parcelFileDescriptor
                        val params: SessionParams =
                            createSessionParams(originatingUid, intent, pfd, uri.toString())
                        stagedSessionId = packageInstaller.createSession(params)
                        piaStagesLatencyTracker.setSessionId(stagedSessionId)
                    }
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Failed to create a staging session", e)
                    _stagingResult.value = InstallAborted(
                        ABORT_REASON_INTERNAL_ERROR,
                        resultIntent = Intent().putExtra(
                            Intent.EXTRA_INSTALL_RESULT, PackageManager.INSTALL_FAILED_INVALID_APK
                        ),
                        activityResultCode = Activity.RESULT_FIRST_USER,
                        errorDialogType = if (e is IOException) DLG_PACKAGE_ERROR else DLG_NONE
                    )
                    return
                }
            }

            sessionStager = SessionStager(context, uri, stagedSessionId, piaStagesLatencyTracker)
            stagingJob = GlobalScope.launch(Dispatchers.Main) {
                val wasFileStaged = sessionStager!!.execute()

                if (wasFileStaged) {
                    _stagingResult.value = InstallReady()
                } else {
                    cleanupStagingSession()
                    Log.e(LOG_TAG, "Could not stage APK.")
                    _stagingResult.value = InstallAborted(
                        ABORT_REASON_INTERNAL_ERROR,
                        resultIntent = Intent().putExtra(
                            Intent.EXTRA_INSTALL_RESULT, PackageManager.INSTALL_FAILED_INVALID_APK
                        ),
                        activityResultCode = Activity.RESULT_FIRST_USER
                    )
                }
            }
        } else {
            Log.e(LOG_TAG, "Invalid URI: ${if (uri == null) "null" else uri.scheme}")
            _stagingResult.value = InstallAborted(
                ABORT_REASON_INTERNAL_ERROR,
                resultIntent = Intent().putExtra(
                    Intent.EXTRA_INSTALL_RESULT, PackageManager.INSTALL_FAILED_INVALID_URI
                ),
                activityResultCode = Activity.RESULT_FIRST_USER
            )
        }
    }

    private fun cleanupStagingSession() {
        if (stagedSessionId > 0) {
            try {
                packageInstaller.abandonSession(stagedSessionId)
            } catch (ignored: SecurityException) {
            }
            stagedSessionId = 0
        }
    }

    private fun createSessionParams(
        originatingUid: Int,
        intent: Intent,
        pfd: ParcelFileDescriptor?,
        debugPathName: String,
    ): SessionParams {
        val params = SessionParams(SessionParams.MODE_FULL_INSTALL)
        val referrerUri = intent.getParcelableExtra(Intent.EXTRA_REFERRER, Uri::class.java)
        params.setPackageSource(
            if (referrerUri != null)
                PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
            else PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE
        )
        params.setInstallAsInstantApp(false)
        params.setReferrerUri(referrerUri)
        params.setOriginatingUri(
            intent.getParcelableExtra(Intent.EXTRA_ORIGINATING_URI, Uri::class.java)
        )
        if (originatingUid != Process.INVALID_UID) {
            params.setOriginatingUid(originatingUid)
        }
        params.setInstallerPackageName(intent.getStringExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME))
        params.setInstallReason(PackageManager.INSTALL_REASON_USER)
        // Disable full screen intent usage by for sideloads.
        params.setPermissionState(
            Manifest.permission.USE_FULL_SCREEN_INTENT, SessionParams.PERMISSION_STATE_DENIED
        )
        if (pfd != null) {
            try {
                val installInfo = packageInstaller.readInstallInfo(pfd, debugPathName, 0)
                params.setAppPackageName(installInfo.packageName)
                params.setInstallLocation(installInfo.installLocation)
                params.setSize(installInfo.calculateInstalledSize(params, pfd))
            } catch (e: PackageInstaller.PackageParsingException) {
                Log.e(LOG_TAG, "Cannot parse package $debugPathName. Assuming defaults.", e)
                params.setSize(pfd.statSize)
            } catch (e: IOException) {
                Log.e(
                    LOG_TAG, "Cannot calculate installed size $debugPathName. " +
                        "Try only apk size.", e
                )
            }
        } else {
            Log.e(LOG_TAG, "Cannot parse package $debugPathName. Assuming defaults.")
        }
        return params
    }

    /**
     * Processes Install session, file:// or package:// URI to generate data pertaining to user
     * confirmation for an install. This method also checks if the source app has the AppOp granted
     * to install unknown apps when {@code forceSourceCheck} is true. If an AppOp is to be
     * requested, cache the user action prompt data to be reused once appOp has been granted.
     *
     * When the identity of the install source could not be determined, user can skip checking the
     * source and directly proceed with the install by setting {@code forceSourceCheck} to false.
     *
     * @return
     *  * [InstallAborted]
     *      *  If install session is invalid (not sealed or resolvedBaseApk path is invalid)
     *      *  Source app doesn't have visibility to target app
     *      *  The APK is invalid
     *      *  URI is invalid
     *      *  Can't get ApplicationInfo for source app, to request AppOp
     *
     *  *  [InstallUserActionRequired]
     *      * If AppOP is granted and user action is required to proceed with install
     *      * If AppOp grant is to be requested from the user
     */
    fun requestUserConfirmation(forceSourceCheck: Boolean = true): InstallStage? {
        if (!this::intent.isInitialized) {
            Log.e(LOG_TAG, "intent not initialized")
            return InstallAborted(ABORT_REASON_INTERNAL_ERROR)
        }
        return maybeDeferUserConfirmation(forceSourceCheck)
    }

    /**
     *  If the update-owner for the incoming app is being changed, defer confirming with the
     *  user and directly proceed with the install. The system will request another
     *  user confirmation shortly.
     */
    private fun maybeDeferUserConfirmation(forceSourceCheck: Boolean = true): InstallStage? {
        // Returns InstallUserActionRequired stage if install details could be successfully
        // computed, else it returns InstallAborted.
        val confirmationSnippet: InstallStage = generateConfirmationSnippet()
        if (confirmationSnippet.stageCode == InstallStage.STAGE_ABORTED) {
            return confirmationSnippet
        }

        // check source is trusted or granted permission
        if (forceSourceCheck) {
            if (isTrustedSource) {
                if (localLogv) {
                    Log.i(LOG_TAG, "Install allowed")
                }
            } else {
                if (!this::appOpRequestInfo.isInitialized) {
                    Log.e(LOG_TAG, "appOpRequestInfo not initialized")
                    return InstallAborted(ABORT_REASON_INTERNAL_ERROR)
                }
                val unknownSourceStage = handleUnknownSources(appOpRequestInfo)
                if (unknownSourceStage.stageCode != InstallStage.STAGE_READY) {
                    return unknownSourceStage
                }
            }
        }

        val existingUpdateOwner: CharSequence? = getExistingUpdateOwner(newPackageInfo!!)
        return if (sessionId == SessionInfo.INVALID_ID &&
            !TextUtils.isEmpty(existingUpdateOwner) &&
            !TextUtils.equals(existingUpdateOwner, callingPackage)
        ) {
            // Since update ownership is being changed, the system will request another
            // user confirmation shortly. Thus, we don't need to ask the user to confirm
            // installation here.
            initiateInstall()
            null
        } else {
            confirmationSnippet
        }
    }

    private fun generateConfirmationSnippet(): InstallStage {
        val packageSource: Any?
        val pendingUserActionReason: Int

        if (PackageInstaller.ACTION_CONFIRM_INSTALL == intent.action) {
            val info = packageInstaller.getSessionInfo(sessionId)
            val resolvedPath = info?.resolvedBaseApkPath
            if (info == null || !info.isSealed || resolvedPath == null) {
                Log.e(LOG_TAG, "Session $sessionId in funky state; ignoring")
                return InstallAborted(ABORT_REASON_INTERNAL_ERROR)
            }
            packageSource = Uri.fromFile(File(resolvedPath))
            // TODO: Not sure where is this used yet. PIA.java passes it to
            //  InstallInstalling if not null
            // mOriginatingURI = null;
            // mReferrerURI = null;
            pendingUserActionReason = info.getPendingUserActionReason()
        } else if (PackageInstaller.ACTION_CONFIRM_PRE_APPROVAL == intent.action) {
            val info = packageInstaller.getSessionInfo(sessionId)
            if (info == null || !info.isPreApprovalRequested) {
                Log.e(LOG_TAG, "Session $sessionId in funky state; ignoring")
                return InstallAborted(ABORT_REASON_INTERNAL_ERROR)
            }
            packageSource = info
            // mOriginatingURI = null;
            // mReferrerURI = null;
            pendingUserActionReason = info.getPendingUserActionReason()
        } else if (PackageInstaller.ACTION_CONFIRM_DEVELOPER_VERIFICATION == intent.action) {
            val info = packageInstaller.getSessionInfo(sessionId)
            val resolvedPath = info?.resolvedBaseApkPath
            if (info == null || !info.isSealed || resolvedPath == null) {
                Log.e(LOG_TAG, "Session $sessionId in funky state; ignoring")
                return InstallAborted(ABORT_REASON_INTERNAL_ERROR)
            }
            packageSource = Uri.fromFile(File(resolvedPath))
            pendingUserActionReason = info.getPendingUserActionReason()
        } else {
            // Two possible origins:
            // 1. Installation with SCHEME_PACKAGE.
            // 2. Installation with "file://" for session created by this app
            packageSource =
                if (intent.data?.scheme == SCHEME_PACKAGE) {
                    intent.data
                } else {
                    val stagedSessionInfo = packageInstaller.getSessionInfo(stagedSessionId)
                    Uri.fromFile(File(stagedSessionInfo?.resolvedBaseApkPath!!))
                }
            // mOriginatingURI = mIntent.getParcelableExtra(Intent.EXTRA_ORIGINATING_URI);
            // mReferrerURI = mIntent.getParcelableExtra(Intent.EXTRA_REFERRER);
            pendingUserActionReason = PackageInstaller.REASON_CONFIRM_PACKAGE_CHANGE
        }

        // if there's nothing to do, quietly slip into the ether
        if (packageSource == null) {
            Log.e(LOG_TAG, "Unspecified source")
            return InstallAborted(
                ABORT_REASON_INTERNAL_ERROR,
                resultIntent = Intent().putExtra(
                    Intent.EXTRA_INSTALL_RESULT,
                    PackageManager.INSTALL_FAILED_INVALID_URI
                ),
                activityResultCode = Activity.RESULT_FIRST_USER
            )
        }
        return processAppSnippet(packageSource, pendingUserActionReason)
    }

    /**
     * Parse the Uri (post-commit install session) or use the SessionInfo (pre-commit install
     * session) to set up the installer for this install.
     *
     * @param source The source of package URI or SessionInfo
     * @return
     *  * [InstallUserActionRequired] if source could be processed
     *  * [InstallAborted] if source is invalid or there was an error is processing a source
     */
    private fun processAppSnippet(source: Any, userActionReason: Int): InstallStage {
        return when (source) {
            is Uri -> processPackageUri(source, userActionReason)
            is SessionInfo -> processSessionInfo(source, userActionReason)
            else -> InstallAborted(ABORT_REASON_INTERNAL_ERROR)
        }
    }

    /**
     * Parse the Uri and set up the installer for this package.
     *
     * @param packageUri The URI to parse
     * @return
     *  * [InstallUserActionRequired] if source could be processed
     *  * [InstallAborted] if source is invalid or there was an error is processing a source
     */
    private fun processPackageUri(packageUri: Uri, userActionReason: Int): InstallStage {
        val scheme = packageUri.scheme
        val packageName = packageUri.schemeSpecificPart
        if (scheme == null) {
            return InstallAborted(ABORT_REASON_INTERNAL_ERROR)
        }
        if (localLogv) {
            Log.i(LOG_TAG, "processPackageUri(): uri = $packageUri, scheme = $scheme")
        }
        when (scheme) {
            SCHEME_PACKAGE -> {
                for (handle in userManager!!.getUserHandles(true)) {
                    val pmForUser = context.createContextAsUser(handle, 0).packageManager
                    try {
                        if (pmForUser.canPackageQuery(callingPackage!!, packageName)) {
                            newPackageInfo = pmForUser.getPackageInfo(
                                packageName,
                                PackageManager.GET_PERMISSIONS
                                    or PackageManager.MATCH_UNINSTALLED_PACKAGES
                            )
                        }
                    } catch (ignored: PackageManager.NameNotFoundException) {
                    }
                }
                if (newPackageInfo == null) {
                    Log.e(
                        LOG_TAG, "Requested package " + packageUri.schemeSpecificPart
                            + " not available. Discontinuing installation"
                    )
                    return InstallAborted(
                        ABORT_REASON_INTERNAL_ERROR,
                        errorDialogType = DLG_PACKAGE_ERROR,
                        resultIntent = Intent().putExtra(
                            Intent.EXTRA_INSTALL_RESULT, PackageManager.INSTALL_FAILED_INVALID_APK
                        ),
                        activityResultCode = Activity.RESULT_FIRST_USER
                    )
                }
                appSnippet = getAppSnippet(context, newPackageInfo!!)
                if (localLogv) {
                    Log.i(LOG_TAG, "Created snippet for " + appSnippet.label)
                }
            }

            ContentResolver.SCHEME_FILE -> {
                val sourceFile = packageUri.path?.let { File(it) }
                newPackageInfo = sourceFile?.let {
                    getPackageInfo(context, it, PackageManager.GET_PERMISSIONS)
                }

                // Check for parse errors
                if (newPackageInfo == null) {
                    Log.w(
                        LOG_TAG, "Parse error when parsing manifest. " +
                            "Discontinuing installation"
                    )
                    return InstallAborted(
                        ABORT_REASON_INTERNAL_ERROR,
                        errorDialogType = DLG_PACKAGE_ERROR,
                        resultIntent = Intent().putExtra(
                            Intent.EXTRA_INSTALL_RESULT,
                            PackageManager.INSTALL_FAILED_INVALID_APK
                        ),
                        activityResultCode = Activity.RESULT_FIRST_USER
                    )
                }
                if (localLogv) {
                    Log.i(LOG_TAG, "Creating snippet for local file $sourceFile")
                }
                appSnippet = getAppSnippet(context, newPackageInfo!!, sourceFile!!)
            }

            else -> {
                Log.e(LOG_TAG, "Unexpected URI scheme $packageUri")
                return InstallAborted(ABORT_REASON_INTERNAL_ERROR)
            }
        }
        isAppUpdating = isAppUpdating(newPackageInfo)
        val (existingUpdateOwner, requestedUpdateOwner) =
            getUpdateOwners(newPackageInfo, userActionReason, isAppUpdating)

        return InstallUserActionRequired(USER_ACTION_REASON_INSTALL_CONFIRMATION, appSnippet,
            isAppUpdating, existingUpdateOwner, requestedUpdateOwner)
    }

    /**
     * Use the SessionInfo and set up the installer for pre-commit install session.
     *
     * @param sessionInfo The SessionInfo to compose
     * @return [InstallUserActionRequired]
     */
    private fun processSessionInfo(sessionInfo: SessionInfo, userActionReason: Int): InstallStage {
        newPackageInfo = generateStubPackageInfo(sessionInfo.getAppPackageName())
        appSnippet = getAppSnippet(context, sessionInfo)
        isAppUpdating = isAppUpdating(newPackageInfo)
        val (existingUpdateOwner, requestedUpdateOwner) =
            getUpdateOwners(newPackageInfo, userActionReason, isAppUpdating)

        return InstallUserActionRequired(USER_ACTION_REASON_INSTALL_CONFIRMATION, appSnippet,
            isAppUpdating, existingUpdateOwner, requestedUpdateOwner)
    }

    private fun getUpdateOwners(
        pkgInfo: PackageInfo?,
        userActionReason: Int,
        isAppUpdating: Boolean
    ): Pair<CharSequence?, CharSequence?> {
        if (pkgInfo == null) {
            return Pair(null, null)
        }

        val existingUpdateOwnerPackageName = getExistingUpdateOwner(pkgInfo)
        val existingUpdateOwnerLabel = PackageUtil.getApplicationLabel(
            context,
            existingUpdateOwnerPackageName.toString()
        )

        var requestedPackageName: CharSequence? = if (
            isAppUpdating &&
            !TextUtils.isEmpty(existingUpdateOwnerLabel) &&
            userActionReason == PackageInstaller.REASON_REMIND_OWNERSHIP
        ) {
            // In the update-ownership case, the callingUid is not from the download manager
            // or documents manager. The originatingUid should not be INVALID_UID, it should be
            // callingUid in this case. It is not INVALID_UID.
            var uid = originatingUidFromSessionInfo
            if (uid == Process.INVALID_UID) {
                uid = originatingUid
            }
            val originatingPackageName =
                getPackageNameForUid(context, uid, callingPackage)
            originatingPackageName
        } else {
            null
        }

        return Pair(existingUpdateOwnerPackageName, requestedPackageName)
    }

    private fun getExistingUpdateOwner(pkgInfo: PackageInfo): String? {
        return try {
            val packageName = pkgInfo.packageName
            val sourceInfo = packageManager.getInstallSourceInfo(packageName)
            sourceInfo.updateOwnerPackageName
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun isAppUpdating(newPkgInfo: PackageInfo?): Boolean {
        if (newPkgInfo == null) {
            return false
        }
        var pkgName = newPkgInfo.packageName
        // Check if there is already a package on the device with this name
        // but it has been renamed to something else.
        val oldName = packageManager.canonicalToCurrentPackageNames(arrayOf(pkgName))
        if (oldName != null && oldName.isNotEmpty() && oldName[0] != null) {
            pkgName = oldName[0]
            newPkgInfo.packageName = pkgName
            newPkgInfo.applicationInfo?.packageName = pkgName
        }

        // Check if package is already installed. display confirmation dialog if replacing pkg
        try {
            // This is a little convoluted because we want to get all uninstalled
            // apps, but this may include apps with just data, and if it is just
            // data we still want to count it as "installed".
            val appInfo = packageManager.getApplicationInfo(
                pkgName, PackageManager.MATCH_UNINSTALLED_PACKAGES
            )
            // If the package is archived, treat it as an update case.
            if (!appInfo.isArchived && appInfo.flags and ApplicationInfo.FLAG_INSTALLED == 0) {
                return false
            }
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
        return true
    }

    fun requestVerificationConfirmation(): InstallStage {
        var confirmationSnippet: InstallStage = generateConfirmationSnippet()

        if (confirmationSnippet.stageCode == InstallStage.STAGE_ABORTED) {
            packageInstaller.setDeveloperVerificationUserResponse(
                sessionId, DEVELOPER_VERIFICATION_USER_RESPONSE_ERROR
            )
            return confirmationSnippet
        }

        val verificationInfo: DeveloperVerificationUserConfirmationInfo? =
            packageInstaller.getDeveloperVerificationUserConfirmationInfo(sessionId)
        if (verificationInfo == null) {
            Log.e(LOG_TAG, "Could not get VerificationInfo for sessionId $sessionId")
            packageInstaller.setDeveloperVerificationUserResponse(
                sessionId, DEVELOPER_VERIFICATION_USER_RESPONSE_ERROR
            )
            return InstallAborted(ABORT_REASON_INTERNAL_ERROR)
        }

        confirmationSnippet = confirmationSnippet as InstallUserActionRequired
        val appSnippet = PackageUtil.getAppSnippet(
                context, confirmationSnippet.appLabel, confirmationSnippet.appIcon)
        val sessionInfo = packageInstaller.getSessionInfo(sessionId)
        val stubPackageInfo = generateStubPackageInfo(sessionInfo?.getAppPackageName())
        val isAppUpdating = isAppUpdating(stubPackageInfo)

        // Since InstallUserActionRequired returned by generateConfirmationSnippet is immutable,
        // create a new InstallUserActionRequired with the required data
        return InstallUserActionRequired(
            isAppUpdating = isAppUpdating,
            actionReason = USER_ACTION_REASON_VERIFICATION_CONFIRMATION,
            appSnippet = appSnippet,
            verificationInfo = verificationInfo
        )
    }

    fun setNegativeVerificationUserResponse(): InstallStage {
        if (PackageInstaller.ACTION_CONFIRM_DEVELOPER_VERIFICATION != intent.action) {
            Log.e(LOG_TAG, "Cannot set verification response for this request: $intent")
            return InstallAborted(ABORT_REASON_INTERNAL_ERROR)
        }
        if (localLogv) {
            Log.d(LOG_TAG, "Setting negative verification user response")
        }
        packageInstaller.setDeveloperVerificationUserResponse(
            sessionId, DEVELOPER_VERIFICATION_USER_RESPONSE_ABORT
        )
        return InstallAborted(
            ABORT_REASON_DONE,
            activityResultCode = Activity.RESULT_OK,
            // Set the errorDialogType to show the error dialog for the user
            errorDialogType = DLG_PACKAGE_ERROR
        )
    }

    fun setPositiveVerificationUserResponse(): InstallStage {
        if (PackageInstaller.ACTION_CONFIRM_DEVELOPER_VERIFICATION != intent.action) {
            Log.e(LOG_TAG, "Cannot set verification response for this request: $intent")
            return InstallAborted(ABORT_REASON_INTERNAL_ERROR)
        }
        if (localLogv) {
            Log.d(LOG_TAG, "Setting positive verification user response")
        }
        packageInstaller.setDeveloperVerificationUserResponse(
            sessionId, DEVELOPER_VERIFICATION_USER_RESPONSE_INSTALL_ANYWAY
        )
        // If it is triggered by PIA itself, show the installing dialog and wait for the
        // result from the receiver. Don't need to set the aborted.
        if (wasUserConfirmationTriggeredByPia) {
            wasUserConfirmationTriggeredByPia = false
            return InstallInstalling(appSnippet, isAppUpdating)
        } else {
            return InstallAborted(
                ABORT_REASON_DONE, activityResultCode = Activity.RESULT_OK
            )
        }
    }

    fun setRetryVerificationUserResponse(): InstallStage {
        if (PackageInstaller.ACTION_CONFIRM_DEVELOPER_VERIFICATION != intent.action) {
            Log.e(LOG_TAG, "Cannot set verification response for this request: $intent")
            return InstallAborted(ABORT_REASON_INTERNAL_ERROR)
        }
        if (localLogv) {
            Log.d(LOG_TAG, "Setting retry verification user response")
        }
        packageInstaller.setDeveloperVerificationUserResponse(
            sessionId, DEVELOPER_VERIFICATION_USER_RESPONSE_RETRY
        )
        // If it is triggered by PIA itself, show the installing dialog and wait for the
        // result from the receiver. Don't need to set the aborted.
        if (wasUserConfirmationTriggeredByPia) {
            wasUserConfirmationTriggeredByPia = false
            return InstallInstalling(appSnippet, isAppUpdating)
        } else {
            return InstallAborted(
                ABORT_REASON_DONE, activityResultCode = Activity.RESULT_OK
            )
        }
    }

    /**
     * Once the user returns from Settings related to installing from unknown sources, reattempt
     * the installation if the source app is granted permission to install other apps. Abort the
     * installation if the source app is still not granted installing permission.
     *
     * @return
     * * [InstallUserActionRequired] containing data required to ask user confirmation
     * to proceed with the install.
     * * [InstallAborted] if there was an error while recomputing, or the source still
     * doesn't have install permission.
     */
    fun reattemptInstall(): InstallStage {
        if (!this::appOpRequestInfo.isInitialized) {
            Log.e(LOG_TAG, "appOpRequestInfo not initialized")
            return InstallAborted(ABORT_REASON_INTERNAL_ERROR)
        }
        val unknownSourceStage = handleUnknownSources(appOpRequestInfo)
        return when (unknownSourceStage.stageCode) {
            InstallStage.STAGE_READY -> {
                // Source app now has appOp granted.
                generateConfirmationSnippet()
            }

            InstallStage.STAGE_ABORTED -> {
                // There was some error in determining the AppOp code for the source app.
                // Abort installation
                unknownSourceStage
            }

            else -> {
                // AppOpsManager again returned a MODE_ERRORED or MODE_DEFAULT op code. This was
                // unexpected while reattempting the install. Let's abort it.
                Log.e(LOG_TAG, "AppOp still not granted.")
                InstallAborted(ABORT_REASON_INTERNAL_ERROR)
            }
        }
    }

    private fun handleUnknownSources(requestInfo: AppOpRequestInfo): InstallStage {
        if (requestInfo.originatingPackage == null) {
            Log.i(LOG_TAG, "No source found for package " + newPackageInfo?.packageName)
            return InstallUserActionRequired(USER_ACTION_REASON_ANONYMOUS_SOURCE)
        }
        // Shouldn't use static constant directly, see b/65534401.
        val appOpStr = AppOpsManager.permissionToOp(Manifest.permission.REQUEST_INSTALL_PACKAGES)
        val appOpMode = appOpsManager!!.noteOpNoThrow(
            appOpStr!!, requestInfo.originatingUid, requestInfo.originatingPackage,
            requestInfo.attributionTag, "Started package installation activity"
        )
        if (localLogv) {
            Log.i(LOG_TAG, "handleUnknownSources(): appMode=$appOpMode")
        }

        return when (appOpMode) {
            AppOpsManager.MODE_DEFAULT, AppOpsManager.MODE_ERRORED -> {
                if (appOpMode == AppOpsManager.MODE_DEFAULT) {
                    appOpsManager.setMode(
                        appOpStr, requestInfo.originatingUid, requestInfo.originatingPackage,
                        AppOpsManager.MODE_ERRORED
                    )
                }
                try {
                    val sourceInfo =
                        packageManager.getApplicationInfo(requestInfo.originatingPackage, 0)
                    val sourceAppSnippet = getAppSnippet(context, sourceInfo)
                    InstallUserActionRequired(
                        USER_ACTION_REASON_UNKNOWN_SOURCE, appSnippet = sourceAppSnippet,
                        unknownSourcePackageName = requestInfo.originatingPackage
                    )
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.e(LOG_TAG, "Did not find appInfo for " + requestInfo.originatingPackage)
                    InstallAborted(ABORT_REASON_INTERNAL_ERROR)
                }
            }

            AppOpsManager.MODE_ALLOWED -> InstallReady()

            else -> {
                Log.e(
                    LOG_TAG, "Invalid app op mode $appOpMode for " +
                        "OP_REQUEST_INSTALL_PACKAGES found for uid $requestInfo.originatingUid"
                )
                InstallAborted(ABORT_REASON_INTERNAL_ERROR)
            }
        }
    }

    /**
     * Kick off the installation. Register a broadcast listener to get the result of the
     * installation and commit the staged session here. If the installation was session based,
     * signal the PackageInstaller that the user has granted permission to proceed with the install
     */
    fun initiateInstall() {
        if (!this::intent.isInitialized || !this::appSnippet.isInitialized) {
            Log.e(LOG_TAG, "intent or appSnippet not initialized")
            _installResult.value = InstallAborted(ABORT_REASON_INTERNAL_ERROR)
            return
        }
        if (sessionId > 0) {
            packageInstaller.setPermissionsResult(sessionId, true)
            if (localLogv) {
                Log.i(LOG_TAG, "Install permission granted for session $sessionId")
            }
            // If it is triggered by PIA itself, show the installing dialog and wait for the
            // result from the receiver. Don't need to set the aborted.
            if (wasUserConfirmationTriggeredByPia) {
                wasUserConfirmationTriggeredByPia = false
                _installResult.value = InstallInstalling(appSnippet, isAppUpdating)
            } else {
                _installResult.value = InstallAborted(
                    ABORT_REASON_DONE, activityResultCode = Activity.RESULT_OK
                )
            }
            return
        }
        val uri = intent.data
        if (SCHEME_PACKAGE == uri?.scheme) {
            try {
                packageManager.installExistingPackage(
                    newPackageInfo!!.packageName, PackageManager.INSTALL_REASON_USER
                )
                setStageBasedOnResult(PackageInstaller.STATUS_SUCCESS, -1, null)
            } catch (e: PackageManager.NameNotFoundException) {
                setStageBasedOnResult(
                    PackageInstaller.STATUS_FAILURE,
                    PackageManager.INSTALL_FAILED_INTERNAL_ERROR,
                    null
                )
            }
            return
        }
        if (stagedSessionId <= 0) {
            // How did we even land here?
            Log.e(LOG_TAG, "Invalid local session and caller initiated session")
            _installResult.value = InstallAborted(ABORT_REASON_INTERNAL_ERROR)
            return
        }
        val installId: Int
        try {
            _installResult.value = InstallInstalling(appSnippet, isAppUpdating)
            installId = InstallEventReceiver.addObserver(
                context,
                EventResultPersister.GENERATE_NEW_ID,
                this
            )
        } catch (e: OutOfIdsException) {
            setStageBasedOnResult(
                PackageInstaller.STATUS_FAILURE, PackageManager.INSTALL_FAILED_INTERNAL_ERROR, null
            )
            return
        }
        val broadcastIntent = Intent(BROADCAST_ACTION)
        broadcastIntent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        broadcastIntent.setPackage(context.packageName)
        broadcastIntent.putExtra(EventResultPersister.EXTRA_ID, installId)
        val pendingIntent = PendingIntent.getBroadcast(
            context, installId, broadcastIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        try {
            val session = packageInstaller.openSession(stagedSessionId)
            session.commit(pendingIntent.intentSender)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Session $stagedSessionId could not be opened.", e)
            packageInstaller.abandonSession(stagedSessionId)
            setStageBasedOnResult(
                PackageInstaller.STATUS_FAILURE, PackageManager.INSTALL_FAILED_INTERNAL_ERROR, null)
        }
    }

    private fun setStageBasedOnResult(
        statusCode: Int,
        legacyStatus: Int,
        message: String?,
    ) {
        if (localLogv) {
            Log.i(
                LOG_TAG, "Status code: $statusCode\n" +
                    "legacy status: $legacyStatus\n" +
                    "message: $message"
            )
        }

        val shouldReturnResult = intent.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false)

        if (statusCode == PackageInstaller.STATUS_SUCCESS) {
            val resultIntent = if (shouldReturnResult) {
                Intent().putExtra(Intent.EXTRA_INSTALL_RESULT, PackageManager.INSTALL_SUCCEEDED)
            } else {
                val intent = packageManager.getLaunchIntentForPackage(newPackageInfo!!.packageName)
                if (isLauncherActivityEnabled(intent)) intent else null
            }
            _installResult.value = InstallSuccess(
                appSnippet,
                shouldReturnResult,
                isAppUpdating,
                resultIntent
            )
        } else {
            // TODO (b/346655018): Use INSTALL_FAILED_ABORTED legacyCode in the condition
            // statusCode can be STATUS_FAILURE_ABORTED if:
            // 1. GPP blocks an install.
            // 2. User denies ownership update explicitly.
            // InstallFailed dialog must not be shown only when the user denies ownership update. We
            // must show this dialog for all other install failures.

            val userDenied = statusCode == PackageInstaller.STATUS_FAILURE_ABORTED &&
                            legacyStatus != PackageManager.INSTALL_FAILED_VERIFICATION_TIMEOUT &&
                            legacyStatus != PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE

            if (shouldReturnResult) {
                val resultIntent = Intent().putExtra(Intent.EXTRA_INSTALL_RESULT, legacyStatus)
                _installResult.value = InstallFailed(
                    legacyCode = legacyStatus,
                    statusCode = statusCode,
                    shouldReturnResult = true,
                    resultIntent = resultIntent
                )
            } else if (userDenied) {
                _installResult.value = InstallAborted(ABORT_REASON_INTERNAL_ERROR)
            } else {
                _installResult.value = InstallFailed(appSnippet, legacyStatus, statusCode, message)
            }
        }
    }

    private fun isLauncherActivityEnabled(intent: Intent?): Boolean {
        if (intent == null || intent.component == null) {
            return false
        }
        return (intent.component?.let { packageManager.getComponentEnabledSetting(it) }
            != COMPONENT_ENABLED_STATE_DISABLED)
    }

    /**
     * Cleanup the staged session. Also signal the packageinstaller that an install session is to
     * be aborted
     */
    fun cleanupInstall() {
        if (sessionId > 0) {
            packageInstaller.setPermissionsResult(sessionId, false)
        } else if (stagedSessionId > 0) {
            cleanupStagingSession()
        }
    }

    fun abortStaging() {
        sessionStager?.cancel()
        if (this::stagingJob.isInitialized) {
            stagingJob.cancel()
        }
        cleanupStagingSession()
    }

    val stagingProgress: LiveData<Int>
        get() = sessionStager?.progress ?: MutableLiveData(0)

    /** Override the callback method of the EventResultPersister.EventResultObserver */
    override fun onResult(
        status: Int,
        legacyStatus: Int,
        message: String?,
        serviceId: Int,
        intent: Intent?
    ) {
        setStageBasedOnResult(status, legacyStatus, message)
    }

    /** Override the callback method of the EventResultPersister.EventResultObserver */
    override fun onHandleIntent(intent: Intent): Boolean {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, 0)

        // If the status is pending user action, trigger the user confirmation from PIA.
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val intentToStart = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            // Get the value of should return result from the original intent and add it into
            // the intentToStart
            val shouldReturnResult = this.intent.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false)
            intentToStart!!.putExtra(Intent.EXTRA_RETURN_RESULT, shouldReturnResult)

            // In this case, the caller is PIA itself
            val stage = performPreInstallChecks(
                intentToStart!!,
                CallerInfo(context.packageName, context.applicationInfo.uid))
            if (stage.stageCode == InstallStage.STAGE_ABORTED) {
                _installResult.value = stage
                return false
            }
            wasUserConfirmationTriggeredByPia = true
            stageForInstall()
            return true
        }
        return false
    }

    companion object {
        const val EXTRA_STAGED_SESSION_ID = "com.android.packageinstaller.extra.STAGED_SESSION_ID"
        const val SCHEME_PACKAGE = "package"
        const val BROADCAST_ACTION = "com.android.packageinstaller.ACTION_INSTALL_COMMIT"
        private val LOG_TAG = InstallRepository::class.java.simpleName
    }

    data class CallerInfo(val packageName: String?, val uid: Int)
    data class AppOpRequestInfo(
        val originatingPackage: String?,
        val originatingUid: Int,
        val attributionTag: String?,
    )
}
