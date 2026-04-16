/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (C) 2025 Raimondas Rimkus
 * Copyright (C) 2021 wittmane
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

import android.content.ActivityNotFoundException;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Resources;
import android.text.method.ScrollingMovementMethod;
import android.net.Uri;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.util.Log;
import android.util.TypedValue;
import android.widget.TextView;

import rkr.simplekeyboard.inputmethod.R;
import rkr.simplekeyboard.inputmethod.latin.utils.ApplicationUtils;

public final class SettingsFragment extends InputMethodSettingsFragment {
    private static final String TAG = "SettingsFragment";

    @Override
    public void onCreate(final Bundle icicle) {
        super.onCreate(icicle);
        setHasOptionsMenu(true);
        addPreferencesFromResource(R.xml.prefs);
        final PreferenceScreen preferenceScreen = getPreferenceScreen();
        preferenceScreen.setTitle(
                ApplicationUtils.getActivityTitleResId(getActivity(), SettingsActivity.class));
        final Resources res = getResources();

        findPreference("privacy_policy").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                showDocumentDialog(R.string.privacy_policy, R.raw.privacy_policy);
                return true;
            }
        });
        findPreference("license").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                showDocumentDialog(R.string.license, R.raw.license_notice);
                return true;
            }
        });
    }

    private void openUrl(String uri) {
        try {
            final Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            startActivity(browserIntent);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "Browser not found");
        }
    }

    private void showDocumentDialog(final int titleRes, final int rawResId) {
        final TextView textView = new TextView(getActivity());
        final int padding = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20,
                getResources().getDisplayMetrics());
        textView.setPadding(padding, padding, padding, padding);
        textView.setText(readRawText(rawResId));
        textView.setTextIsSelectable(true);
        textView.setMovementMethod(new ScrollingMovementMethod());
        new AlertDialog.Builder(getActivity())
                .setTitle(titleRes)
                .setView(textView)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private CharSequence readRawText(final int rawResId) {
        try (java.io.InputStream inputStream = getResources().openRawResource(rawResId);
             java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream()) {
            final byte[] buffer = new byte[4096];
            int count;
            while ((count = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, count);
            }
            return outputStream.toString(java.nio.charset.StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            Log.e(TAG, "Unable to read local document", e);
            return "";
        }
    }
}
