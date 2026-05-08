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

package com.android.packageinstaller.v2.ui.fragments;

import static android.content.pm.PackageInstaller.DEVELOPER_VERIFICATION_POLICY_BLOCK_FAIL_CLOSED;
import static android.content.pm.PackageInstaller.DeveloperVerificationUserConfirmationInfo.DEVELOPER_VERIFICATION_USER_ACTION_NEEDED_REASON_DEVELOPER_BLOCKED;
import static android.content.pm.PackageInstaller.DeveloperVerificationUserConfirmationInfo.DEVELOPER_VERIFICATION_USER_ACTION_NEEDED_REASON_NETWORK_UNAVAILABLE;
import static android.content.pm.PackageInstaller.DeveloperVerificationUserConfirmationInfo.DEVELOPER_VERIFICATION_USER_ACTION_NEEDED_REASON_UNKNOWN;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import com.android.packageinstaller.R;
import com.android.packageinstaller.v2.model.InstallAborted;
import com.android.packageinstaller.v2.model.InstallFailed;
import com.android.packageinstaller.v2.model.InstallInstalling;
import com.android.packageinstaller.v2.model.InstallStage;
import com.android.packageinstaller.v2.model.InstallSuccess;
import com.android.packageinstaller.v2.model.InstallUserActionRequired;
import com.android.packageinstaller.v2.model.InstallVerificationFailure;
import com.android.packageinstaller.v2.ui.InstallActionListener;
import com.android.packageinstaller.v2.ui.UiUtil;
import com.android.packageinstaller.v2.viewmodel.InstallViewModel;

import java.util.List;

/**
 * The dialogFragment to handle the installation.
 */
public class InstallationFragment extends DialogFragment {

    public static final String LOG_TAG = InstallationFragment.class.getSimpleName();
    @NonNull
    private InstallActionListener mInstallActionListener;
    @NonNull
    private Dialog mDialog;

    private ImageView mAppIcon = null;
    private TextView mAppLabelTextView = null;
    private View mAppSnippet = null;
    private TextView mCustomMessageTextView = null;
    private View mCustomViewPanel = null;
    private ProgressBar mProgressBar = null;
    private ProgressBar mIndeterminateProgressBar = null;
    private View mTitleTemplate = null;
    private View mMoreDetailsClickableLayout = null;
    private View mMoreDetailsExpandedLayout = null;
    private boolean mIsMoreDetailsExpanded = false;
    private View mButtonPanel = null;

    private TextView mInstallWithoutVerifyingTextView = null;
    private TextView mMoreDetailsExpandedTextView = null;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mInstallActionListener = (InstallActionListener) context;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        final InstallStage installStage = getCurrentInstallStage();

        // There is no root view here. Ok to pass null view root
        @SuppressWarnings("InflateParams")
        View dialogView = getLayoutInflater().inflate(
                UiUtil.getInstallationLayoutResId(requireContext()), null);
        mAppSnippet = dialogView.requireViewById(R.id.app_snippet);
        mAppIcon = dialogView.requireViewById(R.id.app_icon);
        mAppLabelTextView = dialogView.requireViewById(R.id.app_label);
        mCustomViewPanel = dialogView.requireViewById(R.id.custom_view_panel);
        mIndeterminateProgressBar = dialogView.requireViewById(R.id.indeterminate_progress_bar);
        mProgressBar = dialogView.requireViewById(R.id.progress_bar);
        mCustomMessageTextView = dialogView.requireViewById(R.id.custom_message);
        mCustomMessageTextView.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        mMoreDetailsClickableLayout = dialogView.requireViewById(
                R.id.more_details_clickable_layout);
        mMoreDetailsExpandedLayout = dialogView.requireViewById(
                R.id.more_details_expanded_layout);
        mInstallWithoutVerifyingTextView = dialogView.requireViewById(
                R.id.install_without_verifying_text);
        mMoreDetailsExpandedTextView = dialogView.requireViewById(R.id.more_details_expanded_text);

        String title = getString(R.string.title_install_staging);
        mDialog = UiUtil.getAlertDialog(requireContext(), title, dialogView,
                R.string.button_install, R.string.button_cancel,
                /* positiveBtnListener= */ null,
                (dialog, which) -> {
                    mInstallActionListener.onNegativeResponse(installStage.getStageCode());
                });

