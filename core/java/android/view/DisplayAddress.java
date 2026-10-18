/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.view;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

/** Display identifier that is stable across reboots.
 *
 * @hide
 */
// @TestApi without associated feature.
@SuppressLint({"UnflaggedApi", "ParcelNotFinal", "ParcelCreator"})
@TestApi
public abstract class DisplayAddress implements Parcelable {

    /**
     * @hide
     */
    public static final int INVALID_PORT = -1;
    /**
     * @hide
     */
    public static final int INVALID_DISPLAY_ID = -1;

    /**
     * Creates an address for a physical display given its stable ID.
     *
     * A physical display ID is stable if the display can be identified using EDID information.
     *
     * @param physicalDisplayId A physical display ID.
     * @return The {@link Physical} address.
     * @see com.android.server.display.DisplayControl#getPhysicalDisplayIds
     *
     * @hide
     */
    @NonNull
    public static Physical fromPhysicalDisplayId(long physicalDisplayId) {
        return new Physical(physicalDisplayId);
    }

    /**
     * Creates a DisplayAddress, of either type StablePhysical or Physical
     * If a Physical display address is created - the port parameter will be ignored, and
     * calculated from the physical display id instead.
     * @param physicalDisplayId used to identify the display uniquely.
     * @param port              the connection point of this display
     * @return DisplayAddress
     *
     * @hide
     */
    @NonNull
    public static DisplayAddress fromPhysicalDisplayId(long physicalDisplayId, int port,
            boolean stableEdidsFlag) {
        if (stableEdidsFlag) {
            return new StablePhysical(physicalDisplayId, port);
        } else {
            return new Physical(physicalDisplayId);
        }
    }

    /**
     * Creates an address for a physical display given its port and model.
     *
     * @param port A port in the range [0, 255].
     * @param model A positive integer, or {@code null} if the model cannot be identified.
     * @return The {@link Physical} address.
     *
     * @hide
     */
    @NonNull
    public static Physical fromPortAndModel(int port, Long model) {
        return new Physical(port, model);
    }

    /**
     * Creates an address for a network display given its MAC address.
     *
     * @param macAddress A MAC address in colon notation.
     * @return The {@link Network} address.
     *
     * @hide
     */
    @NonNull
    public static Network fromMacAddress(String macAddress) {
        return new Network(macAddress);
    }

    /**
     * The port of the display if the display is connected to a physical connector. If the display
     * is not physically connected to the device - e.g. when the display is a Network display -
     * then {@link #INVALID_PORT} is returned.
     * @return The port of the display.
     *
     * @hide
     */
    public int getPort() {
        return INVALID_PORT;
    }

    /**
     * If the display is not physically connected - it will not have a physical display id and
     * therefore {@link #INVALID_DISPLAY_ID} will be returned.
     * @return The physical display id of the display.
     *
     * @hide
     */
    @SuppressLint("UnflaggedApi") // @TestApi without associated feature.
    @TestApi
    public long getPhysicalDisplayId() {
        return INVALID_DISPLAY_ID;
    }

    /**
     * @param one first display address to compare
     * @param two second display address to compare
     * @return whether the displays are equal - if using Physical display addresses, allow ports to
     * be used instead to match. If using StablePhysical, match only their physical display ids.
     *
     * @hide
     */
    public static boolean matchInternalDisplays(DisplayAddress one, DisplayAddress two,
            boolean stableEdidsFlag) {
        if (stableEdidsFlag && one instanceof StablePhysical oneStable
                && two instanceof StablePhysical twoStable) {
            return (oneStable.getPhysicalDisplayId() == twoStable.getPhysicalDisplayId()) ||
                    (oneStable.getPort() == twoStable.getPort());
        }

        if (!stableEdidsFlag && one instanceof Physical onePhysical
                && two instanceof Physical twoPhysical) {
            return onePhysical.equals(twoPhysical) || Physical.isPortMatch(one, two);
        }

        return false;
    }

    /**
     * @hide
     */
    public static final class StablePhysical extends DisplayAddress {
        private final long mPhysicalDisplayId;
        private final int mPort;

        @Override
        public long getPhysicalDisplayId() {
            return mPhysicalDisplayId;
        }

        @Override
        public int getPort() {
            return mPort;
        }

        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof StablePhysical
                    && mPhysicalDisplayId == ((StablePhysical) other).mPhysicalDisplayId;
        }

        @Override
        public String toString() {
            final StringBuilder builder = new StringBuilder("StablePhysical{")
                    .append("id=").append(mPhysicalDisplayId)
                    .append(", port=").append(getPort());

            return builder.append("}").toString();
        }

        @Override
        public int hashCode() {
            return Long.hashCode(mPhysicalDisplayId);
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeLong(mPhysicalDisplayId);
            out.writeInt(mPort);
        }

        private StablePhysical(long physicalDisplayId, int port) {
            mPhysicalDisplayId = physicalDisplayId;
            mPort = port;
        }

