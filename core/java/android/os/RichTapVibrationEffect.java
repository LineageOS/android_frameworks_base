/*
 * Copyright (C) 2017 The Android AAC vibraiton extension
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

package android.os;

import android.annotation.NonNull;
import android.app.ActivityThread;
import android.content.Context;
import android.content.res.Resources;
import java.util.Arrays;
import java.util.Objects;
import android.util.Log;
import android.os.VibrationEffect;

/**
 * A RichTapVibrationEffect describes a haptic effect to be performed by a {@link Vibrator}.
 *
 * These effects may be any number of things, from single shot vibrations to complex waveforms, and to AAC extended effects.
 *
 * @hide
 */
public class RichTapVibrationEffect {
    private static final String TAG = "RichTapVibrationEffect";

    private static final int PARCEL_TOKEN_EXT_PREBAKED = 501;
    private static final int PARCEL_TOKEN_ENVELOPE = 502;
    private static final int PARCEL_TOKEN_PATTERN_HE = 503;
    private static final int PARCEL_TOKEN_PATTERN_HE_LOOP_PARAMETER = 504;
    private static final int PARCEL_TOKEN_HAPTIC_PARAMETER = 505;
	
    private static final int OPPO_CLIENT = 0x0001 << 16;
    private static final int ONEPLUS_CLIENT = 0x0002 << 16;
    private static final int MI_CLIENT = 0x0003 << 16;
    private static final int VIVO_CLIENT = 0x0004 << 16;
    private static final int HONOR_CLIENT = 0x0005 << 16;
    private static final int LENOVO_CLIENT = 0x0006 << 16;
    private static final int ZTE_CLIENT = 0x0007 << 16;
    private static final int AAC_CLIENT = 0x00FF << 16;
    private static final int MAJOR_RICHTAP_VERSION = 0x0020 << 8;
    private static final int MINOR_RICHTAP_VERSION = 0x0010 << 0;

    private static final int VIBRATION_EFFECT_SUPPORT_UNKNOWN = 0;
    private static final int VIBRATION_EFFECT_SUPPORT_YES = 1;
    private static final int VIBRATION_EFFECT_SUPPORT_NO = 2;
	private static final int EFFECT_ID_START = 0x1000;

    private RichTapVibrationEffect() {
        // no called
    }

    @NonNull
    public static VibrationEffect createExtPreBaked(@NonNull int effectId, int strength){
        VibrationEffect effect = new ExtPrebaked(EFFECT_ID_START+effectId, strength);
        effect.validate();
        return effect;
    }


    @NonNull
    public static VibrationEffect createEnvelope(@NonNull int[] relativeTimeArr, @NonNull int[] scaleArr, @NonNull int[] freqArr, boolean steepMode, int amplitude){
        VibrationEffect effect = new Envelope(relativeTimeArr, scaleArr, freqArr, steepMode, amplitude);
        effect.validate();
        return effect;
    }

   
    @NonNull
    public static VibrationEffect createPatternHeParameter(int interval,int amplitude,int freq){
        VibrationEffect effect = new PatternHeParameter(interval,amplitude,freq);
        effect.validate();
        return effect;
    }

    @NonNull
    public static VibrationEffect createHapticParameter(@NonNull int[] param, int length) {
        VibrationEffect effect = new HapticParameter(param, length);
        effect.validate();
        return effect;
    }

    @NonNull
    public static VibrationEffect createPatternHeWithParam(@NonNull int[] patternInfo, int looper, int interval,int amplitude,int freq) {
        VibrationEffect effect = new PatternHe(patternInfo, looper, interval,amplitude,freq);
        effect.validate();
        return effect;
    }

    /*
     * Whether this device integrates RichTap support, as declared by the
     * {@code com.android.internal.R.bool.config_hasRichTapVibrator} framework config (default
     * false, overridden by device overlays that ship a RichTap-capable vibrator HAL).
     *
     * Client SDKs reflectively call {@link #checkIfRichTapSupport()} and treat the returned
     * {@link #VIBRATION_EFFECT_SUPPORT_NO} value as "device does not integrate RichTap", falling
     * back to standard vibration. Returning unsupported here is therefore what keeps client apps
     * from sending RichTap effects to devices without a RichTap HAL.
     *
     * NEED MODIFY ON CUSTOM BRANCH
     */
    public static int checkIfRichTapSupport(){
        if (!hasRichTapSupport()) {
            return VIBRATION_EFFECT_SUPPORT_NO;
        }
        return (AAC_CLIENT | MAJOR_RICHTAP_VERSION | MINOR_RICHTAP_VERSION);//richtap version:1.6.1.0   client:aac
    }

