/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.media.controls.ui.controller;

import static android.provider.Settings.ACTION_MEDIA_CONTROLS_SETTINGS;

import static com.android.systemui.Flags.communalHub;
import static com.android.systemui.media.controls.domain.pipeline.MediaActionsKt.getNotificationActions;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.animation.AnimatorSet;
import android.app.ActivityOptions;
import android.app.BroadcastOptions;
import android.app.PendingIntent;
import android.app.WallpaperColors;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.theming.ThemeStyle;
import android.graphics.Bitmap;
import android.graphics.BlendMode;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Process;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.Pair;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.constraintlayout.widget.ConstraintSet;

import com.android.app.animation.Interpolators;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.InstanceId;
import com.android.internal.widget.CachingIconView;
import com.android.settingslib.media.LocalMediaManager.MediaDeviceState;
import com.android.settingslib.widget.AdaptiveIcon;
import com.android.systemui.ActivityIntentHelper;
import com.android.systemui.Flags;
import com.android.systemui.animation.ActivityTransitionAnimator;
import com.android.systemui.animation.GhostedViewTransitionAnimatorController;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.communal.domain.interactor.CommunalSceneInteractor;
import com.android.systemui.communal.widgets.CommunalTransitionAnimatorController;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager;
import com.android.systemui.media.controls.shared.model.MediaAction;
import com.android.systemui.media.controls.shared.model.MediaButton;
import com.android.systemui.media.controls.shared.model.MediaData;
import com.android.systemui.media.controls.shared.model.MediaDeviceData;
import com.android.systemui.media.controls.shared.model.SuggestedMediaDeviceData;
import com.android.systemui.media.controls.shared.model.SuggestionData;
import com.android.systemui.media.controls.ui.animation.AnimationBindHandler;
import com.android.systemui.media.controls.ui.animation.ColorSchemeTransition;
import com.android.systemui.media.controls.ui.animation.MediaColorSchemesKt;
import com.android.systemui.media.controls.ui.animation.MetadataAnimationHandler;
import com.android.systemui.media.controls.ui.binder.SeekBarObserver;
import com.android.systemui.media.controls.ui.view.GutsViewHolder;
import com.android.systemui.media.controls.ui.view.MediaViewHolder;
import com.android.systemui.media.controls.ui.viewmodel.SeekBarViewModel;
import com.android.systemui.media.controls.util.MediaDataUtils;
import com.android.systemui.media.controls.util.MediaUiEventLogger;
import com.android.systemui.media.dialog.MediaOutputDialogManager;
import com.android.systemui.monet.ColorScheme;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.res.R;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.shade.ShadeDisplayAware;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.surfaceeffects.core.ripple.RippleAnimationConfig;
import com.android.systemui.surfaceeffects.core.ripple.RippleShader;
import com.android.systemui.surfaceeffects.core.turbulencenoise.TurbulenceNoiseAnimationConfig;
import com.android.systemui.surfaceeffects.core.turbulencenoise.TurbulenceNoiseShader.Companion.Type;
import com.android.systemui.surfaceeffects.view.PaintDrawCallback;
import com.android.systemui.surfaceeffects.view.loadingeffect.LoadingEffect;
import com.android.systemui.surfaceeffects.view.loadingeffect.LoadingEffect.AnimationState;
import com.android.systemui.surfaceeffects.view.loadingeffect.LoadingEffectView;
import com.android.systemui.surfaceeffects.view.ripple.MultiRippleController;
import com.android.systemui.surfaceeffects.view.ripple.MultiRippleView;
import com.android.systemui.surfaceeffects.view.ripple.RippleAnimation;
import com.android.systemui.surfaceeffects.view.turbulencenoise.TurbulenceNoiseController;
import com.android.systemui.surfaceeffects.view.turbulencenoise.TurbulenceNoiseView;
import com.android.systemui.util.ColorUtilKt;
import com.android.systemui.util.animation.TransitionLayout;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.settings.GlobalSettings;

import dagger.Lazy;

import kotlin.Triple;
import kotlin.Unit;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * A view controller used for Media Playback.
 */
public class MediaControlPanel {
    protected static final String TAG = "MediaControlPanel";

    private static final float DISABLED_ALPHA = 0.38f;
    private static final Intent SETTINGS_INTENT = new Intent(ACTION_MEDIA_CONTROLS_SETTINGS);

    // Buttons to show in small player when using semantic actions
    private static final List<Integer> SEMANTIC_ACTIONS_COMPACT = List.of(
            R.id.actionPlayPause,
            R.id.actionPrev,
            R.id.actionNext
    );

    // Buttons that should get hidden when we're scrubbing (they will be replaced with the views
    // showing scrubbing time)
    private static final List<Integer> SEMANTIC_ACTIONS_HIDE_WHEN_SCRUBBING = List.of(
            R.id.actionPrev,
            R.id.actionNext
    );

    // Buttons to show in small player when using semantic actions
    private static final List<Integer> SEMANTIC_ACTIONS_ALL = List.of(
            R.id.actionPlayPause,
            R.id.actionPrev,
            R.id.actionNext,
            R.id.action0,
            R.id.action1
    );

    // Time in millis for playing turbulence noise that is played after a touch ripple.
    @VisibleForTesting
    static final long TURBULENCE_NOISE_PLAY_DURATION = 7500L;
    public static final float MEDIA_PLAYER_SCRIM_START_ALPHA = 0.65f;
    public static final float MEDIA_PLAYER_SCRIM_END_ALPHA = 0.75f;

    private final SeekBarViewModel mSeekBarViewModel;
    private final CommunalSceneInteractor mCommunalSceneInteractor;
    private final CommunalTransitionAnimatorController.Factory mCommunalAnimationControllerFactory;
    private SeekBarObserver mSeekBarObserver;
    protected final Executor mBackgroundExecutor;
    private final DelayableExecutor mMainExecutor;
    private final ActivityStarter mActivityStarter;
    private final BroadcastSender mBroadcastSender;

    private Context mContext;
    private MediaViewHolder mMediaViewHolder;
    private String mKey;
    private MediaData mMediaData;
    private MediaViewController mMediaViewController;
    private MediaSession.Token mToken;
    private MediaController mController;
    private Lazy<MediaDataManager> mMediaDataManagerLazy;
    // Uid for the media app.
    protected int mUid = Process.INVALID_UID;
    private MediaCarouselController mMediaCarouselController;
    private final MediaOutputDialogManager mMediaOutputDialogManager;
    private final FalsingManager mFalsingManager;
    private MetadataAnimationHandler mMetadataAnimationHandler;
    private ColorSchemeTransition mColorSchemeTransition;
    private Drawable mPrevArtwork = null;
    private boolean mIsArtworkBound = false;
    private int mArtworkBoundId = 0;
    private int mArtworkNextBindRequestId = 0;
    private boolean mPageArrowsVisible = false;

    private final KeyguardStateController mKeyguardStateController;
    private final ActivityIntentHelper mActivityIntentHelper;
    private final NotificationLockscreenUserManager mLockscreenUserManager;

    // Used for logging.
    private MediaUiEventLogger mLogger;
    private InstanceId mInstanceId;
    private String mPackageName;

    private boolean mIsScrubbing = false;
    private boolean mIsSeekBarEnabled = false;

    private final SeekBarViewModel.ScrubbingChangeListener mScrubbingChangeListener =
            this::setIsScrubbing;
    private final SeekBarViewModel.EnabledChangeListener mEnabledChangeListener =
            this::setIsSeekBarEnabled;
    private final SeekBarViewModel.ContentDescriptionListener mContentDescriptionListener =
            this::setSeekbarContentDescription;

