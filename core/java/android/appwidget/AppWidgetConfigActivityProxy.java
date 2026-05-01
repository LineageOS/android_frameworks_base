/*
 * Copyright (C) 2025 The Android Open Source Project
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

package android.appwidget;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;

/**
 * Activity to proxy config activity launches
 *
 * @hide
 */
public class AppWidgetConfigActivityProxy extends Activity {

    private static final int CONFIG_ACTIVITY_REQUEST_CODE = 1;
    private static final String TAG = "AppWidgetConfigActivityProxy";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setResult(RESULT_CANCELED);
        Intent intent = getIntent();
        Intent target = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
        if (target == null) {
            finish();
            return;
        }

        startActivityForResultSafely(target, CONFIG_ACTIVITY_REQUEST_CODE);
    }

    private void startActivityForResultSafely(Intent intent, int requestCode) {
        try {
            startActivityForResult(intent, requestCode);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "Config activity for widget not found", e);
            onActivityResult(requestCode, RESULT_CANCELED, null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // b/498916138 - Only pass the result code and intent extras to the config activity and
        // nothing else.
        Intent result = data != null ? new Intent().putExtras(data) : null;
        setResult(resultCode, result);
        int widgetId = getIntent().getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        AppWidgetManager.getInstance(this).setConfigActivityComplete(widgetId);

        finish();
    }
}
