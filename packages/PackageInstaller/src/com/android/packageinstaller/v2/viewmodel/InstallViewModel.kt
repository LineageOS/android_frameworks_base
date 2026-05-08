/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.packageinstaller.v2.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.distinctUntilChanged
import com.android.packageinstaller.stats.StatsUtil.PIA_INSTALL_STAGE_FAILED
import com.android.packageinstaller.stats.StatsUtil.PIA_INSTALL_STAGE_INSTALLING
import com.android.packageinstaller.stats.StatsUtil.PIA_INSTALL_STAGE_STAGING
import com.android.packageinstaller.stats.StatsUtil.PIA_INSTALL_STAGE_SUCCESS
import com.android.packageinstaller.stats.StatsUtil.PIA_INSTALL_STAGE_USER_ACTION_REQUIRED
import com.android.packageinstaller.stats.StatsUtil.PIA_INSTALL_STAGE_VERIFICATION_FAILURE
import com.android.packageinstaller.v2.model.InstallRepository
import com.android.packageinstaller.v2.model.InstallStage
import com.android.packageinstaller.v2.model.InstallStaging

class InstallViewModel(application: Application, val repository: InstallRepository) :
    AndroidViewModel(application) {

    companion object {
        private val LOG_TAG = InstallViewModel::class.java.simpleName
    }

    private val _currentInstallStage = MediatorLiveData<InstallStage>(InstallStaging())
    val currentInstallStage: MutableLiveData<InstallStage>
        get() = _currentInstallStage

    var isPreprocessed: Boolean = false
        private set

    init {
        // Since installing is an async operation, we may get the install result later in time.
        // Result of the installation will be set in InstallRepository#installResult.
        // As such, currentInstallStage will need to add another MutableLiveData as a data source
        _currentInstallStage.addSource(
            repository.installResult.distinctUntilChanged()
        ) { installStage: InstallStage? ->
            if (installStage != null) {
                    updateInstallStage(installStage)
                _currentInstallStage.value = installStage
            }
        }

        // Since staging is an async operation, we will get the staging result later in time.
        // Result of the file staging will be set in InstallRepository#mStagingResult.
        // As such, mCurrentInstallStage will need to add another MutableLiveData
        // as a data source
        _currentInstallStage.addSource(
            repository.stagingResult.distinctUntilChanged()
        ) { installStage: InstallStage ->
            when (installStage.stageCode) {
                InstallStage.STAGE_READY -> checkIfAllowedAndInitiateInstall()
                InstallStage.STAGE_VERIFICATION_CONFIRMATION_REQUIRED -> {
                    if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
                        requestVerification()
                    }
                }
                else -> updateInstallStage(installStage)
            }
        }
    }

    /**
     * Single source of truth for updating the current install stage.
     * Updates the LiveData and automatically tracks the telemetry stage.
     */
    private fun updateInstallStage(stage: InstallStage) {
        _currentInstallStage.value = stage
        trackInstallStage(stage)
    }

    fun preprocessIntent(intent: Intent, callerInfo: InstallRepository.CallerInfo) {
        isPreprocessed = true
        val stage = repository.performPreInstallChecks(intent, callerInfo)
        if (stage.stageCode == InstallStage.STAGE_ABORTED
            || stage.stageCode == InstallStage.STAGE_VERIFICATION_FAILURE) {
            updateInstallStage(stage)
        } else {
            trackInstallStage(stage)
            repository.stageForInstall()
        }
    }

    val stagingProgress: LiveData<Int>
        get() = repository.stagingProgress

    private fun checkIfAllowedAndInitiateInstall() {
        val stage = repository.requestUserConfirmation()
        if (stage != null) {
            updateInstallStage(stage)
        }
    }

    @RequiresApi(Build.VERSION_CODES_FULL.BAKLAVA_1)
    private fun requestVerification() {
        val stage = repository.requestVerificationConfirmation()
        updateInstallStage(stage)
    }

    @RequiresApi(Build.VERSION_CODES_FULL.BAKLAVA_1)
    fun onNegativeVerificationUserResponse() {
        val stage = repository.setNegativeVerificationUserResponse()
        updateInstallStage(stage)
    }

    @RequiresApi(Build.VERSION_CODES_FULL.BAKLAVA_1)
    fun onPositiveVerificationUserResponse() {
        val stage =
            repository.setPositiveVerificationUserResponse()
        updateInstallStage(stage)
    }

    @RequiresApi(Build.VERSION_CODES_FULL.BAKLAVA_1)
    fun onRetryVerificationUserResponse() {
        val stage =
            repository.setRetryVerificationUserResponse()
        updateInstallStage(stage)
    }

    fun forcedSkipSourceCheck() {
        val stage = repository.requestUserConfirmation(/* forceSourceCheck= */ false)
        if (stage != null) {
            updateInstallStage(stage)
        }
    }

    fun cleanupInstall() {
        repository.cleanupInstall()
    }

    fun reattemptInstall() {
        val stage = repository.reattemptInstall()
        updateInstallStage(stage)
    }

    fun initiateInstall() {
        repository.initiateInstall()
    }

    fun abortStaging() {
        repository.abortStaging()
    }

    /**
     * Track the installation stage and log the corresponding StatsD stage.
     *
     * @param installStage The installation stage to track.
     */
    fun trackInstallStage(installStage: InstallStage) {
        // 1. Log the corresponding StatsD stage
        when (installStage.stageCode) {
            InstallStage.STAGE_STAGING -> repository.piaStagesLatencyTracker.startRecordingNextStage(PIA_INSTALL_STAGE_STAGING)
            InstallStage.STAGE_USER_ACTION_REQUIRED -> repository.piaStagesLatencyTracker.startRecordingNextStage(PIA_INSTALL_STAGE_USER_ACTION_REQUIRED)
            InstallStage.STAGE_INSTALLING -> repository.piaStagesLatencyTracker.startRecordingNextStage(PIA_INSTALL_STAGE_INSTALLING)
            InstallStage.STAGE_SUCCESS -> repository.piaStagesLatencyTracker.startRecordingNextStage(PIA_INSTALL_STAGE_SUCCESS)
            InstallStage.STAGE_FAILED -> repository.piaStagesLatencyTracker.startRecordingNextStage(PIA_INSTALL_STAGE_FAILED)
            InstallStage.STAGE_VERIFICATION_FAILURE -> repository.piaStagesLatencyTracker.startRecordingNextStage(PIA_INSTALL_STAGE_VERIFICATION_FAILURE)
        }

        // 2. Stop recording and log if this is a terminal stage
        if (installStage.stageCode == InstallStage.STAGE_SUCCESS ||
            installStage.stageCode == InstallStage.STAGE_FAILED ||
            installStage.stageCode == InstallStage.STAGE_ABORTED) {
            repository.piaStagesLatencyTracker.stopRecordingAndLog()
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Catch-all: Ensure the latency tracker always dispatches the atom
        // when the PackageInstaller UI is closed or finishes early.
        repository.piaStagesLatencyTracker.stopRecordingAndLog()
    }

    val stagedSessionId: Int
        get() = repository.stagedSessionId
}
