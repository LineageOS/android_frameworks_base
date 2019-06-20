/*
 * Copyright (c) 2019, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *   * Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *   * Redistributions in binary form must reproduce the above
 *     copyright notice, this list of conditions and the following
 *     disclaimer in the documentation and/or other materials provided
 *     with the distribution.
 *   * Neither the name of The Linux Foundation nor the names of its
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package android.bluetooth;

import android.annotation.NonNull;
import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * This class provides the public APIs to access the data of BQR event reported
 * from firmware side. Currently it supports five event types: Quality monitor event,
 * Approaching LSTO event, A2DP choppy event, SCO choppy event and Connect fail event.
 * To know which kind of event is wrapped in this {@link BluetoothQualityReport} object,
 * you need to call {@link #getQualityReportId}.
 * <ul>
 *   <li> For Quality monitor event, you can call {@link #getBqrCommon} to get a
 *   {@link BluetoothQualityReport.BqrCommon} object, and call {@link #getBqrVsCommon} to get a
 *   {@link BluetoothQualityReport.BqrVsCommon} object.
 *   <li> For Approaching LSTO event, you can call {@link #getBqrCommon} to get a
 *   {@link BluetoothQualityReport.BqrCommon} object, and call {@link #getBqrVsCommon} to get a
 *   {@link BluetoothQualityReport.BqrVsCommon} object, and call {@link #getBqrVsLsto} to get a
 *   {@link BluetoothQualityReport.BqrVsLsto} object.
 *   <li> For A2DP choppy event, you can call {@link #getBqrCommon} to get a
 *   {@link BluetoothQualityReport.BqrCommon} object, and call {@link #getBqrVsCommon} to get a
 *   {@link BluetoothQualityReport.BqrVsCommon} object, and call {@link #getBqrVsA2dpChoppy} to
 *   get a {@link BluetoothQualityReport.BqrVsA2dpChoppy} object.
 *   <li> For SCO choppy event, you can call {@link #getBqrCommon} to get a
 *   {@link BluetoothQualityReport.BqrCommon} object, and call {@link #getBqrVsCommon} to get a
 *   {@link BluetoothQualityReport.BqrVsCommon} object, and call {@link #getBqrVsScoChoppy} to
 *   get a {@link BluetoothQualityReport.BqrVsScoChoppy} object.
 *   <li> For Connect fail event, you can call {@link #getBqrCommon} to get a
 *   {@link BluetoothQualityReport.BqrCommon} object, and call {@link #getBqrVsCommon} to get a
 *   {@link BluetoothQualityReport.BqrVsCommon} object, and call {@link #getBqrVsConnectFail} to
 *   get a {@link BluetoothQualityReport.BqrVsConnectFail} object.
 * </ul>
 *
 * @hide
 */
public final class BluetoothQualityReport implements Parcelable {
    private static final String TAG = "BluetoothQualityReport";

    public static final int QUALITY_REPORT_ID_MONITOR = 0x01;
    public static final int QUALITY_REPORT_ID_APPROACH_LSTO = 0x02;
    public static final int QUALITY_REPORT_ID_A2DP_CHOPPY = 0x03;
    public static final int QUALITY_REPORT_ID_SCO_CHOPPY = 0x04;
    /* Vendor Specific Report IDs from 0x20 */
    public static final int QUALITY_REPORT_ID_CONN_FAIL = 0x20;

    private String mAddr;
    private int mLmpVer;
    private int mLmpSubVer;
    private int mManufacturerId;
    private String mName;
    private int mBluetoothClass;

    private BqrCommon mBqrCommon;

    private BqrVsCommon mBqrVsCommon;
    private BqrVsLsto mBqrVsLsto;
    private BqrVsA2dpChoppy mBqrVsA2dpChoppy;
    private BqrVsScoChoppy mBqrVsScoChoppy;
    private BqrVsConnectFail mBqrVsConnectFail;

    enum PacketType {
        INVALID, TYPE_ID, TYPE_NULL, TYPE_POLL, TYPE_FHS, TYPE_HV1, TYPE_HV2, TYPE_HV3,
        TYPE_DV, TYPE_EV3, TYPE_EV4, TYPE_EV5, TYPE_2EV3, TYPE_2EV5, TYPE_3EV3, TYPE_3EV5,
        TYPE_DM1, TYPE_DH1, TYPE_DM3, TYPE_DH3, TYPE_DM5, TYPE_DH5, TYPE_AUX1, TYPE_2DH1,
        TYPE_2DH3, TYPE_2DH5, TYPE_3DH1, TYPE_3DH3, TYPE_3DH5;

        private static PacketType[] sAllValues = values();

        static PacketType fromOrdinal(int n) {
            if (n < sAllValues.length) {
                return sAllValues[n];
            }
            return INVALID;
        }
    }

    enum ConnState {
        CONN_IDLE(0x00), CONN_ACTIVE(0x81), CONN_HOLD(0x02), CONN_SNIFF_IDLE(0x03),
        CONN_SNIFF_ACTIVE(0x84), CONN_SNIFF_MASTER_TRANSITION(0x85), CONN_PARK(0x06),
        CONN_PARK_PEND(0x47), CONN_UNPARK_PEND(0x08), CONN_UNPARK_ACTIVE(0x89),
        CONN_DISCONNECT_PENDING(0x4A), CONN_PAGING(0x0B), CONN_PAGE_SCAN(0x0C),
        CONN_LOCAL_LOOPBACK(0x0D), CONN_LE_ACTIVE(0x0E), CONN_ANT_ACTIVE(0x0F),
        CONN_TRIGGER_SCAN(0x10), CONN_RECONNECTING(0x11), CONN_SEMI_CONN(0x12);

        private int mValue;
        private static ConnState[] sAllStates = values();

        private ConnState(int val) {
            mValue = val;
        }

