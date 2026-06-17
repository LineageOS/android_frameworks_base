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

package com.android.internal.telephony.tests;

import static org.junit.Assert.assertNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.mms.pdu.PduParser;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PduParserTest {

    // AIOOBE Trigger (14 bytes)
    private static final byte[] AIOOBE_PAYLOAD = {
        (byte) 0x8C, (byte) 0x84, (byte) 0x8D, (byte) 0x90, (byte) 0x85, (byte) 0x01, (byte) 0x01,
        (byte) 0x84, (byte) 0xA3, (byte) 0x01, (byte) 0x01, (byte) 0x01, (byte) 0xA6, (byte) 0x00
    };

    // NPE Trigger (14 bytes)
    private static final byte[] NPE_PAYLOAD = {
        (byte) 0x8C, (byte) 0x84, (byte) 0x8D, (byte) 0x90, (byte) 0x85, (byte) 0x01, (byte) 0x01,
        (byte) 0x84, (byte) 0xA3, (byte) 0x01, (byte) 0x01, (byte) 0x01, (byte) 0xA6, (byte) 0x01
    };

    // OOM Trigger (17 bytes)
    private static final byte[] OOM_PAYLOAD = {
        (byte) 0x8C, (byte) 0x84, (byte) 0x8D, (byte) 0x90, (byte) 0x85, (byte) 0x01, (byte) 0x01,
        (byte) 0x84, (byte) 0xA3, (byte) 0x01, (byte) 0x01, (byte) 0x87, (byte) 0xFF, (byte) 0xFF,
        (byte) 0xFF, (byte) 0x7F, (byte) 0x83
    };

    @Test
    public void testAioobePayload() {
        PduParser parser = new PduParser(AIOOBE_PAYLOAD, true);
        assertNull(parser.parse());
    }

    @Test
    public void testNpePayload() {
        PduParser parser = new PduParser(NPE_PAYLOAD, true);
        assertNull(parser.parse());
    }

    @Test
    public void testOomPayload() {
        PduParser parser = new PduParser(OOM_PAYLOAD, true);
        assertNull(parser.parse());
    }
}
