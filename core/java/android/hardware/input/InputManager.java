/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.hardware.input;

import static com.android.input.flags.Flags.FLAG_INPUT_DEVICE_VIEW_BEHAVIOR_API;
import static com.android.input.flags.Flags.FLAG_DEVICE_ASSOCIATIONS;
import static com.android.hardware.input.Flags.enableCustomizableInputGestures;
import static com.android.hardware.input.Flags.keyboardGlyphMap;

import android.Manifest;
import android.annotation.FlaggedApi;
import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UserHandleAware;
import android.annotation.UserIdInt;
import android.app.ActivityThread;
import android.compat.annotation.ChangeId;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.graphics.PointF;
import android.graphics.drawable.Drawable;
import android.hardware.BatteryState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.InputEventInjectionSync;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Vibrator;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputMonitor;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.VerifiedInputEvent;
import android.view.View;
import android.view.View.PointerCaptureMode;
import android.view.WindowManager.LayoutParams;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Provides information about input devices and available key layouts.
 */
@SystemService(Context.INPUT_SERVICE)
@RavenwoodKeepWholeClass(conditional = true, comment = """
        Need to provide an implementation of IInputManager and initialize with
        InputManagerGlobal#createTestSession before using any of its methods.
        The behavior of this class fully depends on the provided IInputManager.
        """)
public final class InputManager {
    private static final String TAG = "InputManager";
    // To enable these logs, run: 'adb shell setprop log.tag.InputManager DEBUG' (requires restart)
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private final IInputManager mIm;

    /**
     * Whether a PointerIcon is shown for stylus pointers.
     * Obtain using {@link #isStylusPointerIconEnabled()}.
     */
    @Nullable
    private Boolean mIsStylusPointerIconEnabled = null;

    /**
     * Broadcast Action: Query available keyboard layouts.
     * <p>
     * The input manager service locates available keyboard layouts
     * by querying broadcast receivers that are registered for this action.
     * An application can offer additional keyboard layouts to the user
     * by declaring a suitable broadcast receiver in its manifest.
     * </p><p>
     * Here is an example broadcast receiver declaration that an application
     * might include in its AndroidManifest.xml to advertise keyboard layouts.
     * The meta-data specifies a resource that contains a description of each keyboard
     * layout that is provided by the application.
     * <pre><code>
     * &lt;receiver android:name=".InputDeviceReceiver"
     *         android:label="@string/keyboard_layouts_label">
     *     &lt;intent-filter>
     *         &lt;action android:name="android.hardware.input.action.QUERY_KEYBOARD_LAYOUTS" />
     *     &lt;/intent-filter>
     *     &lt;meta-data android:name="android.hardware.input.metadata.KEYBOARD_LAYOUTS"
     *             android:resource="@xml/keyboard_layouts" />
     * &lt;/receiver>
     * </code></pre>
     * </p><p>
     * In the above example, the <code>@xml/keyboard_layouts</code> resource refers to
     * an XML resource whose root element is <code>&lt;keyboard-layouts></code> that
     * contains zero or more <code>&lt;keyboard-layout></code> elements.
     * Each <code>&lt;keyboard-layout></code> element specifies the name, label, and location
     * of a key character map for a particular keyboard layout.  The label on the receiver
     * is used to name the collection of keyboard layouts provided by this receiver in the
     * keyboard layout settings.
     * <pre><code>
     * &lt;?xml version="1.0" encoding="utf-8"?>
     * &lt;keyboard-layouts xmlns:android="http://schemas.android.com/apk/res/android">
     *     &lt;keyboard-layout android:name="keyboard_layout_english_us"
     *             android:label="@string/keyboard_layout_english_us_label"
     *             android:keyboardLayout="@raw/keyboard_layout_english_us" />
     * &lt;/keyboard-layouts>
     * </pre></code>
     * </p><p>
     * The <code>android:name</code> attribute specifies an identifier by which
     * the keyboard layout will be known in the package.
     * The <code>android:label</code> attribute specifies a human-readable descriptive
     * label to describe the keyboard layout in the user interface, such as "English (US)".
     * The <code>android:keyboardLayout</code> attribute refers to a
     * <a href="https://source.android.com/docs/core/interaction/input/key-character-map-files">
     * key character map</a> resource that defines the keyboard layout.
     * The <code>android:keyboardLocale</code> attribute specifies a comma separated list of BCP 47
     * language tags depicting the locales supported by the keyboard layout. This attribute is
     * optional and will be used for auto layout selection for external physical keyboards.
     * The <code>android:keyboardLayoutType</code> attribute specifies the layoutType for the
     * keyboard layout. This can be either empty or one of the following supported layout types:
     * qwerty, qwertz, azerty, dvorak, colemak, workman, extended, turkish_q, turkish_f. This
     * attribute is optional and will be used for auto layout selection for external physical
     * keyboards.
     * </p>
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_QUERY_KEYBOARD_LAYOUTS =
            "android.hardware.input.action.QUERY_KEYBOARD_LAYOUTS";

    /**
     * Metadata Key: Keyboard layout metadata associated with
     * {@link #ACTION_QUERY_KEYBOARD_LAYOUTS}.
     * <p>
     * Specifies the resource id of a XML resource that describes the keyboard
     * layouts that are provided by the application.
     * </p>
     */
    public static final String META_DATA_KEYBOARD_LAYOUTS =
            "android.hardware.input.metadata.KEYBOARD_LAYOUTS";

    /**
     * Broadcast Action: Query available keyboard glyph maps.
     * <p>
     * The input manager service locates available keyboard glyph maps
     * by querying broadcast receivers that are registered for this action.
     * An application can offer additional keyboard glyph maps to the user
     * by declaring a suitable broadcast receiver in its manifest.
     * </p>
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_QUERY_KEYBOARD_GLYPH_MAPS =
            "android.hardware.input.action.QUERY_KEYBOARD_GLYPH_MAPS";

    /**
     * Metadata Key: Keyboard glyph map metadata associated with
     * {@link #ACTION_QUERY_KEYBOARD_GLYPH_MAPS}.
     * <p>
     * Specifies the resource id of a XML resource that describes the keyboard
     * glyph maps that are provided by the application.
     * </p>
     *
     * @hide
     */
    public static final String META_DATA_KEYBOARD_GLYPH_MAPS =
            "android.hardware.input.metadata.KEYBOARD_GLYPH_MAPS";

    /**
     * Prevent touches from being consumed by apps if these touches passed through a non-trusted
     * window from a different UID and are considered unsafe.
     *
     * @hide
     */
    @TestApi
    @ChangeId
    public static final long BLOCK_UNTRUSTED_TOUCHES = 158002302L;

    /**
     * Input Event Injection Synchronization Mode: None.
     * Never blocks.  Injection is asynchronous and is assumed always to be successful.
     * @hide
     */
    public static final int INJECT_INPUT_EVENT_MODE_ASYNC = InputEventInjectionSync.NONE;

    /**
     * Input Event Injection Synchronization Mode: Wait for result.
     * Waits for previous events to be dispatched so that the input dispatcher can
     * determine whether input event injection will be permitted based on the current
     * input focus.  Does not wait for the input event to finish being handled
     * by the application.
     * @hide
     */
    public static final int INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT =
            InputEventInjectionSync.WAIT_FOR_RESULT;

