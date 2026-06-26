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

package android.app.admin;

import android.annotation.NonNull;
import android.os.Bundle;
import android.os.Parcelable;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Utility class containing methods to verify that types inside a Bundle are the permitted ones.
 * @hide
 */
public class BundlePolicyTypeVerifier {

    /**
     * Throw if bundle contains any value that is not of a permitted type.
     * Permitted types are: Boolean, Integer, String, String[], Bundle, and Parcelable[]
     * (containing only Bundles).
     */
    public static void enforceValidTypes(@NonNull Bundle bundle) {
        Queue<Bundle> queue = new ArrayDeque<>();
        queue.add(bundle);

        while (!queue.isEmpty()) {
            Bundle current = queue.remove();
            for (String key : current.keySet()) {
                Object value = getUntypedValue(current, key);

                if (value == null
                        || value instanceof Boolean
                        || value instanceof Integer
                        || value instanceof String
                        || value instanceof String[]) {
                    continue;
                } else if (value instanceof Bundle b) {
                    queue.add(b);
                } else if (value instanceof Parcelable[] parcelableArray) {
                    for (Parcelable parcelable : parcelableArray) {
                        if (!(parcelable instanceof Bundle)) {
                            throw new IllegalArgumentException("bundle-array can only hold "
                                    + "Bundles, found: "
                                    + (parcelable == null
                                        ? "null"
                                        : parcelable.getClass().getName()));
                        }
                        queue.add((Bundle) parcelable);
                    }
                } else {
                    throw new IllegalArgumentException("Invalid type inside Bundle: "
                            + value.getClass().getName() + " for key: " + key);
                }
            }
        }
    }

    /**
     * Safely retrieves an untyped object from a Bundle.
     * <p>
     * Note: {@link Bundle#get(String)} is deprecated in favor of type-safe getters, but this
     * verifier needs the raw value in order to inspect its type.
     */
    @SuppressWarnings("deprecation")
    private static Object getUntypedValue(Bundle bundle, String key) {
        return bundle.get(key);
    }

    private BundlePolicyTypeVerifier() {}
}
