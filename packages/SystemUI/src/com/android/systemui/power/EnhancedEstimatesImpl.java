package com.android.systemui.power;

import android.content.Context;
import android.os.BatteryStats;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserManager;

import com.android.internal.os.BatteryStatsHelper;

import com.android.settingslib.fuelgauge.Estimate;
import com.android.settingslib.fuelgauge.EstimateKt;
import com.android.settingslib.utils.PowerUtil;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class EnhancedEstimatesImpl implements EnhancedEstimates {

    private BatteryStatsHelper mBatteryStatsHelper;
    private UserManager mUserManager;

    @Inject
    public EnhancedEstimatesImpl(Context context) {
        mBatteryStatsHelper = new BatteryStatsHelper(context);
        mUserManager = context.getSystemService(UserManager.class);
    }

    @Override
    public boolean isHybridNotificationEnabled() {
        return true;
    }

    @Override
    public Estimate getEstimate() {
        try {
            BatteryStats stats = mBatteryStatsHelper.getStats();
            if (stats != null) {
                long remaining = stats.computeBatteryTimeRemaining(PowerUtil.convertMsToUs(
                        SystemClock.elapsedRealtime()));
                if (remaining != -1) {
                    return new Estimate(PowerUtil.convertUsToMs(remaining), false,
                            EstimateKt.AVERAGE_TIME_TO_DISCHARGE_UNKNOWN);
                }
            }
        } catch (Exception e) {
        }
        return null;
    }

    @Override
    public long getLowWarningThreshold() {
        return 0;
    }

    @Override
    public long getSevereWarningThreshold() {
        return 0;
    }

    @Override
    public boolean getLowWarningEnabled() {
        return true;
    }
}
