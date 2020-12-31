package com.nvidia.NvCPLSvc;

import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;

public class NvSaverAppInfo implements Parcelable {
    public static final Creator<NvSaverAppInfo> CREATOR = new Creator<NvSaverAppInfo>() {
        public NvSaverAppInfo createFromParcel(Parcel source) {
            return new NvSaverAppInfo(source);
        }

        public NvSaverAppInfo[] newArray(int size) {
            return new NvSaverAppInfo[size];
        }
    };

    public static final int NVSAVER_ACTIVITY_HIGH = 1;
    public static final int NVSAVER_ACTIVITY_LOW = 3;
    public static final int NVSAVER_ACTIVITY_MIDIUM = 2;
    public static final int NVSAVER_LIST_BLACKLIST = 3;
    public static final int NVSAVER_LIST_NONE = 1;
    public static final int NVSAVER_LIST_WHITELIST = 2;
    public static final int NV_APP_OPTIMIZE_LIST = 4;
    public int mAppList;
    public String mPkgName;
    public long mTotalWakeupStatsTime;
    public int mUid;
    public long mWakeupStatsTime;
    public int mWakeupTimes;
    public int mWowWakeupTimes;
    private int mAppActivity;
    private Drawable mAppIcon;
    private String mAppLabel;
    private float mPowerSaver;

    public NvSaverAppInfo(Parcel pl) {
        mUid = pl.readInt();
        mAppList = pl.readInt();
        mWakeupTimes = pl.readInt();
        mWowWakeupTimes = pl.readInt();
        mPkgName = pl.readString();
        mWakeupStatsTime = pl.readLong();
        mTotalWakeupStatsTime = pl.readLong();
        mAppLabel = null;
        mAppIcon = null;
        mAppActivity = 0;
        mPowerSaver = 0.0f;
    }

    public NvSaverAppInfo(int u, int a, int w, int wow, String pkg, long t1, long t2) {
        mUid = u;
        mAppList = a;
        mWakeupTimes = w;
        mWowWakeupTimes = wow;
        mPkgName = pkg;
        mWakeupStatsTime = t1;
        mTotalWakeupStatsTime = t2;
        mAppLabel = null;
        mAppIcon = null;
        mAppActivity = 0;
        mPowerSaver = 0.0f;
    }

    public String getAppLabel() {
        return mAppLabel;
    }

    public void setAppLabel(String appLabel) {
        mAppLabel = appLabel;
    }

    public Drawable getAppIcon() {
        return mAppIcon;
    }

    public void setAppIcon(Drawable appIcon) {
        mAppIcon = appIcon;
    }

    public int getAppActivity() {
        return mAppActivity;
    }

    public void setAppActivity(int activity) {
        mAppActivity = activity;
    }

    public String getPkgName() {
        return mPkgName;
    }

    public void setPkgName(String pkgName) {
        mPkgName = pkgName;
    }

    public int getUid() {
        return mUid;
    }

    public void setUid(int uid) {
        mUid = uid;
    }

    public int getWakeupTimes() {
        return mWakeupTimes;
    }

    public void setWakeupTimes(int wakeupTimes) {
        mWakeupTimes = wakeupTimes;
    }

    public int getWowWakeupTimes() {
        return mWowWakeupTimes;
    }

    public void setWowWakeupTimes(int wowWakeupTimes) {
        mWowWakeupTimes = wowWakeupTimes;
    }

    public long getTotalWakeupStatsTime() {
        return mTotalWakeupStatsTime;
    }

    public void setTotalWakeupStatsTime(long totalWakeupStatsTime) {
        mTotalWakeupStatsTime = totalWakeupStatsTime;
    }

    public long getWakeupStatsTime() {
        return mWakeupStatsTime;
    }

    public void setWakeupStatsTime(long wakeupStatsTime) {
        mWakeupStatsTime = wakeupStatsTime;
    }

    public int getAppList() {
        return mAppList;
    }

    public void setAppList(int appList) {
        mAppList = appList;
    }

    public float getPowerSaver() {
        return mPowerSaver;
    }

    public void setPowerSaver(float powerSaver) {
        mPowerSaver = powerSaver;
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mUid);
        dest.writeInt(mAppList);
        dest.writeInt(mWakeupTimes);
        dest.writeInt(mWowWakeupTimes);
        dest.writeString(mPkgName);
        dest.writeLong(mWakeupStatsTime);
        dest.writeLong(mTotalWakeupStatsTime);
    }
}
