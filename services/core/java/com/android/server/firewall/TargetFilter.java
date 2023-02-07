/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.firewall;

import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.os.Process;
import android.os.RemoteException;
import android.util.Slog;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

class TargetFilter {
    private static final String ATTR_TYPE = "type";

    private static final String VAL_SIGNATURE = "signature";
    private static final String VAL_SYSTEM = "system";
    private static final String VAL_SYSTEM_OR_SIGNATURE = "system|signature";

    static boolean isPrivilegedApp(int callerUid, int callerPid) {
        if (callerPid >= 0 && (callerUid == Process.SYSTEM_UID || callerUid == 0 ||
                callerPid == Process.myPid() || callerPid == 0)) {
            return true;
        }

        IPackageManager pm = AppGlobals.getPackageManager();
        try {
            return (pm.getPrivateFlagsForUid(callerUid) & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED)
                    != 0;
        } catch (RemoteException ex) {
            Slog.e(IntentFirewall.TAG, "Remote exception while retrieving uid flags",
                    ex);
        }

        return false;
    }

    public static final FilterFactory FACTORY = new FilterFactory("target") {
        @Override
        public Filter newFilter(XmlPullParser parser) throws IOException, XmlPullParserException {
            String typeString = parser.getAttributeValue(null, ATTR_TYPE);
            if (typeString == null) {
                throw new XmlPullParserException("type attribute must be specified for <target>",
                        parser, null);
            }
            if (typeString.equals(VAL_SYSTEM)) {
                return SYSTEM;
            } else if (typeString.equals(VAL_SIGNATURE)) {
                return SIGNATURE;
            } else if (typeString.equals(VAL_SYSTEM_OR_SIGNATURE)) {
                return SYSTEM_OR_SIGNATURE;
            }
            throw new XmlPullParserException(
                    "Invalid type attribute for <target>: " + typeString, parser, null);
        }
    };

    private static final Filter SIGNATURE = new Filter() {
        @Override
        public boolean matches(IntentFirewall ifw, ComponentName resolvedComponent, Intent intent,
                int callerUid, int callerPid, String resolvedType, int receivingUid) {
            return ifw.signaturesMatch(callerUid, receivingUid);
        }
    };

    private static final Filter SYSTEM = new Filter() {
        @Override
        public boolean matches(IntentFirewall ifw, ComponentName resolvedComponent, Intent intent,
                int callerUid, int callerPid, String resolvedType, int receivingUid) {
            return isPrivilegedApp(receivingUid, -1);
        }
    };

    private static final Filter SYSTEM_OR_SIGNATURE = new Filter() {
        @Override
        public boolean matches(IntentFirewall ifw, ComponentName resolvedComponent, Intent intent,
                int callerUid, int callerPid, String resolvedType, int receivingUid) {
            return isPrivilegedApp(receivingUid, -1) ||
                    ifw.signaturesMatch(callerUid, receivingUid);
        }
    };
}
