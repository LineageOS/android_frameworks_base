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

package android.os;

import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.text.TextUtils;
import android.util.Log;
import android.annotation.NonNull;
import android.app.ActivityThread;
import android.content.Context;
import android.os.Binder;
import android.os.VibratorManager;
import android.os.Process;
import android.os.DynamicEffect;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONArray;
import org.json.JSONObject;

/** @hide */
@SuppressLint("NotCloseable")
public class HapticPlayer {
    private static final String TAG = "HapticPlayer";

    private boolean mStarted;
    private DynamicEffect mEffect;
    private final VibratorManager mVibratorManager;
    private final String mPackageName;
    private final Binder mToken;
    private static boolean mAvailable = isSupportRichtap();
    private static ExecutorService mExcutor = Executors.newSingleThreadExecutor();
    private static AtomicInteger mSeq = new AtomicInteger();

 
    public static final int FORMAT_VERSION = 2;
    public static final String VIBRATE_REASON = "DynamicEffect";

    public static final String PATTERN_KEY_PATTERN = "Pattern";
    public static final String EVENT_TYPE_HE_CONTINUOUS_NAME = "continuous";
    public static final String EVENT_TYPE_HE_TRANSIENT_NAME = "transient";
    public static final String EVENT_KEY_EVENT = "Event";
    public static final String EVENT_KEY_RELATIVE_TIME = "RelativeTime";
    public static final String EVENT_KEY_DURATION = "Duration";
    public static final String EVENT_KEY_HE_TYPE = "Type";
    public static final String EVENT_KEY_HE_PARAMETERS = "Parameters";
    public static final String EVENT_KEY_HE_INTENSITY = "Intensity";
    public static final String EVENT_KEY_HE_FREQUENCY = "Frequency";
    public static final String EVENT_KEY_HE_CURVE = "Curve";
    public static final String EVENT_KEY_HE_CURVE_POINT_TIME = "Time";

    public static final int CONTINUOUS_EVENT = 0x1000;
    public static final int TRANSIENT_EVENT = CONTINUOUS_EVENT + 1;

    public static final int HE_DEFAULT_RELATIVE_TIME = 400; // default relativeTime 400ms
    public static final int HE_DEFAULT_DURATION = 0; // default duration for every event 15ms
    public static final int HE_TYPE = 0;
    public static final int HE_RELATIVE_TIME = 1;
    public static final int HE_INTENSITY = 2;
    public static final int HE_FREQUENCY = 3;
    public static final int HE_DURATION = 4;
    public static final int HE_VIB_INDEX = 5;
    public static final int HE_POINT_COUNT = 6;
    public static final int HE_CURVE_POINT_0_TIME = 7;
    public static final int HE_CURVE_POINT_0_INTENSITY = 8;
    public static final int HE_CURVE_POINT_0_FREQUENCY = 9;
    public static final int HE_VALUE_LENGTH = 7 + 3 * 16;


    private  static final int MAX_PATERN_EVENT_LAST_TIME = 5000;
    private  static final int MAX_PATERN_LAST_TIME = 50000;
    private  static final int MAX_INTENSITY = 100;
    private  static final int MAX_FREQ = 100;

    private static final int MAX_EVENT_COUNT = 16;
    private static final int MAX_POINT_COUNT = 16;

    private static final String PATTERN_KEY_PATTERN_LIST = "PatternList";

    private static final String HE_META_DATA_KEY = "Metadata";
    private static final String HE_VERSION_KEY = "Version";

    public static final String PATTERN_KEY_PATTERN_ABS_TIME = "AbsoluteTime";
    public static final String PATTERN_KEY_EVENT_VIB_ID = "Index";

    private static final int VIBRATION_EFFECT_SUPPORT_NO = 2;
    public static final int ANDROID_VERSIONCODE_O = 26;
    public static final int HE2_0_PATTERN_WRAP_NUM = 10;

    private final boolean DEBUG = false;

    private HapticPlayer() {
        this.mToken = new Binder();
        this.mStarted = false;
        this.mPackageName = ActivityThread.currentPackageName();
        Context ctx = ActivityThread.currentActivityThread().getSystemContext();
        this.mVibratorManager = ctx.getSystemService(VibratorManager.class);;
    }

    public HapticPlayer(@NonNull final DynamicEffect effect) {
        this();
        this.mEffect = effect;
    }

    /*
     * 判断给定数据data，是否在(a,b)之间
     * 返回true表示在区间内，返回false表示不在区间内
     */
    private boolean isInTheInterval(int data, int a, int b){
        return data >= a && data <= b;
    }
    private static boolean isSupportRichtap() {
        if (Build.VERSION.SDK_INT < ANDROID_VERSIONCODE_O){
            return false;
        }

        int support = RichTapVibrationEffect.checkIfRichTapSupport();
        if(support == VIBRATION_EFFECT_SUPPORT_NO){
            return false;
        }else{
            return true;
        }
    }

    public static boolean isAvailable() {
        return mAvailable;
    }

