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

#include "UnclippedLayerDrawables.h"
#include "utils/TraceUtils.h"

#include <SkAndroidFrameworkUtils.h>
#include <SkSurface.h>
#include <SkImage.h>

namespace android {
namespace uirenderer {
namespace skiapipeline {

void StartUnclippedLayerDrawable::onDraw(SkCanvas* canvas) {
    ATRACE_NAME("startUnclippedLayer");
    // store the contents of the canvas within the bounds
    sk_sp<SkSurface> surface = SkAndroidFrameworkUtils::getSurfaceFromCanvas(canvas);
    SkRect deviceBounds;

    if(surface && canvas->getTotalMatrix().mapRect(&deviceBounds, mBounds)) {
        SkIRect deviceIBounds;
        deviceBounds.roundOut(&deviceIBounds);
        mOriginalContents = surface->makeImageSnapshot(deviceIBounds);

        canvas->flush();

        mSaveCount = canvas->save();

        // clear the contents of the canvas within the bounds
        SkPaint p;
        p.setBlendMode(SkBlendMode::kClear);
        canvas->drawRect(mBounds, p);

    } else {
        // LOG WARNING
    }
}

void EndUnclippedLayerDrawable::onDraw(SkCanvas* canvas) {
    ATRACE_NAME("endUnclippedLayer");
    sk_sp<SkImage> originalContents(mStartDrawable->releaseOriginalContents());
    const SkRect bounds = mStartDrawable->getBounds();
    const int saveCount = mStartDrawable->getSaveCount();

    if (saveCount > 0) {
        canvas->restoreToCount(saveCount);
        SkPaint p;
        p.setBlendMode(SkBlendMode::kDstOver);
        canvas->drawImage(originalContents, bounds.fLeft, bounds.fTop, &p);
    }
}

};  // namespace skiapipeline
};  // namespace uirenderer
};  // namespace android
