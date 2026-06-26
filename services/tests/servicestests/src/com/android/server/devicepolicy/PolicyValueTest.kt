/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.server.devicepolicy
import android.app.admin.BundlePolicyValue
import android.app.admin.ListOfStringPolicyValue
import android.app.admin.PackageIdentifier
import android.app.admin.PackagePolicyValue
import android.app.admin.StringPolicyValue
import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.experimental.runners.Enclosed
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
@RunWith(Enclosed::class)
class PolicyValueTest {
    @RunWith(Parameterized::class)
    class UnsupportedBundleTypesTest(
        private val testName: String,
        private val bundle: Bundle
    ) {
        companion object {
            @JvmStatic
            @Parameterized.Parameters(name = "{0}")
            fun data(): Collection<Array<Any>> {
                return listOf(
                    arrayOf(
                        "Long",
                        Bundle().apply {
                            putLong("long_key", 123L)
                        }
                    ),
                    arrayOf(
                        "Double",
                        Bundle().apply {
                            putDouble("double_key", 1.23)
                        }
                    ),
                    arrayOf(
                        "Long in Bundle",
                        Bundle().apply {
                            putBundle("nested_bundle", Bundle().apply {
                                putLong("nested_long_key", 123L)
                            })
                        }
                    ),
                    arrayOf(
                        "non-bundle in Array",
                        Bundle().apply {
                            putParcelableArray("parcelable_array_key", arrayOf(Intent()))
                        }
                    )
                )
            }
        }
        @Test
        fun bundlePolicyValue_unsupportedTypes_throws() {
            assertThrows(IllegalArgumentException::class.java) {
                BundlePolicyValue(bundle)
            }
        }
    }
    @RunWith(AndroidJUnit4::class)
    class StandardTests {
        private fun toParcel(parcelable: Parcelable) =
            Parcel.obtain().apply {
                parcelable.writeToParcel(this, 0)
                setDataPosition(0)
            }
        @Test
        fun serialize_emptyString_matches() {
            val original = StringPolicyValue("")
            val parcel = toParcel(original)
            val parsed = StringPolicyValue.CREATOR.createFromParcel(parcel)
            assertThat(parsed).isEqualTo(original)
        }
        @Test
        fun serialize_testString_matches() {
            val original = StringPolicyValue("testString")
            val parcel = toParcel(original)
            val parsed = StringPolicyValue.CREATOR.createFromParcel(parcel)
            assertThat(parsed).isEqualTo(original)
        }
        @Test
        fun serialize_emptyStringList_matches() {
            val original = ListOfStringPolicyValue(listOf())
            val parcel = toParcel(original)
            val parsed = ListOfStringPolicyValue.CREATOR.createFromParcel(parcel)
            assertThat(parsed).isEqualTo(original)
        }
        @Test
        fun serialize_filledStringList_matches() {
            val original = ListOfStringPolicyValue(listOf("firstTestString", "secondTestString"))
            val parcel = toParcel(original)
            val parsed = ListOfStringPolicyValue.CREATOR.createFromParcel(parcel)
            assertThat(parsed).isEqualTo(original)
        }
        @Test
        fun serialize_testPackage_matches() {
            val original = PackagePolicyValue(PackageIdentifier("test.package.name"))
            val parcel = toParcel(original)
            val parsed = PackagePolicyValue.CREATOR.createFromParcel(parcel)
            assertThat(parsed).isEqualTo(original)
        }
        @Test
        fun bundlePolicyValue_validTypes_doesNotThrow() {
            val bundle = Bundle().apply {
                putBoolean("boolean_key", true)
                putInt("int_key", 42)
                putString("string_key", "value")
                putStringArray("string_array_key", arrayOf("val1", "val2"))
                putBundle("nested_bundle_key", Bundle().apply {
                    putString("nested_string_key", "nested_val")
                })
                putParcelableArray("parcelable_array_key", arrayOf(Bundle().apply {
                    putString("array_bundle_string", "array_bundle_val")
                }))
            }
            val original = BundlePolicyValue(bundle)
            assertThat(original.value).isEqualTo(bundle)
        }
    }
}