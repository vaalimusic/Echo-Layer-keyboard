package rkr.simplekeyboard.inputmethod.latin;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import rkr.simplekeyboard.inputmethod.keyboard.AdaptiveTouchStore;
import rkr.simplekeyboard.inputmethod.keyboard.Key;
import rkr.simplekeyboard.inputmethod.keyboard.Keyboard;

public final class AdaptiveTouchMapView extends View {
    private final Paint mKeyFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mKeyStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHeatPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mVectorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCenterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();

    private List<AdaptiveTouchStore.VisualKeyProfile> mProfiles = Collections.emptyList();
    private Keyboard mKeyboard;
    private int mMaxSampleCount;

    public AdaptiveTouchMapView(final Context context) {
        this(context, null);
    }

    public AdaptiveTouchMapView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        mKeyFillPaint.setColor(Color.parseColor("#F3F4F6"));
        mKeyFillPaint.setStyle(Paint.Style.FILL);

        mKeyStrokePaint.setColor(Color.parseColor("#A0A7B4"));
        mKeyStrokePaint.setStrokeWidth(dp(1f));
        mKeyStrokePaint.setStyle(Paint.Style.STROKE);

        mTextPaint.setColor(Color.parseColor("#20242D"));
        mTextPaint.setTextSize(sp(11f));
        mTextPaint.setTextAlign(Paint.Align.CENTER);

        mHeatPaint.setStyle(Paint.Style.FILL);
        mVectorPaint.setColor(Color.parseColor("#CC1D4ED8"));
        mVectorPaint.setStrokeWidth(dp(2f));
        mVectorPaint.setStyle(Paint.Style.STROKE);

        mCenterPaint.setColor(Color.parseColor("#FF111827"));
        mCenterPaint.setStyle(Paint.Style.FILL);
    }

    public void setData(final Keyboard keyboard,
            final List<AdaptiveTouchStore.VisualKeyProfile> profiles) {
        mKeyboard = keyboard;
        mProfiles = profiles == null ? Collections.emptyList() : profiles;
        mMaxSampleCount = 0;
        for (final AdaptiveTouchStore.VisualKeyProfile profile : mProfiles) {
            if (profile.profile != null) {
                mMaxSampleCount = Math.max(mMaxSampleCount, profile.profile.sampleCount);
            }
        }
        invalidate();
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        super.onDraw(canvas);
        if (mKeyboard == null || mProfiles.isEmpty()) {
            drawEmptyState(canvas);
            return;
        }
        final float availableWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        final float availableHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        if (availableWidth <= 0 || availableHeight <= 0) {
            return;
        }
        final float scale = Math.min(
                availableWidth / Math.max(1, mKeyboard.mOccupiedWidth),
                availableHeight / Math.max(1, mKeyboard.mOccupiedHeight));
        final float offsetX = getPaddingLeft()
                + (availableWidth - mKeyboard.mOccupiedWidth * scale) / 2.0f;
        final float offsetY = getPaddingTop()
                + (availableHeight - mKeyboard.mOccupiedHeight * scale) / 2.0f;

        for (final AdaptiveTouchStore.VisualKeyProfile visualProfile : mProfiles) {
            drawKey(canvas, visualProfile, scale, offsetX, offsetY);
        }
    }

    private void drawKey(final Canvas canvas, final AdaptiveTouchStore.VisualKeyProfile visualProfile,
            final float scale, final float offsetX, final float offsetY) {
        final Key key = visualProfile.key;
        final float left = offsetX + key.getX() * scale;
        final float top = offsetY + key.getY() * scale;
        final float right = left + key.getWidth() * scale;
        final float bottom = top + key.getHeight() * scale;
        mRect.set(left, top, right, bottom);

        canvas.drawRoundRect(mRect, dp(6f), dp(6f), mKeyFillPaint);
        if (visualProfile.profile != null && visualProfile.profile.sampleCount > 0) {
            final int alpha = 48 + Math.round(
                    160f * visualProfile.profile.sampleCount / Math.max(1, mMaxSampleCount));
            mHeatPaint.setColor(Color.argb(Math.min(220, alpha), 16, 185, 129));
            canvas.drawRoundRect(mRect, dp(6f), dp(6f), mHeatPaint);
        }
        canvas.drawRoundRect(mRect, dp(6f), dp(6f), mKeyStrokePaint);

        final float centerX = (left + right) / 2.0f;
        final float centerY = (top + bottom) / 2.0f;
        canvas.drawCircle(centerX, centerY, dp(1.8f), mCenterPaint);

        if (visualProfile.profile != null && visualProfile.profile.sampleCount > 0) {
            final float targetX = centerX + visualProfile.profile.meanDx * scale;
            final float targetY = centerY + visualProfile.profile.meanDy * scale;
            canvas.drawLine(centerX, centerY, targetX, targetY, mVectorPaint);
            canvas.drawCircle(targetX, targetY, dp(2.6f), mVectorPaint);
        }

        final String label = getKeyLabel(key);
        if (!TextUtils.isEmpty(label)) {
            final float textY = top + key.getHeight() * scale * 0.45f;
            canvas.drawText(label, centerX, textY, mTextPaint);
        }
        if (visualProfile.profile != null && visualProfile.profile.sampleCount > 0) {
            mTextPaint.setTextSize(sp(9f));
            canvas.drawText(String.format(Locale.ROOT, "%d", visualProfile.profile.sampleCount),
                    centerX, bottom - dp(6f), mTextPaint);
            mTextPaint.setTextSize(sp(11f));
        }
    }

    private void drawEmptyState(final Canvas canvas) {
        final String message = "No adaptive touch data yet";
        canvas.drawText(message, getWidth() / 2.0f, getHeight() / 2.0f, mTextPaint);
    }

    private static String getKeyLabel(final Key key) {
        if (key == null) {
            return "";
        }
        final String label = key.getLabel();
        if (!TextUtils.isEmpty(label)) {
            return label;
        }
        return key.toShortString();
    }

    private float dp(final float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private float sp(final float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                getResources().getDisplayMetrics());
    }
}
