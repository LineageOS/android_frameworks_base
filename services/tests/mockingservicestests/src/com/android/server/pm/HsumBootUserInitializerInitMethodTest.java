/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static android.multiuser.Flags.FLAG_CREATE_INITIAL_USER;
import static android.os.UserHandle.USER_NULL;
import static android.os.UserHandle.USER_SYSTEM;
import static android.content.pm.UserInfo.FLAG_ADMIN;
import static android.content.pm.UserInfo.FLAG_FULL;
import static android.content.pm.UserInfo.FLAG_MAIN;
import static android.content.pm.UserInfo.FLAG_SYSTEM;

import static com.android.server.pm.HsumBootUserInitializerInitMethodTest.ExpectedResult.ADMIN_USER_CREATED;
import static com.android.server.pm.HsumBootUserInitializerInitMethodTest.ExpectedResult.FIRST_ADMIN_USER_PROMOTED_TO_MAIN;
import static com.android.server.pm.HsumBootUserInitializerInitMethodTest.ExpectedResult.MAIN_USER_CREATED;
import static com.android.server.pm.HsumBootUserInitializerInitMethodTest.ExpectedResult.MAIN_USER_DEMOTED;
import static com.android.server.pm.HsumBootUserInitializerInitMethodTest.ExpectedResult.NO_USER_CREATED;
import static com.android.server.pm.HsumBootUserInitializerInitMethodTest.ExpectedResult.SECOND_ADMIN_USER_PROMOTED_TO_MAIN;
import static com.android.server.pm.HsumBootUserInitializerInitMethodTest.InitialUsers.SYSTEM_AND_MAIN;
import static com.android.server.pm.HsumBootUserInitializerInitMethodTest.InitialUsers.SYSTEM_AND_ADMINS;
import static com.android.server.pm.HsumBootUserInitializerInitMethodTest.InitialUsers.SYSTEM_AND_ADMINS_FIRST_ADMIN_UNPROMOTABLE;
import static com.android.server.pm.HsumBootUserInitializerInitMethodTest.InitialUsers.SYSTEM_AND_REGULAR;
import static com.android.server.pm.HsumBootUserInitializerInitMethodTest.InitialUsers.SYSTEM_ONLY;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.annotation.SpecialUsers.CanBeNULL;
import android.annotation.UserIdInt;
import android.content.ContentResolver;
import android.content.pm.UserInfo;
import android.content.pm.UserInfo.UserInfoFlag;
import android.os.UserManager;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.util.Log;

import com.android.server.am.ActivityManagerService;
import com.android.server.utils.TimingsTraceAndSlog;

import com.google.common.truth.Expect;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

@RunWith(Parameterized.class)
public final class HsumBootUserInitializerInitMethodTest {

    private static final String TAG = HsumBootUserInitializerInitMethodTest.class.getSimpleName();

    @UserIdInt
    private static final int MAIN_USER_ID = 4;
    @UserIdInt
    private static final int ADMIN_USER_ID = 8;
    @UserIdInt
    private static final int ANOTHER_ADMIN_USER_ID = 15;
    @UserIdInt
    private static final int REGULAR_USER_ID = 16;

    // Pre-defined users. NOTE: only setting basic flags and not setting UserType.
    private final UserInfo mHeadlessSystemUser =
            createUser(USER_SYSTEM, FLAG_SYSTEM | FLAG_ADMIN);
    private final UserInfo mMainUser =
            createUser(MAIN_USER_ID, FLAG_FULL | FLAG_MAIN | FLAG_ADMIN);
    private final UserInfo mAdminUser =
            createUser(ADMIN_USER_ID, FLAG_FULL | FLAG_ADMIN);
    private final UserInfo mAnotherAdminUser =
            createUser(ANOTHER_ADMIN_USER_ID, FLAG_FULL | FLAG_ADMIN);
    private final UserInfo mRegularUser =
            createUser(REGULAR_USER_ID, FLAG_FULL);

    @Rule
    public final Expect expect = Expect.create();

