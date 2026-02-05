/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.app.admin;

import android.content.ComponentName;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.PersistableBundle;

import java.io.UTFDataFormatException;
import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Utility class containing methods to verify the max allowed size of certain policy types.
 *
 * @hide
 */
public class PolicySizeVerifier {

    /**
     * Throw if string argument is too long to be serialized.
     */
    public static void enforceMaxStringLength(String str, String argName) {
        try {
            long len = countBytes(str, /* throw error if too long */ true);
        } catch (UTFDataFormatException e) {
            throw new IllegalArgumentException(argName + " too long");
        }
    }

    /**
     * Returns the number of bytes the modified UTF-8 representation of 's' would take. Note
     * that this is just the space for the bytes representing the characters, not the length
     * which precedes those bytes, because different callers represent the length differently,
     * as two, four, or even eight bytes. If {@code shortLength} is true, we'll throw an
     * exception if the string is too long for its length to be represented by a short.
     */
    private static long countBytes(String s, boolean shortLength) throws UTFDataFormatException {
        long result = 0;
        final int length = s.length();
        for (int i = 0; i < length; ++i) {
            char ch = s.charAt(i);
            if (ch != 0 && ch <= 127) { // U+0000 uses two bytes.
                ++result;
            } else if (ch <= 2047) {
                result += 2;
            } else {
                result += 3;
            }
            if (shortLength && result > 65535) {
                throw new UTFDataFormatException("String more than 65535 UTF bytes long");
            }
        }
        return result;
    }
}
