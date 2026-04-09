/*
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.android.packageinstaller;

import static android.content.pm.PackageManager.GET_PERMISSIONS;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * This is a utility class for defining some utility methods and constants
 * used in the package installer application.
 */
public class PackageUtil {
    private static final String LOG_TAG = "PackageInstaller";

    public static final String PREFIX="com.android.packageinstaller.";
    public static final String INTENT_ATTR_INSTALL_STATUS = PREFIX+"installStatus";
    public static final String INTENT_ATTR_APPLICATION_INFO=PREFIX+"applicationInfo";
    public static final String INTENT_ATTR_PERMISSIONS_LIST=PREFIX+"PermissionsList";
    //intent attribute strings related to uninstall
    public static final String INTENT_ATTR_PACKAGE_NAME=PREFIX+"PackageName";
    private static final String DOWNLOADS_AUTHORITY = "downloads";
    private static final String SPLIT_BASE_APK_SUFFIX = "base.apk";
    private static final String SPLIT_APK_SUFFIX = ".apk";

    /**
     * Utility method to get package information for a given {@link File}
     */
    @Nullable
    public static PackageInfo getPackageInfo(Context context, File sourceFile, int flags) {
        String filePath = sourceFile.getAbsolutePath();
        if (filePath.endsWith(SPLIT_BASE_APK_SUFFIX)) {
            File dir = sourceFile.getParentFile();
            try (Stream<Path> list = Files.list(dir.toPath())) {
                long count = list
                        .filter((name) -> name.endsWith(SPLIT_APK_SUFFIX))
                        .limit(2)
                        .count();
                if (count > 1) {
                    // split apks, use file directory to get archive info
                    filePath = dir.getPath();
                }
            } catch (Exception ignored) {
                // No access to the parent directory, proceed to read app snippet
                // from the base apk only
            }
        }
        try {
            return context.getPackageManager().getPackageArchiveInfo(filePath, flags);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static View initSnippet(View snippetView, CharSequence label, Drawable icon) {
        ((ImageView)snippetView.findViewById(R.id.app_icon)).setImageDrawable(icon);
        ((TextView)snippetView.findViewById(R.id.app_name)).setText(label);
        return snippetView;
    }

    /**
     * Utility method to display a snippet of an installed application.
     * The content view should have been set on context before invoking this method.
     * appSnippet view should include R.id.app_icon and R.id.app_name
     * defined on it.
     *
     * @param pContext context of package that can load the resources
     * @param componentInfo ComponentInfo object whose resources are to be loaded
     * @param snippetView the snippet view
     */
    public static View initSnippetForInstalledApp(Context pContext,
            ApplicationInfo appInfo, View snippetView) {
        return initSnippetForInstalledApp(pContext, appInfo, snippetView, null);
    }

    /**
     * Utility method to display a snippet of an installed application.
     * The content view should have been set on context before invoking this method.
     * appSnippet view should include R.id.app_icon and R.id.app_name
     * defined on it.
     *
     * @param pContext context of package that can load the resources
     * @param componentInfo ComponentInfo object whose resources are to be loaded
     * @param snippetView the snippet view
     * @param UserHandle user that the app si installed for.
     */
    public static View initSnippetForInstalledApp(Context pContext,
            ApplicationInfo appInfo, View snippetView, UserHandle user) {
        final PackageManager pm = pContext.getPackageManager();
        Drawable icon = appInfo.loadIcon(pm);
        if (user != null) {
            icon = pContext.getPackageManager().getUserBadgedIcon(icon, user);
        }
        return initSnippet(
                snippetView,
                appInfo.loadLabel(pm),
                icon);
    }

    static final class AppSnippet implements Parcelable {
        @NonNull public CharSequence label;
        @NonNull public Drawable icon;
        public int iconSize;

        AppSnippet(@NonNull CharSequence label, @NonNull Drawable icon, Context context) {
            this.label = label;
            this.icon = icon;
            final ActivityManager am = context.getSystemService(ActivityManager.class);
            this.iconSize = am.getLauncherLargeIconSize();
        }

        private AppSnippet(Parcel in) {
            label = in.readString();
            byte[] b = in.readBlob();
            Bitmap bmp = BitmapFactory.decodeByteArray(b, 0, b.length);
            icon = new BitmapDrawable(Resources.getSystem(), bmp);
            iconSize = in.readInt();
        }

        @Override
        public String toString() {
            return "AppSnippet[" + label + " (has icon)]";
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        // writeString8 is a hidden API, which is unavailable as this package does not use
        // platform_apis.
        @SuppressWarnings("AndroidFrameworkEfficientParcelable")
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeString(label.toString());

            Bitmap bmp = getBitmapFromDrawable(icon);
            dest.writeBlob(getBytesFromBitmap(bmp));
            bmp.recycle();

            dest.writeInt(iconSize);
        }

        private Bitmap getBitmapFromDrawable(Drawable drawable) {
            // Create an empty bitmap with the dimensions of our drawable
            final Bitmap bmp = Bitmap.createBitmap(drawable.getIntrinsicWidth(),
                drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
            // Associate it with a canvas. This canvas will draw the icon on the bitmap
            final Canvas canvas = new Canvas(bmp);
            // Draw the drawable in the canvas. The canvas will ultimately paint the drawable in the
            // bitmap held within
            drawable.draw(canvas);

            // Scale it down if the icon is too large
            if ((bmp.getWidth() > iconSize * 2) || (bmp.getHeight() > iconSize * 2)) {
                Bitmap scaledBitmap = Bitmap.createScaledBitmap(bmp, iconSize, iconSize, true);
                if (scaledBitmap != bmp) {
                    bmp.recycle();
                }
                return scaledBitmap;
            }
            return bmp;
        }

        private byte[] getBytesFromBitmap(Bitmap bmp) {
            ByteArrayOutputStream baos = null;
            try {
                baos = new ByteArrayOutputStream();
                bmp.compress(CompressFormat.PNG, 100, baos);
            } finally {
                try {
                    if (baos != null) {
                        baos.close();
                    }
                } catch (IOException e) {
                    Log.e(LOG_TAG, "ByteArrayOutputStream was not closed");
                }
            }
            return baos.toByteArray();
        }

        public static final Parcelable.Creator<AppSnippet> CREATOR = new Parcelable.Creator<>() {
            public AppSnippet createFromParcel(Parcel in) {
                return new AppSnippet(in);
            }

            public AppSnippet[] newArray(int size) {
                return new AppSnippet[size];
            }
        };
    }

    /**
     * Utility method to load application label
     *
     * @param pContext context of package that can load the resources
     * @param appInfo ApplicationInfo object of package whose resources are to be loaded
     * @param sourceFile File the package is in
     */
    public static AppSnippet getAppSnippet(
            Activity pContext, ApplicationInfo appInfo, File sourceFile) {
        final String archiveFilePath = sourceFile.getAbsolutePath();
        PackageManager pm = pContext.getPackageManager();
        appInfo.publicSourceDir = archiveFilePath;

        if (appInfo.splitNames != null && appInfo.splitSourceDirs == null) {
            final File[] files = sourceFile.getParentFile().listFiles(
                    (dir, name) -> name.endsWith(SPLIT_APK_SUFFIX));
            final String[] splits = Arrays.stream(appInfo.splitNames)
                    .map(i -> findFilePath(files, i + SPLIT_APK_SUFFIX))
                    .filter(Objects::nonNull)
                    .toArray(String[]::new);

            appInfo.splitSourceDirs = splits;
            appInfo.splitPublicSourceDirs = splits;
        }

        CharSequence label = null;
        // Try to load the label from the package's resources. If an app has not explicitly
        // specified any label, just use the package name.
        if (appInfo.labelRes != 0) {
            try {
                label = appInfo.loadLabel(pm);
            } catch (Resources.NotFoundException e) {
            }
        }
        if (label == null) {
            label = (appInfo.nonLocalizedLabel != null) ?
                    appInfo.nonLocalizedLabel : appInfo.packageName;
        }
        Drawable icon = null;
        // Try to load the icon from the package's resources. If an app has not explicitly
        // specified any resource, just use the default icon for now.
        try {
            if (appInfo.icon != 0) {
                try {
                    icon = appInfo.loadIcon(pm);
                } catch (Resources.NotFoundException e) {
                }
            }
            if (icon == null) {
                icon = pContext.getPackageManager().getDefaultActivityIcon();
            }
        } catch (OutOfMemoryError e) {
            Log.i(LOG_TAG, "Could not load app icon", e);
        }
        return new PackageUtil.AppSnippet(label, icon, pContext);
    }

    private static String findFilePath(File[] files, String postfix) {
        final int length = files != null ? files.length : 0;
        for (int i = 0; i < length; i++) {
            File file = files[i];
            final String path = file.getAbsolutePath();
            if (path.endsWith(postfix)) {
                return path;
            }
        }
        return null;
    }

    /**
     * Get the maximum target sdk for a UID.
     *
     * @param context The context to use
     * @param uid The UID requesting the install/uninstall
     *
     * @return The maximum target SDK or -1 if the uid does not match any packages.
     */
    static int getMaxTargetSdkVersionForUid(@NonNull Context context, int uid) {
        PackageManager pm = context.getPackageManager();
        final String[] packages = pm.getPackagesForUid(uid);
        int targetSdkVersion = -1;
        if (packages != null) {
            for (String packageName : packages) {
                try {
                    ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
                    targetSdkVersion = Math.max(targetSdkVersion, info.targetSdkVersion);
                } catch (PackageManager.NameNotFoundException e) {
                    // Ignore and try the next package
                }
            }
        }
        return targetSdkVersion;
    }


    /**
     * Quietly close a closeable resource (e.g. a stream or file). The input may already
     * be closed and it may even be null.
     */
    static void safeClose(Closeable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (IOException ioe) {
                // Catch and discard the error
            }
        }
    }

    /**
     * A simple error dialog showing a message
     */
    public static class SimpleErrorDialog extends DialogFragment {
        private static final String MESSAGE_KEY =
                SimpleErrorDialog.class.getName() + "MESSAGE_KEY";

        static SimpleErrorDialog newInstance(@StringRes int message) {
            SimpleErrorDialog dialog = new SimpleErrorDialog();

            Bundle args = new Bundle();
            args.putInt(MESSAGE_KEY, message);
            dialog.setArguments(args);

            return dialog;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            return new AlertDialog.Builder(getActivity())
                    .setMessage(getArguments().getInt(MESSAGE_KEY))
                    .setPositiveButton(R.string.ok, (dialog, which) -> {
                        getActivity().finish();
                    })
                    .create();
        }

        @Override
        public void onCancel(DialogInterface dialog) {
            getActivity().setResult(Activity.RESULT_CANCELED);
            getActivity().finish();
        }
    }

    /**
     * Determines if the UID belongs to the system downloads provider and returns the
     * {@link ApplicationInfo} of the provider
     *
     * @param uid UID of the caller
     * @return {@link ApplicationInfo} of the provider if a downloads provider exists,
     *          it is a system app, and its UID matches with the passed UID, null otherwise.
     */
    public static ApplicationInfo getSystemDownloadsProviderInfo(PackageManager pm, int uid) {
        final ProviderInfo providerInfo = pm.resolveContentProvider(
                DOWNLOADS_AUTHORITY, 0);
        if (providerInfo == null) {
            // There seems to be no currently enabled downloads provider on the system.
            return null;
        }
        ApplicationInfo appInfo = providerInfo.applicationInfo;
        if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && uid == appInfo.uid) {
            return appInfo;
        }
        return null;
    }

    /**
     * Returns if the version two is enabled.
     * 1. Currently, the version two is only enabled for the mobile devices
     */
    public static boolean isVersionTwoEnabled(@NonNull Context context) {
        if (isAuto(context) || isTV(context) || isWatch(context)) {
            Log.d(LOG_TAG, "The device doesn't support PIA version 2");
            return false;
        }

        // Only enable PIA V2 on the version that is newer than BAKLAVA
        if (Build.VERSION.SDK_INT_FULL <= Build.VERSION_CODES_FULL.BAKLAVA) {
            Log.d(LOG_TAG, "The OS version doesn't support PIA version 2");
            return false;
        }

        Log.d(LOG_TAG, "Use PIA V2");
        return true;
    }

    private static boolean hasSystemFeature(@NonNull Context context, @NonNull String featureName) {
        return context.getPackageManager().hasSystemFeature(featureName);
    }

    private static boolean isAuto(@NonNull Context context) {
        return hasSystemFeature(context, PackageManager.FEATURE_AUTOMOTIVE);
    }

    private static boolean isWatch(@NonNull Context context) {
        return hasSystemFeature(context, PackageManager.FEATURE_WATCH);
    }

    private static boolean isTV(@NonNull Context context) {
        return hasSystemFeature(context, PackageManager.FEATURE_TELEVISION)
                || hasSystemFeature(context, PackageManager.FEATURE_LEANBACK);
    }

    /**
     * Returns a string containing the reason for using Pia V2. Used for debugging purposes
     */
    private static String getDebugStringForPiaV2(boolean usePiaV2aConfig,
            boolean testOverrideForPiaV2) {
        StringBuilder sb = new StringBuilder("Using Pia V2 due to: ");
        boolean aconfigUsed = false;
        if (usePiaV2aConfig) {
            sb.append("aconfig flag USE_PIA_V2");
            aconfigUsed = true;
        }
        if (testOverrideForPiaV2) {
            if (aconfigUsed) {
                sb.append(" and ");
            }
            sb.append("testOverrideForPiaV2.");
        }
        return sb.toString();
    }

    /**
     * Returns the package name for the given UID.
     *
     * @param pm The package manager to use
     * @param sourceUid The UID of the package to get the package name for
     * @return The package name for the given UID or null if the UID does not match any packages.
     */
    public static String getPackageNameForUid(PackageManager pm, int sourceUid) {
        String[] packagesForUid = pm.getPackagesForUid(sourceUid);
        if (packagesForUid == null) {
            Log.e(LOG_TAG, "Package not found for uid " + sourceUid);
            return null;
        }
        return packagesForUid[0];
    }

    /**
     * Returns the requested permissions for the given package.
     *
     * @param pm The package manager to use
     * @param callingPackage The package to get the requested permissions for
     * @return The requested permissions for the given package or an empty array if the package is
     *         not found.
     */
    @NonNull
    public static String[] getRequestedPermissions(PackageManager pm, String callingPackage) {
        String[] requestedPermissions = null;
        try {
            requestedPermissions =
                    pm.getPackageInfo(callingPackage, GET_PERMISSIONS).requestedPermissions;
        } catch (Exception e) {
            // Should be unreachable because we've just fetched the packageName above.
            Log.e(LOG_TAG, "Package not found for " + callingPackage);
        }
        return requestedPermissions == null ? new String[]{} : requestedPermissions;
    }


}
