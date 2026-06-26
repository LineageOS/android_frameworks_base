/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.notification;

import android.util.ArrayMap;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.notification.ManagedServices.ManagedServiceInfo;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.PrintWriter;

class NotificationListenerStats {
    /**
     * Maximum number of channels that a single NLS is allowed to create for <em>all</em> installed
     * packages. Should be much higher than reasonably necessary, but still prevent runaway channel
     * creation abuse.
     *
     * @see PreferencesHelper#NOTIFICATION_CHANNEL_COUNT_LIMIT
     */
    private static final int MAX_CHANNELS = 5_000;

    /**
     * Maximum number of channels that a single NLS is allowed to create <em>per package</em>.
     * Each package can have a total of {@link PreferencesHelper#NOTIFICATION_CHANNEL_COUNT_LIMIT}
     * channels. This constrains the subset of those that can be conversations added by an NLS,
     * so that it doesn't fill up the entire channel allocation. Prevents legitimate, app-requested
     * channel creations being blocked.
     *
     * @see PreferencesHelper#NOTIFICATION_CHANNEL_COUNT_LIMIT
     */
    private static final int MAX_CHANNELS_PER_PKG = 1_000;

    private static final String TAG_STATS = "nlsStats";
    private static final String TAG_NLS = "nls";
    private static final String ATT_UID = "uid";
    private static final String ATT_OWNED_CHANNEL_COUNT = "channelCount";
    private static final String TAG_PKG = "pkg";
    private static final String ATT_PKG_NAME = "name";
    private static final String ATT_PKG_CHANNEL_COUNT = "channelCount";

    private final Object mLock = new Object();

    // nlsUid => {total, packageName => count }
    @GuardedBy("mLock")
    private final SparseArray<ChannelCount> mChannelsCreated = new SparseArray<>();

    record ChannelCount(int total, ArrayMap<String, Integer> pkgMap) {
        private ChannelCount withRemovedPackage(String packageName) {
            if (pkgMap != null) {
                Integer count = pkgMap.remove(packageName);
                if (count != null && count > 0) {
                    return new ChannelCount(Math.max(0, total - count), pkgMap);
                }
            }
            return this;
        }

        private ChannelCount withCreatedChannels(String packageName, int increase) {
            ArrayMap<String, Integer> map = pkgMap;
            if (map == null) {
                map = new ArrayMap<>();
            }
            int prevPkgCreated = map.getOrDefault(packageName, 0);
            map.put(packageName, prevPkgCreated + increase);

            return new ChannelCount(total + increase, map);
        }
    }

    private final int mMaxChannelsAllowed;
    private final int mMaxChannelsPerPkgAllowed;

    NotificationListenerStats() {
        this(MAX_CHANNELS, MAX_CHANNELS_PER_PKG);
    }

    @VisibleForTesting
    NotificationListenerStats(int maxChannelsAllowed, int maxChannelsPerPkgAllowed) {
        mMaxChannelsAllowed = maxChannelsAllowed;
        mMaxChannelsPerPkgAllowed = maxChannelsPerPkgAllowed;
    }

    boolean isAllowedToCreateChannel(ManagedServiceInfo nls, String pkg) {
        synchronized (mLock) {
            ChannelCount channelCount = mChannelsCreated.get(nls.uid);
            if (channelCount == null) {
                return true;
            }

            int numCreated = channelCount.total;
            if (numCreated >= mMaxChannelsAllowed) {
                return false;
            }

            ArrayMap<String, Integer> pkgMap = channelCount.pkgMap;
            if (pkgMap != null) {
                int numPkgCreated = pkgMap.getOrDefault(pkg, 0);
                return numPkgCreated < mMaxChannelsPerPkgAllowed;
            }
        }
        return true;
    }

    void logCreatedChannels(ManagedServiceInfo nls, String pkg, int increase) {
        synchronized (mLock) {
            ChannelCount channelCount = mChannelsCreated.get(nls.uid);
            if (channelCount != null) {
                mChannelsCreated.put(nls.uid, channelCount.withCreatedChannels(pkg, increase));
            } else {
                ArrayMap<String, Integer> pkgMap = new ArrayMap<>();
                pkgMap.put(pkg, increase);
                mChannelsCreated.put(nls.uid, new ChannelCount(increase, pkgMap));
            }
        }
    }