    public static int getMajorVersion() {
        if (Build.VERSION.SDK_INT < ANDROID_VERSIONCODE_O){
            return 0x00;
        }
        int support = RichTapVibrationEffect.checkIfRichTapSupport();
        if(support == VIBRATION_EFFECT_SUPPORT_NO){
            return 0x00;
        }else{
            int clientCode = (support & (0x00FF << 16))>>16;
            int majorVersion = (support & (0x00FF << 8))>>8;
            int minorVersion = (support & (0x00FF << 0))>>0;

            Log.d(TAG, "clientCode:"+clientCode+" majorVersion:"+
                    majorVersion+" minorVersion:"+minorVersion);

            return majorVersion;
        }
    }

    public static int getMinorVersion() {
        if (Build.VERSION.SDK_INT < ANDROID_VERSIONCODE_O){
            return 0x00;
        }
        int support = RichTapVibrationEffect.checkIfRichTapSupport();
        if(support == VIBRATION_EFFECT_SUPPORT_NO){
            return 0x00;
        }else{
            int clientCode = (support & (0x00FF << 16))>>16;
            int majorVersion = (support & (0x00FF << 8))>>8;
            int minorVersion = (support & (0x00FF << 0))>>0;

            Log.d(TAG, "clientCode:"+clientCode+" majorVersion:"+
                    majorVersion+" minorVersion:"+minorVersion);

            return minorVersion;
        }
    }
    private static boolean checkSdkSupport(int richTapMajorVersion,int richTapMinorVersion,int heVersion){

        Log.d(TAG, "check richtap richTapMajorVersion:"+richTapMajorVersion +" heVersion:"+heVersion);
        if(richTapMajorVersion < 0x16) {//v1.6
            Log.e(TAG, "can not support he in richtap version:"+String.format("%x",richTapMajorVersion));
            return false;
        }else if(richTapMajorVersion == 0x16) {//v1.6
            if(heVersion != 1){
                Log.e(TAG, "RichTap version is "+
                        String.format("%x",richTapMajorVersion) + " can not support he version: " + heVersion);
                return false;
            }
        }else if(richTapMajorVersion == 0x17) {//v1.7
            if(heVersion != 1 && heVersion != 2){
                return false;
            }
        }

        return true;
    }
    private int[] getSerializationDataHe_1_0( String patternString){
        int totalDuration;
        int relativeTimeLast = 0;
        int durationLast = 0;
        int[] patternHeInfo = null;
        try {
            JSONObject hapticObject = new JSONObject(patternString);
            JSONArray pattern = hapticObject.getJSONArray(PATTERN_KEY_PATTERN);
            int eventNumberTmp = Math.min(pattern.length(), MAX_EVENT_COUNT);
            int len = eventNumberTmp * HE_VALUE_LENGTH;
            patternHeInfo = new int[len];

            boolean isCompliance = true;

            for (int ind = 0; ind < eventNumberTmp; ind++) {

                JSONObject patternObject = pattern.getJSONObject(ind);
                JSONObject eventObject = patternObject.getJSONObject(EVENT_KEY_EVENT);

                //get type
                String name = eventObject.getString(EVENT_KEY_HE_TYPE);
                int type;
                if (TextUtils.equals(EVENT_TYPE_HE_CONTINUOUS_NAME, name)) {
                    type = CONTINUOUS_EVENT;
                } else if (TextUtils.equals(EVENT_TYPE_HE_TRANSIENT_NAME, name)) {
                    type = TRANSIENT_EVENT;
                } else {
                    //err data
                    Log.e(TAG, "haven't get type value");
                    isCompliance = false;
                    break;
                }
                //get RelativeTime
                if (!eventObject.has(EVENT_KEY_RELATIVE_TIME)) {
                    Log.e(TAG, "event:" + ind + " don't have relativeTime parameters,set default:" + (ind * HE_DEFAULT_RELATIVE_TIME));
                    relativeTimeLast = ind * HE_DEFAULT_RELATIVE_TIME;
                } else {
                    relativeTimeLast = eventObject.getInt(EVENT_KEY_RELATIVE_TIME);
                }
                if (!isInTheInterval(relativeTimeLast, 0, 50000)) {
                    Log.e(TAG, "relativeTime must between 0 and 50000");
                    isCompliance = false;
                    break;
                }
                //get Parameters
                JSONObject parametersObject = eventObject.getJSONObject(EVENT_KEY_HE_PARAMETERS);
                int intensity = parametersObject.getInt(EVENT_KEY_HE_INTENSITY);
                int frequency = parametersObject.getInt(EVENT_KEY_HE_FREQUENCY);
                if (!isInTheInterval(intensity, 0, 100) || !isInTheInterval(frequency, 0, 100)) {
                    Log.e(TAG, "intensity or frequency must between 0 and 100");
                    isCompliance = false;
                    break;
                }
                patternHeInfo[ind * HE_VALUE_LENGTH + HE_TYPE] = type;
                patternHeInfo[ind * HE_VALUE_LENGTH + HE_RELATIVE_TIME] = relativeTimeLast;
                patternHeInfo[ind * HE_VALUE_LENGTH + HE_INTENSITY] = intensity;
                patternHeInfo[ind * HE_VALUE_LENGTH + HE_FREQUENCY] = frequency;

                if (CONTINUOUS_EVENT == type) {
                    //get Duration
                    if (!eventObject.has(EVENT_KEY_DURATION)) {
                        Log.e(TAG, "event:" + ind + " don't have duration parameters,set default:" + HE_DEFAULT_DURATION);
                        durationLast = HE_DEFAULT_DURATION;
                    } else {
                        durationLast = eventObject.getInt(EVENT_KEY_DURATION);
                    }
                    if (!isInTheInterval(durationLast, 0, 5000)) {
                        Log.e(TAG, "duration must be less than 5000");
                        isCompliance = false;
                        break;
                    }
                    patternHeInfo[ind * HE_VALUE_LENGTH + HE_DURATION] = durationLast;

					patternHeInfo[ind * HE_VALUE_LENGTH + HE_VIB_INDEX] = 0;

                    JSONArray curve = parametersObject.getJSONArray(EVENT_KEY_HE_CURVE);
                    int pointCount = Math.min(curve.length(), MAX_POINT_COUNT);
                    patternHeInfo[ind * HE_VALUE_LENGTH + HE_POINT_COUNT] = pointCount;

                    //points data
                    for (int i = 0; i < pointCount; i++) {
                        JSONObject curveObject = curve.getJSONObject(i);
                        //get curve points data
                        int pointTime = curveObject.getInt(EVENT_KEY_HE_CURVE_POINT_TIME);
                        int pointIntensity = (int) (curveObject.getDouble(EVENT_KEY_HE_INTENSITY) * 100);// * 100 ,方便传递数据
                        int pointFrequency = curveObject.getInt(EVENT_KEY_HE_FREQUENCY);
                        if (0 == i && (pointTime != 0 || pointIntensity != 0 || !isInTheInterval(pointFrequency, -100, 100))) {
                            Log.e(TAG, "first point's time,  intensity must be 0, frequency must between -100 and 100");
                            isCompliance = false;
                            break;
                        } else if ( 0 < i && i < pointCount - 1  && (!isInTheInterval(pointTime, 0, 5000) || !isInTheInterval(pointIntensity, 0, 100) || !isInTheInterval(pointFrequency, -100, 100))) {
                            // intensity value has multi 100, so interval is 0~100
                            Log.e(TAG, "point's time must be less than 5000, intensity must between 0~1, frequency must between -100 and 100");
                            isCompliance = false;
                            break;
                        } else if (pointCount -1 == i && (pointTime != durationLast || pointIntensity != 0 || !isInTheInterval(pointFrequency, -100, 100))) {
                            Log.e(TAG, "last point's time must equal with duration, and intensity must be 0, frequency must between -100 and 100");
                            isCompliance = false;
                            break;
                        }

                        patternHeInfo[ind * HE_VALUE_LENGTH + (HE_CURVE_POINT_0_TIME + i * 3)] = pointTime;
                        patternHeInfo[ind * HE_VALUE_LENGTH + (HE_CURVE_POINT_0_INTENSITY + i * 3)] = pointIntensity;
                        patternHeInfo[ind * HE_VALUE_LENGTH + (HE_CURVE_POINT_0_FREQUENCY + i * 3)] = pointFrequency;
                    }
                }
                if (!isCompliance) break;

                if (DEBUG) {
                    for (int i = 0; i < HE_VALUE_LENGTH; i++) {
                        Log.d(TAG, "patternHeInfo[" + ind + "][" + i + "]:" + patternHeInfo[ind * HE_VALUE_LENGTH + i]);
                    }
                }
            }
            //Compliance check
            if (!isCompliance) {
                Log.e(TAG, "current he file data, isn't compliance!!!!!!!");
                return null;
            }

            int lastEventIndex = (eventNumberTmp - 1) * HE_VALUE_LENGTH + HE_TYPE;
            if (CONTINUOUS_EVENT == patternHeInfo[lastEventIndex]) {
                totalDuration = relativeTimeLast + durationLast;
                Log.d(TAG, "last event type is continuous, totalDuration:" + totalDuration);
            } else {
                totalDuration = relativeTimeLast + 80;
                Log.d(TAG, "last event type is transient, totalDuration:" + totalDuration);
            }
        }catch(Exception e){
            e.printStackTrace();
            return null;
        }
        return patternHeInfo;
    }

