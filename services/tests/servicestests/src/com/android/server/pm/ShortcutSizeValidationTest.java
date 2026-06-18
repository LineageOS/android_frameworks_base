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
package com.android.server.pm;

import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertExpectException;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertShortcutIds;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.findShortcut;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.list;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Person;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.os.PersistableBundle;
import androidx.test.filters.SmallTest;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

@SmallTest
public class ShortcutSizeValidationTest extends BaseShortcutManagerTest {

    private String repeat(char c, int count) {
        char[] arr = new char[count];
        Arrays.fill(arr, c);
        return new String(arr);
    }

    private Person[] createPersons(int count, int nameLength) {
        Person[] persons = new Person[count];
        for (int i = 0; i < count; i++) {
            persons[i] = new Person.Builder()
                    .setName(repeat('p', nameLength))
                    .setKey("person_key_" + i)
                    .build();
        }
        return persons;
    }

    private ShortcutInfo.Builder makeBuilder(String id) {
        return new ShortcutInfo.Builder(mClientContext, id)
                .setShortLabel("Label-" + id)
                .setIntent(makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class));
    }


    @Test
    public void testPushDynamicShortcut_oversizedAndUntrimmable_dropsSilently() {
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            // Intent with 35KB extra -> ~35KB total size.
            // Intent extras are not trimmable, so this cannot be trimmed under 32KB.
            Intent intent = makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class);
            intent.putExtra("large_extra", repeat('e', 35000));
            ShortcutInfo si = makeBuilder("untrimmable")
                    .setIntent(intent)
                    .build();

            // Pushing the untrimmable shortcut should not crash, but it should not be published.
            mManager.pushDynamicShortcut(si);

            List<ShortcutInfo> active = mManager.getDynamicShortcuts();
            // Should be empty (shortcut was dropped).
            assertTrue(active == null || active.isEmpty());
        });
    }

    @Test
    public void testPushDynamicShortcut_oversizedAndTrimmablePersons_succeedsWithTrim() {
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            // 25 Persons, each with 1.5KB name -> ~37KB total size.
            // Trimming to 20 persons -> ~30KB total size (under 32KB).
            // This should succeed.
            ShortcutInfo si = makeBuilder("trimmable_persons")
                    .setPersons(createPersons(25, 1500))
                    .build();

            mManager.pushDynamicShortcut(si);

            // Verify it was published and trimmed to 20 persons
            List<ShortcutInfo> active = mManager.getDynamicShortcuts();
            assertShortcutIds(active, "trimmable_persons");
            ShortcutInfo published = findShortcut(active, "trimmable_persons");
            assertNotNull(published);
            assertNotNull(published.getPersons());
            assertEquals(20, published.getPersons().length);
        });
    }

    @Test
    public void testPushDynamicShortcut_oversizedAndTrimmableExtras_succeedsWithTrim() {
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            // Normal size persons (5 persons * 1KB = 5KB).
            // Large extras (30KB) -> ~35KB total size.
            // Trimming persons to 20 does nothing (already 5).
            // Trimming extras clears them -> ~5KB total size (under 32KB).
            // This should succeed, but extras should be cleared.
            PersistableBundle extras = new PersistableBundle();
            extras.putString("large_key", repeat('e', 30000));

            ShortcutInfo si = makeBuilder("trimmable_extras")
                    .setPersons(createPersons(5, 1000))
                    .setExtras(extras)
                    .build();

            mManager.pushDynamicShortcut(si);

            // Verify it was published, persons are intact (5), but extras are cleared (empty)
            List<ShortcutInfo> active = mManager.getDynamicShortcuts();
            assertShortcutIds(active, "trimmable_extras");
            ShortcutInfo published = findShortcut(active, "trimmable_extras");
            assertNotNull(published);
            assertNotNull(published.getPersons());
            assertEquals(5, published.getPersons().length);

            PersistableBundle publishedExtras = published.getExtras();
            assertTrue(publishedExtras == null || publishedExtras.isEmpty());
        });
    }

    @Test
    public void testBulkApi_dropsOversizedAndPublishesValid() {
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            // s1: Normal shortcut (~1KB)
            ShortcutInfo s1 = makeShortcut("s1");

            // s2: Oversized and untrimmable (~35KB)
            Intent intentS2 = makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class);
            intentS2.putExtra("large_extra", repeat('e', 35000));
            ShortcutInfo s2 = makeBuilder("s2")
                    .setIntent(intentS2)
                    .build();

            // s3: Oversized but trimmable (25 persons * 1.5KB = ~37KB, trims to 20 * 1.5KB = ~30KB)
            ShortcutInfo s3 = makeBuilder("s3")
                    .setPersons(createPersons(25, 1500))
                    .build();

            // Call addDynamicShortcuts with all three
            assertTrue(mManager.addDynamicShortcuts(list(s1, s2, s3)));

            // Verify:
            // - s1 is published
            // - s2 is NOT published (dropped)
            // - s3 is published (trimmed)
            List<ShortcutInfo> active = mManager.getDynamicShortcuts();
            assertShortcutIds(active, "s1", "s3");

            ShortcutInfo publishedS3 = findShortcut(active, "s3");
            assertNotNull(publishedS3);
            assertEquals(20, publishedS3.getPersons().length);
        });
    }

    @Test
    public void testBulkApi_allOversizedAndDropped_succeedsSilently() {
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            // s1 & s2: Both oversized and untrimmable
            Intent intentS1 = makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class);
            intentS1.putExtra("large_extra", repeat('e', 35000));
            ShortcutInfo s1 = makeBuilder("s1")
                    .setIntent(intentS1)
                    .build();

            Intent intentS2 = makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class);
            intentS2.putExtra("large_extra", repeat('e', 35000));
            ShortcutInfo s2 = makeBuilder("s2")
                    .setIntent(intentS2)
                    .build();

            // Calling addDynamicShortcuts should return true (success)
            assertTrue(mManager.addDynamicShortcuts(list(s1, s2)));

            // Verify both shortcuts were dropped (list is empty)
            List<ShortcutInfo> active = mManager.getDynamicShortcuts();
            assertTrue(active == null || active.isEmpty());
        });
    }

    @Test
    public void testSetDynamicShortcuts_handlesSizeValidation() {
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            ShortcutInfo s1 = makeShortcut("s1");
            Intent intentS2 = makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class);
            intentS2.putExtra("large_extra", repeat('e', 35000));
            ShortcutInfo s2 = makeBuilder("s2")
                    .setIntent(intentS2)
                    .build();
            ShortcutInfo s3 = makeBuilder("s3")
                    .setPersons(createPersons(25, 1500))
                    .build();

            assertTrue(mManager.setDynamicShortcuts(list(s1, s2, s3)));

            List<ShortcutInfo> active = mManager.getDynamicShortcuts();
            assertShortcutIds(active, "s1", "s3");
        });
    }

    @Test
    public void testUpdateShortcuts_handlesSizeValidation() {
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            // First publish valid s1 and s3
            ShortcutInfo s1 = makeShortcut("s1");
            ShortcutInfo s3 = makeShortcut("s3");
            assertTrue(mManager.addDynamicShortcuts(list(s1, s3)));

            // Now update them:
            // - Update s1 to be oversized and untrimmable
            // - Update s3 to be oversized but trimmable
            Intent intentU1 = makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class);
            intentU1.putExtra("large_extra", repeat('e', 35000));
            ShortcutInfo u1 = makeBuilder("s1") // same ID to update
                    .setIntent(intentU1)
                    .build();

            ShortcutInfo u3 = makeBuilder("s3") // same ID to update
                    .setPersons(createPersons(25, 1500))
                    .build();

            assertTrue(mManager.updateShortcuts(list(u1, u3)));

            // Verify:
            // - s1 should NOT be updated (the update is dropped, so it remains in its original
            //   state)
            // - s3 should be updated and trimmed.
            List<ShortcutInfo> active = mManager.getDynamicShortcuts();
            assertShortcutIds(active, "s1", "s3");

            ShortcutInfo publishedS1 = findShortcut(active, "s1");
            assertNotNull(publishedS1);
            // Original s1 didn't have persons
            assertTrue(publishedS1.getPersons() == null || publishedS1.getPersons().length == 0);

            ShortcutInfo publishedS3 = findShortcut(active, "s3");
            assertNotNull(publishedS3);
            assertEquals(20, publishedS3.getPersons().length);
        });
    }

    @Test
    public void testPushDynamicShortcut_nullShortcut_ignoredWithoutCrash() {
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            mService.pushDynamicShortcut(CALLING_PACKAGE_1, null, USER_10);
        });
    }
}