        public static String getName(int val) {
            for (ConnState state: sAllStates) {
                if (state.mValue == val) {
                    return state.toString();
                }
            }
            return "INVALID";
        }
    }

    enum LinkQuality {
        ULTRA_HIGH, HIGH, STANDARD, MEDIUM, LOW, INVALID;

        private static LinkQuality[] sAllValues = values();

        static LinkQuality fromOrdinal(int n) {
            if (n < sAllValues.length - 1) {
                return sAllValues[n];
            }
            return INVALID;
        }
    }

    enum AirMode {
        uLaw, aLaw, CVSD, transparent_msbc, INVALID;

        private static AirMode[] sAllValues = values();

        static AirMode fromOrdinal(int n) {
            if (n < sAllValues.length - 1) {
                return sAllValues[n];
            }
            return INVALID;
        }
    }

    public BluetoothQualityReport(String remoteAddr, int lmpVer, int lmpSubVer,
            int manufacturerId, String remoteName, int remoteCoD, byte[] rawData) {
        if (!BluetoothAdapter.checkBluetoothAddress(remoteAddr)) {
            Log.d(TAG, "remote addr is invalid");
            mAddr = "00:00:00:00:00:00";
        } else {
            mAddr = remoteAddr;
        }

        mLmpVer = lmpVer;
        mLmpSubVer = lmpSubVer;
        mManufacturerId = manufacturerId;
        if (remoteName == null) {
            Log.d(TAG, "remote name is null");
            mName = "";
        } else {
            mName = remoteName;
        }
        mBluetoothClass = remoteCoD;

        mBqrCommon = new BqrCommon(rawData, 0);

        mBqrVsCommon = new BqrVsCommon(rawData, BqrCommon.BQR_COMMON_LEN);
        int id = mBqrCommon.getQualityReportId();
        if (id == QUALITY_REPORT_ID_MONITOR)
            return;

        int vsPartOffset = BqrCommon.BQR_COMMON_LEN + mBqrVsCommon.getLength();
        if (id == QUALITY_REPORT_ID_APPROACH_LSTO) {
            mBqrVsLsto = new BqrVsLsto(rawData, vsPartOffset);
        } else if (id == QUALITY_REPORT_ID_A2DP_CHOPPY) {
            mBqrVsA2dpChoppy = new BqrVsA2dpChoppy(rawData, vsPartOffset);
        } else if (id == QUALITY_REPORT_ID_SCO_CHOPPY) {
            mBqrVsScoChoppy = new BqrVsScoChoppy(rawData, vsPartOffset);
        } else if (id == QUALITY_REPORT_ID_CONN_FAIL) {
            mBqrVsConnectFail = new BqrVsConnectFail(rawData, vsPartOffset);
        } else {
            throw new IllegalArgumentException(TAG + ": unkown quality report id:" + id);
        }
    }

    private BluetoothQualityReport(Parcel in) {
        mBqrCommon = new BqrCommon(in);
        mAddr = in.readString();
        mLmpVer = in.readInt();
        mLmpSubVer = in.readInt();
        mManufacturerId = in.readInt();
        mName = in.readString();
        mBluetoothClass = in.readInt();

        mBqrVsCommon = new BqrVsCommon(in);
        int id = mBqrCommon.getQualityReportId();
        if (id == QUALITY_REPORT_ID_APPROACH_LSTO) {
            mBqrVsLsto = new BqrVsLsto(in);
        } else if (id == QUALITY_REPORT_ID_A2DP_CHOPPY) {
            mBqrVsA2dpChoppy = new BqrVsA2dpChoppy(in);
        } else if (id == QUALITY_REPORT_ID_SCO_CHOPPY) {
            mBqrVsScoChoppy = new BqrVsScoChoppy(in);
        } else if (id == QUALITY_REPORT_ID_CONN_FAIL) {
            mBqrVsConnectFail = new BqrVsConnectFail(in);
        }
    }

    /**
     * Get the quality report id.
     * @return the id, is one of {@link #QUALITY_REPORT_ID_MONITOR},
     *         {@link #QUALITY_REPORT_ID_APPROACH_LSTO}, {@link #QUALITY_REPORT_ID_A2DP_CHOPPY},
     *         {@link #QUALITY_REPORT_ID_SCO_CHOPPY}, {@link #QUALITY_REPORT_ID_CONN_FAIL}.
     */
    public int getQualityReportId() {
        return mBqrCommon.getQualityReportId();
    }

    /**
     * Get the string of the quality report id.
     * @return the string of the id.
     */
    public String getQualityReportIdStr() {
        int id = mBqrCommon.getQualityReportId();
        switch (id) {
            case QUALITY_REPORT_ID_MONITOR:
                return "Quality monitor";
            case QUALITY_REPORT_ID_APPROACH_LSTO:
                return "Approaching LSTO";
            case QUALITY_REPORT_ID_A2DP_CHOPPY:
                return "A2DP choppy";
            case QUALITY_REPORT_ID_SCO_CHOPPY:
                return "SCO choppy";
            case QUALITY_REPORT_ID_CONN_FAIL:
                return "Connect fail";
            default:
                return "INVALID";
        }
    }

    /**
     * Get bluetooth address of remote device in this report.
     * @return bluetooth address of remote device.
     */
    public String getAddress() {
        return mAddr;
    }

    /**
     * Get LMP version of remote device in this report.
     * @return LMP version of remote device.
     */
    public int getLmpVersion() {
        return mLmpVer;
    }

    /**
     * Get LMP subVersion of remote device in this report.
     * @return LMP subVersion of remote device.
     */
    public int getLmpSubVersion() {
        return mLmpSubVer;
    }

    /**
     * Get manufacturer id of remote device in this report.
     * @return manufacturer id of remote device.
     */
    public int getManufacturerId() {
        return mManufacturerId;
    }