    int[] generateSerializationDataHe_2_0(int formatVersion ,int heVersion,int totalPattern,int pid,int seq,
                                          int indexBase,Pattern[] pattern){
        int totalPatternLen = 0;
        int patternOffset = 5;
        for(Pattern patternTmp:pattern){
            totalPatternLen +=patternTmp.getPatternDataLen();
        }
        int[] data = new int[patternOffset+totalPatternLen];
        Arrays.fill(data,0);

        data[0] = formatVersion;
        data[1] = heVersion;
        data[2] = pid;
        data[3] = seq;
        data[4] |= totalPattern & 0x0000FFFF;
        int patternNum = pattern.length;
        data[4] |= ((patternNum << 16) & 0xFFFF0000);

        int index = indexBase;
        int[] patternData = null;

        for(Pattern patternTmp:pattern){
            patternData = patternTmp.generateSerializationPatternData(indexBase);
            System.arraycopy(patternData,0,data,patternOffset,patternData.length);
            patternOffset += patternData.length;
            indexBase++;
        }
        return data;
    }
    void sendPatternWrapper(int seq,int pid,int heVersion,int loop, int interval,
                            int amplitude, int freq,int totalPatternNum,int patternIndexOffset,Pattern[] list){
        int[] patternHe = generateSerializationDataHe_2_0(FORMAT_VERSION,heVersion,totalPatternNum,pid,
                seq,patternIndexOffset,list);
        try {
            if (Build.VERSION.SDK_INT >= ANDROID_VERSIONCODE_O) {
                VibrationEffect createPatternHe = RichTapVibrationEffect.createPatternHeWithParam(patternHe,loop, interval, amplitude, freq);
                VibrationAttributes atr = new VibrationAttributes.Builder().build();
                CombinedVibration combinedEffect = CombinedVibration.createParallel(createPatternHe);
                mVibratorManager.vibrate(Process.myUid(), mPackageName,
                        combinedEffect, VIBRATE_REASON, atr);
            }else{
                Log.e(TAG, "The system is low than 26,does not support richTap!!");
            }
        }catch (Exception e){
            e.printStackTrace();
            Log.w(TAG, "for createPatternHe, The system doesn't integrate richTap software");
        }
    }
    private void parseAndSendDataHe_2_0(int seq,int pid,int heVersion,int loop, int interval,
                                        int amplitude, int freq,  String patternString){
        int relativeTimeLast = 0;
        int durationLast = 0;
        byte[] patternHeInfo = null;
        Pattern[] patternList = null;
        try {
            JSONObject hapticObject = new JSONObject(patternString);
            JSONArray patternArray = hapticObject.getJSONArray(PATTERN_KEY_PATTERN_LIST);
            int patternNum = patternArray.length();
            patternHeInfo = new byte[patternNum * 64];

            boolean isCompliance = true;
            patternList = new Pattern[patternNum];
            int wrapperOffset = 0,wrapperIndex = 0;
            for (int ind = 0; ind < patternNum;) {
                Pattern pattern = new Pattern();
                JSONObject patternObject = patternArray.getJSONObject(ind);
                int patternRelativeTime = patternObject.getInt(PATTERN_KEY_PATTERN_ABS_TIME);
                pattern.mRelativeTime = patternRelativeTime;

                int patternDurationTime = relativeTimeLast + durationLast;
                if(ind > 0 && patternRelativeTime < patternDurationTime){
                    Log.e(TAG, "Bad pattern relative time in int:"+ind);
                    return;
                }

                JSONArray eventArray = patternObject.getJSONArray(PATTERN_KEY_PATTERN);
                pattern.mEvent = new Event[eventArray.length()];

                int eventRelativeTime = -1;
                for (int event = 0; event < eventArray.length(); event++) {
                    JSONObject eventObject = eventArray.getJSONObject(event);

                    JSONObject eventTemp = eventObject.getJSONObject(EVENT_KEY_EVENT);
                    //get type
                    String name = eventTemp.getString(EVENT_KEY_HE_TYPE);
                    int type;
                    if (TextUtils.equals(EVENT_TYPE_HE_CONTINUOUS_NAME, name)) {
                        type = CONTINUOUS_EVENT;
                        pattern.mEvent[event] = new ContinuousEvent();
                    } else if (TextUtils.equals(EVENT_TYPE_HE_TRANSIENT_NAME, name)) {
                        type = TRANSIENT_EVENT;
                        pattern.mEvent[event] = new TransientEvent();
                    } else {
                        //err data
                        Log.e(TAG, "haven't get type value");
                        isCompliance = false;
                        break;
                    }

                    int vibId = eventTemp.getInt(PATTERN_KEY_EVENT_VIB_ID);
                    pattern.mEvent[event].mVibId = (byte)vibId;
                    //get RelativeTime
                    if (!eventTemp.has(EVENT_KEY_RELATIVE_TIME)) {
                        Log.e(TAG, "event:" + ind + " don't have relativeTime parameters,BAD he!");
                        //relativeTimeLast = ind * HE_DEFAULT_RELATIVE_TIME;
                        return;
                    } else {
                        relativeTimeLast = eventTemp.getInt(EVENT_KEY_RELATIVE_TIME);

                        if(event > 0 && relativeTimeLast < eventRelativeTime){
                            Log.e(TAG, "pattern ind:"+ind+" event:"+event+" relative time is not right!");
                            return;
                        }
                        eventRelativeTime = relativeTimeLast;
                    }
                    if (!isInTheInterval(relativeTimeLast, 0, 50000)) {
                        Log.e(TAG, "relativeTime must between 0 and 50000");
                        isCompliance = false;
                        break;
                    }
                    //get Parameters
                    JSONObject parametersObject = eventTemp.getJSONObject(EVENT_KEY_HE_PARAMETERS);
                    int intensity = parametersObject.getInt(EVENT_KEY_HE_INTENSITY);
                    int frequency = parametersObject.getInt(EVENT_KEY_HE_FREQUENCY);
                    if (!isInTheInterval(intensity, 0, 100) || !isInTheInterval(frequency, 0, 100)) {
                        Log.e(TAG, "intensity or frequency must between 0 and 100");
                        isCompliance = false;
                        break;
                    }
                    pattern.mEvent[event].mType = type;
                    pattern.mEvent[event].mRelativeTime = relativeTimeLast;
                    pattern.mEvent[event].mIntensity = intensity;
                    pattern.mEvent[event].mFreq = frequency;

                    if (CONTINUOUS_EVENT == type) {
                        //get Duration
                        if (!eventTemp.has(EVENT_KEY_DURATION)) {
                            Log.e(TAG, "event:" + ind + " don't have duration parameters");
                            //durationLast = HE_DEFAULT_DURATION;
                            return;
                        } else {
                            durationLast = eventTemp.getInt(EVENT_KEY_DURATION);
                        }
                        if (!isInTheInterval(durationLast, 0, 5000)) {
                            Log.e(TAG, "duration must be less than 5000");
                            isCompliance = false;
                            break;
                        }
                        //patternHeInfo[ind * HE_VALUE_LENGTH + HE_DURATION] = durationLast;
                        pattern.mEvent[event].mDuration = durationLast;

                        JSONArray curve = parametersObject.getJSONArray(EVENT_KEY_HE_CURVE);
                        ((ContinuousEvent)pattern.mEvent[event]).mPointNum = (byte)curve.length();
                        Point[] piontArray = new Point[curve.length()];
                        //
                        //  points data
                        int prevPointTime = -1;
                        int i = 0;
                        int pointLastTime = 0;
                        for ( ; i < curve.length(); i++) {
                            JSONObject curveObject = curve.getJSONObject(i);
                            piontArray[i] = new Point();
                            //get curve points data
                            int pointTime = curveObject.getInt(EVENT_KEY_HE_CURVE_POINT_TIME);
                            int pointIntensity = (int) (curveObject.getDouble(EVENT_KEY_HE_INTENSITY) * 100);// * 100 ,方便传递数据
                            int pointFrequency = curveObject.getInt(EVENT_KEY_HE_FREQUENCY);

                            if(i==0 && pointTime != 0 ){
                                Log.d(TAG, "time of first point is not 0,bad he!");
                                return;
                            }

                            if((i > 0) && (pointTime < prevPointTime)){
                                Log.d(TAG, "point times did not arrange in order,bad he!");
                                return;
                            }
                            prevPointTime = pointTime;

                            piontArray[i].mTime = pointTime;
                            piontArray[i].mIntensity = pointIntensity;
                            piontArray[i].mFreq = pointFrequency;
                            pointLastTime = pointTime;
                        }
                        if(pointLastTime != durationLast){
                            Log.e(TAG, "event:" + ind + " point last time do not match duration parameter");
                            return;
                        }
                        if(piontArray.length > 0) {
                            ((ContinuousEvent) pattern.mEvent[event]).mPoint = piontArray;
                        }else{
                            Log.d(TAG, "continuous event has nothing in point");
                            isCompliance = false;
                        }
                    }
                    if (!isCompliance) break;

                    if (DEBUG) {
                        for (int i = 0; i < HE_VALUE_LENGTH; i++) {
                            Log.d(TAG, "patternHeInfo[" + ind + "][" + i + "]:" + patternHeInfo[ind * HE_VALUE_LENGTH + i]);
                        }
                    }
                }
                //Compliance check
                if (!isCompliance) {
                    Log.e(TAG, "current he file data, isn't compliance!!!!!!!");
                    return;
                }
                patternList[ind] = pattern;
                ind++;
                if(ind >= HE2_0_PATTERN_WRAP_NUM*(wrapperIndex+1)){
                    Pattern[] patternWrapper = new Pattern[HE2_0_PATTERN_WRAP_NUM];
                    for(int i = 0;i<HE2_0_PATTERN_WRAP_NUM;i++){
                        patternWrapper[i] = patternList[wrapperOffset + i];
                    }
                    sendPatternWrapper(seq,pid,heVersion,loop, interval,
                            amplitude, freq,patternNum,wrapperOffset,patternWrapper);
                    wrapperIndex++;
                    wrapperOffset = HE2_0_PATTERN_WRAP_NUM*wrapperIndex;
                }

            }
            if(wrapperOffset < patternList.length){
                int endWapperNum = patternList.length - wrapperOffset;
                Pattern[] patternWrapper = new Pattern[endWapperNum];
                for(int i = 0;i<patternWrapper.length;i++){
                    patternWrapper[i] = patternList[wrapperOffset + i];
                }
                sendPatternWrapper(seq,pid,heVersion,loop, interval,
                        amplitude, freq,patternNum,wrapperOffset,patternWrapper);
            }
        }catch(Exception e){
            e.printStackTrace();
        }

    }
    public void applyPatternHeWithString(@Nullable String patternString, int loop,int interval,int amplitude,int freq){
        Log.d(TAG, "play new he api");
        if (!mAvailable) {
            Log.w(TAG, "RichTap is not supported on this device, ignoring pattern");
            return;
        }
        if (loop < 1){
            Log.e(TAG, "The minimum count of loop pattern is 1");
            return;
        }
        try{
            JSONObject hapticObject = new JSONObject(patternString);

            int heVersion = 0;
            if(mAvailable) {
                JSONObject metaData = hapticObject.getJSONObject(HE_META_DATA_KEY);
                heVersion = metaData.getInt(HE_VERSION_KEY);
                int richTapMajorVersion = getMajorVersion();
                int richTapMinorVersion = getMinorVersion();
                boolean checkPass = checkSdkSupport(richTapMajorVersion,richTapMinorVersion, heVersion);

                if (!checkPass) {
                    Log.e(TAG, "richtap version check failed, richTapMajorVersion:" +
                            String.format("%x02", richTapMajorVersion) + " heVersion:" + heVersion);
                    return;
                }
            }

            int[] patternHeInfo = null;
            Pattern[] patternList = null;
            int[] patternHe = null;

            if(heVersion == 1){
                patternHeInfo = getSerializationDataHe_1_0(patternString);
                if(patternHeInfo == null){
                    Log.e(TAG, "serialize he failed!! ,heVersion:"+heVersion);
                    return;
                }
                int len = patternHeInfo.length;
                try {
                    if (Build.VERSION.SDK_INT >= ANDROID_VERSIONCODE_O) {
                        int[] realPatternHeInfo = null;
                        realPatternHeInfo = new int[len + 1];
                        realPatternHeInfo[0] = 0x3; // 0x3: HE1.0 which support 16 curve points
                        System.arraycopy(patternHeInfo, 0, realPatternHeInfo, 1, patternHeInfo.length);

                        VibrationEffect createPatternHe = RichTapVibrationEffect.createPatternHeWithParam(realPatternHeInfo,loop, interval, amplitude, freq);
                        VibrationAttributes atr = new VibrationAttributes.Builder().build();
                        CombinedVibration combinedEffect = CombinedVibration.createParallel(createPatternHe);
                        mVibratorManager.vibrate(Process.myUid(), mPackageName,
                                combinedEffect, VIBRATE_REASON, atr);
                    }else{
                        Log.e(TAG, "The system is low than 26,does not support richTap!!");
                    }
                }catch (Exception e){
                    e.printStackTrace();
                    Log.w(TAG, "for createPatternHe, The system doesn't integrate richTap software");
                }
            }else if(heVersion == 2){
                int seq = mSeq.getAndIncrement();
                int pid = android.os.Process.myPid();
                parseAndSendDataHe_2_0(seq,pid,heVersion,loop, interval, amplitude,freq,patternString);
            }else{
                Log.e(TAG, "unsupport he version heVersion:"+heVersion);
            }
        }catch (Exception e) {
            e.printStackTrace();
        }
    }

