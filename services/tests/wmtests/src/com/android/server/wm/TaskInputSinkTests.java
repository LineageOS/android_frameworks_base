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

package com.android.server.wm;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.InputConfig;
import android.platform.test.annotations.Presubmit;
import android.view.InputWindowHandle;
import android.view.SurfaceControl;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Test class for {@link TaskInputSink}.
 *
 * Build/Install/Run:
 * atest WmTests:TaskInputSinkTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class TaskInputSinkTests extends WindowTestsBase {

    private Task mTaskSpy;
    private TaskInputSink mTaskInputSinkSpy;

    @Before
    public void setUp() throws Exception {
        mTaskSpy = spy(createTask(mDisplayContent));
        mTaskInputSinkSpy = spy(new TaskInputSink(mTaskSpy));
    }

    @Test
    public void testApplyChangesToSurface_inTransition_allowPassthrough() {
        final SurfaceControl.Transaction t = mTaskSpy.getPendingTransaction();
        when(mTaskSpy.inTransition()).thenReturn(true);

        Mockito.reset(t);
        mTaskInputSinkSpy.applyChangesToSurfaceIfChanged(t);
        ArgumentCaptor<InputWindowHandle> inputWindowHandleArgumentCaptor =
                ArgumentCaptor.forClass(InputWindowHandle.class);
        verify(t).setInputWindowInfo(any(), inputWindowHandleArgumentCaptor.capture());

        int inputConfig = inputWindowHandleArgumentCaptor.getValue().inputConfig;
        assertThat(inputConfig & InputConfig.NOT_TOUCHABLE)
                .isEqualTo(InputConfig.NOT_TOUCHABLE);
    }

    @Test
    public void testApplyChangesToSurface_notInTransition_disallowPassthrough() {
        final SurfaceControl.Transaction t = mTaskSpy.getPendingTransaction();
        when(mTaskSpy.inTransition()).thenReturn(false);

        Mockito.reset(t);
        mTaskInputSinkSpy.applyChangesToSurfaceIfChanged(t);
        ArgumentCaptor<InputWindowHandle> inputWindowHandleArgumentCaptor =
                ArgumentCaptor.forClass(InputWindowHandle.class);
        verify(t).setInputWindowInfo(any(), inputWindowHandleArgumentCaptor.capture());

        int inputConfig = inputWindowHandleArgumentCaptor.getValue().inputConfig;
        assertThat(inputConfig & InputConfig.NOT_TOUCHABLE).isEqualTo(InputConfig.DEFAULT);
    }
}
