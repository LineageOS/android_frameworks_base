package com.nvidia.NvCPLSvc;

import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Parcelable.Creator;

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
    private int appActivity;
    private Drawable appIcon;
    private String appLabel;
    public int appList;
    public String pkgName;
    private float powerSaver;
    public long totalWakeupStatsTime;
    public int uid;
    public long wakeupStatsTime;
    public int wakeupTimes;
    public int wowWakeupTimes;

    public NvSaverAppInfo(Parcel pl) {
        this.uid = pl.readInt();
        this.appList = pl.readInt();
        this.wakeupTimes = pl.readInt();
        this.wowWakeupTimes = pl.readInt();
        this.pkgName = pl.readString();
        this.wakeupStatsTime = pl.readLong();
        this.totalWakeupStatsTime = pl.readLong();
        this.appLabel = null;
        this.appIcon = null;
        this.appActivity = 0;
        this.powerSaver = 0.0f;
    }

    public NvSaverAppInfo(int u, int a, int w, int wow, String pkg, long t1, long t2) {
        this.uid = u;
        this.appList = a;
        this.wakeupTimes = w;
        this.wowWakeupTimes = wow;
        this.pkgName = pkg;
        this.wakeupStatsTime = t1;
        this.totalWakeupStatsTime = t2;
        this.appLabel = null;
        this.appIcon = null;
        this.appActivity = 0;
        this.powerSaver = 0.0f;
    }

    public String getAppLabel() {
        return this.appLabel;
    }

    public void setAppLabel(String appLabel) {
        this.appLabel = appLabel;
    }

    public Drawable getAppIcon() {
        return this.appIcon;
    }

    public void setAppIcon(Drawable appIcon) {
        this.appIcon = appIcon;
    }

    public int getAppActivity() {
        return this.appActivity;
    }

    public void setAppActivity(int activity) {
        this.appActivity = activity;
    }

    public String getPkgName() {
        return this.pkgName;
    }

    public void setPkgName(String pkgName) {
        this.pkgName = pkgName;
    }

    public int getUid() {
        return this.uid;
    }

    public void setUid(int uid) {
        this.uid = uid;
    }

    public int getWakeupTimes() {
        return this.wakeupTimes;
    }

    public void setWakeupTimes(int wakeupTimes) {
        this.wakeupTimes = wakeupTimes;
    }

    public int getWowWakeupTimes() {
        return this.wowWakeupTimes;
    }

    public void setWowWakeupTimes(int wowWakeupTimes) {
        this.wowWakeupTimes = wowWakeupTimes;
    }

    public long getTotalWakeupStatsTime() {
        return this.totalWakeupStatsTime;
    }

    public void setTotalWakeupStatsTime(long totalWakeupStatsTime) {
        this.totalWakeupStatsTime = totalWakeupStatsTime;
    }

    public long getWakeupStatsTime() {
        return this.wakeupStatsTime;
    }

    public void setWakeupStatsTime(long wakeupStatsTime) {
        this.wakeupStatsTime = wakeupStatsTime;
    }

    public int getAppList() {
        return this.appList;
    }

    public void setAppList(int appList) {
        this.appList = appList;
    }

    public float getPowerSaver() {
        return this.powerSaver;
    }

    public void setPowerSaver(float powerSaver) {
        this.powerSaver = powerSaver;
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(this.uid);
        dest.writeInt(this.appList);
        dest.writeInt(this.wakeupTimes);
        dest.writeInt(this.wowWakeupTimes);
        dest.writeString(this.pkgName);
        dest.writeLong(this.wakeupStatsTime);
        dest.writeLong(this.totalWakeupStatsTime);
    }
}
