/*
 * Copyright (C) 2019 The Lineage OS Project
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

package com.nvidia.shieldtech;

import android.content.ComponentName;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.view.InputEvent;
import android.view.KeyEvent;

public interface INvHookBinder extends IInterface
{
    int interceptKeyBeforeQueueing(KeyEvent event, int policyFlags)
            throws RemoteException;

    int interceptKeyBeforeDispatching(KeyEvent event, int policyFlags)
            throws RemoteException;

    int deliverInputEvent(InputEvent event, int flags)
            throws RemoteException;

    void notifyAppResume(ComponentName component) throws RemoteException;

    void notifyInputFocusChange(String packageName) throws RemoteException;

    void notifyGoToSleepReason(int reason) throws RemoteException;

    static final String descriptor = "com.nvidia.shieldtech.INvHookBinder";

    int INTERCEPT_KEY_BEFORE_QUEUEING    = IBinder.FIRST_CALL_TRANSACTION;
    int INTERCEPT_KEY_BEFORE_DISPATCHING = IBinder.FIRST_CALL_TRANSACTION+1;
    int DELIVER_INPUT_EVENT              = IBinder.FIRST_CALL_TRANSACTION+2;
    int NOTIFY_APP_RESUME                = IBinder.FIRST_CALL_TRANSACTION+3;
    int NOTIFY_INPUT_FOCUS_CHANGE        = IBinder.FIRST_CALL_TRANSACTION+4;
    int NOTIFY_GO_TO_SLEEP_REASON        = IBinder.FIRST_CALL_TRANSACTION+5;

}