    /**
     * Get the name of remote device in this report.
     * @return the name of remote device.
     */
    public String getName() {
        return mName;
    }

    /**
     * Get the class of remote device in this report.
     * @return the class of remote device.
     */
    public int getBluetoothClass() {
        return mBluetoothClass;
    }

    /**
     * Get the {@link BluetoothQualityReport.BqrCommon} object.
     * @return the {@link BluetoothQualityReport.BqrCommon} object.
     */
    public BqrCommon getBqrCommon() {
        return mBqrCommon;
    }

    /**
     * Get the {@link BluetoothQualityReport.BqrVsCommon} object.
     * @return the {@link BluetoothQualityReport.BqrVsCommon} object.
     */
    public BqrVsCommon getBqrVsCommon() {
        return mBqrVsCommon;
    }

    /**
     * Get the {@link BluetoothQualityReport.BqrVsLsto} object.
     * @return the {@link BluetoothQualityReport.BqrVsLsto} object
     *         or null if report id is not {@link #QUALITY_REPORT_ID_APPROACH_LSTO}.
     */
    public BqrVsLsto getBqrVsLsto() {
        return mBqrVsLsto;
    }

    /**
     * Get the {@link BluetoothQualityReport.BqrVsA2dpChoppy} object.
     * @return the {@link BluetoothQualityReport.BqrVsA2dpChoppy} object
     *         or null if report id is not {@link #QUALITY_REPORT_ID_A2DP_CHOPPY}.
     */
    public BqrVsA2dpChoppy getBqrVsA2dpChoppy() {
        return mBqrVsA2dpChoppy;
    }

    /**
     * Get the {@link BluetoothQualityReport.BqrVsScoChoppy} object.
     * @return the {@link BluetoothQualityReport.BqrVsScoChoppy} object
     *         or null if report id is not {@link #QUALITY_REPORT_ID_SCO_CHOPPY}.
     */
    public BqrVsScoChoppy getBqrVsScoChoppy() {
        return mBqrVsScoChoppy;
    }

    /**
     * Get the {@link BluetoothQualityReport.BqrVsConnectFail} object.
     * @return the {@link BluetoothQualityReport.BqrVsConnectFail} object
     *         or null if report id is not {@link #QUALITY_REPORT_ID_CONN_FAIL}.
     */
    public BqrVsConnectFail getBqrVsConnectFail() {
        return mBqrVsConnectFail;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<BluetoothQualityReport> CREATOR =
        new Parcelable.Creator<BluetoothQualityReport>() {
            public BluetoothQualityReport createFromParcel(Parcel in) {
                return new BluetoothQualityReport(in);
            }

            public BluetoothQualityReport[] newArray(int size) {
                return new BluetoothQualityReport[size];
            }
        };

    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        mBqrCommon.writeToParcel(out, flags);
        out.writeString(mAddr);
        out.writeInt(mLmpVer);
        out.writeInt(mLmpSubVer);
        out.writeInt(mManufacturerId);
        out.writeString(mName);
        out.writeInt(mBluetoothClass);
        mBqrVsCommon.writeToParcel(out, flags);
        int id = mBqrCommon.getQualityReportId();
        if (id == QUALITY_REPORT_ID_APPROACH_LSTO) {
            mBqrVsLsto.writeToParcel(out, flags);
        } else if (id == QUALITY_REPORT_ID_A2DP_CHOPPY) {
            mBqrVsA2dpChoppy.writeToParcel(out, flags);
        } else if (id == QUALITY_REPORT_ID_SCO_CHOPPY) {
            mBqrVsScoChoppy.writeToParcel(out, flags);
        } else if (id == QUALITY_REPORT_ID_CONN_FAIL) {
            mBqrVsConnectFail.writeToParcel(out, flags);
        }
    }

    @Override
    public String toString() {
        String str;
        str =  "BQR: {\n"
             + "  mAddr: " + mAddr
             + ", mLmpVer: " + String.format("0x%02X", mLmpVer)
             + ", mLmpSubVer: " + String.format("0x%04X", mLmpSubVer)
             + ", mManufacturerId: " + String.format("0x%04X", mManufacturerId)
             + ", mName: " + mName
             + ", mBluetoothClass: " + String.format("0x%X", mBluetoothClass)
             + ",\n"
             + mBqrCommon + "\n"
             + mBqrVsCommon + "\n";

        int id = mBqrCommon.getQualityReportId();
        if (id == QUALITY_REPORT_ID_APPROACH_LSTO) {
            str += mBqrVsLsto + "\n}";
        } else if (id == QUALITY_REPORT_ID_A2DP_CHOPPY) {
            str += mBqrVsA2dpChoppy + "\n}";
        } else if (id == QUALITY_REPORT_ID_SCO_CHOPPY) {
            str += mBqrVsScoChoppy + "\n}";
        } else if (id == QUALITY_REPORT_ID_CONN_FAIL) {
            str += mBqrVsConnectFail + "\n}";
        } else if (id == QUALITY_REPORT_ID_MONITOR) {
            str += "}";
        }

        return str;
    }

    /**
     * This class provides the public APIs to access the common part of BQR event.
     */
    public class BqrCommon implements Parcelable {
        private static final String TAG = BluetoothQualityReport.TAG + ".BqrCommon";
        static final int BQR_COMMON_LEN = 48;

        private int mQualityReportId;
        private int mPacketType;
        private int mConnectionHandle;
        private int mConnectionRole;
        private int mTxPowerLevel;
        private int mRssi;
        private int mSnr;
        private int mUnusedAfhChannelCount;
        private int mAfhSelectUnidealChannelCount;
        private int mLsto;
        private long mPiconetClock;
        private long mRetransmissionCount;
        private long mNoRxCount;
        private long mNakCount;
        private long mLastTxAckTimestamp;
        private long mFlowOffCount;
        private long mLastFlowOnTimestamp;
        private long mOverflowCount;
        private long mUnderflowCount;

