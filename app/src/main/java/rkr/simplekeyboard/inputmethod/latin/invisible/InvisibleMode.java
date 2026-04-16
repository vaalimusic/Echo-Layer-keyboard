package rkr.simplekeyboard.inputmethod.latin.invisible;

public enum InvisibleMode {
    INVISIBLE_UNICODE("invisible_unicode"),
    HOMOGLYPH("homoglyph"),
    WHITESPACE("whitespace"),
    VISIBLE_TOKEN("visible_token"),
    AUTO("auto");

    private final String mPreferenceValue;

    InvisibleMode(final String preferenceValue) {
        mPreferenceValue = preferenceValue;
    }

    public String getPreferenceValue() {
        return mPreferenceValue;
    }

    public static InvisibleMode fromPreferenceValue(final String value) {
        for (final InvisibleMode mode : values()) {
            if (mode.mPreferenceValue.equals(value)) {
                return mode;
            }
        }
        return AUTO;
    }
}
