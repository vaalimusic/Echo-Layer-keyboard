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

import java.security.GeneralSecurityException;
import java.util.List;

import rkr.simplekeyboard.inputmethod.R;
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardLayoutSet;
import rkr.simplekeyboard.inputmethod.latin.invisible.CoverTemplateRepository;
import rkr.simplekeyboard.inputmethod.latin.invisible.PassphraseStore;

public final class InvisibleSettingsFragment extends SubScreenFragment {
    private PassphraseStore mPassphraseStore;
    private CoverTemplateRepository mTemplateRepository;

    @Override
    public void onCreate(final Bundle icicle) {
        super.onCreate(icicle);
        addPreferencesFromResource(R.xml.prefs_screen_invisible);

        final SharedPreferences prefs = getSharedPreferences();
        mPassphraseStore = new PassphraseStore(prefs, Settings.PREF_INVISIBLE_PASSPHRASE);
        mTemplateRepository = new CoverTemplateRepository(
                prefs,
                Settings.PREF_INVISIBLE_COVER_TEMPLATES,
                Settings.PREF_INVISIBLE_DEFAULT_TEMPLATE);

        final ListPreference modePreference =
                (ListPreference) findPreference(Settings.PREF_INVISIBLE_MODE);
        if (modePreference != null) {
            modePreference.setSummary(modePreference.getEntry());
        }

        findPreference(Settings.PREF_INVISIBLE_PASSPHRASE)
                .setOnPreferenceClickListener(preference -> {
                    showPassphraseDialog();
                    return true;
                });
        findPreference(Settings.PREF_INVISIBLE_DEFAULT_TEMPLATE)
                .setOnPreferenceClickListener(preference -> {
                    showSingleTextDialog(
                            R.string.invisible_default_template_dialog_title,
                            mTemplateRepository.getDefaultTemplate(),
                            value -> {
                                if (!TextUtils.isEmpty(value)
                                        && !CoverTemplateRepository.isValidTemplate(value)) {
                                    return false;
                                }
                                mTemplateRepository.saveDefaultTemplate(value);
                                updateSummaries();
                                return true;
                            });
                    return true;
                });
        findPreference(Settings.PREF_INVISIBLE_COVER_TEMPLATES)
                .setOnPreferenceClickListener(preference -> {
                    showSingleTextDialog(
                            R.string.invisible_cover_templates_dialog_title,
                            TextUtils.join("\n", mTemplateRepository.getTemplates()),
                            value -> {
                                final String[] templates = value.split("\n");
                                for (final String template : templates) {
                                    final String trimmed = template.trim();
                                    if (!trimmed.isEmpty()
                                            && !CoverTemplateRepository.isValidTemplate(trimmed)) {
                                        return false;
                                    }
                                }
                                mTemplateRepository.saveTemplates(value);
                                updateSummaries();
                                return true;
                            });
                    return true;
                });
        findPreference(Settings.PREF_INVISIBLE_EMOJI_MARKER)
                .setOnPreferenceClickListener(preference -> {
                    showSingleTextDialog(
                            R.string.invisible_emoji_marker_dialog_title,
                            prefs.getString(Settings.PREF_INVISIBLE_EMOJI_MARKER, ""),
                            value -> {
                                prefs.edit().putString(Settings.PREF_INVISIBLE_EMOJI_MARKER,
                                        value == null ? "" : value.trim()).apply();
                                updateSummaries();
                                return true;
                            });
                    return true;
                });
        updateSummaries();
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences prefs, final String key) {
        if (Settings.PREF_INVISIBLE_FEATURE_ENABLED.equals(key)
                || Settings.PREF_INVISIBLE_MODE.equals(key)) {
            KeyboardLayoutSet.onKeyboardThemeChanged();
            final ListPreference modePreference =
                    (ListPreference) findPreference(Settings.PREF_INVISIBLE_MODE);
            if (modePreference != null) {
                modePreference.setSummary(modePreference.getEntry());
            }
        }
        updateSummaries();
    }

    private void updateSummaries() {
        final Preference passphrasePreference =
                findPreference(Settings.PREF_INVISIBLE_PASSPHRASE);
        if (passphrasePreference != null) {
            passphrasePreference.setSummary(mPassphraseStore.hasPassphrase()
                    ? R.string.invisible_passphrase_summary_set
                    : R.string.invisible_passphrase_summary_missing);
        }

        final Preference defaultTemplatePreference =
                findPreference(Settings.PREF_INVISIBLE_DEFAULT_TEMPLATE);
        if (defaultTemplatePreference != null) {
            final String value = mTemplateRepository.getDefaultTemplate();
            defaultTemplatePreference.setSummary(TextUtils.isEmpty(value)
                    ? getString(R.string.invisible_default_template_summary)
                    : value);
        }

        final Preference templatesPreference =
                findPreference(Settings.PREF_INVISIBLE_COVER_TEMPLATES);
        if (templatesPreference != null) {
            final List<String> templates = mTemplateRepository.getTemplates();
            templatesPreference.setSummary(getString(R.string.invisible_cover_templates_summary)
                    + " (" + templates.size() + ")");
        }

        final Preference emojiMarkerPreference =
                findPreference(Settings.PREF_INVISIBLE_EMOJI_MARKER);
        if (emojiMarkerPreference != null) {
            final String value = getSharedPreferences()
                    .getString(Settings.PREF_INVISIBLE_EMOJI_MARKER, "");
            emojiMarkerPreference.setSummary(TextUtils.isEmpty(value)
                    ? getString(R.string.invisible_emoji_marker_summary)
                    : value);
        }
    }

    private void showPassphraseDialog() {
        final EditText editText = buildEditText(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.invisible_passphrase_dialog_title)
                .setView(editText)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    final String value = editText.getText().toString();
                    if (TextUtils.isEmpty(value)) {
                        mPassphraseStore.clear();
                    } else {
                        try {
                            mPassphraseStore.save(value.toCharArray());
                        } catch (final GeneralSecurityException ignored) {
                        }
                    }
                    updateSummaries();
                })
                .setNeutralButton(android.R.string.cancel, null)
                .show();
    }

    private void showSingleTextDialog(final int titleRes, final String currentValue,
            final ValueHandler handler) {
        final EditText editText = buildEditText(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editText.setText(currentValue);
        new AlertDialog.Builder(getActivity())
                .setTitle(titleRes)
                .setView(editText)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> handler.apply(editText.getText().toString()))
                .setNeutralButton(android.R.string.cancel, null)
                .show();
    }

    private EditText buildEditText(final int inputType) {
        final EditText editText = new EditText(getActivity());
        editText.setInputType(inputType);
        editText.setMinLines(1);
        editText.setMaxLines(6);
        editText.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return editText;
    }

    private interface ValueHandler {
        boolean apply(String value);
    }
}
