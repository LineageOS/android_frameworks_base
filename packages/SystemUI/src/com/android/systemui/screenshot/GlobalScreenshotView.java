/*
 * Copyright (C) 2020 The LineageOS Project
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

package com.android.systemui.screenshot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

import java.util.Collections;
import java.util.List;

public class GlobalScreenshotView extends FrameLayout {
    private Rect mLayoutBounds = new Rect();
    private List<Rect> mExclusionRects = Collections.singletonList(mLayoutBounds);

    public GlobalScreenshotView(Context context) {
        super(context);
    }

    public GlobalScreenshotView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public GlobalScreenshotView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public GlobalScreenshotView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        if (changed) {
            mLayoutBounds.set(left, top, right, bottom);
            setSystemGestureExclusionRects(mExclusionRects);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        canvas.getClipBounds(mLayoutBounds);
        setSystemGestureExclusionRects(mExclusionRects);
    }
}
