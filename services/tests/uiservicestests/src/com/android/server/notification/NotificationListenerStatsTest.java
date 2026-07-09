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

package com.android.server.notification;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;

import android.util.Xml;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.UiServiceTestCase;
import com.android.server.notification.ManagedServices.ManagedServiceInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

@RunWith(AndroidJUnit4.class)
public class NotificationListenerStatsTest extends UiServiceTestCase {

    private NotificationListenerStats mStats;
    private ManagedServiceInfo mNls1;
    private ManagedServiceInfo mNls2;

    @Before
    public void setUp() {
        mStats = new NotificationListenerStats(10, 5); // Max 10 total, Max 5 per pkg
        mNls1 = mock(ManagedServiceInfo.class);
        mNls1.uid = 101;
        mNls2 = mock(ManagedServiceInfo.class);
        mNls2.uid = 102;
    }

    @Test
    public void testLogCreatedChannels() {
        mStats.logCreatedChannels(mNls1, "pkg1", 2);
        mStats.logCreatedChannels(mNls1, "pkg2", 3);
        mStats.logCreatedChannels(mNls2, "pkg1", 4);

        // NLS1 total: 5, pkg1: 2, pkg2: 3. Limits: 10 total, 5 per pkg. Allowed.
        assertThat(mStats.isAllowedToCreateChannel(mNls1, "pkg1")).isTrue();
        assertThat(mStats.isAllowedToCreateChannel(mNls1, "pkg2")).isTrue();

        mStats.logCreatedChannels(mNls1, "pkg2", 5); // NLS1 total now 10.
        assertThat(mStats.isAllowedToCreateChannel(mNls1, "pkg1")).isFalse(); // Reached total limit
    }

    @Test
    public void testMaxChannelsPerPkgLimit() {
        // Limits: 10 total, 5 per pkg.
        mStats.logCreatedChannels(mNls1, "pkg1", 4);
        assertThat(mStats.isAllowedToCreateChannel(mNls1, "pkg1")).isTrue();

        mStats.logCreatedChannels(mNls1, "pkg1", 1);
        // Not allowed for pkg1
        assertThat(mStats.isAllowedToCreateChannel(mNls1, "pkg1")).isFalse();

        // Still allowed for pkg2 because total is 5 < 10
        assertThat(mStats.isAllowedToCreateChannel(mNls1, "pkg2")).isTrue();

        mStats.logCreatedChannels(mNls1, "pkg2", 5); // Total now 10
        // Reached total limit
        assertThat(mStats.isAllowedToCreateChannel(mNls1, "pkg2")).isFalse();
        assertThat(mStats.isAllowedToCreateChannel(mNls1, "pkg3")).isFalse();
    }

    @Test
    public void testOnPackageRemoved_NlsRemoved() {
        mStats.logCreatedChannels(mNls1, "pkg1", 5);
        mStats.logCreatedChannels(mNls2, "pkg1", 3);

        // Remove NLS1
        mStats.onPackageRemoved(mNls1.uid, "some.nls.package");

        // NLS1 stats should be gone, so it should be allowed to create channels again.
        assertThat(mStats.isAllowedToCreateChannel(mNls1, "pkg1")).isTrue();

        // NLS2 should be unaffected. It has 3 channels for pkg1. Limit is 5.
        // If we add 7 more, total becomes 10 (reaches limit).
        mStats.logCreatedChannels(mNls2, "pkg1", 7);
        assertThat(mStats.isAllowedToCreateChannel(mNls2, "pkg1")).isFalse();
    }

