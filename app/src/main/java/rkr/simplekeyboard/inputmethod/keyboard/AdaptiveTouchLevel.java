package rkr.simplekeyboard.inputmethod.keyboard;

import android.text.TextUtils;

enum AdaptiveTouchLevel {
    MILD("mild", 4.0f, 3.0f, 40, 0.75f),
    BALANCED("balanced", 7.0f, 5.0f, 24, 1.0f),
    STRONG("strong", 10.0f, 7.0f, 16, 1.25f);

    public final String preferenceValue;
    public final float maxShiftDp;
    public final float maxExpandDp;
    public final int minSamplesToAdapt;
    public final float spreadMultiplier;

    AdaptiveTouchLevel(final String preferenceValue, final float maxShiftDp,
            final float maxExpandDp, final int minSamplesToAdapt, final float spreadMultiplier) {
        this.preferenceValue = preferenceValue;
        this.maxShiftDp = maxShiftDp;
        this.maxExpandDp = maxExpandDp;
        this.minSamplesToAdapt = minSamplesToAdapt;
        this.spreadMultiplier = spreadMultiplier;
    }

    static AdaptiveTouchLevel fromPreferenceValue(final String value) {
        if (!TextUtils.isEmpty(value)) {
            for (final AdaptiveTouchLevel level : values()) {
                if (level.preferenceValue.equals(value)) {
                    return level;
                }
            }
        }
        return BALANCED;
    }
}