    void onPackageRemoved(int uid, String packageName) {
        // If the uninstalled package was an NLS, drop its stats.
        synchronized (mLock) {
            mChannelsCreated.delete(uid);

            // If the uninstalled package was a target package, we should also clean it up
            // from other NLSs' stats, and update their total counts.
            for (int i = 0; i < mChannelsCreated.size(); i++) {
                int nlsUid = mChannelsCreated.keyAt(i);
                mChannelsCreated.put(nlsUid,
                        mChannelsCreated.get(nlsUid).withRemovedPackage(packageName));
            }
        }
    }

    static boolean isXmlTag(String tag) {
        return TAG_STATS.equals(tag);
    }

    void writeXml(TypedXmlSerializer out) throws IOException {
        out.startTag(null, TAG_STATS);
        synchronized (mLock) {
            for (int i = 0; i < mChannelsCreated.size(); i++) {
                int uid = mChannelsCreated.keyAt(i);
                out.startTag(null, TAG_NLS);
                out.attributeInt(null, ATT_UID, uid);
                out.attributeInt(null, ATT_OWNED_CHANNEL_COUNT, mChannelsCreated.valueAt(i).total);

                ArrayMap<String, Integer> pkgMap = mChannelsCreated.get(uid).pkgMap;
                if (pkgMap != null) {
                    for (int j = 0; j < pkgMap.size(); j++) {
                        out.startTag(null, TAG_PKG);
                        out.attribute(null, ATT_PKG_NAME, pkgMap.keyAt(j));
                        out.attributeInt(null, ATT_PKG_CHANNEL_COUNT, pkgMap.valueAt(j));
                        out.endTag(null, TAG_PKG);
                    }
                }
                out.endTag(null, TAG_NLS);
            }
        }
        out.endTag(null, TAG_STATS);
    }

    void readXml(TypedXmlPullParser parser) throws XmlPullParserException, IOException {
        int type = parser.getEventType();
        if (type != XmlPullParser.START_TAG) return;
        String tag = parser.getName();
        if (!TAG_STATS.equals(tag)) return;

        synchronized (mLock) {
            ArrayMap<String, Integer> currentPkgMap = null;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                tag = parser.getName();
                if (type == XmlPullParser.END_TAG && TAG_STATS.equals(tag)) {
                    break;
                }
                if (type == XmlPullParser.START_TAG) {
                    if (TAG_NLS.equals(tag)) {
                        int currentUid = parser.getAttributeInt(null, ATT_UID, 0);
                        int channelCount = parser.getAttributeInt(null, ATT_OWNED_CHANNEL_COUNT, 0);
                        currentPkgMap = new ArrayMap<>();
                        mChannelsCreated.put(currentUid,
                                new ChannelCount(channelCount, currentPkgMap));
                    } else if (TAG_PKG.equals(tag)) {
                        if (currentPkgMap != null) {
                            String pkgName = parser.getAttributeValue(null, ATT_PKG_NAME);
                            int channelCount = parser.getAttributeInt(
                                    null, ATT_PKG_CHANNEL_COUNT, 0);
                            if (pkgName != null) {
                                currentPkgMap.put(pkgName, channelCount);
                            }
                        }
                    }
                } else if (type == XmlPullParser.END_TAG) {
                    if (TAG_NLS.equals(tag)) {
                        currentPkgMap = null;
                    }
                }
            }
        }
    }

    void dump(PrintWriter pw, String prefix) {
        synchronized (mLock) {
            for (int i = 0; i < mChannelsCreated.size(); i++) {
                int uid = mChannelsCreated.keyAt(i);
                ChannelCount count = mChannelsCreated.get(uid);
                pw.println(prefix + "NLS with uid " + uid);
                pw.println(prefix + "  created channel count: " + count.total);
                if (count.pkgMap != null) {
                    pw.println(prefix + "  per package:");
                    for (int j = 0; j < count.pkgMap.size(); j++) {
                        pw.println(prefix + "    " + count.pkgMap.keyAt(j)
                                + ": " + count.pkgMap.valueAt(j));
                    }
                }
            }
        }
    }
}
