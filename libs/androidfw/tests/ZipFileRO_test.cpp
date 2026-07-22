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

#include "androidfw/ZipFileRO.h"

#include <string>
#include <string_view>
#include <ziparchive/zip_archive.h>

#include "gtest/gtest.h"

namespace android {

// This is a copy of a private internal struct defined in the .cpp file.
struct _ZipEntryRO {
    ZipEntry entry;
    std::string_view name;
};

TEST(ZipFileROTest, GetEntryFileName_MaxLengthOverflow) {
    constexpr size_t kMaxZipFileNameLen = 65535;
    std::string long_filename(kMaxZipFileNameLen, 'a');

    _ZipEntryRO dummy_entry{};
    dummy_entry.name = long_filename;

    ZipEntryRO entry = reinterpret_cast<ZipEntryRO>(&dummy_entry);

    char buffer[4096] = {};
    constexpr size_t buf_len = sizeof(buffer);

    ZipFileRO zip_file(nullptr, nullptr);

    int result = zip_file.getEntryFileName(entry, buffer, buf_len);

    // Verify that requiredSize is 65536 and getEntryFileName returns 65536
    // without getting any overflows or touching a buffer that's too small.
    EXPECT_EQ(result, static_cast<int>(kMaxZipFileNameLen + 1));
    EXPECT_EQ('\0', buffer[0]);
}

}  // namespace android