    private MultiRippleController mMultiRippleController;
    private TurbulenceNoiseController mTurbulenceNoiseController;
    private LoadingEffect mLoadingEffect;
    private final GlobalSettings mGlobalSettings;
    private TurbulenceNoiseAnimationConfig mTurbulenceNoiseAnimationConfig;
    private boolean mWasPlaying = false;
    private boolean mButtonClicked = false;
    @Nullable
    private Runnable mOnSuggestionSpaceVisibleRunnable = null;

    private final PaintDrawCallback mNoiseDrawCallback =
            new PaintDrawCallback() {
                @Override
                public void onDraw(@NonNull Paint paint) {
                    mMediaViewHolder.getLoadingEffectView().draw(paint);
                }
            };
    private final LoadingEffect.AnimationStateChangedCallback mStateChangedCallback =
            new LoadingEffect.AnimationStateChangedCallback() {
                @Override
                public void onStateChanged(@NonNull AnimationState oldState,
                        @NonNull AnimationState newState) {
                    LoadingEffectView loadingEffectView =
                            mMediaViewHolder.getLoadingEffectView();
                    if (newState == AnimationState.NOT_PLAYING) {
                        loadingEffectView.setVisibility(View.INVISIBLE);
                    } else {
                        loadingEffectView.setVisibility(View.VISIBLE);
                    }
                }
            };

    /**
     * Initialize a new control panel
     *
     * @param backgroundExecutor background executor, used for processing artwork
     * @param mainExecutor       main thread executor, used if we receive callbacks on the
     *                           background
     *                           thread that then trigger UI changes.
     * @param activityStarter    activity starter
     */
    @Inject
    public MediaControlPanel(
            @ShadeDisplayAware Context context,
            @Background Executor backgroundExecutor,
            @Main DelayableExecutor mainExecutor,
            ActivityStarter activityStarter,
            BroadcastSender broadcastSender,
            MediaViewController mediaViewController,
            SeekBarViewModel seekBarViewModel,
            Lazy<MediaDataManager> lazyMediaDataManager,
            MediaOutputDialogManager mediaOutputDialogManager,
            MediaCarouselController mediaCarouselController,
            FalsingManager falsingManager,
            MediaUiEventLogger logger,
            KeyguardStateController keyguardStateController,
            ActivityIntentHelper activityIntentHelper,
            CommunalSceneInteractor communalSceneInteractor,
            NotificationLockscreenUserManager lockscreenUserManager,
            GlobalSettings globalSettings,
            CommunalTransitionAnimatorController.Factory communalAnimationControllerFactory
    ) {
        mContext = context;
        mBackgroundExecutor = backgroundExecutor;
        mMainExecutor = mainExecutor;
        mActivityStarter = activityStarter;
        mBroadcastSender = broadcastSender;
        mSeekBarViewModel = seekBarViewModel;
        mMediaViewController = mediaViewController;
        mMediaDataManagerLazy = lazyMediaDataManager;
        mMediaOutputDialogManager = mediaOutputDialogManager;
        mMediaCarouselController = mediaCarouselController;
        mFalsingManager = falsingManager;
        mLogger = logger;
        mKeyguardStateController = keyguardStateController;
        mActivityIntentHelper = activityIntentHelper;
        mLockscreenUserManager = lockscreenUserManager;
        mCommunalSceneInteractor = communalSceneInteractor;
        mCommunalAnimationControllerFactory = communalAnimationControllerFactory;

        mSeekBarViewModel.setLogSeek(() -> {
            if (mPackageName != null && mInstanceId != null) {
                mLogger.logSeek(mUid, mPackageName, mInstanceId);
            }
            return Unit.INSTANCE;
        });

        mGlobalSettings = globalSettings;
        updateAnimatorDurationScale();
    }

    /**
     * Clean up seekbar and controller when panel is destroyed
     */
    public void onDestroy() {
        if (mSeekBarObserver != null) {
            mSeekBarViewModel.getProgress().removeObserver(mSeekBarObserver);
        }
        mSeekBarViewModel.removeScrubbingChangeListener(mScrubbingChangeListener);
        mSeekBarViewModel.removeEnabledChangeListener(mEnabledChangeListener);
        mSeekBarViewModel.removeContentDescriptionListener(mContentDescriptionListener);
        mSeekBarViewModel.onDestroy();
        mMediaViewController.onDestroy();
    }

    /**
     * Get the view holder used to display media controls.
     *
     * @return the media view holder
     */
    @Nullable
    public MediaViewHolder getMediaViewHolder() {
        return mMediaViewHolder;
    }

    /**
     * Get the view controller used to display media controls
     *
     * @return the media view controller
     */
    @NonNull
    public MediaViewController getMediaViewController() {
        return mMediaViewController;
    }

    /**
     * Sets the listening state of the player.
     * <p>
     * Should be set to true when the QS panel is open. Otherwise, false. This is a signal to avoid
     * unnecessary work when the QS panel is closed.
     *
     * @param listening True when player should be active. Otherwise, false.
     */
    public void setListening(boolean listening) {
        mSeekBarViewModel.setListening(listening);
    }

    @VisibleForTesting
    public boolean getListening() {
        return mSeekBarViewModel.getListening();
    }

    /** Sets whether the user is touching the seek bar to change the track position. */
    private void setIsScrubbing(boolean isScrubbing) {
        if (mMediaData == null || mMediaData.getSemanticActions() == null) {
            return;
        }
        if (isScrubbing == this.mIsScrubbing) {
            return;
        }
        this.mIsScrubbing = isScrubbing;
        mMainExecutor.execute(() ->
                updateDisplayForScrubbingChange(mMediaData.getSemanticActions()));
    }

    private void setIsSeekBarEnabled(boolean isSeekBarEnabled) {
        if (isSeekBarEnabled == this.mIsSeekBarEnabled) {
            return;
        }
        this.mIsSeekBarEnabled = isSeekBarEnabled;
        updateSeekBarVisibility();
        mMainExecutor.execute(() -> {
            if (!mMetadataAnimationHandler.isRunning()) {
                // Trigger a state refresh so that we immediately update visibilities.
                mMediaViewController.refreshState();
            }
        });
    }

    private void setSeekbarContentDescription(CharSequence elapsedTime, CharSequence duration) {
        mMainExecutor.execute(() -> {
            mSeekBarObserver.updateContentDescription(elapsedTime, duration);
        });
    }

