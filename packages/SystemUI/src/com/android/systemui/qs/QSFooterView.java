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

package com.android.systemui.qs;

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;

import android.content.Context;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.settingslib.development.DevelopmentSettingsEnabler;
import com.android.settingslib.net.DataUsageController;
import com.android.systemui.FontSizeUtils;
import com.android.systemui.res.R;

/**
 * Footer of expanded Quick Settings, tiles page indicator, (optionally) build number and
 * {@link FooterActionsView}
 */
public class QSFooterView extends FrameLayout {
    private static final String TAG = "QSFooterView";

    private PageIndicator mPageIndicator;
    private TextView mUsageText;
    private View mEditButton;

    @Nullable
    protected TouchAnimator mFooterAnimator;

    private boolean mQsDisabled;
    private boolean mExpanded;
    private float mExpansionAmount;

    private boolean mShouldShowDataUsage;
    private boolean mShouldShowUsageText;
 
    @Nullable
    private OnClickListener mExpandClickListener;

    private DataUsageController mDataController;
    private SubscriptionManager mSubManager;

    private boolean mHasNoSims;
    private boolean mIsWifiConnected;
    private String mWifiSsid;
    private int mSubId;
    private int mCurrentDataSubId;
    
    private static final long DEBOUNCE_DELAY_MS = 200;
    private Handler mHandler = new Handler();
    private Runnable mSetUsageTextRunnable = this::setUsageText;

    public QSFooterView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDataController = new DataUsageController(context);
        mSubManager = context.getSystemService(SubscriptionManager.class);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mPageIndicator = findViewById(R.id.footer_page_indicator);
        mUsageText = findViewById(R.id.build);
        mEditButton = findViewById(android.R.id.edit);

        updateResources();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setClickable(false);
        setUsageText();

