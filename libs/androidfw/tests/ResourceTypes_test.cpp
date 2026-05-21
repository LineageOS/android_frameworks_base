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

#include "androidfw/ResourceTypes.h"

#include "androidfw/StringPool.h"
#include "androidfw/Util.h"
#include "gmock/gmock.h"
#include "gtest/gtest.h"

using ::testing::Eq;

namespace android {

TEST(ResourceTypesTest, ResStringPool_PotentialOverflowFindingStyles) {
  using namespace android;  // For NO_ERROR on Windows.

  // Create proper ResStringPool with 1 style
  StringPool pool;
  pool.MakeRef(StyleString{{"foo"}, {Span{{"b"}, 0, 1}}});

  NoOpDiagnostics diag;
  BigBuffer buffer(1024);
  StringPool::FlattenUtf8(&buffer, pool, &diag);

  ResStringPool test;
  std::unique_ptr<uint8_t[]> data = android::util::Copy(buffer);
  ASSERT_THAT(test.setTo(data.get(), buffer.size()), Eq(NO_ERROR));

  // Change style count to cause potential overflow when finding styles
  ResStringPool_header* header =
      const_cast<ResStringPool_header*>(test.data().convert<ResStringPool_header>().unsafe_ptr());
  header->styleCount = util::HostToDevice32(UINT32_MAX);

  ResStringPool modified;
  ASSERT_THAT(modified.setTo(test.data(), test.bytes()), Eq(BAD_TYPE));
}

TEST(ResourceTypesTest, ResStringPool_HeaderStyleCountExceedsStyleOffsetCount) {
  using namespace android;  // For NO_ERROR on Windows.

  // Create proper ResStringPool with 1 style
  StringPool pool;
  pool.MakeRef(StyleString{{"foo"}, {Span{{"b"}, 0, 1}}});

  NoOpDiagnostics diag;
  BigBuffer buffer(1024);
  StringPool::FlattenUtf8(&buffer, pool, &diag);

  ResStringPool test;
  std::unique_ptr<uint8_t[]> data = android::util::Copy(buffer);
  ASSERT_THAT(test.setTo(data.get(), buffer.size()), Eq(NO_ERROR));

  // Change style count to exceed the actual style count (which should be 1)
  ResStringPool_header* header =
      const_cast<ResStringPool_header*>(test.data().convert<ResStringPool_header>().unsafe_ptr());
  header->styleCount = 2;

  ResStringPool modified;
  ASSERT_THAT(modified.setTo(test.data(), test.bytes()), Eq(BAD_TYPE));
}

TEST(ResourceTypesTest, ResStringPool_StyleCountUnboundedWhenStringCountIsZero) {
  const ResStringPool_span endSpan = {
      { htodl(ResStringPool_span::END) },
      htodl(ResStringPool_span::END), htodl(ResStringPool_span::END)
  };

  struct MockResStringPool {
    ResStringPool_header header;
    uint32_t styleOffset;
    ResStringPool_span terminator;
  };

  MockResStringPool mock;
  memset(&mock, 0, sizeof(mock));

  mock.header.header.type = util::HostToDevice16(RES_STRING_POOL_TYPE);
  mock.header.header.headerSize = util::HostToDevice16(sizeof(ResStringPool_header));
  mock.header.header.size = util::HostToDevice32(sizeof(mock));
  mock.header.stringCount = util::HostToDevice32(0);
  mock.header.styleCount = util::HostToDevice32(100);
  mock.header.stringsStart = util::HostToDevice32(0xFFFFFFFF);
  mock.header.stylesStart = util::HostToDevice32(offsetof(MockResStringPool, terminator));
  mock.terminator = endSpan;

  ResStringPool test;
  ASSERT_THAT(test.setTo(&mock, sizeof(mock)), Eq(BAD_TYPE));
}

TEST(ResourceTypesTest, ResStringPool_StyleCountCalculationOverflow) {
  using namespace android;

  struct MockResStringPool {
    ResStringPool_header header;
    uint32_t styleOffset;
  };

  MockResStringPool mock;
  memset(&mock, 0, sizeof(mock));

  mock.header.header.type = util::HostToDevice16(RES_STRING_POOL_TYPE);
  mock.header.header.headerSize = util::HostToDevice16(sizeof(ResStringPool_header));
  mock.header.header.size = util::HostToDevice32(sizeof(mock));
  mock.header.stringCount = util::HostToDevice32(0);
  mock.header.styleCount = util::HostToDevice32(0x40000000u);
  mock.header.stringsStart = util::HostToDevice32(sizeof(ResStringPool_header));
  mock.header.stylesStart = util::HostToDevice32(sizeof(ResStringPool_header));

  ResStringPool test;
  ASSERT_THAT(test.setTo(&mock, sizeof(mock)), Eq(BAD_TYPE));
}

TEST(ResourceTypesTest, ResXMLTree_ValidateNode_SmallAttributeSize) {
  struct MockResXMLTree {
    ResXMLTree_header header;
    ResStringPool_header pool_header;
    ResXMLTree_node node;
    ResXMLTree_attrExt attr_ext;
    char padding[32];
  };

  MockResXMLTree mock;
  memset(&mock, 0, sizeof(mock));

  // XML Tree Header
  mock.header.header.type = util::HostToDevice16(RES_XML_TYPE);
  mock.header.header.headerSize = util::HostToDevice16(sizeof(ResXMLTree_header));
  mock.header.header.size = util::HostToDevice32(sizeof(mock));

  // String Pool Header (empty)
  mock.pool_header.header.type = util::HostToDevice16(RES_STRING_POOL_TYPE);
  mock.pool_header.header.headerSize = util::HostToDevice16(sizeof(ResStringPool_header));
  mock.pool_header.header.size = util::HostToDevice32(sizeof(ResStringPool_header));

  // XML Node
  mock.node.header.type = util::HostToDevice16(RES_XML_START_ELEMENT_TYPE);
  mock.node.header.headerSize = util::HostToDevice16(sizeof(ResXMLTree_node));
  // Set size large enough to pass the existing bounds check even with an invalid attribute size.
  mock.node.header.size = util::HostToDevice32(
      sizeof(ResXMLTree_node) + sizeof(ResXMLTree_attrExt) + 20);

  // Attr Ext
  mock.attr_ext.attributeStart = util::HostToDevice16(sizeof(ResXMLTree_attrExt));
  mock.attr_ext.attributeSize =
      util::HostToDevice16(sizeof(ResXMLTree_attribute) - 1); // Too small
  mock.attr_ext.attributeCount = util::HostToDevice16(1);

  ResXMLTree tree;
  ASSERT_THAT(tree.setTo(&mock, sizeof(mock)), Eq(BAD_TYPE));
}

}  // namespace android
