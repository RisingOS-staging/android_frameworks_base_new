/*
 * Copyright (C) 2023-2024 The risingOS Android Project
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
package com.android.systemui.util;

import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.os.Handler;
import android.provider.Settings;
import android.util.TypedValue;

import com.android.systemui.Dependency;

public class StatusBarUtils {

    public static final String LEFT_PADDING = "statusbar_left_padding";
    public static final String RIGHT_PADDING = "statusbar_right_padding";
    public static final String TOP_PADDING = "statusbar_top_padding";

    private int mLeftPad;
    private int mRightPad;
    private int mTopPad;

    private Context mContext;
    private Resources mRes;

    private LayoutChangeListener mListener;
    private ContentObserver mContentObserver;

    public interface LayoutChangeListener {
        void onLayoutChanged(int leftPadding, int rightPadding, int topPadding);
    }

    private static StatusBarUtils sInstance;

    private StatusBarUtils(Context context) {
        mContext = context;
        mRes = mContext.getResources();
        mContentObserver = new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                updatePadding();
                notifyChange();
            }
        };
    }

    public static synchronized StatusBarUtils getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new StatusBarUtils(context.getApplicationContext());
        }
        return sInstance;
    }

    public void setLayoutChangeListener(LayoutChangeListener listener) {
        mListener = listener;
        if (mListener != null) {
            mListener.onLayoutChanged(mLeftPad, mRightPad, mTopPad);
        }
    }

    public int getLeftPadding() {
        return mLeftPad;
    }

    public int getRightPadding() {
        return mRightPad;
    }

    public int getTopPadding() {
        return mTopPad;
    }

    public void updatePadding() {
        float leftPadding = Settings.System.getFloat(
                mContext.getContentResolver(), LEFT_PADDING, getDefaultLeftPadding());
        float rightPadding = Settings.System.getFloat(
                mContext.getContentResolver(), RIGHT_PADDING, getDefaultRightPadding());
        float topPadding = Settings.System.getFloat(
                mContext.getContentResolver(), TOP_PADDING, getDefaultTopPadding());
        mLeftPad = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, leftPadding, mRes.getDisplayMetrics()));
        mRightPad = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, rightPadding, mRes.getDisplayMetrics()));
        mTopPad = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, topPadding, mRes.getDisplayMetrics()));
    }

    public int getDefaultLeftPadding() {
        return mRes.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_padding_start);
    }

    public int getDefaultRightPadding() {
        return mRes.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_padding_end);
    }

    public int getDefaultTopPadding() {
        return mRes.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_padding_top);
    }

    private void notifyChange() {
        if (mListener != null) {
            mListener.onLayoutChanged(mLeftPad, mRightPad, mTopPad);
        }
    }

    public void registerObservers() {
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(LEFT_PADDING), false, mContentObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(RIGHT_PADDING), false, mContentObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(TOP_PADDING), false, mContentObserver);
        mContentObserver.onChange(true);
    }

    public void unregisterObservers() {
        mContext.getContentResolver().unregisterContentObserver(mContentObserver);
    }
}