        private BqrCommon(byte[] rawData, int offset) {
            if (rawData == null || rawData.length < offset + BQR_COMMON_LEN) {
                throw new IllegalArgumentException(TAG + ": BQR raw data length is abnormal.");
            }

            ByteBuffer bqrBuf = ByteBuffer.wrap(rawData, offset, rawData.length - offset)
                                          .asReadOnlyBuffer();
            bqrBuf.order(ByteOrder.LITTLE_ENDIAN);

            mQualityReportId = bqrBuf.get() & 0xFF;
            mPacketType = bqrBuf.get() & 0xFF;
            mConnectionHandle = bqrBuf.getShort() & 0xFFFF;
            mConnectionRole = bqrBuf.get() & 0xFF;
            mTxPowerLevel = bqrBuf.get() & 0xFF;
            mRssi = bqrBuf.get();
            mSnr = bqrBuf.get();
            mUnusedAfhChannelCount = bqrBuf.get() & 0xFF;
            mAfhSelectUnidealChannelCount = bqrBuf.get() & 0xFF;
            mLsto = bqrBuf.getShort() & 0xFFFF;
            mPiconetClock = bqrBuf.getInt() & 0xFFFFFFFFL;
            mRetransmissionCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mNoRxCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mNakCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mLastTxAckTimestamp = bqrBuf.getInt() & 0xFFFFFFFFL;
            mFlowOffCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mLastFlowOnTimestamp = bqrBuf.getInt() & 0xFFFFFFFFL;
            mOverflowCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mUnderflowCount = bqrBuf.getInt() & 0xFFFFFFFFL;
        }

        private BqrCommon(Parcel in) {
            mQualityReportId = in.readInt();
            mPacketType = in.readInt();
            mConnectionHandle = in.readInt();
            mConnectionRole = in.readInt();
            mTxPowerLevel = in.readInt();
            mRssi = in.readInt();
            mSnr = in.readInt();
            mUnusedAfhChannelCount = in.readInt();
            mAfhSelectUnidealChannelCount = in.readInt();
            mLsto = in.readInt();
            mPiconetClock = in.readLong();
            mRetransmissionCount = in.readLong();
            mNoRxCount = in.readLong();
            mNakCount = in.readLong();
            mLastTxAckTimestamp = in.readLong();
            mFlowOffCount = in.readLong();
            mLastFlowOnTimestamp = in.readLong();
            mOverflowCount = in.readLong();
            mUnderflowCount = in.readLong();
        }

        int getQualityReportId() {
            return mQualityReportId;
        }

        /**
         * Get the packet type of the connection.
         * @return the packet type.
         */
        public int getPacketType() {
            return mPacketType;
        }

        /**
         * Get the string of packet type
         * @return the string of packet type.
         */
        public String getPacketTypeStr() {
            PacketType type = PacketType.fromOrdinal(mPacketType);
            return type.toString();
        }

        /**
         * Get the connecton handle of the connection
         * @return the connecton handle.
         */
        public int getConnectionHandle() {
            return mConnectionHandle;
        }

        /**
         * Get the connecton Role of the connection, "Master" or "Slave".
         * @return the connecton Role.
         */
        public String getConnectionRole() {
            if (mConnectionRole == 0) {
                return "Master";
            } else if (mConnectionRole == 1) {
                return "Slave";
            } else {
                return "INVALID:" + mConnectionRole;
            }
        }

        /**
         * Get the current transmit power level for the connection.
         * @return the TX power level.
         */
        public int getTxPowerLevel() {
            return mTxPowerLevel;
        }

        /**
         * Get the Received Signal Strength Indication (RSSI) value for the connection.
         * @return the RSSI.
         */
        public int getRssi() {
            return mRssi;
        }

        /**
         * get the Signal-to-Noise Ratio (SNR) value for the connection.
         * @return the SNR.
         */
        public int getSnr() {
            return mSnr;
        }

        /**
         * Get the number of unused channels in AFH_channel_map.
         * @return the number of unused channels.
         */
        public int getUnusedAfhChannelCount() {
            return mUnusedAfhChannelCount;
        }

        /**
         * Get the number of the channels which are interfered and quality is
         * bad but are still selected for AFH.
         * @return the number of the selected unideal channels.
         */
        public int getAfhSelectUnidealChannelCount() {
            return mAfhSelectUnidealChannelCount;
        }

        /**
         * Get the current link supervision timeout setting.
         * time_ms: N * 0.625 ms (1 slot).
         * @return link supervision timeout value.
         */
        public int getLsto() {
            return mLsto;
        }

        /**
         * Get the piconet clock for the specified Connection_Handle.
         * time_ms: N * 0.3125 ms (1 Bluetooth Clock).
         * @return the piconet clock.
         */
        public long getPiconetClock() {
            return mPiconetClock;
        }

        /**
         * Get the count of retransmission.
         * @return the count of retransmission.
         */
        public long getRetransmissionCount() {
            return mRetransmissionCount;
        }

        /**
         * Get the count of no RX.
         * @return the count of no RX.
         */
        public long getNoRxCount() {
            return mNoRxCount;
        }

        /**
         * Get the count of NAK(Negative Acknowledge).
         * @return the count of NAK.
         */
        public long getNakCount() {
            return mNakCount;
        }

        /**
         * Get the timestamp of last TX ACK.
         * time_ms: N * 0.3125 ms (1 Bluetooth Clock).
         * @return the timestamp of last TX ACK.
         */
        public long getLastTxAckTimestamp() {
            return mLastTxAckTimestamp;
        }

        /**
         * Get the count of flow-off.
         * @return the count of flow-off.
         */
        public long getFlowOffCount() {
            return mFlowOffCount;
        }

