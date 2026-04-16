package rkr.simplekeyboard.inputmethod.keyboard;

import android.content.Context;
import android.util.DisplayMetrics;

import java.util.List;

import rkr.simplekeyboard.inputmethod.latin.settings.Settings;

final class AdaptiveKeyDetector extends KeyDetector {
    private final Context mContext;
    private final AdaptiveTouchStore mStore = AdaptiveTouchStore.getInstance();

    AdaptiveKeyDetector(final Context context, final float keyHysteresisDistance,
            final float keyHysteresisDistanceForSlidingModifier) {
        super(keyHysteresisDistance, keyHysteresisDistanceForSlidingModifier);
        mContext = context.getApplicationContext();
    }

    @Override
    public Key detectHitKey(final int x, final int y) {
        final Keyboard keyboard = getKeyboard();
        if (keyboard == null) {
            return null;
        }
        final int touchX = getTouchX(x);
        final int touchY = getTouchY(y);
        final Key baseKey = detectBaseHitKey(touchX, touchY);
        if (!Settings.readAdaptiveTouchEnabled(Settings.getInstance().getSharedPrefs())) {
            return baseKey;
        }

        final AdaptiveTouchLevel level = AdaptiveTouchLevel.fromPreferenceValue(
                Settings.readAdaptiveTouchLevel(Settings.getInstance().getSharedPrefs()));
        Key bestKey = baseKey;
        float bestScore = Float.MAX_VALUE;
        final List<Key> nearestKeys = keyboard.getNearestKeys(touchX, touchY);
        for (final Key candidate : nearestKeys) {
            if (candidate == null || candidate.isSpacer()) {
                continue;
            }
            final AdaptiveTouchStore.KeyTouchProfile profile = mStore.getProfile(mContext, keyboard, candidate);
            if (profile == null || profile.sampleCount < level.minSamplesToAdapt) {
                if (candidate == baseKey && bestKey == null) {
                    bestKey = candidate;
                }
                continue;
            }
            final float score = scoreCandidate(candidate, profile, touchX, touchY, level);
            if (!isInsideAdaptiveHitbox(candidate, profile, touchX, touchY, level)) {
                continue;
            }
            if (score < bestScore) {
                bestScore = score;
                bestKey = candidate;
            }
        }
        return bestKey != null ? bestKey : baseKey;
    }

    @Override
    public void recordAcceptedKey(final Context context, final Key key, final int x, final int y,
            final boolean autoCorrected) {
        final Keyboard keyboard = getKeyboard();
        if (keyboard == null || key == null) {
            return;
        }
        if (!Settings.readAdaptiveTouchEnabled(Settings.getInstance().getSharedPrefs())) {
            return;
        }
        final int touchX = getTouchX(x);
        final int touchY = getTouchY(y);
        final Key baseKey = detectBaseHitKey(touchX, touchY);
        mStore.recordTouch(mContext, keyboard, key, baseKey, touchX, touchY, autoCorrected);
    }

    private Key detectBaseHitKey(final int touchX, final int touchY) {
        final Keyboard keyboard = getKeyboard();
        if (keyboard == null) {
            return null;
        }
        for (final Key key : keyboard.getNearestKeys(touchX, touchY)) {
            if (key.isOnKey(touchX, touchY)) {
                return key;
            }
        }
        return null;
    }

    private boolean isInsideAdaptiveHitbox(final Key key, final AdaptiveTouchStore.KeyTouchProfile profile,
            final int touchX, final int touchY, final AdaptiveTouchLevel level) {
        if (key.isOnKey(touchX, touchY)) {
            return true;
        }
        final float expandPx = Math.min(dpToPx(level.maxExpandDp),
                Math.max(profile.stdDevX(), profile.stdDevY()) * level.spreadMultiplier);
        final float left = key.getX() - key.getLeftPadding() - expandPx;
        final float top = key.getY() - key.getTopPadding() - expandPx;
        final float right = key.getX() + key.getWidth() + key.getRightPadding() + expandPx;
        final float bottom = key.getY() + key.getHeight() + key.getBottomPadding() + expandPx;
        return touchX >= left && touchX < right && touchY >= top && touchY < bottom;
    }

    private float scoreCandidate(final Key key, final AdaptiveTouchStore.KeyTouchProfile profile,
            final int touchX, final int touchY, final AdaptiveTouchLevel level) {
        final float maxShiftPx = dpToPx(level.maxShiftDp);
        final float adjustedCenterX = key.getX() + key.getWidth() / 2.0f
                + clamp(profile.meanDx, -maxShiftPx, maxShiftPx);
        final float adjustedCenterY = key.getY() + key.getHeight() / 2.0f
                + clamp(profile.meanDy, -maxShiftPx, maxShiftPx);
        final float dx = touchX - adjustedCenterX;
        final float dy = touchY - adjustedCenterY;
        float score = dx * dx + dy * dy;
        if (profile.conflictCount > 0) {
            score *= 0.92f;
        }
        return score;
    }

    private float dpToPx(final float dp) {
        final DisplayMetrics metrics = mContext.getResources().getDisplayMetrics();
        return dp * metrics.density;
    }

    private static float clamp(final float value, final float min, final float max) {
        return Math.max(min, Math.min(max, value));
    }
}
