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
package android.view

import android.os.Parcel
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val BITWISE_PORT = 0x12 and 0xFF

@RunWith(TestParameterInjector::class)
class DisplayAddressTest {

    @Test
    fun portSelectorMatchesStablePanelVariants() {
        val inner = DisplayAddress.fromPortAndModel(131, null)
        val cover = DisplayAddress.fromPortAndModel(132, null)
        val panels = listOf(4630946704981769859L, 4630946717172870531L).map {
            DisplayAddress.fromPhysicalDisplayId(it, 131, true)
        }
        for (panel in panels) {
            assertTrue(DisplayAddress.matchInternalDisplays(inner, panel, true))
            assertTrue(DisplayAddress.matchInternalDisplays(panel, inner, true))
            assertFalse(DisplayAddress.matchInternalDisplays(cover, panel, true))
            assertFalse(DisplayAddress.matchInternalDisplays(panel, cover, true))
            assertFalse(inner.equals(panel))
        }
        assertFalse(DisplayAddress.matchInternalDisplays(panels[0], panels[1], true))
        val modelAddress = DisplayAddress.fromPortAndModel(131, 1L)
        assertFalse(DisplayAddress.matchInternalDisplays(modelAddress, panels[0], true))
        assertFalse(DisplayAddress.matchInternalDisplays(panels[0], modelAddress, true))
    }

    enum class MatchInternalDisplays(
        val edidsFlag: Boolean,
        val physicalDisplayId1: Long,
        val port1: Int,
        val physicalDisplayId2: Long,
        val port2: Int,
        val matches: Boolean
    ) {
        STABLE_PHYSICAL_MATCH(
            true, 123L, 12, 123L, 99, true
        ),
        STABLE_PHYSICAL_NO_MATCH(
            true, 123L, 12, 456L, 12, false
        ),
        MODEL_MISMATCH(
            false,
            physicalDisplayId1 = (BITWISE_PORT or 0x0100).toLong(),
            port1 = BITWISE_PORT,
            physicalDisplayId2 = (BITWISE_PORT or 0x1000).toLong(),
            port2 = BITWISE_PORT,
            false
        ),
    }

    enum class MatchInternalDisplaysNoPort(
        val edidsFlag: Boolean,
        val physicalDisplayId1: Long,
        val physicalDisplayId2: Long,
        val matches: Boolean
    ) {
        PHYSICAL_MATCH(
            false, 123L, 123L, true
        ),
        PHYSICAL_NO_MATCH(
            false, 123L, 456L, false
        ),
    }

    @Test
    fun testMatchInternalDisplays_physicalMatch(
        @TestParameter testCase: MatchInternalDisplaysNoPort
    ) {
        val address1 = DisplayAddress.fromPhysicalDisplayId(testCase.physicalDisplayId1)
        val address2 = DisplayAddress.fromPhysicalDisplayId(testCase.physicalDisplayId2)

        assertEquals(
            testCase.matches,
            DisplayAddress.matchInternalDisplays(address1, address2, testCase.edidsFlag)
        )
    }


    @Test
    fun testMatchInternalDisplays_stableEdidsFlagTrue_stablePhysicalMatch(
        @TestParameter testCase: MatchInternalDisplays
    ) {
        val address1 = DisplayAddress.fromPhysicalDisplayId(
            testCase.physicalDisplayId1, testCase.port1, testCase.edidsFlag
        )
        val address2 = DisplayAddress.fromPhysicalDisplayId(
            testCase.physicalDisplayId2, testCase.port2, testCase.edidsFlag
        )

        assertEquals(
            testCase.matches,
            DisplayAddress.matchInternalDisplays(address1, address2, testCase.edidsFlag)
        )
    }

    @Test
    fun testMatchInternalDisplays_stableEdidsFlagTrue_typeMismatch() {
        val stableEdidsFlag = true

        val physicalDisplayId1 = 123L
        // Creates Physical
        val address1 = DisplayAddress.fromPhysicalDisplayId(physicalDisplayId1)

        val physicalDisplayId2 = 123L
        val port2 = 2
        // Creates StablePhysical
        val address2 =
            DisplayAddress.fromPhysicalDisplayId(physicalDisplayId2, port2, stableEdidsFlag)

        assertFalse(DisplayAddress.matchInternalDisplays(address1, address2, stableEdidsFlag))
    }

    @Test
    fun testMatchInternalDisplays_stableEdidsFlagFalse_portMatch() {
        val stableEdidsFlag = false

        // same ports; different addresses.
        val port1 = (0x12 and 0xFF).toInt()
        val model1 = null
        val address1 = DisplayAddress.fromPortAndModel(port1, model1)

        val port2 = (0x12 and 0xFF).toInt()
        val model2 = null
        val address2 = DisplayAddress.fromPortAndModel(port2, model2)

        assertTrue(DisplayAddress.matchInternalDisplays(address1, address2, stableEdidsFlag))
    }

    @Test
    fun testFromPhysicalDisplayId_stableEdidsTrue() {
        val stableEdidsFlag = true
        val physicalDisplayId = 123L
        val port = 12
        val address = DisplayAddress.fromPhysicalDisplayId(physicalDisplayId, port, stableEdidsFlag)

        assertTrue(address is DisplayAddress.StablePhysical)
    }

    @Test
    fun testFromPhysicalDisplayId_stableEdidsFalse() {
        val stableEdidsFlag = false
        val physicalDisplayId = 123L
        val port = 12
        val address = DisplayAddress.fromPhysicalDisplayId(physicalDisplayId, port, stableEdidsFlag)

        assertTrue(address is DisplayAddress.Physical)
    }

    @Test
    fun testStablePhysical_isParcelable() {
        val originalAddress =
            DisplayAddress.fromPhysicalDisplayId(123L, 45, true) as DisplayAddress.StablePhysical

        val parcel = Parcel.obtain()
        originalAddress.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)

        val createdFromParcel = DisplayAddress.StablePhysical.CREATOR.createFromParcel(parcel)

        assertEquals(originalAddress.physicalDisplayId, createdFromParcel.physicalDisplayId)
        assertEquals(originalAddress.port, createdFromParcel.port)
        assertEquals(originalAddress, createdFromParcel)
    }
}
