/*
* Copyright (C) 2016-2017 SlimRoms Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.android.internal.util.slim;

import android.graphics.Color;

public class ColorUtils {

    public static boolean isDarkColor(int color) {
        double darkness = 1-(0.299 * Color.red(color)
                + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255;

        return darkness > 0.5;
    }

    public static int darkenColor(int color) {
        float factor = 0.8f;
        int a = Color.alpha(color);
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);

        return Color.argb(a,
                Math.max((int) (r * factor), 0 ),
                Math.max((int) (g * factor), 0 ),
                Math.max((int) (b * factor), 0 ));
    }

    public static int lightenColor(int color) {
        float factor = 1.2f;
        int a = Color.alpha(color);
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);

        return Color.argb(a,
                Math.max((int) (r * factor), 0 ),
                Math.max((int) (g * factor), 0 ),
                Math.max((int) (b * factor), 0 ));
    }
}
