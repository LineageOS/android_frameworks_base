package motorola.hardware.health.V1_0;

import android.os.HidlSupport;
import android.os.HwBlob;
import android.os.HwParcel;
import java.util.ArrayList;
import java.util.Objects;

public final class BatteryProperties {
    public int modFlag;
    public int modLevel;
    public int modPowerSource;
    public int modStatus;
    public int modType;

    public final boolean equals(Object otherObject) {
        if (this == otherObject) {
            return true;
        }
        if (otherObject == null || otherObject.getClass() != BatteryProperties.class) {
            return false;
        }
        BatteryProperties other = (BatteryProperties) otherObject;
        if (this.modLevel == other.modLevel && this.modStatus == other.modStatus && this.modFlag == other.modFlag && this.modType == other.modType && this.modPowerSource == other.modPowerSource) {
            return true;
        }
        return false;
    }

    public final int hashCode() {
        return Objects.hash(new Object[]{Integer.valueOf(HidlSupport.deepHashCode(Integer.valueOf(this.modLevel))), Integer.valueOf(HidlSupport.deepHashCode(Integer.valueOf(this.modStatus))), Integer.valueOf(HidlSupport.deepHashCode(Integer.valueOf(this.modFlag))), Integer.valueOf(HidlSupport.deepHashCode(Integer.valueOf(this.modType))), Integer.valueOf(HidlSupport.deepHashCode(Integer.valueOf(this.modPowerSource)))});
    }

    public final String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("{");
        builder.append(".modLevel = ");
        builder.append(this.modLevel);
        builder.append(", .modStatus = ");
        builder.append(this.modStatus);
        builder.append(", .modFlag = ");
        builder.append(this.modFlag);
        builder.append(", .modType = ");
        builder.append(this.modType);
        builder.append(", .modPowerSource = ");
        builder.append(this.modPowerSource);
        builder.append("}");
        return builder.toString();
    }

    public final void readFromParcel(HwParcel parcel) {
        readEmbeddedFromParcel(parcel, parcel.readBuffer(20), 0);
    }

    public static final ArrayList<BatteryProperties> readVectorFromParcel(HwParcel parcel) {
        ArrayList<BatteryProperties> _hidl_vec = new ArrayList();
        HwBlob _hidl_blob = parcel.readBuffer(16);
        int _hidl_vec_size = _hidl_blob.getInt32(8);
        HwBlob childBlob = parcel.readEmbeddedBuffer((long) (_hidl_vec_size * 20), _hidl_blob.handle(), 0, true);
        _hidl_vec.clear();
        for (int _hidl_index_0 = 0; _hidl_index_0 < _hidl_vec_size; _hidl_index_0++) {
            BatteryProperties _hidl_vec_element = new BatteryProperties();
            _hidl_vec_element.readEmbeddedFromParcel(parcel, childBlob, (long) (_hidl_index_0 * 20));
            _hidl_vec.add(_hidl_vec_element);
        }
        return _hidl_vec;
    }

    public final void readEmbeddedFromParcel(HwParcel parcel, HwBlob _hidl_blob, long _hidl_offset) {
        this.modLevel = _hidl_blob.getInt32(0 + _hidl_offset);
        this.modStatus = _hidl_blob.getInt32(4 + _hidl_offset);
        this.modFlag = _hidl_blob.getInt32(8 + _hidl_offset);
        this.modType = _hidl_blob.getInt32(12 + _hidl_offset);
        this.modPowerSource = _hidl_blob.getInt32(16 + _hidl_offset);
    }

    public final void writeToParcel(HwParcel parcel) {
        HwBlob _hidl_blob = new HwBlob(20);
        writeEmbeddedToBlob(_hidl_blob, 0);
        parcel.writeBuffer(_hidl_blob);
    }

    public static final void writeVectorToParcel(HwParcel parcel, ArrayList<BatteryProperties> _hidl_vec) {
        HwBlob _hidl_blob = new HwBlob(16);
        int _hidl_vec_size = _hidl_vec.size();
        _hidl_blob.putInt32(8, _hidl_vec_size);
        int _hidl_index_0 = 0;
        _hidl_blob.putBool(12, false);
        HwBlob childBlob = new HwBlob(_hidl_vec_size * 20);
        while (_hidl_index_0 < _hidl_vec_size) {
            ((BatteryProperties) _hidl_vec.get(_hidl_index_0)).writeEmbeddedToBlob(childBlob, (long) (_hidl_index_0 * 20));
            _hidl_index_0++;
        }
        _hidl_blob.putBlob(0, childBlob);
        parcel.writeBuffer(_hidl_blob);
    }

    public final void writeEmbeddedToBlob(HwBlob _hidl_blob, long _hidl_offset) {
        _hidl_blob.putInt32(0 + _hidl_offset, this.modLevel);
        _hidl_blob.putInt32(4 + _hidl_offset, this.modStatus);
        _hidl_blob.putInt32(8 + _hidl_offset, this.modFlag);
        _hidl_blob.putInt32(12 + _hidl_offset, this.modType);
        _hidl_blob.putInt32(16 + _hidl_offset, this.modPowerSource);
    }
}
