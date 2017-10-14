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
 * limitations under the License
 */

package com.android.systemui.statusbar.policy;

import android.annotation.NonNull;
import android.content.res.Resources;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArraySet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.phone.NotificationPanelViewController;
import com.android.systemui.statusbar.phone.NotificationShadeWindowView;
import com.android.systemui.tuner.TunerService;

import lineageos.providers.LineageSettings;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Controls showing and hiding of the brightness mirror.
 */
public class BrightnessMirrorController
        implements CallbackController<BrightnessMirrorController.BrightnessMirrorListener> {

    private final NotificationShadeWindowView mStatusBarWindow;
    private static final String QS_SHOW_AUTO_BRIGHTNESS =
            "lineagesecure:" + LineageSettings.Secure.QS_SHOW_AUTO_BRIGHTNESS;
    private final Consumer<Boolean> mVisibilityCallback;
    private final NotificationPanelViewController mNotificationPanel;
    private final NotificationShadeDepthController mDepthController;
    private final ArraySet<BrightnessMirrorListener> mBrightnessMirrorListeners = new ArraySet<>();
    private final int[] mInt2Cache = new int[2];
    private View mBrightnessMirror;
    private boolean mShouldShowAutoBrightness;

    private final TunerService.Tunable mTunable = new TunerService.Tunable() {
            @Override
            public void onTuningChanged(String key, String newValue) {
                if (QS_SHOW_AUTO_BRIGHTNESS.equals(key)) {
                    mShouldShowAutoBrightness = TunerService.parseIntegerSwitch(newValue, true);
                    updateIcon();
                }
            }
        };

    public BrightnessMirrorController(NotificationShadeWindowView statusBarWindow,
            NotificationPanelViewController notificationPanelViewController,
            NotificationShadeDepthController notificationShadeDepthController,
            @NonNull Consumer<Boolean> visibilityCallback) {
        mStatusBarWindow = statusBarWindow;
        mBrightnessMirror = statusBarWindow.findViewById(R.id.brightness_mirror);
        mNotificationPanel = notificationPanelViewController;
        mDepthController = notificationShadeDepthController;
        mNotificationPanel.setPanelAlphaEndAction(() -> {
            mBrightnessMirror.setVisibility(View.INVISIBLE);
        });
        mVisibilityCallback = visibilityCallback;

        Dependency.get(TunerService.class).addTunable(mTunable, QS_SHOW_AUTO_BRIGHTNESS);
    }

    public void showMirror() {
        mBrightnessMirror.setVisibility(View.VISIBLE);
        mVisibilityCallback.accept(true);
        mNotificationPanel.setPanelAlpha(0, true /* animate */);
        mDepthController.setBrightnessMirrorVisible(true);
        updateIcon();
    }

    public void hideMirror() {
        mVisibilityCallback.accept(false);
        mNotificationPanel.setPanelAlpha(255, true /* animate */);
        mDepthController.setBrightnessMirrorVisible(false);
    }

    public void setLocation(View original) {
        original.getLocationInWindow(mInt2Cache);

        // Original is slightly larger than the mirror, so make sure to use the center for the
        // positioning.
        int originalX = mInt2Cache[0] + original.getWidth() / 2;
        int originalY = mInt2Cache[1] + original.getHeight() / 2;
        mBrightnessMirror.setTranslationX(0);
        mBrightnessMirror.setTranslationY(0);
        mBrightnessMirror.getLocationInWindow(mInt2Cache);
        int mirrorX = mInt2Cache[0] + mBrightnessMirror.getWidth() / 2;
        int mirrorY = mInt2Cache[1] + mBrightnessMirror.getHeight() / 2;
        mBrightnessMirror.setTranslationX(originalX - mirrorX);
        mBrightnessMirror.setTranslationY(originalY - mirrorY);
    }

    public View getMirror() {
        return mBrightnessMirror;
    }

    public void updateResources() {
        FrameLayout.LayoutParams lp =
                (FrameLayout.LayoutParams) mBrightnessMirror.getLayoutParams();
        Resources r = mBrightnessMirror.getResources();
        lp.width = r.getDimensionPixelSize(R.dimen.qs_panel_width);
        lp.height = r.getDimensionPixelSize(R.dimen.brightness_mirror_height);
        lp.gravity = r.getInteger(R.integer.notification_panel_layout_gravity);
        mBrightnessMirror.setLayoutParams(lp);
    }

    public void onOverlayChanged() {
        reinflate();
    }

    public void onDensityOrFontScaleChanged() {
        reinflate();
    }

    private void reinflate() {
        int index = mStatusBarWindow.indexOfChild(mBrightnessMirror);
        mStatusBarWindow.removeView(mBrightnessMirror);
        mBrightnessMirror = LayoutInflater.from(mBrightnessMirror.getContext()).inflate(
                R.layout.brightness_mirror, mStatusBarWindow, false);
        mStatusBarWindow.addView(mBrightnessMirror, index);

        for (int i = 0; i < mBrightnessMirrorListeners.size(); i++) {
            mBrightnessMirrorListeners.valueAt(i).onBrightnessMirrorReinflated(mBrightnessMirror);
        }
    }

    @Override
    public void addCallback(BrightnessMirrorListener listener) {
        Objects.requireNonNull(listener);
        mBrightnessMirrorListeners.add(listener);
    }

    @Override
    public void removeCallback(BrightnessMirrorListener listener) {
        mBrightnessMirrorListeners.remove(listener);
    }

    public void onUiModeChanged() {
        reinflate();
    }

    public interface BrightnessMirrorListener {
        void onBrightnessMirrorReinflated(View brightnessMirror);
    }

    private void updateIcon() {
        ImageView iv = mBrightnessMirror.findViewById(R.id.brightness_icon);
        boolean autoBrightnessAvailable = mBrightnessMirror.getContext().getResources().getBoolean(
                com.android.internal.R.bool.config_automatic_brightness_available);
        if (autoBrightnessAvailable && mShouldShowAutoBrightness) {
            int automatic = Settings.System.getIntForUser(mBrightnessMirror.getContext()
                            .getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                    UserHandle.USER_CURRENT);
            boolean isAutomatic = automatic != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
            iv.setImageResource(isAutomatic
                    ? com.android.systemui.R.drawable.ic_qs_brightness_auto_on
                    : com.android.systemui.R.drawable.ic_qs_brightness_auto_off);
            iv.setVisibility(View.VISIBLE);
        } else {
            iv.setVisibility(View.GONE);
        }
    }
}
