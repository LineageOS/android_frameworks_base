/*
 * Copyright (C) 2024 The LineageOS Project
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

#include "core_jni_helpers.h"

namespace android {

static jboolean isDebuggable(JNIEnv*, jclass) {
#ifdef ANDROID_DEBUGGABLE
    return true;
#else
    return false;
#endif
}

static const JNINativeMethod method_table[] = {
        {"isDebuggable", "()Z", (void*)isDebuggable},
};

int register_com_android_internal_content_MicrogUtils(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "com/android/internal/content/MicrogUtils", method_table,
                                NELEM(method_table));
}

} // namespace android
