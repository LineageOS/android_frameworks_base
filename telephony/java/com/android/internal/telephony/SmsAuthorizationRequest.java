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

import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

/**
 * This class represents a request from the {@link ISmsSecurityService} to trusted parties
 * in order to allow third party components to participate in the decision process to accept
 * or reject a request to send an SMS message.
 *
 * @hide
 */
public class SmsAuthorizationRequest implements Parcelable {

    private final ISmsSecurityService service;

    private final IBinder token;

    public final String packageName;

    public final String destinationAddress;

    public final String message;

    public SmsAuthorizationRequest(final Parcel source) {
        this.service = ISmsSecurityService.Stub.asInterface(source.readStrongBinder());
        this.token = source.readStrongBinder();
        this.packageName = source.readString();
        this.destinationAddress = source.readString();
        this.message = source.readString();
    }

    public SmsAuthorizationRequest(final ISmsSecurityService service,
            final IBinder binderToken,
            final String packageName,
            final String destinationAddress,
            final String message) {
        this.service = service;
        this.token = binderToken;
        this.packageName = packageName;
        this.destinationAddress = destinationAddress;
        this.message = message;
    }

    @Override
    public void writeToParcel(final Parcel dest, final int flags) {
        dest.writeStrongBinder(service.asBinder());
        dest.writeStrongBinder(token);
        dest.writeString(packageName);
        dest.writeString(destinationAddress);
        dest.writeString(message);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static Parcelable.Creator<SmsAuthorizationRequest> CREATOR =
            new Creator<SmsAuthorizationRequest>() {
        @Override
        public SmsAuthorizationRequest[] newArray(final int size) {
            return new SmsAuthorizationRequest[size];
        }

        @Override
        public SmsAuthorizationRequest createFromParcel(final Parcel source) {
            return new SmsAuthorizationRequest(source);
        }
    };

    public void accept() throws RemoteException{
        service.sendResponse(this, true);
    }

    public void reject() throws RemoteException {
        service.sendResponse(this, false);
    }

    public IBinder getToken() {
        return token;
    }

    @Override
    public String toString() {
        return String.format("[%s] (%s) # %s",
                this.packageName,
                this.destinationAddress,
                this.message);
    }
}