        public static final @NonNull Parcelable.Creator<StablePhysical> CREATOR =
                new Parcelable.Creator<>() {
                    @Override
                    public StablePhysical createFromParcel(Parcel in) {
                        return new StablePhysical(in.readLong(), in.readInt());
                    }

                    @Override
                    public StablePhysical[] newArray(int size) {
                        return new StablePhysical[size];
                    }
                };
    }

    /**
     * Address for a physically connected display.
     *
     * A {@link Physical} address is represented by a 64-bit identifier combining the port and model
     * of a display. The port, located in the least significant byte, uniquely identifies a physical
     * connector on the device for display output like eDP or HDMI. The model, located in the upper
     * bits, uniquely identifies a display model across manufacturers by encoding EDID information.
     * While the port is always stable, the model may not be available if EDID identification is not
     * supported by the platform, in which case the address is not unique.
     *
     * @hide
     */
    public static final class Physical extends DisplayAddress {
        private static final long UNKNOWN_MODEL = 0;
        private static final int MODEL_SHIFT = 8;

        private final long mPhysicalDisplayId;

        /**
         * Stable display ID combining port and model.
         *
         * @return An ID in the range [0, 2^64) interpreted as signed.
         * @see com.android.server.display.DisplayControl#getPhysicalDisplayIds
         */
        @Override
        public long getPhysicalDisplayId() {
            return mPhysicalDisplayId;
        }

        /**
         * Physical port to which the display is connected.
         *
         * @return A port in the range [0, 255].
         */
        @Override
        public int getPort() {
            return (int) (mPhysicalDisplayId & 0xFF);
        }

        /**
         * Model identifier unique across manufacturers.
         *
         * @return A positive integer, or {@code null} if the model cannot be identified.
         */
        @Nullable
        public Long getModel() {
            final long model = mPhysicalDisplayId >>> MODEL_SHIFT;
            return model == UNKNOWN_MODEL ? null : model;
        }

        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof Physical
                    && mPhysicalDisplayId == ((Physical) other).mPhysicalDisplayId;
        }

        @Override
        public String toString() {
            final StringBuilder builder = new StringBuilder("Physical{")
                    .append("id=").append(mPhysicalDisplayId)
                    .append(", port=").append(getPort());

            final Long model = getModel();
            if (model != null) {
                builder.append(", model=0x").append(Long.toHexString(model));
            }

            return builder.append("}").toString();
        }

        @Override
        public int hashCode() {
            return Long.hashCode(mPhysicalDisplayId);
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeLong(mPhysicalDisplayId);
        }

        /**
         * This method is meant to check to see if the ports match
         * @param a1 Address to compare
         * @param a2 Address to compare
         *
         * @return true if the arguments have the same port, and at least one does not specify
         *         a model.
         */
        public static boolean isPortMatch(DisplayAddress a1, DisplayAddress a2) {
            // Both displays must be of type Physical
            if (!(a1 instanceof Physical p1 && a2 instanceof Physical p2)) {
                return false;
            }

            // If both addresses specify a model, fallback to a basic match check (which
            // also checks the port).
            if (p1.getModel() != null && p2.getModel() != null) {
                return p1.equals(p2);
            }
            return p1.getPort() == p2.getPort();
        }

        private Physical(long physicalDisplayId) {
            mPhysicalDisplayId = physicalDisplayId;
        }

        private Physical(int port, Long model) {
            if (port < 0 || port > 255) {
                throw new IllegalArgumentException("The port should be in the interval [0, 255]");
            }
            mPhysicalDisplayId = Integer.toUnsignedLong(port)
                    | (model == null ? UNKNOWN_MODEL : (model << MODEL_SHIFT));
        }

        public static final @NonNull Parcelable.Creator<Physical> CREATOR =
                new Parcelable.Creator<Physical>() {
                    @Override
                    public Physical createFromParcel(Parcel in) {
                        return new Physical(in.readLong());
                    }

                    @Override
                    public Physical[] newArray(int size) {
                        return new Physical[size];
                    }
                };
    }

    /**
     * Address for a network-connected display.
     *
     * @hide
     */
    public static final class Network extends DisplayAddress {
        private final String mMacAddress;

        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof Network && mMacAddress.equals(((Network) other).mMacAddress);
        }

        @Override
        public String toString() {
            return mMacAddress;
        }

        @Override
        public int hashCode() {
            return mMacAddress.hashCode();
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeString(mMacAddress);
        }

        private Network(String macAddress) {
            mMacAddress = macAddress;
        }

        public static final @NonNull Parcelable.Creator<Network> CREATOR =
                new Parcelable.Creator<Network>() {
                    @Override
                    public Network createFromParcel(Parcel in) {
                        return new Network(in.readString());
                    }

                    @Override
                    public Network[] newArray(int size) {
                        return new Network[size];
                    }
                };
    }

    /**
     * @hide
     */
    @SuppressLint("UnflaggedApi")
    @Override
    public int describeContents() {
        return 0;
    }
}