    public int getRealLooper(int looper){
        if(looper < 0 ){
            if(looper == -1){
                return Integer.MAX_VALUE;
            }else{
                return 0;
            }
        }else if(looper == 0){
            return 1;
        } else{
            return looper;
        }
    }
    public void start(final int loop) {
        Log.d(TAG, "start play pattern loop:"+loop);
        if(mEffect == null){
            Log.e(TAG, "effect is null,do nothing");
            return;
        }

        if (Build.VERSION.SDK_INT >= ANDROID_VERSIONCODE_O) {
            final int realLooper = getRealLooper(loop);
            if(realLooper < 0){
                Log.e(TAG, "looper is not correct realLooper:"+realLooper);
                return;
            }
            mExcutor.execute(new Runnable(){
                @Override
                public void run() {
                    Log.d(TAG, "haptic play start!");
                    long startRunTime = System.currentTimeMillis();
                    try {
                        mStarted = true;
						String patternJson = mEffect.getPatternInfo();
						if(patternJson == null){
							Log.d(TAG, "pattern is null,can not play!");
							return;
						}
                        applyPatternHeWithString(patternJson, realLooper,0,255,0);
                    }catch (Exception e){
                        e.printStackTrace();
                        Log.w(TAG, "for createPatternHe, The system doesn't integrate richTap software");
                    }
                    long useTime = System.currentTimeMillis()-startRunTime;
                    Log.d(TAG, "run vibrate thread use time:"+ useTime);
                }
            });
        }else{
            Log.e(TAG, "The system is low than 26,does not support richTap!!");
        }
    }
/**
     * 必须实现，开始播放效果
     * @param loop 循环次数, 1不循环，大于1循环次数，-1无限循环；
     * @param interval 循环间隔, 0-1000, 每次震动循环播放的间隔，单位ms;
     * @param amplitude 振动强度，1-255，1最小，255最大。此参数用于修饰HE文件，进行整体的强度信号调整/缩减;
     */
    public void start(final int loop, final int interval, final int amplitude) {
        Log.d(TAG, "start with loop:"+ loop+" interval:"+interval+" amplitude:"+amplitude);
        boolean checkResult = checkParam(interval,amplitude, -1);
        
        if(!checkResult){
            Log.e(TAG, "wrong start param");
            return;
        }
        
        if(mEffect == null){
            Log.e(TAG, "effect is null,do nothing");
            return;
        }

        if (Build.VERSION.SDK_INT >= ANDROID_VERSIONCODE_O) {
            final int realLooper = getRealLooper(loop);
            if(realLooper < 0){
                Log.e(TAG, "looper is not correct realLooper:"+realLooper);
                return;
            }
            mExcutor.execute(new Runnable(){
                @Override
                public void run() {
                    Log.d(TAG, "haptic play start!");
                    long startRunTime = System.currentTimeMillis();
                    try {
                        mStarted = true;
                        String patternJson = mEffect.getPatternInfo();
                        if(patternJson == null){
                            Log.d(TAG, "pattern is null,can not play!");
                            return;
                        }
                        applyPatternHeWithString(patternJson, realLooper,interval,amplitude,0);
                    }catch (Exception e){
                        e.printStackTrace();
                        Log.w(TAG, "for createPatternHe, The system doesn't integrate richTap software");
                    }
                    long useTime = System.currentTimeMillis()-startRunTime;
                    Log.d(TAG, "run vibrate thread use time:"+ useTime);
                }
            });
        }else{
            Log.e(TAG, "The system is low than 26,does not support richTap!!");
        }
    }

