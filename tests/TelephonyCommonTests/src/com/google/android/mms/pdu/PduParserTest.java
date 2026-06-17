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

package com.google.android.mms.pdu;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class PduParserTest {

    @Test
    public void testParse_malformedHugeHeaderLength_doesNotOOM() {
        byte[] pduData = new byte[] {
            // Headers (Send-Req)
            (byte) 0x8C, (byte) 0x80, // Message-Type: Send-Req
            (byte) 0x98, (byte) '1', (byte) '2', (byte) '3', (byte) 0x00, // Transaction-ID: "123"
            (byte) 0x8D, (byte) 0x90, // MMS-Version: 1.0
            (byte) 0x89, (byte) 0x01, (byte) 0x81, // From: insert-address-token
            (byte) 0x84, (byte) 0xA3, // Content-Type: multipart/mixed

            // Body (Multipart)
            (byte) 0x01, // Part count = 1
            // Part 1
            // Part Header Length: 0x7FFFFFFF
            (byte) 0x87, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0x7F,
            (byte) 0x00, // Part Data Length: 0
            (byte) 0x83, // Part Content-Type: text/plain
        };

        PduParser parser = new PduParser(pduData, false);
        assertNull(parser.parse()); // Should return null instead of throwing OOM
    }

    @Test
    @SmallTest
    public void testParse_invalidContentTypeIndex_doesNotThrowAIOOBE() {
        byte[] pduData = new byte[] {
            // Headers (Send-Req)
            (byte) 0x8C, (byte) 0x80, // Message-Type: Send-Req
            (byte) 0x98, (byte) '1', (byte) '2', (byte) '3', (byte) 0x00, // Transaction-ID: "123"
            (byte) 0x8D, (byte) 0x90, // MMS-Version: 1.0
            (byte) 0x89, (byte) 0x01, (byte) 0x81, // From: insert-address-token
            (byte) 0x84, (byte) 0xD3, // Content-Type: index 83 (invalid, max is 82)
        };

        PduParser parser = new PduParser(pduData, false);
        try {
            parser.parse();
        } catch (ArrayIndexOutOfBoundsException e) {
            org.junit.Assert.fail("Should not throw ArrayIndexOutOfBoundsException");
        }
    }

    @Test
    @SmallTest
    public void testParse_elementDescriptorWithParams_doesNotThrowNPE() {
        byte[] pduData = new byte[] {
            // Headers (Send-Req)
            (byte) 0x8C, (byte) 0x80, // Message-Type: Send-Req
            (byte) 0x98, (byte) '1', (byte) '2', (byte) '3', (byte) 0x00, // Transaction-ID: "123"
            (byte) 0x8D, (byte) 0x90, // MMS-Version: 1.0
            (byte) 0x89, (byte) 0x01, (byte) 0x81, // From: insert-address-token
            (byte) 0x84, (byte) 0xA3, // Content-Type: multipart/mixed

            // ELEMENT_DESCRIPTOR (which passes null map)
            (byte) 0xB2,
            (byte) 0x03, // Value-length
            (byte) 0xA3, // Media-type: multipart/mixed
            (byte) 0x83, // Parameter: P_TYPE
            (byte) 0x80, // Parameter value: index 0
        };

        PduParser parser = new PduParser(pduData, false);
        try {
            parser.parse();
        } catch (NullPointerException e) {
            org.junit.Assert.fail("Should not throw NullPointerException");
        }
    }

    @Test
    @SmallTest
    public void testParse_emptyCharset_doesNotThrowNPE() {
        byte[] pduData = new byte[] {
            // Headers (Send-Req)
            (byte) 0x8C, (byte) 0x80, // Message-Type: Send-Req
            (byte) 0x98, (byte) '1', (byte) '2', (byte) '3', (byte) 0x00, // Transaction-ID: "123"
            (byte) 0x8D, (byte) 0x90, // MMS-Version: 1.0
            (byte) 0x89, (byte) 0x01, (byte) 0x81, // From: insert-address-token
            (byte) 0x84, // Content-Type
            // Value for Content-Type (with parameters)
            (byte) 0x03, // Value-length
            (byte) 0xA3, // Media-type: multipart/mixed
            (byte) 0x81, // Parameter: P_CHARSET
            (byte) 0x00, // Parameter value: empty string
        };

        PduParser parser = new PduParser(pduData, false);
        try {
            parser.parse();
        } catch (NullPointerException e) {
            org.junit.Assert.fail("Should not throw NullPointerException");
        }
    }

    @Test
    @SmallTest
    public void testPduParserUnderflowOOM() {
        // CONTENT_TYPE (0x84), LENGTH_QUOTE (0x1F), uintvar for Integer.MIN_VALUE,
        // well-known type (0x83)
        byte[] pdu = new byte[] {
                (byte) 0x84,
                (byte) 0x1F,
                (byte) 0x88,
                (byte) 0x80,
                (byte) 0x80,
                (byte) 0x80,
                (byte) 0x00,
                (byte) 0x83
        };

        try {
            PduParser parser = new PduParser(pdu, false);
            // This should not crash with OOM.
            parser.parse();
        } catch (OutOfMemoryError e) {
            fail("Triggered OutOfMemoryError due to integer underflow!");
        }
    }
}