    // NOTE: replace by ExtendedMockitoRule once it needs to mock UM.isHSUM() or other methods
    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Rule
    public final SetFlagsRule setFlagsRule =
            new SetFlagsRule(SetFlagsRule.DefaultInitValueType.DEVICE_DEFAULT);

    @Mock
    private UserManagerService mMockUms;
    @Mock
    private ActivityManagerService mMockAms;
    @Mock
    private PackageManagerService mMockPms;
    @Mock
    private ContentResolver mMockContentResolver;

    @Nullable // Must be created in the same thread that it's used
    private TimingsTraceAndSlog mTracer;

    private final boolean mShouldAlwaysHaveMainUser;
    private final boolean mShouldCreateInitialUser;
    private final InitialUsers mInitialUsers;
    private final ExpectedResult mExpectedResult;

    // NOTE: do NOT auto-format lines below, otherwise it'd be harder to read; if repo upload fails,
    // try 'repo upload --no-verify --ignore-hooks' instead (after fixing other reported issues)

    // CHECKSTYLE:OFF Generated code

    /** Useless javadoc to make checkstyle happy... */
    @Parameters(name = "{index}: hasMain={0},createInitial={1},initial={2},result={3}")
    public static Collection<Object[]> junitParametersPassedToConstructor() {
        return Arrays.asList(new Object[][] {

    // shouldAlwaysHaveMainUser=false, shouldCreateInitialUser=false
    { false, false, SYSTEM_ONLY, NO_USER_CREATED }, // index 0
    { false, false, SYSTEM_AND_MAIN, MAIN_USER_DEMOTED },
    { false, false, SYSTEM_AND_ADMINS, NO_USER_CREATED },
    { false, false, SYSTEM_AND_ADMINS_FIRST_ADMIN_UNPROMOTABLE, NO_USER_CREATED },
    { false, false, SYSTEM_AND_REGULAR, NO_USER_CREATED },
    // shouldAlwaysHaveMainUser=false, shouldCreateInitialUser=true
    { false, true, SYSTEM_ONLY, ADMIN_USER_CREATED}, // index 5
    { false, true, SYSTEM_AND_MAIN, MAIN_USER_DEMOTED },
    { false, true, SYSTEM_AND_ADMINS, NO_USER_CREATED },
    { false, true, SYSTEM_AND_ADMINS_FIRST_ADMIN_UNPROMOTABLE, NO_USER_CREATED },
    { false, true, SYSTEM_AND_REGULAR, NO_USER_CREATED },
    // shouldAlwaysHaveMainUser=true, shouldCreateInitialUser=false
    { true, false, SYSTEM_ONLY, MAIN_USER_CREATED }, // index 10
    { true, false, SYSTEM_AND_MAIN, NO_USER_CREATED },
    { true, false, SYSTEM_AND_ADMINS, FIRST_ADMIN_USER_PROMOTED_TO_MAIN },
    { true, false, SYSTEM_AND_ADMINS_FIRST_ADMIN_UNPROMOTABLE, SECOND_ADMIN_USER_PROMOTED_TO_MAIN },
    { true, false, SYSTEM_AND_REGULAR, MAIN_USER_CREATED },
    // shouldAlwaysHaveMainUser=true, shouldCreateInitialUser=true
    { true, true, SYSTEM_ONLY, MAIN_USER_CREATED }, // index 15
    { true, true, SYSTEM_AND_MAIN, NO_USER_CREATED },
    { true, true, SYSTEM_AND_ADMINS, FIRST_ADMIN_USER_PROMOTED_TO_MAIN },
    { true, true, SYSTEM_AND_ADMINS_FIRST_ADMIN_UNPROMOTABLE, SECOND_ADMIN_USER_PROMOTED_TO_MAIN },
    { true, true, SYSTEM_AND_REGULAR, MAIN_USER_CREATED }

        // NOTE: if you add more arguments to the constructor, create a new block below by
        // copying the "baseline" values above and changing the proper argument
        });
    }
    // CHECKSTYLE:ON Generated code

