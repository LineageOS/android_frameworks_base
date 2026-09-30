/*
 * Copyright (C) 2021 The Android AAC vibraiton extension
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

import android.text.TextUtils;
import android.util.Log;

import android.annotation.NonNull;
import android.annotation.Nullable;

/**
 * A DynamicEffect describes a haptic effect to tencent he perform {@link Vibrator}.
 *
 * 
 *
 */
/**
 * @hide
 */
public final class DynamicEffect extends VibrationEffect implements Parcelable {
    private static final String TAG = "DynamicEffect";

    public static final boolean DEBUG = false;

	String mPatternJson;
    /** @hide */
    public DynamicEffect(@NonNull Parcel in) {
    }
    
    public DynamicEffect(@NonNull String patternJson) {
        mPatternJson = new String(patternJson);
    }
     
    public @Nullable
    static DynamicEffect create(@Nullable String json) {
        //Log.d(TAG,"create json effect, effect=" + json);
        if(TextUtils.isEmpty(json)){
            Log.e(TAG, "empty pattern,do nothing");
            return null;
        }

        DynamicEffect ret = new DynamicEffect(json);
        return ret;
    }
    
    @Override
    public int describeContents() {
        return 0;
    }
    /**
     * Resolve default values into integer amplitude numbers.
     *
     * @param defaultAmplitude the default amplitude to apply, must be between 0 and
     *                         MAX_AMPLITUDE
     * @return this if amplitude value is already set, or a copy of this effect with given default
     *         amplitude otherwise
     *
     * @hide
     */
    public DynamicEffect resolve(int defaultAmplitude) {
        return this;
    }

    /**
     * Scale the vibration effect intensity with the given constraints.
     *
     * @param scaleFactor scale factor to be applied to the intensity. Values within [0,1) will
     *                    scale down the intensity, values larger than 1 will scale up
     * @return this if there is no scaling to be done, or a copy of this effect with scaled
     *         vibration intensity otherwise
     *
     * @hide
     */
    public DynamicEffect scale(float scaleFactor) {
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
    public String toDebugString() { return toString(); }

    public @NonNull String getPatternInfo(){
        return mPatternJson;
    }

    @Override
    public void validate() {
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof String)) {
            return false;
        }
        String other = (String) o;
        return (mPatternJson == other);
    }

    @Override
    public int hashCode() {
        int result = 17;
        if(mPatternJson != null) {
            result += 37 * (int) mPatternJson.hashCode();
        }
        return result;
    }

    @Override
    public long getDuration() {
        return 0;
    }
    @Override
    public String toString() {
        return "DynamicEffect{mPatternJson=" +  mPatternJson+"}";
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {

    }


    public static final @NonNull Parcelable.Creator<DynamicEffect> CREATOR =
        new Parcelable.Creator<DynamicEffect>() {
            @Override
            public DynamicEffect createFromParcel(@NonNull Parcel in) {
                // Skip the type token
                in.readInt();
                return new DynamicEffect(in);
            }
            @Override
            public @NonNull DynamicEffect[] newArray(int size) {
                return new DynamicEffect[size];
            }
        };

}
