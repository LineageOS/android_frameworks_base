/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.screenrecord;

import static android.content.Context.MEDIA_PROJECTION_SERVICE;

import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.INTERNAL;
import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.MIC;
import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.MIC_AND_INTERNAL;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.ThumbnailUtils;
import android.media.projection.IMediaProjection;
import android.media.projection.IMediaProjectionManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.media.projection.StopReason;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.MediaStore;
import android.text.format.DateUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.Surface;

import androidx.annotation.NonNull;

import com.android.internal.R;
import com.android.systemui.mediaprojection.MediaProjectionCaptureTarget;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Recording screen and mic/internal audio
 */
public class ScreenMediaRecorder {
    private static final int TOTAL_NUM_TRACKS = 1;
    private static final int VIDEO_FRAME_RATE = 30;
    private static final int VIDEO_FRAME_RATE_TO_RESOLUTION_RATIO = 6;
    private static final int AUDIO_BIT_RATE = 196000;
    private static final int AUDIO_SAMPLE_RATE = 44100;
    private static final int MAX_DURATION_MS = (int) DateUtils.HOUR_IN_MILLIS;
    private static final long MAX_FILESIZE_BYTES = 5000000000L;

    private static final String TAG = "ScreenMediaRecorder";

    private File mTempVideoFile;
    private File mTempAudioFile;
    private MediaProjection mMediaProjection;
    private Surface mInputSurface;
    private VirtualDisplay mVirtualDisplay;
    private MediaRecorder mMediaRecorder;
    private int mUid;
    private ScreenInternalAudioRecorder mAudio;
    private ScreenRecordingAudioSource mAudioSource;
    private Long mStartTimeMillis = 0L;
    private final MediaProjectionCaptureTarget mCaptureRegion;
    private final Handler mHandler;
    private final int mDisplayId;
    private final AtomicBoolean mIsStarted = new AtomicBoolean();

    private Context mContext;
    ScreenMediaRecorderListener mListener;

    public ScreenMediaRecorder(
            Context context,
            Handler handler,
            int uid,
            ScreenRecordingAudioSource audioSource,
            MediaProjectionCaptureTarget captureRegion,
            int displayId,
            ScreenMediaRecorderListener listener) {
        mContext = context;
        mHandler = handler;
        mUid = uid;
        mCaptureRegion = captureRegion;
        mListener = listener;
        mAudioSource = audioSource;
        mDisplayId = displayId;
    }

