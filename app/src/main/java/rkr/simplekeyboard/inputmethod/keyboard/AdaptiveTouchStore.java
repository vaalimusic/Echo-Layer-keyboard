package rkr.simplekeyboard.inputmethod.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat;

import java.util.Locale;

public final class AdaptiveTouchStore {
    private static final String PREF_PREFIX = "pref_adaptive_touch_profile_";
    private static final AdaptiveTouchStore sInstance = new AdaptiveTouchStore();

    public static final class KeyTouchProfile {
        public final int sampleCount;
        public final float meanDx;
        public final float meanDy;
        public final float varianceDx;
        public final float varianceDy;
        public final int conflictCount;
        public final int correctedCount;

        KeyTouchProfile(final int sampleCount, final float meanDx, final float meanDy,
                final float varianceDx, final float varianceDy, final int conflictCount,
                final int correctedCount) {
            this.sampleCount = sampleCount;
            this.meanDx = meanDx;
            this.meanDy = meanDy;
            this.varianceDx = varianceDx;
            this.varianceDy = varianceDy;
            this.conflictCount = conflictCount;
            this.correctedCount = correctedCount;
        }

        float stdDevX() {
            return (float)Math.sqrt(Math.max(0.0f, varianceDx));
        }

        float stdDevY() {
            return (float)Math.sqrt(Math.max(0.0f, varianceDy));
        }
    }

    private AdaptiveTouchStore() {}

    public static AdaptiveTouchStore getInstance() {
        return sInstance;
    }

    public KeyTouchProfile getProfile(final Context context, final Keyboard keyboard, final Key key) {
        final SharedPreferences prefs = PreferenceManagerCompat.getDeviceSharedPreferences(context);
        final MutableStats stats = loadAggregatedStats(prefs, keyboard, key);
        return toProfile(stats);
    }

    public java.util.List<VisualKeyProfile> getVisualProfiles(final Context context,
            final Keyboard keyboard) {
        if (context == null || keyboard == null) {
            return java.util.Collections.emptyList();
        }
        final java.util.ArrayList<VisualKeyProfile> profiles = new java.util.ArrayList<>();
        for (final Key key : keyboard.getSortedKeys()) {
            if (key == null || key.isSpacer()) {
                continue;
            }
            profiles.add(new VisualKeyProfile(key, getProfile(context, keyboard, key)));
        }
        return profiles;
    }

    public void recordTouch(final Context context, final Keyboard keyboard, final Key selectedKey,
            final Key baseHitKey, final int touchX, final int touchY, final boolean autoCorrected) {
        if (context == null || keyboard == null || selectedKey == null) {
            return;
        }
        final SharedPreferences prefs = PreferenceManagerCompat.getDeviceSharedPreferences(context);
        final String storageKey = buildStorageKey(keyboard, selectedKey);
        final MutableStats stats = loadAggregatedStats(prefs, keyboard, selectedKey);
        final float centerX = selectedKey.getX() + selectedKey.getWidth() / 2.0f;
        final float centerY = selectedKey.getY() + selectedKey.getHeight() / 2.0f;
        final float dx = touchX - centerX;
        final float dy = touchY - centerY;
        stats.addSample(dx, dy);
        if (baseHitKey != null && baseHitKey.getCode() != selectedKey.getCode()) {
            stats.conflictCount++;
        }
        if (autoCorrected) {
            stats.correctedCount++;
        }
        prefs.edit().putString(storageKey, stats.serialize()).apply();
    }

