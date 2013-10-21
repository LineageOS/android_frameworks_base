/*
 * Copyright (c) 2013, The Linux Foundation. All rights reserved.
 * Not a Contribution.
 *
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.server;

import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Message;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.TextView;

import com.android.internal.R;

public class PermissionDialog extends AlertDialog {
    private final static String TAG = "PermissionDialog";

    private final AppOpsService mService;
    private final String mPackageName;
    private final int mCode;
    private View  mView;
    private CheckBox mChoice;
    private int mUid;
    final CharSequence[] mOpLabels;
    private Context mContext;
    private boolean mConsuming;

    // Event 'what' codes
    private static final int MSG_START = 1;
    private static final int MSG_ALLOWED = 2;
    private static final int MSG_IGNORED = 3;
    private static final int MSG_IGNORED_TIMEOUT = 4;

    // 15s timeout, then we automatically dismiss the permission dialog.
    // Otherwise, it may cause watchdog timeout sometimes.
    private static final long DISMISS_TIMEOUT = 1000 * 15 * 1;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_START) {
                mConsuming = false;
                setEnabled(true);
            } else {
                int mode;
                boolean remember = mChoice.isChecked();
                switch (msg.what) {
                    case MSG_ALLOWED:
                        mode = AppOpsManager.MODE_ALLOWED;
                        break;
                    case MSG_IGNORED:
                        mode = AppOpsManager.MODE_IGNORED;
                        break;
                    default:
                        mode = AppOpsManager.MODE_IGNORED;
                        remember = false;
                        break;
                }
                mService.notifyOperation(mCode, mUid, mPackageName, mode, remember);
                dismiss();
            }
        }
    };

    public PermissionDialog(Context context, AppOpsService service,
            int code, int uid, String packageName) {
        super(context, com.android.internal.R.style.Theme_Dialog_AppError);

        mContext = context;
        Resources res = context.getResources();

        mService = service;
        mCode = code;
        mPackageName = packageName;
        mUid = uid;
        mOpLabels = res.getTextArray(com.android.internal.R.array.app_ops_labels);

        getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);

        setTitle(res.getString(com.android.internal.R.string.privacy_guard_dialog_title));
        setIconAttribute(R.attr.alertDialogIcon);
        setCancelable(false);

        setButton(DialogInterface.BUTTON_POSITIVE,
                res.getString(com.android.internal.R.string.allow),
                mHandler.obtainMessage(MSG_ALLOWED));

        setButton(DialogInterface.BUTTON_NEGATIVE,
                res.getString(com.android.internal.R.string.deny),
                mHandler.obtainMessage(MSG_IGNORED));

        final CharSequence appName = getAppName(mPackageName);
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.setTitle("Permission info: " + appName);
        attrs.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SYSTEM_ERROR
                | WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        getWindow().setAttributes(attrs);

        mView = getLayoutInflater().inflate(
                com.android.internal.R.layout.permission_confirmation_dialog, null);
        TextView tv = (TextView) mView.findViewById(com.android.internal.R.id.permission_text);
        mChoice = (CheckBox) mView.findViewById(
                com.android.internal.R.id.permission_remember_choice_checkbox);
        tv.setText(mContext.getString(com.android.internal.R.string.privacy_guard_dialog_summary,
                appName, mOpLabels[mCode]));
        setView(mView);

        // After the timeout, pretend the user clicked the quit button
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_IGNORED_TIMEOUT), DISMISS_TIMEOUT);
    }

    public void ignore() {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_IGNORED_TIMEOUT));
    }

    @Override
    public void onStart() {
        super.onStart();
        setEnabled(false);
        mHandler.sendMessage(mHandler.obtainMessage(MSG_START));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mConsuming) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private CharSequence getAppName(String packageName) {
        try {
            PackageManager pm = mContext.getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName,
                    PackageManager.GET_DISABLED_COMPONENTS
                    | PackageManager.GET_UNINSTALLED_PACKAGES);
            return pm.getApplicationLabel(appInfo);
        } catch (PackageManager.NameNotFoundException e) {
            // fall through to returning package name
        }
        return packageName;
    }

    private void setEnabled(boolean enabled) {
        View pos = getButton(DialogInterface.BUTTON_POSITIVE);
        pos.setEnabled(enabled);

        View neg = getButton(DialogInterface.BUTTON_NEGATIVE);
        neg.setEnabled(enabled);
    }
}
