/*
 * Copyright (c) 2016, NVIDIA CORPORATION.  All rights reserved.
 * NVIDIA CORPORATION and its licensors retain all intellectual property
 * and proprietary rights in and to this software, related documentation
 * and any modifications thereto.  Any use, reproduction, disclosure or
 * distribution of this software and related documentation without an express
 * license agreement from NVIDIA CORPORATION is strictly prohibited.
 */

package com.nvidia.shieldtech;

import android.content.ComponentName;
import android.view.InputEvent;
import android.view.KeyEvent;

/** @hide */
interface INvHookBinder {
    int interceptKeyBeforeQueueing(in KeyEvent event, int policyFlags);
    int interceptKeyBeforeDispatching(in KeyEvent event, int policyFlags);
    int deliverInputEvent(in InputEvent event, int flags);
    void notifyAppResume(in ComponentName component);
    void notifyInputFocusChange(in String packageName);
    void notifyGoToSleepReason(int reason);
}
