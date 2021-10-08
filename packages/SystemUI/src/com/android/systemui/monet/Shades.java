package com.android.systemui.monet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.ColorUtils;

@VisibleForTesting
public class Shades {
    public static int[] of(float f, float f2) {
        int[] iArr = new int[12];
        iArr[0] = ColorUtils.CAMToColor(f, f2, 99.0f);
        iArr[1] = ColorUtils.CAMToColor(f, f2, 95.0f);
        int i = 2;
        while (i < 12) {
            iArr[i] = ColorUtils.CAMToColor(f, f2, i == 6 ? 49.6f : (float) (100 - ((i - 1) * 10)));
            i++;
        }
        return iArr;
    }
}
