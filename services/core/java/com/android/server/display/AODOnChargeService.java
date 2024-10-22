/*
 * Copyright (C) 2023-2024 the risingOS Android Project
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
package com.android.server.display;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import lineageos.providers.LineageSettings;

import com.android.server.SystemService;

public class AODOnChargeService extends SystemService {
    private static final String TAG = "AODOnChargeService";
    private static final String PULSE_ACTION = "com.android.systemui.doze.pulse";
    private static final int WAKELOCK_TIMEOUT_MS = 3000;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final PowerManager mPowerManager;

    private boolean mAODActive = false;
    private boolean mPluggedIn = false;
    private boolean mIsAODStateModifiedByService = false;
    private boolean mReceiverRegistered = false;

    private final BroadcastReceiver mPowerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null || !isServiceEnabled()) return;
            if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                if (isCharging(intent) && isPluggedIn(intent)) {
                    Slog.v(TAG, "Device plugged in and charging, enabling AOD");
                    mPluggedIn = true;
                    maybeActivateAOD();
                } else {
                    Slog.v(TAG, "Device not charging or unplugged, disabling AOD");
                    mPluggedIn = false;
                    maybeDeactivateAOD();
                }
            }
        }
    };

    private final ContentObserver mSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            if (isServiceEnabled()) {
                registerPowerReceiver();
            } else {
                unregisterPowerReceiver();
                if (mIsAODStateModifiedByService) {
                    Settings.Secure.putInt(mContext.getContentResolver(),
                         Settings.Secure.DOZE_ALWAYS_ON, 0);
                    mAODActive = false;
                    mIsAODStateModifiedByService = false;
                }
            }
        }
    };

    public AODOnChargeService(Context context) {
        super(context);
        mContext = context;
        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    }

    @Override
    public void onStart() {
        Slog.v(TAG, "Starting " + TAG);
        publishLocalService(AODOnChargeService.class, this);
        registerSettingsObserver();

        if (isServiceEnabled()) {
            registerPowerReceiver();
        }
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            Slog.v(TAG, "onBootPhase PHASE_BOOT_COMPLETED");
            Intent batteryStatus = mContext.registerReceiver(null, 
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (isServiceEnabled() && batteryStatus != null 
                && isCharging(batteryStatus) && isPluggedIn(batteryStatus)) {
                // reset AOD state on boot if service is enabled
                Settings.Secure.putInt(mContext.getContentResolver(), 
                    Settings.Secure.DOZE_ALWAYS_ON, 0);
                mIsAODStateModifiedByService = true;
                Slog.v(TAG, "Device is plugged in and charging on boot, enabling AOD");
                mPluggedIn = true;
                maybeActivateAOD();
            }
        }
    }

    private void registerPowerReceiver() {
        if (mReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        mContext.registerReceiver(mPowerReceiver, filter);
        mReceiverRegistered = true;
    }

    private void unregisterPowerReceiver() {
        if (!mReceiverRegistered) return;
        mContext.unregisterReceiver(mPowerReceiver);
        mReceiverRegistered = false;
    }

    private void registerSettingsObserver() {
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor("doze_always_on_charge_mode"), 
                true, 
                mSettingsObserver);
    }

    private void maybeActivateAOD() {
        if (!mAODActive && mPluggedIn) {
            Slog.v(TAG, "Activating AOD due to device being plugged in");
            setAutoAODChargeActive(true);
        }
    }

    private void maybeDeactivateAOD() {
        if (mAODActive && !mPluggedIn) {
            Slog.v(TAG, "Deactivating AOD due to device being unplugged");
            setAutoAODChargeActive(false);
        }
    }

    private void setAutoAODChargeActive(boolean activate) {
        if (!isServiceEnabled()) {
            if (mIsAODStateModifiedByService) {
                Settings.Secure.putInt(mContext.getContentResolver(),
                        Settings.Secure.DOZE_ALWAYS_ON, 0);
            }
            return;
        }
        boolean isAODCurrentlyActive = Settings.Secure.getInt(
                mContext.getContentResolver(),
                Settings.Secure.DOZE_ALWAYS_ON, 0) == 1;
        if (activate && isAODCurrentlyActive && !mIsAODStateModifiedByService) {
            Slog.v(TAG, "AOD is already enabled, skipping activation");
            return;
        }
        if (!activate && !mIsAODStateModifiedByService) {
            Slog.v(TAG, "AOD was not modified by this service, skipping de-activation");
            return;
        }
        if (activate) {
            Settings.Secure.putInt(mContext.getContentResolver(),
                    Settings.Secure.DOZE_ALWAYS_ON, 1);
            mAODActive = true;
            mIsAODStateModifiedByService = true;
            Slog.v(TAG, "AOD activated by service");
        } else {
            Settings.Secure.putInt(mContext.getContentResolver(),
                    Settings.Secure.DOZE_ALWAYS_ON, 0);
            mAODActive = false;
            mIsAODStateModifiedByService = false;
            Slog.v(TAG, "AOD deactivated by service");
        }
        handleAODStateChange(activate);
    }

    private void handleAODStateChange(boolean activate) {
        if (mPowerManager.isInteractive()) {
            Slog.v(TAG, "Screen is already on, no further action needed");
        } else {
            if ((activate && !isWakeOnPlugEnabled()) || !activate) {
                mContext.sendBroadcast(new Intent(PULSE_ACTION));
            }
        }
    }

    private boolean isServiceEnabled() {
        return Settings.System.getInt(mContext.getContentResolver(),
                "doze_always_on_charge_mode", 0) != 0;
    }
    
    private boolean isWakeOnPlugEnabled() {
        return LineageSettings.Global.getInt(mContext.getContentResolver(),
                LineageSettings.Global.WAKE_WHEN_PLUGGED_OR_UNPLUGGED,
                (mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_unplugTurnsOnScreen) ? 1 : 0)) == 1;
    }

    private boolean isCharging(Intent intent) {
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
    }

    private boolean isPluggedIn(Intent intent) {
        int chargePlug = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
        return chargePlug == BatteryManager.BATTERY_PLUGGED_AC
                || chargePlug == BatteryManager.BATTERY_PLUGGED_USB
                || chargePlug == BatteryManager.BATTERY_PLUGGED_WIRELESS;
    }
}
