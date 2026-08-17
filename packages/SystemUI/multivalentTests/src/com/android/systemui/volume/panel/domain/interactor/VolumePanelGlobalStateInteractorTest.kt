/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.volume.panel.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.runTest
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.activityStarter
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.testKosmosNew
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class VolumePanelGlobalStateInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmosNew()

    private val underTest by lazy { kosmos.volumePanelGlobalStateInteractor }

    @Before
    fun setUp() {
        whenever(kosmos.activityStarter.dismissKeyguardThenExecute(any(), any(), anyBoolean()))
            .then { (it.arguments[0] as ActivityStarter.OnDismissAction).onDismiss() }
    }

    @Test
    fun changeVisibility_changesVisibility() = kosmos.runTest {
        setDeviceEntered(true)

        underTest.setVisible(false)
        assertThat(underTest.globalState.value.isVisible).isFalse()

        underTest.setVisible(true)
        assertThat(underTest.globalState.value.isVisible).isTrue()
    }

    @Test
    fun setVisible_noOpWhenDeviceNotEntered() = kosmos.runTest {
        setDeviceEntered(false)

        underTest.setVisible(true)
        assertThat(underTest.globalState.value.isVisible).isFalse()
    }

    @Test
    fun globalState_becomesInvisibleWhenDeviceNotEntered() = kosmos.runTest {
        setDeviceEntered(true)

        underTest.setVisible(true)
        assertThat(underTest.globalState.value.isVisible).isTrue()

        setDeviceEntered(false)
        assertThat(underTest.globalState.value.isVisible).isFalse()
    }

    private fun Kosmos.setDeviceEntered(isEntered: Boolean) {
        if (isEntered) {
            fakeAuthenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)
            fakeKeyguardRepository.setKeyguardEnabled(true)
            sceneInteractor.changeScene(Scenes.Gone, "reason")
        } else {
            sceneInteractor.changeScene(Scenes.Lockscreen, "reason")
        }
    }
}