    /**
     * 预留接口，开始播放效果
     * @param loop 循环次数, 1不循环，大于1循环次数，-1无限循环；
     * @param interval 循环间隔, 0-1000, 每次震动循环播放的间隔，单位ms;
     * @param amplitude 振动强度, 1-255，1最小，255最大。此参数用于修饰HE文件，进行整体的强度信号调整/缩减;
     * @param freq 振动频率, 此参数用于修饰HE文件，进行整体的频率信号进行调整;
     */
    public void start(final int loop, final int interval, final int amplitude, final int freq) {
        Log.d(TAG, "start with loop:"+ loop+" interval:"+interval+" amplitude:"+amplitude + " freq:"+freq);
        boolean checkResult = checkParam(interval, amplitude, freq);
        
        if(!checkResult){
            Log.e(TAG, "wrong start param");
            return;
        }

        if(mEffect == null){
            Log.e(TAG, "effect is null,do nothing");
            return;
        }

        if (Build.VERSION.SDK_INT >= ANDROID_VERSIONCODE_O) {
            final int realLooper = getRealLooper(loop);
            if(realLooper < 0){
                Log.e(TAG, "looper is not correct realLooper:"+realLooper);
                return;
            }
            mExcutor.execute(new Runnable(){
                @Override
                public void run() {
                    Log.d(TAG, "haptic play start!");
                    long startRunTime = System.currentTimeMillis();
                    try {
                        mStarted = true;
                        String patternJson = mEffect.getPatternInfo();
                        if(patternJson == null){
                            Log.d(TAG, "pattern is null,can not play!");
                            return;
                        }
                        applyPatternHeWithString(patternJson, realLooper,interval,amplitude,freq);
                    }catch (Exception e){
                        e.printStackTrace();
                        Log.w(TAG, "for createPatternHe, The system doesn't integrate richTap software");
                    }
                    long useTime = System.currentTimeMillis()-startRunTime;
                    Log.d(TAG, "run vibrate thread use time:"+ useTime);
                }
            });
        }else{
            Log.e(TAG, "The system is low than 26,does not support richTap!!");
        }
    }
    
