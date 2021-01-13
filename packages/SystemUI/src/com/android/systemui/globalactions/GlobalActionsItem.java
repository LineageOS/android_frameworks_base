/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.globalactions;

import android.content.Context;
import android.text.Layout;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.widget.LinearLayout;
import android.widget.LinearLayout.LayoutParams;
import android.widget.TextView;

import com.android.internal.R;

/**
 * Layout for GlobalActions items.
 */
public class GlobalActionsItem extends LinearLayout {
    public GlobalActionsItem(Context context) {
        super(context);
    }

    public GlobalActionsItem(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public GlobalActionsItem(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    private TextView getTextView() {
        return (TextView) findViewById(R.id.message);
    }

    /**
     * Sets this item to marquee or not.
     */
    public void setMarquee(boolean marquee) {
        TextView text = getTextView();
        text.setSingleLine(marquee);
        text.setEllipsize(marquee ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
    }

    /**
     * Sets message top margin depending on linecount
     */
    public void setMessageMargin() {
        TextView message = findViewById(R.id.message);

        if (message != null) {
            LayoutParams params = (LinearLayout.LayoutParams) message.getLayoutParams();
            int marginTop = (int) getResources().getDimension(getTextView().getLineCount() > 1
                    ? com.android.systemui.R.dimen
                            .global_actions_power_dialog_twoline_message_top_margin
                    : com.android.systemui.R.dimen
                            .global_actions_power_dialog_message_top_margin);

            params.setMargins(params.leftMargin, marginTop, params.rightMargin,
                    params.bottomMargin);
            message.setLayoutParams(params);
        }
    }

    /**
     * Determines whether the message for this item has been truncated.
     */
    public boolean isTruncated() {
        TextView message = getTextView();
        if (message != null) {
            Layout messageLayout = message.getLayout();
            if (messageLayout != null) {
                if (messageLayout.getLineCount() > 0) {
                    // count the number of ellipses in the last line.
                    int ellipses = messageLayout.getEllipsisCount(
                            messageLayout.getLineCount() - 1);
                    // If ellipses are present, the line was forced to truncate.
                    return ellipses > 0;
                }
            }
        }
        return false;
    }
}
