/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include <jni.h>

extern jint android_util_MemoryIntArrayTest_createAshmem(JNIEnv* env,
        jobject clazz, jstring name, jint size);
extern void android_util_MemoryIntArrayTest_setAshmemSize(JNIEnv* env,
       jobject clazz, jint fd, jint size);
extern jlong android_util_MemoryIntArrayTest_mremap(JNIEnv* env,
        jobject clazz, jlong oldAddress, jint oldSize, jint newSize);
extern jboolean android_util_MemoryIntArrayTest_isRangeMapped(JNIEnv* env,
        jobject clazz, jlong address, jint size);
extern jint android_util_MemoryIntArrayTest_munmap(JNIEnv* env,
        jobject clazz, jlong address, jint size);

extern "C" {
    JNIEXPORT jint JNICALL Java_android_util_MemoryIntArrayTest_nativeCreateAshmem(
            JNIEnv * env, jobject obj, jstring name, jint size);
    JNIEXPORT void JNICALL Java_android_util_MemoryIntArrayTest_nativeSetAshmemSize(
            JNIEnv * env, jobject obj, jint fd, jint size);
    JNIEXPORT jlong JNICALL Java_android_util_MemoryIntArrayTest_nativeMremap(
            JNIEnv * env, jobject obj, jlong oldAddress, jint oldSize, jint newSize);
    JNIEXPORT jboolean JNICALL Java_android_util_MemoryIntArrayTest_nativeIsRangeMapped(
            JNIEnv * env, jobject obj, jlong address, jint size);
    JNIEXPORT jint JNICALL Java_android_util_MemoryIntArrayTest_nativeMunmap(
            JNIEnv * env, jobject obj, jlong address, jint size);
};

JNIEXPORT jint JNICALL Java_android_util_MemoryIntArrayTest_nativeCreateAshmem(
        __attribute__((unused)) JNIEnv * env,__attribute__((unused)) jobject obj,
        jstring name, jint size)
{
    return android_util_MemoryIntArrayTest_createAshmem(env, obj, name, size);
}

JNIEXPORT void JNICALL Java_android_util_MemoryIntArrayTest_nativeSetAshmemSize(
        __attribute__((unused)) JNIEnv * env,__attribute__((unused)) jobject obj,
        jint fd, jint size)
{
    android_util_MemoryIntArrayTest_setAshmemSize(env, obj, fd, size);
}

JNIEXPORT jlong JNICALL Java_android_util_MemoryIntArrayTest_nativeMremap(
        __attribute__((unused)) JNIEnv * env,__attribute__((unused)) jobject obj,
        jlong oldAddress, jint oldSize, jint newSize)
{
    return android_util_MemoryIntArrayTest_mremap(env, obj, oldAddress, oldSize, newSize);
}

JNIEXPORT jboolean JNICALL Java_android_util_MemoryIntArrayTest_nativeIsRangeMapped(
        __attribute__((unused)) JNIEnv * env,__attribute__((unused)) jobject obj,
        jlong address, jint size)
{
    return android_util_MemoryIntArrayTest_isRangeMapped(env, obj, address, size);
}

JNIEXPORT jint JNICALL Java_android_util_MemoryIntArrayTest_nativeMunmap(
        __attribute__((unused)) JNIEnv * env,__attribute__((unused)) jobject obj,
        jlong address, jint size)
{
    return android_util_MemoryIntArrayTest_munmap(env, obj, address, size);
}
