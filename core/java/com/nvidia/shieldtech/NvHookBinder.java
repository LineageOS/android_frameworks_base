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
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.InputEvent;
import android.view.KeyEvent;

public abstract class NvHookBinder extends Binder implements INvHookBinder
{
    static public INvHookBinder asInterface(IBinder obj)
    {
        if (obj == null) {
            return null;
        }
        INvHookBinder in =
            (INvHookBinder)obj.queryLocalInterface(descriptor);
        if (in != null) {
            return in;
        }

        return new NvHookBinderProxy(obj);
    }

    public NvHookBinder()
    {
        attachInterface(this, descriptor);
    }

    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
    {
        try {
            switch (code) {
                case INvHookBinder.INTERCEPT_KEY_BEFORE_QUEUEING: {
                    data.enforceInterface(INvHookBinder.descriptor);

                    KeyEvent event = null;
		    if (data.readInt() != 0)
                        event = (KeyEvent) KeyEvent.CREATOR.createFromParcel(data);

                    reply.writeNoException();
                    reply.writeInt(interceptKeyBeforeQueueing(event, data.readInt()));

                    return true;
                }

                case INvHookBinder.INTERCEPT_KEY_BEFORE_DISPATCHING: {
                    data.enforceInterface(INvHookBinder.descriptor);

                    KeyEvent event = null;
		    if (data.readInt() != 0)
                        event = (KeyEvent) KeyEvent.CREATOR.createFromParcel(data);

                    reply.writeNoException();
                    reply.writeInt(interceptKeyBeforeDispatching(event, data.readInt()));

                    return true;
                }

                case INvHookBinder.DELIVER_INPUT_EVENT: {
                    data.enforceInterface(INvHookBinder.descriptor);

                    InputEvent event = null;
		    if (data.readInt() != 0)
                        event = (InputEvent) InputEvent.CREATOR.createFromParcel(data);

                    reply.writeNoException();
                    reply.writeInt(deliverInputEvent(event, data.readInt()));

                    return true;
                }

                case INvHookBinder.NOTIFY_APP_RESUME: {
                    data.enforceInterface(INvHookBinder.descriptor);

                    ComponentName cname = null;
		    if (data.readInt() != 0)
                        cname = (ComponentName) ComponentName.CREATOR.createFromParcel(data);

                    notifyAppResume(cname);
                    reply.writeNoException();

                    return true;
                }

                case INvHookBinder.NOTIFY_INPUT_FOCUS_CHANGE: {
                    data.enforceInterface(INvHookBinder.descriptor);

                    notifyInputFocusChange(data.readString());
                    reply.writeNoException();

                    return true;
                }

                case INvHookBinder.NOTIFY_GO_TO_SLEEP_REASON: {
                    data.enforceInterface(INvHookBinder.descriptor);

                    notifyGoToSleepReason(data.readInt());
                    reply.writeNoException();

                    return true;
                }
            }
        } catch (RemoteException e) {
        }

        return false;
    }

    public IBinder asBinder()
    {
        return this;
    }
}

class NvHookBinderProxy implements INvHookBinder {
    public NvHookBinderProxy(IBinder remote) {
        mRemote = remote;
    }

    public IBinder asBinder() {
        return mRemote;
    }

    public int interceptKeyBeforeQueueing(KeyEvent event, int policyFlags)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(INvHookBinder.descriptor);
        if (event != null) {
            data.writeInt(1);
            event.writeToParcel(data, 0);
        } else {
            data.writeInt(0);
        }
        data.writeInt(policyFlags);
        mRemote.transact(INTERCEPT_KEY_BEFORE_QUEUEING, data, reply, 0);
        reply.readException();
        int result = reply.readInt();
        reply.recycle();
        data.recycle();
        return result;
    }

    public int interceptKeyBeforeDispatching(KeyEvent event, int policyFlags)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(INvHookBinder.descriptor);
        if (event != null) {
            data.writeInt(1);
            event.writeToParcel(data, 0);
        } else {
            data.writeInt(0);
        }
        data.writeInt(policyFlags);
        mRemote.transact(INTERCEPT_KEY_BEFORE_DISPATCHING, data, reply, 0);
        reply.readException();
        int result = reply.readInt();
        reply.recycle();
        data.recycle();
        return result;
    }

    public int deliverInputEvent(InputEvent event, int flags)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(INvHookBinder.descriptor);
        if (event != null) {
            data.writeInt(1);
            event.writeToParcel(data, 0);
        } else {
            data.writeInt(0);
        }
        data.writeInt(flags);
        mRemote.transact(DELIVER_INPUT_EVENT, data, reply, 0);
        reply.readException();
        int result = reply.readInt();
        reply.recycle();
        data.recycle();
        return result;
    }

    public void notifyAppResume(ComponentName component)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(INvHookBinder.descriptor);
        if (component != null) {
            data.writeInt(1);
            component.writeToParcel(data, 0);
        } else {
            data.writeInt(0);
        }
        mRemote.transact(NOTIFY_APP_RESUME, data, reply, 0);
        reply.readException();
        reply.recycle();
        data.recycle();
    }

    public void notifyInputFocusChange(String packageName)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(INvHookBinder.descriptor);
        data.writeString(packageName);
        mRemote.transact(NOTIFY_INPUT_FOCUS_CHANGE, data, reply, 0);
        reply.readException();
        reply.recycle();
        data.recycle();
    }

    public void notifyGoToSleepReason(int reason)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(INvHookBinder.descriptor);
        data.writeInt(reason);
        mRemote.transact(NOTIFY_GO_TO_SLEEP_REASON, data, reply, 0);
        reply.readException();
        reply.recycle();
        data.recycle();
    }

    private IBinder mRemote;
}
