/*
 * Copyright (C) 2026 The Android Open Source Project
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

import android.content.Intent
import android.os.Bundle
import android.util.Xml
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.experimental.runners.Enclosed
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

@RunWith(Enclosed::class)
class BundlePolicySerializerTest {

    @RunWith(Parameterized::class)
    class UnsupportedTypesTest(
        private val ignoredTestName: String,
        private val bundle: Bundle,
        private val expectedExceptionMessage: String
    ) {

        companion object {
            @JvmStatic
            @Parameterized.Parameters(name = "{0}")
            fun data(): Collection<Array<Any>> {
                return listOf(
                    arrayOf(
                        "Long",
                        Bundle().apply {
                            putLong("key", 123L)
                        },
                        "Unsupported type in Bundle for key 'key': java.lang.Long"
                    ),
                    arrayOf(
                        "Double",
                        Bundle().apply {
                            putDouble("key", 1.23)
                        },
                        "Unsupported type in Bundle for key 'key': java.lang.Double"
                    ),
                    arrayOf(
                        "Long in Bundle",
                        Bundle().apply {
                            putBundle("nestedBundle", Bundle().apply {
                                putLong("nestedKey", 123L)
                            })
                        },
                        "Unsupported type in Bundle for key 'nestedKey': java.lang.Long"
                    ),
                    arrayOf(
                        "non-bundle in Array",
                        Bundle().apply {
                            putParcelableArray("arrayKey", arrayOf(Intent()))
                        },
                        "bundle-array can only hold Bundles"
                    )
                )
            }
        }

        @Test
        fun saveToXml_unsupportedTypes_throwsIllegalArgumentException() {
            val serializer = BundlePolicySerializer()
            val outXml = Xml.newFastSerializer()
            val os = ByteArrayOutputStream()
            outXml.setOutput(os, StandardCharsets.UTF_8.name())
            outXml.startDocument(null, true)

            val thrown = assertThrows(IllegalArgumentException::class.java) {
                serializer.saveToXml(outXml, bundle)
            }

            assertThat(thrown).hasMessageThat().isEqualTo(expectedExceptionMessage)
        }
    }

    class SupportedTypesTest {

        @Test
        fun saveToXml_supportedTypes_doesNotThrow() {
            val bundle = Bundle().apply {
                putBoolean("bool", true)
                putInt("int", 42)
                putString("str", "value")
                putStringArray("strArray", arrayOf("val1", "val2"))
                putBundle("nestedBundle", Bundle().apply {
                    putString("nestedStr", "val")
                })
                putParcelableArray("bundleArray", arrayOf(Bundle(), Bundle()))
            }

            val serializer = BundlePolicySerializer()
            val outXml = Xml.newFastSerializer()
            val os = ByteArrayOutputStream()
            outXml.setOutput(os, StandardCharsets.UTF_8.name())
            outXml.startDocument(null, true)

            // Should execute successfully without throwing exceptions
            serializer.saveToXml(outXml, bundle)
        }
    }
}