    private void prepare() throws IOException, RemoteException, RuntimeException {
        //Setup media projection
        IBinder b = ServiceManager.getService(MEDIA_PROJECTION_SERVICE);
        IMediaProjectionManager mediaService =
                IMediaProjectionManager.Stub.asInterface(b);
        IMediaProjection proj =
                mediaService.createProjection(
                        mUid,
                        mContext.getPackageName(),
                        MediaProjectionManager.TYPE_SCREEN_CAPTURE,
                        false,
                        mDisplayId);
        IMediaProjection projection = IMediaProjection.Stub.asInterface(proj.asBinder());
        if (mCaptureRegion != null) {
            projection.setLaunchCookie(mCaptureRegion.getLaunchCookie());
            projection.setTaskId(mCaptureRegion.getTaskId());
        }
        final MediaProjectionCallback mediaProjectionCallback = new MediaProjectionCallback(
                mListener, mContext.getUserId());
        mMediaProjection = new MediaProjection(mContext, projection);
        mMediaProjection.registerCallback(mediaProjectionCallback, mHandler);

        File cacheDir = mContext.getCacheDir();
        cacheDir.mkdirs();
        mTempVideoFile = File.createTempFile("temp", ".mp4", cacheDir);

        // Set up media recorder
        mMediaRecorder = new MediaRecorder(mContext);

        // Set up audio source
        if (mAudioSource == MIC) {
            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.DEFAULT);
        }
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);

        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);


        // Set up video
        DisplayMetrics metrics = new DisplayMetrics();
        DisplayManager dm = mContext.getSystemService(DisplayManager.class);
        Display display = dm.getDisplay(mDisplayId);
        display.getRealMetrics(metrics);
        int refreshRate = (int) display.getRefreshRate();
        VideoParameters videoParameters = getSupportedSize(metrics.widthPixels,
                metrics.heightPixels, refreshRate);
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mMediaRecorder.setVideoEncodingProfileLevel(
                MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
                MediaCodecInfo.CodecProfileLevel.AVCLevel3);
        mMediaRecorder.setVideoSize(videoParameters.mWidth, videoParameters.mHeight);
        mMediaRecorder.setVideoFrameRate(videoParameters.mRefreshRate);
        mMediaRecorder.setVideoEncodingBitRate(videoParameters.bitrate());
        mMediaRecorder.setMaxDuration(MAX_DURATION_MS);
        mMediaRecorder.setMaxFileSize(MAX_FILESIZE_BYTES);

        // Set up audio
        if (mAudioSource == MIC) {
            mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.HE_AAC);
            mMediaRecorder.setAudioChannels(TOTAL_NUM_TRACKS);
            mMediaRecorder.setAudioEncodingBitRate(AUDIO_BIT_RATE);
            mMediaRecorder.setAudioSamplingRate(AUDIO_SAMPLE_RATE);
        }

        mMediaRecorder.setOutputFile(mTempVideoFile);
        mMediaRecorder.prepare();
        // Create surface
        mInputSurface = mMediaRecorder.getSurface();
        mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                "Recording Display",
                videoParameters.mWidth,
                videoParameters.mHeight,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mInputSurface,
                new VirtualDisplay.Callback() {
                    @Override
                    public void onStopped() {
                        mediaProjectionCallback.onStop();
                    }
                },
                mHandler);

        mMediaRecorder.setOnInfoListener((mr, what, extra) -> mListener.onInfo(mr, what, extra));
        if (mAudioSource == INTERNAL ||
                mAudioSource == MIC_AND_INTERNAL) {
            mTempAudioFile = File.createTempFile("temp", ".aac",
                    mContext.getCacheDir());
            mAudio = new ScreenInternalAudioRecorder(mTempAudioFile.getAbsolutePath(),
                    mMediaProjection, mAudioSource == MIC_AND_INTERNAL);
        }

    }

    /**
     * Find the highest supported screen resolution and refresh rate for the given dimensions on
     * this device, up to actual size and given rate.
     * If possible this will return the same values as given, but values may be smaller on some
     * devices.
     *
     * @param screenWidth  Actual pixel width of screen
     * @param screenHeight Actual pixel height of screen
     * @param refreshRate  Desired refresh rate
     * @return returns {@link VideoParameters} for the screen recording.
     */
    private VideoParameters getSupportedSize(final int screenWidth, final int screenHeight,
            int refreshRate)
            throws IOException {
        String videoType = MediaFormat.MIMETYPE_VIDEO_AVC;

        // Get max size from the decoder, to ensure recordings will be playable on device
        MediaCodec decoder = MediaCodec.createDecoderByType(videoType);
        MediaCodecInfo.VideoCapabilities vc = decoder.getCodecInfo().getCapabilitiesForType(
                        videoType)
                .getVideoCapabilities();
        decoder.release();

        // Check if we can support screen size as-is
        int width = vc.getSupportedWidths().getUpper();
        int height = vc.getSupportedHeights().getUpper();

        int screenWidthAligned = screenWidth;
        if (screenWidthAligned % vc.getWidthAlignment() != 0) {
            screenWidthAligned -= (screenWidthAligned % vc.getWidthAlignment());
        }
        int screenHeightAligned = screenHeight;
        if (screenHeightAligned % vc.getHeightAlignment() != 0) {
            screenHeightAligned -= (screenHeightAligned % vc.getHeightAlignment());
        }

        if (width >= screenWidthAligned && height >= screenHeightAligned
                && vc.isSizeSupported(screenWidthAligned, screenHeightAligned)) {
            // Desired size is supported, now get the rate
            int maxRate = getSupportedFrameRateFor(vc, screenWidthAligned, screenHeightAligned);

            if (maxRate < refreshRate) {
                refreshRate = maxRate;
            }
            VideoParameters parameters = new VideoParameters(
                    /* mWidth= */ screenWidthAligned,
                    /* mHeight= */ screenHeightAligned,
                    /* mRefreshRate= */ refreshRate
            );
            Log.d(TAG, "Screen size supported with parameters: " + parameters);
            return parameters;
        }

        // Otherwise, resize for max supported size
        double scale = Math.min(((double) width / screenWidth),
                ((double) height / screenHeight));

        int scaledWidth = (int) (screenWidth * scale);
        int scaledHeight = (int) (screenHeight * scale);
        if (scaledWidth % vc.getWidthAlignment() != 0) {
            scaledWidth -= (scaledWidth % vc.getWidthAlignment());
        }
        if (scaledHeight % vc.getHeightAlignment() != 0) {
            scaledHeight -= (scaledHeight % vc.getHeightAlignment());
        }

        // Find max supported rate for size
        int maxRate = getSupportedFrameRateFor(vc, scaledWidth, scaledHeight);
        if (maxRate < refreshRate) {
            refreshRate = maxRate;
        }

        VideoParameters parameters = new VideoParameters(
                /* mWidth= */ scaledWidth,
                /* mHeight= */ scaledHeight,
                /* mRefreshRate= */ refreshRate
        );
        Log.d(TAG, "Resized to parameters: " + parameters);
        return parameters;
    }

    /**
     * Start screen recording
     */
    public void start() throws IOException, RemoteException, RuntimeException {
        Log.d(TAG, "start recording");
        prepare();
        mMediaRecorder.start();
        mStartTimeMillis = System.currentTimeMillis();
        mListener.onStarted();
        recordInternalAudio();
        mIsStarted.set(true);
    }

    /**
     * End screen recording, throws an exception if stopping recording failed
     */
    public void end(@StopReason int stopReason) throws IOException {
        if (mIsStarted.compareAndSet(true, false)) {
            Closer closer = new Closer();

            // MediaRecorder might throw RuntimeException if stopped immediately after starting
            // We should remove the recording in this case as it will be invalid
            closer.register(mMediaRecorder::stop);
            closer.register(mMediaRecorder::release);
            closer.register(mInputSurface::release);
            closer.register(mVirtualDisplay::release);
            closer.register(() -> {
                if (stopReason == StopReason.STOP_UNKNOWN) {
                    // Attempt to call MediaProjection#stop() even if it might have already been
                    // called.
                    // If projection has already been stopped, then nothing will happen. Else, stop
                    // will be logged as a manually requested stop from host app.
                    mMediaProjection.stop();
                } else {
                    // In any other case, the stop reason is related to the recorder, so pass it
                    // on here
                    mMediaProjection.stop(stopReason);
                }
            });
            closer.register(this::stopInternalAudioRecording);

            closer.close();

            mMediaRecorder = null;
            mMediaProjection = null;

            Log.d(TAG, "end recording");
        } else {
            Log.d(TAG, "recording hasn't been started. Nothing to end");
        }
    }

    private void stopInternalAudioRecording() {
        if (mAudioSource == INTERNAL || mAudioSource == MIC_AND_INTERNAL) {
            mAudio.end();
            mAudio = null;
        }
    }

    private void recordInternalAudio() throws IllegalStateException {
        if (mAudioSource == INTERNAL || mAudioSource == MIC_AND_INTERNAL) {
            mAudio.start();
        }
    }

    public SavedRecording save() throws IOException, IllegalStateException {
        return save(createRecordingUri());
    }

    public Uri createRecordingUri() {
        String saveDate = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        String fileName = mStartTimeMillis > 0L
                ? String.format("screen-%s-%d.mp4", saveDate, mStartTimeMillis)
                : String.format("screen-%s.mp4", saveDate);

        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis());
        values.put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis());

        ContentResolver resolver = mContext.getContentResolver();
        Uri collectionUri = MediaStore.Video.Media.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY);
        return ContentProvider.maybeAddUserId(resolver.insert(collectionUri, values),
                mContext.getUserId());
    }

    /**
     * Store recorded video
     */
    public SavedRecording save(Uri itemUri)
            throws IOException, IllegalStateException {
        Log.d(TAG, itemUri.toString());
        ContentResolver resolver = mContext.getContentResolver();
        if (mAudioSource == MIC_AND_INTERNAL || mAudioSource == INTERNAL) {
            try {
                Log.d(TAG, "muxing recording");
                File file = File.createTempFile("temp", ".mp4",
                        mContext.getCacheDir());
                ScreenRecordingMuxer muxer = new ScreenRecordingMuxer(
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                        file.getAbsolutePath(),
                        mTempVideoFile.getAbsolutePath(),
                        mTempAudioFile.getAbsolutePath());
                muxer.mux();
                mTempVideoFile.delete();
                mTempVideoFile = file;
            } catch (IOException e) {
                Log.e(TAG, "muxing recording " + e.getMessage());
                e.printStackTrace();
            }
        }

        // Add to the mediastore
        OutputStream os = resolver.openOutputStream(itemUri, "w");
        Files.copy(mTempVideoFile.toPath(), os);
        os.close();
        if (mTempAudioFile != null) mTempAudioFile.delete();
        SavedRecording recording = new SavedRecording(
                itemUri, mTempVideoFile, getRequiredThumbnailSize());
        mTempVideoFile.delete();
        return recording;
    }

    /**
     * Returns the required {@code Size} of the thumbnail.
     */
    private Size getRequiredThumbnailSize() {
        int thumbnailIconHeight = mContext.getResources().getDimensionPixelSize(
                R.dimen.notification_big_picture_max_height);
        int thumbnailIconWidth = mContext.getResources().getDimensionPixelSize(
                R.dimen.notification_big_picture_max_width);
        return new Size(thumbnailIconWidth, thumbnailIconHeight);
    }

    /**
     * Release the resources without saving the data
     */
    public void release() {
        if (mTempVideoFile != null) {
            mTempVideoFile.delete();
        }
        if (mTempAudioFile != null) {
            mTempAudioFile.delete();
        }
    }

    /**
     * Currently, screen recording is being treated as a real time usecase which is at the same
     * priority as any other video encoding/decoding usecases. This can result in video playback and
     * recording failures while screen recording is in progress.
     *
     * Test the selfie enabled when increasing the cap because it's known to overflow the
     * buffer when it is too high.
     *
     * @return frame rate that is supported by the codec and adjusted for the screen recording.
     */
    private int getSupportedFrameRateFor(MediaCodecInfo.VideoCapabilities vc, int width,
            int height) {
        int maxRate = vc.getSupportedFrameRatesFor(width,
                height).getUpper().intValue() / 2;
        // hard cap refresh rate at VIDEO_FRAME_RATE anyway
        return Math.min(maxRate, VIDEO_FRAME_RATE);
    }

    private static final class MediaProjectionCallback extends MediaProjection.Callback {

        private final ScreenMediaRecorderListener mListener;
        private final int mUserId;

        MediaProjectionCallback(ScreenMediaRecorderListener listener, int userId) {
            mListener = listener;
            mUserId = userId;
        }

        @Override
        public void onStop() {
            Log.d(TAG, "Projection stopped");
            mListener.onStopped(mUserId, StopReason.STOP_TARGET_REMOVED);
        }

        @Override
        public void onCapturedContentVisibilityChanged(boolean isVisible) {
            if (!isVisible) {
                Log.d(TAG, "Content became invisible");
                mListener.onStopped(mUserId, StopReason.STOP_TARGET_REMOVED);
            }
        }
    }

    /**
     * Object representing the recording
     */
    public static class SavedRecording {

        @NonNull
        private final Uri mUri;
        @Nullable
        private final Icon mThumbnailIcon;

        public SavedRecording(@NonNull Uri uri, File file, Size thumbnailSize) {
            this(uri, createThumbnail(file, thumbnailSize));
        }

        public SavedRecording(@NonNull Uri uri, @Nullable Icon thumbnailIcon) {
            mUri = uri;
            mThumbnailIcon = thumbnailIcon;
        }

        @NonNull
        public Uri getUri() {
            return mUri;
        }

        public @Nullable Icon getThumbnail() {
            return mThumbnailIcon;
        }

        @Nullable
        private static Icon createThumbnail(File file, Size thumbnailSize) {
            Icon thumbnailIcon = null;
            try {
                Bitmap thumbnailBitmap = ThumbnailUtils.createVideoThumbnail(
                        file, thumbnailSize, null);
                thumbnailIcon = Icon.createWithBitmap(thumbnailBitmap);
            } catch (IOException e) {
                Log.e(TAG, "Error creating thumbnail", e);
            }
            return thumbnailIcon;
        }
    }

    public interface ScreenMediaRecorderListener {

        /**
         * Called when the recording actually starts
         */
        void onStarted();

        /**
         * Called to indicate an info or a warning during recording.
         * See {@link MediaRecorder.OnInfoListener} for the full description.
         */
        void onInfo(MediaRecorder mr, int what, int extra);

        /**
         * Called when the recording stopped by the system.
         * For example, this might happen when doing partial screen sharing of an app
         * and the app that is being captured is closed.
         */
        void onStopped(int userId, @StopReason int stopReason);
    }

    /**
     * Allows to register multiple {@link Closeable} objects and close them all by calling
     * {@link Closer#close}. If there is an exception thrown during closing of one
     * of the registered closeables it will continue trying closing the rest closeables.
     * If there are one or more exceptions thrown they will be re-thrown at the end.
     * In case of multiple exceptions only the first one will be thrown and all the rest
     * will be printed.
     */
    private static class Closer implements Closeable {
        private final List<Closeable> mCloseables = new ArrayList<>();

        void register(Closeable closeable) {
            mCloseables.add(closeable);
        }

        @Override
        public void close() throws IOException {
            Throwable throwable = null;

            for (int i = 0; i < mCloseables.size(); i++) {
                Closeable closeable = mCloseables.get(i);

                try {
                    closeable.close();
                } catch (Throwable e) {
                    if (throwable == null) {
                        throwable = e;
                    } else {
                        e.printStackTrace();
                    }
                }
            }

            if (throwable != null) {
                if (throwable instanceof IOException) {
                    throw (IOException) throwable;
                }

                if (throwable instanceof RuntimeException) {
                    throw (RuntimeException) throwable;
                }

                throw (Error) throwable;
            }
        }
    }

    private record VideoParameters(int mWidth, int mHeight, int mRefreshRate) {

        int bitrate() {
            return mWidth * mHeight * mRefreshRate / VIDEO_FRAME_RATE
                    * VIDEO_FRAME_RATE_TO_RESOLUTION_RATIO;
        }
    }
}
