package com.android.server.wm.onehand;

import android.view.Gravity;

class OneHandedMode {

    private static void ensureGravityValid(int gravity) {
        if ((gravity & ~(Gravity.LEFT | Gravity.RIGHT | Gravity.TOP | Gravity.BOTTOM)) != 0) {
            throw new RuntimeException("Invalid gravity for OneHandedMode:" + gravity);
        }
    }

    private static void ensureScaleValid(float scale) {
        if (scale <= 0)
            throw new RuntimeException("Invalid scale for OneHandedMode:" + scale);
    }

    private static String gravityToString(int gravity) {
        StringBuilder b = new StringBuilder();

        if ((gravity & Gravity.LEFT)== Gravity.LEFT) {
            b.append("LEFT");
        }
        if ((gravity & Gravity.RIGHT)== Gravity.RIGHT) {
            b.append("RIGHT");
        }
        if ((gravity & Gravity.TOP)== Gravity.TOP) {
            b.append("-TOP");
        }
        if ((gravity & Gravity.BOTTOM)== Gravity.BOTTOM) {
            b.append("-BOTTOM");
        }
        return b.toString();
    }

    int xAdj;
    int yAdj;
    private float mScale;
    private int mGravity;

    /**
     * Create a OneHandedMode and initialize it as an OFF mode
     */
    OneHandedMode() {
        reset();
    }

    /**
     * Create OneHandedMode
     * @param x OffsetX, according to gravity
     * @param y OffsetY, according to gravity
     * @param scale The scale factor
     * @param gravity Where the transformed screen docks to;
     */
    OneHandedMode(int x, int y, float scale, int gravity) {
        xAdj = x;
        yAdj = y;
        setScale(scale);
        setGravity(gravity);
    }

    OneHandedMode(OneHandedMode src) {
        set(src);
    }

    int getGravity() {
        return mGravity;
    }

    void setGravity(int gravity) {
        ensureGravityValid(gravity);
        mGravity = gravity;
    }

    boolean hasGravity(int gravity) {
        return ((mGravity & gravity) == gravity);
    }

    float getScale() {
        return mScale;
    }

    void setScale(float scale) {
        ensureScaleValid(scale);
        mScale = scale;
    }

    boolean isOffMode() {
        return xAdj == 0 && yAdj == 0 && mScale == 1 && mGravity == (Gravity.LEFT | Gravity.TOP);
    }

    void set(OneHandedMode mode) {
        xAdj = mode.xAdj;
        yAdj = mode.yAdj;
        mScale = mode.mScale;
        mGravity = mode.mGravity;
    }

    /**
     * Reset this instance to OFF mode
     */
    void reset() {
        xAdj = 0;
        yAdj = 0;
        setScale(1);
        setGravity(Gravity.LEFT | Gravity.TOP);
    }

    int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (o != null && o instanceof OneHandedMode) {
            OneHandedMode other = (OneHandedMode)o;
            return other.xAdj == xAdj
                    && other.yAdj == yAdj
                    && other.mScale == mScale
                    && other.mGravity == mGravity;
        } else {
            return false;
        }
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        b.append("OneHandedMode: (");
        b.append(xAdj);
        b.append(",");
        b.append(yAdj);
        b.append("), ");
        b.append(mScale);
        b.append(", ");
        b.append(gravityToString(mGravity));
        return b.toString();
    }
}