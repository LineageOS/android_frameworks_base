/*
 * Copyright (C) 2017 The Android Open Source Project
 * Copyright (C) 2017-2019 The LineageOS Project
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

package com.android.server.telecom;

import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;

public class SensitivePhoneNumber {
    private static final String LOG_TAG = "SensitivePhoneNumber";
    private static final String ns = null;

    private String mNetworkNumeric;
    private ArrayList<SensitivePhoneNumberInfo> mPhoneNumberInfos;

    public SensitivePhoneNumber(String networkNumeric, ArrayList<SensitivePhoneNumberInfo> infos) {
        mNetworkNumeric = networkNumeric;
        mPhoneNumberInfos = infos;
    }

    public String getNetworkNumeric() {
        return mNetworkNumeric;
    }

    public ArrayList<SensitivePhoneNumberInfo> getPhoneNumberInfos() {
        return mPhoneNumberInfos;
    }

    public void setNetworkNumeric(String networkNumeric) {
        mNetworkNumeric = networkNumeric;
    }

    public void setPhoneNumberInfos(ArrayList<SensitivePhoneNumberInfo> infos) {
        mPhoneNumberInfos = infos;
    }

    public void addPhoneNumberInfo(SensitivePhoneNumberInfo info) {
        mPhoneNumberInfos.add(info);
    }

    public static SensitivePhoneNumber readSensitivePhoneNumbers (XmlPullParser parser)
                throws XmlPullParserException, IOException {
        parser.require(XmlPullParser.START_TAG, ns, "sensitivePN");

        String network = parser.getAttributeValue(null, "network");

        ArrayList<SensitivePhoneNumberInfo> infos = null;
        infos = readPhoneNumberInfo(parser);

        return new SensitivePhoneNumber(network, infos);
    }

    private static ArrayList<SensitivePhoneNumberInfo> readPhoneNumberInfo (XmlPullParser parser)
                throws XmlPullParserException, IOException {
        ArrayList<SensitivePhoneNumberInfo> numberInfos = new ArrayList<>();
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }

            parser.require(XmlPullParser.START_TAG, ns, "item");
            SensitivePhoneNumberInfo item = parseItem(parser);
            numberInfos.add(item);
            parser.require(XmlPullParser.END_TAG, ns, "item");
        }
        return numberInfos;
    }

    private static SensitivePhoneNumberInfo parseItem(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        SensitivePhoneNumberInfo item = new SensitivePhoneNumberInfo();
        while (parser.next() != XmlPullParser.END_TAG) {
            int eventType = parser.getEventType();

            if (eventType == XmlPullParser.START_TAG) {
                String tag = parser.getName();
                String value = "";
                if (parser.next() == XmlPullParser.TEXT) {
                    value = parser.getText();
                    parser.nextTag();
                }
                parser.require(XmlPullParser.END_TAG, ns, tag);

                item.set(tag, value);
            } else if (eventType == XmlPullParser.TEXT) {
                String number = parser.getText();
                if (!number.trim().isEmpty()) {
                    item.set("number", number);
                } else {
                    // skipping all whitespace
                    continue;
                }
            }
        }
        return item;
    }
}