    public HsumBootUserInitializerInitMethodTest(boolean shouldAlwaysHaveMainUser,
            boolean shouldCreateInitialUser, InitialUsers initialUsers,
            ExpectedResult expectedResult) {
        mShouldAlwaysHaveMainUser = shouldAlwaysHaveMainUser;
        mShouldCreateInitialUser = shouldCreateInitialUser;
        mInitialUsers = initialUsers;
        mExpectedResult = expectedResult;
        Log.i(TAG, "Constructor: shouldAlwaysHaveMainUser=" + shouldAlwaysHaveMainUser
                + ", shouldCreateInitialUser=" + shouldCreateInitialUser
                + ", initialUsers=" + initialUsers + ",expectedResult=" + expectedResult);
    }

    @Before
    public void setDefaultExpectations() throws Exception {
        switch (mInitialUsers) {
            case SYSTEM_ONLY:
                mockGetUsers(mHeadlessSystemUser);
                mockGetMainUserId(USER_NULL);
                break;
            case SYSTEM_AND_MAIN:
                mockGetUsers(mHeadlessSystemUser, mMainUser);
                mockGetMainUserId(MAIN_USER_ID);
                break;
            case SYSTEM_AND_ADMINS_FIRST_ADMIN_UNPROMOTABLE:
                mockPromoteToMainUserFails(ADMIN_USER_ID);
                // fall through
            case SYSTEM_AND_ADMINS:
                mockGetUsers(mHeadlessSystemUser, mAdminUser, mAnotherAdminUser);
                mockGetMainUserId(USER_NULL);
                break;
            case SYSTEM_AND_REGULAR:
                mockGetUsers(mHeadlessSystemUser, mRegularUser);
                mockGetMainUserId(USER_NULL);
                break;
        }
        // NOTE: need to mock createNewUser() as the user id is used on Slog.
        switch (mExpectedResult) {
            case ADMIN_USER_CREATED:
                mockCreateNewUser(ADMIN_USER_ID);
                break;
            case FIRST_ADMIN_USER_PROMOTED_TO_MAIN:
                mockPromoteToMainUser(ADMIN_USER_ID);
                break;
            case SECOND_ADMIN_USER_PROMOTED_TO_MAIN:
                mockPromoteToMainUserFails(ADMIN_USER_ID);
                mockPromoteToMainUser(ANOTHER_ADMIN_USER_ID);
                break;
            case MAIN_USER_CREATED:
                mockCreateNewUser(MAIN_USER_ID);
                break;
            case MAIN_USER_DEMOTED:
                mockGetMainUserId(MAIN_USER_ID);
                break;
            default:
                // don't need to mock it
        }
    }

    @After
    public void expectAllTracingCallsAreFinished() {
        if (mTracer == null) {
            return;
        }
        var unfinished = mTracer.getUnfinishedTracesForDebug();
        if (!unfinished.isEmpty()) {
            expect.withMessage("%s unfinished tracing calls: %s", unfinished.size(), unfinished)
                    .fail();
        }
    }

    @Test
    @EnableFlags(FLAG_CREATE_INITIAL_USER)
    public void testFlagEnabled() {
        var initializer = createHsumBootUserInitializer(mShouldAlwaysHaveMainUser,
                mShouldCreateInitialUser);

        initializer.init(mTracer);

        switch (mExpectedResult) {
            case ADMIN_USER_CREATED:
                expectAdminUserCreated();
                expectSetBootUserId(ADMIN_USER_ID);
                expectMainUserNotDemoted();
                break;
            case FIRST_ADMIN_USER_PROMOTED_TO_MAIN:
                expectNoUserCreated();
                expectSetBootUserIdNeverCalled();
                expectAdminPromotedToMainUser(ADMIN_USER_ID);
                expectAdminNotPromotedToMainUser(ANOTHER_ADMIN_USER_ID);
                break;
            case SECOND_ADMIN_USER_PROMOTED_TO_MAIN:
                expectNoUserCreated();
                expectSetBootUserIdNeverCalled();
                // don't need to verify call to ADMIN_USER_ID - it was mocked to return false
                expectAdminPromotedToMainUser(ANOTHER_ADMIN_USER_ID);
                break;
            case MAIN_USER_CREATED:
                expectMainUserCreated();
                expectSetBootUserId(MAIN_USER_ID);
                expectMainUserNotDemoted();
                expectNoAdminPromotedToMainUser();
                break;
            case NO_USER_CREATED:
                expectNoUserCreated();
                expectMainUserNotDemoted();
                expectNoAdminPromotedToMainUser();
                break;
            case MAIN_USER_DEMOTED:
                expectNoUserCreated();
                expectMainUserDemoted();
                expectNoAdminPromotedToMainUser();
                break;
        }
    }