        /**
         * Get the timestamp of last flow-on.
         * @return the timestamp of last flow-on.
         */
        public long getLastFlowOnTimestamp() {
            return mLastFlowOnTimestamp;
        }

        /**
         * Get the buffer overflow count (how many bytes of TX data are dropped) since the
         * last event.
         * @return the buffer overflow count.
         */
        public long getOverflowCount() {
            return mOverflowCount;
        }

        /**
         * Get the buffer underflow count (in byte).
         * @return the buffer underflow count.
         */
        public long getUnderflowCount() {
            return mUnderflowCount;
        }

        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mQualityReportId);
            dest.writeInt(mPacketType);
            dest.writeInt(mConnectionHandle);
            dest.writeInt(mConnectionRole);
            dest.writeInt(mTxPowerLevel);
            dest.writeInt(mRssi);
            dest.writeInt(mSnr);
            dest.writeInt(mUnusedAfhChannelCount);
            dest.writeInt(mAfhSelectUnidealChannelCount);
            dest.writeInt(mLsto);
            dest.writeLong(mPiconetClock);
            dest.writeLong(mRetransmissionCount);
            dest.writeLong(mNoRxCount);
            dest.writeLong(mNakCount);
            dest.writeLong(mLastTxAckTimestamp);
            dest.writeLong(mFlowOffCount);
            dest.writeLong(mLastFlowOnTimestamp);
            dest.writeLong(mOverflowCount);
            dest.writeLong(mUnderflowCount);
        }

        @Override
        public String toString() {
            String str;
            str =  "  BqrCommon: {\n"
                 + "    mQualityReportId: " + BluetoothQualityReport.this.getQualityReportIdStr()
                                            + "(" + String.format("0x%02X", mQualityReportId) + ")"
                 + ", mPacketType: " + getPacketTypeStr()
                                     + "(" + String.format("0x%02X", mPacketType) + ")"
                 + ", mConnectionHandle: " + String.format("0x%04X", mConnectionHandle)
                 + ", mConnectionRole: " + getConnectionRole() + "(" + mConnectionRole + ")"
                 + ", mTxPowerLevel: " + mTxPowerLevel
                 + ", mRssi: " + mRssi
                 + ", mSnr: " + mSnr
                 + ", mUnusedAfhChannelCount: " + mUnusedAfhChannelCount
                 + ",\n"
                 + "    mAfhSelectUnidealChannelCount: " + mAfhSelectUnidealChannelCount
                 + ", mLsto: " + mLsto
                 + ", mPiconetClock: " + String.format("0x%08X", mPiconetClock)
                 + ", mRetransmissionCount: " + mRetransmissionCount
                 + ", mNoRxCount: " + mNoRxCount
                 + ", mNakCount: " + mNakCount
                 + ", mLastTxAckTimestamp: " + String.format("0x%08X", mLastTxAckTimestamp)
                 + ", mFlowOffCount: " + mFlowOffCount
                 + ",\n"
                 + "    mLastFlowOnTimestamp: " + String.format("0x%08X", mLastFlowOnTimestamp)
                 + ", mOverflowCount: " + mOverflowCount
                 + ", mUnderflowCount: " + mUnderflowCount
                 + "\n  }";

            return str;
        }

    }

    /**
     * This class provides the public APIs to access the vendor specific common part of
     * BQR event.
     */
    public class BqrVsCommon implements Parcelable {
        private static final String TAG = BluetoothQualityReport.TAG + ".BqrVsCommon";
        private static final int BQR_VS_COMMON_LEN = 6 + 1;

        private String mAddr;
        private int mCalFailedItemCount;

        private BqrVsCommon(byte[] rawData, int offset) {
            if (rawData == null || rawData.length < offset + BQR_VS_COMMON_LEN) {
                throw new IllegalArgumentException(TAG + ": BQR raw data length is abnormal.");
            }

            ByteBuffer bqrBuf = ByteBuffer.wrap(rawData, offset, rawData.length - offset)
                                          .asReadOnlyBuffer();
            bqrBuf.order(ByteOrder.LITTLE_ENDIAN);

            mAddr = String.format("%02X:%02X:%02X:%02X:%02X:%02X", bqrBuf.get(offset+5),
                    bqrBuf.get(offset+4), bqrBuf.get(offset+3), bqrBuf.get(offset+2),
                    bqrBuf.get(offset+1), bqrBuf.get(offset+0));
            bqrBuf.position(offset+6);
            mCalFailedItemCount = bqrBuf.get() & 0xFF;
        }

        private BqrVsCommon(Parcel in) {
            mAddr = in.readString();
            mCalFailedItemCount = in.readInt();
        }

        /**
         * Get the count of calibration failed items.
         * @return the count of calibration failure.
         */
        public int getCalFailedItemCount() {
            return mCalFailedItemCount;
        }

        int getLength() {
            return BQR_VS_COMMON_LEN;
        }

        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(mAddr);
            dest.writeInt(mCalFailedItemCount);
        }

        @Override
        public String toString() {
            String str;
            str =  "  BqrVsCommon: {\n"
                 + "    mAddr: " + mAddr
                 + ", mCalFailedItemCount: " + mCalFailedItemCount
                 + "\n  }";

            return str;
        }
    }

    /**
     * This class provides the public APIs to access the vendor specific part of
     * Approaching LSTO event.
     */
    public class BqrVsLsto implements Parcelable {
        private static final String TAG = BluetoothQualityReport.TAG + ".BqrVsLsto";

        private int mConnState;
        private long mBasebandStats;
        private long mSlotsUsed;
        private int mCxmDenials;
        private int mTxSkipped;
        private int mRfLoss;
        private long mNativeClock;
        private long mLastTxAckTimestamp;

        private BqrVsLsto(byte[] rawData, int offset) {
            if (rawData == null || rawData.length <= offset) {
                throw new IllegalArgumentException(TAG + ": BQR raw data length is abnormal.");
            }

            ByteBuffer bqrBuf = ByteBuffer.wrap(rawData, offset, rawData.length - offset)
                                          .asReadOnlyBuffer();
            bqrBuf.order(ByteOrder.LITTLE_ENDIAN);

            mConnState = bqrBuf.get() & 0xFF;
            mBasebandStats = bqrBuf.getInt() & 0xFFFFFFFFL;
            mSlotsUsed = bqrBuf.getInt() & 0xFFFFFFFFL;
            mCxmDenials = bqrBuf.getShort() & 0xFFFF;
            mTxSkipped = bqrBuf.getShort() & 0xFFFF;
            mRfLoss = bqrBuf.getShort() & 0xFFFF;
            mNativeClock = bqrBuf.getInt() & 0xFFFFFFFFL;
            mLastTxAckTimestamp = bqrBuf.getInt() & 0xFFFFFFFFL;
        }

        private BqrVsLsto(Parcel in) {
            mConnState = in.readInt();
            mBasebandStats = in.readLong();
            mSlotsUsed = in.readLong();
            mCxmDenials = in.readInt();
            mTxSkipped = in.readInt();
            mRfLoss = in.readInt();
            mNativeClock = in.readLong();
            mLastTxAckTimestamp = in.readLong();
        }

        /**
         * Get the conn state of sco.
         * @return the conn state.
         */
        public int getConnState() {
            return mConnState;
        }

        /**
         * Get the string of conn state of sco.
         * @return the string of conn state.
         */
        public String getConnStateStr() {
            return ConnState.getName(mConnState);
        }

        /**
         * Get the baseband statistics.
         * @return the baseband statistics.
         */
        public long getBasebandStats() {
            return mBasebandStats;
        }

        /**
         * Get the count of slots allocated for current connection.
         * @return the count of slots allocated for current connection.
         */
        public long getSlotsUsed() {
            return mSlotsUsed;
        }

        /**
         * Get the count of Coex denials.
         * @return the count of CXM denials.
         */
        public int getCxmDenials() {
            return mCxmDenials;
        }

        /**
         * Get the count of TX skipped when no poll from remote device.
         * @return the count of TX skipped.
         */
        public int getTxSkipped() {
            return mTxSkipped;
        }

        /**
         * Get the count of RF loss.
         * @return the count of RF loss.
         */
        public int getRfLoss() {
            return mRfLoss;
        }

        /**
         * Get the timestamp when issue happened.
         * time_ms: N * 0.3125 ms (1 Bluetooth Clock).
         * @return the timestamp when issue happened.
         */
        public long getNativeClock() {
            return mNativeClock;
        }

        /**
         * Get the timestamp of last TX ACK.
         * time_ms: N * 0.3125 ms (1 Bluetooth Clock).
         * @return the timestamp of last TX ACK.
         */
        public long getLastTxAckTimestamp() {
            return mLastTxAckTimestamp;
        }

        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mConnState);
            dest.writeLong(mBasebandStats);
            dest.writeLong(mSlotsUsed);
            dest.writeInt(mCxmDenials);
            dest.writeInt(mTxSkipped);
            dest.writeInt(mRfLoss);
            dest.writeLong(mNativeClock);
            dest.writeLong(mLastTxAckTimestamp);
        }

        @Override
        public String toString() {
            String str;
            str =  "  BqrVsLsto: {\n"
                 + "    mConnState: " + getConnStateStr()
                                      + "(" + String.format("0x%02X", mConnState) + ")"
                 + ", mBasebandStats: " + String.format("0x%08X", mBasebandStats)
                 + ", mSlotsUsed: " + mSlotsUsed
                 + ", mCxmDenials: " + mCxmDenials
                 + ", mTxSkipped: " + mTxSkipped
                 + ", mRfLoss: " + mRfLoss
                 + ", mNativeClock: " + String.format("0x%08X", mNativeClock)
                 + ", mLastTxAckTimestamp: " + String.format("0x%08X", mLastTxAckTimestamp)
                 + "\n  }";

            return str;
        }
    }

    /**
     * This class provides the public APIs to access the vendor specific part of
     * A2dp choppy event.
     */
    public class BqrVsA2dpChoppy implements Parcelable {
        private static final String TAG = BluetoothQualityReport.TAG + ".BqrVsA2dpChoppy";

        private long mArrivalTime;
        private long mScheduleTime;
        private int mGlitchCount;
        private int mTxCxmDenials;
        private int mRxCxmDenials;
        private int mAclTxQueueLength;
        private int mLinkQuality;

        private BqrVsA2dpChoppy(byte[] rawData, int offset) {
            if (rawData == null || rawData.length <= offset) {
                throw new IllegalArgumentException(TAG + ": BQR raw data length is abnormal.");
            }

            ByteBuffer bqrBuf = ByteBuffer.wrap(rawData, offset, rawData.length - offset)
                                          .asReadOnlyBuffer();
            bqrBuf.order(ByteOrder.LITTLE_ENDIAN);

            mArrivalTime = bqrBuf.getInt() & 0xFFFFFFFFL;
            mScheduleTime = bqrBuf.getInt() & 0xFFFFFFFFL;
            mGlitchCount = bqrBuf.getShort() & 0xFFFF;
            mTxCxmDenials = bqrBuf.getShort() & 0xFFFF;
            mRxCxmDenials = bqrBuf.getShort() & 0xFFFF;
            mAclTxQueueLength = bqrBuf.get() & 0xFF;
            mLinkQuality = bqrBuf.get() & 0xFF;
        }

        private BqrVsA2dpChoppy(Parcel in) {
            mArrivalTime = in.readLong();
            mScheduleTime = in.readLong();
            mGlitchCount = in.readInt();
            mTxCxmDenials = in.readInt();
            mRxCxmDenials = in.readInt();
            mAclTxQueueLength = in.readInt();
            mLinkQuality = in.readInt();
        }

        /**
         * Get the timestamp of a2dp packet arrived.
         * time_ms: N * 0.3125 ms (1 Bluetooth Clock).
         * @return the timestamp of a2dp packet arrived.
         */
        public long getArrivalTime() {
            return mArrivalTime;
        }

        /**
         * Get the timestamp of a2dp packet scheduled.
         * time_ms: N * 0.3125 ms (1 Bluetooth Clock).
         * @return the timestamp of a2dp packet scheduled.
         */
        public long getScheduleTime() {
            return mScheduleTime;
        }

        /**
         * Get the a2dp glitch count since the last event.
         * @return the a2dp glitch count.
         */
        public int getGlitchCount() {
            return mGlitchCount;
        }

        /**
         * Get the count of Coex TX denials.
         * @return the count of Coex TX denials.
         */
        public int getTxCxmDenials() {
            return mTxCxmDenials;
        }

        /**
         * Get the count of Coex RX denials.
         * @return the count of Coex RX denials.
         */
        public int getRxCxmDenials() {
            return mRxCxmDenials;
        }

        /**
         * Get the ACL queue length which are pending TX in FW.
         * @return the ACL queue length.
         */
        public int getAclTxQueueLength() {
            return mAclTxQueueLength;
        }

        /**
         * Get the link quality for the current connection.
         * @return the link quality.
         */
        public int getLinkQuality() {
            return mLinkQuality;
        }

        /**
         * Get the string of link quality for the current connection.
         * @return the string of link quality.
         */
        public String getLinkQualityStr() {
            LinkQuality q = LinkQuality.fromOrdinal(mLinkQuality);
            return q.toString();
        }

        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeLong(mArrivalTime);
            dest.writeLong(mScheduleTime);
            dest.writeInt(mGlitchCount);
            dest.writeInt(mTxCxmDenials);
            dest.writeInt(mRxCxmDenials);
            dest.writeInt(mAclTxQueueLength);
            dest.writeInt(mLinkQuality);
        }

        @Override
        public String toString() {
            String str;
            str =  "  BqrVsA2dpChoppy: {\n"
                 + "    mArrivalTime: " + String.format("0x%08X", mArrivalTime)
                 + ", mScheduleTime: " + String.format("0x%08X", mScheduleTime)
                 + ", mGlitchCount: " + mGlitchCount
                 + ", mTxCxmDenials: " + mTxCxmDenials
                 + ", mRxCxmDenials: " + mRxCxmDenials
                 + ", mAclTxQueueLength: " + mAclTxQueueLength
                 + ", mLinkQuality: " + getLinkQualityStr()
                                      + "(" + String.format("0x%02X", mLinkQuality) + ")"
                 + "\n  }";

            return str;
        }

    }

    /**
     * This class provides the public APIs to access the vendor specific part of
     * SCO choppy event.
     */
    public class BqrVsScoChoppy implements Parcelable {
        private static final String TAG = BluetoothQualityReport.TAG + ".BqrVsScoChoppy";

        private int mGlitchCount;
        private int mIntervalEsco;
        private int mWindowEsco;
        private int mAirFormat;
        private int mInstanceCount;
        private int mTxCxmDenials;
        private int mRxCxmDenials;
        private int mTxAbortCount;
        private int mLateDispatch;
        private int mMicIntrMiss;
        private int mLpaIntrMiss;
        private int mSprIntrMiss;
        private int mPlcFillCount;
        private int mPlcDiscardCount;

        private BqrVsScoChoppy(byte[] rawData, int offset) {
            if (rawData == null || rawData.length <= offset) {
                throw new IllegalArgumentException(TAG + ": BQR raw data length is abnormal.");
            }

            ByteBuffer bqrBuf = ByteBuffer.wrap(rawData, offset, rawData.length - offset)
                                          .asReadOnlyBuffer();
            bqrBuf.order(ByteOrder.LITTLE_ENDIAN);

            mGlitchCount = bqrBuf.getShort() & 0xFFFF;
            mIntervalEsco = bqrBuf.get() & 0xFF;
            mWindowEsco = bqrBuf.get() & 0xFF;
            mAirFormat = bqrBuf.get() & 0xFF;
            mInstanceCount = bqrBuf.getShort() & 0xFFFF;
            mTxCxmDenials = bqrBuf.getShort() & 0xFFFF;
            mRxCxmDenials = bqrBuf.getShort() & 0xFFFF;
            mTxAbortCount = bqrBuf.getShort() & 0xFFFF;
            mLateDispatch = bqrBuf.getShort() & 0xFFFF;
            mMicIntrMiss = bqrBuf.getShort() & 0xFFFF;
            mLpaIntrMiss = bqrBuf.getShort() & 0xFFFF;
            mSprIntrMiss = bqrBuf.getShort() & 0xFFFF;
            mPlcFillCount = bqrBuf.getShort() & 0xFFFF;
            mPlcDiscardCount = bqrBuf.getShort() & 0xFFFF;
        }

        private BqrVsScoChoppy(Parcel in) {
            mGlitchCount = in.readInt();
            mIntervalEsco = in.readInt();
            mWindowEsco = in.readInt();
            mAirFormat = in.readInt();
            mInstanceCount = in.readInt();
            mTxCxmDenials = in.readInt();
            mRxCxmDenials = in.readInt();
            mTxAbortCount = in.readInt();
            mLateDispatch = in.readInt();
            mMicIntrMiss = in.readInt();
            mLpaIntrMiss = in.readInt();
            mSprIntrMiss = in.readInt();
            mPlcFillCount = in.readInt();
            mPlcDiscardCount = in.readInt();
        }

        /**
         * Get the sco glitch count since the last event.
         * @return the sco glitch count.
         */
        public int getGlitchCount() {
            return mGlitchCount;
        }

        /**
         * Get ESCO interval in slots. It is the value of Transmission_Interval parameter in
         * Synchronous Connection Complete event.
         * @return ESCO interval in slots.
         */
        public int getIntervalEsco() {
            return mIntervalEsco;
        }

        /**
         * Get ESCO window in slots. It is the value of Retransmission Window parameter in
         * Synchronous Connection Complete event.
         * @return ESCO window in slots.
         */
        public int getWindowEsco() {
            return mWindowEsco;
        }

        /**
         * Get the air mode. It is the value of Air Mode parameter in
         * Synchronous Connection Complete event.
         * @return the air mode.
         */
        public int getAirFormat() {
            return mAirFormat;
        }

        /**
         * Get the string of air mode.
         * @return the string of air mode.
         */
        public String getAirFormatStr() {
            AirMode m = AirMode.fromOrdinal(mAirFormat);
            return m.toString();
        }

        /**
         * Get the xSCO instance count.
         * @return the xSCO instance count.
         */
        public int getInstanceCount() {
            return mInstanceCount;
        }

        /**
         * Get the count of Coex TX denials.
         * @return the count of Coex TX denials.
         */
        public int getTxCxmDenials() {
            return mTxCxmDenials;
        }

        /**
         * Get the count of Coex RX denials.
         * @return the count of Coex RX denials.
         */
        public int getRxCxmDenials() {
            return mRxCxmDenials;
        }

        /**
         * Get the count of sco packets aborted.
         * @return the count of sco packets aborted.
         */
        public int getTxAbortCount() {
            return mTxAbortCount;
        }

        /**
         * Get the count of sco packets dispatched late.
         * @return the count of sco packets dispatched late.
         */
        public int getLateDispatch() {
            return mLateDispatch;
        }

        /**
         * Get the count of missed Mic interrrupts.
         * @return the count of missed Mic interrrupts.
         */
        public int getMicIntrMiss() {
            return mMicIntrMiss;
        }

        /**
         * Get the count of missed LPA interrrupts.
         * @return the count of missed LPA interrrupts.
         */
        public int getLpaIntrMiss() {
            return mLpaIntrMiss;
        }

        /**
         * Get the count of missed Speaker interrrupts.
         * @return the count of missed Speaker interrrupts.
         */
        public int getSprIntrMiss() {
            return mSprIntrMiss;
        }

        /**
         * Get the count of packet loss concealment filled.
         * @return the count of packet loss concealment filled.
         */
        public int getPlcFillCount() {
            return mPlcFillCount;
        }

        /**
         * Get the count of packet loss concealment discarded.
         * @return the count of packet loss concealment discarded.
         */
        public int getPlcDiscardCount() {
            return mPlcDiscardCount;
        }

        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mGlitchCount);
            dest.writeInt(mIntervalEsco);
            dest.writeInt(mWindowEsco);
            dest.writeInt(mAirFormat);
            dest.writeInt(mInstanceCount);
            dest.writeInt(mTxCxmDenials);
            dest.writeInt(mRxCxmDenials);
            dest.writeInt(mTxAbortCount);
            dest.writeInt(mLateDispatch);
            dest.writeInt(mMicIntrMiss);
            dest.writeInt(mLpaIntrMiss);
            dest.writeInt(mSprIntrMiss);
            dest.writeInt(mPlcFillCount);
            dest.writeInt(mPlcDiscardCount);
        }

        @Override
        public String toString() {
            String str;
            str =  "  BqrVsScoChoppy: {\n"
                 + "    mGlitchCount: " + mGlitchCount
                 + ", mIntervalEsco: " + mIntervalEsco
                 + ", mWindowEsco: " + mWindowEsco
                 + ", mAirFormat: " + getAirFormatStr()
                                    + "(" + String.format("0x%02X", mAirFormat) + ")"
                 + ", mInstanceCount: " + mInstanceCount
                 + ", mTxCxmDenials: " + mTxCxmDenials
                 + ", mRxCxmDenials: " + mRxCxmDenials
                 + ", mTxAbortCount: " + mTxAbortCount
                 + ",\n"
                 + "    mLateDispatch: " + mLateDispatch
                 + ", mMicIntrMiss: " + mMicIntrMiss
                 + ", mLpaIntrMiss: " + mLpaIntrMiss
                 + ", mSprIntrMiss: " + mSprIntrMiss
                 + ", mPlcFillCount: " + mPlcFillCount
                 + ", mPlcDiscardCount: " + mPlcDiscardCount
                 + "\n  }";

            return str;
        }

    }

    /**
     * This class provides the public APIs to access the vendor specific part of
     * Connect fail event.
     */
    public class BqrVsConnectFail implements Parcelable {
        private static final String TAG = BluetoothQualityReport.TAG + ".BqrVsConnectFail";

        private int mFailReason;

        private BqrVsConnectFail(byte[] rawData, int offset) {
            if (rawData == null || rawData.length <= offset) {
                throw new IllegalArgumentException(TAG + ": BQR raw data length is abnormal.");
            }

            ByteBuffer bqrBuf = ByteBuffer.wrap(rawData, offset, rawData.length - offset)
                                          .asReadOnlyBuffer();
            bqrBuf.order(ByteOrder.LITTLE_ENDIAN);

            mFailReason = bqrBuf.get() & 0xFF;
        }

        private BqrVsConnectFail(Parcel in) {
            mFailReason = in.readInt();
        }

        /**
         * Get the fail reason.
         * @return the fail reason.
         */
        public int getFailReason() {
            return mFailReason;
        }

        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mFailReason);
        }

        @Override
        public String toString() {
            String str;
            str =  "  BqrVsConnectFail: {\n"
                 + "    mFailReason: " + String.format("0x%02X", mFailReason)
                 + "\n  }";

            return str;
        }
    }

}
