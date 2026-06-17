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

package android.view;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.WindowConfiguration;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.IBinder;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.util.Log;
import android.util.MergedConfiguration;
import android.view.View.FocusDirection;
import android.view.WindowInsets.Type.InsetsType;
import android.view.inputmethod.ImeTracker;
import android.window.ClientWindowFrames;
import android.window.InputTransferToken;
import android.window.OnBackInvokedCallbackInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/**
* A simplistic implementation of IWindowSession. Rather than managing Surfaces
* as children of the display, it manages Surfaces as children of a given root.
*
* By parcelling the root surface, the app can offer another app content for embedding.
* @hide
*/
public class WindowlessWindowManager implements IWindowSession {
    private final static String TAG = "WindowlessWindowManager";

    private class State {
        SurfaceControl mSurfaceControl;
        final WindowManager.LayoutParams mParams = new WindowManager.LayoutParams();
        final WindowManager.LayoutParams mLastReportedParams = new WindowManager.LayoutParams();
        int mDisplayId;
        IBinder mInputChannelToken;
        Region mInputRegion;
        IWindow mClient;
        SurfaceControl mLeash;
        Rect mFrame;
        Rect mAttachedFrame;
        InputTransferToken mInputTransferToken;

        State(SurfaceControl sc, WindowManager.LayoutParams p, int displayId, IWindow client,
                SurfaceControl leash, Rect frame) {
            mSurfaceControl = sc;
            mParams.copyFrom(p);
            mDisplayId = displayId;
            mClient = client;
            mLeash = leash;
            mFrame = frame;
        }
    };

    /**
     * Used to store SurfaceControl we've built for clients to
     * reconfigure them if relayout is called.
     */
    final HashMap<IBinder, State> mStateForWindow = new HashMap<>();

    public interface ResizeCompleteCallback {
        public void finished(SurfaceControl.Transaction completion);
    }

    final HashMap<IBinder, ResizeCompleteCallback> mResizeCompletionForWindow =
        new HashMap<IBinder, ResizeCompleteCallback>();

    protected final SurfaceControl mRootSurface;
    private final Configuration mConfiguration;
    private final IWindowSession mRealWm;
    private InputTransferToken mHostInputTransferToken;
    private final InputTransferToken mInputTransferToken = new InputTransferToken();
    private InsetsState mInsetsState;
    private final ClientWindowFrames mTmpFrames = new ClientWindowFrames();
    private final MergedConfiguration mTmpConfig = new MergedConfiguration();
    private final WindowlessWindowLayout mLayout = new WindowlessWindowLayout();

    private ISurfaceControlViewHostParent mParentInterface;

    public WindowlessWindowManager(Configuration c, SurfaceControl rootSurface,
            InputTransferToken hostInputTransferToken) {
        mRootSurface = rootSurface;
        mConfiguration = new Configuration(c);
        mRealWm = WindowManagerGlobal.getWindowSession();
        mHostInputTransferToken = hostInputTransferToken;
    }

    public void setConfiguration(Configuration configuration) {
        mConfiguration.setTo(configuration);
        dispatchStateToClients();
    }

    InputTransferToken getInputTransferToken(IBinder window) {
        synchronized (this) {
            // This can only happen if someone requested the focusGrantToken before setView was
            // called for the SCVH. In that case, use the root focusGrantToken since this will be
            // the same token sent to WMS for the root window once setView is called.
            if (mStateForWindow.isEmpty()) {
                return mInputTransferToken;
            }
            State state = mStateForWindow.get(window);
            if (state != null) {
                return state.mInputTransferToken;
            }
        }

        Log.w(TAG, "Failed to get focusGrantToken. Returning null token");
        return null;
    }

    void setHostInputTransferToken(InputTransferToken token) {
        mHostInputTransferToken = token;
    }

    InputTransferToken getHostInputTransferToken() {
        return mHostInputTransferToken;
    }

