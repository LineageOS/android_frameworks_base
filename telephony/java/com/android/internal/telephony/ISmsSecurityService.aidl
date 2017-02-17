/*
 * Copyright (c) 2016, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *   * Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *   * Redistributions in binary form must reproduce the above
 *     copyright notice, this list of conditions and the following
 *     disclaimer in the documentation and/or other materials provided
 *     with the distribution.
 *   * Neither the name of The Linux Foundation nor the names of its
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.internal.telephony;

import com.android.internal.telephony.ISmsSecurityAgent;
import com.android.internal.telephony.SmsAuthorizationRequest;

/**
 * ISmsSecurityService exposes a service that monitors the dispatch of outgoing SMS messages
 * and notifies a registered ISmsSecurityAgent in order to authorize or reject the dispatch
 * of each outgoing SMS message.
 *
 * @hide
 */
interface ISmsSecurityService {
    /**
     * Registers an agent in order to receive requests for outgoing SMS messages on which
     * it can accept or reject the request for the dispatch of each SMS message.
     * <b>Only one agent can be registered at one time.</b>
     * @param agent the agent to be registered.
     * @return true if the registration succeeds, false otherwise.
     */
    boolean register(in ISmsSecurityAgent agent);

    /**
     * Unregisters the previously registered agent and causes the security
     * service to no longer rely on the agent for a decision regarding
     * successive SMS messages being dispatched allowing all successive messages to be dispatched.
     *
     * @param agent the agent to be unregistered.
     * @return true if the unregistration succeeds, false otherwise.
     */
    boolean unregister(in ISmsSecurityAgent agent);

    /**
     * Allows the registered ISmsSecurityAgent implementation to asynchronously send a response
     * on whether it will accept/reject the dispatch of the SMS message.
     * <b>If the agent responds after the OEM defined timeout it may not be able to
     * interfere on whether the SMS was sent or not.</b>
     * @param request the request related to an outgoing SMS message to accept/reject.
     * @param accepted true to accept, false to reject.
     * return true if the response took effect, false if a response has already been sent for this
     * request or an OEM specific timeout already happened.
     */
    boolean sendResponse(in SmsAuthorizationRequest request, boolean authorized);
}
