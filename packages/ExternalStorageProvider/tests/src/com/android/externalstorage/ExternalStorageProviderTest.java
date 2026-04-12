/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.externalstorage;

import static android.provider.DocumentsContract.EXTERNAL_STORAGE_PRIMARY_EMULATED_ROOT_ID;
import static android.provider.Flags.FLAG_ENABLE_DOCUMENTS_TRASH_API;

import static com.android.externalstorage.ExternalStorageProvider.AUTHORITY;
import static com.android.externalstorage.ExternalStorageProvider.getPathFromDocId;
import static com.android.providers.media.flags.Flags.FLAG_ENABLE_TRASH_AND_RESTORE_BY_FILE_PATH_API;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * These tests don't fake / mock StorageManager and thus can only test simpler functionality.
 */
@RunWith(AndroidJUnit4.class)
public class ExternalStorageProviderTest {

    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();
    @NonNull
    private static final Instrumentation sInstrumentation =
            InstrumentationRegistry.getInstrumentation();
    @NonNull
    private static final Context sTargetContext = sInstrumentation.getTargetContext();

    private ExternalStorageProvider mExternalStorageProvider;

    @Before
    public void setUp() {
        mExternalStorageProvider = new ExternalStorageProvider();

        final ProviderInfo providerInfo = new ProviderInfo();
        providerInfo.authority = AUTHORITY;
        providerInfo.grantUriPermissions = true;
        providerInfo.exported = true;
        sInstrumentation.runOnMainSync(() ->
                mExternalStorageProvider.attachInfoForTesting(sTargetContext, providerInfo));
    }

    @Test
    public void onCreate_shouldUpdateVolumes() {
        final ExternalStorageProvider spyProvider = spy(new ExternalStorageProvider());
        final ProviderInfo providerInfo = new ProviderInfo();
        providerInfo.authority = AUTHORITY;
        providerInfo.grantUriPermissions = true;
        providerInfo.exported = true;

        sInstrumentation.runOnMainSync(() ->
                spyProvider.attachInfoForTesting(sTargetContext, providerInfo));

        verify(spyProvider, atLeast(1)).updateVolumes();
    }

    @Test
    public void test_getPathFromDocId() {
        final String root = "root";
        final String path = "abc/def/ghi";
        String docId = root + ":" + path;
        assertEquals(getPathFromDocId(docId), path);

        docId = root + ":" + path + "/";
        assertEquals(getPathFromDocId(docId), path);

        docId = root + ":";
        assertTrue(getPathFromDocId(docId).isEmpty());

        docId = root + ":./" + path;
        assertEquals(getPathFromDocId(docId), path);

        final String dotPath = "abc/./def/ghi";
        docId = root + ":" + dotPath;
        assertEquals(getPathFromDocId(docId), path);

        final String twoDotPath = "abc/../abc/def/ghi";
        docId = root + ":" + twoDotPath;
        assertEquals(getPathFromDocId(docId), path);
    }

    @Test
    public void test_shouldHideDocument() {
        // Should hide "Android/data", "Android/obb", "Android/sandbox" and all their
        // "subtrees".
        final String[] shouldHide = {
                // "Android/data" and all its subdirectories
                "Android/data",
                "Android/data/com.my.app",
                "Android/data/com.my.app/cache",
                "Android/data/com.my.app/cache/image.png",
                "Android/data/mydata",

                // "Android/obb" and all its subdirectories
                "Android/obb",
                "Android/obb/com.my.app",
                "Android/obb/com.my.app/file.blob",

                // "Android/sandbox" and all its subdirectories
                "Android/sandbox",
                "Android/sandbox/com.my.app",

                // Also make sure we are not allowing path traversals
                "Android/./data",
                "Android/Download/../data",
        };
        for (String path : shouldHide) {
            final String docId = buildDocId(path);
            assertTrue("ExternalStorageProvider should hide \"" + docId + "\", but it didn't",
                    mExternalStorageProvider.shouldHideDocument(docId));
        }

        // Should NOT hide anything else.
        final String[] shouldNotHide = {
                "Android",
                "Android/datadir",
                "Documents",
                "Download",
                "Music",
                "Pictures",
        };
        for (String path : shouldNotHide) {
            final String docId = buildDocId(path);
            assertFalse("ExternalStorageProvider should NOT hide \"" + docId + "\", but it did",
                    mExternalStorageProvider.shouldHideDocument(docId));
        }
    }