    // TODO(b/409650316): remove tests below after flag's completely pushed
    @Test
    @DisableFlags(FLAG_CREATE_INITIAL_USER)
    public void testFlagDisabled() {
        var initializer =
                createHsumBootUserInitializer(mShouldAlwaysHaveMainUser, mShouldCreateInitialUser);

        initializer.init(mTracer);

        switch (mExpectedResult) {
            // When the flag is disabled, it shouldn't trigger the "create admin user" workflow
            case ADMIN_USER_CREATED:
            case NO_USER_CREATED:
            case MAIN_USER_DEMOTED:
                expectNoUserCreated();
                break;
            case MAIN_USER_CREATED:
                expectMainUserCreated();
                break;
        }
        expectMainUserNotDemoted();
    }

    private HsumBootUserInitializer createHsumBootUserInitializer(
            boolean shouldAlwaysHaveMainUser, boolean shouldCreateInitialUser) {
        mTracer = new TimingsTraceAndSlog(TAG);
        return new HsumBootUserInitializer(mMockUms, mMockAms, mMockPms, mMockContentResolver,
                shouldAlwaysHaveMainUser, shouldCreateInitialUser);
    }

    private void expectMainUserCreated() {
        expectUserCreated(UserInfo.FLAG_ADMIN | UserInfo.FLAG_MAIN);
    }

    private void expectAdminUserCreated() {
        expectUserCreated(UserInfo.FLAG_ADMIN);
    }

    private void expectUserCreated(@UserInfoFlag int flags) {
        try {
            verify(mMockUms).createUserInternalUnchecked(/* name= */ null,
                    UserManager.USER_TYPE_FULL_SECONDARY, flags, /* parentId= */ USER_NULL,
                    /* preCreated= */ false, /* disallowedPackages= */ null, /* token= */ null,
                    /* devicePolicyUserRestrictions= */ null);
        } catch (Exception e) {
            String msg = "didn't create user with flags " + flags;
            Log.e(TAG, msg, e);
            expect.withMessage(msg).fail();
        }
    }

    private void expectNoUserCreated() {
        try {
            verify(mMockUms, never()).createUserInternalUnchecked(any(), any(), anyInt(), anyInt(),
                    anyBoolean(), any(), any(), any());
        } catch (Exception e) {
            String msg = "shouldn't have created any user";
            Log.e(TAG, msg, e);
            expect.withMessage(msg).fail();
        }

        // Since the user was not created, we can automatically infer that the boot user should not
        // have been set as well
        expectSetBootUserIdNeverCalled();
    }

    private void expectMainUserDemoted() {
        try {
            verify(mMockUms).demoteMainUser();
        } catch (Exception e) {
            String msg = "should have demoted main user";
            Log.e(TAG, msg, e);
            expect.withMessage(msg).fail();
        }
    }

    private void expectMainUserNotDemoted() {
        try {
            verify(mMockUms, never()).demoteMainUser();
        } catch (Exception e) {
            String msg = "should not have demoted main user";
            Log.e(TAG, msg, e);
            expect.withMessage(msg).fail();
        }
    }

    private void expectAdminPromotedToMainUser(@UserIdInt int userId) {
        try {
            verify(mMockUms).setMainUser(userId);
        } catch (Exception e) {
            String msg = "should have set main user as " + userId;
            Log.e(TAG, msg, e);
            expect.withMessage(msg).fail();
        }
    }

