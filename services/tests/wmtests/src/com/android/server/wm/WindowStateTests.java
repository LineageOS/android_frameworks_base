/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.permission.flags.Flags.FLAG_SENSITIVE_NOTIFICATION_APP_PROTECTION;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.InsetsFrameProvider.SOURCE_ATTACHED_CONTAINER_BOUNDS;
import static android.view.InsetsSource.ID_IME;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowInsets.Type.navigationBars;
import static android.view.WindowInsets.Type.statusBars;
import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;
import static android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_INPUT_METHOD_WINDOW;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ABOVE_SUB_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_MAGNIFICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE;
import static android.view.WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doThrow;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.WindowContainer.SYNC_STATE_WAITING_FOR_DRAW;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.WindowStateResizeItem;
import android.content.ContentResolver;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.InputConfig;
import android.os.RemoteException;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.MergedConfiguration;
import android.view.Gravity;
import android.view.IWindow;
import android.view.InputDevice;
import android.view.InputWindowHandle;
import android.view.InsetsFrameProvider;
import android.view.InsetsSource;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.KeyCharacterMap;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowRelayoutResult;
import android.window.ClientWindowFrames;
import android.window.ITaskFragmentOrganizer;
import android.window.TaskFragmentOrganizer;

import androidx.test.filters.SmallTest;

