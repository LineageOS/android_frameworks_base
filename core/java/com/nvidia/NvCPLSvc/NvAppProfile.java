package com.nvidia.NvCPLSvc;

import android.net.ProxyInfo;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.SparseArray;

public class NvAppProfile implements Parcelable {
    public static final Creator<NvAppProfile> CREATOR = new Creator<NvAppProfile>() {
        public NvAppProfile createFromParcel(Parcel parcel) {
            return NvAppProfile.createFromParcel(parcel);
        }

        public NvAppProfile[] newArray(int size) {
            return new NvAppProfile[size];
        }
    };
    public final String pkgName;
    public final String pkgVersion;
    public final int typeId;
    public SparseArray<String> settings;

    public NvAppProfile(int typeId, String pkgName, String pkgVersion,
            SparseArray<String> settings) {
        this.typeId = typeId;
        this.pkgName = pkgName;
        this.pkgVersion = pkgVersion;
        this.settings = settings;
    }

    private static NvAppProfile createFromParcel(Parcel parcel) {
        int typeId = parcel.readInt();
        String pkgName = decodeNull(parcel.readString());
        String pkgVersion = decodeNull(parcel.readString());
        int numSettings = parcel.readInt();
        SparseArray<String> settings = new SparseArray();
        for (int i = 0; i < numSettings; i++) {
            settings.append(parcel.readInt(), parcel.readString());
        }
        return new NvAppProfile(typeId, pkgName, pkgVersion, settings);
    }

    private static String encodeNull(String string) {
        return string != null ? string : ProxyInfo.LOCAL_EXCL_LIST;
    }

    private static String decodeNull(String string) {
        return !string.equals(ProxyInfo.LOCAL_EXCL_LIST) ? string : null;
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel parcel, int flag) {
        parcel.writeInt(this.typeId);
        parcel.writeString(encodeNull(this.pkgName));
        parcel.writeString(encodeNull(this.pkgVersion));
        parcel.writeInt(this.settings.size());
        for (int i = 0; i < this.settings.size(); i++) {
            parcel.writeInt(this.settings.keyAt(i));
            parcel.writeString((String) this.settings.valueAt(i));
        }
    }
}