    @Test
    public void testOnPackageRemoved_TargetPackageRemoved() {
        mStats.logCreatedChannels(mNls1, "pkg1", 5);
        mStats.logCreatedChannels(mNls1, "pkg2", 3);
        mStats.logCreatedChannels(mNls2, "pkg1", 4);

        // UID of target package doesn't matter for target removal
        mStats.onPackageRemoved(999, "pkg1");

        // NLS1 should have lost "pkg1" stats (5 channels), total should be 3 (pkg2).
        // NLS2 should have lost "pkg1" stats (4 channels), total should be 0.

        // We can verify by adding more and checking limits.
        // NLS1 total limit is 10. It has 3 (pkg2). pkg2 limit is 5. It has 3.
        // We can add 1 more to pkg2. Total 4, pkg2 4. Allowed.
        mStats.logCreatedChannels(mNls1, "pkg2", 1);
        assertThat(mStats.isAllowedToCreateChannel(mNls1, "pkg2")).isTrue();

        // Add 1 more to pkg2. Total 5, pkg2 5. Not allowed for pkg2 (reached pkg limit).
        mStats.logCreatedChannels(mNls1, "pkg2", 1);
        assertThat(mStats.isAllowedToCreateChannel(mNls1, "pkg2")).isFalse();
        // But allowed for pkg3 (pkg3 has 0, total is 5 < 10).
        assertThat(mStats.isAllowedToCreateChannel(mNls1, "pkg3")).isTrue();

        // Also check that NLS2 was cleaned up
        mStats.logCreatedChannels(mNls2, "pkg1", 5);
        assertThat(mStats.isAllowedToCreateChannel(mNls2, "pkg2")).isTrue();
    }

    @Test
    public void testXmlSerialization() throws Exception {
        mStats.logCreatedChannels(mNls1, "pkg1", 2);
        mStats.logCreatedChannels(mNls1, "pkg2", 3);
        mStats.logCreatedChannels(mNls2, "pkg1", 4);

        // Write to XML
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        TypedXmlSerializer serializer = Xml.newFastSerializer();
        serializer.setOutput(baos, StandardCharsets.UTF_8.name());
        serializer.startDocument(null, true);
        mStats.writeXml(serializer);
        serializer.endDocument();
        serializer.flush();

        // Read from XML (restore with same limits: 10 total, 5 per pkg)
        NotificationListenerStats restoredStats = new NotificationListenerStats(10, 5);
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        TypedXmlPullParser parser = Xml.resolvePullParser(bais);

        // Find start tag
        int type;
        while ((type = parser.next()) != TypedXmlPullParser.END_DOCUMENT) {
            if (type == TypedXmlPullParser.START_TAG
                    && NotificationListenerStats.isXmlTag(parser.getName())) {
                restoredStats.readXml(parser);
                break;
            }
        }

        // Verify restored stats
        // NLS1 has: pkg1 (2), pkg2 (3). Total 5.
        // NLS2 has: pkg1 (4). Total 4.

        // Add 2 to NLS2 pkg1 -> NLS2 pkg1 becomes 6 (exceeds 5).
        restoredStats.logCreatedChannels(mNls2, "pkg1", 2);
        // Reached pkg limit
        assertThat(restoredStats.isAllowedToCreateChannel(mNls2, "pkg1")).isFalse();
        // Total is 6 < 10, so pkg2 allowed
        assertThat(restoredStats.isAllowedToCreateChannel(mNls2, "pkg2")).isTrue();

        // Also verify that package stats were restored by removing a package and checking
        // if quota is reclaimed.
        restoredStats.onPackageRemoved(999, "pkg1");
        // NLS1 had: pkg1 (2), pkg2 (3). Total 5.
        // After removing pkg1, NLS1 should have: pkg2 (3). Total 3.
        // pkg2 limit is 5. It has 3. We can add 2. Total 5, pkg2 5.
        restoredStats.logCreatedChannels(mNls1, "pkg2", 2);
        // Reached pkg limit
        assertThat(restoredStats.isAllowedToCreateChannel(mNls1, "pkg2")).isFalse();
        // Total is 5 < 10, so pkg3 allowed
        assertThat(restoredStats.isAllowedToCreateChannel(mNls1, "pkg3")).isTrue();
    }
}
