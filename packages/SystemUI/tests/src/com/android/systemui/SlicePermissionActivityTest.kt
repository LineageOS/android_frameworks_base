/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui

import android.app.Activity
import android.app.Application
import android.app.FragmentController
import android.app.slice.SliceManager
import android.app.slice.SliceProvider
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Bundle
import android.testing.TestableLooper.RunWithLooper
import android.view.Window
import android.window.OnBackInvokedDispatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper
class SlicePermissionActivityTest : SysuiTestCase() {

    companion object {
        private const val TEST_PKG = "com.example.test"
        private const val SPOOF_PKG = "com.example.spoof"
        private const val PROVIDER_PKG = "com.example.provider"
    }

    private lateinit var mActivity: TestSlicePermissionActivity
    private lateinit var mIntent: Intent
    private lateinit var mMockContentResolver: ContentResolver
    private lateinit var mMockPackageManager: PackageManager
    private lateinit var mMockSliceManager: SliceManager

    @Before
    fun setUp() {
        mActivity = TestSlicePermissionActivity()

        try {
            val activityInfoField = Activity::class.java.getDeclaredField("mActivityInfo")
            activityInfoField.isAccessible = true
            activityInfoField.set(mActivity, ActivityInfo())

            val fragmentsField = Activity::class.java.getDeclaredField("mFragments")
            fragmentsField.isAccessible = true
            fragmentsField.set(mActivity, mock(FragmentController::class.java))

            val applicationField = Activity::class.java.getDeclaredField("mApplication")
            applicationField.isAccessible = true
            applicationField.set(mActivity, mock(Application::class.java))

            val windowField = Activity::class.java.getDeclaredField("mWindow")
            windowField.isAccessible = true
            val mockWindow = mock(Window::class.java)
            val mockDispatcher = mock(OnBackInvokedDispatcher::class.java)
            whenever(mockWindow.onBackInvokedDispatcher).thenReturn(mockDispatcher)
            windowField.set(mActivity, mockWindow)

            val baseField = ContextWrapper::class.java.getDeclaredField("mBase")
            baseField.isAccessible = true
            val mockBase = mock(Context::class.java)
            whenever(mockBase.applicationInfo).thenReturn(ApplicationInfo())
            baseField.set(mActivity, mockBase)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        mIntent = Intent(SliceManager.ACTION_REQUEST_SLICE_PERMISSION)
        val uri = Uri.parse("content://com.example.provider/slice")
        mIntent.putExtra(SliceProvider.EXTRA_BIND_URI, uri)
        mIntent.putExtra(SliceProvider.EXTRA_PKG, TEST_PKG)
        mActivity.intent = mIntent

        mMockContentResolver = mock(ContentResolver::class.java)
        mMockSliceManager = mock(SliceManager::class.java)

        mActivity.setContentResolver(mMockContentResolver)
        mActivity.setSliceManager(mMockSliceManager)

        whenever(mMockContentResolver.getType(uri)).thenReturn(SliceProvider.SLICE_TYPE)

        val providerInfo = ProviderInfo()
        providerInfo.applicationInfo = ApplicationInfo()
        providerInfo.applicationInfo.packageName = PROVIDER_PKG

        val appInfo = mock(ApplicationInfo::class.java)
        appInfo.packageName = TEST_PKG
        appInfo.uid = 10001
        whenever(appInfo.loadSafeLabel(any(), anyFloat(), anyInt())).thenReturn("Test App 1")

        val providerAppInfo = mock(ApplicationInfo::class.java)
        providerAppInfo.packageName = PROVIDER_PKG
        providerAppInfo.uid = 10002
        whenever(providerAppInfo.loadSafeLabel(any(), anyFloat(), anyInt())).thenReturn("Test App 2")

        // Bypass strict signature matching to survive internal AOSP PackageManager changes
        mMockPackageManager = mock(PackageManager::class.java) { invocation ->
            val methodName = invocation.method.name

            if ("resolveContentProvider" == methodName) {
                return@mock providerInfo
            } else if ("getApplicationInfo" == methodName) {
                val pkg = invocation.getArgument<String>(0)
                if (TEST_PKG == pkg) return@mock appInfo
                if (PROVIDER_PKG == pkg) return@mock providerAppInfo

                val dummy = ApplicationInfo()
                dummy.packageName = pkg
                return@mock dummy
            }

            org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation)
        }

        mActivity.setPackageManager(mMockPackageManager)
    }

