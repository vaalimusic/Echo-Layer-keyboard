package rkr.simplekeyboard.inputmethod.latin.settings;

import android.os.Bundle;
import android.content.Intent;
import android.preference.ListPreference;
import android.preference.Preference;

import rkr.simplekeyboard.inputmethod.R;
import rkr.simplekeyboard.inputmethod.keyboard.AdaptiveTouchStore;
import rkr.simplekeyboard.inputmethod.latin.AdaptiveTouchMapActivity;

public final class AdaptiveTouchSettingsFragment extends SubScreenFragment {
    @Override
    public void onCreate(final Bundle icicle) {
        super.onCreate(icicle);
        addPreferencesFromResource(R.xml.prefs_screen_adaptive_touch);

        updateAdaptiveTouchLevelSummary();
        final Preference resetPreference = findPreference("pref_adaptive_touch_reset");
        if (resetPreference != null) {
            resetPreference.setOnPreferenceClickListener(preference -> {
                AdaptiveTouchStore.getInstance().reset(getActivity());
                return true;
            });
        }
        final Preference mapPreference = findPreference("pref_adaptive_touch_map");
        if (mapPreference != null) {
            mapPreference.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(getActivity(), AdaptiveTouchMapActivity.class));
                return true;
            });
        }
    }

    @Override
    public void onSharedPreferenceChanged(final android.content.SharedPreferences prefs,
            final String key) {
        super.onSharedPreferenceChanged(prefs, key);
        if (Settings.PREF_ADAPTIVE_TOUCH_LEVEL.equals(key)) {
            updateAdaptiveTouchLevelSummary();
        }
    }

    private void updateAdaptiveTouchLevelSummary() {
        final ListPreference levelPreference =
                (ListPreference)findPreference(Settings.PREF_ADAPTIVE_TOUCH_LEVEL);
        if (levelPreference != null) {
            levelPreference.setSummary(levelPreference.getEntry());
        }
    }
}