    @Test
    @RequiresFlagsEnabled({FLAG_ENABLE_TRASH_AND_RESTORE_BY_FILE_PATH_API,
            FLAG_ENABLE_DOCUMENTS_TRASH_API})
    public void test_trashDocument() throws Exception {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        File trashDir = Environment.getExternalStoragePublicDirectory("/.trash-storage");
        File targetFile = new File(downloadsDir, "example.txt");
        File volumePath = getPrimaryVolumePath();
        try {
            targetFile.createNewFile();
            MediaScannerConnection.scanFile(sTargetContext,
                    new String[]{targetFile.getAbsolutePath()},
                    null, null);

            assertTrue("Original target file should exist after creation", targetFile.exists());

            String docId = targetFile.getPath().replace(volumePath.getPath() + File.separator,
                    "");

            // trash document
            String trashedDocId = mExternalStorageProvider.trashDocument(buildDocId(docId));
            String trashedPath = ExternalStorageProvider.getPathFromDocId(trashedDocId);
            File trashedFile = new File(volumePath, trashedPath);
            assertFalse("Original target file should not exist after trashing",
                    targetFile.exists());
            assertTrue("Trashed file should exist after document is moved to trash",
                    trashedFile.exists());

            assertTrue("Trashed file should be a descendant of the trash directory",
                    trashedFile.getCanonicalPath().startsWith(trashDir.getCanonicalPath()));
        } finally {
            CleanupTemporaryFilesRule.removeFilesRecursively(downloadsDir);
            CleanupTemporaryFilesRule.removeFilesRecursively(trashDir);
        }
    }


