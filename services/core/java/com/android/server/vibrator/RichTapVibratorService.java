/*
 * Copyright (C) 2021 The Android AAC vibration extension
 * Copyright (C) 2026 The LineageOS Project
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

package com.android.server.vibrator;

import android.os.Binder;
import android.os.CombinedVibration;
import android.os.HapticPlayer;
import android.os.IBinder;
import android.os.RichTapVibrationEffect;
import android.os.ServiceManager;
import android.os.VibrationEffect;
import android.util.Slog;

import vendor.aac.hardware.richtap.vibrator.IRichtapCallback;
import vendor.aac.hardware.richtap.vibrator.IRichtapVibrator;

/**
 * Bridges the framework RichTap effects to the RichTap HAL extension attached to the standard
 * vibrator HAL service.
 *
 * <p>The RichTap AIDL interface is exposed by the vibrator HAL as a binder extension of the
 * {@code android.hardware.vibrator.IVibrator/default} service, and is fetched lazily here. A
 * device only enables this path when it declares {@code config_hasRichTapVibrator}, so on
 * devices without a RichTap-capable HAL the service is never instantiated.
 */
public class RichTapVibratorService {
    private static final String TAG = "RichTapVibratorService";
    private static final boolean DEBUG = false;

    private static final String VIBRATOR_DESCRIPTOR = "android.hardware.vibrator.IVibrator/default";

    public static final String ACTION_CHANGE_MODE = "richtap_change_mode";

    private final IRichtapCallback mCallback;

    private volatile IRichtapVibrator sRichtapVibratorService = null;
    private VibHalDeathRecipient mHalDeathLinker = null;
    // Set once the extension is known to be unavailable, so we do not retry (and log) on every
    // effect. Cleared again when the vibrator HAL dies and is re-registered.
    private boolean mRichtapUnavailable = false;

    public enum HapticParamType {
        HAPTIC_DRC(0x01);

        private final int type;

        HapticParamType(int type) {
            this.type = type;
        }

        public int getValue() {
            return type;
        }
    }

    RichTapVibratorService(IRichtapCallback callback) {
        mCallback = callback;
    }

    private IRichtapVibrator getRichtapService() {
        synchronized (RichTapVibratorService.class) {
            if (sRichtapVibratorService == null && !mRichtapUnavailable) {
                final android.hardware.vibrator.IVibrator vibratorHalService =
                        android.hardware.vibrator.IVibrator.Stub.asInterface(
                                ServiceManager.getService(VIBRATOR_DESCRIPTOR));
                if (vibratorHalService == null) {
                    if (DEBUG) {
                        Slog.d(TAG, "Vibrator HAL service not available");
                    }
                    mRichtapUnavailable = true;
                    return null;
                }
                try {
                    final IBinder binder = vibratorHalService.asBinder().getExtension();
                    if (binder == null) {
                        Slog.e(TAG, "Vibrator HAL does not expose a RichTap extension");
                        mRichtapUnavailable = true;
                        return null;
                    }
                    sRichtapVibratorService =
                            IRichtapVibrator.Stub.asInterface(Binder.allowBlocking(binder));
                    mHalDeathLinker = new VibHalDeathRecipient(this);
                    binder.linkToDeath(mHalDeathLinker, 0);
                } catch (Exception e) {
                    sRichtapVibratorService = null;
                    mRichtapUnavailable = true;
                    Slog.e(TAG, "Failed to get RichTap extension", e);
                }
            }
        }
        return sRichtapVibratorService;
    }

    /**
     * Intercepts RichTap parameter effects that are not vibrations by themselves: HE play-time
     * parameter updates and raw haptic (DRC) parameters. Returns {@code true} when the effect was
     * consumed and must not be processed by the standard vibrator path.
     */
    public boolean disposeRichtapEffectParams(CombinedVibration combEffect) {
        if (!(combEffect instanceof CombinedVibration.Mono)) {
            return false;
        }
        final VibrationEffect effect = ((CombinedVibration.Mono) combEffect).getEffect();
        if (effect instanceof RichTapVibrationEffect.PatternHeParameter) {
            final RichTapVibrationEffect.PatternHeParameter param =
                    (RichTapVibrationEffect.PatternHeParameter) effect;
            if (DEBUG) {
                Slog.d(TAG, "performHeParam interval=" + param.getInterval()
                        + " amplitude=" + param.getAmplitude() + " freq=" + param.getFreq());
            }
            final IRichtapVibrator service = getRichtapService();
            if (service != null) {
                try {
                    service.performHeParam(param.getInterval(), param.getAmplitude(),
                            param.getFreq(), mCallback);
                } catch (Exception e) {
                    Slog.e(TAG, "performHeParam failed", e);
                }
            }
            return true;
        } else if (effect instanceof RichTapVibrationEffect.HapticParameter) {
            final RichTapVibrationEffect.HapticParameter parameter =
                    (RichTapVibrationEffect.HapticParameter) effect;
            setHapticParam(parameter.getParam(), parameter.getLength());
            return true;
        }
        return false;
    }