    /**
     * Reloads animator duration scale.
     */
    void updateAnimatorDurationScale() {
        if (mSeekBarObserver != null) {
            mSeekBarObserver.setAnimationEnabled(
                    mGlobalSettings.getFloat(Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f);
        }
    }

    /**
     * Get the context
     *
     * @return context
     */
    public Context getContext() {
        return mContext;
    }

    /** Attaches the player to the player view holder. */
    public void attachPlayer(MediaViewHolder vh) {
        mMediaViewHolder = vh;
        TransitionLayout player = vh.getPlayer();

        mSeekBarObserver = new SeekBarObserver(vh);
        mSeekBarViewModel.getProgress().observeForever(mSeekBarObserver);
        mSeekBarViewModel.attachTouchHandlers(vh.getSeekBar());
        mSeekBarViewModel.setScrubbingChangeListener(mScrubbingChangeListener);
        mSeekBarViewModel.setEnabledChangeListener(mEnabledChangeListener);
        mSeekBarViewModel.setContentDescriptionListener(mContentDescriptionListener);
        mMediaViewController.attach(player);

        vh.getPlayer().setOnLongClickListener(v -> {
            if (mFalsingManager.isFalseLongTap(FalsingManager.LOW_PENALTY)) return true;
            if (!mMediaViewController.isGutsVisible()) {
                openGuts();
                return true;
            } else {
                closeGuts();
                return true;
            }
        });

        // AlbumView uses a hardware layer so that clipping of the foreground is handled
        // with clipping the album art. Otherwise album art shows through at the edges.
        mMediaViewHolder.getAlbumView().setLayerType(View.LAYER_TYPE_HARDWARE, null);

        TextView titleText = mMediaViewHolder.getTitleText();
        TextView artistText = mMediaViewHolder.getArtistText();
        CachingIconView explicitIndicator = mMediaViewHolder.getExplicitIndicator();
        AnimatorSet enter = loadAnimator(R.anim.media_metadata_enter,
                Interpolators.EMPHASIZED_DECELERATE, titleText, artistText, explicitIndicator);
        AnimatorSet exit = loadAnimator(R.anim.media_metadata_exit,
                Interpolators.EMPHASIZED_ACCELERATE, titleText, artistText, explicitIndicator);

        MultiRippleView multiRippleView = vh.getMultiRippleView();
        mMultiRippleController = new MultiRippleController(multiRippleView);

        TurbulenceNoiseView turbulenceNoiseView = vh.getTurbulenceNoiseView();
        turbulenceNoiseView.setBlendMode(BlendMode.SCREEN);
        LoadingEffectView loadingEffectView = vh.getLoadingEffectView();
        loadingEffectView.setBlendMode(BlendMode.SCREEN);
        loadingEffectView.setVisibility(View.INVISIBLE);

        mTurbulenceNoiseController = new TurbulenceNoiseController(turbulenceNoiseView);

        mColorSchemeTransition = new ColorSchemeTransition(
                mContext, mMediaViewHolder, mMultiRippleController, mTurbulenceNoiseController);
        mMetadataAnimationHandler = new MetadataAnimationHandler(exit, enter);
    }

    @VisibleForTesting
    protected AnimatorSet loadAnimator(int animId, Interpolator motionInterpolator,
            View... targets) {
        ArrayList<Animator> animators = new ArrayList<>();
        for (View target : targets) {
            AnimatorSet animator = (AnimatorSet) AnimatorInflater.loadAnimator(mContext, animId);
            animator.getChildAnimations().get(0).setInterpolator(motionInterpolator);
            animator.setTarget(target);
            animators.add(animator);
        }

        AnimatorSet result = new AnimatorSet();
        result.playTogether(animators);
        return result;
    }

    /** Bind this player view based on the data given. */
    public void bindPlayer(@NonNull MediaData data, String key) {
        SceneContainerFlag.assertInLegacyMode();
        if (mMediaViewHolder == null) {
            return;
        }
        if (Trace.isEnabled()) {
            Trace.traceBegin(Trace.TRACE_TAG_APP, "MediaControlPanel#bindPlayer<" + key + ">");
        }
        mKey = key;
        mMediaData = data;
        MediaSession.Token token = data.getToken();
        mPackageName = data.getPackageName();
        mUid = data.getAppUid();
        mInstanceId = data.getInstanceId();

        if (mToken == null || !mToken.equals(token)) {
            mToken = token;
        }

        if (mToken != null) {
            mController = new MediaController(mContext, mToken);
        } else {
            mController = null;
        }

        // Click action
        PendingIntent clickIntent = data.getClickIntent();
        if (clickIntent != null) {
            mMediaViewHolder.getPlayer().setOnClickListener(v -> {
                if (mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) return;
                if (mMediaViewController.isGutsVisible()) return;
                mLogger.logTapContentView(mUid, mPackageName, mInstanceId);

                boolean showOverLockscreen = mKeyguardStateController.isShowing()
                        && mActivityIntentHelper.wouldPendingShowOverLockscreen(clickIntent,
                        mLockscreenUserManager.getCurrentUserId());
                if (showOverLockscreen) {
                    mActivityStarter.startPendingIntentMaybeDismissingKeyguard(
                            clickIntent,
                            /* dismissShade = */ true,
                            /* intentSentUiThreadCallback = */ null,
                            buildLaunchAnimatorController(mMediaViewHolder.getPlayer()),
                            /* fillIntent = */ null,
                            /* extraOptions = */ null,
                            /* customMessage */ null);
                } else {
                    mActivityStarter.postStartActivityDismissingKeyguard(clickIntent,
                            buildLaunchAnimatorController(mMediaViewHolder.getPlayer()));
                }
            });
        }

        // Seek Bar
        if (data.getResumption() && data.getResumeProgress() != null) {
            double progress = data.getResumeProgress();
            mSeekBarViewModel.updateStaticProgress(progress);
        } else {
            final MediaController controller = getController();
            mBackgroundExecutor.execute(() -> mSeekBarViewModel.updateController(controller));
        }

        bindOutputSwitcherChip(data);
        bindGutsMenuForPlayer(data);
        bindPlayerContentDescription(data);
        bindScrubbingTime(data);
        bindActionButtons(data);
        bindDeviceSuggestion(data);
        bindPageButtons();

        boolean isSongUpdated = bindSongMetadata(data);
        bindArtworkAndColors(data, key, isSongUpdated);

        // TODO: We don't need to refresh this state constantly, only if the state actually changed
        // to something which might impact the measurement
        // State refresh interferes with the translation animation, only run it if it's not running.
        if (!mMetadataAnimationHandler.isRunning()) {
            mMediaViewController.refreshState();
        }

        final boolean isTurbulenceNoiseEnabled = mContext.getResources().getBoolean(
                R.bool.config_turbulenceNoise);

        if (isTurbulenceNoiseEnabled && shouldPlayTurbulenceNoise()) {
            // Need to create the config here to get the correct view size and color.
            if (mTurbulenceNoiseAnimationConfig == null) {
                mTurbulenceNoiseAnimationConfig =
                        createTurbulenceNoiseConfig();
            }

            if (mLoadingEffect == null) {
                mLoadingEffect = new LoadingEffect(
                        Type.SIMPLEX_NOISE,
                        mTurbulenceNoiseAnimationConfig,
                        mNoiseDrawCallback,
                        mStateChangedCallback
                );
                mColorSchemeTransition.setLoadingEffect(mLoadingEffect);
            }

            mLoadingEffect.play();
            mMainExecutor.executeDelayed(
                    mLoadingEffect::finish,
                    TURBULENCE_NOISE_PLAY_DURATION
            );
        }

        mButtonClicked = false;
        mWasPlaying = isPlaying();

        Trace.endSection();
    }

    /**
     * Called when the panel becomes fully visible.
     */
    public void onPanelFullyVisible() {
        if (!Flags.enableSuggestedDeviceUi()) {
            return;
        }
        if (mMediaData.getResumption()) {
            return;
        }
        @Nullable Runnable onSuggestionVisibleRunnable = mOnSuggestionSpaceVisibleRunnable;
        if (onSuggestionVisibleRunnable != null) {
            onSuggestionVisibleRunnable.run();
        }
    }

    private void bindDeviceSuggestion(@NonNull MediaData data) {
        if (!Flags.enableSuggestedDeviceUi()) {
            return;
        }
        View deviceSuggestionButton = mMediaViewHolder.getDeviceSuggestionButton();
        View deviceSuggestionContainer = mMediaViewHolder.getDeviceSuggestionContainer();
        TextView deviceText = mMediaViewHolder.getSeamlessText();
        @Nullable SuggestionData suggestionData = data.getSuggestionData();
        if (suggestionData != null) {
            mOnSuggestionSpaceVisibleRunnable = suggestionData.getOnSuggestionSpaceVisible();
            @Nullable
            SuggestedMediaDeviceData suggestionDeviceData =
                    suggestionData.getSuggestedMediaDeviceData();
            if (suggestionDeviceData != null
                    && isValidSuggestion(suggestionDeviceData)
                    && !data.getResumption()) {
                // Don't show the OSw device text if we have a suggestion: just show the icon
                deviceText.setVisibility(View.GONE);
                setSuggestionClickListener(suggestionDeviceData);
                setSuggestionText(suggestionDeviceData);
                setSuggestionIcon(suggestionDeviceData);
                deviceSuggestionButton.setVisibility(View.VISIBLE);
                deviceSuggestionContainer.setImportantForAccessibility(
                        View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
                return;
            }
        }
        deviceSuggestionButton.setVisibility(View.GONE);
        // Change the importantForAccessibility attribute instead of visibility since the latter
        // is manipulated by the TransitionLayout and the Guts animation logic.
        deviceSuggestionContainer.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        deviceText.setVisibility(View.VISIBLE);
        return;
    }

    private boolean isValidSuggestion(SuggestedMediaDeviceData suggestionData) {
        int connectionState = suggestionData.getConnectionState();
        return connectionState == MediaDeviceState.STATE_DISCONNECTED
                || connectionState == MediaDeviceState.STATE_CONNECTING
                || connectionState == MediaDeviceState.STATE_GROUPING
                || connectionState == MediaDeviceState.STATE_CONNECTING_FAILED;
    }

    private void setSuggestionText(SuggestedMediaDeviceData suggestionData) {
        String suggestionText = null;
        switch (suggestionData.getConnectionState()) {
            case MediaDeviceState.STATE_DISCONNECTED:
            case MediaDeviceState.STATE_CONNECTING:
            case MediaDeviceState.STATE_GROUPING:
                suggestionText =
                        mContext.getString(
                                R.string.media_suggestion_disconnected_text,
                                suggestionData.getName());
                break;
            case MediaDeviceState.STATE_CONNECTING_FAILED:
                suggestionText = mContext.getString(R.string.media_suggestion_failure_text);
                break;
            default:
                Log.wtf(TAG, "Invalid media device state for suggestion: "
                        + suggestionData.getConnectionState());
        }
        mMediaViewHolder.getDeviceSuggestionText().setText(suggestionText);
    }

    private void setSuggestionIcon(SuggestedMediaDeviceData suggestionData) {
        int connectionState = suggestionData.getConnectionState();
        if (connectionState == MediaDeviceState.STATE_CONNECTING
                || connectionState == MediaDeviceState.STATE_GROUPING) {
            mMediaViewHolder.getDeviceSuggestionIcon().setVisibility(View.GONE);
            mMediaViewHolder.getDeviceSuggestionConnectingIcon().setVisibility(View.VISIBLE);
            return;
        }
        mMediaViewHolder.getDeviceSuggestionConnectingIcon().setVisibility(View.GONE);
        mMediaViewHolder.getDeviceSuggestionIcon().setImageDrawable(suggestionData.getIcon());
        mMediaViewHolder.getDeviceSuggestionIcon().setVisibility(View.VISIBLE);
    }

    private void setSuggestionClickListener(SuggestedMediaDeviceData suggestionData) {
        ViewGroup deviceSuggestionContainer = mMediaViewHolder.getDeviceSuggestionContainer();
        int connectionState = suggestionData.getConnectionState();
        if (connectionState == MediaDeviceState.STATE_DISCONNECTED
                || connectionState == MediaDeviceState.STATE_CONNECTING_FAILED) {

            deviceSuggestionContainer.setClickable(true);
            deviceSuggestionContainer
                    .setOnClickListener(
                            v -> {
                                suggestionData.getConnect().invoke();
                            });
        } else {
            deviceSuggestionContainer.setOnClickListener(null);
            deviceSuggestionContainer.setClickable(false);
        }
    }

    private void bindOutputSwitcherChip(MediaData data) {
        ViewGroup seamlessView = mMediaViewHolder.getSeamless();
        seamlessView.setVisibility(View.VISIBLE);
        ImageView iconView = mMediaViewHolder.getSeamlessIcon();
        TextView deviceName = mMediaViewHolder.getSeamlessText();
        final MediaDeviceData device = data.getDevice();

        // Disable clicking on output switcher for invalid devices and resumption controls
        final boolean isDisabled =
                (device != null && !device.getEnabled()) || data.getResumption();
        CharSequence deviceString = mContext.getString(R.string.media_seamless_other_device);
        final int iconResource = R.drawable.ic_media_home_devices;

        mMediaViewHolder.getSeamlessButton().setAlpha(isDisabled ? DISABLED_ALPHA : 1.0f);
        seamlessView.setEnabled(!isDisabled);

        if (device != null) {
            Drawable icon = device.getIcon();
            if (icon instanceof AdaptiveIcon) {
                AdaptiveIcon aIcon = (AdaptiveIcon) icon;
                aIcon.setBackgroundColor(mColorSchemeTransition.getDeviceIconColor());
                iconView.setImageDrawable(aIcon);
            } else {
                iconView.setImageDrawable(icon);
            }
            if (device.getName() != null) {
                deviceString = device.getName();
            }
        } else {
            // Set to default icon
            iconView.setImageResource(iconResource);
        }
        deviceName.setText(deviceString);
        seamlessView.setContentDescription(deviceString);
        seamlessView.setOnClickListener(
                v -> {
                    if (mFalsingManager.isFalseTap(FalsingManager.MODERATE_PENALTY)) {
                        return;
                    }

                    mLogger.logOpenOutputSwitcher(mUid, mPackageName, mInstanceId);
                    if (device.getIntent() != null) {
                        PendingIntent deviceIntent = device.getIntent();
                        boolean showOverLockscreen =
                                mKeyguardStateController.isShowing()
                                        && mActivityIntentHelper.wouldPendingShowOverLockscreen(
                                        deviceIntent,
                                        mLockscreenUserManager.getCurrentUserId());
                        if (deviceIntent.isActivity()) {
                            if (!showOverLockscreen) {
                                mActivityStarter.postStartActivityDismissingKeyguard(deviceIntent);
                            } else {
                                try {
                                    BroadcastOptions options = BroadcastOptions.makeBasic();
                                    options.setInteractive(true);
                                    options.setPendingIntentBackgroundActivityStartMode(
                                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                                    deviceIntent.send(options.toBundle());
                                } catch (PendingIntent.CanceledException e) {
                                    Log.e(TAG, "Device pending intent was canceled");
                                }
                            }
                        } else {
                            Log.w(TAG, "Device pending intent is not an activity.");
                        }
                    } else {
                        mMediaOutputDialogManager.createAndShow(
                                mPackageName,
                                /* aboveStatusBar= */ true,
                                mMediaViewHolder.getSeamlessButton(),
                                UserHandle.getUserHandleForUid(mUid),
                                mToken,
                                /* useSystemColors= */ false);
                    }
                });
    }

    private void bindGutsMenuForPlayer(MediaData data) {
        Runnable onDismissClickedRunnable = () -> {
            if (mKey != null) {
                closeGuts();
                if (!mMediaDataManagerLazy.get().dismissMediaData(mKey,
                        /* delay */ MediaViewController.GUTS_ANIMATION_DURATION + 100,
                        /* userInitiated */ true)) {
                    Log.w(TAG, "Manager failed to dismiss media " + mKey);
                    // Remove directly from carousel so user isn't stuck with defunct controls
                    mMediaCarouselController.removePlayer(mKey, false, true);
                }
            } else {
                Log.w(TAG, "Dismiss media with null notification. Token uid="
                        + data.getToken().getUid());
            }
        };

        bindGutsMenuCommon(
                /* isDismissible= */ data.isClearable(),
                data.getApp(),
                mMediaViewHolder.getGutsViewHolder(),
                onDismissClickedRunnable);
    }

    private boolean bindSongMetadata(MediaData data) {
        TextView titleText = mMediaViewHolder.getTitleText();
        TextView artistText = mMediaViewHolder.getArtistText();
        ConstraintSet expandedSet = mMediaViewController.getExpandedLayout();
        ConstraintSet collapsedSet = mMediaViewController.getCollapsedLayout();
        return mMetadataAnimationHandler.setNext(
                new Triple(data.getSong(), data.getArtist(), data.isExplicit()),
                () -> {
                    titleText.setText(data.getSong());
                    artistText.setText(data.getArtist());
                    setVisibleAndAlpha(expandedSet, R.id.media_explicit_indicator,
                            data.isExplicit());
                    setVisibleAndAlpha(collapsedSet, R.id.media_explicit_indicator,
                            data.isExplicit());

                    // refreshState is required here to resize the text views (and prevent ellipsis)
                    mMediaViewController.refreshState();
                    return Unit.INSTANCE;
                },
                () -> {
                    // After finishing the enter animation, we refresh state. This could pop if
                    // something is incorrectly bound, but needs to be run if other elements were
                    // updated while the enter animation was running
                    mMediaViewController.refreshState();
                    return Unit.INSTANCE;
                });
    }

    private void bindPlayerContentDescription(MediaData data) {
        if (mMediaViewHolder == null) {
            return;
        }

        CharSequence contentDescription;
        if (mMediaViewController.isGutsVisible()) {
            contentDescription = mMediaViewHolder.getGutsViewHolder().getGutsText().getText();
        } else if (data != null) {
            contentDescription = mContext.getString(
                    R.string.controls_media_playing_item_description,
                    data.getSong(),
                    data.getArtist(),
                    data.getApp());
        } else {
            contentDescription = null;
        }
        mMediaViewHolder.getPlayer().setContentDescription(contentDescription);
    }

    private void bindArtworkAndColors(MediaData data, String key, boolean updateBackground) {
        final int traceCookie = data.hashCode();
        final String traceName = "MediaControlPanel#bindArtworkAndColors<" + key + ">";
        Trace.beginAsyncSection(traceName, traceCookie);

        final int reqId = mArtworkNextBindRequestId++;
        if (updateBackground) {
            mIsArtworkBound = false;
        }

        // Capture width & height from views in foreground for artwork scaling in background
        int width = mMediaViewHolder.getAlbumView().getMeasuredWidth();
        int height = mMediaViewHolder.getAlbumView().getMeasuredHeight();

        final int finalWidth = width;
        final int finalHeight = height;
        mBackgroundExecutor.execute(() -> {
            // Album art
            ColorScheme mutableColorScheme = null;
            Drawable artwork;
            boolean isArtworkBound;
            Icon artworkIcon = data.getArtwork();
            WallpaperColors wallpaperColors = getWallpaperColor(artworkIcon);
            boolean darkTheme = false;
            if (wallpaperColors != null) {
                mutableColorScheme = new ColorScheme(wallpaperColors, darkTheme,
                        ThemeStyle.CONTENT);
                artwork = addGradientToPlayerAlbum(artworkIcon, mutableColorScheme, finalWidth,
                        finalHeight);
                isArtworkBound = true;
            } else {
                // If there's no artwork, use colors from the app icon
                artwork = new ColorDrawable(Color.TRANSPARENT);
                isArtworkBound = false;
                try {
                    Drawable icon = getAppIcon(data.getPackageName());
                    mutableColorScheme = new ColorScheme(WallpaperColors.fromDrawable(icon),
                            darkTheme, ThemeStyle.CONTENT);
                } catch (PackageManager.NameNotFoundException e) {
                    Log.w(TAG, "Cannot find icon for package " + data.getPackageName(), e);
                }
            }

            final ColorScheme colorScheme = mutableColorScheme;
            mMainExecutor.execute(() -> {
                // Cancel the request if a later one arrived first
                if (reqId < mArtworkBoundId) {
                    Trace.endAsyncSection(traceName, traceCookie);
                    return;
                }
                mArtworkBoundId = reqId;

                // Transition Colors to current color scheme
                boolean colorSchemeChanged;
                colorSchemeChanged = mColorSchemeTransition.updateColorScheme(colorScheme);

                // Bind the album view to the artwork or a transition drawable
                ImageView albumView = mMediaViewHolder.getAlbumView();
                albumView.setPadding(0, 0, 0, 0);
                if (updateBackground || colorSchemeChanged
                        || (!mIsArtworkBound && isArtworkBound)) {
                    if (mPrevArtwork == null) {
                        albumView.setImageDrawable(artwork);
                    } else {
                        // Since we throw away the last transition, this'll pop if you backgrounds
                        // are cycled too fast (or the correct background arrives very soon after
                        // the metadata changes).
                        TransitionDrawable transitionDrawable = new TransitionDrawable(
                                new Drawable[]{mPrevArtwork, artwork});

                        scaleTransitionDrawableLayer(transitionDrawable, 0, finalWidth,
                                finalHeight);
                        scaleTransitionDrawableLayer(transitionDrawable, 1, finalWidth,
                                finalHeight);
                        transitionDrawable.setLayerGravity(0, Gravity.CENTER);
                        transitionDrawable.setLayerGravity(1, Gravity.CENTER);
                        transitionDrawable.setCrossFadeEnabled(true);
                        albumView.setImageDrawable(transitionDrawable);
                        transitionDrawable.startTransition(isArtworkBound ? 333 : 80);
                    }
                    mPrevArtwork = artwork;
                    mIsArtworkBound = isArtworkBound;
                }

                // App icon - use notification icon
                ImageView appIconView = mMediaViewHolder.getAppIcon();
                appIconView.clearColorFilter();
                if (data.getAppIcon() != null && !data.getResumption()) {
                    appIconView.setImageIcon(data.getAppIcon());
                    appIconView.setColorFilter(mColorSchemeTransition.getAppIconColor());
                } else {
                    // Resume players use launcher icon
                    appIconView.setColorFilter(getGrayscaleFilter());
                    try {
                        Drawable icon = getAppIcon(data.getPackageName());
                        appIconView.setImageDrawable(icon);
                    } catch (PackageManager.NameNotFoundException e) {
                        Log.w(TAG, "Cannot find icon for package " + data.getPackageName(), e);
                        appIconView.setImageResource(R.drawable.ic_music_note);
                        appIconView.setColorFilter(Color.WHITE);
                    }
                }
                Trace.endAsyncSection(traceName, traceCookie);
            });
        });
    }

    private Drawable getAppIcon(String packageName) throws PackageManager.NameNotFoundException {
        ApplicationInfo appInfo = mContext.getPackageManager()
                .getApplicationInfoAsUser(packageName, 0, mMediaData.getUserId());
        return mContext.getPackageManager().getApplicationIcon(appInfo);
    }

    // This method should be called from a background thread. WallpaperColors.fromBitmap takes a
    // good amount of time. We do that work on the background executor to avoid stalling animations
    // on the UI Thread.
    @VisibleForTesting
    protected WallpaperColors getWallpaperColor(Icon artworkIcon) {
        if (artworkIcon != null) {
            if (artworkIcon.getType() == Icon.TYPE_BITMAP
                    || artworkIcon.getType() == Icon.TYPE_ADAPTIVE_BITMAP) {
                // Avoids extra processing if this is already a valid bitmap
                Bitmap artworkBitmap = artworkIcon.getBitmap();
                if (artworkBitmap.isRecycled()) {
                    Log.d(TAG, "Cannot load wallpaper color from a recycled bitmap");
                    return null;
                }
                return WallpaperColors.fromBitmap(artworkBitmap);
            } else {
                Drawable artworkDrawable = artworkIcon.loadDrawable(mContext);
                if (artworkDrawable != null) {
                    return WallpaperColors.fromDrawable(artworkDrawable);
                }
            }
        }
        return null;
    }

    @VisibleForTesting
    protected LayerDrawable addGradientToPlayerAlbum(Icon artworkIcon,
            ColorScheme mutableColorScheme, int width, int height) {
        Drawable albumArt = getScaledBackground(artworkIcon, width, height);
        GradientDrawable gradient = (GradientDrawable) mContext.getDrawable(
                R.drawable.qs_media_scrim).mutate();
        return setupGradientColorOnDrawable(albumArt, gradient, mutableColorScheme,
                MEDIA_PLAYER_SCRIM_START_ALPHA, MEDIA_PLAYER_SCRIM_END_ALPHA);
    }

    private LayerDrawable setupGradientColorOnDrawable(Drawable albumArt, GradientDrawable gradient,
            ColorScheme mutableColorScheme, float startAlpha, float endAlpha) {
        int color = MediaColorSchemesKt.backgroundFromScheme(mutableColorScheme);
        gradient.setColors(new int[]{
                ColorUtilKt.getColorWithAlpha(color, startAlpha),
                ColorUtilKt.getColorWithAlpha(color, endAlpha),
        });
        return new LayerDrawable(new Drawable[]{albumArt, gradient});
    }

    private void scaleTransitionDrawableLayer(TransitionDrawable transitionDrawable, int layer,
            int targetWidth, int targetHeight) {
        Drawable drawable = transitionDrawable.getDrawable(layer);
        if (drawable == null) {
            return;
        }

        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();
        float scale = MediaDataUtils.getScaleFactor(new Pair(width, height),
                new Pair(targetWidth, targetHeight));
        if (scale == 0) return;
        transitionDrawable.setLayerSize(layer, (int) (scale * width), (int) (scale * height));
    }

    private void bindActionButtons(MediaData data) {
        MediaButton semanticActions = data.getSemanticActions();

        List<ImageButton> genericButtons = new ArrayList<>();
        for (int id : MediaViewHolder.Companion.getGenericButtonIds()) {
            genericButtons.add(mMediaViewHolder.getAction(id));
        }

        ConstraintSet expandedSet = mMediaViewController.getExpandedLayout();
        ConstraintSet collapsedSet = mMediaViewController.getCollapsedLayout();
        if (semanticActions != null) {
            // Hide all the generic buttons
            for (ImageButton b : genericButtons) {
                setVisibleAndAlpha(collapsedSet, b.getId(), false);
                setVisibleAndAlpha(expandedSet, b.getId(), false);
            }

            for (int id : SEMANTIC_ACTIONS_ALL) {
                ImageButton button = mMediaViewHolder.getAction(id);
                MediaAction action = semanticActions.getActionById(id);
                setSemanticButton(button, action, semanticActions);
            }
        } else {
            // Hide buttons that only appear for semantic actions
            for (int id : SEMANTIC_ACTIONS_COMPACT) {
                setVisibleAndAlpha(collapsedSet, id, false);
                setVisibleAndAlpha(expandedSet, id, false);
            }

            // Set all the generic buttons
            List<Integer> actionsWhenCollapsed = data.getActionsToShowInCompact();
            List<MediaAction> actions = getNotificationActions(data.getActions(), mActivityStarter);
            int i = 0;
            for (; i < actions.size() && i < genericButtons.size(); i++) {
                boolean showInCompact = actionsWhenCollapsed.contains(i);
                setGenericButton(
                        genericButtons.get(i),
                        actions.get(i),
                        collapsedSet,
                        expandedSet,
                        showInCompact);
            }
            for (; i < genericButtons.size(); i++) {
                // Hide any unused buttons
                setGenericButton(
                        genericButtons.get(i),
                        /* mediaAction= */ null,
                        collapsedSet,
                        expandedSet,
                        /* showInCompact= */ false);
            }
        }

        updateSeekBarVisibility();
    }

    private void bindPageButtons() {
        ImageButton pageLeft = mMediaViewHolder.getPageLeft();
        pageLeft.setOnClickListener(v -> {
            mMediaCarouselController.getMediaCarouselScrollHandler().scrollByStep(-1);
            mMultiRippleController.play(createTouchRippleAnimation(pageLeft));
        });

        ImageButton pageRight = mMediaViewHolder.getPageRight();
        pageRight.setOnClickListener(v -> {
            mMediaCarouselController.getMediaCarouselScrollHandler().scrollByStep(1);
            mMultiRippleController.play(createTouchRippleAnimation(pageRight));
        });
    }

    void setPageArrowsVisible(boolean visible) {
        if (mPageArrowsVisible == visible) return;
        mPageArrowsVisible = visible;

        ConstraintSet expandedSet = mMediaViewController.getExpandedLayout();
        setVisibleAndAlpha(expandedSet, R.id.page_left, visible);
        setVisibleAndAlpha(expandedSet, R.id.page_right, visible);

        ConstraintSet collapsedSet = mMediaViewController.getCollapsedLayout();
        setVisibleAndAlpha(collapsedSet, R.id.page_left, visible);
        setVisibleAndAlpha(collapsedSet, R.id.page_right, visible);

        int guidelineDimen = visible
                ? R.dimen.qs_media_session_collapsed_guideline_with_arrows
                : R.dimen.qs_media_session_collapsed_guideline;
        collapsedSet.setGuidelineEnd(
                R.id.action_button_guideline,
                mContext.getResources().getDimensionPixelSize(guidelineDimen));
    }

    void setPageLeftEnabled(boolean enabled) {
        ImageButton pageLeft = mMediaViewHolder.getPageLeft();
        pageLeft.setEnabled(enabled);
    }

    void setPageRightEnabled(boolean enabled) {
        ImageButton pageRight = mMediaViewHolder.getPageRight();
        pageRight.setEnabled(enabled);
    }

    private void updateSeekBarVisibility() {
        ConstraintSet expandedSet = mMediaViewController.getExpandedLayout();
        expandedSet.setVisibility(R.id.media_progress_bar, getSeekBarVisibility());
        expandedSet.setAlpha(R.id.media_progress_bar, mIsSeekBarEnabled ? 1.0f : 0.0f);
    }

    private int getSeekBarVisibility() {
        if (mIsSeekBarEnabled) {
            return ConstraintSet.VISIBLE;
        }
        // Set progress bar to INVISIBLE to keep the positions of text and buttons similar to the
        // original positions when seekbar is enabled.
        return ConstraintSet.INVISIBLE;
    }

    private void setGenericButton(
            final ImageButton button,
            @Nullable MediaAction mediaAction,
            ConstraintSet collapsedSet,
            ConstraintSet expandedSet,
            boolean showInCompact) {
        bindButtonCommon(button, mediaAction);
        boolean visible = mediaAction != null;
        setVisibleAndAlpha(expandedSet, button.getId(), visible);
        setVisibleAndAlpha(collapsedSet, button.getId(), visible && showInCompact);
    }

    private void setSemanticButton(
            final ImageButton button,
            @Nullable MediaAction mediaAction,
            MediaButton semanticActions) {
        AnimationBindHandler animHandler;
        if (button.getTag() == null) {
            animHandler = new AnimationBindHandler();
            button.setTag(animHandler);
        } else {
            animHandler = (AnimationBindHandler) button.getTag();
        }

        animHandler.tryExecute(() -> {
            bindButtonWithAnimations(button, mediaAction, animHandler);
            setSemanticButtonVisibleAndAlpha(button.getId(), mediaAction, semanticActions);
            return Unit.INSTANCE;
        });
    }

    private void bindButtonWithAnimations(
            final ImageButton button,
            @Nullable MediaAction mediaAction,
            @NonNull AnimationBindHandler animHandler) {
        if (mediaAction != null) {
            if (animHandler.updateRebindId(mediaAction.getRebindId())) {
                animHandler.unregisterAll();
                animHandler.tryRegister(mediaAction.getIcon());
                animHandler.tryRegister(mediaAction.getBackground());
                bindButtonCommon(button, mediaAction);
            }
        } else {
            animHandler.unregisterAll();
            clearButton(button);
        }
    }

    private void bindButtonCommon(final ImageButton button, @Nullable MediaAction mediaAction) {
        if (mediaAction != null) {
            final Drawable icon = mediaAction.getIcon();
            button.setImageDrawable(icon);
            button.setContentDescription(mediaAction.getContentDescription());
            final Drawable bgDrawable = mediaAction.getBackground();
            button.setBackground(bgDrawable);

            Runnable action = mediaAction.getAction();
            if (action == null) {
                button.setEnabled(false);
            } else {
                button.setEnabled(true);
                button.setOnClickListener(v -> {
                    if (!mFalsingManager.isFalseTap(FalsingManager.MODERATE_PENALTY)) {
                        mLogger.logTapAction(button.getId(), mUid, mPackageName, mInstanceId);
                        // Used to determine whether to play turbulence noise.
                        mWasPlaying = isPlaying();
                        mButtonClicked = true;

                        action.run();

                        mMultiRippleController.play(createTouchRippleAnimation(button));

                        if (icon instanceof Animatable) {
                            ((Animatable) icon).start();
                        }
                        if (bgDrawable instanceof Animatable) {
                            ((Animatable) bgDrawable).start();
                        }
                    }
                });
            }
        } else {
            clearButton(button);
        }
    }

    private RippleAnimation createTouchRippleAnimation(ImageButton button) {
        float maxSize = mMediaViewHolder.getMultiRippleView().getWidth() * 2;
        return new RippleAnimation(
                new RippleAnimationConfig(
                        RippleShader.RippleShape.CIRCLE,
                        /* duration= */ 1500L,
                        /* centerX= */ button.getX() + button.getWidth() * 0.5f,
                        /* centerY= */ button.getY() + button.getHeight() * 0.5f,
                        /* maxWidth= */ maxSize,
                        /* maxHeight= */ maxSize,
                        /* pixelDensity= */ getContext().getResources().getDisplayMetrics().density,
                        /* color= */ mColorSchemeTransition.getSurfaceEffectColor(),
                        /* opacity= */ 100,
                        /* sparkleStrength= */ 0f,
                        /* baseRingFadeParams= */ null,
                        /* sparkleRingFadeParams= */ null,
                        /* centerFillFadeParams= */ null,
                        /* shouldDistort= */ false
                )
        );
    }

    private boolean shouldPlayTurbulenceNoise() {
        return mButtonClicked && !mWasPlaying && isPlaying();
    }

    private TurbulenceNoiseAnimationConfig createTurbulenceNoiseConfig() {
        View targetView = mMediaViewHolder.getLoadingEffectView();
        int width = targetView.getWidth();
        int height = targetView.getHeight();
        Random random = new Random();
        float luminosity = 0.6f;

        return new TurbulenceNoiseAnimationConfig(
                /* gridCount= */ 2.14f,
                /* luminosityMultiplier= */ luminosity,
                /* noiseOffsetX= */ random.nextFloat(),
                /* noiseOffsetY= */ random.nextFloat(),
                /* noiseOffsetZ= */ random.nextFloat(),
                /* noiseMoveSpeedX= */ 0.42f,
                /* noiseMoveSpeedY= */ 0f,
                TurbulenceNoiseAnimationConfig.DEFAULT_NOISE_SPEED_Z,
                // Color will be correctly updated in ColorSchemeTransition.
                /* color= */ mColorSchemeTransition.getSurfaceEffectColor(),
                /* screenColor= */ Color.BLACK,
                width,
                height,
                TurbulenceNoiseAnimationConfig.DEFAULT_MAX_DURATION_IN_MILLIS,
                /* easeInDuration= */ 1350f,
                /* easeOutDuration= */ 1350f,
                getContext().getResources().getDisplayMetrics().density,
                /* lumaMatteBlendFactor= */ 0.26f,
                /* lumaMatteOverallBrightness= */ 0.09f,
                /* shouldInverseNoiseLuminosity= */ false
        );
    }

    private void clearButton(final ImageButton button) {
        button.setImageDrawable(null);
        button.setContentDescription(null);
        button.setEnabled(false);
        button.setBackground(null);
    }

    private void setSemanticButtonVisibleAndAlpha(
            int buttonId,
            @Nullable MediaAction mediaAction,
            MediaButton semanticActions) {
        ConstraintSet collapsedSet = mMediaViewController.getCollapsedLayout();
        ConstraintSet expandedSet = mMediaViewController.getExpandedLayout();
        boolean showInCompact = SEMANTIC_ACTIONS_COMPACT.contains(buttonId);
        boolean hideWhenScrubbing = SEMANTIC_ACTIONS_HIDE_WHEN_SCRUBBING.contains(buttonId);
        boolean shouldBeHiddenDueToScrubbing =
                scrubbingTimeViewsEnabled(semanticActions) && hideWhenScrubbing && mIsScrubbing;
        boolean visible = mediaAction != null && !shouldBeHiddenDueToScrubbing;

        int notVisibleValue;
        if (!shouldBeHiddenDueToScrubbing
                && ((buttonId == R.id.actionPrev && semanticActions.getReservePrev())
                || (buttonId == R.id.actionNext && semanticActions.getReserveNext()))) {
            notVisibleValue = ConstraintSet.INVISIBLE;
            mMediaViewHolder.getAction(buttonId).setFocusable(visible);
            mMediaViewHolder.getAction(buttonId).setClickable(visible);
        } else {
            notVisibleValue = ConstraintSet.GONE;
        }
        setVisibleAndAlpha(expandedSet, buttonId, visible, notVisibleValue);
        setVisibleAndAlpha(collapsedSet, buttonId, visible && showInCompact);
    }

    /** Updates all the views that might change due to a scrubbing state change. */
    private void updateDisplayForScrubbingChange(@NonNull MediaButton semanticActions) {
        // Update visibilities of the scrubbing time views and the scrubbing-dependent buttons.
        bindScrubbingTime(mMediaData);
        SEMANTIC_ACTIONS_HIDE_WHEN_SCRUBBING.forEach((id) -> setSemanticButtonVisibleAndAlpha(
                id, semanticActions.getActionById(id), semanticActions));
        if (!mMetadataAnimationHandler.isRunning()) {
            // Trigger a state refresh so that we immediately update visibilities.
            mMediaViewController.refreshState();
        }
    }

    private void bindScrubbingTime(MediaData data) {
        ConstraintSet expandedSet = mMediaViewController.getExpandedLayout();
        int elapsedTimeId = mMediaViewHolder.getScrubbingElapsedTimeView().getId();
        int totalTimeId = mMediaViewHolder.getScrubbingTotalTimeView().getId();

        boolean visible = scrubbingTimeViewsEnabled(data.getSemanticActions()) && mIsScrubbing;
        setVisibleAndAlpha(expandedSet, elapsedTimeId, visible);
        setVisibleAndAlpha(expandedSet, totalTimeId, visible);
        // Collapsed view is always GONE as set in XML, so doesn't need to be updated dynamically
    }

    private boolean scrubbingTimeViewsEnabled(@Nullable MediaButton semanticActions) {
        // The scrubbing time views replace the SEMANTIC_ACTIONS_HIDE_WHEN_SCRUBBING action views,
        // so we should only allow scrubbing times to be shown if those action views are present.
        return semanticActions != null && SEMANTIC_ACTIONS_HIDE_WHEN_SCRUBBING.stream().allMatch(
                id -> (semanticActions.getActionById(id) != null
                        || ((id == R.id.actionPrev && semanticActions.getReservePrev())
                        || (id == R.id.actionNext && semanticActions.getReserveNext())))
        );
    }

    @Nullable
    private ActivityTransitionAnimator.Controller buildLaunchAnimatorController(
            TransitionLayout player) {
        if (!(player.getParent() instanceof ViewGroup)) {
            // TODO(b/192194319): Throw instead of just logging.
            Log.wtf(TAG, "Skipping player animation as it is not attached to a ViewGroup",
                    new Exception());
            return null;
        }

        // TODO(b/174236650): Make sure that the carousel indicator also fades out.
        // TODO(b/174236650): Instrument the animation to measure jank.
        final ActivityTransitionAnimator.Controller controller =
                new GhostedViewTransitionAnimatorController(player,
                        InteractionJankMonitor.CUJ_SHADE_APP_LAUNCH_FROM_MEDIA_PLAYER) {
                    @Override
                    protected float getCurrentTopCornerRadius() {
                        return mContext.getResources().getDimension(
                                R.dimen.notification_corner_radius);
                    }

                    @Override
                    protected float getCurrentBottomCornerRadius() {
                        // TODO(b/184121838): Make IlluminationDrawable support top and bottom
                        //  radius.
                        return getCurrentTopCornerRadius();
                    }
                };

        // When on the hub, wrap in the communal animation controller to ensure we exit the hub
        // at the proper stage of the animation.
        if (communalHub()
                && mMediaViewController.getCurrentEndLocation()
                == MediaHierarchyManager.LOCATION_COMMUNAL_HUB) {
            mCommunalSceneInteractor.setIsLaunchingWidget(true);
            return mCommunalAnimationControllerFactory.create(controller);
        }
        return controller;
    }

    private void bindGutsMenuCommon(
            boolean isDismissible,
            String appName,
            GutsViewHolder gutsViewHolder,
            Runnable onDismissClickedRunnable) {
        // Text
        String text;
        if (isDismissible) {
            text = mContext.getString(R.string.controls_media_close_session, appName);
        } else {
            text = mContext.getString(R.string.controls_media_active_session);
        }
        gutsViewHolder.getGutsText().setText(text);

        // Dismiss button
        gutsViewHolder.getDismissText().setVisibility(isDismissible ? View.VISIBLE : View.GONE);
        gutsViewHolder.getDismiss().setEnabled(isDismissible);
        gutsViewHolder.getDismiss().setOnClickListener(v -> {
            if (mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) return;
            mLogger.logLongPressDismiss(mUid, mPackageName, mInstanceId);

            onDismissClickedRunnable.run();
        });

        // Cancel button
        TextView cancelText = gutsViewHolder.getCancelText();
        if (isDismissible) {
            cancelText.setBackground(mContext.getDrawable(R.drawable.qs_media_outline_button));
        } else {
            cancelText.setBackground(mContext.getDrawable(R.drawable.qs_media_solid_button));
        }
        gutsViewHolder.getCancel().setOnClickListener(v -> {
            if (!mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                closeGuts();
            }
        });
        gutsViewHolder.setDismissible(isDismissible);

        // Settings button
        gutsViewHolder.getSettings().setOnClickListener(v -> {
            if (!mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                mLogger.logLongPressSettings(mUid, mPackageName, mInstanceId);
                mActivityStarter.startActivity(SETTINGS_INTENT, /* dismissShade= */true);
            }
        });
    }

    /**
     * Close the guts for this player.
     *
     * @param immediate {@code true} if it should be closed without animation
     */
    public void closeGuts(boolean immediate) {
        if (mMediaViewHolder != null) {
            mMediaViewHolder.marquee(false, mMediaViewController.GUTS_ANIMATION_DURATION);
        }
        mMediaViewController.closeGuts(immediate);
        if (mMediaViewHolder != null) {
            bindPlayerContentDescription(mMediaData);
        }
    }

    private void closeGuts() {
        closeGuts(false);
    }

    private void openGuts() {
        if (mMediaViewHolder != null) {
            mMediaViewHolder.marquee(true, mMediaViewController.GUTS_ANIMATION_DURATION);
        }
        mMediaViewController.openGuts();
        if (mMediaViewHolder != null) {
            bindPlayerContentDescription(mMediaData);
        }
        mLogger.logLongPressOpen(mUid, mPackageName, mInstanceId);
    }

    /**
     * Scale artwork to fill the background of the panel
     */
    @UiThread
    private Drawable getScaledBackground(Icon icon, int width, int height) {
        if (icon == null) {
            return null;
        }
        Drawable drawable = icon.loadDrawable(mContext);
        Rect bounds = new Rect(0, 0, width, height);
        if (bounds.width() > width || bounds.height() > height) {
            float offsetX = (bounds.width() - width) / 2.0f;
            float offsetY = (bounds.height() - height) / 2.0f;
            bounds.offset((int) -offsetX, (int) -offsetY);
        }
        drawable.setBounds(bounds);
        return drawable;
    }

    /**
     * Get the current media controller
     *
     * @return the controller
     */
    public MediaController getController() {
        return mController;
    }

    /**
     * Check whether the media controlled by this player is currently playing
     *
     * @return whether it is playing, or false if no controller information
     */
    public boolean isPlaying() {
        return isPlaying(mController);
    }

    /**
     * Check whether the given controller is currently playing
     *
     * @param controller media controller to check
     * @return whether it is playing, or false if no controller information
     */
    protected boolean isPlaying(MediaController controller) {
        if (controller == null) {
            return false;
        }

        PlaybackState state = controller.getPlaybackState();
        if (state == null) {
            return false;
        }

        return (state.getState() == PlaybackState.STATE_PLAYING);
    }

    private ColorMatrixColorFilter getGrayscaleFilter() {
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0);
        return new ColorMatrixColorFilter(matrix);
    }

    private void setVisibleAndAlpha(ConstraintSet set, int actionId, boolean visible) {
        setVisibleAndAlpha(set, actionId, visible, ConstraintSet.GONE);
    }

    private void setVisibleAndAlpha(ConstraintSet set, int actionId, boolean visible,
            int notVisibleValue) {
        set.setVisibility(actionId, visible ? ConstraintSet.VISIBLE : notVisibleValue);
        set.setAlpha(actionId, visible ? 1.0f : 0.0f);
    }
}

