/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.content.res;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorStateListDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.platform.test.annotations.DisabledOnRavenwood;
import android.platform.test.annotations.Presubmit;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.frameworks.coretests.R;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@SmallTest
@DisabledOnRavenwood(blockedBy = Drawable.class)
@RunWith(AndroidJUnit4.class)
public class ResourcesDrawableTest {

    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder().build();

    @Test
    public void testLoadColorAsDrawable() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Resources resources = context.getResources();
        Drawable drawable = resources.getDrawable(R.color.color1);
        assertTrue(drawable instanceof ColorStateListDrawable);
    }

    @Test
    public void testLoadColorAsDrawableFailureThrowsOriginalException() throws Throwable {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Resources resources = context.getResources();

        Exception exception = null;

        try {
            resources.getDrawable(R.color.drawable_in_color_dir_invalid);
        } catch (Exception e) {
            exception = e;
        }

        assertNotNull(
                "Loading drawable_in_color_dir_invalid should throw an exception",
                exception
        );

        assertEquals(
                "Can't find ColorStateList from drawable resource ID #0x"
                        + Integer.toHexString(R.color.drawable_in_color_dir_invalid),
                exception.getCause().getCause().getMessage()
        );
    }

    @Test
    public void testLoadNormalDrawableInColorDir() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Resources resources = context.getResources();
        Drawable drawable = resources.getDrawable(R.color.drawable_in_color_dir_valid);
        assertTrue(drawable instanceof LayerDrawable);
    }

    @Test
    public void testGetDrawableCapped_downsamplesLargeResource() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Resources resources = context.getResources();

        // Load the massive 16,000 x 16,000 px image via the capped API
        Drawable drawable = resources.getDrawableForDensityCapped(
                com.android.frameworks.coretests.R.drawable.test16000x16000, 0, null);

        assertNotNull("Capped drawable should not be null", drawable);
        assertTrue("Capped drawable should be a BitmapDrawable",
                drawable instanceof BitmapDrawable);

        Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();

        // The original image is 16,000 x 16,000. Under our cap of 2048,
        // it should be scaled down to exactly 2048 x 2048 px.
        assertEquals("Width should be capped to 2048 px", 2048, bitmap.getWidth());
        assertEquals("Height should be capped to 2048 px", 2048, bitmap.getHeight());
    }

    @Test
    public void testGetDrawableCapped_doesNotPolluteSharedCache() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Resources resources = context.getResources();

        // 1. Load the massive image via the capped API first
        Drawable cappedDrawable = resources.getDrawableForDensityCapped(
                com.android.frameworks.coretests.R.drawable.test16000x16000, 0, null);
        assertNotNull(cappedDrawable);
        assertEquals(2048, cappedDrawable.getIntrinsicWidth());

        // 2. Load the same image using the standard uncapped API.
        Drawable uncappedDrawable = resources.getDrawable(
                com.android.frameworks.coretests.R.drawable.test16000x16000, null);
        // Assert that cache isolation works:
        // The uncapped load must either fail (return null) because it attempted to load
        // the full 16,000px image and failed,
        // or return the original large image (width > 2048).
        // It must NEVER return the cached downsampled version (which would be non-null
        // with width == 2048).
        if (uncappedDrawable != null) {
            assertEquals("Cache pollution occurred! The uncapped load should have returned the "
                            + "original uncapped version (width == 16000 px)",
                    16000, uncappedDrawable.getIntrinsicWidth());
        }
    }

    @Test
    public void testGetDrawableCapped_usesCache() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Resources resources = context.getResources();

        Drawable doc1 = resources.getDrawableForDensityCapped(
                com.android.frameworks.coretests.R.drawable.test16000x16000, 0, null);
        Drawable doc2 = resources.getDrawableForDensityCapped(
                com.android.frameworks.coretests.R.drawable.test16000x16000, 0, null);

        assertNotNull("First loaded capped drawable should not be null", doc1);
        assertNotNull("Second loaded capped drawable should not be null", doc2);
        // If caching works, both calls return a drawable sharing the same constant state
        assertEquals("Caching failed! Capped drawables should share the same ConstantState",
                doc1.getConstantState(), doc2.getConstantState());
    }
}
