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

package android.appwidget

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ActivityScenario.launchActivityForResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

class FakeConfigWithFlagsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = Intent().apply {
            setDataAndType(Uri.parse("content://"), "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        setResult(RESULT_OK, data)
        finish()
    }
}

class FakeConfigActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = Intent()
        data.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, WIDGET_ID)
        setResult(RESULT_OK, data)
        finish()
    }

    companion object {
        const val WIDGET_ID = 123
    }
}

@RunWith(AndroidJUnit4::class)
class AppWidgetConfigActivityProxyTest {

    @Test
    fun onActivityResult_preservesAppWidgetIdExtra() {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.component = PROXY_COMPONENT

        val targetIntent = Intent()
        targetIntent.component = ComponentName(
            "com.android.frameworks.coretests",
            FakeConfigActivity::class.java.name
        )
        intent.putExtra(Intent.EXTRA_INTENT, targetIntent)

        launchActivityForResult<AppWidgetConfigActivityProxy>(intent).use { scenario ->
            assertThat(scenario.result.resultCode).isEqualTo(Activity.RESULT_OK)
            val resultData = scenario.result.resultData
            assertThat(resultData.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
                .isEqualTo(FakeConfigActivity.WIDGET_ID)
        }
    }

    @Test
    fun onActivityResult_stripsUriPermissions() {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.component = PROXY_COMPONENT

        val targetIntent = Intent()
        targetIntent.component = ComponentName(
            "com.android.frameworks.coretests",
            FakeConfigWithFlagsActivity::class.java.name
        )
        intent.putExtra(Intent.EXTRA_INTENT, targetIntent)

        launchActivityForResult<AppWidgetConfigActivityProxy>(intent).use { scenario ->
            assertThat(scenario.result.resultCode).isEqualTo(Activity.RESULT_OK)
            val resultData = scenario.result.resultData
            assertThat(resultData.flags).isEqualTo(0)
            assertThat(resultData.data).isNull()
        }
    }

    companion object {
        private val PROXY_COMPONENT =
            ComponentName(
                "com.android.frameworks.coretests",
                AppWidgetConfigActivityProxy::class.java.name
            )
    }
}
