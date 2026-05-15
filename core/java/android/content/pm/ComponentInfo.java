/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.content.pm;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Service;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources.NotFoundException;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Parcel;
import android.text.TextUtils;
import android.util.Printer;

import java.util.Objects;

/**
 * Base class containing information common to all application components
 * ({@link ActivityInfo}, {@link ServiceInfo}).  This class is not intended
 * to be used by itself; it is simply here to share common definitions
 * between all application components.  As such, it does not itself
 * implement Parcelable, but does provide convenience methods to assist
 * in the implementation of Parcelable in subclasses.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class ComponentInfo extends PackageItemInfo {
    /**
     * Global information about the application/package this component is a
     * part of.
     */
    public ApplicationInfo applicationInfo;

    /**
     * The name of the process this component should run in.
     * From the "android:process" attribute or, if not set, the same
     * as <var>applicationInfo.processName</var>.
     */
    public String processName;

    /**
     * The name of the split in which this component is declared.
     * Null if the component was declared in the base APK.
     */
    public String splitName;

    /**
     * Set of attribution tags that should be automatically applied to this
     * component.
     * <p>
     * When this component represents an {@link Activity}, {@link Service},
     * {@link ContentResolver} or {@link BroadcastReceiver}, each instance will
     * be automatically configured with {@link Context#createAttributionContext}
     * using the first attribution tag contained here.
     * <p>
     * Additionally, when this component represents a {@link BroadcastReceiver}
     * and the sender of a broadcast requires the receiver to hold one or more
     * specific permissions, those permission checks will be performed using
     * each of the attributions tags contained here.
     *
     * @see Context#createAttributionContext(String)
     */
    @SuppressLint({"MissingNullability", "MutableBareField"})
    public String[] attributionTags;

    /**
     * A string resource identifier (in the package's resources) containing
     * a user-readable description of the component.  From the "description"
     * attribute or, if not set, 0.
     */
    public int descriptionRes;

    /**
     * Indicates whether or not this component may be instantiated.  Note that this value can be
     * overridden by the one in its parent {@link ApplicationInfo}.
     */
    public boolean enabled = true;

    /**
     * Set to true if this component is available for use by other applications.
     * Comes from {@link android.R.attr#exported android:exported} of the
     * &lt;activity&gt;, &lt;receiver&gt;, &lt;service&gt;, or
     * &lt;provider&gt; tag.
     */
    public boolean exported = false;

    /**
     * Indicates if this component is aware of direct boot lifecycle, and can be
     * safely run before the user has entered their credentials (such as a lock
     * pattern or PIN).
     */
    public boolean directBootAware = false;

    /** @hide */
    public static final int MAX_SAFE_DESCRIPTION_LENGTH = 1024;

    private static final int FLAG_ENABLED = 1 << 0;
    private static final int FLAG_EXPORTED = 1 << 1;
    private static final int FLAG_DIRECT_BOOT_AWARE = 1 << 2;
    private static final int FLAG_IS_ARCHIVED = 1 << 3;
    private static final int FLAG_INHERIT_PACKAGE_NAME = 1 << 4;
    private static final int FLAG_INHERIT_PROCESS_NAME = 1 << 5;
    private static final int FLAG_INHERIT_META_DATA = 1 << 6;
    private static final int FLAG_INHERIT_LABEL = 1 << 7;
    private static final int FLAG_INHERIT_ICON = 1 << 8;
    private static final int FLAG_INHERIT_LOGO = 1 << 9;
    private static final int FLAG_INHERIT_BANNER = 1 << 10;

    public ComponentInfo() {
    }

    public ComponentInfo(ComponentInfo orig) {
        super(orig);
        applicationInfo = orig.applicationInfo;
        processName = orig.processName;
        splitName = orig.splitName;
        attributionTags = orig.attributionTags;
        descriptionRes = orig.descriptionRes;
        enabled = orig.enabled;
        exported = orig.exported;
        directBootAware = orig.directBootAware;
    }

    /** @hide */
    @Override public CharSequence loadUnsafeLabel(PackageManager pm) {
        if (nonLocalizedLabel != null) {
            return nonLocalizedLabel;
        }
        ApplicationInfo ai = applicationInfo;
        CharSequence label;
        if (labelRes != 0) {
            label = pm.getText(packageName, labelRes, ai);
            if (label != null) {
                return label;
            }
        }
        if (ai.nonLocalizedLabel != null) {
            return ai.nonLocalizedLabel;
        }
        if (ai.labelRes != 0) {
            label = pm.getText(packageName, ai.labelRes, ai);
            if (label != null) {
                return label;
            }
        }
        return name;
    }

    /** @hide */
    public CharSequence loadDescription(@NonNull PackageManager pm) {
        Objects.requireNonNull(pm);
        if (descriptionRes != 0) {
            try {
                return TextUtils.trimToSize(
                        pm.getText(packageName, descriptionRes, applicationInfo),
                        MAX_SAFE_DESCRIPTION_LENGTH);
            } catch (OutOfMemoryError e) {
                throw new NotFoundException();
            }
        }
        throw new NotFoundException();
    }

    /**
     * Return whether this component and its enclosing application are enabled.
     */
    public boolean isEnabled() {
        return enabled && applicationInfo.enabled;
    }

    /**
     * Return the icon resource identifier to use for this component.  If
     * the component defines an icon, that is used; else, the application
     * icon is used.
     *
     * @return The icon associated with this component.
     */
    public final int getIconResource() {
        return icon != 0 ? icon : applicationInfo.icon;
    }

    /**
     * Return the logo resource identifier to use for this component.  If
     * the component defines a logo, that is used; else, the application
     * logo is used.
     *
     * @return The logo associated with this component.
     */
    public final int getLogoResource() {
        return logo != 0 ? logo : applicationInfo.logo;
    }

    /**
     * Return the banner resource identifier to use for this component. If the
     * component defines a banner, that is used; else, the application banner is
     * used.
     *
     * @return The banner associated with this component.
     */
    public final int getBannerResource() {
        return banner != 0 ? banner : applicationInfo.banner;
    }

    /**
     * Return the uid to use for this component based on whether it should run in pcc or not.
     * @hide
     */
    public final int getUid() {
        return shouldRunInPccSandbox() ? applicationInfo.pccUid : applicationInfo.uid;
    }

    /**
     * Return whether this component should run in PCC sandbox or not.
     * This method should be overridden by concrete application components
     * ({@link ActivityInfo}, {@link ServiceInfo}, {@link ProviderInfo}) that can make use
     * of their flags.
     * @hide
     */
    public boolean shouldRunInPccSandbox() {
        return false;
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public ComponentName getComponentName() {
        return new ComponentName(packageName, name);
    }

    @Override
    protected void dumpFront(Printer pw, String prefix) {
        super.dumpFront(pw, prefix);
        if (processName != null && !packageName.equals(processName)) {
            pw.println(prefix + "processName=" + processName);
        }
        if (splitName != null) {
            pw.println(prefix + "splitName=" + splitName);
        }
        if (attributionTags != null && attributionTags.length > 0) {
            StringBuilder tags = new StringBuilder();
            tags.append(attributionTags[0]);
            for (int i = 1; i < attributionTags.length; i++) {
                tags.append(", ");
                tags.append(attributionTags[i]);
            }
            pw.println(prefix + "attributionTags=[" + tags + "]");
        }
        pw.println(prefix + "enabled=" + enabled + " exported=" + exported
                + " directBootAware=" + directBootAware);
        if (descriptionRes != 0) {
            pw.println(prefix + "description=" + descriptionRes);
        }
    }

    @Override
    protected void dumpBack(Printer pw, String prefix) {
        dumpBack(pw, prefix, DUMP_FLAG_ALL);
    }

    void dumpBack(Printer pw, String prefix, int dumpFlags) {
        if ((dumpFlags & DUMP_FLAG_APPLICATION) != 0) {
            if (applicationInfo != null) {
                pw.println(prefix + "ApplicationInfo:");
                applicationInfo.dump(pw, prefix + "  ", dumpFlags);
            } else {
                pw.println(prefix + "ApplicationInfo: null");
            }
        }
        super.dumpBack(pw, prefix);
    }

    // LINT.IfChange(parcel)
    @Override
    public void writeToParcel(Parcel dest, int parcelableFlags) {
        // Optimized writeToParcel replacing PackageItemInfo.writeToParcel.
        // If you add fields to PackageItemInfo, you MUST update this method and the constructor.
        // PackageItemInfo fields
        dest.writeString8(name);

        int flags = 0;
        if (enabled) {
            flags |= FLAG_ENABLED;
        }
        if (exported) {
            flags |= FLAG_EXPORTED;
        }
        if (directBootAware) {
            flags |= FLAG_DIRECT_BOOT_AWARE;
        }
        if (isArchived) {
            flags |= FLAG_IS_ARCHIVED;
        }

        // We optimize the parcel size by avoiding writing data that is already present
        // in the parent ApplicationInfo. This is especially important for fields that
        // are often duplicated across many components in a package, such as the package name,
        // process name, and especially the meta-data bundle.
        boolean inheritPackageName = applicationInfo != null && packageName != null
                && packageName.equals(applicationInfo.packageName);
        if (inheritPackageName) {
            flags |= FLAG_INHERIT_PACKAGE_NAME;
        }

        boolean inheritProcessName = applicationInfo != null && processName != null
                && processName.equals(applicationInfo.processName);
        if (inheritProcessName) {
            flags |= FLAG_INHERIT_PROCESS_NAME;
        }

        // We use reference equality here because we want to know if it's the exact same
        // Bundle object instance. This is efficient and catches the common case where
        // the PackageParser assigns the same Bundle to all components.
        boolean inheritMetaData = applicationInfo != null && metaData != null
                && metaData == applicationInfo.metaData;
        if (inheritMetaData) {
            flags |= FLAG_INHERIT_META_DATA;
        }

        boolean inheritLabel = applicationInfo != null && labelRes != 0
                && labelRes == applicationInfo.labelRes && nonLocalizedLabel == null
                && applicationInfo.nonLocalizedLabel == null;
        if (inheritLabel) {
            flags |= FLAG_INHERIT_LABEL;
        }

        boolean inheritIcon = applicationInfo != null && icon != 0 && icon == applicationInfo.icon;
        if (inheritIcon) {
            flags |= FLAG_INHERIT_ICON;
        }

        boolean inheritLogo = applicationInfo != null && logo != 0 && logo == applicationInfo.logo;
        if (inheritLogo) {
            flags |= FLAG_INHERIT_LOGO;
        }

        boolean inheritBanner = applicationInfo != null && banner != 0
                && banner == applicationInfo.banner;
        if (inheritBanner) {
            flags |= FLAG_INHERIT_BANNER;
        }

        dest.writeInt(flags);

        if (!inheritPackageName) {
            dest.writeString8(packageName);
        }

        if (!inheritLabel) {
            dest.writeInt(labelRes);
        }

        TextUtils.writeToParcel(nonLocalizedLabel, dest, parcelableFlags);

        if (!inheritIcon) {
            dest.writeInt(icon);
        }

        if (!inheritLogo) {
            dest.writeInt(logo);
        }

        if (!inheritMetaData) {
            dest.writeBundle(metaData);
        }

        if (!inheritBanner) {
            dest.writeInt(banner);
        }

        dest.writeInt(showUserIcon);
        // isArchived is in flags

        // ComponentInfo fields
        applicationInfo.writeToParcel(dest, parcelableFlags);

        if (!inheritProcessName) {
            dest.writeString8(processName);
        }

        dest.writeString8(splitName);
        dest.writeString8Array(attributionTags);
        dest.writeInt(descriptionRes);
        // enabled, exported, directBootAware are in flags
    }

    protected ComponentInfo(Parcel source) {
        super(); // Use default constructor, manually read PackageItemInfo fields

        // PackageItemInfo reading
        name = source.readString8();
        int flags = source.readInt();

        enabled = (flags & FLAG_ENABLED) != 0;
        exported = (flags & FLAG_EXPORTED) != 0;
        directBootAware = (flags & FLAG_DIRECT_BOOT_AWARE) != 0;
        isArchived = (flags & FLAG_IS_ARCHIVED) != 0;
        boolean inheritPackageName = (flags & FLAG_INHERIT_PACKAGE_NAME) != 0;
        boolean inheritProcessName = (flags & FLAG_INHERIT_PROCESS_NAME) != 0;
        boolean inheritMetaData = (flags & FLAG_INHERIT_META_DATA) != 0;
        boolean inheritLabel = (flags & FLAG_INHERIT_LABEL) != 0;
        boolean inheritIcon = (flags & FLAG_INHERIT_ICON) != 0;
        boolean inheritLogo = (flags & FLAG_INHERIT_LOGO) != 0;
        boolean inheritBanner = (flags & FLAG_INHERIT_BANNER) != 0;


        if (!inheritPackageName) {
            packageName = source.readString8();
        }

        if (!inheritLabel) {
            labelRes = source.readInt();
        }
        nonLocalizedLabel = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
        if (!inheritIcon) {
            icon = source.readInt();
        }
        if (!inheritLogo) {
            logo = source.readInt();
        }

        if (!inheritMetaData) {
            metaData = source.readBundle();
        }

        if (!inheritBanner) {
            banner = source.readInt();
        }
        showUserIcon = source.readInt();

        // ComponentInfo reading
        applicationInfo = ApplicationInfo.CREATOR.createFromParcel(source);

        if (!inheritProcessName) {
            processName = source.readString8();
        }

        splitName = source.readString8();
        attributionTags = source.createString8Array();
        descriptionRes = source.readInt();

        // Fixups
        if (inheritPackageName && applicationInfo != null) {
            packageName = applicationInfo.packageName;
        }
        if (inheritProcessName && applicationInfo != null) {
            processName = applicationInfo.processName;
        }
        if (inheritMetaData && applicationInfo != null) {
            metaData = applicationInfo.metaData;
        }

        if (applicationInfo != null) {
            if (inheritLabel) {
                labelRes = applicationInfo.labelRes;
            }
            if (inheritIcon) {
                icon = applicationInfo.icon;
            }
            if (inheritLogo) {
                logo = applicationInfo.logo;
            }
            if (inheritBanner) {
                banner = applicationInfo.banner;
            }
        }
    }
    // LINT.ThenChange(PackageItemInfo.java:parcel)

    /**
     * @hide
     */
    @Override
    @android.ravenwood.annotation.RavenwoodThrow(blockedBy = android.content.res.Resources.class)
    public Drawable loadDefaultIcon(PackageManager pm) {
        return applicationInfo.loadIcon(pm);
    }

    /**
     * @hide
     */
    @android.ravenwood.annotation.RavenwoodThrow(blockedBy = android.content.res.Resources.class)
    @Override protected Drawable loadDefaultBanner(PackageManager pm) {
        return applicationInfo.loadBanner(pm);
    }

    /**
     * @hide
     */
    @Override
    @android.ravenwood.annotation.RavenwoodThrow(blockedBy = android.content.res.Resources.class)
    protected Drawable loadDefaultLogo(PackageManager pm) {
        return applicationInfo.loadLogo(pm);
    }

    /**
     * @hide
     */
    @Override public ApplicationInfo getApplicationInfo() {
        return applicationInfo;
    }
}