import com.android.compatibility.common.util.SystemUtil;
import com.android.internal.R;
import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.testutils.StubTransaction;
import com.android.server.wm.SensitiveContentPackages.PackageInfo;
import com.android.window.flags.Flags;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * Tests for the {@link WindowState} class.
 *
 * <p> Build/Install/Run:
 * atest WmTests:WindowStateTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class WindowStateTests extends WindowTestsBase {

    @Before
    public void setUp() {
        when(mWm.mInputManager.getInputDevices()).thenReturn(new InputDevice[]{
                new InputDevice.Builder().setSources(InputDevice.SOURCE_TOUCHSCREEN).build()
        });
        mWm.onInputDevicesChanged();
    }

    @After
    public void tearDown() {
        mWm.mSensitiveContentPackages.clearBlockedApps();
    }

    @Test
    public void testIsParentWindowHidden() {
        final WindowState parentWindow = newWindowBuilder("parentWindow", TYPE_APPLICATION).build();
        final WindowState child1 = newWindowBuilder("child1", FIRST_SUB_WINDOW).setParent(
                parentWindow).build();
        final WindowState child2 = newWindowBuilder("child2", FIRST_SUB_WINDOW).setParent(
                parentWindow).build();

        // parentWindow is initially set to hidden.
        assertTrue(parentWindow.mHidden);
        assertFalse(parentWindow.isParentWindowHidden());
        assertTrue(child1.isParentWindowHidden());
        assertTrue(child2.isParentWindowHidden());

        parentWindow.mHidden = false;
        assertFalse(parentWindow.isParentWindowHidden());
        assertFalse(child1.isParentWindowHidden());
        assertFalse(child2.isParentWindowHidden());
    }

    @Test
    public void testIsChildWindow() {
        final WindowState parentWindow = newWindowBuilder("parentWindow", TYPE_APPLICATION).build();
        final WindowState child1 = newWindowBuilder("child1", FIRST_SUB_WINDOW).setParent(
                parentWindow).build();
        final WindowState child2 = newWindowBuilder("child2", FIRST_SUB_WINDOW).setParent(
                parentWindow).build();
        final WindowState randomWindow = newWindowBuilder("randomWindow", TYPE_APPLICATION).build();

        assertFalse(parentWindow.isChildWindow());
        assertTrue(child1.isChildWindow());
        assertTrue(child2.isChildWindow());
        assertFalse(randomWindow.isChildWindow());
    }

    @Test
    public void testHasChild() {
        final WindowState win1 = newWindowBuilder("win1", TYPE_APPLICATION).build();
        final WindowState win11 = newWindowBuilder("win11", FIRST_SUB_WINDOW).setParent(
                win1).build();
        final WindowState win12 = newWindowBuilder("win12", FIRST_SUB_WINDOW).setParent(
                win1).build();
        final WindowState win2 = newWindowBuilder("win2", TYPE_APPLICATION).build();
        final WindowState win21 = newWindowBuilder("win21", FIRST_SUB_WINDOW).setParent(
                win2).build();
        final WindowState randomWindow = newWindowBuilder("randomWindow", TYPE_APPLICATION).build();

        assertTrue(win1.hasChild(win11));
        assertTrue(win1.hasChild(win12));
        assertTrue(win2.hasChild(win21));

        assertFalse(win1.hasChild(win21));
        assertFalse(win1.hasChild(randomWindow));

        assertFalse(win2.hasChild(win11));
        assertFalse(win2.hasChild(win12));
        assertFalse(win2.hasChild(randomWindow));
    }

    @Test
    public void testGetParentWindow() {
        final WindowState parentWindow = newWindowBuilder("parentWindow", TYPE_APPLICATION).build();
        final WindowState child1 = newWindowBuilder("child1", FIRST_SUB_WINDOW).setParent(
                parentWindow).build();
        final WindowState child2 = newWindowBuilder("child2", FIRST_SUB_WINDOW).setParent(
                parentWindow).build();

        assertNull(parentWindow.getParentWindow());
        assertEquals(parentWindow, child1.getParentWindow());
        assertEquals(parentWindow, child2.getParentWindow());
    }

    @Test
    public void testOverlayWindowHiddenWhenSuspended() {
        final WindowState overlayWindow = spy(
                newWindowBuilder("overlayWindow", TYPE_APPLICATION_OVERLAY).build());
        overlayWindow.setHiddenWhileSuspended(true);
        verify(overlayWindow).hide(true /* doAnimation */, true /* requestAnim */);
        overlayWindow.setHiddenWhileSuspended(false);
        verify(overlayWindow).show(true /* doAnimation */, true /* requestAnim */);
    }

    @Test
    public void testSubWindowHiddenWhenSuspended() {
        final WindowState parent = newWindowBuilder("parent", TYPE_APPLICATION_OVERLAY).build();
        final WindowState subWindow = spy(
                newWindowBuilder("subWindow", TYPE_APPLICATION_PANEL).setParent(parent).build());

        subWindow.setHiddenWhileSuspended(true);
        verify(subWindow).hide(true /* doAnimation */, true /* requestAnim */);
        subWindow.setHiddenWhileSuspended(false);
        verify(subWindow).show(true /* doAnimation */, true /* requestAnim */);
    }

    @Test
    public void testGetTopParentWindow() {
        final WindowState root = newWindowBuilder("root", TYPE_APPLICATION).build();
        final WindowState child1 = newWindowBuilder("child1", FIRST_SUB_WINDOW).setParent(
                root).build();
        final WindowState child2 = newWindowBuilder("child2", FIRST_SUB_WINDOW).setParent(
                child1).build();

        assertEquals(root, root.getTopParentWindow());
        assertEquals(root, child1.getTopParentWindow());
        assertEquals(child1, child2.getParentWindow());
        assertEquals(root, child2.getTopParentWindow());

        // Test case were child is detached from parent.
        root.removeChild(child1);
        assertEquals(child1, child1.getTopParentWindow());
        assertEquals(child1, child2.getParentWindow());
    }

    @Test
    public void testIsOnScreen_hiddenByPolicy() {
        final WindowState window = newWindowBuilder("window", TYPE_APPLICATION).build();
        window.setHasSurface(true);
        assertTrue(window.isOnScreen());
        window.hide(false /* doAnimation */, false /* requestAnim */);
        assertFalse(window.isOnScreen());
        verify(mTransaction).hide(window.mSurfaceControl);
        clearInvocations(mTransaction);

        // Verifies that a window without animation can be hidden even if its parent is animating.
        window.show(false /* doAnimation */, false /* requestAnim */);
        assertTrue(window.isVisibleByPolicy());
        verify(mTransaction).show(window.mSurfaceControl);
        window.getParent().startAnimation(mTransaction, mock(AnimationAdapter.class),
                false /* hidden */, SurfaceAnimator.ANIMATION_TYPE_TOKEN_TRANSFORM);
        window.mAttrs.windowAnimations = 0;
        window.hide(true /* doAnimation */, true /* requestAnim */);
        assertFalse(window.isSelfAnimating(0, SurfaceAnimator.ANIMATION_TYPE_WINDOW_ANIMATION));
        assertFalse(window.isVisibleByPolicy());
        assertFalse(window.isOnScreen());

        // Verifies that a window with animation can be hidden after the hide animation is finished.
        window.show(false /* doAnimation */, false /* requestAnim */);
        window.mAttrs.windowAnimations = android.R.style.Animation_Dialog;
        window.hide(true /* doAnimation */, true /* requestAnim */);
        assertTrue(window.isSelfAnimating(0, SurfaceAnimator.ANIMATION_TYPE_WINDOW_ANIMATION));
        assertTrue(window.isVisibleByPolicy());
        window.cancelAnimation();
        assertFalse(window.isVisibleByPolicy());
    }

    @Test
    public void testShouldMagnify_typeIsMagnificationAndNavBarPanel_shouldNotMagnify() {
        final WindowState a11yMagWindow = newWindowBuilder("a11yMagWindow",
                TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY).build();
        final WindowState magWindow = newWindowBuilder("magWindow",
                TYPE_MAGNIFICATION_OVERLAY).build();
        final WindowState navPanelWindow = newWindowBuilder("navPanelWindow",
                TYPE_NAVIGATION_BAR_PANEL).build();

        a11yMagWindow.setHasSurface(true);
        magWindow.setHasSurface(true);
        navPanelWindow.setHasSurface(true);

        assertFalse(a11yMagWindow.shouldMagnify());
        assertFalse(magWindow.shouldMagnify());
        assertFalse(navPanelWindow.shouldMagnify());
    }

    @Test
    @DisableFlags(com.android.server.accessibility
            .Flags.FLAG_ENABLE_MAGNIFICATION_MAGNIFY_NAV_BAR_AND_IME)
    public void testMagnifyIme_flagOffAndSettingsEnabled_typeIsIme_shouldNotMagnify() {
        final ContentResolver cr = useFakeSettingsProvider();
        Settings.Secure.putInt(cr,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MAGNIFY_NAV_AND_IME, 1);
        mWm.mSettingsObserver.onChange(false /* selfChange */,
                Settings.Secure.getUriFor(
                        Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MAGNIFY_NAV_AND_IME));
        final WindowState imeWindow = newWindowBuilder("imeWindow", TYPE_INPUT_METHOD).build();
        final WindowState imeDialogWindow =
                newWindowBuilder("imeDialogWindow", TYPE_INPUT_METHOD_DIALOG).build();
        final WindowState privateImeWindow = newWindowBuilder("appWindow",
                TYPE_APPLICATION).build();
        privateImeWindow.mAttrs.privateFlags |= PRIVATE_FLAG_INPUT_METHOD_WINDOW;

        imeWindow.setHasSurface(true);
        imeDialogWindow.setHasSurface(true);
        privateImeWindow.setHasSurface(true);

        assertFalse(mWm.isMagnifyImeEnabled());
        assertFalse(imeWindow.shouldMagnify());
        assertFalse(imeDialogWindow.shouldMagnify());
        assertFalse(privateImeWindow.shouldMagnify());
    }

    @Test
    @EnableFlags(com.android.server.accessibility
            .Flags.FLAG_ENABLE_MAGNIFICATION_MAGNIFY_NAV_BAR_AND_IME)
    public void testMagnifyIme_flagOnAndSettingsDisabled_typeIsIme_shouldNotMagnify() {
        final ContentResolver cr = useFakeSettingsProvider();
        Settings.Secure.putInt(cr,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MAGNIFY_NAV_AND_IME, 0);
        mWm.mSettingsObserver.onChange(false /* selfChange */,
                Settings.Secure.getUriFor(
                        Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MAGNIFY_NAV_AND_IME));
        final WindowState imeWindow = newWindowBuilder("imeWindow", TYPE_INPUT_METHOD).build();
        final WindowState imeDialogWindow =
                newWindowBuilder("imeDialogWindow", TYPE_INPUT_METHOD_DIALOG).build();
        final WindowState privateImeWindow = newWindowBuilder("appWindow",
                TYPE_APPLICATION).build();
        privateImeWindow.mAttrs.privateFlags |= PRIVATE_FLAG_INPUT_METHOD_WINDOW;

        imeWindow.setHasSurface(true);
        imeDialogWindow.setHasSurface(true);
        privateImeWindow.setHasSurface(true);

        assertFalse(mWm.isMagnifyImeEnabled());
        assertFalse(imeWindow.shouldMagnify());
        assertFalse(imeDialogWindow.shouldMagnify());
        assertFalse(privateImeWindow.shouldMagnify());
    }

    @Test
    @EnableFlags(com.android.server.accessibility
            .Flags.FLAG_ENABLE_MAGNIFICATION_MAGNIFY_NAV_BAR_AND_IME)
    public void testMagnifyIme_flagOnAndSettingsEnabled_typeIsIme_shouldMagnify() {
        final ContentResolver cr = useFakeSettingsProvider();
        Settings.Secure.putInt(cr,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MAGNIFY_NAV_AND_IME, 1);
        mWm.mSettingsObserver.onChange(false /* selfChange */,
                Settings.Secure.getUriFor(
                        Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MAGNIFY_NAV_AND_IME));
        final WindowState imeWindow = newWindowBuilder("imeWindow", TYPE_INPUT_METHOD).build();
        final WindowState imeDialogWindow =
                newWindowBuilder("imeDialogWindow", TYPE_INPUT_METHOD_DIALOG).build();
        final WindowState privateImeWindow = newWindowBuilder("appWindow",
                TYPE_APPLICATION).build();
        privateImeWindow.mAttrs.privateFlags |= PRIVATE_FLAG_INPUT_METHOD_WINDOW;

        imeWindow.setHasSurface(true);
        imeDialogWindow.setHasSurface(true);
        privateImeWindow.setHasSurface(true);

        assertTrue(mWm.isMagnifyImeEnabled());
        assertTrue(imeWindow.shouldMagnify());
        assertTrue(imeDialogWindow.shouldMagnify());
        assertTrue(privateImeWindow.shouldMagnify());
    }

    @Test
    @EnableFlags(com.android.server.accessibility
            .Flags.FLAG_ENABLE_MAGNIFICATION_MAGNIFY_NAV_BAR_AND_IME)
    public void testMagnifyIme_flagOnAndDefaultEnable_typeIsIme_shouldMagnify() {
        useFakeSettingsProvider();  // This resets the Settings.Secure value.
        spyOn(mContext.getResources());
        when(mContext.getResources().getBoolean(
                R.bool.config_magnification_magnify_keyboard_default)).thenReturn(true);

        final WindowState imeWindow = newWindowBuilder("imeWindow", TYPE_INPUT_METHOD).build();
        final WindowState imeDialogWindow =
                newWindowBuilder("imeDialogWindow", TYPE_INPUT_METHOD_DIALOG).build();
        final WindowState privateImeWindow = newWindowBuilder("appWindow",
                TYPE_APPLICATION).build();
        privateImeWindow.mAttrs.privateFlags |= PRIVATE_FLAG_INPUT_METHOD_WINDOW;

        imeWindow.setHasSurface(true);
        imeDialogWindow.setHasSurface(true);
        privateImeWindow.setHasSurface(true);

        mWm.mSettingsObserver.loadSettings();

        assertTrue(mWm.isMagnifyImeEnabled());
        assertTrue(imeWindow.shouldMagnify());
        assertTrue(imeDialogWindow.shouldMagnify());
        assertTrue(privateImeWindow.shouldMagnify());
    }

    @Test
    @EnableFlags(com.android.server.accessibility
            .Flags.FLAG_ENABLE_MAGNIFICATION_MAGNIFY_NAV_BAR_AND_IME)
    public void testMagnifyIme_flagOnAndDefaultDisable_typeIsIme_shouldNotMagnify() {
        useFakeSettingsProvider();  // This resets the Settings.Secure value.
        spyOn(mContext.getResources());
        when(mContext.getResources().getBoolean(
                R.bool.config_magnification_magnify_keyboard_default)).thenReturn(false);

        final WindowState imeWindow = newWindowBuilder("imeWindow", TYPE_INPUT_METHOD).build();
        final WindowState imeDialogWindow =
                newWindowBuilder("imeDialogWindow", TYPE_INPUT_METHOD_DIALOG).build();
        final WindowState privateImeWindow = newWindowBuilder("appWindow",
                TYPE_APPLICATION).build();
        privateImeWindow.mAttrs.privateFlags |= PRIVATE_FLAG_INPUT_METHOD_WINDOW;

        imeWindow.setHasSurface(true);
        imeDialogWindow.setHasSurface(true);
        privateImeWindow.setHasSurface(true);

        mWm.mSettingsObserver.loadSettings();

        assertFalse(mWm.isMagnifyImeEnabled());
        assertFalse(imeWindow.shouldMagnify());
        assertFalse(imeDialogWindow.shouldMagnify());
        assertFalse(privateImeWindow.shouldMagnify());
    }

    @Test
    @EnableFlags(com.android.server.accessibility
            .Flags.FLAG_ENABLE_MAGNIFICATION_MAGNIFY_NAV_BAR_AND_IME)
    public void testMagnifyNavBar_WhenImeIsMagnified_shouldNotMagnify() {
        final ContentResolver cr = useFakeSettingsProvider();
        Settings.Secure.putInt(cr,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MAGNIFY_NAV_AND_IME, 1);
        mWm.mSettingsObserver.onChange(true /* selfChange */,
                Settings.Secure.getUriFor(
                        Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MAGNIFY_NAV_AND_IME));

        final WindowState navWindow = newWindowBuilder("navWindow", TYPE_NAVIGATION_BAR).build();
        navWindow.setHasSurface(true);

        // Here are examples of devices that should not trigger magnifying nav bar:
        when(mWm.mInputManager.getInputDevices()).thenReturn(new InputDevice[]{
                new InputDevice.Builder().setSources(InputDevice.SOURCE_TOUCHSCREEN).build(),
                new InputDevice.Builder().setSources(InputDevice.SOURCE_GAMEPAD).build(),
                new InputDevice.Builder().setSources(InputDevice.SOURCE_ROTARY_ENCODER).build(),
                // A disabled device.
                new InputDevice.Builder().setSources(InputDevice.SOURCE_MOUSE)
                        .setEnabled(false).build(),
                // A non-full alphabetic keyboard.
                new InputDevice.Builder().setSources(InputDevice.SOURCE_KEYBOARD)
                        .setKeyboardType(InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC).build(),
                // A virtual keyboard.
                new InputDevice.Builder().setSources(InputDevice.SOURCE_KEYBOARD)
                        .setId(KeyCharacterMap.VIRTUAL_KEYBOARD).build(),
        });
        mWm.onInputDevicesChanged();

        assertTrue(mWm.isMagnifyImeEnabled());
        assertFalse(mWm.isMagnifyNavBarEnabled());
        assertFalse(navWindow.shouldMagnify());
    }

    @Test
    @EnableFlags(com.android.server.accessibility
            .Flags.FLAG_ENABLE_MAGNIFICATION_MAGNIFY_NAV_BAR_AND_IME)
    public void testMagnifyNavBar_WhenImeIsMagnified_withMouse_shouldMagnify() {
        final ContentResolver cr = useFakeSettingsProvider();
        Settings.Secure.putInt(cr,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MAGNIFY_NAV_AND_IME, 1);
        mWm.mSettingsObserver.onChange(true /* selfChange */,
                Settings.Secure.getUriFor(
                        Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MAGNIFY_NAV_AND_IME));

        final WindowState navWindow = newWindowBuilder("navWindow", TYPE_NAVIGATION_BAR).build();
        navWindow.setHasSurface(true);

        when(mWm.mInputManager.getInputDevices()).thenReturn(new InputDevice[]{
                new InputDevice.Builder().setSources(InputDevice.SOURCE_MOUSE).build()
        });
        mWm.onInputDevicesChanged();

        assertTrue(mWm.isMagnifyImeEnabled());
        assertTrue(mWm.isMagnifyNavBarEnabled());
        assertTrue(navWindow.shouldMagnify());
    }

    @Test
    @EnableFlags(com.android.server.accessibility
            .Flags.FLAG_ENABLE_MAGNIFICATION_MAGNIFY_NAV_BAR_AND_IME)
    public void testMagnifyNavBar_WhenImeIsMagnified_withKeyboard_shouldMagnify() {
        final ContentResolver cr = useFakeSettingsProvider();
        Settings.Secure.putInt(cr,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MAGNIFY_NAV_AND_IME, 1);
        mWm.mSettingsObserver.onChange(true /* selfChange */,
                Settings.Secure.getUriFor(
                        Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MAGNIFY_NAV_AND_IME));

        final WindowState navWindow = newWindowBuilder("navWindow", TYPE_NAVIGATION_BAR).build();
        navWindow.setHasSurface(true);

        when(mWm.mInputManager.getInputDevices()).thenReturn(new InputDevice[]{
                new InputDevice.Builder().setSources(InputDevice.SOURCE_KEYBOARD).setKeyboardType(
                        InputDevice.KEYBOARD_TYPE_ALPHABETIC).build()
        });
        mWm.onInputDevicesChanged();

        assertTrue(mWm.isMagnifyImeEnabled());
        assertTrue(mWm.isMagnifyNavBarEnabled());
        assertTrue(navWindow.shouldMagnify());
    }

    @Test
    public void testCanBeImeLayeringTarget() {
        final WindowState appWindow = newWindowBuilder("appWindow", TYPE_APPLICATION).build();
        final WindowState imeWindow = newWindowBuilder("imeWindow", TYPE_INPUT_METHOD).build();

        // Setting FLAG_NOT_FOCUSABLE prevents the window from being an IME layering target.
        appWindow.mAttrs.flags |= FLAG_NOT_FOCUSABLE;
        imeWindow.mAttrs.flags |= FLAG_NOT_FOCUSABLE;

        // Make windows visible
        appWindow.setHasSurface(true);
        imeWindow.setHasSurface(true);

        // Windows with FLAG_NOT_FOCUSABLE can't be IME layering targets
        assertFalse(appWindow.canBeImeLayeringTarget());
        assertFalse(imeWindow.canBeImeLayeringTarget());

        // Add IME layering target flags
        appWindow.mAttrs.flags |= (FLAG_NOT_FOCUSABLE | FLAG_ALT_FOCUSABLE_IM);
        imeWindow.mAttrs.flags |= (FLAG_NOT_FOCUSABLE | FLAG_ALT_FOCUSABLE_IM);

        // Visible app window with flags can be IME layering target while an IME window can never
        // be an IME layering target regardless of its visibility or flags.
        assertTrue(appWindow.canBeImeLayeringTarget());
        assertFalse(imeWindow.canBeImeLayeringTarget());

        // Verify PINNED windows can't be IME layering target.
        int initialMode = appWindow.mActivityRecord.getWindowingMode();
        appWindow.mActivityRecord.setWindowingMode(WINDOWING_MODE_PINNED);
        assertFalse(appWindow.canBeImeLayeringTarget());
        appWindow.mActivityRecord.setWindowingMode(initialMode);

        // Verify that app window can still be IME layering target as long as it is visible (even if
        // it is going to become invisible).
        appWindow.mActivityRecord.setVisibleRequested(false);
        assertTrue(appWindow.canBeImeLayeringTarget());

        // Make windows invisible
        appWindow.hide(false /* doAnimation */, false /* requestAnim */);
        imeWindow.hide(false /* doAnimation */, false /* requestAnim */);

        // Invisible window can't be IME layering targets even if they have the right flags.
        assertFalse(appWindow.canBeImeLayeringTarget());
        assertFalse(imeWindow.canBeImeLayeringTarget());

        // Simulate the window is in split screen root task.
        final Task rootTask = createTask(mDisplayContent,
                WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD);
        rootTask.setFocusable(false);
        appWindow.mActivityRecord.reparent(rootTask, 0 /* position */, "test");

        // Make sure canBeImeLayeringTarget is false;
        assertFalse(appWindow.canBeImeLayeringTarget());
    }

    @Test
    public void testGetWindow() {
        final WindowState root = newWindowBuilder("root", TYPE_APPLICATION).build();
        final WindowState mediaChild = newWindowBuilder("mediaChild",
                TYPE_APPLICATION_MEDIA).setParent(root).build();
        final WindowState mediaOverlayChild = newWindowBuilder("mediaOverlayChild",
                TYPE_APPLICATION_MEDIA_OVERLAY).setParent(root).build();
        final WindowState attachedDialogChild = newWindowBuilder("attachedDialogChild",
                TYPE_APPLICATION_ATTACHED_DIALOG).setParent(root).build();
        final WindowState subPanelChild = newWindowBuilder("subPanelChild",
                TYPE_APPLICATION_SUB_PANEL).setParent(root).build();
        final WindowState aboveSubPanelChild = newWindowBuilder("aboveSubPanelChild",
                TYPE_APPLICATION_ABOVE_SUB_PANEL).setParent(root).build();

        final LinkedList<WindowState> windows = new LinkedList<>();

        root.getWindow(w -> {
            windows.addLast(w);
            return false;
        });

        // getWindow should have returned candidate windows in z-order.
        assertEquals(aboveSubPanelChild, windows.pollFirst());
        assertEquals(subPanelChild, windows.pollFirst());
        assertEquals(attachedDialogChild, windows.pollFirst());
        assertEquals(root, windows.pollFirst());
        assertEquals(mediaOverlayChild, windows.pollFirst());
        assertEquals(mediaChild, windows.pollFirst());
        assertTrue(windows.isEmpty());
    }

    @Test
    public void testDestroySurface() {
        final WindowState win = newWindowBuilder("win", TYPE_APPLICATION).build();
        win.mHasSurface = win.mAnimatingExit = true;
        win.mWinAnimator.mSurfaceControl = mock(SurfaceControl.class);
        win.onExitAnimationDone();

        assertFalse("Case 1 destroySurface no-op",
                win.destroySurface(false /* cleanupOnResume */, false /* appStopped */));
        assertTrue(win.mHasSurface);
        assertTrue(win.mDestroying);

        assertFalse("Case 2 destroySurface no-op",
                win.destroySurface(true /* cleanupOnResume */, false /* appStopped */));
        assertTrue(win.mHasSurface);
        assertTrue(win.mDestroying);

        assertTrue("Case 3 destroySurface destroys surface",
                win.destroySurface(false /* cleanupOnResume */, true /* appStopped */));
        assertFalse(win.mDestroying);
        assertFalse(win.mHasSurface);
    }

    @Test
    public void testPrepareWindowToDisplayDuringRelayout() {
        // Call prepareWindowToDisplayDuringRelayout for a window without FLAG_TURN_SCREEN_ON before
        // calling setCurrentLaunchCanTurnScreenOn for windows with flag in the same activity.
        final ActivityRecord activity = createActivityRecord(mDisplayContent);
        final WindowState first = newWindowBuilder("first", TYPE_APPLICATION).setWindowToken(
                activity).build();
        final WindowState second = newWindowBuilder("second", TYPE_APPLICATION).setWindowToken(
                activity).build();

        testPrepareWindowToDisplayDuringRelayout(first, false /* expectedWakeupCalled */,
                true /* expectedCurrentLaunchCanTurnScreenOn */);
        testPrepareWindowToDisplayDuringRelayout(second, false /* expectedWakeupCalled */,
                true /* expectedCurrentLaunchCanTurnScreenOn */);

        // Call prepareWindowToDisplayDuringRelayout for two windows from the same activity, one of
        // which has FLAG_TURN_SCREEN_ON. The first processed one should trigger the wakeup.
        second.mAttrs.flags |= WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
        testPrepareWindowToDisplayDuringRelayout(first, true /* expectedWakeupCalled */,
                false /* expectedCurrentLaunchCanTurnScreenOn */);
        testPrepareWindowToDisplayDuringRelayout(second, false /* expectedWakeupCalled */,
                false /* expectedCurrentLaunchCanTurnScreenOn */);

        // Call prepareWindowToDisplayDuringRelayout for two window that have FLAG_TURN_SCREEN_ON
        // from the same activity. Only one should trigger the wakeup.
        activity.setCurrentLaunchCanTurnScreenOn(true);
        first.mAttrs.flags |= WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
        second.mAttrs.flags |= WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;

        testPrepareWindowToDisplayDuringRelayout(first, true /* expectedWakeupCalled */,
                false /* expectedCurrentLaunchCanTurnScreenOn */);
        testPrepareWindowToDisplayDuringRelayout(second, false /* expectedWakeupCalled */,
                false /* expectedCurrentLaunchCanTurnScreenOn */);

        // Without window flags, the state of ActivityRecord.canTurnScreenOn should still be able to
        // turn on the screen.
        activity.setCurrentLaunchCanTurnScreenOn(true);
        first.mAttrs.flags &= ~WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
        doReturn(true).when(activity).canTurnScreenOn();

        testPrepareWindowToDisplayDuringRelayout(first, true /* expectedWakeupCalled */,
                false /* expectedCurrentLaunchCanTurnScreenOn */);

        // Call prepareWindowToDisplayDuringRelayout for a windows that are not children of an
        // activity. Both windows have the FLAG_TURNS_SCREEN_ON so both should call wakeup
        final WindowToken windowToken = createTestWindowToken(FIRST_SUB_WINDOW, mDisplayContent);
        final WindowState firstWindow = newWindowBuilder("firstWindow",
                TYPE_APPLICATION).setWindowToken(windowToken).build();
        final WindowState secondWindow = newWindowBuilder("secondWindow",
                TYPE_APPLICATION).setWindowToken(windowToken).build();
        firstWindow.mAttrs.flags |= WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
        secondWindow.mAttrs.flags |= WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;

        final var powerManager = mWm.mPowerManager;
        clearInvocations(powerManager);
        firstWindow.prepareWindowToDisplayDuringRelayout(false /*wasVisible*/);
        verify(powerManager).wakeUp(anyLong(), anyInt(), anyString(), anyInt());

        clearInvocations(powerManager);
        secondWindow.prepareWindowToDisplayDuringRelayout(false /*wasVisible*/);
        verify(powerManager).wakeUp(anyLong(), anyInt(), anyString(), anyInt());
    }

    private void testPrepareWindowToDisplayDuringRelayout(WindowState appWindow,
            boolean expectedWakeupCalled, boolean expectedCurrentLaunchCanTurnScreenOn) {
        final var powerManager = mWm.mPowerManager;
        clearInvocations(powerManager);
        appWindow.prepareWindowToDisplayDuringRelayout(false /* wasVisible */);

        if (expectedWakeupCalled) {
            verify(powerManager).wakeUp(anyLong(), anyInt(), anyString(), anyInt());
        } else {
            verify(powerManager, never()).wakeUp(anyLong(), anyInt(), anyString(), anyInt());
        }
        // If wakeup is expected to be called, the currentLaunchCanTurnScreenOn should be false
        // because the state will be consumed.
        assertThat(appWindow.mActivityRecord.currentLaunchCanTurnScreenOn(),
                is(expectedCurrentLaunchCanTurnScreenOn));
    }

    @Test
    public void testCanAffectSystemUiFlags() {
        final WindowState app = newWindowBuilder("app", TYPE_APPLICATION).build();
        app.mActivityRecord.setVisible(true);
        assertTrue(app.canAffectSystemUiFlags());
        app.mActivityRecord.setVisible(false);
        assertFalse(app.canAffectSystemUiFlags());
        app.mActivityRecord.setVisible(true);
        app.mAttrs.alpha = 0.0f;
        assertFalse(app.canAffectSystemUiFlags());
    }

    @Test
    public void testCanAffectSystemUiFlags_starting() {
        final WindowState app = newWindowBuilder("app", TYPE_APPLICATION_STARTING).build();
        app.mActivityRecord.setVisible(true);
        app.mStartingData = new SnapshotStartingData(mWm, null, 0);
        assertFalse(app.canAffectSystemUiFlags());
        app.mStartingData = new SplashScreenStartingData(mWm, 0, 0);
        assertTrue(app.canAffectSystemUiFlags());
    }

    @Test
    public void testCanAffectSystemUiFlags_disallow() {
        final WindowState app = newWindowBuilder("app", TYPE_APPLICATION).build();
        app.mActivityRecord.setVisible(true);
        assertTrue(app.canAffectSystemUiFlags());
        app.getTask().setCanAffectSystemUiFlags(false);
        assertFalse(app.canAffectSystemUiFlags());
    }

    @SetupWindows(addWindows = { W_ACTIVITY, W_STATUS_BAR })
    @Test
    public void testVisibleWithInsetsProvider() {
        final WindowState statusBar = mStatusBarWindow;
        final WindowState app = mAppWindow;
        statusBar.mHasSurface = true;
        assertTrue(statusBar.isVisible());
        final int statusBarId = InsetsSource.createId(null, 0, statusBars());
        mDisplayContent.getInsetsStateController()
                .getOrCreateSourceProvider(statusBarId, statusBars())
                .setWindow(statusBar, null /* frameProvider */, null /* imeFrameProvider */);
        mDisplayContent.getInsetsStateController().onBarControlTargetChanged(
                app, null /* fakeTopControlling */, app, null /* fakeNavControlling */);
        app.setRequestedVisibleTypes(0, statusBars());
        mDisplayContent.getInsetsStateController()
                .getOrCreateSourceProvider(statusBarId, statusBars())
                .updateClientVisibility(app, null /* statsToken */);
        waitUntilHandlersIdle();
        assertFalse(statusBar.isVisible());
    }

    /**
     * Verifies that the InsetsSourceProvider frame cannot be updated by WindowState before
     * relayout is called.
     */
    @SetupWindows(addWindows = { W_STATUS_BAR })
    @Test
    public void testUpdateSourceFrameBeforeRelayout() {
        final WindowState statusBar = mStatusBarWindow;
        statusBar.mHasSurface = true;
        assertTrue(statusBar.isVisible());
        final int statusBarId = InsetsSource.createId(null, 0, statusBars());
        final var statusBarProvider = mDisplayContent.getInsetsStateController()
                .getOrCreateSourceProvider(statusBarId, statusBars());
        statusBarProvider.setWindow(statusBar, null /* frameProvider */,
                null /* imeFrameProvider */);

        statusBar.updateSourceFrame(new Rect(0, 0, 500, 200));
        assertTrue("InsetsSourceProvider frame should not be updated before relayout",
                statusBarProvider.getSourceFrame().isEmpty());

        makeWindowVisible(statusBar);
        statusBar.updateSourceFrame(new Rect(0, 0, 500, 100));
        assertEquals("InsetsSourceProvider frame should be updated after relayout",
                new Rect(0, 0, 500, 100), statusBarProvider.getSourceFrame());
    }

    @Test
    public void testIsSelfOrAncestorWindowAnimating() {
        final WindowState root = newWindowBuilder("root", TYPE_APPLICATION).build();
        final WindowState child1 = newWindowBuilder("child1", FIRST_SUB_WINDOW).setParent(
                root).build();
        final WindowState child2 = newWindowBuilder("child2", FIRST_SUB_WINDOW).setParent(
                child1).build();
        assertFalse(child2.isSelfOrAncestorWindowAnimatingExit());
        child2.mAnimatingExit = true;
        assertTrue(child2.isSelfOrAncestorWindowAnimatingExit());
        child2.mAnimatingExit = false;
        root.mAnimatingExit = true;
        assertTrue(child2.isSelfOrAncestorWindowAnimatingExit());
    }

    @Test
    public void testOnExitAnimationDone() {
        final WindowState parent = newWindowBuilder("parent", TYPE_APPLICATION).build();
        final WindowState child = newWindowBuilder("child", TYPE_APPLICATION_PANEL).setParent(
                parent).build();
        final SurfaceControl.Transaction t = parent.getPendingTransaction();
        child.startAnimation(t, mock(AnimationAdapter.class), false /* hidden */,
                SurfaceAnimator.ANIMATION_TYPE_WINDOW_ANIMATION);
        parent.mAnimatingExit = parent.mRemoveOnExit = parent.mWindowRemovalAllowed = true;
        child.mAnimatingExit = child.mRemoveOnExit = child.mWindowRemovalAllowed = true;
        final int[] numRemovals = new int[2];
        parent.registerWindowContainerListener(new WindowContainerListener() {
            @Override
            public void onRemoved() {
                numRemovals[0]++;
            }
        });
        child.registerWindowContainerListener(new WindowContainerListener() {
            @Override
            public void onRemoved() {
                numRemovals[1]++;
            }
        });
        spyOn(parent);
        // parent onExitAnimationDone
        //   -> child onExitAnimationDone() -> no-op because isAnimating()
        //   -> parent destroySurface()
        //     -> parent removeImmediately() because mDestroying+mRemoveOnExit
        //       -> child removeImmediately() -> cancelAnimation()
        //       -> child onExitAnimationDone()
        //         -> child destroySurface() because animation is canceled
        //           -> child removeImmediately() -> no-op because mRemoved
        parent.onExitAnimationDone();
        // There must be no additional destroySurface() of parent from its child.
        verify(parent, atMost(1)).destroySurface(anyBoolean(), anyBoolean());
        assertEquals(1, numRemovals[0]);
        assertEquals(1, numRemovals[1]);
    }

    @Test
    public void testLayoutSeqResetOnReparent() {
        final WindowState app = newWindowBuilder("app", TYPE_APPLICATION).build();
        app.mLayoutSeq = 1;
        mDisplayContent.mLayoutSeq = 1;

        DisplayContent newDisplay = createNewDisplay();

        app.onDisplayChanged(newDisplay);

        assertThat(app.mLayoutSeq, not(is(mDisplayContent.mLayoutSeq)));
    }

    @Test
    public void testDisplayIdUpdatedOnReparent() {
        final WindowState app = newWindowBuilder("app", TYPE_APPLICATION).build();
        // fake a different display
        app.mInputWindowHandle.setDisplayId(mDisplayContent.getDisplayId() + 1);
        app.onDisplayChanged(mDisplayContent);

        assertThat(app.mInputWindowHandle.getDisplayId(), is(mDisplayContent.getDisplayId()));
        assertThat(app.getDisplayId(), is(mDisplayContent.getDisplayId()));
    }

    @Test
    public void testApplyWithNextDraw() {
        final WindowState win = newWindowBuilder("app", TYPE_APPLICATION_OVERLAY).build();
        final SurfaceControl.Transaction[] handledT = { null };
        // The normal case that the draw transaction is applied with finishing drawing.
        win.applyWithNextDraw(t -> handledT[0] = t);
        assertTrue(win.syncNextBuffer());
        final SurfaceControl.Transaction drawT = new StubTransaction();
        final SurfaceControl.Transaction currT = win.getSyncTransaction();
        clearInvocations(currT);
        win.mWinAnimator.mLastHidden = true;
        assertTrue(win.finishDrawing(drawT, Integer.MAX_VALUE));
        // The draw transaction should be merged to current transaction even if the state is hidden.
        verify(currT).merge(eq(drawT));
        assertEquals(drawT, handledT[0]);
        assertFalse(win.syncNextBuffer());

        // If the window is gone before reporting drawn, the sync state should be cleared.
        win.applyWithNextDraw(t -> handledT[0] = t);
        win.destroySurfaceUnchecked();
        assertFalse(win.syncNextBuffer());
        assertNotEquals(drawT, handledT[0]);
    }

    @Test
    public void testVisibilityChangeSwitchUser() {
        final WindowState window = newWindowBuilder("app", TYPE_APPLICATION).build();
        window.mHasSurface = true;
        spyOn(window);
        doReturn(false).when(window).showForAllUsers();

        mWm.mCurrentUserId = 1;
        window.switchUser(mWm.mCurrentUserId);
        assertFalse(window.isVisible());
        assertFalse(window.isVisibleByPolicy());

        mWm.mCurrentUserId = 0;
        window.switchUser(mWm.mCurrentUserId);
        assertTrue(window.isVisible());
        assertTrue(window.isVisibleByPolicy());
    }

    @Test
    @EnableFlags(com.android.server.accessibility
            .Flags.FLAG_ENABLE_MAGNIFICATION_MAGNIFY_NAV_BAR_AND_IME)
    @DisableFlags(Flags.FLAG_APPLY_DESK_ACTIVATION_ON_USER_SWITCH)
    public void testSwitchUser_settingValueIsDisabled_shouldNotMagnify_deskUserSwitchDisabled() {
        final ContentResolver cr = useFakeSettingsProvider();
        Settings.Secure.putIntForUser(cr,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MAGNIFY_NAV_AND_IME, 0, 1);

        mWm.setCurrentUser(1);

        assertFalse(mWm.isMagnifyImeEnabled());
    }

    @Test
    @EnableFlags(com.android.server.accessibility
            .Flags.FLAG_ENABLE_MAGNIFICATION_MAGNIFY_NAV_BAR_AND_IME)
    @DisableFlags(Flags.FLAG_APPLY_DESK_ACTIVATION_ON_USER_SWITCH)
    public void testSwitchUser_settingValueIsEnabled_shouldMagnify_deskUserSwitchDisabled() {
        final ContentResolver cr = useFakeSettingsProvider();
        Settings.Secure.putIntForUser(cr,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MAGNIFY_NAV_AND_IME, 1, 2);

        mWm.setCurrentUser(2);

        assertTrue(mWm.isMagnifyImeEnabled());
    }

    @Test
    @EnableFlags({com.android.server.accessibility
            .Flags.FLAG_ENABLE_MAGNIFICATION_MAGNIFY_NAV_BAR_AND_IME,
            Flags.FLAG_APPLY_DESK_ACTIVATION_ON_USER_SWITCH})
    public void testSwitchUser_settingValueIsDisabled_shouldNotMagnify() {
        final ContentResolver cr = useFakeSettingsProvider();
        Settings.Secure.putIntForUser(cr,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MAGNIFY_NAV_AND_IME, 0, 1);

        mWm.prepareUserStart(1);

        assertFalse(mWm.isMagnifyImeEnabled());
    }

    @Test
    @EnableFlags({com.android.server.accessibility
            .Flags.FLAG_ENABLE_MAGNIFICATION_MAGNIFY_NAV_BAR_AND_IME,
            Flags.FLAG_APPLY_DESK_ACTIVATION_ON_USER_SWITCH})
    public void testSwitchUser_settingValueIsEnabled_shouldMagnify() {
        final ContentResolver cr = useFakeSettingsProvider();
        Settings.Secure.putIntForUser(cr,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MAGNIFY_NAV_AND_IME, 1, 2);

        mWm.prepareUserStart(2);

        assertTrue(mWm.isMagnifyImeEnabled());
    }

    @Test
    public void testCompatOverrideScale() {
        final float overrideScale = 2; // 0.5x on client side.
        final CompatModePackages cmp = mWm.mAtmService.mCompatModePackages;
        spyOn(cmp);
        doReturn(overrideScale).when(cmp).getCompatScale(anyString(), anyInt());
        final WindowState w = newWindowBuilder("win", TYPE_APPLICATION_OVERLAY).build();
        final WindowState child = newWindowBuilder("child", TYPE_APPLICATION_PANEL).setParent(
                w).build();

        assertTrue(w.hasCompatScale());
        assertTrue(child.hasCompatScale());

        makeWindowVisible(w, child);
        w.setRequestedSize(100, 200);
        child.setRequestedSize(50, 100);
        child.mAttrs.width = child.mAttrs.height = 0;
        w.mAttrs.x = w.mAttrs.y = 100;
        w.mAttrs.width = w.mAttrs.height = WindowManager.LayoutParams.WRAP_CONTENT;
        w.mAttrs.gravity = Gravity.TOP | Gravity.LEFT;
        child.mAttrs.gravity = Gravity.CENTER;
        DisplayContentTests.performLayout(mDisplayContent);
        final Rect parentFrame = w.getFrame();
        final Rect childFrame = child.getFrame();

        // Frame on screen = 200x400 (200, 200 - 400, 600). Compat frame on client = 100x200.
        final Rect unscaledCompatFrame = new Rect(w.getWindowFrames().mCompatFrame);
        unscaledCompatFrame.scale(overrideScale);
        assertEquals(parentFrame, unscaledCompatFrame);

        // Frame on screen = 100x200 (250, 300 - 350, 500). Compat frame on client = 50x100.
        unscaledCompatFrame.set(child.getWindowFrames().mCompatFrame);
        unscaledCompatFrame.scale(overrideScale);
        assertEquals(childFrame, unscaledCompatFrame);

        // The position of child is relative to parent. So the local coordinates should be scaled.
        final Point expectedChildPos = new Point(
                (int) ((childFrame.left - parentFrame.left) / overrideScale),
                (int) ((childFrame.top - parentFrame.top) / overrideScale));
        final Point childPos = new Point();
        child.transformFrameToSurfacePosition(childFrame.left, childFrame.top, childPos);
        assertEquals(expectedChildPos, childPos);

        // Surface should apply the scale.
        final SurfaceControl.Transaction t = w.getPendingTransaction();
        w.prepareSurfaces();
        verify(t).setMatrix(w.mSurfaceControl, overrideScale, 0, 0, overrideScale);
        // Child surface inherits parent's scale, so it doesn't need to scale.
        verify(t, never()).setMatrix(any(), anyInt(), anyInt(), anyInt(), anyInt());

        // According to "dp * density / 160 = px", density is scaled and the size in dp is the same.
        final Configuration winConfig = w.getConfiguration();
        final Configuration clientConfig = new Configuration(w.getConfiguration());
        CompatibilityInfo.scaleConfiguration(w.mInvGlobalScale, clientConfig);

        assertEquals(winConfig.screenWidthDp, clientConfig.screenWidthDp);
        assertEquals(winConfig.screenHeightDp, clientConfig.screenHeightDp);
        assertEquals(winConfig.smallestScreenWidthDp, clientConfig.smallestScreenWidthDp);
        assertEquals(winConfig.densityDpi, (int) (clientConfig.densityDpi * overrideScale));

        final Rect unscaledClientBounds = new Rect(clientConfig.windowConfiguration.getBounds());
        unscaledClientBounds.scale(overrideScale);
        assertEquals(w.getWindowConfiguration().getBounds(), unscaledClientBounds);

        // Child window without scale (e.g. different app) should apply inverse scale of parent.
        doReturn(1f).when(cmp).getCompatScale(anyString(), anyInt());
        final WindowState child2 = newWindowBuilder("child2", TYPE_APPLICATION_SUB_PANEL).setParent(
                w).build();
        makeWindowVisible(w, child2);
        clearInvocations(t);
        child2.prepareSurfaces();
        verify(t).setMatrix(child2.mSurfaceControl, w.mInvGlobalScale, 0, 0, w.mInvGlobalScale);
    }

    @SetupWindows(addWindows = { W_ABOVE_ACTIVITY, W_NOTIFICATION_SHADE })
    @Test
    public void testRequestDrawIfNeeded() {
        final WindowState startingApp = newWindowBuilder("startingApp",
                TYPE_BASE_APPLICATION).build();
        final WindowState startingWindow = newWindowBuilder("starting",
                TYPE_APPLICATION_STARTING).setWindowToken(startingApp.mToken).build();
        startingApp.mActivityRecord.mStartingWindow = startingWindow;
        final WindowState keyguardHostWindow = mNotificationShadeWindow;
        final WindowState allDrawnApp = mAppWindow;
        allDrawnApp.mActivityRecord.allDrawn = true;

        // The waiting list is used to ensure the content is ready when turning on screen.
        final List<WindowState> outWaitingForDrawn = mDisplayContent.mWaitingForDrawn;
        final List<WindowState> visibleWindows = Arrays.asList(mChildAppWindowAbove,
                keyguardHostWindow, allDrawnApp, startingApp, startingWindow);
        visibleWindows.forEach(w -> {
            w.mHasSurface = true;
            w.requestDrawIfNeeded(outWaitingForDrawn);
        });

        // Keyguard host window should be always contained. The drawn app or app with starting
        // window are unnecessary to draw.
        assertEquals(Arrays.asList(keyguardHostWindow, startingWindow), outWaitingForDrawn);

        // No need to wait for a window of invisible activity even if the window has surface.
        final WindowState invisibleApp = mAppWindow;
        invisibleApp.mActivityRecord.setVisibleRequested(false);
        invisibleApp.mActivityRecord.allDrawn = false;
        outWaitingForDrawn.clear();
        invisibleApp.requestDrawIfNeeded(outWaitingForDrawn);
        assertTrue(outWaitingForDrawn.isEmpty());

        // Drawn state should not be changed for insets change if the window is not visible.
        startingApp.mActivityRecord.setVisibleRequested(false);
        makeWindowVisibleAndDrawn(startingApp);
        startingApp.getConfiguration().orientation = 0; // Reset to be the same as last reported.
        startingApp.getWindowFrames().setInsetsChanged(true);
        startingApp.updateResizingWindowIfNeeded();
        assertTrue(mWm.mResizingWindows.contains(startingApp));
        assertTrue(startingApp.isDrawn());
    }

    @SetupWindows(addWindows = W_ABOVE_ACTIVITY)
    @Test
    public void testReportResizedWithRemoteException() {
        final WindowState win = mChildAppWindowAbove;
        makeWindowVisible(win, win.getParentWindow());
        win.mLayoutSeq = win.getDisplayContent().mLayoutSeq;
        win.updateResizingWindowIfNeeded();

        assertThat(mWm.mResizingWindows).contains(win);

        mWm.mResizingWindows.remove(win);
        spyOn(win.mClient);
        try {
            doThrow(new RemoteException("test")).when(win.mClient).resized(any() /* layout */,
                    anyBoolean() /* reportDraw */, anyBoolean() /* forceLayout */,
                    anyInt() /* displayId */, anyBoolean() /* withBuffers */,
                    anyBoolean() /* dragResizing */);
        } catch (RemoteException ignored) {
        }
        win.reportResized();
        win.updateResizingWindowIfNeeded();

        // Even "resized" throws remote exception, it is still considered as reported. So the window
        // shouldn't be resized again (which may block unfreeze in real case).
        assertThat(mWm.mResizingWindows).doesNotContain(win);
    }

    @Test
    public void testRequestResizeForSync() {
        final WindowState win = newWindowBuilder("window", TYPE_APPLICATION).build();
        makeWindowVisible(win);
        makeLastConfigReportedToClient(win, true /* visible */);
        win.mLayoutSeq = win.getDisplayContent().mLayoutSeq;
        win.reportResized();
        win.updateResizingWindowIfNeeded();
        assertThat(mWm.mResizingWindows).doesNotContain(win);

        // Check that the window is in resizing if using blast sync.
        final BLASTSyncEngine.SyncGroup syncGroup = mock(BLASTSyncEngine.SyncGroup.class);
        syncGroup.mSyncMethod = BLASTSyncEngine.METHOD_BLAST;
        win.mSyncGroup = syncGroup;
        win.reportResized();
        win.prepareSync();
        assertEquals(SYNC_STATE_WAITING_FOR_DRAW, win.mSyncState);
        win.updateResizingWindowIfNeeded();
        assertThat(mWm.mResizingWindows).contains(win);

        // Don't re-add the window again if it's been reported to the client and still waiting on
        // the client draw for blast sync.
        win.reportResized();
        mWm.mResizingWindows.remove(win);
        win.updateResizingWindowIfNeeded();
        assertThat(mWm.mResizingWindows).doesNotContain(win);

        if (!mWm.mAlwaysSeqId) {
            // Non blast sync doesn't require to force resizing, because it won't use syncSeqId.
            // And if the window is already drawn, it can report sync finish immediately so that the
            // sync group won't be blocked.
            win.finishSync(mTransaction, syncGroup, false /* cancel */);
            syncGroup.mSyncMethod = BLASTSyncEngine.METHOD_NONE;
            win.mSyncGroup = syncGroup;
            win.mWinAnimator.mDrawState = WindowStateAnimator.HAS_DRAWN;
            win.prepareSync();
            assertEquals(SYNC_STATE_WAITING_FOR_DRAW, win.mSyncState);
            win.updateResizingWindowIfNeeded();
            assertThat(mWm.mResizingWindows).doesNotContain(win);
            assertTrue(win.isSyncFinished(syncGroup));
            assertEquals(WindowContainer.SYNC_STATE_READY, win.mSyncState);
        }
    }

    @Test
    public void testSyncMethodBlastOverride() {
        assumeTrue(mWm.mAlwaysSeqId);
        final WindowState win = newWindowBuilder("window", TYPE_APPLICATION).build();
        win.mSession.onWindowAdded(win);
        makeWindowVisible(win);
        makeLastConfigReportedToClient(win, true /* visible */);
        win.mLayoutSeq = win.getDisplayContent().mLayoutSeq;
        win.reportResized();
        win.updateResizingWindowIfNeeded();
        assertThat(mWm.mResizingWindows).doesNotContain(win);

        // Hack so that lifecyclemanager holds onto pending for us to inspect
        mWm.mWindowPlacerLocked.deferLayout();

        // Start a non-BLAST sync (pretend like the config changed)
        final BLASTSyncEngine.SyncGroup syncGroup = mock(BLASTSyncEngine.SyncGroup.class);
        win.mSyncGroup = syncGroup;
        win.prepareSync();
        assertEquals(SYNC_STATE_WAITING_FOR_DRAW, win.mSyncState);
        win.setLastConfigReportedToClientForTest(false);
        win.updateResizingWindowIfNeeded();
        assertThat(mWm.mResizingWindows).contains(win);
        // Override this window to BLAST (seamless rotation does this)
        win.useBlastForNextSync();
        win.reportResized();

        final ClientTransaction ct = mAtm.getLifecycleManager().mPendingTransactions.get(
                win.getProcess().getThread().asBinder());
        WindowStateResizeItem ri = (WindowStateResizeItem) ct.getTransactionItems().getLast();
        assertTrue(ri.getSyncWithBuffersForTest());
    }

    @Test
    public void testEmbeddedActivityResizing_clearAllDrawn() {
        final TaskFragmentOrganizer organizer = new TaskFragmentOrganizer(Runnable::run);
        registerTaskFragmentOrganizer(
                ITaskFragmentOrganizer.Stub.asInterface(organizer.getOrganizerToken().asBinder()));
        final Task task = createTask(mDisplayContent);
        final TaskFragment embeddedTf = createTaskFragmentWithEmbeddedActivity(task, organizer);
        final ActivityRecord embeddedActivity = embeddedTf.getTopMostActivity();
        final WindowState win = newWindowBuilder("App window", TYPE_APPLICATION).setWindowToken(
                embeddedActivity).build();
        doReturn(true).when(embeddedActivity).isVisible();
        embeddedActivity.setVisibleRequested(true);
        makeWindowVisible(win);
        win.mLayoutSeq = win.getDisplayContent().mLayoutSeq;
        // Set the bounds twice:
        // 1. To make sure there is no orientation change after #reportResized, which can also cause
        // #clearAllDrawn.
        // 2. Make #isLastConfigReportedToClient to be false after #reportResized, so it can process
        // to check if we need redraw.
        embeddedTf.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        embeddedTf.setBounds(0, 0, 1000, 2000);
        win.reportResized();
        embeddedTf.setBounds(500, 0, 1000, 2000);

        // Clear all drawn when the window config of embedded TaskFragment is changed.
        win.updateResizingWindowIfNeeded();
        verify(embeddedActivity).clearAllDrawn();
    }

    @Test
    public void testCantReceiveTouchWhenAppTokenHiddenRequested() {
        final WindowState win0 = newWindowBuilder("win0", TYPE_APPLICATION).build();
        win0.mActivityRecord.setVisibleRequested(false);
        assertFalse(win0.canReceiveTouchInput());
    }

    @Test
    public void testCantReceiveTouchWhenNotFocusable() {
        final WindowState win0 = newWindowBuilder("win0", TYPE_APPLICATION).build();
        final Task rootTask = win0.mActivityRecord.getRootTask();
        spyOn(rootTask);
        when(rootTask.shouldIgnoreInput()).thenReturn(true);
        assertFalse(win0.canReceiveTouchInput());
    }

    private boolean testFlag(int flags, int test) {
        return (flags & test) == test;
    }

    @Test
    public void testUpdateInputWindowHandle() {
        final WindowState win = newWindowBuilder("win", TYPE_APPLICATION).build();
        win.mAttrs.inputFeatures = WindowManager.LayoutParams.INPUT_FEATURE_DISABLE_USER_ACTIVITY;
        win.mAttrs.flags = FLAG_WATCH_OUTSIDE_TOUCH | FLAG_SPLIT_TOUCH;
        final InputWindowHandle handle = new InputWindowHandle(
                win.mInputWindowHandle.getInputApplicationHandle(), win.getDisplayId());
        final InputWindowHandleWrapper handleWrapper = new InputWindowHandleWrapper(handle);
        final IBinder inputChannelToken = mock(IBinder.class);
        win.mInputChannelToken = inputChannelToken;

        mDisplayContent.getInputMonitor().populateInputWindowHandle(handleWrapper, win);

        assertTrue(handleWrapper.isChanged());
        assertTrue(testFlag(handle.inputConfig, InputConfig.WATCH_OUTSIDE_TOUCH));
        assertTrue(testFlag(handle.inputConfig, InputConfig.DISABLE_USER_ACTIVITY));
        // The window of standard resizable task should not use surface crop as touchable region.
        assertFalse(handle.replaceTouchableRegionWithCrop);
        assertEquals(inputChannelToken, handle.token);
        assertEquals(win.mActivityRecord.getInputApplicationHandle(false /* update */),
                handle.inputApplicationHandle);

        final SurfaceControl sc = mock(SurfaceControl.class);
        final SurfaceControl.Transaction transaction = mSystemServicesTestRule.mTransaction;
        InputMonitor.setInputWindowInfoIfNeeded(transaction, sc, handleWrapper);

        // The fields of input window handle are changed, so it must set input window info
        // successfully. And then the changed flag should be reset.
        verify(transaction).setInputWindowInfo(eq(sc), eq(handle));
        assertFalse(handleWrapper.isChanged());
        // Populate the same states again, the handle should not detect change.
        mDisplayContent.getInputMonitor().populateInputWindowHandle(handleWrapper, win);
        assertFalse(handleWrapper.isChanged());

        // Apply the no change handle, the invocation of setInputWindowInfo should be skipped.
        clearInvocations(transaction);
        InputMonitor.setInputWindowInfoIfNeeded(transaction, sc, handleWrapper);
        verify(transaction, never()).setInputWindowInfo(any(), any());

        // The rotated bounds have higher priority as the touchable region.
        final Rect rotatedBounds = new Rect(0, 0, 123, 456);
        doReturn(rotatedBounds).when(win.mToken).getFixedRotationTransformDisplayBounds();
        mDisplayContent.getInputMonitor().populateInputWindowHandle(handleWrapper, win);
        assertEquals(rotatedBounds, handle.touchableRegion.getBounds());

        // Populate as an overlay to disable the input of window.
        InputMonitor.populateOverlayInputInfo(handleWrapper);
        // The overlay attributes should be set.
        assertTrue(handleWrapper.isChanged());
        assertFalse(handleWrapper.isFocusable());
        assertNull(handle.token);
        assertEquals(0L, handle.dispatchingTimeoutMillis);
        assertTrue(testFlag(handle.inputConfig, InputConfig.NO_INPUT_CHANNEL));
    }

    @DisableFlags(Flags.FLAG_SCROLLING_FROM_LETTERBOX)
    @Test
    public void testTouchRegionUsesLetterboxBoundsIfTransformedBoundsAndLetterboxScrolling() {
        final WindowState win = newWindowBuilder("win", TYPE_APPLICATION).build();

        // Transformed bounds used for size of touchable region if letterbox inner bounds are empty.
        final Rect transformedBounds = new Rect(0, 0, 300, 500);
        doReturn(transformedBounds).when(win.mToken).getFixedRotationTransformDisplayBounds();

        // Otherwise, touchable region should match letterbox inner bounds.
        final Rect letterboxInnerBounds = new Rect(30, 0, 270, 500);
        doAnswer(invocation -> {
            Rect rect = invocation.getArgument(0);
            rect.set(letterboxInnerBounds);
            return null;
        }).when(win.mActivityRecord).getLetterboxInnerBounds(any());

        Region outRegion = new Region();
        win.getSurfaceTouchableRegion(outRegion, win.mAttrs);

        // Because scrollingFromLetterbox flag is disabled and letterboxInnerBounds is not empty,
        // touchable region should match letterboxInnerBounds always.
        assertEquals(letterboxInnerBounds, outRegion.getBounds());
    }

    @DisableFlags(Flags.FLAG_SCROLLING_FROM_LETTERBOX)
    @Test
    public void testTouchRegionUsesLetterboxBoundsIfNullTransformedBoundsAndLetterboxScrolling() {
        final WindowState win = newWindowBuilder("win", TYPE_APPLICATION).build();

        // Fragment bounds used for size of touchable region if letterbox inner bounds are empty
        // and Transform bounds are null.
        doReturn(null).when(win.mToken).getFixedRotationTransformDisplayBounds();
        final Rect fragmentBounds = new Rect(0, 0, 300, 500);
        final TaskFragment taskFragment = win.mActivityRecord.getTaskFragment();
        doAnswer(invocation -> {
            Rect rect = invocation.getArgument(0);
            rect.set(fragmentBounds);
            return null;
        }).when(taskFragment).getDimBounds(any());

        // Otherwise, touchable region should match letterbox inner bounds.
        final Rect letterboxInnerBounds = new Rect(30, 0, 270, 500);
        doAnswer(invocation -> {
            Rect rect = invocation.getArgument(0);
            rect.set(letterboxInnerBounds);
            return null;
        }).when(win.mActivityRecord).getLetterboxInnerBounds(any());

        Region outRegion = new Region();
        win.getSurfaceTouchableRegion(outRegion, win.mAttrs);

        // Because scrollingFromLetterbox flag is disabled and letterboxInnerBounds is not empty,
        // touchable region should match letterboxInnerBounds always.
        assertEquals(letterboxInnerBounds, outRegion.getBounds());
    }

    @EnableFlags(Flags.FLAG_SCROLLING_FROM_LETTERBOX)
    @Test
    public void testTouchRegionUsesTransformedBoundsIfLetterboxScrolling() {
        final WindowState win = newWindowBuilder("win", TYPE_APPLICATION).build();

        // Transformed bounds used for size of touchable region if letterbox inner bounds are empty.
        final Rect transformedBounds = new Rect(0, 0, 300, 500);
        doReturn(transformedBounds).when(win.mToken).getFixedRotationTransformDisplayBounds();

        // Otherwise, touchable region should match letterbox inner bounds.
        final Rect letterboxInnerBounds = new Rect(30, 0, 270, 500);
        doAnswer(invocation -> {
            Rect rect = invocation.getArgument(0);
            rect.set(letterboxInnerBounds);
            return null;
        }).when(win.mActivityRecord).getLetterboxInnerBounds(any());

        Region outRegion = new Region();
        win.getSurfaceTouchableRegion(outRegion, win.mAttrs);

        // Because scrollingFromLetterbox flag is enabled and transformedBounds are non-null,
        // touchable region should match transformedBounds.
        assertEquals(transformedBounds, outRegion.getBounds());
    }

    @Test
    public void testHasActiveVisibleWindow() {
        final int uid = ActivityBuilder.DEFAULT_FAKE_UID;

        final WindowState app = newWindowBuilder("app", TYPE_APPLICATION).setOwnerId(uid).build();
        app.mActivityRecord.setVisible(false);
        app.mActivityRecord.setVisibility(false);
        assertFalse(mAtm.hasActiveVisibleWindow(uid));

        app.mActivityRecord.setVisibility(true);
        assertTrue(mAtm.hasActiveVisibleWindow(uid));

        // Make the activity invisible and add a visible toast. The uid should have no active
        // visible window because toast can be misused by legacy app to bypass background check.
        app.mActivityRecord.setVisibility(false);
        final WindowState overlay = newWindowBuilder("overlay",
                TYPE_APPLICATION_OVERLAY).setOwnerId(uid).build();
        final WindowState toast = newWindowBuilder("toast", TYPE_TOAST).setWindowToken(
                app.mToken).setOwnerId(uid).build();
        toast.onSurfaceShownChanged(true);
        assertFalse(mAtm.hasActiveVisibleWindow(uid));

        // Though starting window should belong to system. Make sure it is ignored to avoid being
        // allow-list unexpectedly, see b/129563343.
        final WindowState starting = newWindowBuilder("starting",
                TYPE_APPLICATION_STARTING).setWindowToken(app.mToken).setOwnerId(uid).build();
        starting.onSurfaceShownChanged(true);
        assertFalse(mAtm.hasActiveVisibleWindow(uid));

        // Make the application overlay window visible. It should be a valid active visible window.
        overlay.onSurfaceShownChanged(true);
        assertTrue(mAtm.hasActiveVisibleWindow(uid));

        // The number of windows should be independent of the existence of uid state.
        mAtm.mActiveUids.onUidInactive(uid);
        mAtm.mActiveUids.onUidActive(uid, 0 /* any proc state */);
        assertTrue(mAtm.mActiveUids.hasNonAppVisibleWindow(uid));
    }

    @SetupWindows(addWindows = { W_ACTIVITY, W_INPUT_METHOD })
    @Test
    public void testNeedsRelativeLayeringToIme_notAttached() {
        WindowState sameTokenWindow = newWindowBuilder("SameTokenWindow",
                TYPE_BASE_APPLICATION).setWindowToken(mAppWindow.mToken).build();
        mDisplayContent.setImeLayeringTarget(mAppWindow);
        makeWindowVisible(mImeWindow);
        sameTokenWindow.mActivityRecord.getRootTask().setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        assertTrue(sameTokenWindow.needsRelativeLayeringToIme());
        sameTokenWindow.removeImmediately();
        assertFalse(sameTokenWindow.needsRelativeLayeringToIme());
    }

    @SetupWindows(addWindows = { W_ACTIVITY, W_INPUT_METHOD })
    @Test
    public void testNeedsRelativeLayeringToIme_startingWindow() {
        WindowState sameTokenWindow = newWindowBuilder("SameTokenWindow",
                TYPE_APPLICATION_STARTING).setWindowToken(mAppWindow.mToken).build();
        mDisplayContent.setImeLayeringTarget(mAppWindow);
        makeWindowVisible(mImeWindow);
        sameTokenWindow.mActivityRecord.getRootTask().setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        assertFalse(sameTokenWindow.needsRelativeLayeringToIme());
    }

    @UseTestDisplay(addWindows = {W_ACTIVITY, W_INPUT_METHOD})
    @Test
    public void testNeedsRelativeLayeringToIme_systemDialog() {
        WindowState systemDialogWindow = newWindowBuilder("SystemDialog",
                TYPE_SECURE_SYSTEM_OVERLAY).setDisplay(
                mDisplayContent).setOwnerCanAddInternalSystemWindow(true).build();
        mDisplayContent.setImeLayeringTarget(mAppWindow);
        mAppWindow.getTask().setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        makeWindowVisible(mImeWindow);
        systemDialogWindow.mAttrs.flags |= FLAG_ALT_FOCUSABLE_IM;
        assertTrue(systemDialogWindow.needsRelativeLayeringToIme());
    }

    @UseTestDisplay(addWindows = {W_INPUT_METHOD})
    @Test
    public void testNeedsRelativeLayeringToIme_notificationShadeShouldNotHideSystemDialog() {
        WindowState systemDialogWindow = newWindowBuilder("SystemDialog",
                TYPE_SECURE_SYSTEM_OVERLAY).setDisplay(
                mDisplayContent).setOwnerCanAddInternalSystemWindow(true).build();
        mDisplayContent.setImeLayeringTarget(systemDialogWindow);
        makeWindowVisible(mImeWindow);
        WindowState notificationShade = newWindowBuilder("NotificationShade",
                TYPE_NOTIFICATION_SHADE).setDisplay(
                mDisplayContent).setOwnerCanAddInternalSystemWindow(true).build();
        notificationShade.mAttrs.flags |= FLAG_ALT_FOCUSABLE_IM;
        assertFalse(notificationShade.needsRelativeLayeringToIme());
    }

    @Test
    public void testSetFreezeInsetsState() {
        final WindowState app = newWindowBuilder("app", TYPE_APPLICATION).build();
        spyOn(app);
        doReturn(true).when(app).isVisible();

        // Set freezing the insets state to make the window ignore to dispatch insets changed.
        final InsetsState expectedState = new InsetsState(app.getInsetsState(),
                true /* copySources */);
        app.freezeInsetsState();
        assertEquals(expectedState, app.getFrozenInsetsState());
        assertFalse(app.isReadyToDispatchInsetsState());
        assertEquals(expectedState, app.getInsetsState());
        mDisplayContent.getInsetsStateController().notifyInsetsChanged();
        verify(app, never()).notifyInsetsChanged();

        // Unfreeze the insets state to make the window can dispatch insets changed.
        app.clearFrozenInsetsState();
        assertTrue(app.isReadyToDispatchInsetsState());
        mDisplayContent.getInsetsStateController().notifyInsetsChanged();
        verify(app).notifyInsetsChanged();

        // Verify that invisible non-activity window won't dispatch insets changed.
        final WindowState overlay = newWindowBuilder("overlay", TYPE_APPLICATION_OVERLAY).build();
        makeWindowVisible(overlay);
        assertTrue(overlay.isReadyToDispatchInsetsState());
        overlay.mHasSurface = false;
        assertFalse(overlay.isReadyToDispatchInsetsState());
        mDisplayContent.getInsetsStateController().notifyInsetsChanged();
        assertFalse(overlay.getWindowFrames().hasInsetsChanged());
    }

    @SetupWindows(addWindows = { W_INPUT_METHOD, W_ACTIVITY })
    @Test
    public void testImeAlwaysReceivesVisibleNavigationBarInsets() {
        final int navId = InsetsSource.createId(null, 0, navigationBars());
        final InsetsSource navSource = new InsetsSource(navId, navigationBars());
        mImeWindow.mAboveInsetsState.addSource(navSource);
        mAppWindow.mAboveInsetsState.addSource(navSource);

        navSource.setVisible(false);
        assertTrue(mImeWindow.getInsetsState().isSourceOrDefaultVisible(navId, navigationBars()));
        assertFalse(mAppWindow.getInsetsState().isSourceOrDefaultVisible(navId, navigationBars()));

        navSource.setVisible(true);
        assertTrue(mImeWindow.getInsetsState().isSourceOrDefaultVisible(navId, navigationBars()));
        assertTrue(mAppWindow.getInsetsState().isSourceOrDefaultVisible(navId, navigationBars()));
    }

    @SetupWindows(addWindows = { W_INPUT_METHOD })
    @Test
    public void testAdjustImeInsetsVisibilityWhenSwitchingApps() {
        final var appWin1 = newWindowBuilder("appWin1", TYPE_APPLICATION).build();
        final var appWin2 = newWindowBuilder("appWin2", TYPE_APPLICATION).build();
        makeWindowVisibleAndDrawn(mImeWindow);

        final InsetsStateController controller = mDisplayContent.getInsetsStateController();
        controller.getImeSourceProvider().setWindow(mImeWindow, null, null);

        // Simulate appWin2 requests IME.
        appWin2.setRequestedVisibleTypes(ime(), ime());
        mDisplayContent.setImeInputTarget(appWin2);
        mDisplayContent.setImeLayeringTarget(appWin2);
        assertEquals("appWin2 is the IME control target",
                appWin2, mDisplayContent.getImeControlTarget());
        if (android.view.inputmethod.Flags.setServerVisibilityOnprelayout()) {
            controller.getImeSourceProvider().onPreLayout();
        }
        controller.getImeSourceProvider().onPostLayout();

        // Expect all windows behind IME can receive IME insets visible.
        assertTrue("appWin1 has IME insets visible",
                appWin1.getInsetsState().isSourceOrDefaultVisible(ID_IME, ime()));
        assertTrue("appWin2 has IME insets visible",
                appWin2.getInsetsState().isSourceOrDefaultVisible(ID_IME, ime()));

        // Simulate appWin2 plays closing transition to appWin1.
        appWin2.mActivityRecord.commitVisibility(false /* visible */, false /* performLayout */);
        assertNull("appWin1 does not have frozen insets", appWin1.getFrozenInsetsState());
        assertNotNull("appWin2 has frozen insets", appWin2.getFrozenInsetsState());
        mDisplayContent.setImeInputTarget(appWin1);
        mDisplayContent.setImeLayeringTarget(appWin1);
        assertEquals("appWin1 is the IME control target",
                appWin1, mDisplayContent.getImeControlTarget());

        assertFalse("appWin1 does not have IME insets visible",
                appWin1.getInsetsState().isSourceOrDefaultVisible(ID_IME, ime()));
        assertTrue("appWin2 still has IME insets visible, as they were frozen",
                appWin2.getInsetsState().isSourceOrDefaultVisible(ID_IME, ime()));
    }

    @SetupWindows(addWindows = { W_INPUT_METHOD })
    @Test
    public void testAdjustImeInsetsVisibilityWhenSwitchingApps_toAppInMultiWindowMode() {
        final var appWin1 = newWindowBuilder("appWin1", TYPE_APPLICATION)
                .setWindowingMode(WINDOWING_MODE_MULTI_WINDOW).build();
        final var appWin2 = newWindowBuilder("appWin2", TYPE_APPLICATION).build();
        makeWindowVisibleAndDrawn(mImeWindow);
        mDisplayContent.setRemoteInsetsController(createDisplayWindowInsetsController());

        final InsetsStateController controller = mDisplayContent.getInsetsStateController();
        controller.getImeSourceProvider().setWindow(mImeWindow, null, null);

        // Simulate appWin1 in multi-window mode is going to background to switch to the
        // fullscreen appWin2 which requests IME.
        appWin1.mActivityRecord.commitVisibility(false /* visible */, false /* performLayout */);
        assertNotNull("appWin1 has frozen insets", appWin1.getFrozenInsetsState());
        assertNull("appWin2 does not have frozen insets", appWin2.getFrozenInsetsState());
        appWin2.setRequestedVisibleTypes(ime(), ime());
        mDisplayContent.setImeInputTarget(appWin2);
        mDisplayContent.setImeLayeringTarget(appWin2);
        if (android.view.inputmethod.Flags.setServerVisibilityOnprelayout()) {
            controller.getImeSourceProvider().onPreLayout();
        }
        controller.getImeSourceProvider().onPostLayout();

        assertFalse("appWin1 does not have IME insets visible, as it is in background",
                appWin1.getInsetsState().isSourceOrDefaultVisible(ID_IME, ime()));
        assertTrue("appWin2 has IME insets visible",
                appWin2.getInsetsState().isSourceOrDefaultVisible(ID_IME, ime()));

        // Simulate appWin2 plays closing transition to appWin1.
        appWin2.mActivityRecord.commitVisibility(false /* visible */, false /* performLayout */);
        appWin1.mActivityRecord.commitVisibility(true /* visible */, false /* performLayout */);
        assertNull("appWin1 does not have frozen insets", appWin1.getFrozenInsetsState());
        assertNotNull("appWin2 has frozen insets", appWin2.getFrozenInsetsState());
        mDisplayContent.setImeInputTarget(appWin1);
        mDisplayContent.setImeLayeringTarget(appWin1);
        assertEquals("RemoteInsetsControlTarget is the IME control target",
                mDisplayContent.mRemoteInsetsControlTarget, mDisplayContent.getImeControlTarget());

        assertFalse("appWin1 does not have IME insets visible",
                appWin1.getInsetsState().isSourceOrDefaultVisible(ID_IME, ime()));
        assertTrue("appWin2 still has IME insets visible, as they were frozen",
                appWin2.getInsetsState().isSourceOrDefaultVisible(ID_IME, ime()));
    }

    @SetupWindows(addWindows = W_ACTIVITY)
    @Test
    public void testUpdateImeControlTargetWhenLeavingMultiWindow() {
        WindowState app = newWindowBuilder("app", TYPE_BASE_APPLICATION).setWindowToken(
                mAppWindow.mToken).build();
        mDisplayContent.setRemoteInsetsController(createDisplayWindowInsetsController());

        spyOn(app);
        mDisplayContent.setImeInputTarget(mAppWindow);
        mDisplayContent.setImeLayeringTarget(mAppWindow);

        // Simulate entering multi-window mode and verify if the IME control target is remote.
        app.mActivityRecord.getRootTask().setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, app.getWindowingMode());
        assertEquals(mDisplayContent.mRemoteInsetsControlTarget,
                mDisplayContent.computeImeControlTarget());

        // Simulate exiting multi-window mode and verify if the IME control target changed
        // to the app window.
        spyOn(app.getDisplayContent());
        app.mActivityRecord.getRootTask().setWindowingMode(WINDOWING_MODE_FULLSCREEN);

        // Expect updateImeParent will be invoked when the configuration of the IME control
        // target has changed.
        verify(app.getDisplayContent()).updateImeControlTarget(eq(true) /* forceUpdateImeParent */);
        assertEquals(mAppWindow, mDisplayContent.getImeControlTarget().getWindow());
    }

    @SetupWindows(addWindows = { W_ACTIVITY, W_INPUT_METHOD })
    @Test
    public void testLocalInsetsDoesNotCopyToIme() {
        WindowState app = newWindowBuilder("app", TYPE_BASE_APPLICATION).setWindowToken(
                mAppWindow.mToken).build();

        Binder owner = new Binder();
        final Insets attachedInsets = Insets.of(0, 10, 0, 0);
        app.addLocalInsetsFrameProvider(
                new InsetsFrameProvider(owner, 0, WindowInsets.Type.captionBar())
                        .setSource(SOURCE_ATTACHED_CONTAINER_BOUNDS)
                        .setInsetsSize(attachedInsets),
                owner);

        mDisplayContent.setRemoteInsetsController(createDisplayWindowInsetsController());
        mDisplayContent.setImeInputTarget(mAppWindow);
        mDisplayContent.setImeLayeringTarget(mAppWindow);

        mDisplayContent.getInsetsStateController().updateAboveInsetsState(
                false /*notifyInsetsChange*/);
        // Verify the app is having the correct local insets result.
        assertEquals(1, app.mMergedLocalInsetsSources.size());
        InsetsSource appLocalSource = app.mMergedLocalInsetsSources.valueAt(0);
        InsetsSource expectedLocalSource = new InsetsSource(appLocalSource.getId(),
                WindowInsets.Type.captionBar());
        expectedLocalSource.setAttachedInsets(attachedInsets).updateSideHint(new Rect());
        assertEquals(expectedLocalSource, appLocalSource);

        // Verify the IME should not receive any local insets from the target app.
        assertNull(mImeWindow.mMergedLocalInsetsSources);
    }

    @SetupWindows(addWindows = { W_ACTIVITY, W_INPUT_METHOD, W_NOTIFICATION_SHADE })
    @Test
    public void testNotificationShadeHasImeInsetsWhenMultiWindow() {
        WindowState app = newWindowBuilder("app", TYPE_BASE_APPLICATION).setWindowToken(
                mAppWindow.mToken).build();

        // Simulate entering multi-window mode and windowing mode is multi-window.
        app.mActivityRecord.getRootTask().setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, app.getWindowingMode());

        // Simulate notificationShade is shown and being IME layering target.
        mNotificationShadeWindow.setHasSurface(true);
        mNotificationShadeWindow.mAttrs.flags &= ~FLAG_NOT_FOCUSABLE;
        assertTrue(mNotificationShadeWindow.canBeImeLayeringTarget());
        mDisplayContent.getInsetsStateController().getOrCreateSourceProvider(ID_IME, ime())
                .setWindow(mImeWindow, null, null);

        mDisplayContent.computeImeLayeringTarget(true /* update */);
        assertEquals(mNotificationShadeWindow, mDisplayContent.getImeLayeringTarget());
        mDisplayContent.getInsetsStateController().getRawInsetsState()
                .setSourceVisible(ID_IME, true);

        // Verify notificationShade can still get IME insets even windowing mode is multi-window.
        InsetsState state = mNotificationShadeWindow.getInsetsState();
        assertNotNull(state.peekSource(ID_IME));
        assertTrue(state.isSourceOrDefaultVisible(ID_IME, ime()));
    }

    @Test
    public void testRequestedVisibility() {
        final WindowState app = newWindowBuilder("app", TYPE_APPLICATION).build();
        app.mActivityRecord.setVisible(false);
        app.mActivityRecord.setVisibility(false);
        assertFalse(app.isVisibleRequested());

        // It doesn't have a surface yet, but should still be visible requested.
        app.setHasSurface(false);
        app.mActivityRecord.setVisibility(true);

        assertFalse(app.isVisible());
        assertTrue(app.isVisibleRequested());
    }

    @Test
    public void testKeepClearAreas() {
        final WindowState window = newWindowBuilder("window", TYPE_APPLICATION).build();
        makeWindowVisible(window);

        final Rect keepClearArea1 = new Rect(0, 0, 10, 10);
        final Rect keepClearArea2 = new Rect(5, 10, 15, 20);
        final List<Rect> keepClearAreas = Arrays.asList(keepClearArea1, keepClearArea2);
        window.setKeepClearAreas(keepClearAreas, Collections.emptyList());

        // Test that the keep-clear rects are stored and returned
        final List<Rect> windowKeepClearAreas = new ArrayList();
        window.getKeepClearAreas(windowKeepClearAreas, new ArrayList());
        assertEquals(new ArraySet(keepClearAreas), new ArraySet(windowKeepClearAreas));

        // Test that keep-clear rects are overwritten
        window.setKeepClearAreas(Collections.emptyList(), Collections.emptyList());
        windowKeepClearAreas.clear();
        window.getKeepClearAreas(windowKeepClearAreas, new ArrayList());
        assertEquals(0, windowKeepClearAreas.size());

        // Move the window position
        final SurfaceControl.Transaction t = spy(StubTransaction.class);
        window.mSurfaceControl = mock(SurfaceControl.class);
        final Rect frame = window.getFrame();
        frame.set(10, 20, 60, 80);
        window.updateSurfacePosition(t);
        assertEquals(new Point(frame.left, frame.top), window.mLastSurfacePosition);

        // Test that the returned keep-clear rects are translated to display space
        window.setKeepClearAreas(keepClearAreas, Collections.emptyList());
        Rect expectedArea1 = new Rect(keepClearArea1);
        expectedArea1.offset(frame.left, frame.top);
        Rect expectedArea2 = new Rect(keepClearArea2);
        expectedArea2.offset(frame.left, frame.top);

        windowKeepClearAreas.clear();
        window.getKeepClearAreas(windowKeepClearAreas, new ArrayList());
        assertEquals(new ArraySet(Arrays.asList(expectedArea1, expectedArea2)),
                     new ArraySet(windowKeepClearAreas));
    }

    @Test
    public void testUnrestrictedKeepClearAreas() {
        final WindowState window = newWindowBuilder("window", TYPE_APPLICATION).build();
        makeWindowVisible(window);

        final Rect keepClearArea1 = new Rect(0, 0, 10, 10);
        final Rect keepClearArea2 = new Rect(5, 10, 15, 20);
        final List<Rect> keepClearAreas = Arrays.asList(keepClearArea1, keepClearArea2);
        window.setKeepClearAreas(Collections.emptyList(), keepClearAreas);

        // Test that the keep-clear rects are stored and returned
        final List<Rect> restrictedKeepClearAreas = new ArrayList();
        final List<Rect> unrestrictedKeepClearAreas = new ArrayList();
        window.getKeepClearAreas(restrictedKeepClearAreas, unrestrictedKeepClearAreas);
        assertEquals(Collections.emptySet(), new ArraySet(restrictedKeepClearAreas));
        assertEquals(new ArraySet(keepClearAreas), new ArraySet(unrestrictedKeepClearAreas));

        // Test that keep-clear rects are overwritten
        window.setKeepClearAreas(Collections.emptyList(), Collections.emptyList());
        unrestrictedKeepClearAreas.clear();
        window.getKeepClearAreas(unrestrictedKeepClearAreas, new ArrayList());
        assertEquals(0, unrestrictedKeepClearAreas.size());

        // Move the window position
        final SurfaceControl.Transaction t = spy(StubTransaction.class);
        window.mSurfaceControl = mock(SurfaceControl.class);
        final Rect frame = window.getFrame();
        frame.set(10, 20, 60, 80);
        window.updateSurfacePosition(t);
        assertEquals(new Point(frame.left, frame.top), window.mLastSurfacePosition);

        // Test that the returned keep-clear rects are translated to display space
        window.setKeepClearAreas(Collections.emptyList(), keepClearAreas);
        Rect expectedArea1 = new Rect(keepClearArea1);
        expectedArea1.offset(frame.left, frame.top);
        Rect expectedArea2 = new Rect(keepClearArea2);
        expectedArea2.offset(frame.left, frame.top);

        unrestrictedKeepClearAreas.clear();
        window.getKeepClearAreas(restrictedKeepClearAreas, unrestrictedKeepClearAreas);
        assertEquals(Collections.emptySet(), new ArraySet(restrictedKeepClearAreas));
        assertEquals(new ArraySet(Arrays.asList(expectedArea1, expectedArea2)),
                     new ArraySet(unrestrictedKeepClearAreas));
    }

    @Test
    public void testImeTargetChangeListener_OnImeInputTargetVisibilityChanged() {
        final InputMethodManagerInternal immi = InputMethodManagerInternal.get();
        spyOn(immi);

        final WindowState imeInputTarget = newWindowBuilder("imeInputTarget",
                TYPE_BASE_APPLICATION).setWindowToken(
                createActivityRecord(mDisplayContent)).build();

        imeInputTarget.mActivityRecord.setVisibleRequested(true);
        makeWindowVisible(imeInputTarget);
        mDisplayContent.setImeInputTarget(imeInputTarget);
        waitHandlerIdle(mWm.mH);
        verify(immi).onImeInputTargetVisibilityChanged(imeInputTarget.mClient.asBinder(),
                true /* visibleAndNotRemoved */, mDisplayContent.getDisplayId());
        reset(immi);

        imeInputTarget.mActivityRecord.setVisibleRequested(false);
        waitHandlerIdle(mWm.mH);
        verify(immi).onImeInputTargetVisibilityChanged(imeInputTarget.mClient.asBinder(),
                false /* visibleAndNotRemoved */, mDisplayContent.getDisplayId());
        reset(immi);

        imeInputTarget.removeImmediately();
        verify(immi).onImeInputTargetVisibilityChanged(imeInputTarget.mClient.asBinder(),
                false /* visibleAndNotRemoved */, mDisplayContent.getDisplayId());
    }

    @SetupWindows(addWindows = {W_INPUT_METHOD})
    @Test
    public void testImeTargetChangeListener_setHasVisibleImeLayeringOverlay() {
        final InputMethodManagerInternal immi = InputMethodManagerInternal.get();
        spyOn(immi);

        // Scenario 1: test addWindow/relayoutWindow to add Ime layering overlay window as visible.
        final WindowToken windowToken = createTestWindowToken(TYPE_APPLICATION_OVERLAY,
                mDisplayContent);
        final IWindow client = new TestIWindow();
        final Session session = getTestSession();
        final ClientWindowFrames outFrames = new ClientWindowFrames();
        final MergedConfiguration outConfig = new MergedConfiguration();
        final SurfaceControl outSurfaceControl = new SurfaceControl();
        final InsetsState outInsetsState = new InsetsState();
        final InsetsSourceControl.Array outControls = new InsetsSourceControl.Array();
        final WindowRelayoutResult outRelayoutResult = new WindowRelayoutResult(outFrames,
                outConfig, outInsetsState, outControls);
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                TYPE_APPLICATION_OVERLAY);
        params.setTitle("imeLayeringTargetOverlay");
        params.token = windowToken.token;
        params.flags = FLAG_NOT_FOCUSABLE | FLAG_ALT_FOCUSABLE_IM;

        mWm.addWindow(session, client, params, View.VISIBLE, DEFAULT_DISPLAY,
                0 /* userUd */, WindowInsets.Type.defaultVisible(), null,
                new WindowRelayoutResult());
        mWm.relayoutWindow(session, client, params, 100, 200, View.VISIBLE, 0, 0, 0,
                outRelayoutResult, outSurfaceControl);
        waitHandlerIdle(mWm.mH);

        final WindowState imeLayeringTargetOverlay = mDisplayContent.getWindow(
                w -> w.mClient.asBinder() == client.asBinder());
        assertThat(imeLayeringTargetOverlay.isVisible()).isTrue();
        verify(immi, atLeast(1))
                .setHasVisibleImeLayeringOverlay(true /* hasVisibleOverlay */,
                        mDisplayContent.getDisplayId());
        reset(immi);

        // Scenario 2: test relayoutWindow to let the Ime layering target overlay window invisible.
        mWm.relayoutWindow(session, client, params, 100, 200, View.GONE, 0, 0, 0,
                outRelayoutResult, outSurfaceControl);
        waitHandlerIdle(mWm.mH);

        assertThat(imeLayeringTargetOverlay.isVisible()).isFalse();
        verify(immi).setHasVisibleImeLayeringOverlay(false /* hasVisibleOverlay */,
                mDisplayContent.getDisplayId());
        reset(immi);

        // Scenario 3: test removeWindow to remove the Ime layering target overlay window.
        mWm.removeClientToken(session, client.asBinder());
        waitHandlerIdle(mWm.mH);

        verify(immi).setHasVisibleImeLayeringOverlay(false /* hasVisibleOverlay */,
                mDisplayContent.getDisplayId());
    }

    @Test
    public void testIsSecureLocked_flagSecureSet() {
        WindowState window = newWindowBuilder("test-window", TYPE_APPLICATION).setOwnerId(
                1).build();
        window.mAttrs.flags |= WindowManager.LayoutParams.FLAG_SECURE;

        assertTrue(window.isSecureLocked());
    }

    @Test
    public void testIsSecureLocked_flagSecureNotSet() {
        WindowState window = newWindowBuilder("test-window", TYPE_APPLICATION).setOwnerId(
                1).build();

        assertFalse(window.isSecureLocked());
    }

    @Test
    public void testIsSecureLocked_disableSecureWindows() {
        assumeTrue(Build.IS_DEBUGGABLE);

        WindowState window = newWindowBuilder("test-window", TYPE_APPLICATION).setOwnerId(
                1).build();
        window.mAttrs.flags |= WindowManager.LayoutParams.FLAG_SECURE;
        ContentResolver cr = useFakeSettingsProvider();

        // isSecureLocked should return false when DISABLE_SECURE_WINDOWS is set to 1
        Settings.Secure.putString(cr, Settings.Secure.DISABLE_SECURE_WINDOWS, "1");
        mWm.mSettingsObserver.onChange(false /* selfChange */,
                Settings.Secure.getUriFor(Settings.Secure.DISABLE_SECURE_WINDOWS));
        assertFalse(window.isSecureLocked());

        // isSecureLocked should return true if DISABLE_SECURE_WINDOWS is set to 0.
        Settings.Secure.putString(cr, Settings.Secure.DISABLE_SECURE_WINDOWS, "0");
        mWm.mSettingsObserver.onChange(false /* selfChange */,
                Settings.Secure.getUriFor(Settings.Secure.DISABLE_SECURE_WINDOWS));
        assertTrue(window.isSecureLocked());

        // Disable secure windows again.
        Settings.Secure.putString(cr, Settings.Secure.DISABLE_SECURE_WINDOWS, "1");
        mWm.mSettingsObserver.onChange(false /* selfChange */,
                Settings.Secure.getUriFor(Settings.Secure.DISABLE_SECURE_WINDOWS));
        assertFalse(window.isSecureLocked());

        // isSecureLocked should return true if DISABLE_SECURE_WINDOWS is deleted.
        Settings.Secure.putString(cr, Settings.Secure.DISABLE_SECURE_WINDOWS, null);
        mWm.mSettingsObserver.onChange(false /* selfChange */,
                Settings.Secure.getUriFor(Settings.Secure.DISABLE_SECURE_WINDOWS));
        assertTrue(window.isSecureLocked());
    }

    @Test
    @RequiresFlagsEnabled(FLAG_SENSITIVE_NOTIFICATION_APP_PROTECTION)
    public void testIsSecureLocked_sensitiveContentProtectionManagerEnabled() {
        String testPackage = "test";
        int ownerId1 = 20;
        int ownerId2 = 21;
        final WindowState window1 = newWindowBuilder("window1", TYPE_APPLICATION).setOwnerId(
                ownerId1).build();
        final WindowState window2 = newWindowBuilder("window2", TYPE_APPLICATION).setOwnerId(
                ownerId2).build();

        // Setting packagename for targeted feature
        window1.mAttrs.packageName = testPackage;
        window2.mAttrs.packageName = testPackage;

        PackageInfo blockedPackage = new PackageInfo(testPackage, ownerId1);
        ArraySet<PackageInfo> blockedPackages = new ArraySet();
        blockedPackages.add(blockedPackage);
        mWm.mSensitiveContentPackages.addBlockScreenCaptureForApps(blockedPackages);

        assertTrue(window1.isSecureLocked());
        assertFalse(window2.isSecureLocked());
    }

    @Test
    @RequiresFlagsEnabled(FLAG_SENSITIVE_NOTIFICATION_APP_PROTECTION)
    public void testIsSecureLocked_sensitiveContentBlockOrClearScreenCaptureForApp() {
        String testPackage = "test";
        int ownerId = 20;
        final WindowState window = newWindowBuilder("window", TYPE_APPLICATION).setOwnerId(
                ownerId).build();
        window.mAttrs.packageName = testPackage;
        assertFalse(window.isSecureLocked());

        PackageInfo blockedPackage = new PackageInfo(testPackage, ownerId);
        ArraySet<PackageInfo> blockedPackages = new ArraySet();
        blockedPackages.add(blockedPackage);
        mWm.mSensitiveContentPackages.addBlockScreenCaptureForApps(blockedPackages);
        assertTrue(window.isSecureLocked());

        mWm.mSensitiveContentPackages.removeBlockScreenCaptureForApps(blockedPackages);
        assertFalse(window.isSecureLocked());
    }

    @Test
    public void testIsWindowTrustedOverlay_default() {
        final WindowState window = newWindowBuilder("window", TYPE_APPLICATION).build();

        assertThat(window.isWindowTrustedOverlay()).isFalse();
    }

    @Test
    public void testIsWindowTrustedOverlay_isTrustedOverlay() {
        List<Integer> trustedTypes = List.of(
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY,
                WindowManager.LayoutParams.TYPE_INPUT_METHOD,
                WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG,
                WindowManager.LayoutParams.TYPE_MAGNIFICATION_OVERLAY,
                WindowManager.LayoutParams.TYPE_STATUS_BAR,
                WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.TYPE_DOCK_DIVIDER,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.TYPE_INPUT_CONSUMER,
                WindowManager.LayoutParams.TYPE_VOICE_INTERACTION,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_ADDITIONAL);

        for (Integer type : trustedTypes) {
            final WindowState window = newWindowBuilder("window", type).build();
            assertThat(window.isWindowTrustedOverlay()).isTrue();
        }
    }

    @Test
    public void testIsWindowTrustedOverlay_noPrivateFlagTrustedOverlay_internalWindowPermission() {
        SystemUtil.runWithShellPermissionIdentity(() -> {
            final WindowState window = newWindowBuilder("window", TYPE_APPLICATION_OVERLAY).build();

            assertThat(window.isWindowTrustedOverlay()).isFalse();
        });
    }

    @Test
    public void testIsWindowTrustedOverlay_privateFlagTrustedOverlay_internalWindowPermission() {
        SystemUtil.runWithShellPermissionIdentity(() -> {
            final WindowState window = newWindowBuilder("window", TYPE_APPLICATION_OVERLAY).build();
            window.mAttrs.privateFlags |= PRIVATE_FLAG_TRUSTED_OVERLAY;
            assertThat(window.mAttrs.privateFlags & PRIVATE_FLAG_TRUSTED_OVERLAY).isGreaterThan(0);

            assertThat(window.isWindowTrustedOverlay()).isTrue();
        });
    }

    @Test
    public void testIsWindowTrustedOverlay_withoutPrivateFlag_applicationOverlayPermission() {
        SystemUtil.runWithShellPermissionIdentity(() -> {
            final WindowState window = newWindowBuilder("window", TYPE_APPLICATION_OVERLAY).build();

            assertThat(window.isWindowTrustedOverlay()).isFalse();
        });
    }

    @Test
    public void testIsWindowTrustedOverlay_withPrivateFlag_applicationOverlayPermission() {
        SystemUtil.runWithShellPermissionIdentity(() -> {
            final WindowState window = newWindowBuilder("window", TYPE_APPLICATION_OVERLAY).build();
            window.mAttrs.privateFlags |= PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY;

            assertThat(window.isWindowTrustedOverlay()).isTrue();
        });
    }

    @Test
    @EnableFlags(com.android.media.projection.flags.Flags.FLAG_RECORDING_OVERLAY)
    public void testIsWindowTrustedOverlay_recordingOverlay_isApplicationOverlay_hasOp() {
        AppOpsManager mAppOps = mContext.getSystemService(AppOpsManager.class);
        int originalState = mAppOps.unsafeCheckOpRawNoThrow(
                AppOpsManager.OP_SYSTEM_APPLICATION_OVERLAY, android.os.Process.myUid(),
                mContext.getPackageName());
        try {
            mAppOps.setMode(AppOpsManager.OP_SYSTEM_APPLICATION_OVERLAY, android.os.Process.myUid(),
                    mContext.getPackageName(), AppOpsManager.MODE_ALLOWED);
            SystemUtil.runWithShellPermissionIdentity(() -> {
                final WindowState window = newWindowBuilder("window",
                        TYPE_APPLICATION_OVERLAY).build();
                window.mAttrs.privateFlags |= PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY;

                assertThat(window.isWindowTrustedOverlay()).isTrue();
            });
        } finally {
            mAppOps.setMode(AppOpsManager.OP_SYSTEM_APPLICATION_OVERLAY, android.os.Process.myUid(),
                    mContext.getPackageName(), originalState);
        }
    }

    @Test
    @EnableFlags(com.android.media.projection.flags.Flags.FLAG_RECORDING_OVERLAY)
    public void testIsWindowTrustedOverlay_recordingOverlay_isNotApplicationOverlay_hasOp() {
        AppOpsManager mAppOps = mContext.getSystemService(AppOpsManager.class);
        int originalState = mAppOps.unsafeCheckOpRawNoThrow(
                AppOpsManager.OP_SYSTEM_APPLICATION_OVERLAY, android.os.Process.myUid(),
                mContext.getPackageName());
        try {
            mAppOps.setMode(AppOpsManager.OP_SYSTEM_APPLICATION_OVERLAY, android.os.Process.myUid(),
                    mContext.getPackageName(), AppOpsManager.MODE_ALLOWED);
            SystemUtil.runWithShellPermissionIdentity(() -> {
                final WindowState window = newWindowBuilder("window",
                        TYPE_APPLICATION_OVERLAY).build();

                assertThat(window.isWindowTrustedOverlay()).isFalse();
            });
        } finally {
            mAppOps.setMode(AppOpsManager.OP_SYSTEM_APPLICATION_OVERLAY, android.os.Process.myUid(),
                    mContext.getPackageName(), originalState);
        }
    }

    @Test
    @DisableFlags(com.android.media.projection.flags.Flags.FLAG_RECORDING_OVERLAY)
    public void testIsWindowTrustedOverlay_recordingOverlayDisabled_isApplicationOverlay_hasOp() {
        AppOpsManager mAppOps = mContext.getSystemService(AppOpsManager.class);
        int originalState = mAppOps.unsafeCheckOpRawNoThrow(
                AppOpsManager.OP_SYSTEM_APPLICATION_OVERLAY, android.os.Process.myUid(),
                mContext.getPackageName());
        try {
            mAppOps.setMode(AppOpsManager.OP_SYSTEM_APPLICATION_OVERLAY, android.os.Process.myUid(),
                    mContext.getPackageName(), AppOpsManager.MODE_ALLOWED);
            final WindowState window = newWindowBuilder("window", TYPE_APPLICATION_OVERLAY).build();
            window.mAttrs.privateFlags |= PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY;

            assertThat(window.isWindowTrustedOverlay()).isFalse();
        } finally {
            mAppOps.setMode(AppOpsManager.OP_SYSTEM_APPLICATION_OVERLAY, android.os.Process.myUid(),
                    mContext.getPackageName(), originalState);
        }
    }
}
