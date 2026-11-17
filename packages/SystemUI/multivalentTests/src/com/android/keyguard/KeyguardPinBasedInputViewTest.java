/*
 * SPDX-FileCopyrightText: The BlissRoms Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.keyguard;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.view.KeyEvent;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.res.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class KeyguardPinBasedInputViewTest extends SysuiTestCase {

    private KeyguardPINView mPinView;
    private PasswordTextView mPasswordEntry;

    @Before
    public void setup() {
        View root = View.inflate(mContext, R.layout.keyguard_pin_view, null);
        mPinView = root.requireViewById(R.id.keyguard_pin_view);
        mPasswordEntry = root.requireViewById(R.id.pinEntry);
    }

    @Test
    public void onKeyDown_letterKeyMappedToDigit_entersDigit() {
        KeyEvent event = keyEventMatching('1');

        boolean handled = mPinView.onKeyDown(KeyEvent.KEYCODE_W, event);

        assertThat(handled).isTrue();
        assertThat(mPasswordEntry.getText()).isEqualTo("1");
    }

    @Test
    public void onKeyDown_letterKeyWithoutDigit_isIgnored() {
        KeyEvent event = keyEventMatching((char) 0);

        boolean handled = mPinView.onKeyDown(KeyEvent.KEYCODE_A, event);

        assertThat(handled).isFalse();
        assertThat(mPasswordEntry.getText()).isEmpty();
    }

    private static KeyEvent keyEventMatching(char digit) {
        KeyEvent event = mock(KeyEvent.class);
        when(event.getMatch(any(char[].class))).thenReturn(digit);
        return event;
    }
}
