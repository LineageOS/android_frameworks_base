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

package com.android.server.companion.virtual;

import static android.companion.virtual.VirtualDevice.DEVICE_PROFILE_SHELL;

import android.annotation.Nullable;
import android.annotation.RequiresNoPermission;
import android.app.KeyguardManager;
import android.companion.virtual.VirtualDeviceParams;
import android.content.AttributionSource;
import android.hardware.display.DisplayManager;
import android.hardware.display.IVirtualDisplayCallback;
import android.hardware.display.VirtualDisplayConfig;
import android.media.ImageReader;
import android.os.Binder;
import android.os.Process;
import android.os.ShellCommand;
import android.view.Display;

import java.io.PrintWriter;

class VirtualDeviceShellCommand extends ShellCommand {

    private static final int DISPLAY_WIDTH = 960;
    private static final int DISPLAY_HEIGHT = 640;
    private static final int DISPLAY_DPI = 240;
    private static final int DISPLAY_FLAGS =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;

    private final VirtualDeviceManagerService mService;

    VirtualDeviceShellCommand(VirtualDeviceManagerService service) {
        mService = service;
    }

    @Override
    public int onCommand(String cmd) {
        return switch (cmd) {
            case "create-device" -> createDevice();
            case "create-display" -> createDisplay();
            case "close-device" -> closeDevice();
            default -> handleDefaultCommands(cmd);
        };
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Virtual Device Manager (virtualdevice) commands:");
        pw.println("  help");
        pw.println("      Print this help text.");
        pw.println("  create-device NAME [--owner-uid UID] [--owner-package PACKAGE]");
        pw.println("      Creates a new virtual device and outputs its ID. Requires the device to");
        pw.println("      have insecure keyguard.");
        pw.println("  create-display DEVICE_ID");
        pw.println("      Creates a new trusted virtual display for the virtual device with the");
        pw.println("      given DEVICE_ID and outputs the ID of the new display. The device must");
        pw.println("      have been created with the create-device command and requires the");
        pw.println("      device to have insecure keyguard.");
        pw.println("  close-device DEVICE_ID");
        pw.println("      Closes an existing virtual device with the given DEVICE_ID. The device");
        pw.println("      must have been created with the create-device command.");
    }

    private int createDevice() {
        if (isKeyguardSecure()) {
            getErrPrintWriter().println("Keyguard must be insecure to create a virtual device");
            return 1;
        }
        String deviceName = getNextArgRequired();
        int uid = Process.INVALID_UID;
        String packageName = null;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--owner-uid" -> uid = Integer.parseInt(getNextArgRequired());
                case "--owner-package" -> packageName = getNextArgRequired();
            }
        }
        if ((uid == Process.INVALID_UID) != (packageName == null)) {
            getErrPrintWriter().println("--owner-uid and --owner-package must be set together");
            return 1;
        }
        final AttributionSource attributionSource;
        if (uid != Process.INVALID_UID && packageName != null) {
            attributionSource = new AttributionSource(uid, packageName, "virtualdevice");
        } else {
            attributionSource = mService.getContext().getAttributionSource();
        }
        VirtualDeviceImpl virtualDevice = Binder.withCleanCallingIdentity(() ->
                (VirtualDeviceImpl) mService.createShellVirtualDevice(
                        new Binder("VirtualDeviceShellCommand"), attributionSource,
                        new VirtualDeviceParams.Builder().setName(deviceName).build()));
        if (virtualDevice == null) {
            getErrPrintWriter().println("Failed to create virtual device");
            return 1;
        }
        getOutPrintWriter().println(virtualDevice.getDeviceId());
        return 0;
    }

    private int createDisplay() {
        if (isKeyguardSecure()) {
            getErrPrintWriter().println("Keyguard must be insecure to create a virtual display");
            return 1;
        }
        VirtualDeviceImpl virtualDevice = getShellVirtualDevice();
        if (virtualDevice == null) {
            return 1;
        }

        final var imageReader = new ImageReader.Builder(DISPLAY_WIDTH, DISPLAY_HEIGHT).build();
        String displayName = virtualDevice.getDisplayName() + "-display";
        int displayId = Binder.withCleanCallingIdentity(() ->
                virtualDevice.createVirtualDisplay(
                        new VirtualDisplayConfig.Builder(
                                displayName, DISPLAY_WIDTH, DISPLAY_HEIGHT, DISPLAY_DPI)
                                .setFlags(DISPLAY_FLAGS)
                                .setSurface(imageReader.getSurface())
                                .build(),
                        new IVirtualDisplayCallback.Stub() {
                            @RequiresNoPermission
                            @Override
                            public void onPaused() {
                            }

                            @RequiresNoPermission
                            @Override
                            public void onResumed() {
                            }

                            @RequiresNoPermission
                            @Override
                            public void onStopped() {
                                imageReader.close();
                            }
                        }));
        if (displayId == Display.INVALID_DISPLAY) {
            getErrPrintWriter().println("Failed to create virtual display");
            imageReader.close();
            return 1;
        }
        getOutPrintWriter().println(displayId);
        return 0;
    }

    private int closeDevice() {
        VirtualDeviceImpl virtualDevice = getShellVirtualDevice();
        if (virtualDevice == null) {
            return 1;
        }
        virtualDevice.close();
        return 0;
    }

    @Nullable
    private VirtualDeviceImpl getShellVirtualDevice() {
        int deviceId = Integer.parseInt(getNextArgRequired());
        VirtualDeviceImpl virtualDevice = mService.getVirtualDeviceForId(deviceId);
        if (virtualDevice == null) {
            getErrPrintWriter().println("Error: deviceId " + deviceId + " does not exist");
            return null;
        }
        if (virtualDevice.getDeviceProfile() != DEVICE_PROFILE_SHELL) {
            getErrPrintWriter().println("Error: device " + deviceId + " was not created by shell");
            return null;
        }
        return virtualDevice;
    }

    private boolean isKeyguardSecure() {
        final var keyguardManager = mService.getContext().getSystemService(KeyguardManager.class);
        return keyguardManager != null && keyguardManager.isKeyguardSecure();
    }
}