        return mDialog;
    }

    private InstallStage getCurrentInstallStage() {
        return new ViewModelProvider(requireActivity()).get(InstallViewModel.class)
                .getCurrentInstallStage().getValue();
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);

        mInstallActionListener.onNegativeResponse(getCurrentInstallStage().getStageCode());
    }

    @Override
    @RequiresApi(Build.VERSION_CODES_FULL.BAKLAVA_1)
    public void onStart() {
        super.onStart();
        updateUI();
    }

    @Override
    public void onPause() {
        super.onPause();
        // This prevents tapjacking since an overlay activity started in front of Pia will
        // cause Pia to be paused.
        Button button = UiUtil.getAlertDialogPositiveButton(mDialog);
        // If the button is not clickable, don't need to set enabled to false
        if (button != null && button.isClickable()) {
            button.setEnabled(false);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        Button button = UiUtil.getAlertDialogPositiveButton(mDialog);
        if (button != null) {
            button.setEnabled(true);
        }
    }

    /**
     * Update the UI based on the current install stage
     */
    @RequiresApi(Build.VERSION_CODES_FULL.BAKLAVA_1)
    public void updateUI() {
        if (!isAdded()) {
            return;
        }

        // Get the current install stage
        final InstallStage installStage = getCurrentInstallStage();
        Log.i(LOG_TAG, "updateUI " + LOG_TAG + "\n" + installStage.getStageCode());

        this.setCancelable(true);

        // When A11y is enabled, if there are no buttons in some cases E.g. installing,
        // the button panel should not be focused without any descriptions. Set the button
        // panel is not focusable to avoid it being focused.
        if (mButtonPanel == null) {
            mButtonPanel =
                    mDialog.requireViewById(UiUtil.getAlertDialogButtonPanelId(requireContext()));
            mButtonPanel.setFocusable(false);
        }

        // show the title and reset the paddings of the custom message textview
        if (mTitleTemplate != null) {
            mTitleTemplate.setVisibility(View.VISIBLE);
            mCustomMessageTextView.setPadding(0, 0, 0, 0);
        }
        // hide the more details layout by default
        mMoreDetailsClickableLayout.setVisibility(View.GONE);
        mMoreDetailsExpandedLayout.setVisibility(View.GONE);

        // Reset the paddings of the custom view panel
        final int paddingHorizontal = mCustomViewPanel.getPaddingStart();
        final int paddingTop = mCustomViewPanel.getPaddingTop();
        final int paddingBottom =
                getResources().getDimensionPixelOffset(R.dimen.alert_dialog_inner_padding);
        mCustomViewPanel.setPadding(paddingHorizontal, paddingTop,
                paddingHorizontal, paddingBottom);

        // Reset the movement method to avoid unexpected issue
        mCustomMessageTextView.setMovementMethod(null);

        switch (installStage.getStageCode()) {
            case InstallStage.STAGE_ABORTED -> {
                updateInstallAbortedUI(mDialog, (InstallAborted) installStage);
            }
            case InstallStage.STAGE_FAILED -> {
                updateInstallFailedUI(mDialog, (InstallFailed) installStage);
            }
            case InstallStage.STAGE_INSTALLING -> {
                updateInstallInstallingUI(mDialog, (InstallInstalling) installStage);
            }
            case InstallStage.STAGE_STAGING -> {
                updateInstallStagingUI(mDialog);
            }
            case InstallStage.STAGE_SUCCESS -> {
                updateInstallSuccessUI(mDialog, (InstallSuccess) installStage);
            }
            case InstallStage.STAGE_USER_ACTION_REQUIRED -> {
                updateUserActionRequiredUI(mDialog, (InstallUserActionRequired) installStage);
            }
            case InstallStage.STAGE_VERIFICATION_FAILURE -> {
                updateVerificationFailureUI(mDialog, (InstallVerificationFailure) installStage);
            }
        }

        UiUtil.updateButtonBarLayoutIfNeeded(requireContext(), mDialog);
    }

    private void updateInstallAbortedUI(Dialog dialog, InstallAborted installStage) {
        mAppSnippet.setVisibility(View.GONE);
        mIndeterminateProgressBar.setVisibility(View.GONE);
        mProgressBar.setVisibility(View.GONE);

        mCustomMessageTextView.setVisibility(View.VISIBLE);

        // Set the message
        mCustomMessageTextView.setText(R.string.message_parse_failed);

        // Set the title
        dialog.setTitle(R.string.title_cant_install_app);

        // Hide the positive button
        Button positiveButton = UiUtil.getAlertDialogPositiveButton(dialog);
        if (positiveButton != null) {
            positiveButton.setVisibility(View.GONE);
        }

        // Set the negative button and the listener
        Button negativeButton = UiUtil.getAlertDialogNegativeButton(dialog);
        if (negativeButton != null) {
            negativeButton.setVisibility(View.VISIBLE);
            UiUtil.applyOutlinedButtonStyle(requireContext(), negativeButton);
            negativeButton.setText(R.string.button_close);
            negativeButton.setOnClickListener(view -> {
                // Set clickable of the button to false to avoid the user clicks it
                // more than once quickly
                view.setClickable(false);
                mInstallActionListener.onNegativeResponse(
                        installStage.getActivityResultCode(), installStage.getResultIntent());
            });
        }
    }

    private void updateInstallFailedUI(Dialog dialog, InstallFailed installStage) {
        mIndeterminateProgressBar.setVisibility(View.GONE);
        mProgressBar.setVisibility(View.GONE);

        mAppSnippet.setVisibility(View.VISIBLE);
        mCustomMessageTextView.setVisibility(View.VISIBLE);

        Log.i(LOG_TAG, "Installation status code: " + installStage.getLegacyCode());

        // Set the app icon and label
        mAppIcon.setImageDrawable(installStage.getAppIcon());
        mAppLabelTextView.setText(installStage.getAppLabel());

        // Sometimes the A11y focus is on the button E.g. ADI. We should align the other cases.
        // Request the A11y focus on the app label.
        mAppLabelTextView.post(() -> mAppLabelTextView.performAccessibilityAction(
                    AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));

        int titleResId = R.string.title_install_failed_not_installed;
        String positiveButtonText = null;
        View.OnClickListener positiveButtonListener = null;

        switch (installStage.getLegacyCode()) {
            case PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                mCustomMessageTextView.setText(R.string.message_install_failed_blocked);
                titleResId = R.string.title_install_failed_blocked;
            }
            case PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                mCustomMessageTextView.setText(R.string.message_install_failed_conflict);
                titleResId = R.string.title_cant_install_app;
            }
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                mCustomMessageTextView.setText(R.string.message_install_failed_incompatible);
                titleResId = R.string.title_install_failed_incompatible;
            }
            case PackageInstaller.STATUS_FAILURE_INVALID -> {
                mCustomMessageTextView.setText(R.string.message_install_failed_invalid);
                titleResId = R.string.title_cant_install_app;
            }
            case PackageInstaller.STATUS_FAILURE_STORAGE -> {
                mCustomMessageTextView.setText(R.string.message_install_failed_less_storage);
                titleResId = R.string.title_install_failed_less_storage;
                positiveButtonText = getString(R.string.button_manage_apps);
                positiveButtonListener = (view) -> {
                    // Set clickable of the button to false to avoid the user clicks it
                    // more than once quickly
                    view.setClickable(false);
                    mInstallActionListener.sendManageAppsIntent();
                };
            }
            default -> {
                mCustomMessageTextView.setVisibility(View.GONE);
            }
        }

        // Set the title
        dialog.setTitle(titleResId);

        // Set the positive button and set the listener if needed
        Button positiveButton = UiUtil.getAlertDialogPositiveButton(dialog);
        if (positiveButton != null) {
            if (positiveButtonText == null) {
                positiveButton.setVisibility(View.GONE);
            } else {
                positiveButton.setVisibility(View.VISIBLE);
                UiUtil.applyFilledButtonStyle(requireContext(), positiveButton);
                positiveButton.setText(positiveButtonText);
                positiveButton.setFilterTouchesWhenObscured(true);
                positiveButton.setOnClickListener(positiveButtonListener);
            }
        }

        // Set the negative button and set the listener
        Button negativeButton = UiUtil.getAlertDialogNegativeButton(dialog);
        if (negativeButton != null) {
            negativeButton.setVisibility(View.VISIBLE);
            UiUtil.applyOutlinedButtonStyle(requireContext(), negativeButton);
            negativeButton.setText(R.string.button_close);
            negativeButton.setOnClickListener(view -> {
                // Set clickable of the button to false to avoid the user clicks it
                // more than once quickly
                view.setClickable(false);
                mInstallActionListener.onNegativeResponse(installStage.getStageCode());
            });
        }
    }

    private void updateInstallInstallingUI(Dialog dialog, InstallInstalling installStage) {
        mCustomMessageTextView.setVisibility(View.GONE);
        mProgressBar.setVisibility(View.GONE);

        mAppSnippet.setVisibility(View.VISIBLE);
        mIndeterminateProgressBar.setVisibility(View.VISIBLE);

        // Update the padding of the custom view panel
        final int paddingHorizontal = mCustomViewPanel.getPaddingStart();
        final int paddingTop = mCustomViewPanel.getPaddingTop();
        final int paddingBottom = 0;
        mCustomViewPanel.setPadding(paddingHorizontal, paddingTop,
                paddingHorizontal, paddingBottom);

        // Set the app icon, label and progress bar
        mAppIcon.setImageDrawable(installStage.getAppIcon());
        mAppLabelTextView.setText(installStage.getAppLabel());

        // Set the title
        final int titleResId = installStage.isAppUpdating()
                ? R.string.title_updating : R.string.title_installing;
        dialog.setTitle(titleResId);

        // Hide the buttons
        Button positiveButton = UiUtil.getAlertDialogPositiveButton(dialog);
        if (positiveButton != null) {
            positiveButton.setVisibility(View.GONE);
        }
        Button negativeButton = UiUtil.getAlertDialogNegativeButton(dialog);
        if (negativeButton != null) {
            negativeButton.setVisibility(View.GONE);
        }

        // Cancelable is false
        this.setCancelable(false);
    }

    private void updateInstallStagingUI(@NonNull Dialog dialog) {
        mAppSnippet.setVisibility(View.GONE);
        mCustomMessageTextView.setVisibility(View.GONE);
        mIndeterminateProgressBar.setVisibility(View.GONE);

        mProgressBar.setVisibility(View.VISIBLE);

        // Set the title
        dialog.setTitle(R.string.title_install_staging);

        // Set the progress bar
        mProgressBar.setProgress(0);

        // Hide the positive button
        Button positiveButton = UiUtil.getAlertDialogPositiveButton(dialog);
        if (positiveButton != null) {
            positiveButton.setVisibility(View.GONE);
        }

        // Set the negative button
        Button negativeButton = UiUtil.getAlertDialogNegativeButton(dialog);
        if (negativeButton != null) {
            negativeButton.setVisibility(View.VISIBLE);
            UiUtil.applyOutlinedButtonStyle(requireContext(), negativeButton);
            negativeButton.setText(R.string.button_cancel);
            negativeButton.setOnClickListener(view -> {
                // Set clickable of the button to false to avoid the user clicks it
                // more than once quickly
                view.setClickable(false);
                mInstallActionListener.onNegativeResponse(InstallStage.STAGE_STAGING);
            });
        }

        this.setCancelable(false);
    }

    private void updateInstallSuccessUI(Dialog dialog, InstallSuccess installStage) {
        mCustomMessageTextView.setVisibility(View.GONE);
        mIndeterminateProgressBar.setVisibility(View.GONE);
        mProgressBar.setVisibility(View.GONE);

        mAppSnippet.setVisibility(View.VISIBLE);

        // Set the app icon and label
        mAppIcon.setImageDrawable(installStage.getAppIcon());
        mAppLabelTextView.setText(installStage.getAppLabel());

        // Set the title
        final int titleResId = installStage.isAppUpdating()
                ? R.string.title_updated : R.string.title_installed;
        dialog.setTitle(titleResId);

        // If there is an activity entry, show the positive button
        final Intent resultIntent = installStage.getResultIntent();
        boolean hasEntry = false;
        if (resultIntent != null) {
            final List<ResolveInfo> list =
                    requireContext().getPackageManager().queryIntentActivities(resultIntent, 0);
            if (!list.isEmpty()) {
                hasEntry = true;
            }
        }

        Button positiveButton = UiUtil.getAlertDialogPositiveButton(dialog);
        if (positiveButton != null) {
            if (hasEntry) {
                positiveButton.setVisibility(View.VISIBLE);
                UiUtil.applyFilledButtonStyle(requireContext(), positiveButton);
                positiveButton.setText(R.string.button_open);
                positiveButton.setOnClickListener(view -> {
                    Log.i(LOG_TAG, "Finished installing and launching "
                            + installStage.getAppLabel());
                    // Set clickable of the button to false to avoid the user clicks it
                    // more than once quickly
                    view.setClickable(false);
                    mInstallActionListener.openInstalledApp(resultIntent);
                });
            } else {
                positiveButton.setVisibility(View.GONE);
            }
        }

        // Show the Done button
        Button negativeButton = UiUtil.getAlertDialogNegativeButton(dialog);
        if (negativeButton != null) {
            negativeButton.setVisibility(View.VISIBLE);
            UiUtil.applyOutlinedButtonStyle(requireContext(), negativeButton);
            negativeButton.setText(R.string.button_done);
            negativeButton.setOnClickListener(view -> {
                // Set clickable of the button to false to avoid the user clicks it
                // more than once quickly
                view.setClickable(false);
                mInstallActionListener.onNegativeResponse(installStage.getStageCode());
            });
        }
    }

    @RequiresApi(Build.VERSION_CODES_FULL.BAKLAVA_1)
    private void updateUserActionRequiredUI(Dialog dialog, InstallUserActionRequired installStage) {
        switch (installStage.getActionReason()) {
            case InstallUserActionRequired.USER_ACTION_REASON_INSTALL_CONFIRMATION -> {
                updateInstallConfirmationUI(dialog, installStage);
            }
            case InstallUserActionRequired.USER_ACTION_REASON_UNKNOWN_SOURCE -> {
                updateUnknownSourceUI(dialog, installStage);
            }
            case InstallUserActionRequired.USER_ACTION_REASON_ANONYMOUS_SOURCE -> {
                updateAnonymousSourceUI(dialog, installStage);
            }
            case InstallUserActionRequired.USER_ACTION_REASON_VERIFICATION_CONFIRMATION -> {
                updateVerificationConfirmationUI(dialog, installStage);
            }
        }
    }

    private void updateUnknownSourceUI(Dialog dialog, InstallUserActionRequired installStage) {
        mIndeterminateProgressBar.setVisibility(View.GONE);
        mProgressBar.setVisibility(View.GONE);

        mAppSnippet.setVisibility(View.VISIBLE);
        mCustomMessageTextView.setVisibility(View.VISIBLE);

        // Set the app icon and label
        mAppIcon.setImageDrawable(installStage.getAppIcon());
        mAppLabelTextView.setText(installStage.getAppLabel());

        // Set the title and the message
        dialog.setTitle(R.string.title_unknown_source_blocked);
        mCustomMessageTextView.setText(R.string.message_external_source_blocked);

        // Set the button be a text button and set the listener
        Button positiveButton = UiUtil.getAlertDialogPositiveButton(dialog);
        if (positiveButton != null) {
            positiveButton.setVisibility(View.VISIBLE);
            UiUtil.applyTextButtonStyle(requireContext(), positiveButton);
            positiveButton.setText(R.string.external_sources_settings);
            positiveButton.setFilterTouchesWhenObscured(true);
            positiveButton.setOnClickListener(view -> {
                // Set clickable of the button to false to avoid the user clicks it
                // more than once quickly
                view.setClickable(false);
                mInstallActionListener.sendUnknownAppsIntent(
                        installStage.getUnknownSourcePackageName());
            });
        }

        // Set the button be a text button and set the listener
        Button negativeButton = UiUtil.getAlertDialogNegativeButton(dialog);
        if (negativeButton != null) {
            negativeButton.setVisibility(View.VISIBLE);
            UiUtil.applyTextButtonStyle(requireContext(), negativeButton);
            negativeButton.setText(R.string.button_cancel);
            negativeButton.setOnClickListener(view -> {
                // Set clickable of the button to false to avoid the user clicks it
                // more than once quickly
                view.setClickable(false);
                mInstallActionListener.onNegativeResponse(installStage.getStageCode());
            });
        }
    }

    private void updateAnonymousSourceUI(Dialog dialog, InstallUserActionRequired installStage) {
        mAppSnippet.setVisibility(View.GONE);
        mIndeterminateProgressBar.setVisibility(View.GONE);
        mProgressBar.setVisibility(View.GONE);

        mCustomMessageTextView.setVisibility(View.VISIBLE);

        // Hide the title and set the message
        mCustomMessageTextView.setText(R.string.message_anonymous_source_warning);
        dialog.setTitle("");
        mTitleTemplate =
                dialog.findViewById(UiUtil.getAlertDialogTitleTemplateId(requireContext()));
        if (mTitleTemplate != null) {
            mTitleTemplate.setVisibility(View.GONE);
            final int expectedSpace =
                    getResources().getDimensionPixelOffset(R.dimen.alert_dialog_inner_padding);
            final int currentSpace =
                    getResources().getDimensionPixelOffset(R.dimen.dialog_inter_element_margin);
            mCustomMessageTextView.setPadding(0, expectedSpace - currentSpace, 0, 0);
        }

        // Set the button be a text button and set the listener
        Button positiveButton = UiUtil.getAlertDialogPositiveButton(dialog);
        if (positiveButton != null) {
            positiveButton.setVisibility(View.VISIBLE);
            UiUtil.applyTextButtonStyle(requireContext(), positiveButton);
            positiveButton.setText(R.string.button_continue);
            positiveButton.setFilterTouchesWhenObscured(true);
            positiveButton.setOnClickListener(view -> {
                // Set clickable of the button to false to avoid the user clicks it
                // more than once quickly
                view.setClickable(false);
                mInstallActionListener.onPositiveResponse(
                        InstallUserActionRequired.USER_ACTION_REASON_ANONYMOUS_SOURCE);
            });
        }

        // Set the button be a text button and set the listener
        Button negativeButton = UiUtil.getAlertDialogNegativeButton(dialog);
        if (negativeButton != null) {
            negativeButton.setVisibility(View.VISIBLE);
            UiUtil.applyTextButtonStyle(requireContext(), negativeButton);
            negativeButton.setText(R.string.button_cancel);
            negativeButton.setOnClickListener(view -> {
                // Set clickable of the button to false to avoid the user clicks it
                // more than once quickly
                view.setClickable(false);
                mInstallActionListener.onNegativeResponse(installStage.getStageCode());
            });
        }
    }

    private void updateInstallConfirmationUI(Dialog dialog,
            InstallUserActionRequired installStage) {
        mCustomMessageTextView.setVisibility(View.GONE);
        mIndeterminateProgressBar.setVisibility(View.GONE);
        mProgressBar.setVisibility(View.GONE);

        mAppSnippet.setVisibility(View.VISIBLE);

        // Set the app icon and label
        mAppIcon.setImageDrawable(installStage.getAppIcon());
        mAppLabelTextView.setText(installStage.getAppLabel());

        // Set the title and the message
        String title = null;
        int positiveBtnTextRes = 0;
        boolean isUpdateOwnerShip = false;
        if (installStage.isAppUpdating()) {
            final String existingUpdateOwnerLabel =
                    installStage.getExistingUpdateOwnerLabel(requireContext());
            final String requestedUpdateOwnerLabel =
                    installStage.getRequestedUpdateOwnerLabel(requireContext());
            if (existingUpdateOwnerLabel != null && requestedUpdateOwnerLabel != null) {
                isUpdateOwnerShip = true;
                title = getString(R.string.title_update_ownership_change,
                        requestedUpdateOwnerLabel);
                positiveBtnTextRes = R.string.button_update_anyway;
                mCustomMessageTextView.setVisibility(View.VISIBLE);
                String updateOwnerString = getString(R.string.message_update_owner_change,
                        existingUpdateOwnerLabel);
                mCustomMessageTextView.setText(
                        Html.fromHtml(updateOwnerString, Html.FROM_HTML_MODE_LEGACY));
            } else {
                title = getString(R.string.title_update);
                positiveBtnTextRes = R.string.button_update;
            }
        } else {
            title = getString(R.string.title_install);
            positiveBtnTextRes = R.string.button_install;
        }
        dialog.setTitle(title);

        // Set the button and the listener
        Button positiveButton = UiUtil.getAlertDialogPositiveButton(dialog);
        if (positiveButton != null) {
            positiveButton.setVisibility(View.VISIBLE);
            if (isUpdateOwnerShip) {
                UiUtil.applyTextButtonStyle(requireContext(), positiveButton);
            } else {
                UiUtil.applyFilledButtonStyle(requireContext(), positiveButton);
            }
            positiveButton.setText(positiveBtnTextRes);
            positiveButton.setFilterTouchesWhenObscured(true);
            positiveButton.setOnClickListener(view -> {
                // Set clickable of the button to false to avoid the user clicks it
                // more than once quickly
                view.setClickable(false);
                mInstallActionListener.onPositiveResponse(
                        InstallUserActionRequired.USER_ACTION_REASON_INSTALL_CONFIRMATION);
            });
        }

        // Set the button and the listener
        Button negativeButton = UiUtil.getAlertDialogNegativeButton(dialog);
        if (negativeButton != null) {
            negativeButton.setVisibility(View.VISIBLE);
            if (isUpdateOwnerShip) {
                UiUtil.applyTextButtonStyle(requireContext(), negativeButton);
            } else {
                UiUtil.applyOutlinedButtonStyle(requireContext(), negativeButton);
            }
            negativeButton.setText(R.string.button_cancel);
            negativeButton.setOnClickListener(view -> {
                // Set clickable of the button to false to avoid the user clicks it
                // more than once quickly
                view.setClickable(false);
                mInstallActionListener.onNegativeResponse(installStage.getStageCode());
            });
        }
    }

    /**
     * Set the progress of the progress bar
     */
    public void setProgress(int progress) {
        if (mProgressBar != null) {
            mProgressBar.setProgress(progress);
        }
    }

    private void updateVerificationFailureUI(Dialog dialog,
            InstallVerificationFailure installStage) {
        mAppSnippet.setVisibility(View.GONE);
        mCustomMessageTextView.setVisibility(View.VISIBLE);
        mIndeterminateProgressBar.setVisibility(View.GONE);
        mProgressBar.setVisibility(View.GONE);
        // Disable clicking outside of the dialog
        this.setCancelable(false);

        final int verificationUserActionNeededReason = installStage.getFailureReason();
        // For failure case, use strings for policy DEVELOPER_VERIFICATION_POLICY_BLOCK_FAIL_CLOSED
        final int verificationPolicy = DEVELOPER_VERIFICATION_POLICY_BLOCK_FAIL_CLOSED;

        // Set title and main message
        int titleResId = getVerificationConfirmationTitleResourceId(
                verificationUserActionNeededReason);
        int msgResId = getVerificationConfirmationMessageResourceId(
                verificationUserActionNeededReason, verificationPolicy,
                installStage.isAppUpdating());
        dialog.setTitle(titleResId);
        mCustomMessageTextView.setText(
                Html.fromHtml(getString(msgResId), Html.FROM_HTML_MODE_LEGACY));
        mCustomMessageTextView.setMovementMethod(LinkMovementMethod.getInstance());

        // Set negative button
        Button negativeButton = UiUtil.getAlertDialogNegativeButton(dialog);
        if (negativeButton != null) {
            negativeButton.setVisibility(View.VISIBLE);
            negativeButton.setText(R.string.ok);
            negativeButton.setFilterTouchesWhenObscured(true);
            UiUtil.applyOutlinedButtonStyle(requireContext(), negativeButton);
            negativeButton.setOnClickListener(view -> {
                // Set clickable of the button to false to avoid the user clicks it
                // more than once quickly
                view.setClickable(false);
                mInstallActionListener.onNegativeResponse(installStage.getStageCode());
            });
        }

        // Hide the positive button
        Button positiveButton = UiUtil.getAlertDialogPositiveButton(dialog);
        if (positiveButton != null) {
            positiveButton.setVisibility(View.GONE);
        }
    }

    @RequiresApi(Build.VERSION_CODES_FULL.BAKLAVA_1)
    private void updateVerificationConfirmationUI(Dialog dialog,
            InstallUserActionRequired installStage) {
        mAppSnippet.setVisibility(View.VISIBLE);
        mCustomMessageTextView.setVisibility(View.VISIBLE);
        mIndeterminateProgressBar.setVisibility(View.GONE);
        mProgressBar.setVisibility(View.GONE);
        // Disable clicking outside of the dialog
        this.setCancelable(false);

        mAppIcon.setImageDrawable(installStage.getAppIcon());
        mAppLabelTextView.setText(installStage.getAppLabel());

        PackageInstaller.DeveloperVerificationUserConfirmationInfo verificationInfo =
                installStage.getVerificationInfo();
        assert verificationInfo != null;
        int verificationUserActionNeededReason = verificationInfo.getUserActionNeededReason();
        int verificationPolicy = verificationInfo.getVerificationPolicy();

        // Set title and main message
        int titleResId = getVerificationConfirmationTitleResourceId(
                verificationUserActionNeededReason);
        int msgResId = getVerificationConfirmationMessageResourceId(
                verificationUserActionNeededReason, verificationPolicy,
                installStage.isAppUpdating());
        dialog.setTitle(titleResId);
        mCustomMessageTextView.setText(
                Html.fromHtml(getString(msgResId), Html.FROM_HTML_MODE_LEGACY));
        mCustomMessageTextView.setMovementMethod(LinkMovementMethod.getInstance());

        // Set negative button
        Button negativeButton = UiUtil.getAlertDialogNegativeButton(dialog);
        if (negativeButton != null) {
            negativeButton.setVisibility(View.VISIBLE);
            negativeButton.setText(R.string.ok);
            negativeButton.setFilterTouchesWhenObscured(true);
            UiUtil.applyOutlinedButtonStyle(requireContext(), negativeButton);
            negativeButton.setOnClickListener(view -> {
                // Set clickable of the button to false to avoid the user clicks it
                // more than once quickly
                view.setClickable(false);
                // Don't use installStage.getStageCode() here because it can be
                // STAGE_USER_ACTION_REQUIRED if the installation is triggered by Pia itself.
                mInstallActionListener.onNegativeResponse(
                        InstallStage.STAGE_VERIFICATION_CONFIRMATION_REQUIRED);
            });
        }
        // Normally the positive button is hidden.
        Button positiveButton = UiUtil.getAlertDialogPositiveButton(dialog);
        if (positiveButton != null) {
            positiveButton.setVisibility(View.GONE);
        }
        // Sometimes there is a retry button. The user can choose to retry the verification if the
        // previously attempt has failed with a network error and the verification policy is closed.
        // In that case, the positive button displays OK and negative button displays Retry.
        if (isVerificationRetryAllowed(verificationUserActionNeededReason, verificationPolicy)) {
            if (positiveButton != null) {
                positiveButton.setVisibility(View.VISIBLE);
                positiveButton.setText(R.string.ok);
                UiUtil.applyFilledButtonStyle(requireContext(), positiveButton);
                // Notice, even though it's a "positive" button, it still gives a negative response
                // because it means abort the verification.
                positiveButton.setOnClickListener(view -> {
                    // Set clickable of the button to false to avoid the user clicks it
                    // more than once quickly
                    view.setClickable(false);
                    // Don't use installStage.getStageCode() here because it can be
                    // STAGE_USER_ACTION_REQUIRED if the installation is triggered by Pia itself.
                    mInstallActionListener.onNegativeResponse(
                            InstallStage.STAGE_VERIFICATION_CONFIRMATION_REQUIRED);
                });
            }
            if (negativeButton != null) {
                negativeButton.setVisibility(View.VISIBLE);
                negativeButton.setText(R.string.button_retry);
                negativeButton.setFilterTouchesWhenObscured(true);
                UiUtil.applyOutlinedButtonStyle(requireContext(), negativeButton);
                negativeButton.setOnClickListener(view -> {
                    // Set clickable of the button to false to avoid the user clicks it
                    // more than once quickly
                    view.setClickable(false);
                    mInstallActionListener.onRetryResponse();
                });
            }
        } else if (isVerificationBypassAllowed(verificationUserActionNeededReason,
                verificationPolicy)) {
            if (installStage.isAppUpdating()) {
                mMoreDetailsExpandedTextView.setText(
                        R.string.more_details_expanded_update_summary);
                mInstallWithoutVerifyingTextView.setText(R.string.update_without_verifying);
            }
            mInstallWithoutVerifyingTextView.setTypeface(
                    mInstallWithoutVerifyingTextView.getTypeface(), Typeface.BOLD);
            mInstallWithoutVerifyingTextView.setOnClickListener(view -> {
                // Set clickable of the button to false to avoid the user clicks it
                // more than once quickly
                view.setClickable(false);
                mInstallActionListener.onPositiveResponse(
                        InstallUserActionRequired.USER_ACTION_REASON_VERIFICATION_CONFIRMATION);
            });

            if (mIsMoreDetailsExpanded) {
                mMoreDetailsExpandedLayout.setVisibility(View.VISIBLE);
                mMoreDetailsClickableLayout.setVisibility(View.GONE);
            } else {
                mMoreDetailsClickableLayout.setVisibility(View.VISIBLE);
                mMoreDetailsExpandedLayout.setVisibility(View.GONE);
            }

            mMoreDetailsClickableLayout.setOnClickListener(view -> {
                // Set clickable of the button to false to avoid the user clicks it
                // more than once quickly
                view.setClickable(false);
                mIsMoreDetailsExpanded = true;
                mMoreDetailsClickableLayout.setVisibility(View.GONE);
                mMoreDetailsExpandedLayout.setVisibility(View.VISIBLE);
            });
        }
    }

    private int getVerificationConfirmationTitleResourceId(int verificationUserActionNeededReason) {
        return switch (verificationUserActionNeededReason) {
            case DEVELOPER_VERIFICATION_USER_ACTION_NEEDED_REASON_UNKNOWN ->
                    R.string.cannot_install_verification_unavailable_title;

            case DEVELOPER_VERIFICATION_USER_ACTION_NEEDED_REASON_NETWORK_UNAVAILABLE ->
                    R.string.cannot_install_verification_no_internet_title;

            default -> R.string.cannot_install_app_blocked_title;
        };
    }

    private int getVerificationConfirmationMessageResourceId(
            int verificationUserActionNeededReason, int verificationPolicy, boolean isAppUpdating) {
        return switch (verificationUserActionNeededReason) {
            case DEVELOPER_VERIFICATION_USER_ACTION_NEEDED_REASON_UNKNOWN -> {
                if (verificationPolicy == DEVELOPER_VERIFICATION_POLICY_BLOCK_FAIL_CLOSED) {
                    yield isAppUpdating
                            ? R.string.cannot_update_verification_unavailable_fail_closed_summary
                            : R.string.cannot_install_verification_unavailable_fail_closed_summary;
                } else {
                    yield isAppUpdating
                            ? R.string.cannot_update_verification_unavailable_summary
                            : R.string.cannot_install_verification_unavailable_summary;
                }
            }

            case DEVELOPER_VERIFICATION_USER_ACTION_NEEDED_REASON_NETWORK_UNAVAILABLE ->
                    isAppUpdating
                        ? R.string.cannot_update_verification_no_internet_summary
                        : R.string.cannot_install_verification_no_internet_summary;

            default ->
                isAppUpdating
                        ? R.string.cannot_update_app_blocked_summary
                        : R.string.cannot_install_app_blocked_summary;
        };
    }

    /**
     * Returns whether the user can choose to bypass the verification result and force installation,
     * based on the verification policy and the reason for user action.
     */
    public static boolean isVerificationBypassAllowed(
            int verificationUserActionNeededReason, int verificationPolicy) {
        return switch (verificationUserActionNeededReason) {
            case DEVELOPER_VERIFICATION_USER_ACTION_NEEDED_REASON_DEVELOPER_BLOCKED -> false;

            case DEVELOPER_VERIFICATION_USER_ACTION_NEEDED_REASON_NETWORK_UNAVAILABLE,
                 DEVELOPER_VERIFICATION_USER_ACTION_NEEDED_REASON_UNKNOWN ->
                // Only disallow bypass if policy is closed.
                    verificationPolicy != DEVELOPER_VERIFICATION_POLICY_BLOCK_FAIL_CLOSED;

            default -> {
                Log.e(LOG_TAG, "Unknown user action needed reason: "
                        + verificationUserActionNeededReason);
                yield false;
            }
        };
    }

    private static boolean isVerificationRetryAllowed(
            int verificationUserActionNeededReason, int verificationPolicy) {
        return verificationPolicy == DEVELOPER_VERIFICATION_POLICY_BLOCK_FAIL_CLOSED
                && verificationUserActionNeededReason
                == DEVELOPER_VERIFICATION_USER_ACTION_NEEDED_REASON_NETWORK_UNAVAILABLE;
    }
}
