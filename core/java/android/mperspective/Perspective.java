/*
 * Copyright 2015-2016 Preetam J. D'Souza
 * Copyright 2016 The Maru OS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.mperspective;

/**
 * Perspectives are interfaces to hardware.
 *
 * @hide
 */
public class Perspective {

    public static final int STATE_STOPPED = 0;
    public static final int STATE_STARTING = 1;
    public static final int STATE_RUNNING = 2;
    public static final int STATE_STOPPING = 3;

    public static String stateToString(int state) {
        switch (state) {
            case Perspective.STATE_STARTING:
                return "STARTING";
            case Perspective.STATE_RUNNING:
                return "RUNNING";
            case Perspective.STATE_STOPPING:
                return "STOPPING";
            case Perspective.STATE_STOPPED:
                return "STOPPED";
            default:
                return "UNKNOWN";
        }
    }

}
