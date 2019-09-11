/*
 * Copyright (C) 2017 The Android Open Source Project
 * Copyright (C) 2017 The LineageOS Project
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

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;

public class SensitivePhoneNumber {
    private static final String LOG_TAG = "SensitivePhoneNumber";
    private static final String ns = null;

    private String networkNumeric;
    private ArrayList<String> phoneNumbers;

    public SensitivePhoneNumber(String networkNumeric, ArrayList<String> phoneNumbers) {
        this.networkNumeric = networkNumeric;
        this.phoneNumbers = phoneNumbers;
    }

    public String getNetworkNumeric() {
        return networkNumeric;
    }

    public ArrayList<String> getPhoneNumbers() {
        return phoneNumbers;
    }

    public void setNetworkNumeric(String networkNumeric) {
        this.networkNumeric = networkNumeric;
    }

    public void setPhoneNumbers(ArrayList<String> phoneNumbers) {
        this.phoneNumbers = phoneNumbers;
    }

    public void addPhoneNumber(String phoneNumber) {
        this.phoneNumbers.add(phoneNumber);
    }

    public static SensitivePhoneNumber readSensitivePhoneNumbers (XmlPullParser parser)
                throws XmlPullParserException, IOException {
        parser.require(XmlPullParser.START_TAG, ns, "sensitivePN");

        String numeric = parser.getAttributeValue(null, "network");

        ArrayList<String> numbers = null;
        numbers = readPhoneNumber(parser);

        return new SensitivePhoneNumber(numeric, numbers);
    }

    private static ArrayList<String> readPhoneNumber (XmlPullParser parser)
                throws XmlPullParserException, IOException {
        ArrayList<String> numbers = new ArrayList<>();
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }
            parser.require(XmlPullParser.START_TAG, ns, "item");

            String item = "";
            if (parser.next() == XmlPullParser.TEXT) {
                item = parser.getText();
                parser.nextTag();
            }
            parser.require(XmlPullParser.END_TAG, ns, "item");

            numbers.add(item);
        }
        return numbers;
    }
}