    private void expectAdminNotPromotedToMainUser(@UserIdInt int userId) {
        try {
            verify(mMockUms, never()).setMainUser(userId);
        } catch (Exception e) {
            String msg = "should have not set main user as " + userId;
            Log.e(TAG, msg, e);
            expect.withMessage(msg).fail();
        }
    }

    private void expectNoAdminPromotedToMainUser() {
        try {
            verify(mMockUms, never()).setMainUser(anyInt());
        } catch (Exception e) {
            String msg = "should not have set main user";
            Log.e(TAG, msg, e);
            expect.withMessage(msg).fail();
        }
    }

    private void expectSetBootUserId(@UserIdInt int userId) {
        try {
            verify(mMockUms).setBootUserIdUnchecked(userId);
        } catch (Exception e) {
            String msg = "didn't call setBootUserId(" +  userId + ")";
            Log.e(TAG, msg, e);
            expect.withMessage(msg).fail();
        }
    }

    private void expectSetBootUserIdNeverCalled() {
        try {
            verify(mMockUms, never()).setBootUserIdUnchecked(anyInt());
        } catch (Exception e) {
            String msg = "setBootUserId() should never be called";
            Log.e(TAG, msg, e);
            expect.withMessage(msg).fail();
        }
    }

    private void mockCreateNewUser(@UserIdInt int userId) throws Exception {
        @SuppressWarnings("deprecation")
        UserInfo userInfo = new UserInfo();
        userInfo.id = userId;
        Log.d(TAG, "createUserEvenWhenDisallowed() will return " + userInfo);
        when(mMockUms.createUserInternalUnchecked(any(), any(), anyInt(), anyInt(), anyBoolean(),
                any(), any(), any())).thenReturn(userInfo);
    }

    private void mockGetMainUserId(@CanBeNULL @UserIdInt int userId) {
        Log.d(TAG, "mockGetMainUserId(): " + userId);
        when(mMockUms.getMainUserId()).thenReturn(userId);
    }

    private void mockGetUsers(UserInfo... users) {
        List<UserInfo> asList = new ArrayList<>(users.length);
        int[] userIds = new int[users.length];
        for (int i = 0; i < users.length; i++) {
            var user = users[i];
            asList.add(user);
            userIds[i] = user.id;
        }
        Log.d(TAG, "mockGetUsers(): returning " + asList + " for getUsers(), and "
                + Arrays.toString(userIds) + " to getUserIds()");

        when(mMockUms.getUsers(/* excludingDying= */ true)).thenReturn(asList);
        when(mMockUms.getUserIds()).thenReturn(userIds);
    }

    private void mockPromoteToMainUser(@UserIdInt int userId) {
        Log.d(TAG, "mockPromoteToMainUser(): " + userId);
        when(mMockUms.setMainUser(userId)).thenReturn(true);
    }

    private void mockPromoteToMainUserFails(@UserIdInt int userId) {
        Log.d(TAG, "mockPromoteToMainUserFails(): " + userId);
        when(mMockUms.setMainUser(userId)).thenReturn(false);
    }

    private static UserInfo createUser(@UserIdInt int userId, @UserInfoFlag int flags) {
        return new UserInfo(userId, /* name= */ null, /* iconPath= */ null, flags,
                // Not using userType (for now)
                /* userType= */ "AB Positive");
    }

    // NOTE: enums below must be public to be static imported

    public enum InitialUsers {
        SYSTEM_ONLY,
        SYSTEM_AND_MAIN,
        SYSTEM_AND_ADMINS,
        SYSTEM_AND_ADMINS_FIRST_ADMIN_UNPROMOTABLE, // hacky case to mock failure
        SYSTEM_AND_REGULAR
    }

    public enum ExpectedResult {
        NO_USER_CREATED,
        MAIN_USER_CREATED,
        MAIN_USER_DEMOTED,
        FIRST_ADMIN_USER_PROMOTED_TO_MAIN,
        SECOND_ADMIN_USER_PROMOTED_TO_MAIN,
        ADMIN_USER_CREATED
    }
}
