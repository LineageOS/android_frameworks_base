/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.server.wm;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.platform.test.annotations.Presubmit;
import android.window.TaskFragmentOrganizer;


import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test class for {@link ActivityRecordInputSink}.
 *
 * Build/Install/Run:
 * atest WmTests:ActivityRecordInputSinkTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class ActivityRecordInputSinkTests extends WindowTestsBase {

    private Task mTask;
    private ActivityRecord mActivity;
    private ActivityRecordInputSink mInputSink;
    private TaskFragmentOrganizer mOrganizer;

    @Before
    public void setUp() throws Exception {
        mTask = createTask(mDisplayContent);
        mActivity = createActivityRecord(mTask);
        mInputSink = new ActivityRecordInputSink(mActivity, null);
        mOrganizer = new TaskFragmentOrganizer(Runnable::run);
    }

    @Test
    public void testApplyChanges_DifferentUid_BlocksTouchesFromTop() {
        // Create a TaskFragment on top of the activity with a different UID
        // By default, ActivityBuilder uses DEFAULT_FAKE_UID (12345)
        // and TaskFragmentOrganizer uses DEFAULT_TASK_FRAGMENT_ORGANIZER_UID (10000)
        final TaskFragment tf = createTaskFragmentWithEmbeddedActivity(mTask, mOrganizer);
        mTask.positionChildAt(WindowContainer.POSITION_BOTTOM, mActivity, false);
        mTask.positionChildAt(WindowContainer.POSITION_TOP, tf, false);
        mActivity.setVisibleRequested(true);
        tf.setVisibleRequested(true);
        when(mActivity.inTransition()).thenReturn(false);

        reset(mTransaction);
        mInputSink.applyChangesToSurfaceIfChanged(mTransaction);

        // The input sink is blocking touches from top
        verify(mTransaction).setLayer(any(), eq(Integer.MAX_VALUE));
    }

    @Test
    public void testApplyChanges_SameUid_AllowsTouchesFromTop() {
        final TaskFragment tf = createTaskFragmentWithEmbeddedActivity(mTask, mOrganizer);
        // Force the TaskFragment to have the same UID as the activity
        tf.mTaskFragmentOrganizerUid = mActivity.getUid();
        mTask.positionChildAt(WindowContainer.POSITION_BOTTOM, mActivity, false);
        mTask.positionChildAt(WindowContainer.POSITION_TOP, tf, false);
        mActivity.setVisibleRequested(true);
        tf.setVisibleRequested(true);
        when(mActivity.inTransition()).thenReturn(false);

        reset(mTransaction);
        mInputSink.applyChangesToSurfaceIfChanged(mTransaction);

        // The input sink stays at the bottom
        verify(mTransaction).setLayer(any(), eq(Integer.MIN_VALUE));
    }
}
