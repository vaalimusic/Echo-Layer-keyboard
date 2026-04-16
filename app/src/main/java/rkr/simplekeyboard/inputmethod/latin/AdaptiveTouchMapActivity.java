package rkr.simplekeyboard.inputmethod.latin;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

import rkr.simplekeyboard.inputmethod.R;
import rkr.simplekeyboard.inputmethod.keyboard.AdaptiveTouchStore;
import rkr.simplekeyboard.inputmethod.keyboard.Keyboard;
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardId;
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardSwitcher;

public final class AdaptiveTouchMapActivity extends Activity {
    private TextView mSummaryView;
    private AdaptiveTouchMapView mMapView;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_adaptive_touch_map);
        mSummaryView = findViewById(R.id.adaptive_touch_map_summary);
        mMapView = findViewById(R.id.adaptive_touch_map_view);
    }

    @Override
    protected void onResume() {
        super.onResume();
        bindMapData();
    }

    private void bindMapData() {
        final Keyboard keyboard = KeyboardSwitcher.getInstance().getKeyboard();
        if (keyboard == null) {
            mSummaryView.setText(R.string.adaptive_touch_map_unavailable);
            mMapView.setData(null, null);
            return;
        }
        final List<AdaptiveTouchStore.VisualKeyProfile> profiles =
                AdaptiveTouchStore.getInstance().getVisualProfiles(this, keyboard);
        int learnedKeys = 0;
        int totalSamples = 0;
        float totalAbsDx = 0.0f;
        float totalAbsDy = 0.0f;
        for (final AdaptiveTouchStore.VisualKeyProfile profile : profiles) {
            if (profile.profile == null || profile.profile.sampleCount <= 0) {
                continue;
            }
            learnedKeys++;
            totalSamples += profile.profile.sampleCount;
            totalAbsDx += Math.abs(profile.profile.meanDx);
            totalAbsDy += Math.abs(profile.profile.meanDy);
        }
        if (learnedKeys == 0) {
            mSummaryView.setText(R.string.adaptive_touch_map_empty);
        } else {
            final String keyboardName = formatKeyboardName(keyboard);
            final String summary = getString(R.string.adaptive_touch_map_summary_format,
                    learnedKeys,
                    totalSamples,
                    learnedKeys == 0 ? 0 : Math.round(totalAbsDx / learnedKeys),
                    learnedKeys == 0 ? 0 : Math.round(totalAbsDy / learnedKeys),
                    TextUtils.isEmpty(keyboardName) ? "current keyboard" : keyboardName);
            mSummaryView.setText(summary);
        }
        mMapView.setData(keyboard, profiles);
    }

    private String formatKeyboardName(final Keyboard keyboard) {
        if (keyboard == null || keyboard.mId == null) {
            return "";
        }
        final KeyboardId keyboardId = keyboard.mId;
        final String subtypeName = keyboardId.mSubtype == null ? "" : keyboardId.mSubtype.getName();
        final String modeName = KeyboardId.elementIdToName(keyboardId.mElementId);
        if (TextUtils.isEmpty(subtypeName)) {
            return modeName == null ? "" : modeName;
        }
        if (TextUtils.isEmpty(modeName)) {
            return subtypeName;
        }
        return subtypeName + " • " + modeName;
    }
}