    /** Utility API. */
    void setCompletionCallback(IBinder window, ResizeCompleteCallback callback) {
        if (mResizeCompletionForWindow.get(window) != null) {
            Log.w(TAG, "Unsupported overlapping resizes");
        }
        mResizeCompletionForWindow.put(window, callback);
    }

    protected void setTouchRegion(IBinder window, @Nullable Region region) {
        State state;
        synchronized (this) {
            // Do everything while locked so that we synchronize with relayout. This should be a
            // very infrequent operation.
            state = mStateForWindow.get(window);
            if (state == null) {
                return;
            }
            if (Objects.equals(region, state.mInputRegion)) {
                return;
            }
            state.mInputRegion = region != null ? new Region(region) : null;
            updateInputChannel(window);
        }
    }

    protected void updateInputChannel(IBinder window) {
        State state;
        synchronized (this) {
            // Do everything while locked so that we synchronize with relayout. This should be a
            // very infrequent operation.
            state = mStateForWindow.get(window);
            if (state == null) {
                return;
            }
            if (state.mInputChannelToken != null) {
                try {
                    final WindowInputChannelParams params = new WindowInputChannelParams();
                    params.displayId = state.mDisplayId;
                    params.channelToken = state.mInputChannelToken;
                    params.hostInputTransferToken = mHostInputTransferToken;
                    params.surface = state.mSurfaceControl;
                    params.flags = state.mParams.flags;
                    params.privateFlags = state.mParams.privateFlags;
                    params.inputFeatures = state.mParams.inputFeatures;
                    params.region = state.mInputRegion;
                    mRealWm.updateInputChannel(params);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to update surface input channel: ", e);
                }
            }
        }
    }

    protected SurfaceControl getParentSurface(IWindow window, WindowManager.LayoutParams attrs) {
        // If this is the first window, the state map is empty and the parent surface is the
        // root. Otherwise, the parent surface is in the state map.
        synchronized (this) {
            if (mStateForWindow.isEmpty()) {
                return mRootSurface;
            }
            return mStateForWindow.get(attrs.token).mLeash;
        }
    }

    /** IWindowSession implementation. */
    @Override
    public int addToDisplay(IWindow window, WindowManager.LayoutParams attrs,
            int viewVisibility, int displayId, @InsetsType int requestedVisibleTypes,
            InputChannel outInputChannel, WindowRelayoutResult result) {
        final SurfaceControl leash = new SurfaceControl.Builder()
                .setName(attrs.getTitle().toString() + "Leash")
                .setCallsite("WindowlessWindowManager.addToDisplay")
                .setParent(getParentSurface(window, attrs))
                .build();

        final SurfaceControl sc = new SurfaceControl.Builder()
                .setFormat(attrs.format)
                .setBLASTLayer()
                .setName(attrs.getTitle().toString())
                .setCallsite("WindowlessWindowManager.addToDisplay")
                .setHidden(false)
                .setParent(leash)
                .build();

        final State state = new State(sc, attrs, displayId, window, leash, /* frame= */ new Rect());
        synchronized (this) {
            State parentState = mStateForWindow.get(attrs.token);
            if (parentState != null) {
                state.mAttachedFrame = parentState.mFrame;
            }

            // Give the first window the mFocusGrantToken since that's the token the host can use
            // to give focus to the embedded.
            if (mStateForWindow.isEmpty()) {
                state.mInputTransferToken = mInputTransferToken;
            } else {
                state.mInputTransferToken = new InputTransferToken();
            }

            mStateForWindow.put(window.asBinder(), state);
        }

        if (state.mAttachedFrame == null) {
            result.frames.attachedFrame = null;
        } else {
            result.frames.attachedFrame = new Rect(state.mAttachedFrame);
        }
        result.frames.compatScale = 1f;

        if (((attrs.inputFeatures &
                WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL) == 0)) {
            try {
                final WindowInputChannelParams params = new WindowInputChannelParams();
                params.displayId = displayId;
                params.clientToken = window.asBinder();
                params.hostInputTransferToken = mHostInputTransferToken;
                params.inputTransferToken = state.mInputTransferToken;
                params.windowToken = attrs.token;
                params.type = attrs.type;
                params.flags = attrs.flags;
                params.privateFlags = attrs.privateFlags;
                params.inputFeatures = attrs.inputFeatures;
                params.inputHandleName = attrs.getTitle().toString();

                if (mRealWm instanceof IWindowSession.Stub) {
                    params.surface = new SurfaceControl(sc, "WindowlessWindowManager.addToDisplay");
                    InputChannel inputChannel = mRealWm.grantInputChannel(params);
                    inputChannel.copyTo(outInputChannel);
                } else {
                    params.surface = sc;
                    InputChannel inputChannel = mRealWm.grantInputChannel(params);
                    inputChannel.copyTo(outInputChannel);
                }
                state.mInputChannelToken =
                        outInputChannel != null ? outInputChannel.getToken() : null;
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to grant input to surface: ", e);
            }
        }

        final int res = WindowManagerGlobal.ADD_OKAY | WindowManagerGlobal.ADD_FLAG_APP_VISIBLE;

        sendLayoutParamsToParent();
        // Include whether the window is in touch mode.
        return isInTouchModeInternal(displayId) ? res | WindowManagerGlobal.ADD_FLAG_IN_TOUCH_MODE
                : res;
    }

