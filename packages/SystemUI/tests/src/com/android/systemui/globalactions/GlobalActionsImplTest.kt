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

package com.android.systemui.globalactions

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.shade.ShadeController
import com.android.systemui.statusbar.commandQueue
import com.android.systemui.statusbar.policy.deviceProvisionedController
import com.android.systemui.statusbar.policy.keyguardStateController
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class GlobalActionsImplTest : SysuiTestCase() {
    private val shadeController = mock<ShadeController>()
    private lateinit var underTest: GlobalActionsImpl

    @Before
    fun setUp() {
        val kosmos = Kosmos()
        underTest =
            GlobalActionsImpl(
                context,
                kosmos.commandQueue,
                kosmos.globalActionsDialogLite,
                kosmos.keyguardStateController,
                kosmos.deviceProvisionedController,
                shadeController,
                mock<ShutdownUi>(),
            )
    }

    @Test
    fun testShutdown_collapsesShade() {
        underTest.showShutdownUi(false, "test", false)

        verify(shadeController).instantCollapseShade()
    }

    @Test
    fun testReboot_collapsesShade() {
        underTest.showShutdownUi(true, "test", false)

        verify(shadeController).instantCollapseShade()
    }
}
