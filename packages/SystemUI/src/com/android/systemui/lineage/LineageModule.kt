/*
 * Copyright (C) 2023-2025 The LineageOS Project
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

package com.android.systemui.lineage

import com.android.systemui.CoreStartable
import com.android.systemui.accessories.AccessoryPopupStartable
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.model.TileCategory
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.AmbientDisplayTile
import com.android.systemui.qs.tiles.AODTile
import com.android.systemui.qs.tiles.CaffeineTile
import com.android.systemui.qs.tiles.HeadsUpTile
import com.android.systemui.qs.tiles.PowerShareTile
import com.android.systemui.qs.tiles.ProfilesTile
import com.android.systemui.qs.tiles.ReadingModeTile
import com.android.systemui.qs.tiles.SyncTile
import com.android.systemui.qs.tiles.UsbTetherTile
import com.android.systemui.qs.tiles.VpnTile
import com.android.systemui.qs.tiles.base.shared.model.QSTileConfig
import com.android.systemui.qs.tiles.base.shared.model.QSTileUIConfig
import com.android.systemui.res.R

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

@Module
interface LineageModule {
    /** Inject AccessoryPopupStartable into the CoreStartable map */
    @Binds
    @IntoMap
    @ClassKey(AccessoryPopupStartable::class)
    fun bindAccessoryPopupStartable(startable: AccessoryPopupStartable): CoreStartable

    /** Inject AmbientDisplayTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(AmbientDisplayTile.TILE_SPEC)
    fun bindAmbientDisplayTile(ambientDisplayTile: AmbientDisplayTile): QSTileImpl<*>

    /** Inject AODTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(AODTile.TILE_SPEC)
    fun bindAODTile(aodTile: AODTile): QSTileImpl<*>

    /** Inject CaffeineTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(CaffeineTile.TILE_SPEC)
    fun bindCaffeineTile(caffeineTile: CaffeineTile): QSTileImpl<*>

    /** Inject HeadsUpTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(HeadsUpTile.TILE_SPEC)
    fun bindHeadsUpTile(headsUpTile: HeadsUpTile): QSTileImpl<*>

    /** Inject PowerShareTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(PowerShareTile.TILE_SPEC)
    fun bindPowerShareTile(powerShareTile: PowerShareTile): QSTileImpl<*>

    /** Inject ProfilesTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(ProfilesTile.TILE_SPEC)
    fun bindProfilesTile(profilesTile: ProfilesTile): QSTileImpl<*>

    /** Inject ReadingModeTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(ReadingModeTile.TILE_SPEC)
    fun bindReadingModeTile(readingModeTile: ReadingModeTile): QSTileImpl<*>

    /** Inject SyncTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(SyncTile.TILE_SPEC)
    fun bindSyncTile(syncTile: SyncTile): QSTileImpl<*>

    /** Inject UsbTetherTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(UsbTetherTile.TILE_SPEC)
    fun bindUsbTetherTile(usbTetherTile: UsbTetherTile): QSTileImpl<*>

    /** Inject VpnTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(VpnTile.TILE_SPEC)
    fun bindVpnTile(vpnTile: VpnTile): QSTileImpl<*>

    companion object {
        const val AMBIENT_DISPLAY_TILE_SPEC = "ambient_display"
        const val AOD_TILE_SPEC = "aod"
        const val CAFFEINE_TILE_SPEC = "caffeine"
        const val HEADS_UP_TILE_SPEC = "heads_up"
        const val POWERSHARE_TILE_SPEC = "powershare"
        const val PROFILES_TILE_SPEC = "profiles"
        const val READING_MODE_TILE_SPEC = "reading_mode"
        const val SYNC_TILE_SPEC = "sync"
        const val USB_TETHER_TILE_SPEC = "usb_tether"
        const val VPN_TILE_SPEC = "vpn"

        @Provides
        @IntoMap
        @StringKey(AMBIENT_DISPLAY_TILE_SPEC)
        fun provideAmbientDisplayTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(AMBIENT_DISPLAY_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_ambient_display,
                        labelRes = R.string.quick_settings_ambient_display_label
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.DISPLAY,
            )

        @Provides
        @IntoMap
        @StringKey(AOD_TILE_SPEC)
        fun provideAodTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(AOD_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_aod,
                        labelRes = R.string.quick_settings_aod_label
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.DISPLAY,
            )

        @Provides
        @IntoMap
        @StringKey(CAFFEINE_TILE_SPEC)
        fun provideCaffeineTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(CAFFEINE_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_caffeine,
                        labelRes = R.string.quick_settings_caffeine_label
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.DISPLAY,
            )

        @Provides
        @IntoMap
        @StringKey(HEADS_UP_TILE_SPEC)
        fun provideHeadsUpTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(HEADS_UP_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_heads_up,
                        labelRes = R.string.quick_settings_heads_up_label
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.ACCESSIBILITY,
            )

        @Provides
        @IntoMap
        @StringKey(POWERSHARE_TILE_SPEC)
        fun providePowershareTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(POWERSHARE_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_powershare,
                        labelRes = R.string.quick_settings_powershare_label
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.UTILITIES,
            )

        @Provides
        @IntoMap
        @StringKey(PROFILES_TILE_SPEC)
        fun provideProfilesTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(PROFILES_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_profiles,
                        labelRes = R.string.quick_settings_profiles_label
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.UTILITIES,
            )

        @Provides
        @IntoMap
        @StringKey(READING_MODE_TILE_SPEC)
        fun provideReadingModeTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(READING_MODE_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_reader,
                        labelRes = R.string.quick_settings_reading_mode
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.DISPLAY,
            )

        @Provides
        @IntoMap
        @StringKey(SYNC_TILE_SPEC)
        fun provideSyncTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(SYNC_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_sync,
                        labelRes = R.string.quick_settings_sync_label
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.CONNECTIVITY,
            )

        @Provides
        @IntoMap
        @StringKey(USB_TETHER_TILE_SPEC)
        fun provideUsbTetherTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(USB_TETHER_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_usb_tether,
                        labelRes = R.string.quick_settings_usb_tether_label
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.CONNECTIVITY,
            )

        @Provides
        @IntoMap
        @StringKey(VPN_TILE_SPEC)
        fun provideVpnTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(VPN_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_vpn,
                        labelRes = R.string.quick_settings_vpn_label
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.CONNECTIVITY,
            )
    }
}