    public void reset(final Context context) {
        final SharedPreferences prefs = PreferenceManagerCompat.getDeviceSharedPreferences(context);
        final SharedPreferences.Editor editor = prefs.edit();
        for (final String key : prefs.getAll().keySet()) {
            if (key.startsWith(PREF_PREFIX)) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    private static String buildStorageKey(final Keyboard keyboard, final Key key) {
        final KeyboardId keyboardId = keyboard.mId;
        return buildStablePrefix(keyboardId) + "_" + key.getCode();
    }

    private static String buildStablePrefix(final KeyboardId keyboardId) {
        return PREF_PREFIX
                + safePart(keyboardId.mSubtype == null ? "" : keyboardId.mSubtype.getLocale()) + "_"
                + safePart(keyboardId.mSubtype == null ? "" : keyboardId.mSubtype.getKeyboardLayoutSet()) + "_"
                + keyboardId.mMode + "_"
                + keyboardId.mElementId + "_"
                + (keyboardId.isAlphabetKeyboard() ? "alpha" : "other") + "_"
                + (keyboardId.mShowNumberRow ? "num" : "nonum") + "_"
                + (keyboardId.mShowInvisibleKey ? "inv" : "noinv");
    }

    private static String buildLegacyPrefix(final KeyboardId keyboardId) {
        return PREF_PREFIX
                + safePart(keyboardId.mSubtype == null ? "" : keyboardId.mSubtype.getLocale()) + "_"
                + safePart(keyboardId.mSubtype == null ? "" : keyboardId.mSubtype.getKeyboardLayoutSet()) + "_"
                + keyboardId.mMode + "_";
    }

    private static String safePart(final String value) {
        if (value == null || value.isEmpty()) {
            return "none";
        }
        return value.replaceAll("[^A-Za-z0-9_\\-]", "_").toLowerCase(Locale.ROOT);
    }

    private static MutableStats loadAggregatedStats(final SharedPreferences prefs,
            final Keyboard keyboard, final Key key) {
        final KeyboardId keyboardId = keyboard.mId;
        final String stableKey = buildStorageKey(keyboard, key);
        final MutableStats merged = new MutableStats();
        merged.merge(parseMutableStats(prefs.getString(stableKey, null)));

        final String stablePrefix = buildStablePrefix(keyboardId) + "_";
        final String legacyPrefix = buildLegacyPrefix(keyboardId);
        final String codeSuffix = "_" + key.getCode();
        for (final java.util.Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            final String prefKey = entry.getKey();
            if (prefKey == null || prefKey.equals(stableKey) || !prefKey.startsWith(PREF_PREFIX)
                    || !prefKey.endsWith(codeSuffix)) {
                continue;
            }
            if (prefKey.startsWith(stablePrefix) || prefKey.startsWith(legacyPrefix)) {
                final Object rawValue = entry.getValue();
                if (rawValue instanceof String) {
                    merged.merge(parseMutableStats((String) rawValue));
                }
            }
        }
        return merged;
    }

    private static KeyTouchProfile toProfile(final MutableStats stats) {
        if (stats.count <= 0) {
            return null;
        }
        return new KeyTouchProfile(stats.count, stats.meanDx, stats.meanDy,
                stats.varianceX(), stats.varianceY(), stats.conflictCount, stats.correctedCount);
    }

    private static MutableStats parseMutableStats(final String serialized) {
        if (serialized == null || serialized.trim().isEmpty()) {
            return new MutableStats();
        }
        final String[] parts = serialized.split("\\|");
        if (parts.length != 7) {
            return new MutableStats();
        }
        try {
            return new MutableStats(
                    Integer.parseInt(parts[0]),
                    Float.parseFloat(parts[1]),
                    Float.parseFloat(parts[2]),
                    Float.parseFloat(parts[3]),
                    Float.parseFloat(parts[4]),
                    Integer.parseInt(parts[5]),
                    Integer.parseInt(parts[6]));
        } catch (final NumberFormatException e) {
            return new MutableStats();
        }
    }

    private static final class MutableStats {
        int count;
        float meanDx;
        float meanDy;
        float m2Dx;
        float m2Dy;
        int conflictCount;
        int correctedCount;

        MutableStats() {}

        MutableStats(final int count, final float meanDx, final float meanDy, final float m2Dx,
                final float m2Dy, final int conflictCount, final int correctedCount) {
            this.count = count;
            this.meanDx = meanDx;
            this.meanDy = meanDy;
            this.m2Dx = m2Dx;
            this.m2Dy = m2Dy;
            this.conflictCount = conflictCount;
            this.correctedCount = correctedCount;
        }

        void addSample(final float dx, final float dy) {
            count++;
            final float deltaX = dx - meanDx;
            meanDx += deltaX / count;
            m2Dx += deltaX * (dx - meanDx);
            final float deltaY = dy - meanDy;
            meanDy += deltaY / count;
            m2Dy += deltaY * (dy - meanDy);
        }

        void merge(final MutableStats other) {
            if (other == null || other.count <= 0) {
                return;
            }
            if (count <= 0) {
                count = other.count;
                meanDx = other.meanDx;
                meanDy = other.meanDy;
                m2Dx = other.m2Dx;
                m2Dy = other.m2Dy;
                conflictCount = other.conflictCount;
                correctedCount = other.correctedCount;
                return;
            }
            final int combinedCount = count + other.count;
            final float deltaX = other.meanDx - meanDx;
            final float deltaY = other.meanDy - meanDy;
            m2Dx += other.m2Dx + deltaX * deltaX * count * other.count / combinedCount;
            m2Dy += other.m2Dy + deltaY * deltaY * count * other.count / combinedCount;
            meanDx += deltaX * other.count / combinedCount;
            meanDy += deltaY * other.count / combinedCount;
            count = combinedCount;
            conflictCount += other.conflictCount;
            correctedCount += other.correctedCount;
        }

        float varianceX() {
            return count > 1 ? m2Dx / (count - 1) : 0.0f;
        }

        float varianceY() {
            return count > 1 ? m2Dy / (count - 1) : 0.0f;
        }

        String serialize() {
            return count + "|" + meanDx + "|" + meanDy + "|" + m2Dx + "|" + m2Dy
                    + "|" + conflictCount + "|" + correctedCount;
        }
    }

    public static final class VisualKeyProfile {
        public final Key key;
        public final KeyTouchProfile profile;

        VisualKeyProfile(final Key key, final KeyTouchProfile profile) {
            this.key = key;
            this.profile = profile;
        }
    }
}