    private boolean checkParam(int interval, int amplitude, int freq){
        if(interval < 0 && interval != -1){
            Log.e(TAG, "wrong interval param");
            return false;
        }
        
        if(freq < 0 && freq != -1){
            Log.e(TAG, "wrong freq param");
            return false;
        }
        
        if((amplitude < 0 && amplitude != -1) || (amplitude > 255)){
            Log.e(TAG, "wrong amplitude param");
            return false;
        }
        return true;
    }
    
    public void applyPatternHeParam(final int interval, final int amplitude, final int freq) {
        
        Log.d(TAG, "start with "+" interval:"+interval+" amplitude:"+amplitude + " freq:"+freq);

        boolean checkResult = checkParam(interval, amplitude, freq);
        
        if(!checkResult){
            Log.e(TAG, "wrong param!");
            return;
        }
        
        try {
            if(mStarted == true){
                mExcutor.execute(new Runnable(){
                    @Override
                    public void run() {
                        try {
                            VibrationEffect createPatternHe = RichTapVibrationEffect.createPatternHeParameter(interval, amplitude, freq);
                            CombinedVibration combinedEffect = CombinedVibration.createParallel(createPatternHe);
                            mVibratorManager.vibrate(Process.myUid(), mPackageName,
                                        combinedEffect, VIBRATE_REASON, null);
                            Log.d(TAG, "haptic apply param");
                        }catch (Exception e){
                            e.printStackTrace();
                            Log.w(TAG, "for createPatternHe, The system doesn't integrate richTap software");
                        }
                    }
                });
            }else{
                Log.d(TAG, "haptic player has not started");
            }
        } catch (Exception e) {
            e.printStackTrace();
            Log.w(TAG, "The system doesn't integrate richTap software");
        }
    }
    