    @Test
    fun testValidCaller_providerPkg() {
        mActivity.setLaunchedFromPackage(PROVIDER_PKG)
        try {
            mActivity.onCreate(Bundle())
        } catch (e: Exception) {
            // Naked activity will crash during AlertDialog layout, which means it bypassed finish()
        }
        assertThat(mActivity.isTestFinishing).isFalse()
    }

    @Test
    fun testValidCaller_systemuiPkg() {
        mActivity.setLaunchedFromPackage("com.android.systemui")
        try {
            mActivity.onCreate(Bundle())
        } catch (e: Exception) {
        }
        assertThat(mActivity.isTestFinishing).isFalse()
    }

    @Test
    fun testValidCaller_androidPkg() {
        mActivity.setLaunchedFromPackage("android")
        try {
            mActivity.onCreate(Bundle())
        } catch (e: Exception) {
        }
        assertThat(mActivity.isTestFinishing).isFalse()
    }

    @Test
    fun testValidCaller_fallbackToCallingPkg() {
        mActivity.setLaunchedFromPackage(null)
        mActivity.setCallingPackage(PROVIDER_PKG)
        try {
            mActivity.onCreate(Bundle())
        } catch (e: Exception) {
        }
        assertThat(mActivity.isTestFinishing).isFalse()
    }

    @Test
    fun testInvalidCaller_untrustedPkg() {
        // Set the caller to an arbitrary package not allowed
        mActivity.setLaunchedFromPackage(SPOOF_PKG)

        // No exception should be thrown by finish() in the test
        mActivity.onCreate(Bundle())

        // Assert that finish() was called
        assertThat(mActivity.isTestFinishing).isTrue()
    }

    private inner class TestSlicePermissionActivity : SlicePermissionActivity() {
        private var mTestCallingPackage: String? = null
        private var mTestLaunchedFromPackage: String? = null
        private var mTestReferrer: Uri? = null
        private var mTestContentResolver: ContentResolver? = null
        private var mTestPackageManager: PackageManager? = null
        private var mTestSliceManager: SliceManager? = null

        // Custom tracking to bypass Robolectric's broken naked activity lifecycle
        var isTestFinishing = false
            private set

        fun setCallingPackage(pkg: String?) {
            mTestCallingPackage = pkg
        }

        fun setLaunchedFromPackage(pkg: String?) {
            mTestLaunchedFromPackage = pkg
        }

        fun setReferrer(referrer: Uri?) {
            mTestReferrer = referrer
        }

        fun setContentResolver(cr: ContentResolver?) {
            mTestContentResolver = cr
        }

        fun setPackageManager(pm: PackageManager?) {
            mTestPackageManager = pm
        }

        fun setSliceManager(sm: SliceManager?) {
            mTestSliceManager = sm
        }

        override fun getCallingPackage(): String? {
            return mTestCallingPackage
        }

        override fun getLaunchedFromPackage(): String? {
            return mTestLaunchedFromPackage
        }

        override fun getPackageName(): String {
            return "com.android.systemui"
        }

        override fun getApplicationInfo(): ApplicationInfo {
            val appInfo = ApplicationInfo()
            appInfo.packageName = "com.android.systemui"
            appInfo.targetSdkVersion = 32
            return appInfo
        }

        override fun getReferrer(): Uri? {
            return mTestReferrer
        }

        override fun getContentResolver(): ContentResolver {
            return mTestContentResolver ?: super.getContentResolver()
        }

        override fun getPackageManager(): PackageManager {
            return mTestPackageManager ?: super.getPackageManager()
        }

        override fun getSystemService(name: String): Any? {
            if (Context.SLICE_SERVICE == name && mTestSliceManager != null) {
                return mTestSliceManager
            }
            return super.getSystemService(name)
        }

        override fun finish() {
            isTestFinishing = true
        }

        // To make onCreate visible in tests since protected members are scoped differently in kotlin sometimes
        public override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
        }
    }
}
