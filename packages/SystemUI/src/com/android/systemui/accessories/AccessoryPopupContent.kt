/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.accessories

import androidx.annotation.DrawableRes

data class AccessoryPopupContent(
    @DrawableRes val icon: Int,
    val title: String,
    val subtitle: String,
    val levelText: String?,
    val isCharging: Boolean,
    val isError: Boolean,
    val durationMs: Long,
    val onClick: (() -> Unit)? = null,
)