    /**
     * 更新播放效果循环的时间间隔
     * @param interval 循环间隔，interval取值区间[0,1000]，单位ms
     */
    public void updateInterval(int interval) {
        applyPatternHeParam(interval, -1, -1);
    }

    /**
     * 更新播放效果的振动强度，对整体效果的强度信号进行放大、缩小
     * @param amplitude 振动强度，amplitude取值区间[1,255]
     */
    public void updateAmplitude(int amplitude) {
        applyPatternHeParam(-1, amplitude, -1);
    }

    /**
     * 更新播放效果的频率，对整体效果的频率信号进行放大、缩小
     * @param freq 振动频率
     */
    public void updateFrequency(int freq) {
        applyPatternHeParam(-1, -1, freq);
    }

    /**
     * 更新播放效果相关参数
     * interval|amplitude == -1时，表示不更新
     * 
     * @param interval 循环间隔
     * @param amplitude 振动强度
     * @param freq 振动频率, 预留
     */
    public void updateParameter(int interval, int amplitude, int freq) {
        applyPatternHeParam(interval, amplitude, freq);
    }
    
    //必须实现，停止播放振动效果
    public void stop() {
        if (Build.VERSION.SDK_INT >= ANDROID_VERSIONCODE_O) {
            if(mStarted == true){
                mExcutor.execute(new Runnable(){
                    @Override
                    public void run() {
                        try {
                            VibrationEffect createPatternHe = RichTapVibrationEffect.createPatternHeParameter(0, 0, 0);
                            CombinedVibration combinedEffect = CombinedVibration.createParallel(createPatternHe);
                            mVibratorManager.vibrate(Process.myUid(), mPackageName,
                                        combinedEffect, VIBRATE_REASON, null);
                            Log.d(TAG, "haptic play stop");
                        }catch (Exception e){
                            e.printStackTrace();
                            Log.w(TAG, "for createPatternHe, The system doesn't integrate richTap software");
                        }
                    }
                });
            }else{
                Log.d(TAG, "haptic player has not started");
            }

        }else{
            Log.e(TAG, "The system is low than 26,does not support richTap!!");
        }

    }