    private static boolean hasRichTapSupport() {
        try {
            ActivityThread activityThread = ActivityThread.currentActivityThread();
            if (activityThread == null) {
                return false;
            }
            Context context = activityThread.getSystemContext();
            if (context == null) {
                return false;
            }
            Resources resources = context.getResources();
            return resources != null
                    && resources.getBoolean(
                            com.android.internal.R.bool.config_hasRichTapVibrator);
        } catch (Throwable t) {
            // No framework resources available (e.g. early boot): report unsupported.
            return false;
        }
    }

    /** @hide */
    public static final class ExtPrebaked extends VibrationEffect implements Parcelable {
        private int mEffectId;
        private int mStrength;

        /** @hide */
        public ExtPrebaked(@NonNull Parcel in) {
            this(in.readInt(), in.readInt());
        }

        public ExtPrebaked(int effectId, int strength) {
            mEffectId = effectId;
            mStrength = strength;
        }

        public int getId() {
            return mEffectId;
        }

        public int getScale() {
            return mStrength;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public ExtPrebaked resolve(int defaultAmplitude) {
            return this;
        }

        public ExtPrebaked scale(float scaleFactor) {
            return this;
        }

        @Override
        public VibrationEffect applyEffectStrength(int effectStrength) {
            return this;
        }

        @Override
        public long[] computeCreateWaveformOffOnTimingsOrNull() { return null; }

        @Override
        public VibrationEffect cropToLengthOrNull(int length) { return this; }

        @Override
        public boolean areVibrationFeaturesSupported(@NonNull VibratorInfo vibratorInfo) { return true; }

        @Override
        public VibrationEffect scaleLinearly(float scaleFactor) { return this; }

        @Override
        public VibrationEffect applyAdaptiveScale(float scaleFactor) { return this; }

        @Override
        public VibrationEffect applyRepeatingIndefinitely(boolean wantRepeating, int loopDelayMs) { return this; }

        @NonNull
        @Override
        public String toDebugString() {
            return "ExtPrebaked{effectId=" + mEffectId + ", strength=" + mStrength + "}";
        }

        // unuse ?
        @Override
        public long getDuration(){
            return -1;
        }

        @Override
        public void validate() {
            if (mEffectId < 0) {
                throw new IllegalArgumentException(
                        "Unknown ExtPrebaked effect type (value=" + mEffectId + ")");
            }

            if (mStrength < 1 || mStrength > 100) {
                throw new IllegalArgumentException(
                        "mStrength must be between 1 and 100 inclusive (mStrength=" + mStrength + ")");
            }
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof RichTapVibrationEffect.ExtPrebaked)) {
                return false;
            }
            RichTapVibrationEffect.ExtPrebaked other = (RichTapVibrationEffect.ExtPrebaked) o;
            return mEffectId == other.mEffectId;
        }

        @Override
        public int hashCode() {
            return mEffectId;
        }

        @Override
        public String toString() {
            return "ExtPrebaked{mEffectId=" + mEffectId + "mStrength = " + mStrength +"}";
        }

        @Override
        public void writeToParcel(@NonNull Parcel out, int flags) {
            out.writeInt(PARCEL_TOKEN_EXT_PREBAKED);
            out.writeInt(mEffectId);
            out.writeInt(mStrength);
        }

