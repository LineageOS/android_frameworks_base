/*
 * Copyright (C) 2016 The Android Open Source Project
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

#pragma once

#include <SkCanvas.h>
#include <SkDrawable.h>

namespace android {
namespace uirenderer {
namespace skiapipeline {

class StartUnclippedLayerDrawable : public SkDrawable {
public:
    explicit StartUnclippedLayerDrawable(const SkIRect& bounds) : mBounds(SkRect::Make(bounds)) {}
    SkImage* releaseOriginalContents() { return mOriginalContents.release(); }
    int getSaveCount() const { return mSaveCount; }

protected:
    SkRect onGetBounds() override { return mBounds; }
    void onDraw(SkCanvas* canvas) override;

private:
    SkRect mBounds;
    sk_sp<SkImage> mOriginalContents;
    int mSaveCount = 0;
};

class EndUnclippedLayerDrawable : public SkDrawable {
public:
    explicit EndUnclippedLayerDrawable(StartUnclippedLayerDrawable* startDrawable)
            : mStartDrawable(startDrawable) {}


protected:
    SkRect onGetBounds() override { return mStartDrawable->getBounds(); }
    void onDraw(SkCanvas* canvas) override;

private:
    sk_sp<StartUnclippedLayerDrawable> mStartDrawable;
};

};  // namespace skiapipeline
};  // namespace uirenderer
};  // namespace android
