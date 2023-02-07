/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

public class IntentFilterFilter implements Filter {
    public final IntentFilter mIntentFilter;

    public IntentFilterFilter(IntentFilter intentFilter) {
        mIntentFilter = intentFilter;
    }

    @Override
    public boolean matches(IntentFirewall ifw, ComponentName resolvedComponent, Intent intent,
            int callerUid, int callerPid, String resolvedType, int receivingUid) {
        return mIntentFilter.match(null, intent, false, "IntentFirewall:IntentFilterFilter") > 0;
    }

    public static final FilterFactory FACTORY = new FilterFactory("intent-filter") {
        @Override
        public Filter newFilter(XmlPullParser parser)
                throws IOException, XmlPullParserException {
            IntentFilter i = new IntentFilter();
            i.readFromXml(parser);
            return new IntentFilterFilter(i);
        }
    };
}