    @Test
    @RequiresFlagsEnabled({FLAG_ENABLE_TRASH_AND_RESTORE_BY_FILE_PATH_API,
            FLAG_ENABLE_DOCUMENTS_TRASH_API})
    public void test_queryTrashDocument() throws Exception {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        File trashDir = new File(
                Environment.getExternalStorageDirectory().getPath() + "/.trash-storage");
        File targetFile = new File(downloadsDir, "example.txt");
        File volumePath = getPrimaryVolumePath();
        try {
            targetFile.createNewFile();
            MediaScannerConnection.scanFile(sTargetContext,
                    new String[]{targetFile.getAbsolutePath()},
                    null, null);

            assertTrue("Original target file should exist after creation", targetFile.exists());

            String docId = targetFile.getPath().replace(volumePath.getPath() + File.separator,
                    "");

            // trash document
            String trashedDocId = mExternalStorageProvider.trashDocument(buildDocId(docId));
            String trashedPath = ExternalStorageProvider.getPathFromDocId(trashedDocId);
            File trashedFile = new File(volumePath, trashedPath);
            assertFalse("Original target file should not exist after trashing",
                    targetFile.exists());
            assertTrue("Trashed file should exist after document is moved to trash",
                    trashedFile.exists());

            assertTrue("Trashed file should be a descendant of the trash directory",
                    trashedFile.getCanonicalPath().startsWith(trashDir.getCanonicalPath()));

            // query trash documents
            try (Cursor c = mExternalStorageProvider.queryTrashDocuments(
                    EXTERNAL_STORAGE_PRIMARY_EMULATED_ROOT_ID, /* projection */
                    null, null, null)) {
                assertEquals("Querying trash documents should return exactly one item", 1,
                        c.getCount());
                assertTrue("Cursor should move to the first item in trash documents",
                        c.moveToFirst());
                String queryTrashedDocumentId = c.getString(
                        c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID));
                String queryTrashedDocumentPath = ExternalStorageProvider.getPathFromDocId(
                        queryTrashedDocumentId);
                File queryTrashedFile = new File(volumePath, queryTrashedDocumentPath);
                assertEquals(
                        "The queried trashed file should match the originally trashed file",
                        trashedFile, queryTrashedFile);
                String originalRelativePath = c.getString(
                        c.getColumnIndex(DocumentsContract.Document.COLUMN_ORIGINAL_RELATIVE_PATH));
                assertEquals(originalRelativePath, downloadsDir.getName());
            }
        } finally {
            CleanupTemporaryFilesRule.removeFilesRecursively(downloadsDir);
            CleanupTemporaryFilesRule.removeFilesRecursively(trashDir);
        }
    }


    @Test
    @RequiresFlagsEnabled({FLAG_ENABLE_TRASH_AND_RESTORE_BY_FILE_PATH_API,
            FLAG_ENABLE_DOCUMENTS_TRASH_API})
    public void test_restoreDocumentFromTrash() throws Exception {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        File trashDir = new File(
                Environment.getExternalStorageDirectory().getPath() + "/.trash-storage");
        File volumePath = getPrimaryVolumePath();
        File targetFile = new File(downloadsDir, "example.txt");
        try {
            targetFile.createNewFile();
            MediaScannerConnection.scanFile(sTargetContext,
                    new String[]{targetFile.getAbsolutePath()},
                    null, null);

            assertTrue("Original target file should exist after creation", targetFile.exists());

            String docId = targetFile.getPath().replace(volumePath.getPath() + File.separator,
                    "");

            // Trash document
            String trashedDocId = mExternalStorageProvider.trashDocument(buildDocId(docId));

            String trashedPath = ExternalStorageProvider.getPathFromDocId(trashedDocId);
            File trashedFile = new File(volumePath, trashedPath);

            assertFalse("Original target file should not exist after trashing",
                    targetFile.exists());
            assertTrue("Trashed file should exist after document is moved to trash",
                    trashedFile.exists());
            assertTrue("Trashed file should be a descendant of the trash directory",
                    trashedFile.getCanonicalPath().startsWith(trashDir.getCanonicalPath()));

            // query trash items
            try (Cursor c = mExternalStorageProvider.queryTrashDocuments(
                    EXTERNAL_STORAGE_PRIMARY_EMULATED_ROOT_ID, /* projection */
                    null, null, null)) {
                assertEquals("Querying trash documents should return exactly one item", 1,
                        c.getCount());
            }

            // restoreDocumentFromTrash
            String restoredDocId = mExternalStorageProvider.restoreDocumentFromTrash(
                    trashedDocId, /* targetParentDocId */ null);

            String restoredPath = ExternalStorageProvider.getPathFromDocId(restoredDocId);
            File restoredFile = new File(volumePath, restoredPath);


            assertTrue("After restore, the target file should exist", targetFile.exists());
            assertFalse("After restore, the file should no longer be in the trash directory",
                    trashedFile.exists());
            assertEquals("Target file and restored file should be the same instance",
                    targetFile, restoredFile);

            // query trash items after restore
            try (Cursor c = mExternalStorageProvider.queryTrashDocuments(
                    EXTERNAL_STORAGE_PRIMARY_EMULATED_ROOT_ID, /* projection */
                    null, null, null)) {
                assertEquals("Querying trash documents should return 0 item", 0,
                        c.getCount());
            }
        } finally {
            CleanupTemporaryFilesRule.removeFilesRecursively(downloadsDir);
            CleanupTemporaryFilesRule.removeFilesRecursively(trashDir);
        }
    }

    @Test
    public void test_shouldBlockDirectoryFromTree() throws Exception {
        final String[] shouldBlock = {
                "Android",
                "Download",
                "Android\u200B",
                "Download\u200B",
                "Android/\u200B",
                "android",
                "DOWNLOAD",
        };
        for (String path : shouldBlock) {
            final String docId = buildDocId(path);
            try {
                assertTrue("ExternalStorageProvider should block \"" + docId + "\", but it didn't",
                        mExternalStorageProvider.shouldBlockDirectoryFromTree(docId));
            } catch (FileNotFoundException ignored) {
                // If the file doesn't exist, ignored.
            }
        }

        final String[] shouldNotBlock = {
                "Android/datadir",
                "Download/my_file",
                "Documents",
        };
        for (String path : shouldNotBlock) {
            final String docId = buildDocId(path);
            try {
                assertFalse(
                        "ExternalStorageProvider should NOT block \"" + docId + "\", but it did",
                        mExternalStorageProvider.shouldBlockDirectoryFromTree(docId));
            } catch (FileNotFoundException ignored) {
                // If the file doesn't exist, ignored.
            }
        }
    }

    @NonNull
    private static String buildDocId(@NonNull String path) {
        return buildDocId(EXTERNAL_STORAGE_PRIMARY_EMULATED_ROOT_ID, path);
    }

    @NonNull
    private static String buildDocId(@NonNull String root, @NonNull String path) {
        // docId format: root:path/to/file
        return root + ':' + path;
    }

    @NonNull
    private static File getPrimaryVolumePath() {
        return sTargetContext.getSystemService(StorageManager.class)
                .getStorageVolume(MediaStore.Files.getContentUri(
                        MediaStore.VOLUME_EXTERNAL_PRIMARY)).getDirectory();
    }

    @Test
    public void testIsTrashSupported() throws Exception {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        File recordingsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_RECORDINGS);
        File trashDir = Environment.getExternalStoragePublicDirectory("/.trash-storage");
        File testFile = new File(downloadsDir, "example.txt");
        File testFileInTrash = new File(trashDir, "example.txt");
        try {
            testFile.createNewFile();
            recordingsDir.mkdir();
            trashDir.mkdir();
            testFileInTrash.createNewFile();
            MediaScannerConnection.scanFile(sTargetContext,
                    new String[]{testFile.getAbsolutePath(), recordingsDir.getAbsolutePath(),
                            testFileInTrash.getAbsolutePath()},
                    null, null);

            assertTrue("Trash should be supported for a regular file",
                    mExternalStorageProvider.isTrashSupported(testFile));
            assertFalse("Trash should not be supported for a top-level default directory",
                    mExternalStorageProvider.isTrashSupported(recordingsDir));
            assertFalse("Trash should not be supported for a file in trash",
                    mExternalStorageProvider.isTrashSupported(testFileInTrash));
        } finally {
            CleanupTemporaryFilesRule.removeFilesRecursively(downloadsDir);
            CleanupTemporaryFilesRule.removeFilesRecursively(recordingsDir);
            CleanupTemporaryFilesRule.removeFilesRecursively(trashDir);
        }
    }

    @Test
    @RequiresFlagsEnabled({FLAG_ENABLE_TRASH_AND_RESTORE_BY_FILE_PATH_API,
            FLAG_ENABLE_DOCUMENTS_TRASH_API})
    public void test_queryTrashDocuments_setsNotificationUri() throws Exception {
        // query trash documents
        try (Cursor c = mExternalStorageProvider.queryTrashDocuments(
                EXTERNAL_STORAGE_PRIMARY_EMULATED_ROOT_ID, null, null, null)) {
            Uri expectedUri = DocumentsContract.buildTrashDocumentsUri(AUTHORITY,
                    EXTERNAL_STORAGE_PRIMARY_EMULATED_ROOT_ID);
            assertEquals("Cursor should have the trash notification URI set",
                    expectedUri, c.getNotificationUri());
        }
    }

    @Test
    @RequiresFlagsEnabled({FLAG_ENABLE_TRASH_AND_RESTORE_BY_FILE_PATH_API,
            FLAG_ENABLE_DOCUMENTS_TRASH_API})
    public void test_deleteDocument_notifiesTrashChange() throws Exception {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        File targetFile = new File(downloadsDir, "test_delete.txt");
        File volumePath = getPrimaryVolumePath();
        try {
            targetFile.createNewFile();
            MediaScannerConnection.scanFile(sTargetContext,
                    new String[]{targetFile.getAbsolutePath()},
                    null, null);
            String docId = targetFile.getPath().replace(volumePath.getPath() + File.separator, "");
            String trashedDocId = mExternalStorageProvider.trashDocument(buildDocId(docId));

            // Call queryTrashDocuments to get the notification URI set by the provider
            Uri trashNotificationUri;
            try (Cursor c = mExternalStorageProvider.queryTrashDocuments(
                    EXTERNAL_STORAGE_PRIMARY_EMULATED_ROOT_ID, null, null, null)) {
                trashNotificationUri = c.getNotificationUri();
            }

            assertNotNull("Trash notification URI should not be null", trashNotificationUri);

            // Setup observer using the URI obtained from the cursor
            CountDownLatch latch = new CountDownLatch(1);
            ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    if (trashNotificationUri.equals(uri)) {
                        latch.countDown();
                    }
                }
            };
            sTargetContext.getContentResolver().registerContentObserver(
                    trashNotificationUri, false, observer);

            try {
                // Action: Delete the trashed document
                mExternalStorageProvider.deleteDocument(trashedDocId);

                // Verify: Notification was sent to the trash URI
                assertTrue("Notification should be sent when a trashed document is deleted",
                        latch.await(5, TimeUnit.SECONDS));
            } finally {
                sTargetContext.getContentResolver().unregisterContentObserver(observer);
            }
        } finally {
            CleanupTemporaryFilesRule.removeFilesRecursively(downloadsDir);
        }
    }

    @Test
    @RequiresFlagsEnabled({FLAG_ENABLE_TRASH_AND_RESTORE_BY_FILE_PATH_API,
            FLAG_ENABLE_DOCUMENTS_TRASH_API})
    public void test_restoreDocumentFromTrash_notifiesTrashChange() throws Exception {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        File targetFile = new File(downloadsDir, "test_restore.txt");
        File volumePath = getPrimaryVolumePath();
        try {
            targetFile.createNewFile();
            MediaScannerConnection.scanFile(sTargetContext,
                    new String[]{targetFile.getAbsolutePath()},
                    null, null);
            String docId = targetFile.getPath().replace(volumePath.getPath() + File.separator, "");
            String trashedDocId = mExternalStorageProvider.trashDocument(buildDocId(docId));

            // Call queryTrashDocuments to get the notification URI set by the provider
            Uri trashNotificationUri;
            try (Cursor c = mExternalStorageProvider.queryTrashDocuments(
                    EXTERNAL_STORAGE_PRIMARY_EMULATED_ROOT_ID, null, null, null)) {
                trashNotificationUri = c.getNotificationUri();
            }

            assertNotNull("Trash notification URI should not be null", trashNotificationUri);

            // Setup observer using the URI obtained from the cursor
            CountDownLatch latch = new CountDownLatch(1);
            ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    if (trashNotificationUri.equals(uri)) {
                        latch.countDown();
                    }
                }
            };
            sTargetContext.getContentResolver().registerContentObserver(
                    trashNotificationUri, false, observer);

            try {
                // Action: Restore the document
                mExternalStorageProvider.restoreDocumentFromTrash(trashedDocId, null);

                // Verify: Notification was sent to the trash URI
                assertTrue("Notification should be sent when a trashed document is restored",
                        latch.await(5, TimeUnit.SECONDS));
            } finally {
                sTargetContext.getContentResolver().unregisterContentObserver(observer);
            }
        } finally {
            CleanupTemporaryFilesRule.removeFilesRecursively(downloadsDir);
        }
    }
}
