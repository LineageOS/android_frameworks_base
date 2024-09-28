/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents;

import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.systemui.shared.recents.utilities.Utilities.isLargeScreen;
import static com.android.systemui.util.leak.RotationUtils.ROTATION_LANDSCAPE;
import static com.android.systemui.util.leak.RotationUtils.ROTATION_NONE;
import static com.android.systemui.util.leak.RotationUtils.ROTATION_SEASCAPE;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.os.Binder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.SpannableStringBuilder;
import android.text.style.BulletSpan;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.android.systemui.CoreStartable;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.navigationbar.NavigationBarController;
import com.android.systemui.navigationbar.NavigationBarView;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.util.leak.RotationUtils;

import lineageos.providers.LineageSettings;

import dagger.Lazy;

import java.util.ArrayList;

import javax.inject.Inject;

@SysUISingleton
public class ScreenPinningRequest implements
        View.OnClickListener,
        NavigationModeController.ModeChangedListener,
        CoreStartable,
        ConfigurationController.ConfigurationListener {
    private static final String TAG = "ScreenPinningRequest";

    private final Context mContext;
    private final Lazy<NavigationBarController> mNavigationBarControllerLazy;
    private final AccessibilityManager mAccessibilityService;
    private final WindowManager mWindowManager;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final UserTracker mUserTracker;

    private RequestWindowView mRequestWindow;
    private int mNavBarMode;

    /** ID of task to be pinned or locked. */
    private int taskId;

    private final UserTracker.Callback mUserChangedCallback =
            new UserTracker.Callback() {
                @Override
                public void onUserChanged(int newUser, @NonNull Context userContext) {
                    clearPrompt();
                }
            };

    @Inject
    public ScreenPinningRequest(
            Context context,
            NavigationModeController navigationModeController,
            Lazy<NavigationBarController> navigationBarControllerLazy,
            BroadcastDispatcher broadcastDispatcher,
            UserTracker userTracker) {
        mContext = context;
        mNavigationBarControllerLazy = navigationBarControllerLazy;
        mAccessibilityService = (AccessibilityManager)
                mContext.getSystemService(Context.ACCESSIBILITY_SERVICE);
        mWindowManager = (WindowManager)
                mContext.getSystemService(Context.WINDOW_SERVICE);
        mNavBarMode = navigationModeController.addListener(this);
        mBroadcastDispatcher = broadcastDispatcher;
        mUserTracker = userTracker;
    }

    @Override
    public void start() {}

    public void clearPrompt() {
        if (mRequestWindow != null) {
            mWindowManager.removeView(mRequestWindow);
            mRequestWindow = null;
        }
    }

    public void showPrompt(int taskId, boolean allowCancel) {
        try {
            clearPrompt();
        } catch (IllegalArgumentException e) {
            // If the call to show the prompt fails due to the request window not already being
            // attached, then just ignore the error since we will be re-adding it below.
        }

        this.taskId = taskId;

        mRequestWindow = new RequestWindowView(mContext, allowCancel);

        mRequestWindow.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        // show the confirmation
        WindowManager.LayoutParams lp = getWindowLayoutParams();
        mWindowManager.addView(mRequestWindow, lp);
    }

    @Override
    public void onNavigationModeChanged(int mode) {
        mNavBarMode = mode;
    }

    @Override
    public void onConfigChanged(Configuration newConfig) {
        if (mRequestWindow != null) {
            mRequestWindow.onConfigurationChanged();
        }
    }

    protected WindowManager.LayoutParams getWindowLayoutParams() {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.token = new Binder();
        lp.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        lp.setTitle("ScreenPinningConfirmation");
        lp.gravity = Gravity.FILL;
        lp.setFitInsetsTypes(0 /* types */);
        return lp;
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.screen_pinning_ok_button || mRequestWindow == v) {
            try {
                ActivityTaskManager.getService().startSystemLockTaskMode(taskId);
            } catch (RemoteException e) {}
        }
        clearPrompt();
    }

    public FrameLayout.LayoutParams getRequestLayoutParams(int rotation) {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                rotation == ROTATION_SEASCAPE ? (Gravity.CENTER_VERTICAL | Gravity.LEFT) :
                rotation == ROTATION_LANDSCAPE ? (Gravity.CENTER_VERTICAL | Gravity.RIGHT)
                            : (Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM));
    }

    private class RequestWindowView extends FrameLayout {
        private static final int OFFSET_DP = 96;

        private final ColorDrawable mColor = new ColorDrawable(0);
        private ViewGroup mLayout;
        private final boolean mShowCancel;

        private RequestWindowView(Context context, boolean showCancel) {
            super(context);
            setClickable(true);
            setOnClickListener(ScreenPinningRequest.this);
            setBackground(mColor);
            mShowCancel = showCancel;
        }

        @Override
        public void onAttachedToWindow() {
            DisplayMetrics metrics = new DisplayMetrics();
            mWindowManager.getDefaultDisplay().getMetrics(metrics);
            float density = metrics.density;
            int rotation = getRotation(mContext);

            inflateView(rotation);
            int bgColor = mContext.getColor(
                    R.color.screen_pinning_request_window_bg);
            if (ActivityManager.isHighEndGfx()) {
                mLayout.setAlpha(0f);
                if (rotation == ROTATION_SEASCAPE) {
                    mLayout.setTranslationX(-OFFSET_DP * density);
                } else if (rotation == ROTATION_LANDSCAPE) {
                    mLayout.setTranslationX(OFFSET_DP * density);
                } else {
                    mLayout.setTranslationY(OFFSET_DP * density);
                }
                mLayout.animate()
                        .alpha(1f)
                        .translationX(0)
                        .translationY(0)
                        .setDuration(300)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();

                ValueAnimator colorAnim = ValueAnimator.ofObject(new ArgbEvaluator(), 0, bgColor);
                colorAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        final int c = (Integer) animation.getAnimatedValue();
                        mColor.setColor(c);
                    }
                });
                colorAnim.setDuration(1000);
                colorAnim.start();
            } else {
                mColor.setColor(bgColor);
            }

            IntentFilter filter = new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            mBroadcastDispatcher.registerReceiver(mReceiver, filter);
            mUserTracker.addCallback(mUserChangedCallback, mContext.getMainExecutor());
        }

        private void inflateView(int rotation) {
            // We only want this landscape orientation on <600dp, so rather than handle
            // resource overlay for -land and -sw600dp-land, just inflate this
            // other view for this single case.
            mLayout = (ViewGroup) View.inflate(getContext(),
                    rotation == ROTATION_SEASCAPE ? R.layout.screen_pinning_request_sea_phone :
                    rotation == ROTATION_LANDSCAPE ? R.layout.screen_pinning_request_land_phone
                            : R.layout.screen_pinning_request,
                    null);
            // Catch touches so they don't trigger cancel/activate, like outside does.
            mLayout.setClickable(true);
            // Status bar is always on the right.
            mLayout.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            // Buttons and text do switch sides though.
            mLayout.findViewById(R.id.screen_pinning_text_area)
                    .setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
            View buttons = mLayout.findViewById(R.id.screen_pinning_buttons);
            if (!QuickStepContract.isGesturalMode(mNavBarMode)
                    && hasSoftNavigationBar(mContext, mContext.getDisplayId())
                    && !isLargeScreen(mContext)) {
                buttons.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
                swapChildrenIfRtlAndVertical(buttons);
            } else {
                buttons.setVisibility(View.GONE);
            }

            ((Button) mLayout.findViewById(R.id.screen_pinning_ok_button))
                    .setOnClickListener(ScreenPinningRequest.this);
            if (mShowCancel) {
                ((Button) mLayout.findViewById(R.id.screen_pinning_cancel_button))
                        .setOnClickListener(ScreenPinningRequest.this);
            } else {
                ((Button) mLayout.findViewById(R.id.screen_pinning_cancel_button))
                        .setVisibility(View.INVISIBLE);
            }

            int displayId = mContext.getDisplayId();
            boolean overviewEnabled =
                    mNavigationBarControllerLazy.get().isOverviewEnabled(displayId);
            boolean touchExplorationEnabled = mAccessibilityService.isTouchExplorationEnabled();
            int descriptionStringResId;
            if (QuickStepContract.isGesturalMode(mNavBarMode)) {
                descriptionStringResId = R.string.screen_pinning_description_gestural;
            } else if (overviewEnabled) {
                mLayout.findViewById(R.id.screen_pinning_recents_group).setVisibility(VISIBLE);
                mLayout.findViewById(R.id.screen_pinning_home_bg_light).setVisibility(INVISIBLE);
                mLayout.findViewById(R.id.screen_pinning_home_bg).setVisibility(INVISIBLE);
                descriptionStringResId = touchExplorationEnabled
                        ? R.string.screen_pinning_description_accessible
                        : R.string.screen_pinning_description;
            } else {
                mLayout.findViewById(R.id.screen_pinning_recents_group).setVisibility(INVISIBLE);
                mLayout.findViewById(R.id.screen_pinning_home_bg_light).setVisibility(VISIBLE);
                mLayout.findViewById(R.id.screen_pinning_home_bg).setVisibility(VISIBLE);
                descriptionStringResId = touchExplorationEnabled
                        ? R.string.screen_pinning_description_recents_invisible_accessible
                        : R.string.screen_pinning_description_recents_invisible;
            }

            NavigationBarView navigationBarView =
                    mNavigationBarControllerLazy.get().getNavigationBarView(displayId);
            if (navigationBarView != null) {
                ((ImageView) mLayout.findViewById(R.id.screen_pinning_back_icon))
                        .setImageDrawable(navigationBarView.getBackDrawable());
                ((ImageView) mLayout.findViewById(R.id.screen_pinning_home_icon))
                        .setImageDrawable(navigationBarView.getHomeDrawable());
            }

            // Create a bulleted list of the default description plus the two security notes.
            int gapWidth = getResources().getDimensionPixelSize(
                    R.dimen.screen_pinning_description_bullet_gap_width);
            SpannableStringBuilder description = new SpannableStringBuilder();
            description.append(getContext().getText(descriptionStringResId),
                    new BulletSpan(gapWidth), /* flags */ 0);
            description.append(System.lineSeparator());
            description.append(getContext().getText(R.string.screen_pinning_exposes_personal_data),
                    new BulletSpan(gapWidth), /* flags */ 0);
            description.append(System.lineSeparator());
            description.append(getContext().getText(R.string.screen_pinning_can_open_other_apps),
                    new BulletSpan(gapWidth), /* flags */ 0);
            ((TextView) mLayout.findViewById(R.id.screen_pinning_description)).setText(description);

            final int backBgVisibility = touchExplorationEnabled ? View.INVISIBLE : View.VISIBLE;
            mLayout.findViewById(R.id.screen_pinning_back_bg).setVisibility(backBgVisibility);
            mLayout.findViewById(R.id.screen_pinning_back_bg_light).setVisibility(backBgVisibility);

            addView(mLayout, getRequestLayoutParams(rotation));
        }

        /**
         * @param displayId the id of display to check if there is a software navigation bar.
         *
         * @return whether there is a soft nav bar on specific display.
         */
        private boolean hasSoftNavigationBar(Context context, int displayId) {
            if (displayId == DEFAULT_DISPLAY &&
                    LineageSettings.System.getIntForUser(context.getContentResolver(),
                            LineageSettings.System.FORCE_SHOW_NAVBAR, 0,
                            UserHandle.USER_CURRENT) == 1) {
                return true;
            }
            try {
                return WindowManagerGlobal.getWindowManagerService().hasNavigationBar(displayId);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to check soft navigation bar", e);
                return false;
            }
        }

        private void swapChildrenIfRtlAndVertical(View group) {
            if (mContext.getResources().getConfiguration().getLayoutDirection()
                    != View.LAYOUT_DIRECTION_RTL) {
                return;
            }
            LinearLayout linearLayout = (LinearLayout) group;
            if (linearLayout.getOrientation() == LinearLayout.VERTICAL) {
                int childCount = linearLayout.getChildCount();
                ArrayList<View> childList = new ArrayList<>(childCount);
                for (int i = 0; i < childCount; i++) {
                    childList.add(linearLayout.getChildAt(i));
                }
                linearLayout.removeAllViews();
                for (int i = childCount - 1; i >= 0; i--) {
                    linearLayout.addView(childList.get(i));
                }
            }
        }

        @Override
        public void onDetachedFromWindow() {
            mBroadcastDispatcher.unregisterReceiver(mReceiver);
            mUserTracker.removeCallback(mUserChangedCallback);
        }

        protected void onConfigurationChanged() {
            removeAllViews();
            inflateView(getRotation(mContext));
        }

        private int getRotation(Context context) {
            Configuration config = context.getResources().getConfiguration();
            if (config.smallestScreenWidthDp >= 600) {
                return ROTATION_NONE;
            }

            return RotationUtils.getRotation(context);
        }

        private final Runnable mUpdateLayoutRunnable = new Runnable() {
            @Override
            public void run() {
                if (mLayout != null && mLayout.getParent() != null) {
                    mLayout.setLayoutParams(getRequestLayoutParams(getRotation(mContext)));
                }
            }
        };

        private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction().equals(Intent.ACTION_CONFIGURATION_CHANGED)) {
                    post(mUpdateLayoutRunnable);
                } else if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                    clearPrompt();
                }
            }
        };
    }
}