        mUsageText.setOnClickListener(this::onUsageTextClick);
    }
    
    private void onUsageTextClick(View v) {
        if (mSubManager.getActiveSubscriptionInfoCount() > 1) {
            // Get opposite slot 2 ^ 3 = 1, 1 ^ 3 = 2
            mSubId = mSubId ^ 3;
            setUsageText();
            mUsageText.setSelected(false);
            postDelayed(() -> mUsageText.setSelected(true), 1000);
        }
    }
    
    private void setUsageTextDebounced() {
        mHandler.removeCallbacks(mSetUsageTextRunnable);
        mHandler.postDelayed(mSetUsageTextRunnable, DEBOUNCE_DELAY_MS);
    }

    private void setUsageText() {
        if (mUsageText == null || !mShouldShowDataUsage || !mExpanded) return;
        DataUsageController.DataUsageInfo info;
        String suffix;
        if (mIsWifiConnected) {
            info = mDataController.getWifiDailyDataUsageInfo(true);
            if (info == null) {
                info = mDataController.getWifiDailyDataUsageInfo(false);
            }
            suffix = mContext.getString(R.string.usage_wifi_default_suffix);
        } else if (!mHasNoSims) {
            mDataController.setSubscriptionId(mSubId);
            info = mDataController.getDailyDataUsageInfo();
            suffix = mContext.getString(R.string.usage_data_default_suffix);
        } else {
            mShouldShowUsageText = false;
            mUsageText.setText(null);
            updateVisibilities();
            return;
        }
        if (info == null) {
            Log.w(TAG, "setUsageText: DataUsageInfo is NULL.");
            return;
        }
        // Setting text actually triggers a layout pass (because the text view is set to
        // wrap_content width and TextView always relayouts for this). Avoid needless
        // relayout if the text didn't actually change.
        String text = formatDataUsage(info.usageLevel, suffix);
        if (!TextUtils.equals(text, mUsageText.getText())) {
            mUsageText.setText(text);
        }
        mShouldShowUsageText = true;
        updateVisibilities();
    }

    private String formatDataUsage(long byteValue, String suffix) {
        // Format the data usage to ": 1.23 GB (Wi-Fi)"
        return new StringBuilder()
                .append(": ")
                .append(Formatter.formatFileSize(getContext(), byteValue, Formatter.FLAG_IEC_UNITS))
                .append(" ")
                .append(suffix)
                .toString();
    }

    protected void setWifiSsid(String ssid) {
        if (mWifiSsid != ssid) {
            mWifiSsid = ssid;
            setUsageTextDebounced();
        }
    }

    protected void setIsWifiConnected(boolean connected) {
        if (mIsWifiConnected != connected) {
            mIsWifiConnected = connected;
            setUsageTextDebounced();
        }
    }

    protected void setNoSims(boolean hasNoSims) {
        if (mHasNoSims != hasNoSims) {
            mHasNoSims = hasNoSims;
            setUsageTextDebounced();
        }
    }

    protected void setCurrentDataSubId(int subId) {
        if (mCurrentDataSubId != subId) {
            mSubId = mCurrentDataSubId = subId;
            setUsageTextDebounced();
        }
    }

    protected void setShowDataUsage(boolean show) {
        if (mShouldShowDataUsage != show) {
            mShouldShowDataUsage = show;
            updateVisibilities();
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();
    }

    private void updateResources() {
        updateFooterAnimator();
        updateEditButtonResources();
        updateBuildTextResources();
        MarginLayoutParams lp = (MarginLayoutParams) getLayoutParams();
        lp.height = getResources().getDimensionPixelSize(R.dimen.qs_footer_height);
        int sideMargin = getResources().getDimensionPixelSize(R.dimen.qs_footer_margin);
        lp.leftMargin = sideMargin;
        lp.rightMargin = sideMargin;
        lp.bottomMargin = getResources().getDimensionPixelSize(R.dimen.qs_footers_margin_bottom);
        setLayoutParams(lp);
    }

    private void updateEditButtonResources() {
        int size = getResources().getDimensionPixelSize(R.dimen.qs_footer_action_button_size);
        int padding = getResources().getDimensionPixelSize(R.dimen.qs_footer_icon_padding);
        MarginLayoutParams lp = (MarginLayoutParams) mEditButton.getLayoutParams();
        lp.height = size;
        lp.width = size;
        mEditButton.setLayoutParams(lp);
        mEditButton.setPadding(padding, padding, padding, padding);
    }

    private void updateBuildTextResources() {
        FontSizeUtils.updateFontSizeFromStyle(mUsageText, R.style.TextAppearance_QS_Status_DataUsage);
    }

    private void updateFooterAnimator() {
        mFooterAnimator = createFooterAnimator();
    }

    @Nullable
    private TouchAnimator createFooterAnimator() {
        TouchAnimator.Builder builder = new TouchAnimator.Builder()
                .addFloat(mPageIndicator, "alpha", 0, 1)
                .addFloat(mUsageText, "alpha", 0, 1)
                .addFloat(mEditButton, "alpha", 0, 1)
                .setStartDelay(0.9f);
        return builder.build();
    }

    /** */
    public void setKeyguardShowing() {
        setExpansion(mExpansionAmount);
    }

    public void setExpandClickListener(OnClickListener onClickListener) {
        mExpandClickListener = onClickListener;
    }

    void setExpanded(boolean expanded) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        updateEverything();
    }

    /** */
    public void setExpansion(float headerExpansionFraction) {
        mExpansionAmount = headerExpansionFraction;
        if (mFooterAnimator != null) {
            mFooterAnimator.setPosition(headerExpansionFraction);
        }

        if (mUsageText == null || !mShouldShowDataUsage) return;
        if (headerExpansionFraction == 1.0f) {
            postDelayed(() -> mUsageText.setSelected(true), 1000);
        } else if (headerExpansionFraction == 0.0f) {
            mUsageText.setSelected(false);
            mSubId = mCurrentDataSubId;
        }
    }

    void disable(int state2) {
        final boolean disabled = (state2 & DISABLE2_QUICK_SETTINGS) != 0;
        if (disabled == mQsDisabled) return;
        mQsDisabled = disabled;
        updateEverything();
    }

    void updateEverything() {
        post(() -> {
            updateVisibilities();
            setUsageTextDebounced();
        });
    }

    private void updateVisibilities() {
        mUsageText.setVisibility(mShouldShowDataUsage && mExpanded && mShouldShowUsageText
                ? View.VISIBLE : View.INVISIBLE);
    }
}
