/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.server.pm

import android.content.pm.PackageManager
import com.android.server.testutils.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.io.File

@RunWith(JUnit4::class)
class InstallPackageHelperTest : PackageHelperTestBase() {

    private lateinit var installPackageHelper: InstallPackageHelper

    @Before
    override fun setup() {
        super.setup()
        // Mock getPreparingPackageParser on mock injector to return our spied packageParser
        whenever(rule.mocks().injector.preparingPackageParser).thenReturn(rule.mocks().packageParser)

        installPackageHelper = InstallPackageHelper(
            pms,
            mock(AppDataHelper::class.java),
            mock(RemovePackageHelper::class.java),
            mock(DeletePackageHelper::class.java),
            broadcastHelper
        )
    }

    @Test
    fun installPackages_deviceAdminOnExternalStorage_failsVerification() {
        // Create an InstallRequest mock targeting external storage
        val request = mock(InstallRequest::class.java)
        whenever(request.volumeUuid).thenReturn("some-external-uuid")
        whenever(request.installFlags).thenReturn(0)
        whenever(request.installReason).thenReturn(0)
        whenever(request.packageLite).thenReturn(null)
        whenever(request.isArchived).thenReturn(false)

        // Point codePath to the staged DEVICE_ADMIN_PACKAGE's mock base APK path
        val dummyApkFile =
            File(
                File(rule.system().dataAppDirectory, DEVICE_ADMIN_PACKAGE),
                "base.apk"
            )
        whenever(request.codePath).thenReturn(dummyApkFile.absolutePath)

        // Trigger the installation phase using the InstallPackageHelper instance directly
        val requests = listOf(request)
        installPackageHelper.installPackagesTraced(requests, null)

        // Capture error callback on request
        val codeCaptor = ArgumentCaptor.forClass(Int::class.java)
        val msgCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(request).setError(codeCaptor.capture(), msgCaptor.capture())

        // Verify that the prepare phase failed specifically because Device Admin cannot be installed externally
        assertThat(codeCaptor.value).isEqualTo(PackageManager.INSTALL_FAILED_INVALID_INSTALL_LOCATION)
        assertThat(msgCaptor.value).contains("Device Admin can only be installed on internal storage.")
    }
}