    /**
     * IWindowSession implementation. Currently this class doesn't need to support for multi-user.
     */
    @Override
    public int addToDisplayAsUser(IWindow window, WindowManager.LayoutParams attrs,
            int viewVisibility, int displayId, int userId, @InsetsType int requestedVisibleTypes,
            InputChannel outInputChannel, WindowRelayoutResult result) {
        return addToDisplay(window, attrs, viewVisibility, displayId, requestedVisibleTypes,
                outInputChannel, result);
    }

    @Override
    public void remove(IBinder clientToken) throws RemoteException {
        mRealWm.remove(clientToken);
        State state;
        synchronized (this) {
            state = mStateForWindow.remove(clientToken);
        }
        if (state == null) {
            throw new IllegalArgumentException(
                    "Invalid window token (never added or removed already)");
        }
        removeSurface(state.mSurfaceControl);
        removeSurface(state.mLeash);
    }

    /** Separate from {@link #remove} so that subclasses can put removal on a sync transaction. */
    protected void removeSurface(SurfaceControl sc) {
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            t.remove(sc).apply();
        }
    }

    private boolean isOpaque(WindowManager.LayoutParams attrs) {
        if (attrs.surfaceInsets != null && attrs.surfaceInsets.left != 0 ||
                attrs.surfaceInsets.top != 0 || attrs.surfaceInsets.right != 0 ||
                attrs.surfaceInsets.bottom != 0) {
            return false;
        }
        return !PixelFormat.formatHasAlpha(attrs.format);
    }

    private boolean isInTouchModeInternal(int displayId) {
        try {
            return WindowManagerGlobal.getWindowManagerService().isInTouchMode(displayId);
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to check if the window is in touch mode", e);
        }
        return false;
    }

    /** Access to package members for SystemWindow leashing
     * @hide
     */
    protected IBinder getWindowBinder(View rootView) {
        final ViewRootImpl root = rootView.getViewRootImpl();
        if (root == null) {
            return null;
        }
        return root.mWindow.asBinder();
    }

    /** @hide */
    @Nullable
    protected SurfaceControl getSurfaceControl(View rootView) {
        final ViewRootImpl root = rootView.getViewRootImpl();
        if (root == null) {
            return null;
        }
        return getSurfaceControl(root.mWindow);
    }

    /** @hide */
    @Nullable
    protected SurfaceControl getSurfaceControl(IWindow window) {
        final State s = mStateForWindow.get(window.asBinder());
        if (s == null) {
            return null;
        }
        return s.mSurfaceControl;
    }

    /**
     * Requests input focus for the given embedded view root, to be used for cases where the
     * embedded window is not rooted to any window (otherwise focus is managed by the hosting
     * SurfaceView).
     *
     * WM will enforce that callers also hold the INTERNAL_SYSTEM_WINDOW permission.
     * If `focused` is false, WM will resolve focus on the next window.
     * @hide
     */
    boolean requestInputFocus(@NonNull ViewRootImpl viewRoot, boolean focused) {
        final State s = mStateForWindow.get(viewRoot.mWindow.asBinder());
        if (s == null) {
            Log.w(TAG, "Invalid view root specified, not an embedded window");
            return false;
        }
        try {
            mRealWm.grantEmbeddedWindowFocus(null /* callingWin */, s.mInputTransferToken,
                    focused);
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to request input focus on embedded window", e);
            return false;
        }
    }

    @Override
    public int relayout(IWindow window, WindowManager.LayoutParams inAttrs,
            int requestedWidth, int requestedHeight, int viewFlags, int flags, int seq,
            int lastSyncSeqId, WindowRelayoutResult outRelayoutResult, SurfaceControl outSurface) {
        final ClientWindowFrames outFrames;
        final MergedConfiguration outMergedConfiguration;
        final SurfaceControl outSurfaceControl = outSurface;
        final InsetsState outInsetsState;
        final InsetsSourceControl.Array outActiveControls;
        if (outRelayoutResult != null) {
            outFrames = outRelayoutResult.frames;
            outMergedConfiguration = outRelayoutResult.mergedConfiguration;
            outInsetsState = outRelayoutResult.insetsState;
            outActiveControls = outRelayoutResult.activeControls;
        } else {
            outFrames = null;
            outMergedConfiguration = null;
            outInsetsState = null;
            outActiveControls = null;
        }
        return relayoutInner(window, inAttrs, requestedWidth, requestedHeight, viewFlags, flags,
                seq, lastSyncSeqId, outFrames, outMergedConfiguration, outSurfaceControl,
                outInsetsState, outActiveControls);
    }

    private int relayoutInner(IWindow window, WindowManager.LayoutParams inAttrs,
            int requestedWidth, int requestedHeight, int viewFlags, int flags, int seq,
            int lastSyncSeqId, ClientWindowFrames outFrames,
            MergedConfiguration outMergedConfiguration, SurfaceControl outSurfaceControl,
            InsetsState outInsetsState, InsetsSourceControl.Array outActiveControls) {
        final State state;
        synchronized (this) {
            state = mStateForWindow.get(window.asBinder());
        }
        if (state == null) {
            throw new IllegalArgumentException(
                    "Invalid window token (never added or removed already)");
        }
        SurfaceControl sc = state.mSurfaceControl;
        SurfaceControl leash = state.mLeash;
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();

        int attrChanges = 0;
        if (inAttrs != null) {
            attrChanges = state.mParams.copyFrom(inAttrs);
        }
        WindowManager.LayoutParams attrs = state.mParams;

        ClientWindowFrames frames = new ClientWindowFrames();
        frames.attachedFrame = state.mAttachedFrame;

        mLayout.computeFrames(attrs, null, null, null, WindowConfiguration.WINDOWING_MODE_UNDEFINED,
                requestedWidth, requestedHeight, 0, 0,
                frames);

        state.mFrame.set(frames.frame);
        if (outFrames != null) {
            outFrames.frame.set(frames.frame);
            outFrames.parentFrame.set(frames.parentFrame);
            outFrames.displayFrame.set(frames.displayFrame);
        }

        t.setPosition(leash, frames.frame.left, frames.frame.top);

        if (viewFlags == View.VISIBLE) {
            // TODO(b/262892794) ViewRootImpl modifies the app's rendering SurfaceControl
            // opaqueness. We shouldn't need to modify opaqueness for this SurfaceControl here or
            // in the real WindowManager.
            t.setOpaque(sc, isOpaque(attrs)).show(leash).apply();
            if (outSurfaceControl != null) {
                outSurfaceControl.copyFrom(sc, "WindowlessWindowManager.relayout");
            }
        } else {
            t.hide(leash).apply();
            if (outSurfaceControl != null) {
                outSurfaceControl.release();
            }
        }

        if (outMergedConfiguration != null) {
            outMergedConfiguration.setConfiguration(mConfiguration, mConfiguration);
        }

        final int inputChangeMask = WindowManager.LayoutParams.FLAGS_CHANGED
                | WindowManager.LayoutParams.INPUT_FEATURES_CHANGED;
        if ((attrChanges & inputChangeMask) != 0 && state.mInputChannelToken != null) {
            try {
                final WindowInputChannelParams params = new WindowInputChannelParams();
                params.displayId = state.mDisplayId;
                params.channelToken = state.mInputChannelToken;
                params.hostInputTransferToken = mHostInputTransferToken;
                params.flags = attrs.flags;
                params.privateFlags = attrs.privateFlags;
                params.inputFeatures = attrs.inputFeatures;
                params.region = state.mInputRegion;

                if (mRealWm instanceof IWindowSession.Stub) {
                    params.surface = new SurfaceControl(sc, "WindowlessWindowManager.relayout");
                    mRealWm.updateInputChannel(params);
                } else {
                    params.surface = sc;
                    mRealWm.updateInputChannel(params);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to update surface input channel: ", e);
            }
        }

        if (outInsetsState != null && mInsetsState != null) {
            outInsetsState.set(mInsetsState);
        }

        sendLayoutParamsToParent();
        return 0;
    }

    @Override
    public void relayoutAsync(IWindow window, WindowManager.LayoutParams inAttrs,
            int requestedWidth, int requestedHeight, int viewFlags, int flags, int seq,
            int lastSyncSeqId) {
        relayoutInner(window, inAttrs, requestedWidth, requestedHeight, viewFlags, flags, seq,
                lastSyncSeqId, null /* outFrames */, null /* outMergedConfiguration */,
                null /* outSurfaceControl */, null /* outInsetsState */,
                null /* outActiveControls */);
    }

    @Override
    public int relayout2(IWindow window, WindowManager.LayoutParams inAttrs,
            int requestedWidth, int requestedHeight, int viewFlags, int flags, int seq,
            int lastSyncSeqId, SurfaceControl surface, WindowRelayoutResult outRelayoutResult) {
        return relayout(window, inAttrs, requestedWidth, requestedHeight, viewFlags, flags, seq,
                lastSyncSeqId, outRelayoutResult, surface);
    }

    @Override
    public void relayoutAsync2(IWindow window, WindowManager.LayoutParams inAttrs,
            int requestedWidth, int requestedHeight, int viewFlags, int flags, int seq,
            int lastSyncSeqId, SurfaceControl surfaceControl) {
        relayoutInner(window, inAttrs, requestedWidth, requestedHeight, viewFlags, flags, seq,
                lastSyncSeqId, null /* outFrames */, null /* outMergedConfiguration */,
                surfaceControl, null /* outInsetsState */, null /* outActiveControls */);
    }

    @Override
    public boolean outOfMemory(android.view.IWindow window) {
        return false;
    }

    @Override
    public void setInsets(android.view.IWindow window, int touchableInsets,
            android.graphics.Rect contentInsets, android.graphics.Rect visibleInsets,
            android.graphics.Region touchableRegion) {
        setTouchRegion(window.asBinder(), touchableRegion);
    }

    @Override
    public void clearTouchableRegion(android.view.IWindow window) {
        setTouchRegion(window.asBinder(), null);
    }

    @Override
    public void finishDrawing(android.view.IWindow window,
            android.view.SurfaceControl.Transaction postDrawTransaction, int seqId) {
        synchronized (this) {
            final ResizeCompleteCallback c =
                mResizeCompletionForWindow.get(window.asBinder());
            if (c == null) {
                // No one wanted the callback, but it wasn't necessarily unexpected.
                postDrawTransaction.apply();
                return;
            }
            c.finished(postDrawTransaction);
            mResizeCompletionForWindow.remove(window.asBinder());
        }
    }

    @Override
    public android.os.IBinder performDrag(android.view.IWindow window, int flags,
            android.view.SurfaceControl surface, int touchSource, int touchDeviceId,
            int touchPointerId, float touchX, float touchY, float thumbCenterX, float thumbCenterY,
            android.content.ClipData data) {
        return null;
    }

    @Override
    public void reportDropResult(android.view.IWindow window, boolean consumed) {
    }

    @Override
    public void cancelDragAndDrop(android.os.IBinder dragToken, boolean skipAnimation) {
    }

    @Override
    public void dragRecipientEntered(android.view.IWindow window) {
    }

    @Override
    public void dragRecipientExited(android.view.IWindow window) {
    }

    @Override
    public void setWallpaperPosition(android.os.IBinder windowToken, float x, float y,
            float xstep, float ystep) {
    }

    @Override
    public void setWallpaperZoomOut(android.os.IBinder windowToken, float zoom) {
    }

    @Override
    public void setShouldZoomOutWallpaper(android.os.IBinder windowToken, boolean shouldZoom) {
    }

    @Override
    public void setWallpaperDisplayOffset(android.os.IBinder windowToken, int x, int y) {
    }

    @Override
    public void sendWallpaperCommand(android.os.IBinder window,
            java.lang.String action, int x, int y, int z, android.os.Bundle extras) {
    }

    @Override
    public void onRectangleOnScreenRequested(android.os.IBinder token,
            android.graphics.Rect rectangle, int source) {
    }

    @Override
    public android.view.IWindowId getWindowId(android.os.IBinder window) {
        return null;
    }

    @Override
    public void pokeDrawLock(android.os.IBinder window) {
    }

    @Override
    public boolean startMovingTask(android.view.IWindow window, float startX, float startY) {
        return false;
    }

    @Override
    public void finishMovingTask(android.view.IWindow window) {
    }

    @Override
    public void updateTapExcludeRegion(android.view.IWindow window,
            android.graphics.Region region) {
    }

    @Override
    public void updateRequestedVisibleTypes(IWindow window,
            @InsetsType int requestedVisibleTypes, @Nullable ImeTracker.Token imeStatsToken)
            throws RemoteException {
        // Embedded windows do not control insets (except for IME). The host window is
        // responsible for controlling the insets.
        mRealWm.updateRequestedVisibleTypes(window,
                requestedVisibleTypes & WindowInsets.Type.ime(), imeStatsToken);
    }

    @Override
    public void updateAnimatingTypes(IWindow window, @InsetsType int animatingTypes,
            @Nullable ImeTracker.Token statsToken) {
        // NO-OP
    }

    @Override
    public void reportSystemGestureExclusionChanged(android.view.IWindow window,
            List<Rect> exclusionRects) {
    }

    @Override
    public void reportDecorViewGestureInterceptionChanged(IWindow window, boolean intercepted) {}

    @Override
    public void reportKeepClearAreasChanged(
            android.view.IWindow window,
            List<Rect> restrictedRects,
            List<Rect> unrestrictedRects) {}

    @Override
    public InputChannel grantInputChannel(@NonNull WindowInputChannelParams params) {
        return null;
    }

    @Override
    public void updateInputChannel(@NonNull WindowInputChannelParams params) {}

    @Override
    public android.os.IBinder asBinder() {
        return null;
    }

    @Override
    public void grantEmbeddedWindowFocus(IWindow callingWindow, InputTransferToken targetInputToken,
                                         boolean grantFocus) {
    }

    @Override
    public void generateDisplayHash(IWindow window, Rect boundsInWindow, String hashAlgorithm,
            RemoteCallback callback) {
    }

    @Override
    public void setOnBackInvokedCallbackInfo(IWindow window,
            OnBackInvokedCallbackInfo callbackInfo) throws RemoteException {
        final State state;
        synchronized (this) {
            state = mStateForWindow.get(window.asBinder());
        }
        if (state == null) {
            throw new IllegalArgumentException(
                    "Invalid window token (never added or removed already)");
        }
        try {
            mRealWm.setOnBackInvokedCallbackInfoToEmbedded(state.mInputTransferToken,
                    callbackInfo);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register onBackInvokedCallback on embedded window", e);
        }
    }

    @Override
    public void setOnBackInvokedCallbackInfoToEmbedded(InputTransferToken token,
            OnBackInvokedCallbackInfo callbackInfo) throws RemoteException { }

    @Override
    public boolean dropForAccessibility(IWindow window, int x, int y) {
        return false;
    }

    public void setInsetsState(InsetsState state) {
        mInsetsState = state;
        dispatchStateToClients();
    }

    @Override
    public boolean cancelDraw(IWindow window, int seqId) {
        return false;
    }

    @Override
    public boolean moveFocusToAdjacentWindow(IWindow fromWindow, @FocusDirection int direction) {
        if (mHostInputTransferToken == null || mParentInterface == null) {
            // we don't have a host or an interface to the host drop this for now.
            return false;
        }
        try {
            mParentInterface.transferFocusToParent(direction);
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to forward focus move to Parent: ", e);
            return false;
        }
    }

    @Override
    public void notifyImeWindowVisibilityChangedFromClient(IWindow window, boolean visible,
            @NonNull ImeTracker.Token statsToken) {
    }

    void setParentInterface(@Nullable ISurfaceControlViewHostParent parentInterface) {
        IBinder oldInterface = mParentInterface == null ? null : mParentInterface.asBinder();
        IBinder newInterface = parentInterface == null ? null : parentInterface.asBinder();
        // If the parent interface has changed, it needs to clear the last reported params so it
        // will update the new interface with the params.
        if (oldInterface != newInterface) {
            clearLastReportedParams();
        }
        mParentInterface = parentInterface;
        sendLayoutParamsToParent();
    }

    private void clearLastReportedParams() {
        WindowManager.LayoutParams emptyParam = new WindowManager.LayoutParams();
        for (State windowInfo : mStateForWindow.values()) {
            windowInfo.mLastReportedParams.copyFrom(emptyParam);
        }
    }

    private void sendLayoutParamsToParent() {
        if (mParentInterface == null) {
            return;
        }
        WindowManager.LayoutParams[] params =
                new WindowManager.LayoutParams[mStateForWindow.size()];
        int index = 0;
        boolean hasChanges = false;
        for (State windowInfo : mStateForWindow.values()) {
            int changes = windowInfo.mLastReportedParams.copyFrom(windowInfo.mParams);
            hasChanges |= (changes != 0);
            WindowManager.LayoutParams p = new WindowManager.LayoutParams();
            p.copyFrom(windowInfo.mParams);
            p.token = null;
            p.mWindowContextToken = null;
            params[index++] = p;
        }

        if (hasChanges) {
            try {
                mParentInterface.updateParams(params);
            } catch (RemoteException e) {
            }
        }
    }

    /** Notifies clients of changes to {@code mConfiguration} and {@code mInsetsState}. */
    private void dispatchStateToClients() {
        if (mInsetsState == null) {
            return;
        }

        for (State s : mStateForWindow.values()) {
            try {
                mTmpFrames.frame.set(0, 0, s.mParams.width, s.mParams.height);
                mTmpFrames.displayFrame.set(mTmpFrames.frame);
                mTmpConfig.setConfiguration(mConfiguration, mConfiguration);
                final WindowRelayoutResult layout =
                        new WindowRelayoutResult(mTmpFrames, mTmpConfig, mInsetsState, null);
                layout.syncSeqId = Integer.MAX_VALUE;
                s.mClient.resized(
                        layout,
                        false /* reportDraw */,
                        false /* forceLayout */,
                        s.mDisplayId,
                        false /* syncWithBuffers */,
                        false /* dragResizing */);
            } catch (RemoteException e) {
                // Too bad
            }
        }
    }

    boolean forwardBackKeyToParent(@NonNull KeyEvent keyEvent) {
        if (mParentInterface == null) {
            return false;
        }
        try {
            mParentInterface.forwardBackKeyToParent(keyEvent);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to forward back key To Parent: ", e);
            return false;
        }
        return true;
    }
}
