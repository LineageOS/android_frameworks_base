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

package com.android.server.pm;

import static android.os.Process.myUid;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.os.Binder;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.PermissionEnforcer;
import android.os.SELinux;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.system.Os;
import android.util.ArrayMap;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.content.InstallLocationUtils;
import com.android.server.IoThread;
import com.android.server.LocalServices;
import com.android.server.SystemServiceManager;
import com.android.server.pm.verify.developer.DeveloperVerifierController;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.io.File;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class PackageInstallerServiceTest {
    private @Mock PermissionEnforcer mMockPermissionEnforcer;
    private @Mock ActivityManager mMockActivityManager;
    private @Mock AppOpsManager mAppOpsManager;
    private @Mock SystemServiceManager mMockSystemServiceManager;
    private @Mock DeveloperVerifierController mMockDeveloperVerifierController;
    private String mPackageName;
    private PackageManagerService mPms;
    private @Mock Computer mMockSnapshot;
    private @Mock Handler mMockHandler;
    private File mMockeFile;
    private File mTestDir;
    private final PackageManagerServiceTestParams mTestParams =
            new PackageManagerServiceTestParams();
    private static final int TEST_USER_ID = 10;
    private static final int TEST_USER_ID_2 = 20;
    private static final int TEST_USER_ID_3 = 30;
    private static final int TEST_USER_ID_4 = 40;

    @Rule
    public final MockSystemRule rule = new MockSystemRule();

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        rule.system().stageNominalSystemState();
        mPackageName = this.getClass().getPackageName();
        mTestParams.packages = new ArrayMap<>();
        // Basic mocks to support session creation
        when(rule.mocks().getContext().getSystemService(Context.PERMISSION_ENFORCER_SERVICE))
                .thenReturn(mMockPermissionEnforcer);
        when(rule.mocks().getContext().getSystemService(ActivityManager.class))
                .thenReturn(mMockActivityManager);
        when(rule.mocks().getContext().getSystemService(AppOpsManager.class))
                .thenReturn(mAppOpsManager);
        mMockeFile = spy(new File(""));
        doReturn(mMockeFile).when(() -> Environment.getDataAppDirectory(nullable(String.class)));
        doReturn(mMockeFile).when(
                () -> Environment.getDataStagingDirectory(nullable(String.class)));
        doReturn(mMockSystemServiceManager).when(
                () -> LocalServices.getService(SystemServiceManager.class));
        // Run scheduled write tasks right away
        doReturn(mMockHandler).when(IoThread::getHandler);
        when(mMockHandler.post(any(Runnable.class))).thenAnswer(
                i -> {
                    ((Runnable) i.getArguments()[0]).run();
                    return true;
                });
        doReturn(mMockDeveloperVerifierController).when(
                () -> DeveloperVerifierController.getInstance(any(Context.class),
                        any(Handler.class), argThat(componentName
                                -> componentName != null
                                && componentName.getPackageName().equals(mPackageName)
                ))
        );
        // Test specific environment setup
        doReturn(mPackageName).when(mMockDeveloperVerifierController).getVerifierPackageName();
        mPms = spy(new PackageManagerService(rule.mocks().getInjector(), mTestParams));
        doReturn(false).when(mPms).isUserRestricted(anyInt(), nullable(String.class));
        doReturn(mMockSnapshot).when(mPms).snapshotComputer();
        doReturn(myUid()).when(mMockSnapshot).getPackageUidInternal(
                eq(mPackageName), anyLong(), anyInt(), anyInt());
        doReturn(true).when(mMockSnapshot).isCallerSameApp(anyString(), anyInt());
        // Create a test file for read/write of mSessionsFile
        mTestDir = new File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getFilesDir(), "testDir");
        assertThat(mTestDir.mkdirs()).isTrue();
        doReturn(mTestDir).when(Environment::getDataSystemDirectory);
    }

    @After
    public void tearDown() {
        if (mTestDir != null) {
            // Clean up test dir to remove persisted user files.
            FileUtils.deleteContentsAndDir(mTestDir);
        }
    }

    @Test
    public void testVerificationPolicyPerUser() {
        doReturn(new int[] {UserHandle.USER_SYSTEM, TEST_USER_ID, TEST_USER_ID_2})
                .when(mPms.mUserManager).getUserIds();
        final PackageInstallerService service = new PackageInstallerService(
                rule.mocks().getContext(), mPms, null,
                new ComponentName(mPackageName, this.getClass().getName()));
        service.systemReady();
        final int defaultPolicy = service.getDeveloperVerificationPolicy(
                /* userId= */ UserHandle.USER_SYSTEM);
        assertThat(defaultPolicy).isAtLeast(PackageInstaller.DEVELOPER_VERIFICATION_POLICY_NONE);
        assertThat(service.getDeveloperVerificationPolicy(TEST_USER_ID)).isEqualTo(defaultPolicy);
        assertThat(service.getDeveloperVerificationPolicy(TEST_USER_ID_2)).isEqualTo(defaultPolicy);
        assertThat(service.setDeveloperVerificationPolicy(
                /* policy= */ PackageInstaller.DEVELOPER_VERIFICATION_POLICY_BLOCK_FAIL_CLOSED,
                /* userId= */ UserHandle.USER_SYSTEM)).isTrue();
        // Test with a non-existing user
        final int newUserId = TEST_USER_ID_3;
        assertThrows(IllegalStateException.class, () -> service.getDeveloperVerificationPolicy(
                /* userId= */ newUserId));
        assertThrows(IllegalStateException.class,
                () -> service.setDeveloperVerificationPolicy(
                /* policy= */ PackageInstaller.DEVELOPER_VERIFICATION_POLICY_BLOCK_FAIL_CLOSED,
                /* userId= */ newUserId));
        // Add a user
        service.onUserAdded(newUserId);
        assertThat(service.getDeveloperVerificationPolicy(newUserId)).isEqualTo(defaultPolicy);
        assertThat(service.setDeveloperVerificationPolicy(
                PackageInstaller.DEVELOPER_VERIFICATION_POLICY_BLOCK_FAIL_WARN, newUserId)
        ).isTrue();
        assertThat(service.getDeveloperVerificationPolicy(newUserId)).isEqualTo(
                PackageInstaller.DEVELOPER_VERIFICATION_POLICY_BLOCK_FAIL_WARN);
        // Remove a user
        service.onUserRemoved(newUserId);
        assertThrows(IllegalStateException.class, () -> service.getDeveloperVerificationPolicy(
                /* userId= */ newUserId));
        assertThrows(
                IllegalStateException.class,
                () -> service.setDeveloperVerificationPolicy(
                        /* policy= */
                        PackageInstaller.DEVELOPER_VERIFICATION_POLICY_BLOCK_FAIL_CLOSED,
                        /* userId= */ newUserId));
    }

    @Test
    public void testWriteAndReadVerificationPolicyPerUser() {
        doReturn(new int[] {UserHandle.USER_SYSTEM, TEST_USER_ID, TEST_USER_ID_2})
                .when(mPms.mUserManager).getUserIds();
        final PackageInstallerService service = new PackageInstallerService(
                rule.mocks().getContext(), mPms, null,
                new ComponentName(mPackageName, this.getClass().getName()));
        service.systemReady();
        final int defaultPolicy = service.getDeveloperVerificationPolicy(
                /* userId= */ UserHandle.USER_SYSTEM);
        // Modify the policy for each user
        final int policyForSystemUser =
                PackageInstaller.DEVELOPER_VERIFICATION_POLICY_BLOCK_FAIL_CLOSED;
        final int policyForTestUser =
                PackageInstaller.DEVELOPER_VERIFICATION_POLICY_BLOCK_FAIL_WARN;
        final int policyForTestUser2 =
                PackageInstaller.DEVELOPER_VERIFICATION_POLICY_BLOCK_FAIL_OPEN;
        assertThat(service.setDeveloperVerificationPolicy(
                /* policy= */ policyForSystemUser,
                /* userId= */ UserHandle.USER_SYSTEM)).isTrue();
        assertThat(service.setDeveloperVerificationPolicy(
                /* policy= */ policyForTestUser,
                /* userId= */ TEST_USER_ID)).isTrue();
        assertThat(service.setDeveloperVerificationPolicy(
                /* policy= */ policyForTestUser2,
                /* userId= */ TEST_USER_ID_2)).isTrue();
        // systemReady and Each policy change above should have triggered a write to the test file
        // but since the write in systemReady is done immediately in the main thread of
        // PackageInstallerService and doesn't go through the handler of the IO thread, we don't
        // have the chance to verify that here.
        verify(mMockHandler, times(3)).post(any(Runnable.class));
        // Mimic a reboot by creating a new service instance with the latest policy for each user
        // loaded from the test file
        final PackageInstallerService service2 = new PackageInstallerService(
                rule.mocks().getContext(), mPms, null,
                new ComponentName(mPackageName, this.getClass().getName()));
        service2.systemReady();
        assertThat(service2.getDeveloperVerificationPolicy(UserHandle.USER_SYSTEM)).isEqualTo(
                policyForSystemUser);
        assertThat(service2.getDeveloperVerificationPolicy(TEST_USER_ID)).isEqualTo(
                policyForTestUser);
        assertThat(service2.getDeveloperVerificationPolicy(TEST_USER_ID_2)).isEqualTo(
                policyForTestUser2);
    }

    // Mimic a system where it initially has 2 users, then 1 is removed and a new one is added
    // on reboot. Then all are removed and a different one is added on reboot. Test that the
    // per-user developer verification policy is correctly set.
    @Test
    public void testCleanUpVerificationPolicyPerUser() {
        doReturn(new int[] {UserHandle.USER_SYSTEM, TEST_USER_ID})
                .when(mPms.mUserManager).getUserIds();
        PackageInstallerService service = new PackageInstallerService(
                rule.mocks().getContext(), mPms, null,
                new ComponentName(mPackageName, this.getClass().getName()));
        service.systemReady();
        final int defaultPolicy = service.getDeveloperVerificationPolicy(
                /* userId= */ UserHandle.USER_SYSTEM);
        // Modify the policy for each user
        final int policyForSystemUser =
                PackageInstaller.DEVELOPER_VERIFICATION_POLICY_BLOCK_FAIL_CLOSED;
        final int policyForTestUser =
                PackageInstaller.DEVELOPER_VERIFICATION_POLICY_BLOCK_FAIL_WARN;
        assertThat(service.setDeveloperVerificationPolicy(
                /* policy= */ policyForSystemUser,
                /* userId= */ UserHandle.USER_SYSTEM)).isTrue();
        assertThat(service.setDeveloperVerificationPolicy(
                /* policy= */ policyForTestUser,
                /* userId= */ TEST_USER_ID)).isTrue();
        // Mimic a reboot by creating a new service instance. The policy for the deleted user
        // should not exist, and the policy for the new user should be the default policy.
        // And previously set policy should be preserved on the remaining user.
        PackageManagerService mPms2 = spy(new PackageManagerService(rule.mocks().getInjector(),
                mTestParams));
        doReturn(new int[] {UserHandle.USER_SYSTEM, TEST_USER_ID_2})
                .when(mPms2.mUserManager).getUserIds();
        final PackageInstallerService service2 = new PackageInstallerService(
                rule.mocks().getContext(), mPms2, null,
                new ComponentName(mPackageName, this.getClass().getName()));
        service2.systemReady();
        assertThat(service2.getDeveloperVerificationPolicy(UserHandle.USER_SYSTEM)).isEqualTo(
                policyForSystemUser);
        assertThrows(IllegalStateException.class, () -> service2.getDeveloperVerificationPolicy(
                TEST_USER_ID));
        assertThat(service2.getDeveloperVerificationPolicy(TEST_USER_ID_2)).isEqualTo(
                defaultPolicy);
        // Mimic another reboot by creating a new service instance. The policy for the deleted users
        // should not exist, and the policy for the new users should be the default policy.
        PackageManagerService mPms3 = spy(new PackageManagerService(rule.mocks().getInjector(),
                mTestParams));
        doReturn(new int[] {TEST_USER_ID_3, TEST_USER_ID_4})
                .when(mPms3.mUserManager).getUserIds();
        final PackageInstallerService service3 = new PackageInstallerService(
                rule.mocks().getContext(), mPms3, null,
                new ComponentName(mPackageName, this.getClass().getName()));
        service3.systemReady();
        assertThrows(IllegalStateException.class, () -> service3.getDeveloperVerificationPolicy(
                UserHandle.USER_SYSTEM));
        assertThrows(IllegalStateException.class, () -> service3.getDeveloperVerificationPolicy(
                TEST_USER_ID_2));
        assertThat(service3.getDeveloperVerificationPolicy(TEST_USER_ID_3)).isEqualTo(
                defaultPolicy);
        assertThat(service3.getDeveloperVerificationPolicy(TEST_USER_ID_4)).isEqualTo(
                defaultPolicy);
    }

    @Test
    public void testVerifierIsNullThrowsException() {
        doReturn(mMockDeveloperVerifierController).when(
                () -> DeveloperVerifierController.getInstance(any(), any(), eq(null))
        );
        when(mMockDeveloperVerifierController.getVerifierPackageName()).thenReturn(null);
        PackageInstallerService service = new PackageInstallerService(
                rule.mocks().getContext(), mPms, null, null);
        // When there is no verifier specified by the system, no one can change the policy.
        assertThrows(SecurityException.class,
                () -> service.setDeveloperVerificationPolicy(
                /* policy= */ PackageInstaller.DEVELOPER_VERIFICATION_POLICY_BLOCK_FAIL_CLOSED,
                /* userId= */ UserHandle.USER_SYSTEM));
    }

    @Test
    public void testForceUuidFlagWithoutUuidArgumentThrowsException() {
        doReturn(mMockDeveloperVerifierController).when(
                () -> DeveloperVerifierController.getInstance(any(), any(), eq(null))
        );
        when(mMockDeveloperVerifierController.getVerifierPackageName()).thenReturn(null);
        PackageInstallerService service = new PackageInstallerService(
                rule.mocks().getContext(), mPms, null, null);
        service.systemReady();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.installFlags = PackageManager.INSTALL_FORCE_VOLUME_UUID;
        params.isMultiPackage = false;
        params.volumeUuid = "..";
        assertThrows(IllegalArgumentException.class,
                () -> service.createSessionInternal(
                        params,
                        /* installerPackageName= */ null,
                        /* installerAttributionTag= */ null,
                        /* callingUid= */ myUid(),
                        /* userId= */ UserHandle.USER_SYSTEM));
    }

    @Test
    public void createSession_withLongAttributionTag_throwsException() throws Exception {
        doReturn(new int[]{UserHandle.USER_SYSTEM}).when(mPms.mUserManager).getUserIds();
        doReturn(null).when(() -> InstallLocationUtils.resolveInstallVolume(any(), any()));
        final PackageInstallerService service = new PackageInstallerService(
                rule.mocks().getContext(), mPms, null,
                new ComponentName(mPackageName, this.getClass().getName()));
        service.systemReady();

        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        final String longAttributionTag = new String(new char[51]).replace('\0', 'a');

        assertThrows(IllegalArgumentException.class,
                () -> service.createSession(
                        params,
                        /* installerPackageName= */ null,
                        longAttributionTag,
                        /* userId= */ UserHandle.USER_SYSTEM));
    }

    @Test
    public void testAddDeveloperVerificationExperiment_throwsExceptionForNonRootOrShell() {
        final PackageInstallerService service = new PackageInstallerService(
                rule.mocks().getContext(), mPms, null,
                new ComponentName(mPackageName, this.getClass().getName()));

        // Verifies that a SecurityException is thrown when a non-root/non-shell caller
        // attempts to add a developer verification experiment.
        assertThrows(SecurityException.class,
                () -> service.addDeveloperVerificationExperiment(
                        "com.example.app",
                        PackageInstaller.DEVELOPER_VERIFICATION_POLICY_BLOCK_FAIL_CLOSED,
                        new int[]{1, 2, 3}));
    }

    @Test
    public void testClearDeveloperVerificationExperiment_throwsExceptionForNonRootOrShell() {
        final PackageInstallerService service = new PackageInstallerService(
                rule.mocks().getContext(), mPms, null,
                new ComponentName(mPackageName, this.getClass().getName()));

        // Verifies that a SecurityException is thrown when a non-root/non-shell caller
        // attempts to clear a developer verification experiment.
        assertThrows(SecurityException.class,
                () -> service.clearDeveloperVerificationExperiment("com.example.app"));
    }

    @Test
    public void testCreateSessionQuotaEnforcedAfterTransfer() throws Exception {
        doReturn(mMockDeveloperVerifierController).when(
                () -> DeveloperVerifierController.getInstance(any(), any(), eq(null))
        );
        when(mMockDeveloperVerifierController.getVerifierPackageName()).thenReturn(null);

        final PackageInstallerService service = new PackageInstallerService(
                rule.mocks().getContext(), mPms, null, null);
        service.systemReady();

        ContentResolver contentResolver = mock(ContentResolver.class);
        when(rule.mocks().getContext().getContentResolver()).thenReturn(contentResolver);

        // App A has no INSTALL_PACKAGES permission
        when(rule.mocks().getContext().checkPermission(
                eq(android.Manifest.permission.INSTALL_PACKAGES),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_DENIED);

        int appAUid = Binder.getCallingUid();
        int appBUid = 10002;

        // Mock app B info for transfer
        ApplicationInfo appBInfo = new ApplicationInfo();
        appBInfo.uid = appBUid;
        appBInfo.packageName = "com.app.b";
        when(mMockSnapshot.getApplicationInfo(eq("com.app.b"), anyLong(), anyInt()))
                .thenReturn(appBInfo);
        when(mMockSnapshot.checkUidPermission(eq(android.Manifest.permission.INSTALL_PACKAGES),
                eq(appBUid))).thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mMockSnapshot.getNameForUid(appBUid)).thenReturn("com.app.b");
        when(mMockSnapshot.getNameForUid(appAUid)).thenReturn("com.app.a");

        // 1. App A creates 50 sessions
        for (int i = 0; i < 50; i++) {
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            service.createSessionInternal(params, "com.app.a", null, appAUid, TEST_USER_ID);
        }

        // 2. Try to create 51st session for A - should fail
        PackageInstaller.SessionParams params51 = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        assertThrows(IllegalStateException.class,
                () -> service.createSessionInternal(params51, "com.app.a", null, appAUid,
                        TEST_USER_ID));

        // 3. Transfer one session from A to B
        ParceledListSlice<PackageInstaller.SessionInfo> sessions = service.getAllSessions(
                TEST_USER_ID);
        int sessionId = sessions.getList().get(0).getSessionId();
        PackageInstallerSession session = service.getSession(sessionId);

        // (Session needs to be marked prepared for it to be allowed to be transferred.)
        MockitoSession mockitoSession = mockitoSession()
                .mockStatic(Os.class)
                .mockStatic(SELinux.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        try {
            doReturn(true).when(() -> SELinux.restorecon(any(File.class)));
            // Open session so it can be transferred.
            session.open();
        } finally {
            mockitoSession.finishMocking();
        }
        // Transfer session to app B.
        session.transfer("com.app.b");

        // 4. Now app A has 49 owned sessions, but 50 "original" sessions.
        // Try to create another session for A - should still fail.
        doReturn(true).when(() -> InstallLocationUtils.fitsOnInternal(any(), any()));
        assertThrows(IllegalStateException.class,
                () -> service.createSessionInternal(params51, "com.app.a", null, appAUid,
                        TEST_USER_ID));

        // 5. App B now has 1 owned session. B should be able to create 49 more sessions.
        for (int i = 0; i < 49; i++) {
            PackageInstaller.SessionParams bParams = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            service.createSessionInternal(bParams, "com.app.b", null, appBUid, TEST_USER_ID);
        }

        // 6. B now has 50 sessions (49 created + 1 transferred).
        // B's 51st session should fail.
        assertThrows(IllegalStateException.class,
                () -> service.createSessionInternal(params51, "com.app.b", null, appBUid,
                        TEST_USER_ID));

        // 7. Try to transfer another session from A to B - should fail because B is at quota
        int sessionId2 = sessions.getList().get(1).getSessionId();
        PackageInstallerSession session2 = service.getSession(sessionId2);
        assertThrows(IllegalStateException.class, () -> session2.transfer("com.app.b"));
    }

    @Test
    public void testGetAllSessions_filtersSessionsCorrectly() throws Exception {
        doReturn(mMockDeveloperVerifierController).when(
                () -> DeveloperVerifierController.getInstance(any(), any(), eq(null))
        );
        when(mMockDeveloperVerifierController.getVerifierPackageName()).thenReturn(null);

        final PackageInstallerService service = new PackageInstallerService(
                rule.mocks().getContext(), mPms, null, null);
        service.systemReady();

        int callingUid = Binder.getCallingUid();
        int otherUid = 10002;
        String appPackageName = "com.app.b";

        ApplicationInfo appBInfo = new ApplicationInfo();
        appBInfo.uid = otherUid;
        appBInfo.packageName = appPackageName;
        when(mMockSnapshot.getApplicationInfo(eq(appPackageName), anyLong(), anyInt()))
                .thenReturn(appBInfo);
        when(mMockSnapshot.checkUidPermission(eq(android.Manifest.permission.INSTALL_PACKAGES),
                eq(otherUid))).thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mMockSnapshot.getNameForUid(otherUid)).thenReturn(appPackageName);

        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.appPackageName = appPackageName;
        service.createSessionInternal(params, appPackageName, null, otherUid, TEST_USER_ID);

        // Mock snapshot behavior for callingUid
        when(mMockSnapshot.canQueryPackage(callingUid, appPackageName)).thenReturn(false);

        ParceledListSlice<PackageInstaller.SessionInfo> sessions = service.getAllSessions(
                TEST_USER_ID);
        assertThat(sessions.getList()).isEmpty();

        // Now allow callingUid to query the package
        when(mMockSnapshot.canQueryPackage(callingUid, appPackageName)).thenReturn(true);
        sessions = service.getAllSessions(TEST_USER_ID);
        assertThat(sessions.getList()).hasSize(1);
        assertThat(sessions.getList().get(0).getAppPackageName()).isEqualTo(appPackageName);
    }
}
