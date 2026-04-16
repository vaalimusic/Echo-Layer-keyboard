package rkr.simplekeyboard.inputmethod.latin;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import rkr.simplekeyboard.inputmethod.R;
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardTheme;
import rkr.simplekeyboard.inputmethod.latin.settings.Settings;

public final class CandidateStripView extends LinearLayout {
    private static final int MAX_CANDIDATES = 3;
    private final int mNormalTextColor;
    private final int mBackgroundColor;
    private final int mStrokeColor;
    private final float mCandidateTextSizePx;

    public interface OnCandidateItemClickListener {
        void onCandidateItemClicked(CandidateItem item);
    }

    private final ArrayList<TextView> mCandidateViews = new ArrayList<>(MAX_CANDIDATES);
    private List<CandidateItem> mItems = Collections.emptyList();
    private OnCandidateItemClickListener mClickListener;

    public CandidateStripView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setBaselineAligned(false);
        setGravity(Gravity.CENTER);
        final KeyboardTheme keyboardTheme = Settings.getKeyboardTheme(getContext());
        mNormalTextColor = getContext().getColor(resolveTextColorRes(keyboardTheme));
        mBackgroundColor = getContext().getColor(resolveBackgroundColorRes(keyboardTheme));
        mStrokeColor = 0x00000000;
        mCandidateTextSizePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 16, getResources().getDisplayMetrics());
        applyThemeStyling();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mCandidateViews.clear();
        mCandidateViews.add(requireTextView(R.id.candidate_1));
        mCandidateViews.add(requireTextView(R.id.candidate_2));
        mCandidateViews.add(requireTextView(R.id.candidate_3));
        updateViews();
    }

    public void setOnCandidateItemClickListener(final OnCandidateItemClickListener listener) {
        mClickListener = listener;
    }

    public void setItems(final List<CandidateItem> items) {
        mItems = items == null ? Collections.emptyList() : items;
        updateViews();
    }

    public void clearItems() {
        mItems = Collections.emptyList();
        updateViews();
    }

    private void updateViews() {
        final int count = Math.min(mItems.size(), MAX_CANDIDATES);
        for (int index = 0; index < MAX_CANDIDATES; index++) {
            final TextView view = mCandidateViews.get(index);
            final LayoutParams params = (LayoutParams) view.getLayoutParams();
            if (index < count) {
                final CandidateItem item = mItems.get(index);
                if (count == 1) {
                    params.width = LayoutParams.MATCH_PARENT;
                    params.weight = 0.0f;
                } else {
                    params.width = 0;
                    params.weight = 1.0f;
                }
                view.setLayoutParams(params);
                view.setText(item.displayText);
                view.setVisibility(View.VISIBLE);
                view.setGravity(Gravity.CENTER);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    view.setTextAlignment(TEXT_ALIGNMENT_CENTER);
                }
                view.setTextColor(mNormalTextColor);
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, mCandidateTextSizePx);
                view.setOnClickListener(v -> {
                    if (mClickListener != null) {
                        mClickListener.onCandidateItemClicked(item);
                    }
                });
            } else {
                params.width = 0;
                params.weight = 0.0f;
                view.setLayoutParams(params);
                view.setText("");
                view.setVisibility(View.GONE);
                view.setOnClickListener(null);
            }
        }
        requestLayout();
        invalidate();
    }

    private TextView requireTextView(final int id) {
        final TextView view = findViewById(id);
        if (view == null) {
            throw new IllegalStateException("Missing candidate cell: " + id);
        }
        return view;
    }

    private int resolveTextColorRes(final KeyboardTheme keyboardTheme) {
        switch (keyboardTheme.mThemeId) {
            case KeyboardTheme.THEME_ID_DARK:
            case KeyboardTheme.THEME_ID_DARK_BORDER:
                return R.color.key_text_color_lxx_dark;
            case KeyboardTheme.THEME_ID_LIGHT:
            case KeyboardTheme.THEME_ID_LIGHT_BORDER:
                return R.color.key_text_color_lxx_light;
            case KeyboardTheme.THEME_ID_SYSTEM:
            case KeyboardTheme.THEME_ID_SYSTEM_BORDER:
            default:
                return R.color.key_text_color_lxx_system;
        }
    }

    private int resolveBackgroundColorRes(final KeyboardTheme keyboardTheme) {
        switch (keyboardTheme.mThemeId) {
            case KeyboardTheme.THEME_ID_DARK:
            case KeyboardTheme.THEME_ID_DARK_BORDER:
                return R.color.background_secondary_lxx_dark;
            case KeyboardTheme.THEME_ID_LIGHT:
            case KeyboardTheme.THEME_ID_LIGHT_BORDER:
                return R.color.background_secondary_lxx_light;
            case KeyboardTheme.THEME_ID_SYSTEM:
            case KeyboardTheme.THEME_ID_SYSTEM_BORDER:
            default:
                return R.color.background_secondary_lxx_system;
        }
    }

    private void applyThemeStyling() {
        final GradientDrawable backgroundDrawable = new GradientDrawable();
        backgroundDrawable.setShape(GradientDrawable.RECTANGLE);
        backgroundDrawable.setColor(mBackgroundColor);
        final float density = getResources().getDisplayMetrics().density;
        final int strokeWidth = Math.max(1, Math.round(density));
        if ((mStrokeColor >>> 24) != 0) {
            backgroundDrawable.setStroke(strokeWidth, mStrokeColor);
        }
        setBackground(backgroundDrawable);
    }
}
