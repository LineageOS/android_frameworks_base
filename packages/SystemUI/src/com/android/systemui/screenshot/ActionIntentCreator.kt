/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.screenshot

import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipDescription
import android.content.ComponentName
import android.content.ContentProvider
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.net.Uri
import android.os.UserHandle
import android.provider.DocumentsContract
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.res.R
import com.android.systemui.screencapture.record.largescreen.data.repository.ParentUriRepository
import com.android.systemui.screenshot.scroll.LongScreenshotActivity
import java.util.function.Consumer
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SysUISingleton
class ActionIntentCreator
@Inject
constructor(
    private val context: Context,
    private val packageManager: PackageManager,
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val parentUriRepository: ParentUriRepository,
) {
    /** @return a chooser intent to share the given URI. */
    fun createShare(uri: Uri): Intent = createShare(uri, subject = null, text = null)

    /** @return a chooser intent to share the given URI with the optional provided subject. */
    fun createShareWithSubject(uri: Uri, subject: String): Intent =
        createShare(uri, subject = subject)

    /** @return a chooser intent to share the given URI with the optional provided extra text. */
    fun createShareWithText(uri: Uri, extraText: String): Intent =
        createShare(uri, text = extraText)

    private fun createShare(rawUri: Uri, subject: String? = null, text: String? = null): Intent {
        val uri = uriWithoutUserId(rawUri)

        // Create a share intent, this will always go through the chooser activity first
        // which should not trigger auto-enter PiP
        val sharingIntent =
            Intent(Intent.ACTION_SEND).apply {
                setDataAndType(uri, "image/png")
                putExtra(Intent.EXTRA_STREAM, uri)

                // Include URI in ClipData also, so that grantPermission picks it up.
                // We don't use setData here because some apps interpret this as "to:".
                clipData =
                    ClipData(
                        ClipDescription("content", arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN)),
                        ClipData.Item(uri),
                    )

                subject?.let { putExtra(Intent.EXTRA_SUBJECT, subject) }
                text?.let { putExtra(Intent.EXTRA_TEXT, text) }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }

        return Intent.createChooser(sharingIntent, null)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    // Non-suspend version for java compat
    fun createEdit(rawUri: Uri, consumer: Consumer<Intent>) {
        applicationScope.launch { consumer.accept(createEdit(rawUri)) }
    }

    /**
     * @return an ACTION_EDIT intent for the given URI, directed to config_preferredScreenshotEditor
     *   if enabled, falling back to config_screenshotEditor if that's non-empty.
     */
    suspend fun createEdit(rawUri: Uri): Intent {
        return createEditOrView(rawUri, Intent(Intent.ACTION_EDIT))
    }

    /**
     * @return an ACTION_VIEW intent for the given URI, directed to config_screenshotEditor if
     *   available.
     */
    suspend fun createView(rawUri: Uri): Intent {
        return createEditOrView(rawUri, Intent(Intent.ACTION_VIEW))
    }

    suspend fun createEditOrView(rawUri: Uri, intent: Intent): Intent {
        val uri = uriWithoutUserId(rawUri)

        // Use the preferred editor if it's available, otherwise fall back to the default editor
        intent.component = preferredEditor() ?: defaultEditor()

        return intent
            .setDataAndType(uri, "image/png")
            .putExtra(EXTRA_EDIT_SOURCE, EDIT_SOURCE_SCREENSHOT)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    fun createDelete(rawUri: Uri): PendingIntent {
        val intent = Intent(context, DeleteScreenshotReceiver::class.java).apply {
            data = rawUri
        }
        return PendingIntent.getBroadcast(
            context,
            rawUri.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
        )
    }

    /** @return an Intent to start the LongScreenshotActivity */
    fun createLongScreenshotIntent(owner: UserHandle, originalScreenshotUri: Uri): Intent {
        return Intent(context, LongScreenshotActivity::class.java)
            .putExtra(LongScreenshotActivity.EXTRA_SCREENSHOT_USER_HANDLE, owner)
            .putExtra(LongScreenshotActivity.EXTRA_ORIGINAL_SCREENSHOT_URI, originalScreenshotUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }

    /**
     * @return an optional ACTION_VIEW intent for the parent directory of the given MediaStore URI.
     */
    suspend fun createOpenInFiles(rawUri: Uri): Intent? {
        val parentUri = parentUriRepository.getParentDirectoryUri(rawUri)
        if (parentUri != null) {
            return Intent(Intent.ACTION_VIEW)
                .setDataAndType(parentUri, DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        } else {
            return null
        }
    }

    private suspend fun preferredEditor(): ComponentName? =
        runCatching {
                val preferredEditor = context.getString(R.string.config_preferredScreenshotEditor)
                val component = ComponentName.unflattenFromString(preferredEditor) ?: return null

                return if (isComponentAvailable(component)) component else null
            }
            .getOrNull()

    private suspend fun isComponentAvailable(component: ComponentName): Boolean =
        withContext(backgroundDispatcher) {
            try {
                val info =
                    packageManager.getPackageInfo(
                        component.packageName,
                        PackageManager.GET_ACTIVITIES,
                    )
                info.activities?.firstOrNull {
                    it.componentName.className == component.className
                } != null
            } catch (e: NameNotFoundException) {
                false
            }
        }

    private fun defaultEditor(): ComponentName? =
        runCatching {
                context.getString(R.string.config_screenshotEditor).let {
                    ComponentName.unflattenFromString(it)
                }
            }
            .getOrNull()

    companion object {
        private const val EXTRA_EDIT_SOURCE = "edit_source"
        private const val EDIT_SOURCE_SCREENSHOT = "screenshot"
    }
}

/**
 * URIs here are passed only via Intent which are sent to the target user via Intent. Because of
 * this, the userId component can be removed to prevent compatibility issues when an app attempts
 * valid a URI containing a userId within the authority.
 */
private fun uriWithoutUserId(uri: Uri): Uri {
    return ContentProvider.getUriWithoutUserId(uri)
}
