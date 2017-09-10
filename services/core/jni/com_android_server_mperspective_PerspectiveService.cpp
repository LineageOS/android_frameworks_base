/*
 * Copyright 2015-2016 Preetam J. D'Souza
 * Copyright 2016 The Maru OS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Credits: Inspired by android_view_SurfaceSession.cpp.
 */

#define LOG_TAG "PerspectiveServiceJNI"

#include "JNIHelp.h"

#include <android_runtime/AndroidRuntime.h>
#include <utils/Log.h>
#include <utils/RefBase.h>

#include <binder/IServiceManager.h>
#include <perspective/IPerspectiveService.h>

namespace android {

static struct {
    jfieldID mNativeClient;
} gPerspectiveManagerClassInfo;


/**
 * Wrapper for IPerspectiveService Binder proxy.
 *
 * In order for us to preserve the Binder proxy between JNI calls
 * we wrap up the proxy in this wrapper, allocate it on the heap,
 * and store a pointer within the corresponding Java class that is
 * passed as an arg to all subsequent calls.
 *
 * Error handling: if the remote does not exist, we always return false.
 *
 * *I think the sp<> handle adds a reference so that the object is
 * not destroyed, i.e. we don't need to manually incStrong().
 */
class PerspectiveClient {
public:
    PerspectiveClient(sp<IPerspectiveService> proxy)
        : mProxy(proxy) {
        if (proxy != NULL) {
            // listen for remote death
            mDeathRecipient = new MDeathRecipient(*const_cast<PerspectiveClient*>(this));
            IInterface::asBinder(mProxy)->linkToDeath(mDeathRecipient);
        }
    }

    void remoteDied() {
        mProxy = NULL;
        mDeathRecipient = NULL;
    }

    bool start() {
        return mProxy != NULL && mProxy->start();
    }

    bool stop() {
        return mProxy != NULL && mProxy->stop();
    }

    bool isRunning() {
        return mProxy != NULL && mProxy->isRunning();
    }

private:
    sp<IPerspectiveService> mProxy;

    class MDeathRecipient : public IBinder::DeathRecipient {
        PerspectiveClient& mClient;
        virtual void binderDied(const wp<IBinder>& who) {
            ALOGW("PerspectiveService remote died [%p]",
                  who.unsafe_get());
            mClient.remoteDied();
        }
    public:
        MDeathRecipient(PerspectiveClient& client) : mClient(client) { }
    };
    sp<MDeathRecipient> mDeathRecipient;
};

static jlong nativeCreateClient(JNIEnv* env, jclass clazz) {
    sp<IPerspectiveService> client;
    getService(String16("PerspectiveService"), &client);
    if (client == NULL) {
        ALOGE("Failed to get a handle to PerspectiveService from ServiceManager!");
        // we go ahead and wrap up the null ptr anyway...our wrapper
        // will always return false
    }
    PerspectiveClient *wrapper = new PerspectiveClient(client);
    return reinterpret_cast<jlong>(wrapper);
}

static jboolean nativeStart(JNIEnv *env, jclass clazz, jlong ptr) {
    PerspectiveClient *client = reinterpret_cast<PerspectiveClient*>(ptr);
    return client->start();
}

static jboolean nativeStop(JNIEnv *env, jclass clazz, jlong ptr) {
    PerspectiveClient *client = reinterpret_cast<PerspectiveClient*>(ptr);
    return client->stop();
}

static jboolean nativeIsRunning(JNIEnv *env, jclass clazz, jlong ptr) {
    PerspectiveClient *client = reinterpret_cast<PerspectiveClient*>(ptr);
    return client->isRunning();
}

static JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "nativeCreateClient", "()J",
            (void *)nativeCreateClient },
    { "nativeStart", "(J)Z",
            (void *)nativeStart },
    { "nativeStop", "(J)Z",
            (void *)nativeStop },
    { "nativeIsRunning", "(J)Z",
            (void *)nativeIsRunning }
};

int register_android_server_mperspective_PerspectiveService(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "com/android/server/mperspective/PerspectiveService",
            gMethods, NELEM(gMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");

    jclass clazz = env->FindClass("com/android/server/mperspective/PerspectiveService");
    gPerspectiveManagerClassInfo.mNativeClient = env->GetFieldID(clazz, "mNativeClient", "J");

    return res;
}

} // namespace android
