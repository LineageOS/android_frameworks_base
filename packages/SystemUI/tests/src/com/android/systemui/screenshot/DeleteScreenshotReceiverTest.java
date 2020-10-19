/*
 * Copyright (C) 2020 The Android Open Source Project
 * Copyright (C) 2025 The LineageOS Project
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

package com.android.systemui.screenshot;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.util.concurrent.Executor;

@RunWith(AndroidTestingRunner.class)
@SmallTest
public class DeleteScreenshotReceiverTest extends SysuiTestCase {

    @Mock
    private Executor mMockExecutor;

    private DeleteScreenshotReceiver mDeleteScreenshotReceiver;
    private FakeExecutor mFakeExecutor = new FakeExecutor(new FakeSystemClock());

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mDeleteScreenshotReceiver = new DeleteScreenshotReceiver(mMockExecutor);
    }

    @Test
    public void testNoUriProvided() {
        Intent intent = new Intent(mContext, DeleteScreenshotReceiver.class);

        mDeleteScreenshotReceiver.onReceive(mContext, intent);

        verify(mMockExecutor, never()).execute(any(Runnable.class));
    }

    @Test
    public void testFileDeleted() {
        ContentResolver contentResolver = mContext.getContentResolver();
        final Uri testUri = contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, getFakeContentValues());
        assertNotNull(testUri);

        try {
            Cursor cursor =
                    contentResolver.query(testUri, null, null, null, null);
            assertEquals(1, cursor.getCount());
            Intent intent = new Intent(mContext, DeleteScreenshotReceiver.class);
            intent.setData(testUri);

            mDeleteScreenshotReceiver.onReceive(mContext, intent);
            int runCount = mFakeExecutor.runAllReady();

            assertEquals(1, runCount);
            cursor = contentResolver.query(testUri, null, null, null, null);
            assertEquals(0, cursor.getCount());
        } finally {
            contentResolver.delete(testUri, null, null);
        }
    }

    private static ContentValues getFakeContentValues() {
        final ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES
                + File.separator + Environment.DIRECTORY_SCREENSHOTS);
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "test_screenshot");
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
        values.put(MediaStore.MediaColumns.DATE_ADDED, 0);
        values.put(MediaStore.MediaColumns.DATE_MODIFIED, 0);
        return values;
    }
}