    /** Plays an HE (or HE2) pattern on the RichTap HAL. */
    public void richTapVibratorOnPatternHe(VibrationEffect effect) {
        final RichTapVibrationEffect.PatternHe patternHe = (RichTapVibrationEffect.PatternHe) effect;
        if (DEBUG) {
            Slog.d(TAG, "performHe looper=" + patternHe.getLooper()
                    + " interval=" + patternHe.getInterval()
                    + " amplitude=" + patternHe.getAmplitude()
                    + " freq=" + patternHe.getFreq());
        }
        final IRichtapVibrator service = getRichtapService();
        if (service == null) {
            return;
        }
        try {
            service.performHe(patternHe.getLooper(), patternHe.getInterval(),
                    patternHe.getAmplitude(), patternHe.getFreq(),
                    patternHe.getPatternInfo(), mCallback);
        } catch (Exception e) {
            Slog.e(TAG, "performHe failed", e);
        }
    }

    /** Plays a RichTap envelope effect. */
    public void richTapVibratorOnEnvelope(int[] relativeTime, int[] scaleArr, int[] freqArr,
            boolean steepMode, int amplitude) {
        final int[] params = new int[relativeTime.length * 3];
        for (int i = 0; i < relativeTime.length; i++) {
            params[i * 3] = relativeTime[i];
            params[i * 3 + 1] = scaleArr[i];
            params[i * 3 + 2] = freqArr[i];
        }
        richTapVibratorSetAmplitude(amplitude);
        final IRichtapVibrator service = getRichtapService();
        if (service == null) {
            return;
        }
        try {
            service.performEnvelope(params, steepMode, mCallback);
        } catch (Exception e) {
            Slog.e(TAG, "performEnvelope failed", e);
        }
    }

    /** Sets the DRC vibration mode (0..3) and stops any ongoing RichTap effect first. */
    public void richTapSetVibrationMode(int mode) {
        if (DEBUG) {
            Slog.i(TAG, "richTapSetVibrationMode mode=" + mode);
        }
        richTapVibratorStop();
        final int[] param = new int[] {
                HapticParamType.HAPTIC_DRC.getValue(),
                mode
        };
        setHapticParam(param, param.length);
    }

    public void richTapVibratorSetAmplitude(int amplitude) {
        final IRichtapVibrator service = getRichtapService();
        if (service == null) {
            return;
        }
        try {
            service.setAmplitude(amplitude, mCallback);
        } catch (Exception e) {
            Slog.e(TAG, "setAmplitude failed", e);
        }
    }

    public int richTapVibratorPerform(int id, byte scale) {
        final IRichtapVibrator service = getRichtapService();
        if (service == null) {
            return 0;
        }
        try {
            return service.perform(id, scale, mCallback);
        } catch (Exception e) {
            Slog.e(TAG, "perform failed", e);
            return 0;
        }
    }

    public void richTapVibratorStop() {
        final IRichtapVibrator service = getRichtapService();
        if (service == null) {
            return;
        }
        try {
            service.stop(mCallback);
        } catch (Exception e) {
            Slog.e(TAG, "stop failed", e);
        }
    }

    private void setHapticParam(int[] data, int length) {
        final IRichtapVibrator service = getRichtapService();
        if (service == null) {
            return;
        }
        try {
            service.setHapticParam(data, length, mCallback);
        } catch (Exception e) {
            Slog.e(TAG, "setHapticParam failed", e);
        }
    }

    /** Whether the given effect is a RichTap-extended effect that this service should handle. */
    public boolean checkIfRichTapEffect(VibrationEffect effect, String reason) {
        if (reason != null && reason.equals(HapticPlayer.VIBRATE_REASON)) {
            return false;
        }
        return effect instanceof RichTapVibrationEffect.PatternHeParameter
                || effect instanceof RichTapVibrationEffect.PatternHe
                || effect instanceof RichTapVibrationEffect.Envelope
                || effect instanceof RichTapVibrationEffect.HapticParameter
                || effect instanceof RichTapVibrationEffect.ExtPrebaked;
    }

    void resetHalServiceProxy() {
        synchronized (RichTapVibratorService.class) {
            sRichtapVibratorService = null;
            mRichtapUnavailable = false;
        }
    }

    private static final class VibHalDeathRecipient implements IBinder.DeathRecipient {
        private final RichTapVibratorService mRichTapService;

        VibHalDeathRecipient(RichTapVibratorService richtapService) {
            mRichTapService = richtapService;
        }

        public void binderDied() {
            Slog.d(TAG, "Vibrator HAL died, resetting RichTap proxy");
            synchronized (VibHalDeathRecipient.class) {
                if (mRichTapService != null) {
                    mRichTapService.resetHalServiceProxy();
                }
            }
        }
    }
}
