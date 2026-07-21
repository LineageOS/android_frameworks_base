/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.app.admin;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * @hide
 */
public final class PackageSetPolicyValue extends PolicyValue<Set<String>> {

    /**
     * Creates a PackageSetPolicyValue unless the input is null, in which case this method returns
     * null.
     */
    public static @Nullable PackageSetPolicyValue createIfNotNull(
            @Nullable Collection<String> packages) {
        return packages == null ? null : new PackageSetPolicyValue(new HashSet<>(packages));
    }

    /**
     * Creates a PackageSetPolicyValue unless the input is empty, in which case this method returns
     * null.
     */
    public static @Nullable PackageSetPolicyValue createIfNotEmpty(
            @NonNull Collection<String> packages) {
        return packages.isEmpty() ? null : new PackageSetPolicyValue(new HashSet<>(packages));
    }

    public PackageSetPolicyValue(@NonNull Set<String> value) {
        super(value);
        for (String packageName : value) {
            PolicySizeVerifier.enforceMaxPackageNameLength(packageName);
            if (packageName.contains(";")) {
                throw new IllegalArgumentException("Package name cannot contain ';'");
            }
        }
    }

    public PackageSetPolicyValue(Parcel source) {
        this(readValues(source));
    }

    private static Set<String> readValues(Parcel source) {
        Set<String> values = new HashSet<>();
        int size = source.readInt();
        for (int i = 0; i < size; i++) {
            values.add(source.readString());
        }
        return values;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PackageSetPolicyValue other = (PackageSetPolicyValue) o;
        return Objects.equals(getValue(), other.getValue());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getValue());
    }

    @Override
    public String toString() {
        return "PackageNameSetPolicyValue { " + getValue() + " }";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(getValue().size());
        for (String entry : getValue()) {
            dest.writeString(entry);
        }
    }

    @NonNull
    public static final Creator<PackageSetPolicyValue> CREATOR =
            new Creator<PackageSetPolicyValue>() {
                @Override
                public PackageSetPolicyValue createFromParcel(Parcel source) {
                    return new PackageSetPolicyValue(source);
                }

                @Override
                public PackageSetPolicyValue[] newArray(int size) {
                    return new PackageSetPolicyValue[size];
                }
            };
}
