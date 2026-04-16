/*
 * Copyright (C) 2011 The Android Open Source Project
 * Copyright (C) 2025 Raimondas Rimkus
 * Copyright (C) 2024 wittmane
 * Copyright (C) 2019 Micha LaQua
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

package rkr.simplekeyboard.inputmethod.latin.settings;

import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.view.inputmethod.EditorInfo;

import rkr.simplekeyboard.inputmethod.R;
import rkr.simplekeyboard.inputmethod.latin.InputAttributes;
import rkr.simplekeyboard.inputmethod.latin.invisible.InvisibleMode;

// Non-final for testing via mock library.
public class SettingsValues {
    public static final float DEFAULT_SIZE_SCALE = 1.0f; // 100%

    // From resources:
    public final SpacingAndPunctuations mSpacingAndPunctuations;
    // From configuration:
    public final boolean mHasHardwareKeyboard;
    public final int mDisplayOrientation;
    // From preferences, in the same order as xml/prefs.xml:
    public final boolean mAutoCap;
    public final boolean mVibrateOn;
    public final boolean mSoundOn;
    public final boolean mKeyPreviewPopupOn;
    public final boolean mUseOnScreen;
    public final boolean mShowsLanguageSwitchKey;
    public final boolean mImeSwitchEnabled;
    public final int mKeyLongpressTimeout;
    public final boolean mShowSpecialChars;
    public final boolean mShowNumberRow;
    public final boolean mSpaceSwipeEnabled;
    public final boolean mDeleteSwipeEnabled;
    public final boolean mInvisibleFeatureEnabled;
    public final InvisibleMode mInvisibleMode;
    public final boolean mInvisiblePanicGestureEnabled;
    public final boolean mInvisibleShowStatusToasts;
    public final boolean mInvisiblePreferCoverByDefault;
    public final boolean mInvisibleFirstWordCoverEnabled;
    public final String mInvisibleEmojiMarker;
    public final boolean mInvisibleClipboardPopupEnabled;
    public final boolean mAiEnabled;
    public final boolean mAiRewriteOnSend;
    public final String mAiProvider;
    public final String mAiBaseUrl;
    public final String mAiApiKey;
    public final String mAiModel;
    public final String mAiSystemPrompt;

    // From the input box
    public final InputAttributes mInputAttributes;

    // Deduced settings
    public final float mKeypressSoundVolume;
    public final int mKeyPreviewPopupDismissDelay;

    // Debug settings
    public final float mKeyboardHeightScale;

    public final int mBottomOffsetPortrait;

    public SettingsValues(final SharedPreferences prefs, final Resources res,
            final InputAttributes inputAttributes) {
        // Get the resources
        mSpacingAndPunctuations = new SpacingAndPunctuations(res);

        // Store the input attributes
        mInputAttributes = inputAttributes;

        // Get the settings preferences
        mAutoCap = prefs.getBoolean(Settings.PREF_AUTO_CAP, true);
        mVibrateOn = Settings.readVibrationEnabled(prefs, res);
        mSoundOn = Settings.readKeypressSoundEnabled(prefs, res);
        mKeyPreviewPopupOn = Settings.readKeyPreviewPopupEnabled(prefs, res);
        mUseOnScreen = Settings.readUseOnScreenKeyboard(prefs);
        mShowsLanguageSwitchKey = Settings.readShowLanguageSwitchKey(prefs);
        mImeSwitchEnabled = Settings.readEnableImeSwitch(prefs);
        mHasHardwareKeyboard = Settings.readHasHardwareKeyboard(res.getConfiguration());

        // Compute other readable settings
        mKeyLongpressTimeout = Settings.readKeyLongpressTimeout(prefs, res);
        mKeypressSoundVolume = Settings.readKeypressSoundVolume(prefs);
        mKeyPreviewPopupDismissDelay = res.getInteger(R.integer.config_key_preview_linger_timeout);
        mKeyboardHeightScale = Settings.readKeyboardHeight(prefs, DEFAULT_SIZE_SCALE);
        mBottomOffsetPortrait = Settings.readBottomOffsetPortrait(prefs);
        mDisplayOrientation = res.getConfiguration().orientation;
        mShowSpecialChars = Settings.readShowSpecialChars(prefs);
        mShowNumberRow = Settings.readShowNumberRow(prefs);
        mSpaceSwipeEnabled = Settings.readSpaceSwipeEnabled(prefs);
        mDeleteSwipeEnabled = Settings.readDeleteSwipeEnabled(prefs);
        mInvisibleFeatureEnabled = Settings.readInvisibleFeatureEnabled(prefs);
        mInvisibleMode = InvisibleMode.fromPreferenceValue(
                prefs.getString(Settings.PREF_INVISIBLE_MODE, InvisibleMode.AUTO.getPreferenceValue()));
        mInvisiblePanicGestureEnabled = Settings.readInvisiblePanicGestureEnabled(prefs);
        mInvisibleShowStatusToasts = Settings.readInvisibleShowToasts(prefs);
        mInvisiblePreferCoverByDefault = Settings.readInvisiblePreferCover(prefs);
        mInvisibleFirstWordCoverEnabled = Settings.readInvisibleFirstWordCover(prefs);
        mInvisibleEmojiMarker = Settings.readInvisibleEmojiMarker(prefs);
        mInvisibleClipboardPopupEnabled = Settings.readInvisibleClipboardPopupEnabled(prefs);
        mAiEnabled = Settings.readAiEnabled(prefs);
        mAiRewriteOnSend = Settings.readAiRewriteOnSend(prefs);
        mAiProvider = Settings.readAiProvider(prefs);
        mAiBaseUrl = Settings.readAiBaseUrl(prefs);
        mAiApiKey = Settings.readAiApiKey(prefs);
        mAiModel = Settings.readAiModel(prefs);
        mAiSystemPrompt = Settings.readAiSystemPrompt(prefs);
    }

    public boolean isWordSeparator(final int code) {
        return mSpacingAndPunctuations.isWordSeparator(code);
    }

    public boolean isLanguageSwitchKeyDisabled() {
        return !mShowsLanguageSwitchKey;
    }

    public boolean isSameInputType(final EditorInfo editorInfo) {
        return mInputAttributes.isSameInputType(editorInfo);
    }

    public boolean hasSameOrientation(final Configuration configuration) {
        return mDisplayOrientation == configuration.orientation;
    }
}