        public static final @NonNull Parcelable.Creator<ExtPrebaked> CREATOR =
                new Parcelable.Creator<ExtPrebaked>() {
                    @Override
                    public ExtPrebaked createFromParcel(@NonNull Parcel in) {
                        // Skip the type token
                        in.readInt();
                        return new ExtPrebaked(in);
                    }
                    @Override
                    public ExtPrebaked[] newArray(int size) {
                        return new ExtPrebaked[size];
                    }
                };
    }
    /** @hide */
    public static final class Envelope extends VibrationEffect implements Parcelable {
        private int[] relativeTimeArr;
        private int[] scaleArr; // *100
        private int[] freqArr; // freq
        private boolean steepMode;
        private int amplitude;

        /** @hide */
        public Envelope(@NonNull Parcel in) {
            this(in.createIntArray(), in.createIntArray(), in.createIntArray(), in.readInt() == 1, in.readInt());
        }

        public Envelope(@NonNull int[] relativeTimeArr, @NonNull int[] scaleArr, @NonNull int[] freqArr, boolean steepMode, int amplitude) {
            this.relativeTimeArr = Arrays.copyOf(relativeTimeArr, 4);
            this.scaleArr = Arrays.copyOf(scaleArr, 4);
            this.freqArr = Arrays.copyOf(freqArr, 4);
            this.steepMode = steepMode;
            this.amplitude = amplitude;
        }

        public @NonNull int[] getRelativeTimeArr(){
            return this.relativeTimeArr;
        }

        public @NonNull int[] getScaleArr(){
            return this.scaleArr;
        }

        public @NonNull int[] getFreqArr(){
            return this.freqArr;
        }

        public boolean isSteepMode(){
            return this.steepMode;
        }

        public int getAmplitude(){
            return this.amplitude;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public Envelope resolve(int defaultAmplitude) {
            return this;
        }

        public Envelope scale(float scaleFactor) {
            return this;
        }

        @Override
        public VibrationEffect applyEffectStrength(int effectStrength) {
            return this;
        }

        @Override
        public long[] computeCreateWaveformOffOnTimingsOrNull() { return null; }

        @Override
        public VibrationEffect cropToLengthOrNull(int length) { return this; }

        @Override
        public boolean areVibrationFeaturesSupported(@NonNull VibratorInfo vibratorInfo) { return true; }

        @Override
        public VibrationEffect scaleLinearly(float scaleFactor) { return this; }

        @Override
        public VibrationEffect applyAdaptiveScale(float scaleFactor) { return this; }

        @Override
        public VibrationEffect applyRepeatingIndefinitely(boolean wantRepeating, int loopDelayMs) { return this; }

        @NonNull
        @Override
        public String toDebugString() {
            return "Envelope{}";
        }

        // unuse ?
        @Override
        public long getDuration(){
            return -1;
        }

        @Override
        public void validate() {
            for (int i=0; i < 4; i++){
                if (relativeTimeArr[i] < 0){
                    throw new IllegalArgumentException("relative time can not be negative");
                }

                if (scaleArr[i] < 0) {
                    throw new IllegalArgumentException("scale can not be negative");
                }

                if (freqArr[i] < 0){
                    throw new IllegalArgumentException("freq must be positive");
                }
            }

            if (amplitude < -1 || amplitude == 0 || amplitude > 255) {
                throw new IllegalArgumentException(
                        "amplitude must either be DEFAULT_AMPLITUDE, " +
                                "or between 1 and 255 inclusive (amplitude=" + amplitude + ")");
            }
        }

        @Override
        public boolean equals(Object o) {
            //
            if (!(o instanceof RichTapVibrationEffect.Envelope)) {
                return false;
            }
            RichTapVibrationEffect.Envelope other = (RichTapVibrationEffect.Envelope) o;
            int timeArr[] = other.getRelativeTimeArr();
            int scArr[] = other.getScaleArr();
            int frArr[] = other.getFreqArr();
            if (this.amplitude != other.getAmplitude()) return false;
            if (!Arrays.equals(timeArr, this.relativeTimeArr)) return false;
            if (!Arrays.equals(scArr, this.scaleArr)) return false;
            if (!Arrays.equals(frArr, this.freqArr)) return false;
            if (other.isSteepMode() != this.steepMode) return false;
            return true;
        }

        @Override
        public int hashCode() {
            return relativeTimeArr[2] + scaleArr[2] + freqArr[2];
        }

        @Override
        public String toString() {
            return "Envelope: {relativeTimeArr=" + relativeTimeArr +  ", scaleArr = " + scaleArr
                    + ", freqArr = " + freqArr + ", SteepMode = " + this.steepMode + ", Amplitude = " + this.amplitude + "}";
        }


        @Override
        public void writeToParcel(@NonNull Parcel out, int flags) {
            out.writeInt(PARCEL_TOKEN_ENVELOPE);
            out.writeIntArray(this.relativeTimeArr);
            out.writeIntArray(this.scaleArr);
            out.writeIntArray(this.freqArr);
            out.writeInt(this.steepMode ? 1 : 0);
            out.writeInt(this.amplitude);
        }

        public static final @NonNull Parcelable.Creator<Envelope> CREATOR =
                new Parcelable.Creator<Envelope>() {
                    @Override
                    public Envelope createFromParcel(@NonNull Parcel in) {
                        // Skip the type token
                        in.readInt();
                        return new Envelope(in);
                    }
                    @Override
                    public @NonNull Envelope[] newArray(int size) {
                        return new Envelope[size];
                    }
                };
    }
    /** @hide */
    public static final class PatternHeParameter extends VibrationEffect implements Parcelable{
        private final String TAG = "PatternHeParameter";
        private int mInterval;
        private int mAmplitude;
        private int mFreq;
        /** @hide */
        public PatternHeParameter(@NonNull Parcel in) {
            mInterval = in.readInt();
            mAmplitude = in.readInt();
            mFreq = in.readInt();
            Log.d(TAG, "parcel mInterval:"+mInterval+" mAmplitude:"+mAmplitude+" mFreq:"+mFreq);
        }

        public PatternHeParameter(int interval,int amplitude,int freq){
            mInterval = interval;
            mAmplitude = amplitude;
            mFreq = freq;
            Log.d(TAG, "mInterval:"+mInterval+" mAmplitude:"+mAmplitude+" mFreq:"+mFreq);
        }

        public int getInterval(){
            return mInterval;
        }

        public int getAmplitude(){
            return mAmplitude;
        }

        public int getFreq(){
            return mFreq;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public PatternHeParameter resolve(int defaultAmplitude) {
            return this;
        }

        public PatternHeParameter scale(float scaleFactor) {
            return this;
        }

        @Override
        public VibrationEffect applyEffectStrength(int effectStrength) {
            return this;
        }

        @Override
        public long[] computeCreateWaveformOffOnTimingsOrNull() { return null; }

        @Override
        public VibrationEffect cropToLengthOrNull(int length) { return this; }

        @Override
        public boolean areVibrationFeaturesSupported(@NonNull VibratorInfo vibratorInfo) { return true; }

        @Override
        public VibrationEffect scaleLinearly(float scaleFactor) { return this; }

        @Override
        public VibrationEffect applyAdaptiveScale(float scaleFactor) { return this; }

        @Override
        public VibrationEffect applyRepeatingIndefinitely(boolean wantRepeating, int loopDelayMs) { return this; }

        @NonNull
        @Override
        public String toDebugString() {
            return "PatternHeParameter{interval=" + mInterval + ", amplitude=" + mAmplitude + ", freq=" + mFreq + "}";
        }

        // unuse ?
        @Override
        public long getDuration(){
            return -1;
        }

        @Override
        public void validate() {
            if (mAmplitude < -1 || mAmplitude > 255 || mInterval < -1 || mFreq  < -1) {
                throw new IllegalArgumentException(
                        "mAmplitude=" + mAmplitude + " mInterval=" + mInterval+" mFreq="+mFreq );
            }
        }

        @Override
        public boolean equals(Object o) {
            //
            if (!(o instanceof RichTapVibrationEffect.PatternHeParameter)) {
                return false;
            }
            RichTapVibrationEffect.PatternHeParameter other = (RichTapVibrationEffect.PatternHeParameter) o;
            int interval = other.getInterval();
            int amplitude = other.getAmplitude();
            int freq = other.getFreq();
            if (interval != this.mInterval
            || amplitude != this.mAmplitude || freq != this.mFreq){
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            int result = 14;
            result += 37 * mInterval;
            result += 37 * mAmplitude;
            return result;
        }

        @Override
        public String toString() {
            return "PatternHeParameter: {mAmplitude=" + this.mAmplitude + "}"+ "{mInterval=" + this.mInterval + "}";
        }


        @Override
        public void writeToParcel(@NonNull Parcel out, int flags) {
            out.writeInt(PARCEL_TOKEN_PATTERN_HE_LOOP_PARAMETER);
            out.writeInt(mInterval);
            out.writeInt(mAmplitude);
            out.writeInt(mFreq);
            Log.d(TAG, "writeToParcel"+" mInterval:"+mInterval+" mAmplitude:"+mAmplitude+" mFreq:"+mFreq);
        }

        public static final @NonNull Parcelable.Creator<PatternHeParameter> CREATOR =
                new Parcelable.Creator<PatternHeParameter>() {
                    @Override
                    public PatternHeParameter createFromParcel(@NonNull Parcel in) {
                        // Skip the type token
                        in.readInt();
                        return new PatternHeParameter(in);
                    }
                    @Override
                    public @NonNull PatternHeParameter[] newArray(int size) {
                        return new PatternHeParameter[size];
                    }
                };

    }

    /** @hide */
    public static final class HapticParameter extends VibrationEffect implements Parcelable{
        private final String TAG = "HapticParameter";
        private int[] mParam;
        private int mLength;

        /** @hide */
        public HapticParameter(@NonNull Parcel in) {
            mParam = in.createIntArray();
            mLength = in.readInt();
            Log.d(TAG, "parcel mLength:" + mLength);
        }

        public HapticParameter(int[] param, int length){
            mParam = param;
            mLength = length;
            Log.d(TAG, "parcel mLength:" + mLength);
        }

        public int[] getParam(){
            return mParam;
        }

        public int getLength(){
            return mLength;
        }

        public HapticParameter resolve(int defaultAmplitude) {
            return this;
        }

        public HapticParameter scale(float scaleFactor) {
            return this;
        }

        @Override
        public VibrationEffect applyEffectStrength(int effectStrength) {
            return this;
        }

        @Override
        public long[] computeCreateWaveformOffOnTimingsOrNull() { return null; }

        @Override
        public VibrationEffect cropToLengthOrNull(int length) { return this; }

        @Override
        public boolean areVibrationFeaturesSupported(@NonNull VibratorInfo vibratorInfo) { return true; }

        @Override
        public VibrationEffect scaleLinearly(float scaleFactor) { return this; }

        @Override
        public VibrationEffect applyAdaptiveScale(float scaleFactor) { return this; }

        @Override
        public VibrationEffect applyRepeatingIndefinitely(boolean wantRepeating, int loopDelayMs) { return this; }

        @NonNull
        @Override
        public String toDebugString() {
            return "HapticParameter{length=" + mLength + "}";
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public long getDuration(){
            return -1;
        }

        @Override
        public void validate() {
            if (null == mParam || 0 == mParam.length || mLength != mParam.length) {
                throw new IllegalArgumentException("empty param");
            }
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof RichTapVibrationEffect.HapticParameter)) {
                return false;
            }
            if (this.getLength() != ((RichTapVibrationEffect.HapticParameter)o).getLength()) {
                return false;
            }
            if (!Arrays.equals(this.getParam(), ((RichTapVibrationEffect.HapticParameter)o).getParam())) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mParam, mLength);
        }

        @Override
        public String toString() {
            return "HapticParameter: {mLength =" + this.mLength + ",mParam:" + Arrays.toString(mParam) + "}";
        }


        @Override
        public void writeToParcel(@NonNull Parcel out, int flags) {
            out.writeInt(PARCEL_TOKEN_HAPTIC_PARAMETER);
            out.writeIntArray(mParam);
            out.writeInt(mLength);
            Log.d(TAG, "writeToParcel, mLength:" + mLength);
        }

        public static final @NonNull Parcelable.Creator<HapticParameter> CREATOR =
                new Parcelable.Creator<HapticParameter>() {
                    @Override
                    public HapticParameter createFromParcel(@NonNull Parcel in) {
                        // Skip the type token
                        in.readInt();
                        return new HapticParameter(in);
                    }
                    @Override
                    public @NonNull HapticParameter[] newArray(int size) {
                        return new HapticParameter[size];
                    }
                };

    }


    /** @hide */
    public static final class PatternHe extends VibrationEffect implements Parcelable {
        private int[] mPatternInfo;
        private int mLooper;
        private int mInterval;
        private int mAmplitude;
        private int mFreq;
        private long mDuration = 100 ;
        private int mEventCount;
        /** @hide */
        public PatternHe(@NonNull Parcel in) {
            mPatternInfo = in.createIntArray();
            mLooper = in.readInt();
            mInterval = in.readInt();
            mAmplitude = in.readInt();
            mFreq = in.readInt();
        }

        public PatternHe(@NonNull int[] patternInfo, long duration, int eventCount) {
            mPatternInfo = patternInfo;
            mDuration = duration;
            mEventCount = eventCount;
        }

        public PatternHe(@NonNull int[] patternInfo, int looper,int interval,int amplitude,int freq) {
            mPatternInfo = patternInfo;
            mLooper = looper;
            mInterval = interval;
            mFreq = freq;
            mAmplitude = amplitude;
            mDuration = 100;
            mEventCount = 0;
        }

        @Override
        public long getDuration() {
            return mDuration;
        }

        public int getEventCount(){
            return mEventCount;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public PatternHe resolve(int defaultAmplitude) {
            return this;
        }

        public PatternHe scale(float scaleFactor) {
            return this;
        }

        @Override
        public VibrationEffect applyEffectStrength(int effectStrength) {
            return this;
        }

        @Override
        public long[] computeCreateWaveformOffOnTimingsOrNull() { return null; }

        @Override
        public VibrationEffect cropToLengthOrNull(int length) { return this; }

        @Override
        public boolean areVibrationFeaturesSupported(@NonNull VibratorInfo vibratorInfo) { return true; }

        @Override
        public VibrationEffect scaleLinearly(float scaleFactor) { return this; }

        @Override
        public VibrationEffect applyAdaptiveScale(float scaleFactor) { return this; }

        @Override
        public VibrationEffect applyRepeatingIndefinitely(boolean wantRepeating, int loopDelayMs) { return this; }

        @NonNull
        @Override
        public String toDebugString() {
            return "PatternHe{events=" + mEventCount + "}";
        }

        public @NonNull int[] getPatternInfo(){
            return mPatternInfo;
        }

        public int getLooper(){
            return mLooper;
        }

        public int getInterval(){
            return mInterval;
        }

        public int getAmplitude(){
            return mAmplitude;
        }

        public int getFreq(){
            return mFreq;
        }
        @Override
        public void validate() {

            if (mDuration <= 0) {
                throw new IllegalArgumentException(
                        "duration must be positive (duration=" + mDuration + ")");
            }
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof RichTapVibrationEffect.PatternHe)) {
                return false;
            }
            RichTapVibrationEffect.PatternHe other = (RichTapVibrationEffect.PatternHe) o;
            return other.mDuration == mDuration && other.mPatternInfo == mPatternInfo;
        }

        @Override
        public int hashCode() {
            int result = 17;
            result += 37 * (int) mDuration;
            result += 37 * mEventCount;
            return result;
        }

        @Override
        public String toString() {
            return "PatternHe{mLooper=" + mLooper + ", mInterval=" + mInterval + "}";
        }

        @Override
        public void writeToParcel(@NonNull Parcel out, int flags) {
            out.writeInt(PARCEL_TOKEN_PATTERN_HE);
            out.writeIntArray(this.mPatternInfo);
            out.writeInt(mLooper);
            out.writeInt(mInterval);
            out.writeInt(mAmplitude);
            out.writeInt(mFreq);
        }

        public static final @NonNull Parcelable.Creator<PatternHe> CREATOR =
            new Parcelable.Creator<PatternHe>() {
                @Override
                public PatternHe createFromParcel(@NonNull Parcel in) {
                    // Skip the type token
                    in.readInt();
                    return new PatternHe(in);
                }
                @Override
                public @NonNull PatternHe[] newArray(int size) {
                    return new PatternHe[size];
                }
            };
    }

    /** @hide */
    public static final boolean isExtendedEffect(int token) {
        switch (token) {
            case PARCEL_TOKEN_EXT_PREBAKED:
            case PARCEL_TOKEN_ENVELOPE:
            case PARCEL_TOKEN_PATTERN_HE_LOOP_PARAMETER:
            case PARCEL_TOKEN_PATTERN_HE:
            case PARCEL_TOKEN_HAPTIC_PARAMETER:
                return true;
            default:
                return false;
        }
    }

    @NonNull
    public static final VibrationEffect createExtendedEffect(@NonNull Parcel in) {
        int offset = in.dataPosition() - Integer.BYTES;
        in.setDataPosition(offset);
        return RichTapVibrationEffect.CREATOR.createFromParcel(in);
    }

    public static final @NonNull Parcelable.Creator<VibrationEffect> CREATOR =
            new Parcelable.Creator<VibrationEffect>() {

                @Override
                public VibrationEffect createFromParcel(Parcel in) {
                    int token = in.readInt();
                    Log.d(TAG, "read token: " + token + "!");

                    switch (token) {
                        case PARCEL_TOKEN_EXT_PREBAKED:
                            return new ExtPrebaked(in);
                        case PARCEL_TOKEN_ENVELOPE:
                            return new Envelope(in);
                        case PARCEL_TOKEN_PATTERN_HE_LOOP_PARAMETER:
                            return new PatternHeParameter(in);
                        case PARCEL_TOKEN_PATTERN_HE:
                            return new PatternHe(in);
                        case PARCEL_TOKEN_HAPTIC_PARAMETER:
                            return new HapticParameter(in);
                        default:
                            throw new IllegalStateException(
                                "Unexpected vibration event type token in parcel.");
                    }
                }

                @Override
                public VibrationEffect[] newArray(int size) {
                    return new VibrationEffect[size];
                }
            };
}
