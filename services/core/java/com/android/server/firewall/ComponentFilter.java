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

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

public class ComponentFilter implements Filter {
    public final ComponentName mComponentName;

    public ComponentFilter(ComponentName componentName) {
        mComponentName = componentName;
    }

    @Override
    public boolean matches(IntentFirewall ifw, ComponentName resolvedComponent, Intent intent,
            int callerUid, int callerPid, String resolvedType, int receivingUid) {
        return mComponentName.equals(resolvedComponent);
    }

    public static final FilterFactory FACTORY = new FilterFactory("component-filter") {
        private static final String ATTR_NAME = "name";

        @Override
        public Filter newFilter(XmlPullParser parser)
                throws IOException, XmlPullParserException {
            String componentStr = parser.getAttributeValue(null, ATTR_NAME);
            if (componentStr == null) {
                throw new XmlPullParserException("Component name must be specified.",
                        parser, null);
            }

            ComponentName componentName = ComponentName.unflattenFromString(componentStr);
            if (componentName == null) {
                throw new XmlPullParserException("Invalid component name: " + componentStr);
            }
            return new ComponentFilter(componentName);
        }
    };
}
