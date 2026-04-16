package rkr.simplekeyboard.inputmethod.latin;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;

import java.util.Collections;
import java.util.List;

final class CandidateStripControllerImpl implements CandidateStripController {
    private final Context mContext;
    private CandidateStripView mView;
    private CandidateStripView mFallbackView;
    private List<CandidateItem> mLastItems = Collections.emptyList();

    CandidateStripControllerImpl(final Context context) {
        mContext = context;
    }

    @Override
    public void bindView(final CandidateStripView view) {
        mView = view;
        bindCandidateView(mView);
        render();
    }

    @Override
    public void bindFallbackView(final CandidateStripView view) {
        mFallbackView = view;
        bindCandidateView(mFallbackView);
        render();
    }

    @Override
    public void showItems(final List<CandidateItem> items) {
        mLastItems = items == null ? Collections.emptyList() : items;
        render();
    }

    @Override
    public void showSource(final CandidateSource source) {
        showItems(source == null ? Collections.emptyList() : source.getItems());
    }

    @Override
    public void clear() {
        mLastItems = Collections.emptyList();
        render();
    }

    @Override
    public void hide() {
        hideView(mView);
        hideView(mFallbackView);
    }

    private void handleItemClick(final CandidateItem item) {
        if (item == null) {
            return;
        }
        if (item.type == CandidateItemType.DECODE) {
            if (mContext instanceof LatinIME) {
                ((LatinIME) mContext).showDecodedTextDialog(item.actionText);
            } else {
                copyToClipboard(item.actionText);
            }
        } else if (item.type == CandidateItemType.SUGGESTION && mContext instanceof LatinIME) {
            ((LatinIME) mContext).applySuggestionCandidate(item);
        } else if (item.type == CandidateItemType.STATUS) {
            clear();
        }
    }

    private void copyToClipboard(final CharSequence text) {
        final ClipboardManager clipboard = (ClipboardManager)
                mContext.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && text != null && text.length() > 0) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Echo Layer decoded text", text));
        }
    }

    private void bindCandidateView(final CandidateStripView view) {
        if (view != null) {
            view.setOnCandidateItemClickListener(this::handleItemClick);
            view.clearItems();
            if (view == mView) {
                view.setVisibility(View.GONE);
            }
        }
    }

    private void render() {
        renderOnView(mView, mLastItems, false);
        renderOnView(mFallbackView, mLastItems, true);
    }

    private static void renderOnView(final CandidateStripView view, final List<CandidateItem> items,
            final boolean isFallback) {
        if (view == null) {
            return;
        }
        final boolean hasItems = items != null && !items.isEmpty();
        if (isFallback) {
            view.setVisibility(View.VISIBLE);
            if (hasItems) {
                view.setItems(items);
            } else {
                view.clearItems();
            }
        } else {
            view.clearItems();
            view.setVisibility(View.GONE);
        }
    }

    private static void hideView(final CandidateStripView view) {
        if (view != null) {
            view.setVisibility(View.GONE);
        }
    }
}
