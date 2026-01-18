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

package com.android.systemui.qs.tiles.impl.wifi.domain.interactor

import android.content.Intent
import android.provider.Settings
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.tiles.base.domain.actions.QSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.domain.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.base.domain.model.QSTileInput
import com.android.systemui.qs.tiles.base.shared.model.QSTileUserAction
import com.android.systemui.qs.tiles.dialog.InternetDialogManager
import com.android.systemui.qs.tiles.impl.wifi.domain.model.WifiTileModel
import com.android.systemui.statusbar.connectivity.AccessPointController
import com.android.systemui.statusbar.pipeline.shared.ui.model.WifiToggleState
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepository
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/** Handles wifi tile clicks. */
class WifiTileUserActionInteractor
@Inject
constructor(
    @Main private val mainContext: CoroutineContext,
    private val internetDialogManager: InternetDialogManager,
    private val accessPointController: AccessPointController,
    private val wifiRepository: WifiRepository,
    private val qsTileIntentUserActionHandler: QSTileIntentUserInputHandler,
) : QSTileUserActionInteractor<WifiTileModel> {
    val longClickIntent = Intent(Settings.ACTION_WIFI_SETTINGS)

    override suspend fun handleInput(input: QSTileInput<WifiTileModel>): Unit =
        with(input) {
            when (action) {
                is QSTileUserAction.Click -> {
                    handleClick(action.expandable)
                }
                is QSTileUserAction.LongClick -> {
                    handleLongClick(action.expandable)
                }
                is QSTileUserAction.ToggleClick -> {
                    handleSecondaryClick(action.expandable)
                }
            }
        }

    suspend fun handleClick(expandable: Expandable?) {
        withContext(mainContext) {
            internetDialogManager.create(
                aboveStatusBar = true,
                false, /* canConfigMobileData */
                accessPointController.canConfigWifi(),
                expandable,
            )
        }
    }

    suspend fun handleLongClick(expandable: Expandable?) {
        withContext(mainContext) {
            qsTileIntentUserActionHandler.handle(expandable, Intent(Settings.ACTION_WIFI_SETTINGS))
        }
    }

    fun handleSecondaryClick(expandable: Expandable?) {
        if (!wifiRepository.isWifiEnabled.value) {
            wifiRepository.enableWifi()
        } else {
            wifiRepository.disableWifi()
        }
    }
}
