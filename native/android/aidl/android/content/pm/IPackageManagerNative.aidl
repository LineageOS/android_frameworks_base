/*
**
** Copyright 2017, The Android Open Source Project
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

package android.content.pm;

import android.content.pm.IStagedApexObserver;
import android.content.pm.PackageInfoNative;
import android.content.pm.StagedApexInfo;

/**
 * Parallel implementation of certain {@link PackageManager} APIs that need to
 * be exposed to native code.
 * <p>These APIs are a parallel definition to the APIs in PackageManager, so,
 * they can technically diverge. However, it's good practice to keep these
 * APIs in sync with each other.
 * <p>Because these APIs are exposed to native code, it's possible they will
 * be exposed to privileged components [such as UID 0]. Care should be taken
 * to avoid exposing potential security holes for methods where permission
 * checks are bypassed based upon UID alone.
 *
 * @hide
 */
interface IPackageManagerNative {
    /**
     * Returns a set of names for the given UIDs.
     * IMPORTANT: Unlike the Java version of this API, unknown UIDs are
     * not represented by 'null's. Instead, they are represented by empty
     * strings.
     */
    @utf8InCpp String[] getNamesForUids(in int[] uids);

    /**
     * Retrieve package signing information for an application package that is installed on the
     * system.
     *
     * Note that the PackageInfoNative returned may contain unset @nullable fields.  This method
     * only populates the signingInfo field, as well as the (non-@nullable) packageName.
     *
     * @param packageName The full name (i.e. com.google.apps.contacts) of the desired package.
     * @param userId of the user that has the installed package.
     *
     * @return A PackageInfoNative object containing the package name and signing info.
     */
    @nullable PackageInfoNative getPackageInfoWithSigningInfo(String packageName, int userId);

    /**
     * Retrieve signing information for application packages associated with the specified uid.
     *
     * Note that the PackageInfoNative returned may contain unset @nullable fields.  This method
     * only populates the signingInfo field, as well as the (non-@nullable) packageName.
     *
     * @param uid The uid for which associated package information should be returned.
     *
     * @return An array of PackageInfoNative objects containing package names and signing info.
     */
    @nullable PackageInfoNative[] getPackageInfoWithSigningInfoForUid(int uid);

    /**
     * Return the UID associated with the given package name.
     * Note that the same package will have different UIDs under different UserHandle on
     * the same device.
     * @param packageName The full name (i.e. com.google.apps.contacts) of the desired package.
     * @param flags Additional option flags to modify the data returned.
     * @param userId The user handle identifier to look up the package under.
     * @return Returns an integer UID who owns the given package name, or -1 if no such package is
     *            available to the caller.
     */
     int getPackageUid(in @utf8InCpp String packageName, in long flags, in int userId);

    /**
     * Checks whether the specified package has the specified permission.
     *
     * @param permName The name of the permission you are checking for.
     * @param packageName The name of the package you are checking against.
     * @return PackageManager#PERMISSION_GRANTED (0) is returned if the specified package has the
     *         specified permission, or PackageManager#PERMISSION_DENIED (-1) if it does not.
     */
    int checkPermission(in String permName, in String packageName, in int userId);

    /**
     * Returns the name of the installer (a package) which installed the named
     * package. Preloaded packages return the string "preload". Sideloaded packages
     * return an empty string. Unknown or unknowable are returned as empty strings.
     */
    @utf8InCpp String getInstallerForPackage(in String packageName);

    /**
     * Returns the version code of the named package.
     * Unknown or unknowable versions are returned as 0.
     */
    long getVersionCodeForPackage(in String packageName);

    /**
     * Return if each app, identified by its package name allows its audio to be recorded.
     * Unknown packages are mapped to false.
     */
    boolean[] isAudioPlaybackCaptureAllowed(in @utf8InCpp String[] packageNames);

    /*  ApplicationInfo.isSystemApp() == true */
    const int LOCATION_SYSTEM = 0x1;
    /*  ApplicationInfo.isVendor() == true */
    const int LOCATION_VENDOR = 0x2;
    /*  ApplicationInfo.isProduct() == true */
    const int LOCATION_PRODUCT = 0x4;

    /**
     * Returns a set of bitflags about package location.
     * LOCATION_SYSTEM: getApplicationInfo(packageName).isSystemApp()
     * LOCATION_VENDOR: getApplicationInfo(packageName).isVendor()
     * LOCATION_PRODUCT: getApplicationInfo(packageName).isProduct()
     */
    int getLocationFlags(in @utf8InCpp String packageName);

    /**
     * Returns the target SDK version for the given package.
     * Unknown packages will cause the call to fail. The caller must check the
     * returned Status before using the result of this function.
     */
    int getTargetSdkVersionForPackage(in String packageName);

    /**
     * Returns the name of module metadata package, or empty string if device doesn't have such
     * package.
     */
    @utf8InCpp String getModuleMetadataPackageName();

    /**
     * Returns true if the package has the SHA 256 version of the signing certificate.
     * @see PackageManager#hasSigningCertificate(String, byte[], int), where type
     * has been set to {@link PackageManager#CERT_INPUT_SHA256}.
     */
    boolean hasSha256SigningCertificate(in @utf8InCpp String packageName, in byte[] certificate);

    /**
     * Returns the debug flag for the given package.
     * Unknown packages will cause the call to fail.
     */
    boolean isPackageDebuggable(in String packageName);

    /**
     * Check whether the given feature name and version is one of the available
     * features as returned by {@link PackageManager#getSystemAvailableFeatures()}. Since
     * features are defined to always be backwards compatible, this returns true
     * if the available feature version is greater than or equal to the
     * requested version.
     */
    boolean hasSystemFeature(in String featureName, in int version);

    /** Register a observer for change in set of staged APEX ready for installation */
    void registerStagedApexObserver(in IStagedApexObserver observer);

    /**
     * Unregister an existing staged apex observer.
     * This does nothing if this observer was not already registered.
     */
    void unregisterStagedApexObserver(in IStagedApexObserver observer);

    /**
     * Get information of staged APEXes.
     */
    StagedApexInfo[] getStagedApexInfos();

    /**
     * App attempted to perform an operation that is protected with a a special runtime permission
     * and failed due to that permission being denied.
     */
    oneway void onDeniedSpecialRuntimePermissionOp(in String permissionName, int uid, in String packageName);
}
