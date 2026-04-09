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

package com.android.packageinstaller;

import static android.Manifest.permission;
import static android.content.pm.PackageManager.MATCH_ARCHIVED_PACKAGES;
import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import android.app.Activity;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

import com.android.packageinstaller.v2.ui.UnarchiveLaunch;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

public class UnarchiveActivity extends Activity {

    public static final String EXTRA_UNARCHIVE_INTENT_SENDER =
            "android.content.pm.extra.UNARCHIVE_INTENT_SENDER";
    static final String APP_TITLE = "com.android.packageinstaller.unarchive.app_title";
    static final String INSTALLER_TITLE = "com.android.packageinstaller.unarchive.installer_title";

    private static final String TAG = "UnarchiveActivity";

    private String mPackageName;
    private IntentSender mIntentSender;

    @Override
    public void onCreate(Bundle icicle) {
        getWindow().addSystemFlags(SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);

        super.onCreate(null);

        int callingUid = getLaunchedFromUid();
        if (callingUid == Process.INVALID_UID) {
            // Cannot reach Package/ActivityManager. Aborting uninstall.
            Log.e(TAG, "Could not determine the launching uid.");

            setResult(Activity.RESULT_FIRST_USER);
            finish();
            return;
        }

        PackageManager packageManager = getPackageManager();
        String callingPackage = PackageUtil.getPackageNameForUid(packageManager, callingUid);
        if (callingPackage == null) {
            Log.e(TAG, "Package not found for originating uid " + callingUid);
            setResult(Activity.RESULT_FIRST_USER);
            finish();
            return;
        }

        // We don't check the AppOpsManager here for REQUEST_INSTALL_PACKAGES because the requester
        // is not the source of the installation.
        boolean hasRequestInstallPermission = Arrays.asList(
                PackageUtil.getRequestedPermissions(packageManager, callingPackage))
                .contains(permission.REQUEST_INSTALL_PACKAGES);
        boolean hasInstallPermission = getBaseContext().checkPermission(permission.INSTALL_PACKAGES,
                0 /* random value for pid */, callingUid) == PackageManager.PERMISSION_GRANTED;
        if (!hasRequestInstallPermission && !hasInstallPermission) {
            Log.e(TAG, "Uid " + callingUid + " does not have "
                    + permission.REQUEST_INSTALL_PACKAGES + " or "
                    + permission.INSTALL_PACKAGES);
            setResult(Activity.RESULT_FIRST_USER);
            finish();
            return;
        }


        if (PackageUtil.isVersionTwoEnabled(this)) {
            Intent piaV2 = new Intent(getIntent());
            piaV2.putExtra(UnarchiveLaunch.EXTRA_CALLING_PKG_NAME, getLaunchedFromPackage());
            piaV2.putExtra(UnarchiveLaunch.EXTRA_CALLING_PKG_UID, getLaunchedFromUid());
            piaV2.setClass(this, UnarchiveLaunch.class);
            piaV2.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
            startActivity(piaV2);
            finish();
            return;
        }

        Bundle extras = getIntent().getExtras();
        mPackageName = extras.getString(PackageInstaller.EXTRA_PACKAGE_NAME);
        mIntentSender = extras.getParcelable(EXTRA_UNARCHIVE_INTENT_SENDER, IntentSender.class);
        Objects.requireNonNull(mPackageName);
        Objects.requireNonNull(mIntentSender);

        try {
            String appTitle = packageManager.getApplicationInfo(mPackageName,
                    PackageManager.ApplicationInfoFlags.of(
                            MATCH_ARCHIVED_PACKAGES)).loadLabel(packageManager).toString();
            String installerTitle = getResponsibleInstallerTitle(packageManager,
                    packageManager.getInstallSourceInfo(mPackageName));
            showDialogFragment(appTitle, installerTitle);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Invalid packageName: " + e.getMessage());
        }
    }

    private String getResponsibleInstallerTitle(PackageManager pm,
            InstallSourceInfo installSource)
            throws PackageManager.NameNotFoundException {
        String packageName = TextUtils.isEmpty(installSource.getUpdateOwnerPackageName())
                ? installSource.getInstallingPackageName()
                : installSource.getUpdateOwnerPackageName();
        if (packageName == null) {
            // Should be unreachable.
            Log.e(TAG, "Installer not found.");
            setResult(Activity.RESULT_FIRST_USER);
            finish();
            return "";
        }
        return pm.getApplicationInfo(packageName, /* flags= */ 0).loadLabel(pm).toString();
    }

    void startUnarchive() {
        try {
            getPackageManager().getPackageInstaller().requestUnarchive(mPackageName, mIntentSender);
        } catch (PackageManager.NameNotFoundException | IOException e) {
            Log.e(TAG, "RequestUnarchive failed with %s." + e.getMessage());
        }
    }

    private void showDialogFragment(String appTitle, String installerAppTitle) {
        FragmentTransaction ft = getFragmentManager().beginTransaction();
        Fragment prev = getFragmentManager().findFragmentByTag("dialog");
        if (prev != null) {
            ft.remove(prev);
        }

        Bundle args = new Bundle();
        args.putString(APP_TITLE, appTitle);
        args.putString(INSTALLER_TITLE, installerAppTitle);
        DialogFragment fragment = new UnarchiveFragment();
        fragment.setArguments(args);
        fragment.show(ft, "dialog");
    }
}
