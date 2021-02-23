package motorola.hardware.health.V1_0;


public final class BatteryProperties {
    public int modLevel = 0;
    public int modStatus = 0;
    public int modFlag = 0;
    public int modType = 0;
    public int modPowerSource = 0;
    public int batteryLevel = 0;

    @Override
    public final boolean equals(Object otherObject) {
        if (this == otherObject) {
            return true;
        }
        if (otherObject == null) {
            return false;
        }
        if (otherObject.getClass() != motorola.hardware.health.V1_0.BatteryProperties.class) {
            return false;
        }
        motorola.hardware.health.V1_0.BatteryProperties other = (motorola.hardware.health.V1_0.BatteryProperties)otherObject;
        if (this.modLevel != other.modLevel) {
            return false;
        }
        if (this.modStatus != other.modStatus) {
            return false;
        }
        if (this.modFlag != other.modFlag) {
            return false;
        }
        if (this.modType != other.modType) {
            return false;
        }
        if (this.modPowerSource != other.modPowerSource) {
            return false;
        }
        if (this.batteryLevel != other.batteryLevel) {
            return false;
        }
        return true;
    }

    @Override
    public final int hashCode() {
        return java.util.Objects.hash(
                android.os.HidlSupport.deepHashCode(this.modLevel), 
                android.os.HidlSupport.deepHashCode(this.modStatus), 
                android.os.HidlSupport.deepHashCode(this.modFlag), 
                android.os.HidlSupport.deepHashCode(this.modType), 
                android.os.HidlSupport.deepHashCode(this.modPowerSource), 
                android.os.HidlSupport.deepHashCode(this.batteryLevel));
    }

    @Override
    public final String toString() {
        java.lang.StringBuilder builder = new java.lang.StringBuilder();
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
        builder.append(", .batteryLevel = ");
        builder.append(this.batteryLevel);
        builder.append("}");
        return builder.toString();
    }

    public final void readFromParcel(android.os.HwParcel parcel) {
        android.os.HwBlob blob = parcel.readBuffer(24 /* size */);
        readEmbeddedFromParcel(parcel, blob, 0 /* parentOffset */);
    }

    public static final java.util.ArrayList<BatteryProperties> readVectorFromParcel(android.os.HwParcel parcel) {
        java.util.ArrayList<BatteryProperties> _hidl_vec = new java.util.ArrayList();
        android.os.HwBlob _hidl_blob = parcel.readBuffer(16 /* sizeof hidl_vec<T> */);

        {
            int _hidl_vec_size = _hidl_blob.getInt32(0 + 8 /* offsetof(hidl_vec<T>, mSize) */);
            android.os.HwBlob childBlob = parcel.readEmbeddedBuffer(
                    _hidl_vec_size * 24,_hidl_blob.handle(),
                    0 + 0 /* offsetof(hidl_vec<T>, mBuffer) */,true /* nullable */);

            _hidl_vec.clear();
            for (int _hidl_index_0 = 0; _hidl_index_0 < _hidl_vec_size; ++_hidl_index_0) {
                motorola.hardware.health.V1_0.BatteryProperties _hidl_vec_element = new motorola.hardware.health.V1_0.BatteryProperties();
                ((motorola.hardware.health.V1_0.BatteryProperties) _hidl_vec_element).readEmbeddedFromParcel(parcel, childBlob, _hidl_index_0 * 24);
                _hidl_vec.add(_hidl_vec_element);
            }
        }

        return _hidl_vec;
    }

    public final void readEmbeddedFromParcel(
            android.os.HwParcel parcel, android.os.HwBlob _hidl_blob, long _hidl_offset) {
        modLevel = _hidl_blob.getInt32(_hidl_offset + 0);
        modStatus = _hidl_blob.getInt32(_hidl_offset + 4);
        modFlag = _hidl_blob.getInt32(_hidl_offset + 8);
        modType = _hidl_blob.getInt32(_hidl_offset + 12);
        modPowerSource = _hidl_blob.getInt32(_hidl_offset + 16);
        batteryLevel = _hidl_blob.getInt32(_hidl_offset + 20);
    }

    public final void writeToParcel(android.os.HwParcel parcel) {
        android.os.HwBlob _hidl_blob = new android.os.HwBlob(24 /* size */);
        writeEmbeddedToBlob(_hidl_blob, 0 /* parentOffset */);
        parcel.writeBuffer(_hidl_blob);
    }

    public static final void writeVectorToParcel(
            android.os.HwParcel parcel, java.util.ArrayList<BatteryProperties> _hidl_vec) {
        android.os.HwBlob _hidl_blob = new android.os.HwBlob(16 /* sizeof(hidl_vec<T>) */);
        {
            int _hidl_vec_size = _hidl_vec.size();
            _hidl_blob.putInt32(0 + 8 /* offsetof(hidl_vec<T>, mSize) */, _hidl_vec_size);
            _hidl_blob.putBool(0 + 12 /* offsetof(hidl_vec<T>, mOwnsBuffer) */, false);
            android.os.HwBlob childBlob = new android.os.HwBlob((int)(_hidl_vec_size * 24));
            for (int _hidl_index_0 = 0; _hidl_index_0 < _hidl_vec_size; ++_hidl_index_0) {
                _hidl_vec.get(_hidl_index_0).writeEmbeddedToBlob(childBlob, _hidl_index_0 * 24);
            }
            _hidl_blob.putBlob(0 + 0 /* offsetof(hidl_vec<T>, mBuffer) */, childBlob);
        }

        parcel.writeBuffer(_hidl_blob);
    }

    public final void writeEmbeddedToBlob(
            android.os.HwBlob _hidl_blob, long _hidl_offset) {
        _hidl_blob.putInt32(_hidl_offset + 0, modLevel);
        _hidl_blob.putInt32(_hidl_offset + 4, modStatus);
        _hidl_blob.putInt32(_hidl_offset + 8, modFlag);
        _hidl_blob.putInt32(_hidl_offset + 12, modType);
        _hidl_blob.putInt32(_hidl_offset + 16, modPowerSource);
        _hidl_blob.putInt32(_hidl_offset + 20, batteryLevel);
    }
};