    /**
     * Input Event Injection Synchronization Mode: Wait for finish.
     * Waits for the event to be delivered to the application and handled.
     * @hide
     */
    @UnsupportedAppUsage(trackingBug = 171972397)
    public static final int INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH =
            InputEventInjectionSync.WAIT_FOR_FINISHED;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "SWITCH_STATE_" }, value = {
            SWITCH_STATE_UNKNOWN,
            SWITCH_STATE_OFF,
            SWITCH_STATE_ON
    })
    public @interface SwitchState {}

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "REMAPPABLE_MODIFIER_KEY_" }, value = {
            RemappableModifierKey.REMAPPABLE_MODIFIER_KEY_CTRL_LEFT,
            RemappableModifierKey.REMAPPABLE_MODIFIER_KEY_CTRL_RIGHT,
            RemappableModifierKey.REMAPPABLE_MODIFIER_KEY_META_LEFT,
            RemappableModifierKey.REMAPPABLE_MODIFIER_KEY_META_RIGHT,
            RemappableModifierKey.REMAPPABLE_MODIFIER_KEY_ALT_LEFT,
            RemappableModifierKey.REMAPPABLE_MODIFIER_KEY_ALT_RIGHT,
            RemappableModifierKey.REMAPPABLE_MODIFIER_KEY_SHIFT_LEFT,
            RemappableModifierKey.REMAPPABLE_MODIFIER_KEY_SHIFT_RIGHT,
            RemappableModifierKey.REMAPPABLE_MODIFIER_KEY_CAPS_LOCK,
    })
    public @interface RemappableModifierKey {
        int REMAPPABLE_MODIFIER_KEY_CTRL_LEFT = KeyEvent.KEYCODE_CTRL_LEFT;
        int REMAPPABLE_MODIFIER_KEY_CTRL_RIGHT = KeyEvent.KEYCODE_CTRL_RIGHT;
        int REMAPPABLE_MODIFIER_KEY_META_LEFT = KeyEvent.KEYCODE_META_LEFT;
        int REMAPPABLE_MODIFIER_KEY_META_RIGHT = KeyEvent.KEYCODE_META_RIGHT;
        int REMAPPABLE_MODIFIER_KEY_ALT_LEFT = KeyEvent.KEYCODE_ALT_LEFT;
        int REMAPPABLE_MODIFIER_KEY_ALT_RIGHT = KeyEvent.KEYCODE_ALT_RIGHT;
        int REMAPPABLE_MODIFIER_KEY_SHIFT_LEFT = KeyEvent.KEYCODE_SHIFT_LEFT;
        int REMAPPABLE_MODIFIER_KEY_SHIFT_RIGHT = KeyEvent.KEYCODE_SHIFT_RIGHT;
        int REMAPPABLE_MODIFIER_KEY_CAPS_LOCK = KeyEvent.KEYCODE_CAPS_LOCK;
    }

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"CONTROLLER_BUTTON_"}, value = {
            ControllerButton.CONTROLLER_BUTTON_A,
            ControllerButton.CONTROLLER_BUTTON_B,
            ControllerButton.CONTROLLER_BUTTON_X,
            ControllerButton.CONTROLLER_BUTTON_Y,
            ControllerButton.CONTROLLER_BUTTON_L1,
            ControllerButton.CONTROLLER_BUTTON_R1,
            ControllerButton.CONTROLLER_BUTTON_L2,
            ControllerButton.CONTROLLER_BUTTON_R2,
            ControllerButton.CONTROLLER_BUTTON_SELECT,
            ControllerButton.CONTROLLER_BUTTON_START,
            ControllerButton.CONTROLLER_BUTTON_MODE,
            ControllerButton.CONTROLLER_BUTTON_THUMBSTICK_LEFT,
            ControllerButton.CONTROLLER_BUTTON_THUMBSTICK_RIGHT,
    })
    public @interface ControllerButton {
        int CONTROLLER_BUTTON_A = KeyEvent.KEYCODE_BUTTON_A;
        int CONTROLLER_BUTTON_B = KeyEvent.KEYCODE_BUTTON_B;
        int CONTROLLER_BUTTON_X = KeyEvent.KEYCODE_BUTTON_X;
        int CONTROLLER_BUTTON_Y = KeyEvent.KEYCODE_BUTTON_Y;
        int CONTROLLER_BUTTON_L1 = KeyEvent.KEYCODE_BUTTON_L1;
        int CONTROLLER_BUTTON_R1 = KeyEvent.KEYCODE_BUTTON_R1;
        int CONTROLLER_BUTTON_L2 = KeyEvent.KEYCODE_BUTTON_L2;
        int CONTROLLER_BUTTON_R2 = KeyEvent.KEYCODE_BUTTON_R2;
        int CONTROLLER_BUTTON_SELECT = KeyEvent.KEYCODE_BUTTON_SELECT;
        int CONTROLLER_BUTTON_START = KeyEvent.KEYCODE_BUTTON_START;
        int CONTROLLER_BUTTON_MODE = KeyEvent.KEYCODE_BUTTON_MODE;
        int CONTROLLER_BUTTON_THUMBSTICK_LEFT = KeyEvent.KEYCODE_BUTTON_THUMBL;
        int CONTROLLER_BUTTON_THUMBSTICK_RIGHT = KeyEvent.KEYCODE_BUTTON_THUMBR;
    }

    /**
     * Custom input gesture result success
     *
     * @hide
     */
    public static final int CUSTOM_INPUT_GESTURE_RESULT_SUCCESS = 1;

    /**
     * Custom input gesture error: Input gesture already exists
     *
     * @hide
     */
    public static final int CUSTOM_INPUT_GESTURE_RESULT_ERROR_ALREADY_EXISTS = 2;

    /**
     * Custom input gesture error: Input gesture does not exist
     *
     * @hide
     */
    public static final int CUSTOM_INPUT_GESTURE_RESULT_ERROR_DOES_NOT_EXIST = 3;

    /**
     * Custom input gesture error: Input gesture is reserved for system action
     *
     * @hide
     */
    public static final int CUSTOM_INPUT_GESTURE_RESULT_ERROR_RESERVED_GESTURE = 4;

    /**
     * Custom input gesture error: Failure error code for all other errors/warnings
     *
     * @hide
     */
    public static final int CUSTOM_INPUT_GESTURE_RESULT_ERROR_OTHER = 5;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "CUSTOM_INPUT_GESTURE_RESULT_" }, value = {
            CUSTOM_INPUT_GESTURE_RESULT_SUCCESS,
            CUSTOM_INPUT_GESTURE_RESULT_ERROR_ALREADY_EXISTS,
            CUSTOM_INPUT_GESTURE_RESULT_ERROR_DOES_NOT_EXIST,
            CUSTOM_INPUT_GESTURE_RESULT_ERROR_RESERVED_GESTURE,
            CUSTOM_INPUT_GESTURE_RESULT_ERROR_OTHER,
    })
    public @interface CustomInputGestureResult {}

    /**
     * Switch State: Unknown.
     *
     * The system has yet to report a valid value for the switch.
     * @hide
     */
    public static final int SWITCH_STATE_UNKNOWN = -1;

    /**
     * Switch State: Off.
     * @hide
     */
    public static final int SWITCH_STATE_OFF = 0;

    /**
     * Switch State: On.
     * @hide
     */
    public static final int SWITCH_STATE_ON = 1;

    private final InputManagerGlobal mGlobal;
    private final Context mContext;

    /** @hide */
    public InputManager(Context context) {
        mGlobal = InputManagerGlobal.getInstance();
        mIm = mGlobal.getInputManagerService();
        mContext = context;
    }

    /**
     * Gets an instance of the input manager.
     *
     *  Warning: The usage of this method is not supported!
     *
     *  @return The input manager instance.
     *  Use {@link Context#getSystemService(Class)}
     *  to obtain the InputManager instance.
     *
     * TODO (b/277717573): Soft remove this API in version V.
     * TODO (b/277039664): Migrate app usage off this API.
     *
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
    public static InputManager getInstance() {
        return Objects.requireNonNull(ActivityThread.currentApplication())
                .getSystemService(InputManager.class);
    }

    /**
     * Get the current VelocityTracker strategy.
     * @hide
     */
    public String getVelocityTrackerStrategy() {
        return mGlobal.getVelocityTrackerStrategy();
    }

    /**
     * Gets information about the input device with the specified id.
     * @param id The device id.
     * @return The input device or null if not found.
     */
    @Nullable
    public InputDevice getInputDevice(int id) {
        return mGlobal.getInputDevice(id);
    }

    /**
     * Gets the {@link InputDevice.ViewBehavior} of the input device with a given {@code id}.
     *
     * <p>Use this API to query a fresh view behavior instance whenever the input device
     * changes.
     *
     * @param deviceId the id of the input device whose view behavior is being requested.
     * @return the view behavior of the input device with the provided id, or {@code null} if there
     *      is not input device with the provided id.
     */
    @FlaggedApi(FLAG_INPUT_DEVICE_VIEW_BEHAVIOR_API)
    @Nullable
    public InputDevice.ViewBehavior getInputDeviceViewBehavior(int deviceId) {
        InputDevice device = getInputDevice(deviceId);
        return device == null ? null : device.getViewBehavior();
    }

    /**
     * Gets information about the input device with the specified descriptor.
     * @param descriptor The input device descriptor.
     * @return The input device or null if not found.
     * @hide
     */
    public InputDevice getInputDeviceByDescriptor(String descriptor) {
        return mGlobal.getInputDeviceByDescriptor(descriptor);
    }

    /**
     * Gets the ids of all input devices in the system.
     * @return The input device ids.
     */
    public int[] getInputDeviceIds() {
        return mGlobal.getInputDeviceIds();
    }

    /**
     * Enables an InputDevice.
     * <p>
     * Requires {@link android.Manifest.permission#DISABLE_INPUT_DEVICE}.
     * </p>
     *
     * @param id The input device Id.
     *
     * @hide
     */
    public void enableInputDevice(int id) {
        mGlobal.enableInputDevice(id);
    }

    /**
     * Disables an InputDevice.
     * <p>
     * Requires {@link android.Manifest.permission#DISABLE_INPUT_DEVICE}.
     * </p>
     *
     * @param id The input device Id.
     *
     * @hide
     */
    public void disableInputDevice(int id) {
        mGlobal.disableInputDevice(id);
    }

    /**
     * Registers an input device listener to receive notifications about when
     * input devices are added, removed or changed.
     *
     * @param listener The listener to register.
     * @param handler The handler on which the listener should be invoked, or null
     * if the listener should be invoked on the calling thread's looper.
     *
     * @see #unregisterInputDeviceListener
     */
    public void registerInputDeviceListener(InputDeviceListener listener, Handler handler) {
        mGlobal.registerInputDeviceListener(listener, handler);
    }

    /**
     * Unregisters an input device listener.
     *
     * @param listener The listener to unregister.
     *
     * @see #registerInputDeviceListener
     */
    public void unregisterInputDeviceListener(InputDeviceListener listener) {
        mGlobal.unregisterInputDeviceListener(listener);
    }

    /**
     * Queries whether the device is in tablet mode.
     *
     * @return The tablet switch state which is one of {@link #SWITCH_STATE_UNKNOWN},
     * {@link #SWITCH_STATE_OFF} or {@link #SWITCH_STATE_ON}.
     * @hide
     */
    @SwitchState
    public int isInTabletMode() {
        try {
            return mIm.isInTabletMode();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Register a tablet mode changed listener.
     *
     * @param listener The listener to register.
     * @param handler The handler on which the listener should be invoked, or null
     * if the listener should be invoked on the calling thread's looper.
     * @hide
     */
    public void registerOnTabletModeChangedListener(
            OnTabletModeChangedListener listener, Handler handler) {
        mGlobal.registerOnTabletModeChangedListener(listener, handler);
    }

    /**
     * Unregister a tablet mode changed listener.
     *
     * @param listener The listener to unregister.
     * @hide
     */
    public void unregisterOnTabletModeChangedListener(OnTabletModeChangedListener listener) {
        mGlobal.unregisterOnTabletModeChangedListener(listener);
    }

    /**
     * Queries whether the device's microphone is muted
     *
     * @return The mic mute switch state which is one of {@link #SWITCH_STATE_UNKNOWN},
     * {@link #SWITCH_STATE_OFF} or {@link #SWITCH_STATE_ON}.
     * @hide
     */
    @SwitchState
    public int isMicMuted() {
        try {
            return mIm.isMicMuted();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets information about all supported keyboard layouts.
     * <p>
     * The input manager consults the built-in keyboard layouts as well
     * as all keyboard layouts advertised by applications using a
     * {@link #ACTION_QUERY_KEYBOARD_LAYOUTS} broadcast receiver.
     * </p>
     *
     * @return A list of all supported keyboard layouts.
     *
     * @hide
     */
    public KeyboardLayout[] getKeyboardLayouts() {
        try {
            return mIm.getKeyboardLayouts();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the descriptors of all supported keyboard layouts.
     * <p>
     * The input manager consults the built-in keyboard layouts as well as all keyboard layouts
     * advertised by applications using a {@link #ACTION_QUERY_KEYBOARD_LAYOUTS} broadcast receiver.
     * </p>
     *
     * @return The ids of all keyboard layouts which are supported by the specified input device.
     *
     * @hide
     */
    @TestApi
    @NonNull
    @SuppressLint("UnflaggedApi")
    public List<String> getKeyboardLayoutDescriptors() {
        KeyboardLayout[] layouts = getKeyboardLayouts();
        List<String> res = new ArrayList<>();
        for (KeyboardLayout kl : layouts) {
            res.add(kl.getDescriptor());
        }
        return res;
    }

    /**
     * Returns the layout type of the queried layout
     * <p>
     * The input manager consults the built-in keyboard layouts as well as all keyboard layouts
     * advertised by applications using a {@link #ACTION_QUERY_KEYBOARD_LAYOUTS} broadcast receiver.
     * </p>
     *
     * @param layoutDescriptor The layout descriptor of the queried layout
     * @return layout type of the queried layout
     *
     * @hide
     */
    @TestApi
    @NonNull
    public String getKeyboardLayoutTypeForLayoutDescriptor(@NonNull String layoutDescriptor) {
        KeyboardLayout layout = getKeyboardLayout(layoutDescriptor);
        return layout == null ? "" : layout.getLayoutType();
    }

    /**
     * TODO(b/330517633): Cleanup the unsupported API
     * @hide
     */
    @NonNull
    public KeyboardLayout[] getKeyboardLayoutsForInputDevice(
            @NonNull InputDeviceIdentifier identifier) {
        return new KeyboardLayout[0];
    }

    /**
     * Gets the keyboard layout with the specified descriptor.
     *
     * @param keyboardLayoutDescriptor The keyboard layout descriptor, as returned by
     * {@link KeyboardLayout#getDescriptor()}.
     * @return The keyboard layout, or null if it could not be loaded.
     *
     * @hide
     */
    @Nullable
    public KeyboardLayout getKeyboardLayout(String keyboardLayoutDescriptor) {
        if (keyboardLayoutDescriptor == null) {
            throw new IllegalArgumentException("keyboardLayoutDescriptor must not be null");
        }

        try {
            return mIm.getKeyboardLayout(keyboardLayoutDescriptor);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * TODO(b/330517633): Cleanup the unsupported API
     * @hide
     */
    @Nullable
    public String getCurrentKeyboardLayoutForInputDevice(
            @NonNull InputDeviceIdentifier identifier) {
        return null;
    }

    /**
     * TODO(b/330517633): Cleanup the unsupported API
     * @hide
     */
    public void setCurrentKeyboardLayoutForInputDevice(@NonNull InputDeviceIdentifier identifier,
            @NonNull String keyboardLayoutDescriptor) {}

    /**
     * TODO(b/330517633): Cleanup the unsupported API
     * @hide
     */
    public String[] getEnabledKeyboardLayoutsForInputDevice(InputDeviceIdentifier identifier) {
        return new String[0];
    }

    /**
     * TODO(b/330517633): Cleanup the unsupported API
     * @hide
     */
    public void addKeyboardLayoutForInputDevice(InputDeviceIdentifier identifier,
            String keyboardLayoutDescriptor) {
    }

    /**
     * TODO(b/330517633): Cleanup the unsupported API
     * @hide
     */
    @RequiresPermission(Manifest.permission.SET_KEYBOARD_LAYOUT)
    public void removeKeyboardLayoutForInputDevice(@NonNull InputDeviceIdentifier identifier,
            @NonNull String keyboardLayoutDescriptor) {
    }

    /**
     * Remaps modifier keys. Remapping a modifier key to itself will clear any previous remappings
     * for that key.
     *
     * @param fromKey The modifier key getting remapped.
     * @param toKey The modifier key that it is remapped to.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.REMAP_MODIFIER_KEYS)
    public void remapModifierKey(@RemappableModifierKey int fromKey,
            @RemappableModifierKey int toKey) {
        try {
            mIm.remapModifierKey(fromKey, toKey);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Clears all existing modifier key remappings
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.REMAP_MODIFIER_KEYS)
    public void clearAllModifierKeyRemappings() {
        try {
            mIm.clearAllModifierKeyRemappings();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Provides the current modifier key remapping
     *
     * @return a {fromKey, toKey} map that contains the existing modifier key remappings..
     * {@link RemappableModifierKey}
     *
     * @hide
     */
    @TestApi
    @NonNull
    @SuppressWarnings("unchecked")
    @RequiresPermission(Manifest.permission.REMAP_MODIFIER_KEYS)
    public Map<Integer, Integer> getModifierKeyRemapping() {
        try {
            return mIm.getModifierKeyRemapping();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Remaps a controller button.
     *
     * @param identifier The unique identifier for the device.
     * @param fromButton The controller button getting remapped. This represents the positional
     *                   code for the key.
     * @param toKeyCode The key code that it is mapped to.
     *
     * @throws IllegalArgumentException if the provided fromButton is not a valid controller button
     * @throws IllegalArgumentException if the provided toKeycode is not a valid gamepad keycode.
     * @see KeyEvent#isGamepadButton(int)
     * @hide
     */
    @TestApi
    @FlaggedApi(com.android.hardware.input.Flags.FLAG_CONTROLLER_REMAPPING)
    @RequiresPermission(Manifest.permission.CONTROLLER_REMAPPING)
    public void remapControllerButton(@NonNull InputDeviceIdentifier identifier,
            @ControllerButton int fromButton, int toKeyCode) {
        Objects.requireNonNull(identifier, "Device identifier must not be null");
        try {
            mIm.remapControllerButton(mContext.getUserId(), identifier, fromButton, toKeyCode);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Removes controller button remapping.
     *
     * @param identifier The unique identifier for the device.
     * @param fromButton The controller button getting remapped. This represents the positional code
     *                for the button.
     *
     * @throws IllegalArgumentException if the provided fromButton is not a valid controller button
     * @hide
     */
    @TestApi
    @FlaggedApi(com.android.hardware.input.Flags.FLAG_CONTROLLER_REMAPPING)
    @RequiresPermission(Manifest.permission.CONTROLLER_REMAPPING)
    public void removeControllerButtonRemapping(@NonNull InputDeviceIdentifier identifier,
            @ControllerButton int fromButton) {
        Objects.requireNonNull(identifier, "Device identifier must not be null");
        try {
            mIm.removeControllerButtonRemapping(mContext.getUserId(), identifier, fromButton);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Clears all existing controller button remappings for a given device.
     *
     * @param identifier The unique identifier for the device.
     *
     * @hide
     */
    @TestApi
    @FlaggedApi(com.android.hardware.input.Flags.FLAG_CONTROLLER_REMAPPING)
    @RequiresPermission(Manifest.permission.CONTROLLER_REMAPPING)
    public void clearAllControllerButtonRemappings(@NonNull InputDeviceIdentifier identifier) {
        Objects.requireNonNull(identifier, "Device identifier must not be null");
        try {
            mIm.clearAllControllerButtonRemappings(mContext.getUserId(), identifier);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Provides the current controller button remappings for a given device.
     *
     * @param identifier The unique identifier for the device.
     * @return a {fromButton, toKeycode} map that contains the existing controller button remapping.
     *
     * @hide
     */
    @TestApi
    @FlaggedApi(com.android.hardware.input.Flags.FLAG_CONTROLLER_REMAPPING)
    @NonNull
    @SuppressWarnings("unchecked")
    @RequiresPermission(Manifest.permission.CONTROLLER_REMAPPING)
    public Map<Integer, Integer> getControllerButtonRemappings(
            @NonNull InputDeviceIdentifier identifier) {
        Objects.requireNonNull(identifier, "Device identifier must not be null");
        try {
            return mIm.getControllerButtonRemappings(mContext.getUserId(), identifier);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Remaps a controller button to an axis.
     *
     * <p>This makes pressing the button produce motion events with the specified axis value of
     * either 0 or 1.
     *
     * <p>Only physical controller devices with {@link InputDevice#getSources()} containing both
     * {@link InputDevice#SOURCE_CLASS_JOYSTICK} and {@link InputDevice#SOURCE_CLASS_BUTTON} can be
     * remapped. Virtual controller devices are not supported.
     *
     * <p>Only <i>controller</i> buttons can be remapped. Attempt to remap non-controller buttons
     * such as keyboard or mouse buttons will fail.
     *
     * <p>If the button is not supported by the device the operation is a no-op.
     *
     * <p>If the axis is not supported by the device it starts behaving as if it is supported, i.e.
     * reported by {@link InputDevice#getMotionRange(int)} and {@link
     * InputDevice#getAxisValue(int)}.
     *
     * <p>If the button is already mapped to an axis, the existing mapping gets replaced.
     *
     * <p>The existing button to button or axis to axis remappings are both not affected by this
     * operation and in return don't affect this operation, i.e. the button will start producing
     * both key and motion events.
     *
     * <p>The mappings are <i>not</i> transitive:
     *
     * <ul>
     *   <li>If the button is mapped to axis A, and the axis A is mapped to axis B, pressing the
     *       button will produce motion events for the axis A, but not for B.
     *   <li>If button A is mapped to button B, and button B is mapped to an axis, pressing the
     *       button A will produce key events for B, but not produce any motion events for the axis.
     * </ul>
     *
     * @param identifier The unique identifier for the device.
     * @param fromButton The controller button getting remapped. This represents the positional code
     *     for the key.
     * @param toAxis The axis that it is mapped to.
     * @hide
     */
    @TestApi // Effectively hidden, useful for being tested in isolation.
    @SuppressWarnings("UnflaggedApi")
    @RequiresPermission(Manifest.permission.CONTROLLER_REMAPPING)
    public void remapControllerButtonToAxis(
            @NonNull InputDeviceIdentifier identifier,
            @ControllerButton int fromButton,
            @MotionEvent.Axis int toAxis) {
        try {
            mIm.remapControllerButtonToAxis(mContext.getUserId(), identifier, fromButton, toAxis);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Removes a controller button to axis remapping.
     *
     * <p>Stops the button from producing motion events. If the button is not mapped to an axis, the
     * operation is a no-op.
     *
     * <p>The existing button to button or axis to axis remappings are not affected.
     *
     * @param identifier The unique identifier for the device.
     * @param fromButton The controller button getting remapped. This represents the positional code
     *     for the button.
     * @throws IllegalArgumentException if the provided fromButton is not a valid controller button
     * @hide
     */
    @TestApi // Effectively hidden, useful for being tested in isolation.
    @SuppressWarnings("UnflaggedApi")
    @RequiresPermission(Manifest.permission.CONTROLLER_REMAPPING)
    public void removeControllerButtonToAxisRemapping(
            @NonNull InputDeviceIdentifier identifier, @ControllerButton int fromButton) {
        try {
            mIm.removeControllerButtonToAxisRemapping(mContext.getUserId(), identifier, fromButton);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Clears all existing controller button to axis remappings for a given device.
     *
     * <p>Semantically equivalent to calling {@link #removeControllerButtonToAxisRemapping} for all
     * possible controller buttons.
     *
     * @param identifier The unique identifier for the device.
     * @hide
     */
    @TestApi // Effectively hidden, useful for being tested in isolation.
    @SuppressWarnings("UnflaggedApi")
    @RequiresPermission(Manifest.permission.CONTROLLER_REMAPPING)
    public void clearAllControllerButtonToAxisRemappings(
            @NonNull InputDeviceIdentifier identifier) {
        try {
            mIm.clearAllControllerButtonToAxisRemappings(mContext.getUserId(), identifier);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Remaps a controller axis.
     *
     * @param identifier The unique identifier for the device.
     * @param fromAxis The controller axis getting remapped.
     * @param toAxis The controller axis that it is mapped to, or {@link MotionEvent#AXIS_DISABLED}
     *               to disable the axis.
     *
     * @throws IllegalArgumentException if the provided fromAxis or toAxis is not a valid axis
     * @hide
     */
    @TestApi
    @FlaggedApi(com.android.hardware.input.Flags.FLAG_CONTROLLER_REMAPPING)
    @RequiresPermission(Manifest.permission.CONTROLLER_REMAPPING)
    public void remapControllerAxis(@NonNull InputDeviceIdentifier identifier,
            @MotionEvent.Axis int fromAxis, @MotionEvent.Axis int toAxis) {
        Objects.requireNonNull(identifier, "Device identifier must not be null");
        try {
            mIm.remapControllerAxis(mContext.getUserId(), identifier, fromAxis, toAxis);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Removes a controller axis remapping.
     *
     * @param identifier The unique identifier for the device.
     * @param fromAxis The controller axis getting remapped.
     *
     * @throws IllegalArgumentException if the provided fromAxis is not a valid axis
     * @hide
     */
    @TestApi
    @FlaggedApi(com.android.hardware.input.Flags.FLAG_CONTROLLER_REMAPPING)
    @RequiresPermission(Manifest.permission.CONTROLLER_REMAPPING)
    public void removeControllerAxisRemapping(@NonNull InputDeviceIdentifier identifier,
            @MotionEvent.Axis int fromAxis) {
        Objects.requireNonNull(identifier, "Device identifier must not be null");
        try {
            mIm.removeControllerAxisRemapping(mContext.getUserId(), identifier, fromAxis);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Clears all existing controller axis remappings for a given device.
     *
     * @param identifier The unique identifier for the device.
     *
     * @hide
     */
    @TestApi
    @FlaggedApi(com.android.hardware.input.Flags.FLAG_CONTROLLER_REMAPPING)
    @RequiresPermission(Manifest.permission.CONTROLLER_REMAPPING)
    public void clearAllControllerAxisRemappings(@NonNull InputDeviceIdentifier identifier) {
        Objects.requireNonNull(identifier, "Device identifier must not be null");
        try {
            mIm.clearAllControllerAxisRemappings(mContext.getUserId(), identifier);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Provides the current controller axis remappings for a given device.
     *
     * @param identifier The unique identifier for the device.
     * @return a {fromAxis, toAxis} map that contains the existing controller axis remappings, where
     * fromAxis and toAxis are {@link MotionEvent.Axis}.
     *
     * @hide
     */
    @TestApi
    @FlaggedApi(com.android.hardware.input.Flags.FLAG_CONTROLLER_REMAPPING)
    @NonNull
    @SuppressWarnings("unchecked")
    @RequiresPermission(Manifest.permission.CONTROLLER_REMAPPING)
    public Map<Integer, Integer> getControllerAxisRemappings(
            @NonNull InputDeviceIdentifier identifier) {
        Objects.requireNonNull(identifier, "Device identifier must not be null");
        try {
            return mIm.getControllerAxisRemappings(mContext.getUserId(), identifier);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the TouchCalibration applied to the specified input device's coordinates.
     *
     * @param inputDeviceDescriptor The input device descriptor.
     * @return The TouchCalibration currently assigned for use with the given
     * input device. If none is set, an identity TouchCalibration is returned.
     *
     * @hide
     */
    public TouchCalibration getTouchCalibration(String inputDeviceDescriptor, int surfaceRotation) {
        try {
            return mIm.getTouchCalibrationForInputDevice(inputDeviceDescriptor, surfaceRotation);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the TouchCalibration to apply to the specified input device's coordinates.
     * <p>
     * This method may have the side-effect of causing the input device in question
     * to be reconfigured. Requires {@link android.Manifest.permission#SET_INPUT_CALIBRATION}.
     * </p>
     *
     * @param inputDeviceDescriptor The input device descriptor.
     * @param calibration The calibration to be applied
     *
     * @hide
     */
    public void setTouchCalibration(String inputDeviceDescriptor, int surfaceRotation,
            TouchCalibration calibration) {
        try {
            mIm.setTouchCalibrationForInputDevice(inputDeviceDescriptor, surfaceRotation, calibration);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the keyboard layout descriptor for the specified input device, userId, imeInfo and
     * imeSubtype.
     *
     * @param identifier Identifier for the input device
     * @param userId user profile ID
     * @param imeInfo contains IME information like imeId, etc.
     * @param imeSubtype contains IME subtype information like input languageTag, layoutType, etc.
     * @return The keyboard layout descriptor, or null if no keyboard layout has been set.
     *
     * @hide
     */
    @NonNull
    public KeyboardLayoutSelectionResult getKeyboardLayoutForInputDevice(
            @NonNull InputDeviceIdentifier identifier, @UserIdInt int userId,
            @NonNull InputMethodInfo imeInfo, @Nullable InputMethodSubtype imeSubtype) {
        try {
            return mIm.getKeyboardLayoutForInputDevice(identifier, userId, imeInfo, imeSubtype);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the keyboard layout descriptor for the specified input device, userId, imeInfo and
     * imeSubtype.
     *
     * <p>
     * This method may have the side-effect of causing the input device in question to be
     * reconfigured.
     * </p>
     *
     * @param identifier The identifier for the input device.
     * @param userId user profile ID
     * @param imeInfo contains IME information like imeId, etc.
     * @param imeSubtype contains IME subtype information like input languageTag, layoutType, etc.
     * @param keyboardLayoutDescriptor The keyboard layout descriptor to use, must not be null.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.SET_KEYBOARD_LAYOUT)
    public void setKeyboardLayoutForInputDevice(@NonNull InputDeviceIdentifier identifier,
            @UserIdInt int userId, @NonNull InputMethodInfo imeInfo,
            @Nullable InputMethodSubtype imeSubtype, @NonNull String keyboardLayoutDescriptor) {
        if (identifier == null) {
            throw new IllegalArgumentException("identifier must not be null");
        }
        if (keyboardLayoutDescriptor == null) {
            throw new IllegalArgumentException("keyboardLayoutDescriptor must not be null");
        }

        try {
            mIm.setKeyboardLayoutForInputDevice(identifier, userId, imeInfo, imeSubtype,
                    keyboardLayoutDescriptor);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets all keyboard layouts that are enabled for the specified input device, userId, imeInfo
     * and imeSubtype.
     *
     * @param identifier The identifier for the input device.
     * @param userId user profile ID
     * @param imeInfo contains IME information like imeId, etc.
     * @param imeSubtype contains IME subtype information like input languageTag, layoutType, etc.
     * @return The keyboard layout descriptors.
     *
     * @hide
     */
    public KeyboardLayout[] getKeyboardLayoutListForInputDevice(InputDeviceIdentifier identifier,
            @UserIdInt int userId, @NonNull InputMethodInfo imeInfo,
            @Nullable InputMethodSubtype imeSubtype) {
        if (identifier == null) {
            throw new IllegalArgumentException("inputDeviceDescriptor must not be null");
        }

        try {
            return mIm.getKeyboardLayoutListForInputDevice(identifier, userId, imeInfo, imeSubtype);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the mouse pointer speed.
     *
     * <p>The pointer speed is a value between {@link InputSettings#MIN_POINTER_SPEED} and
     * {@link InputSettings#MAX_POINTER_SPEED}, the default value being
     * {@link InputSettings#DEFAULT_POINTER_SPEED}.
     *
     * <p> Note that while setting the mouse pointer speed, it's possible that the input reader has
     * only received this value and has not yet completed reconfiguring itself with this value.
     *
     * @hide
     */
    @SuppressLint("UnflaggedApi") // TestApi without associated feature.
    @TestApi
    public int getMousePointerSpeed() {
        try {
            return mIm.getMousePointerSpeed();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Changes the mouse pointer speed temporarily, but does not save the setting.
     * <p>
     * Requires {@link android.Manifest.permission#SET_POINTER_SPEED}.
     * </p>
     *
     * @param speed The pointer speed as a value between {@link InputSettings#MIN_POINTER_SPEED} and
     * {@link InputSettings#MAX_POINTER_SPEED}, or the default value {@link InputSettings#DEFAULT_POINTER_SPEED}.
     *
     * @hide
     */
    public void tryPointerSpeed(int speed) {
        if (speed < InputSettings.MIN_POINTER_SPEED || speed > InputSettings.MAX_POINTER_SPEED) {
            throw new IllegalArgumentException("speed out of range");
        }

        try {
            mIm.tryPointerSpeed(speed);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the maximum allowed obscuring opacity per UID to propagate touches.
     *
     * <p>For certain window types (eg. {@link LayoutParams#TYPE_APPLICATION_OVERLAY}), the decision
     * of honoring {@link LayoutParams#FLAG_NOT_TOUCHABLE} or not depends on the combined obscuring
     * opacity of the windows above the touch-consuming window, per UID. Check documentation of
     * {@link LayoutParams#FLAG_NOT_TOUCHABLE} for more details.
     *
     * <p>The value returned is between 0 (inclusive) and 1 (inclusive).
     *
     * @see LayoutParams#FLAG_NOT_TOUCHABLE
     */
    @FloatRange(from = 0, to = 1)
    public float getMaximumObscuringOpacityForTouch() {
        return InputSettings.getMaximumObscuringOpacityForTouch(mContext);
    }

    /**
     * Queries the framework about whether any physical keys exist on any currently attached input
     * devices that are capable of producing the given array of key codes.
     *
     * @param keyCodes The array of key codes to query.
     * @return A new array of the same size as the key codes array whose elements
     * are set to true if at least one attached keyboard supports the corresponding key code
     * at the same index in the key codes array.
     *
     * @hide
     */
    public boolean[] deviceHasKeys(int[] keyCodes) {
        return deviceHasKeys(-1, keyCodes);
    }

    /**
     * Queries the framework about whether any physical keys exist on the specified input device
     * that are capable of producing the given array of key codes.
     *
     * @param id The id of the input device to query or -1 to consult all devices.
     * @param keyCodes The array of key codes to query.
     * @return A new array of the same size as the key codes array whose elements are set to true
     * if the given device could produce the corresponding key code at the same index in the key
     * codes array.
     *
     * @hide
     */
    public boolean[] deviceHasKeys(int id, int[] keyCodes) {
        return mGlobal.deviceHasKeys(id, keyCodes);
    }

    /**
     * Gets the {@link android.view.KeyEvent key code} produced by the given location on a reference
     * QWERTY keyboard layout.
     * <p>
     * This API is useful for querying the physical location of keys that change the character
     * produced based on the current locale and keyboard layout.
     * <p>
     * @see InputDevice#getKeyCodeForKeyLocation(int) for examples.
     *
     * @param locationKeyCode The location of a key specified as a key code on the QWERTY layout.
     * This provides a consistent way of referring to the physical location of a key independently
     * of the current keyboard layout. Also see the
     * <a href="https://www.w3.org/TR/2017/CR-uievents-code-20170601/#key-alphanumeric-writing-system">
     * hypothetical keyboard</a> provided by the W3C, which may be helpful for identifying the
     * physical location of a key.
     * @return The key code produced by the key at the specified location, given the current
     * keyboard layout. Returns {@link KeyEvent#KEYCODE_UNKNOWN} if the device does not specify
     * {@link InputDevice#SOURCE_KEYBOARD} or the requested mapping cannot be determined.
     *
     * @hide
     */
    public int getKeyCodeForKeyLocation(int deviceId, int locationKeyCode) {
        return mGlobal.getKeyCodeForKeyLocation(deviceId, locationKeyCode);
    }

    /**
     * Provides a Keyboard layout preview of a particular dimension.
     *
     * @param keyboardLayout Layout whose preview is requested. If null, will return preview of
     *                       the default Keyboard layout defined by {@code Generic.kl}.
     * @param width Expected width of the drawable
     * @param height Expected height of the drawable
     *
     * NOTE: Width and height will auto-adjust to the width and height of the ImageView that
     * shows the drawable but this allows the caller to provide an intrinsic width and height of
     * the drawable allowing the ImageView to properly wrap the drawable content.
     *
     * @hide
     */
    @Nullable
    public Drawable getKeyboardLayoutPreview(@Nullable KeyboardLayout keyboardLayout, int width,
            int height) {
        PhysicalKeyLayout keyLayout = new PhysicalKeyLayout(
                mGlobal.getKeyCharacterMap(keyboardLayout), keyboardLayout);
        return new KeyboardLayoutPreviewDrawable(mContext, keyLayout, width, height);
    }

    /**
     * Provides associated glyph map for the keyboard device (if available)
     *
     * @hide
     */
    @Nullable
    public KeyGlyphMap getKeyGlyphMap(int deviceId) {
        if (!keyboardGlyphMap()) {
            return null;
        }
        try {
            return mIm.getKeyGlyphMap(deviceId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Injects an input event into the event system, targeting windows owned by the provided uid.
     *
     * If a valid targetUid is provided, the system will only consider injecting the input event
     * into windows owned by the provided uid. If the input event is targeted at a window that is
     * not owned by the provided uid, input injection will fail and a RemoteException will be
     * thrown.
     *
     * The synchronization mode determines whether the method blocks while waiting for
     * input injection to proceed.
     * <p>
     * Requires the {@link android.Manifest.permission#INJECT_EVENTS} permission.
     * </p><p>
     * Make sure you correctly set the event time and input source of the event
     * before calling this method.
     * </p>
     *
     * @param event The event to inject.
     * @param mode The synchronization mode.  One of:
     * {@link android.os.InputEventInjectionSync#NONE},
     * {@link android.os.InputEventInjectionSync#WAIT_FOR_RESULT}, or
     * {@link android.os.InputEventInjectionSync#WAIT_FOR_FINISHED}.
     * @param targetUid The uid to target, or {@link android.os.Process#INVALID_UID} to target all
     *                 windows.
     * @return True if input event injection succeeded.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.INJECT_EVENTS)
    public boolean injectInputEvent(InputEvent event, int mode, int targetUid) {
        return mGlobal.injectInputEvent(event, mode, targetUid);
    }

    /**
     * Returns a {@link VirtualKeyboard} to the caller.
     * See {@link android.hardware.input.VirtualKeyboardConfig} for additional configurations
     * available, e.g. display association, layout, and language.
     *
     * @param config the keyboard configuration
     * @return VirtualKeyboard a virtual keyboard device
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            Manifest.permission.INJECT_KEY_EVENTS,
            Manifest.permission.INJECT_EVENTS
    })
    @FlaggedApi(com.android.hardware.input.Flags.FLAG_CREATE_VIRTUAL_KEYBOARD_API)
    @NonNull
    public VirtualKeyboard createVirtualKeyboard(@NonNull VirtualKeyboardConfig config) {
        return mGlobal.createVirtualKeyboard(config);
    }

    /**
     * Returns a {@link VirtualGamepad} to the caller.
     * See {@link android.hardware.input.VirtualGamepadConfig} for additional configurations
     * available, e.g. display association, vendor id, product id, device name.
     *
     * @param config the gamepad configuration
     * @return VirtualGamepad a virtual gamepad device
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.INJECT_EVENTS)
    @NonNull
    public VirtualGamepad createVirtualGamepad(@NonNull VirtualGamepadConfig config) {
        return mGlobal.createVirtualGamepad(config);
    }

    /**
     * Returns a {@link VirtualMouse} to the caller.
     * See {@link android.hardware.input.VirtualMouseConfig} for additional configurations
     * available, e.g. display association, vendor id, product id, device name.
     *
     * @param config the mouse configuration
     * @return VirtualMouse a virtual mouse device
     *
     * @hide
     */
    @TestApi
    @SuppressLint("UnflaggedApi") // @TestApi without associated feature.
    @RequiresPermission(Manifest.permission.INJECT_EVENTS)
    @NonNull
    public VirtualMouse createVirtualMouse(@NonNull VirtualMouseConfig config) {
        return mGlobal.createVirtualMouse(config);
    }

    /**
     * Injects an input event into the event system on behalf of an application.
     * The synchronization mode determines whether the method blocks while waiting for
     * input injection to proceed.
     * <p>
     * Requires the {@link android.Manifest.permission#INJECT_EVENTS} permission.
     * </p><p>
     * Make sure you correctly set the event time and input source of the event
     * before calling this method.
     * </p>
     *
     * @param event The event to inject.
     * @param mode The synchronization mode.  One of:
     * {@link android.os.InputEventInjectionSync#NONE},
     * {@link android.os.InputEventInjectionSync#WAIT_FOR_RESULT}, or
     * {@link android.os.InputEventInjectionSync#WAIT_FOR_FINISHED}.
     * @return True if input event injection succeeded.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.INJECT_EVENTS)
    @UnsupportedAppUsage
    public boolean injectInputEvent(InputEvent event, int mode) {
        return mGlobal.injectInputEvent(event, mode);
    }

    /**
     * Verify the details of an {@link android.view.InputEvent} that came from the system.
     * If the event did not come from the system, or its details could not be verified, then this
     * will return {@code null}. Receiving {@code null} does not mean that the event did not
     * originate from the system, just that we were unable to verify it. This can
     * happen for a number of reasons during normal operation.
     *
     * @param event The {@link android.view.InputEvent} to check.
     *
     * @return {@link android.view.VerifiedInputEvent}, which is a subset of the provided
     *     {@link android.view.InputEvent}, or {@code null} if the event could not be verified.
     */
    @Nullable
    public VerifiedInputEvent verifyInputEvent(@NonNull InputEvent event) {
        try {
            return mIm.verifyInputEvent(event);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * This method exists for backwards-compatibility, and is a no-op.
     *
     * @deprecated
     * @hide
     */
    @UnsupportedAppUsage
    public void setPointerIconType(int iconId) {
        Log.e(TAG, "setPointerIcon: Unsupported app usage!");
    }

    /** @hide */
    public boolean setPointerIcon(PointerIcon icon, int displayId, int deviceId, int pointerId,
            IBinder inputToken) {
        return mGlobal.setPointerIcon(icon, displayId, deviceId, pointerId, inputToken);
    }

    /**
     * Check if showing a {@link android.view.PointerIcon} for styluses is enabled.
     *
     * @return true if a pointer icon will be shown over the location of a
     * stylus pointer, false if there is no pointer icon shown for styluses.
     */
    public boolean isStylusPointerIconEnabled() {
        if (mIsStylusPointerIconEnabled == null) {
            mIsStylusPointerIconEnabled = InputSettings.isStylusPointerIconEnabled(mContext);
        }
        return mIsStylusPointerIconEnabled;
    }

    /**
     * Request or release pointer capture.
     * <p>
     * When in capturing mode, the pointer icon disappears and all mouse and touchpad events are
     * dispatched to the window which has requested the capture.
     *
     * @param mode the capture mode to request.
     *
     * @see View#requestPointerCapture()
     *
     * @hide
     */
    public void requestPointerCapture(@NonNull IBinder windowToken, @PointerCaptureMode int mode) {
        mGlobal.requestPointerCapture(windowToken, mode);
    }

    /**
     * Monitor input on the specified display for gestures.
     *
     * NOTE: New usages of Gesture Monitors are strongly discouraged. Gesture Monitors are
     * deprecated, in favor of spy windows (see {@link LayoutParams#INPUT_FEATURE_SPY}).
     * The spy window should be configured specifically to receive the desired events,
     * unlike the gesture monitor which receives all events on the display.
     *
     * @hide
     * @deprecated
     * @see LayoutParams#INPUT_FEATURE_SPY
     * @see android.os.InputConfig#SPY
     * @see #pilferPointers(IBinder)
     */
    @Deprecated
    public InputMonitor monitorGestureInput(String name, int displayId) {
        return mGlobal.monitorGestureInput(name, displayId);
    }

    /**
     * Add a runtime association between the input port and the display port. This overrides any
     * static associations.
     * @param inputPort the port of the input device
     * @param displayPort the physical port of the associated display
     * <p>
     * Requires {@link android.Manifest.permission#ASSOCIATE_INPUT_DEVICE_TO_DISPLAY}.
     * </p>
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.ASSOCIATE_INPUT_DEVICE_TO_DISPLAY)
    public void addPortAssociation(@NonNull String inputPort, int displayPort) {
        try {
            mIm.addPortAssociation(inputPort, displayPort);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Remove the runtime association between the input port and the display port. Any existing
     * static association for the cleared input port will be restored.
     * @param inputPort the port of the input device to be cleared
     * <p>
     * Requires {@link android.Manifest.permission#ASSOCIATE_INPUT_DEVICE_TO_DISPLAY}.
     * </p>
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.ASSOCIATE_INPUT_DEVICE_TO_DISPLAY)
    public void removePortAssociation(@NonNull String inputPort) {
        try {
            mIm.removePortAssociation(inputPort);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Add a runtime association between the input port and display, by unique id. Input ports are
     * expected to be unique.
     * @param inputPort the port of the input device
     * @param displayUniqueId the unique id of the associated display
     * <p>
     * Requires {@link android.Manifest.permission#ASSOCIATE_INPUT_DEVICE_TO_DISPLAY}.
     * </p>
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.ASSOCIATE_INPUT_DEVICE_TO_DISPLAY)
    @TestApi
    public void addUniqueIdAssociationByPort(@NonNull String inputPort,
            @NonNull String displayUniqueId) {
        mGlobal.addUniqueIdAssociationByPort(inputPort, displayUniqueId);
    }

    /**
     * Removes a runtime association between the input device and display.
     * @param inputPort the port of the input device
     * <p>
     * Requires {@link android.Manifest.permission#ASSOCIATE_INPUT_DEVICE_TO_DISPLAY}.
     * </p>
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.ASSOCIATE_INPUT_DEVICE_TO_DISPLAY)
    @TestApi
    public void removeUniqueIdAssociationByPort(@NonNull String inputPort) {
        mGlobal.removeUniqueIdAssociationByPort(inputPort);
    }

    /**
     * Add a runtime association between the input device name and display, by descriptor. Input
     * device descriptors are expected to be unique per physical device, though one physical
     * device can have multiple virtual input devices that possess the same descriptor.
     * E.g. a keyboard with built in trackpad will be 2 different input devices with the same
     * descriptor.
     * @param inputDeviceDescriptor the descriptor of the input device
     * @param displayUniqueId the unique id of the associated display
     * <p>
     * Requires {@link android.Manifest.permissions.ASSOCIATE_INPUT_DEVICE_TO_DISPLAY}.
     * </p>
     * @hide
     */
    @FlaggedApi(FLAG_DEVICE_ASSOCIATIONS)
    @RequiresPermission(android.Manifest.permission.ASSOCIATE_INPUT_DEVICE_TO_DISPLAY)
    @TestApi
    public void addUniqueIdAssociationByDescriptor(@NonNull String inputDeviceDescriptor,
                                                   @NonNull String displayUniqueId) {
        mGlobal.addUniqueIdAssociationByDescriptor(inputDeviceDescriptor, displayUniqueId);
    }

    /**
     * Removes a runtime association between the input device and display.
    }

    /**
     * Removes a runtime association between the input device and display.
     * @param inputDeviceDescriptor the descriptor of the input device
     * <p>
     * Requires {@link android.Manifest.permissions.ASSOCIATE_INPUT_DEVICE_TO_DISPLAY}.
     * </p>
     * @hide
     */
    @FlaggedApi(FLAG_DEVICE_ASSOCIATIONS)
    @RequiresPermission(android.Manifest.permission.ASSOCIATE_INPUT_DEVICE_TO_DISPLAY)
    @TestApi
    public void removeUniqueIdAssociationByDescriptor(@NonNull String inputDeviceDescriptor) {
        mGlobal.removeUniqueIdAssociationByDescriptor(inputDeviceDescriptor);
    }

    /**
     * Reports the version of the Universal Stylus Initiative (USI) protocol supported by the given
     * display, if any.
     *
     * @return the USI version supported by the display, or null if the device does not support USI
     * @see <a href="https://universalstylus.org">Universal Stylus Initiative</a>
     */
    @Nullable
    public HostUsiVersion getHostUsiVersion(@NonNull Display display) {
        return mGlobal.getHostUsiVersion(display);
    }

    /**
     * Returns the Bluetooth address of this input device, if known.
     *
     * The returned string is always null if this input device is not connected
     * via Bluetooth, or if the Bluetooth address of the device cannot be
     * determined. The returned address will look like: "11:22:33:44:55:66".
     * @hide
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    @Nullable
    public String getInputDeviceBluetoothAddress(int deviceId) {
        return mGlobal.getInputDeviceBluetoothAddress(deviceId);
    }

    /**
     * Gets a vibrator service associated with an input device, always creates a new instance.
     * @return The vibrator, never null.
     * @hide
     */
    public Vibrator getInputDeviceVibrator(int deviceId, int vibratorId) {
        return new InputDeviceVibrator(deviceId, vibratorId);
    }

    /**
     * Cancel all ongoing pointer gestures on all displays.
     * @hide
     */
    public void cancelCurrentTouch() {
        mGlobal.cancelCurrentTouch();
    }

    /**
     * Pilfer pointers from an input channel.
     *
     * Takes all the current pointer event streams that are currently being sent to the given
     * input channel and generates appropriate cancellations for all other windows that are
     * receiving these pointers.
     *
     * This API is intended to be used in conjunction with spy windows. When a spy window pilfers
     * pointers, the foreground windows and all other spy windows that are receiving any of the
     * pointers that are currently being dispatched to the pilfering window will have those pointers
     * canceled. Only the pilfering window will continue to receive events for the affected pointers
     * until the pointer is lifted.
     *
     * Furthermore, if any new pointers go down within the touchable region of the pilfering window
     * and are part of the same gesture, those new pointers will be pilfered as well, and will not
     * be sent to any other windows.
     *
     * Pilfering is designed to be used only once per gesture. Once the gesture is complete
     * (i.e. on {@link MotionEvent#ACTION_UP}, {@link MotionEvent#ACTION_CANCEL},
     * or {@link MotionEvent#ACTION_HOVER_EXIT}), the system will resume dispatching pointers
     * to the appropriately touched windows.
     *
     * NOTE: This method should be used with caution as unexpected pilfering can break fundamental
     * user interactions.
     *
     * NOTE: Since this method pilfers pointers based on gesture stream that is
     * currently active for the window, the behavior will depend on the state of the system, and
     * is inherently racy. For example, a pilfer request on a quick tap may not be successful if
     * the tap is already complete by the time the pilfer request is received by the system.
     *
     * @see android.os.InputConfig#SPY
     * @hide
     */
    @RequiresPermission(Manifest.permission.MONITOR_INPUT)
    public void pilferPointers(IBinder inputChannelToken) {
        mGlobal.pilferPointers(inputChannelToken);
    }

    /**
     * Adds a battery listener to be notified about {@link BatteryState} changes for an input
     * device. The same listener can be registered for multiple input devices.
     * The listener will be notified of the initial battery state of the device after it is
     * successfully registered.
     * @param deviceId the input device that should be monitored
     * @param executor an executor on which the callback will be called
     * @param listener the {@link InputDeviceBatteryListener}
     * @see #removeInputDeviceBatteryListener(int, InputDeviceBatteryListener)
     * @hide
     */
    public void addInputDeviceBatteryListener(int deviceId, @NonNull Executor executor,
            @NonNull InputDeviceBatteryListener listener) {
        mGlobal.addInputDeviceBatteryListener(deviceId, executor, listener);
    }

    /**
     * Removes a previously registered battery listener for an input device.
     * @see #addInputDeviceBatteryListener(int, Executor, InputDeviceBatteryListener)
     * @hide
     */
    public void removeInputDeviceBatteryListener(int deviceId,
            @NonNull InputDeviceBatteryListener listener) {
        mGlobal.removeInputDeviceBatteryListener(deviceId, listener);
    }

    /**
     * Whether there is a gesture-compatible touchpad connected to the device.
     * @hide
     */
    public boolean areTouchpadGesturesAvailable(@NonNull Context context) {
        // TODO: implement the right logic
        return true;
    }

    /**
     * Registers a Keyboard backlight change listener to be notified about {@link
     * KeyboardBacklightState} changes for connected keyboard devices.
     *
     * @param executor an executor on which the callback will be called
     * @param listener the {@link KeyboardBacklightListener}
     * @hide
     * @see #unregisterKeyboardBacklightListener(KeyboardBacklightListener)
     * @throws IllegalArgumentException if {@code listener} has already been registered previously.
     * @throws NullPointerException if {@code listener} or {@code executor} is null.
     */
    @RequiresPermission(Manifest.permission.MONITOR_KEYBOARD_BACKLIGHT)
    public void registerKeyboardBacklightListener(@NonNull Executor executor,
            @NonNull KeyboardBacklightListener listener) throws IllegalArgumentException {
        mGlobal.registerKeyboardBacklightListener(executor, listener);
    }

    /**
     * Unregisters a previously added Keyboard backlight change listener.
     *
     * @param listener the {@link KeyboardBacklightListener}
     * @see #registerKeyboardBacklightListener(Executor, KeyboardBacklightListener)
     * @hide
     */
    @RequiresPermission(Manifest.permission.MONITOR_KEYBOARD_BACKLIGHT)
    public void unregisterKeyboardBacklightListener(
            @NonNull KeyboardBacklightListener listener) {
        mGlobal.unregisterKeyboardBacklightListener(listener);
    }

    /**
     * Registers a Sticky modifier state change listener to be notified about {@link
     * StickyModifierState} changes.
     *
     * @param executor an executor on which the callback will be called
     * @param listener the {@link StickyModifierStateListener}
     * @throws IllegalArgumentException if {@code listener} has already been registered previously.
     * @throws NullPointerException     if {@code listener} or {@code executor} is null.
     * @hide
     * @see #unregisterStickyModifierStateListener(StickyModifierStateListener)
     */
    @RequiresPermission(Manifest.permission.MONITOR_STICKY_MODIFIER_STATE)
    public void registerStickyModifierStateListener(@NonNull Executor executor,
            @NonNull StickyModifierStateListener listener) throws IllegalArgumentException {
        mGlobal.registerStickyModifierStateListener(executor, listener);
    }

    /**
     * Unregisters a previously added Sticky modifier state change listener.
     *
     * @param listener the {@link StickyModifierStateListener}
     * @hide
     * @see #registerStickyModifierStateListener(Executor, StickyModifierStateListener)
     */
    @RequiresPermission(Manifest.permission.MONITOR_STICKY_MODIFIER_STATE)
    public void unregisterStickyModifierStateListener(
            @NonNull StickyModifierStateListener listener) {
        mGlobal.unregisterStickyModifierStateListener(listener);
    }

    /**
     * Registers a key gesture event listener for {@link KeyGestureEvent} being triggered.
     *
     * @param executor an executor on which the callback will be called
     * @param listener the {@link KeyGestureEventListener}
     * @throws IllegalArgumentException if {@code listener} has already been registered previously.
     * @throws NullPointerException     if {@code listener} or {@code executor} is null.
     * @hide
     * @see #unregisterKeyGestureEventListener(KeyGestureEventListener)
     */
    @RequiresPermission(Manifest.permission.MANAGE_KEY_GESTURES)
    public void registerKeyGestureEventListener(@NonNull Executor executor,
            @NonNull KeyGestureEventListener listener) throws IllegalArgumentException {
        mGlobal.registerKeyGestureEventListener(executor, listener);
    }

    /**
     * Unregisters a previously added key gesture event listener.
     *
     * @param listener the {@link KeyGestureEventListener}
     * @hide
     * @see #registerKeyGestureEventListener(Executor, KeyGestureEventListener)
     */
    @RequiresPermission(Manifest.permission.MANAGE_KEY_GESTURES)
    public void unregisterKeyGestureEventListener(@NonNull KeyGestureEventListener listener) {
        mGlobal.unregisterKeyGestureEventListener(listener);
    }

    /**
     * Registers a key gesture event handler for {@link KeyGestureEvent} handling.
     *
     * @param keyGesturesToHandle list of KeyGestureTypes to listen to
     * @param handler the {@link KeyGestureEventHandler}
     * @throws IllegalArgumentException if {@code handler} has already been registered previously
     * or key gestures provided are already registered by some other gesture handler.
     * @throws NullPointerException     if {@code handler} or {@code executor} is null.
     * @hide
     * @see #unregisterKeyGestureEventHandler(KeyGestureEventHandler)
     */
    @RequiresPermission(Manifest.permission.MANAGE_KEY_GESTURES)
    public void registerKeyGestureEventHandler(List<Integer> keyGesturesToHandle,
            @NonNull KeyGestureEventHandler handler) throws IllegalArgumentException {
        mGlobal.registerKeyGestureEventHandler(keyGesturesToHandle, handler);
    }

    /**
     * Unregisters a previously added key gesture event handler.
     *
     * @param handler the {@link KeyGestureEventHandler}
     * @hide
     * @see #registerKeyGestureEventHandler(List, KeyGestureEventHandler)
     */
    @RequiresPermission(Manifest.permission.MANAGE_KEY_GESTURES)
    public void unregisterKeyGestureEventHandler(@NonNull KeyGestureEventHandler handler) {
        mGlobal.unregisterKeyGestureEventHandler(handler);
    }

    /**
     * Find an input gesture mapped to a particular trigger.
     *
     * @param trigger to find the input gesture for
     * @return input gesture mapped to the provided trigger, {@code null} if none found
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_KEY_GESTURES)
    @UserHandleAware
    @Nullable
    public InputGestureData getInputGesture(@NonNull InputGestureData.Trigger trigger) {
        try {
            AidlInputGestureData result = mIm.getInputGesture(mContext.getUserId(),
                    trigger.getAidlTrigger());
            if (result == null) {
                return null;
            }
            return new InputGestureData(result);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Adds a new custom input gesture
     *
     * @param inputGestureData gesture data to add as custom gesture
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_KEY_GESTURES)
    @CustomInputGestureResult
    @UserHandleAware
    public int addCustomInputGesture(@NonNull InputGestureData inputGestureData) {
        if (!enableCustomizableInputGestures()) {
            return CUSTOM_INPUT_GESTURE_RESULT_ERROR_OTHER;
        }
        try {
            return mIm.addCustomInputGesture(mContext.getUserId(), inputGestureData.getAidlData());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Removes an existing custom gesture
     *
     * <p> NOTE: Should not be used to remove system gestures. This API is only to be used to
     * remove gestures added using {@link #addCustomInputGesture(InputGestureData)}
     *
     * @param inputGestureData gesture data for the existing custom gesture to remove
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_KEY_GESTURES)
    @CustomInputGestureResult
    @UserHandleAware
    public int removeCustomInputGesture(@NonNull InputGestureData inputGestureData) {
        if (!enableCustomizableInputGestures()) {
            return CUSTOM_INPUT_GESTURE_RESULT_ERROR_OTHER;
        }
        try {
            return mIm.removeCustomInputGesture(mContext.getUserId(),
                    inputGestureData.getAidlData());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Removes all custom input gestures
     *
     * @param filter for removing all gestures of a category. If {@code null}, all custom input
     *               gestures will be removed
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_KEY_GESTURES)
    @UserHandleAware
    public void removeAllCustomInputGestures(@Nullable InputGestureData.Filter filter) {
        if (!enableCustomizableInputGestures()) {
            return;
        }
        try {
            mIm.removeAllCustomInputGestures(mContext.getUserId(),
                    filter == null ? -1 : filter.getTag());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Get all custom input gestures
     *
     * @param filter for fetching all gestures of a category. If {@code null}, then will return
     *               all custom input gestures
     *
     * @hide
     */
    @UserHandleAware
    public List<InputGestureData> getCustomInputGestures(@Nullable InputGestureData.Filter filter) {
        List<InputGestureData> result = new ArrayList<>();
        if (!enableCustomizableInputGestures()) {
            return result;
        }
        try {
            for (AidlInputGestureData data : mIm.getCustomInputGestures(mContext.getUserId(),
                    filter == null ? -1 : filter.getTag())) {
                result.add(new InputGestureData(data));
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return result;
    }

    /**
     * Return the set of application launch bookmarks handled by the input framework.
     *
     * @return list of {@link InputGestureData} containing the application launch shortcuts parsed
     * at boot time from {@code bookmarks.xml}.
     *
     * @hide
     */
    public List<InputGestureData> getAppLaunchBookmarks() {
        try {
            List<InputGestureData> result = new ArrayList<>();
            for (AidlInputGestureData data : mIm.getAppLaunchBookmarks()) {
                result.add(new InputGestureData(data));
            }
            return result;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Resets locked modifier state (i.e.. Caps Lock, Num Lock, Scroll Lock state)
     *
     * @hide
     */
    @TestApi
    @SuppressLint("UnflaggedApi") // @TestApi without associated feature.
    public void resetLockedModifierState() {
        try {
            mIm.resetLockedModifierState();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set whether all pointer scaling, including linear scaling based on the
     * user's pointer speed setting, should be enabled or disabled for mice.
     *
     * Note that this only affects pointer movements from mice (that is, pointing devices which send
     * relative motions, including trackballs and pointing sticks), not from other pointer devices
     * such as touchpads and styluses.
     *
     * Scaling is enabled by default on new displays until it is explicitly disabled.
     * @hide
     */
    @TestApi
    @SuppressLint("UnflaggedApi") // @TestApi without associated feature.
    @RequiresPermission(Manifest.permission.SET_POINTER_SPEED)
    public void setMouseScalingEnabled(boolean enabled, int displayId) {
        try {
            mIm.setMouseScalingEnabled(enabled, displayId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the current position of the mouse cursor on the specified display.
     *
     * <p>Returned values are in logical display coordinates in pixels.
     *
     * <p>Returns null if no cursor is available, or if existing cursor is not on the supplied
     * `displayId`.
     *
     * <p>This method is inherently racy, and should only be used for test purposes.
     * @hide
     */
    @TestApi
    @SuppressLint("UnflaggedApi") // @TestApi without associated feature.
    @RequiresPermission(Manifest.permission.INJECT_EVENTS)
    @Nullable
    public PointF getCursorPosition(int displayId) {
        try {
            return mIm.getCursorPositionInLogicalDisplay(displayId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * A callback used to be notified about battery state changes for an input device. The
     * {@link #onBatteryStateChanged(int, long, BatteryState)} method will be called once after the
     * listener is successfully registered to provide the initial battery state of the device.
     * @see InputDevice#getBatteryState()
     * @see #addInputDeviceBatteryListener(int, Executor, InputDeviceBatteryListener)
     * @see #removeInputDeviceBatteryListener(int, InputDeviceBatteryListener)
     * @hide
     */
    public interface InputDeviceBatteryListener {
        /**
         * Called when the battery state of an input device changes.
         * @param deviceId the input device for which the battery changed.
         * @param eventTimeMillis the time (in ms) when the battery change took place.
         *        This timestamp is in the {@link SystemClock#uptimeMillis()} time base.
         * @param batteryState the new battery state, never null.
         */
        void onBatteryStateChanged(
                int deviceId, long eventTimeMillis, @NonNull BatteryState batteryState);
    }

    /**
     * Listens for changes in input devices.
     */
    public interface InputDeviceListener {
        /**
         * Called whenever an input device has been added to the system.
         * Use {@link #getInputDevice(int)} to get more information about the device.
         *
         * @param deviceId The id of the input device that was added.
         */
        void onInputDeviceAdded(int deviceId);

        /**
         * Called whenever an input device has been removed from the system.
         *
         * @param deviceId The id of the input device that was removed.
         */
        void onInputDeviceRemoved(int deviceId);

        /**
         * Called whenever the properties of an input device have changed since they
         * were last queried.  Use {@link InputManager#getInputDevice} to get
         * a fresh {@link InputDevice} object with the new properties.
         *
         * @param deviceId The id of the input device that changed.
         */
        void onInputDeviceChanged(int deviceId);
    }

    /** @hide */
    public interface OnTabletModeChangedListener {
        /**
         * Called whenever the device goes into or comes out of tablet mode.
         *
         * @param whenNanos The time at which the device transitioned into or
         * out of tablet mode. This is given in nanoseconds in the
         * {@link SystemClock#uptimeMillis} time base.
         */
        void onTabletModeChanged(long whenNanos, boolean inTabletMode);
    }

    /**
     * A callback used to be notified about keyboard backlight state changes for keyboard device.
     * The {@link #onKeyboardBacklightChanged(int, KeyboardBacklightState, boolean)} method
     * will be called once after the listener is successfully registered to provide the initial
     * keyboard backlight state of the device.
     * @see #registerKeyboardBacklightListener(Executor, KeyboardBacklightListener)
     * @see #unregisterKeyboardBacklightListener(KeyboardBacklightListener)
     * @hide
     */
    public interface KeyboardBacklightListener {
        /**
         * Called when the keyboard backlight brightness level changes.
         * @param deviceId the keyboard for which the backlight brightness changed.
         * @param state the new keyboard backlight state, never null.
         * @param isTriggeredByKeyPress whether brightness change was triggered by the user
         *                              pressing up/down key on the keyboard.
         */
        void onKeyboardBacklightChanged(
                int deviceId, @NonNull KeyboardBacklightState state, boolean isTriggeredByKeyPress);
    }

    /**
     * A callback used to be notified about sticky modifier state changes when A11y Sticky keys
     * feature is enabled.
     *
     * @see #registerStickyModifierStateListener(Executor, StickyModifierStateListener)
     * @see #unregisterStickyModifierStateListener(StickyModifierStateListener)
     * @hide
     */
    public interface StickyModifierStateListener {
        /**
         * Called when the sticky modifier state changes.
         * This method will be called once after the listener is successfully registered to provide
         * the initial modifier state.
         *
         * @param state the new sticky modifier state, never null.
         */
        void onStickyModifierStateChanged(@NonNull StickyModifierState state);
    }

    /**
     * A callback used to notify about key gesture event on completion.
     *
     * @see #registerKeyGestureEventListener(Executor, KeyGestureEventListener)
     * @see #unregisterKeyGestureEventListener(KeyGestureEventListener)
     * @hide
     */
    public interface KeyGestureEventListener {
        /**
         * Called when a key gesture event occurs.
         *
         * @param event the gesture event that occurred.
         */
        void onKeyGestureEvent(@NonNull KeyGestureEvent event);
    }

    /**
     * A callback used to notify about key gesture event start, complete and cancel. Unlike
     * {@see KeyGestureEventListener} which is to listen to successfully handled key gestures, this
     * interface allows system components to register handler for handling key gestures.
     *
     * @see #registerKeyGestureEventHandler(List, KeyGestureEventHandler)
     * @see #unregisterKeyGestureEventHandler(KeyGestureEventHandler)
     *
     * <p> NOTE: All callbacks will occur on system main and input threads, so the caller needs
     * to move time-consuming operations to appropriate handler threads.
     * @hide
     */
    public interface KeyGestureEventHandler {
        /**
         * Called when a key gesture event starts, is completed, or is cancelled.
         *
         * @param event the gesture event
         */
        void handleKeyGestureEvent(@NonNull KeyGestureEvent event, @Nullable IBinder focusedToken);
    }

    /** @hide */
    public interface KeyEventActivityListener {
        /**
         * Reports a change for user activeness.
         *
         * This listener will be triggered any time a user presses a key.
         */
        void onKeyEventActivity();
    }


    /**
     * Registers a listener for updates to key event activeness
     *
     * @param listener to be registered
     * @return true if listener registered successfully
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.LISTEN_FOR_KEY_ACTIVITY)
    public boolean registerKeyEventActivityListener(@NonNull KeyEventActivityListener listener) {
        return mGlobal.registerKeyEventActivityListener(listener);
    }

    /**
     * Unregisters a listener for updates to key event activeness
     *
     * @param listener to be unregistered
     * @return true if listener unregistered successfully, also returns true if
     * invoked but listener was not present
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.LISTEN_FOR_KEY_ACTIVITY)
    public boolean unregisterKeyEventActivityListener(@NonNull KeyEventActivityListener listener) {
        return mGlobal.unregisterKeyEventActivityListener(listener);
    }
}