    abstract class Event{
        int mType;
        int mLen;
        int mVibId;
        int mRelativeTime;
        int mIntensity;
        int mFreq;
        int mDuration;

        abstract int[] generateData();

        @Override
        public String toString() {
            return "Event{" +
                    "mType=" + mType +
                    ", mVibId=" + mVibId +
                    ", mRelativeTime=" + mRelativeTime +
                    ", mIntensity=" + mIntensity +
                    ", mFreq=" + mFreq +
                    ", mDuration=" + mDuration +
                    '}';
        }
    }

    class TransientEvent extends Event{

        TransientEvent(){
            mLen = 7;
        }
        int[] generateData(){
            int[] data = new int[mLen];
            Arrays.fill(data,0);
            data[0] = mType;
            data[1] = mLen - 2;
            data[2] = mVibId;

            data[3] = mRelativeTime;
            data[4] = mIntensity;
            data[5] = mFreq;
            data[6] = mDuration;
            return data;
        }
    }

    class Point{
        int mTime;
        int mIntensity;
        int mFreq;
    }
    class ContinuousEvent extends Event{
        int mPointNum;//max 16
        Point[] mPoint;

        ContinuousEvent(){
        }
        int[] generateData(){
            int pointOffset = 8;
            int[] data = new int[pointOffset + mPointNum*3];
            Arrays.fill(data,0);

            data[0] = mType;
            data[1] = pointOffset + mPointNum*3 - 2;
            data[2] = mVibId;

            data[3] = mRelativeTime;
            data[4] = mIntensity;
            data[5] = mFreq;
            data[6] = mDuration;
            data[7] = mPointNum;

            int offset = pointOffset;
            for(int i = 0;i< mPointNum;i++){
                data[offset] = mPoint[i].mTime;
                offset += 1;

                data[offset] = mPoint[i].mIntensity;
                offset += 1;

                data[offset] = mPoint[i].mFreq;
                offset += 1;
            }

            return data;
        }
        @Override
        public String toString() {
            return "Continuous{" +
                    "mPointNum=" + mPointNum +
                    ", mPoint=" + Arrays.toString(mPoint) +
                    '}';
        }
    }


    class Pattern{
        int mRelativeTime;
        Event[] mEvent;

        int getPatternEventLen(){
            int len = 0;

            for(Event event:mEvent){
                if(event.mType == CONTINUOUS_EVENT){
                    len += 8 + ((ContinuousEvent)event).mPointNum * 3;
                }else if(event.mType == TRANSIENT_EVENT){
                    len += 7;
                }
            }
            return len;
        }

        int getPatternDataLen(){
            int eventLen = getPatternEventLen();

            return 3+eventLen;
        }

        int[] generateSerializationPatternData(int index){

            int dataLen = getPatternDataLen();
            int[] data = new int[dataLen];
            Arrays.fill(data,0);

            data[0] = index;
            data[1] = mRelativeTime;
            data[2] = mEvent.length;


            int[] eventData = null;
            int offset = 3;
            for(Event event:mEvent){
                eventData = event.generateData();
                System.arraycopy(eventData,0,data,offset,eventData.length);
                offset += eventData.length;
            }

            return data;
        }
    }
}
