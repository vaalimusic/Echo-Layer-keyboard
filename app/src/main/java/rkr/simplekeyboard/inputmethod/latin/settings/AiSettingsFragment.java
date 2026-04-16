package rkr.simplekeyboard.inputmethod.latin.settings;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.LinearLayout;

import rkr.simplekeyboard.inputmethod.R;
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardLayoutSet;

public final class AiSettingsFragment extends SubScreenFragment {
    @Override
    public void onCreate(final Bundle icicle) {
        super.onCreate(icicle);
        addPreferencesFromResource(R.xml.prefs_screen_ai);

        final ListPreference aiProviderPreference =
                (ListPreference) findPreference(Settings.PREF_AI_PROVIDER);
        if (aiProviderPreference != null) {
            aiProviderPreference.setSummary(aiProviderPreference.getEntry());
        }

        bindTextPreference(Settings.PREF_AI_BASE_URL, R.string.ai_base_url_title, false);
        bindTextPreference(Settings.PREF_AI_API_KEY, R.string.ai_api_key_title, true);
        bindTextPreference(Settings.PREF_AI_MODEL, R.string.ai_model_title, false);
        bindTextPreference(Settings.PREF_AI_SYSTEM_PROMPT, R.string.ai_system_prompt_title, false);
        updateSummaries();
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences prefs, final String key) {
        if (Settings.PREF_AI_PROVIDER.equals(key)) {
            KeyboardLayoutSet.onKeyboardThemeChanged();
            final ListPreference aiProviderPreference =
                    (ListPreference) findPreference(Settings.PREF_AI_PROVIDER);
            if (aiProviderPreference != null) {
                aiProviderPreference.setSummary(aiProviderPreference.getEntry());
            }
        }
        updateSummaries();
    }

    private void bindTextPreference(final String key, final int titleRes,
            final boolean concealValue) {
        final Preference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        preference.setOnPreferenceClickListener(clickedPreference -> {
            showSingleTextDialog(
                    titleRes,
                    getCurrentTextPreferenceValue(key),
                    value -> {
                        getSharedPreferences().edit().putString(key,
                                value == null ? "" : value.trim()).apply();
                        updateSummaries();
                        return true;
                    },
                    concealValue);
            return true;
        });
    }

    private void updateSummaries() {
        updateTextPreferenceSummary(Settings.PREF_AI_BASE_URL, R.string.ai_base_url_summary, false);
        updateTextPreferenceSummary(Settings.PREF_AI_API_KEY, R.string.ai_api_key_summary, true);
        updateTextPreferenceSummary(Settings.PREF_AI_MODEL, R.string.ai_model_summary, false);
        updateTextPreferenceSummary(Settings.PREF_AI_SYSTEM_PROMPT,
                R.string.ai_system_prompt_summary, false);
    }

    private void updateTextPreferenceSummary(final String key, final int defaultSummaryRes,
            final boolean concealValue) {
        final Preference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        final String value = getCurrentTextPreferenceValue(key);
        if (TextUtils.isEmpty(value)) {
            if (Settings.PREF_AI_BASE_URL.equals(key)) {
                preference.setSummary(getAiBaseUrlSummary());
            } else {
                preference.setSummary(defaultSummaryRes);
            }
        } else if (concealValue) {
            preference.setSummary("вЂўвЂўвЂўвЂўвЂўвЂўвЂўвЂў");
        } else {
            preference.setSummary(value);
        }
    }

    private void showSingleTextDialog(final int titleRes, final String currentValue,
            final ValueHandler handler, final boolean concealValue) {
        final EditText editText = buildEditText(concealValue
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editText.setText(currentValue);
        new AlertDialog.Builder(getActivity())
                .setTitle(titleRes)
                .setView(editText)
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        handler.apply(editText.getText().toString()))
                .setNeutralButton(android.R.string.cancel, null)
                .show();
    }

    private EditText buildEditText(final int inputType) {
        final EditText editText = new EditText(getActivity());
        editText.setInputType(inputType);
        editText.setMinLines(1);
        editText.setMaxLines(10);
        editText.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return editText;
    }

    private String getCurrentTextPreferenceValue(final String key) {
        if (Settings.PREF_AI_SYSTEM_PROMPT.equals(key)) {
            return Settings.readAiSystemPrompt(getSharedPreferences());
        }
        return getSharedPreferences().getString(key, "");
    }

    private CharSequence getAiBaseUrlSummary() {
        final String provider = getSharedPreferences().getString(Settings.PREF_AI_PROVIDER, "openai");
        if ("ollama".equals(provider)) {
            return getString(R.string.ai_base_url_summary_ollama);
        }
        return getString(R.string.ai_base_url_summary);
    }

    private interface ValueHandler {
        boolean apply(String value);
    }
}
