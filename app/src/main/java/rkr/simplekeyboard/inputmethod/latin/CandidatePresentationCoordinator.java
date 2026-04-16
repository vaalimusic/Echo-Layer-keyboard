package rkr.simplekeyboard.inputmethod.latin;

import java.util.Collections;
import java.util.List;

final class CandidatePresentationCoordinator {
    private final CandidateStripController mController;
    private final SuggestionCandidateSource mSuggestionSource;
    private List<CandidateItem> mLastItems = Collections.emptyList();
    private boolean mDecodeVisible;
    private boolean mHasVisibleContent;

    CandidatePresentationCoordinator(final CandidateStripController controller,
            final SuggestionCandidateSource suggestionSource) {
        mController = controller;
        mSuggestionSource = suggestionSource;
    }

    void showDecode(final CharSequence decodedText) {
        if (decodedText == null || decodedText.length() == 0) {
            clear();
            return;
        }
        mDecodeVisible = true;
        mHasVisibleContent = true;
        final List<CandidateItem> items = new DecodeCandidateSource(decodedText).getItems();
        if (!areSameItems(items, mLastItems)) {
            mController.showSource(new DecodeCandidateSource(decodedText));
            mLastItems = items;
        }
    }

    void refreshSuggestions(final RichInputConnection connection,
            final InputAttributes inputAttributes) {
        mSuggestionSource.update(connection, inputAttributes);
        if (!mDecodeVisible) {
            final List<CandidateItem> items = mSuggestionSource.getItems();
            mHasVisibleContent = !items.isEmpty();
            if (areSameItems(items, mLastItems)) {
                return;
            }
            mController.showSource(mSuggestionSource);
            mLastItems = items;
        }
    }

    void clear() {
        mDecodeVisible = false;
        mHasVisibleContent = false;
        mLastItems = Collections.emptyList();
        mController.clear();
    }

    boolean isDecodeVisible() {
        return mDecodeVisible;
    }

    boolean hasVisibleContent() {
        return mHasVisibleContent;
    }

    private static boolean areSameItems(final List<CandidateItem> first,
            final List<CandidateItem> second) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null || first.size() != second.size()) {
            return false;
        }
        for (int index = 0; index < first.size(); index++) {
            final CandidateItem left = first.get(index);
            final CandidateItem right = second.get(index);
            if (left == right) {
                continue;
            }
            if (left == null || right == null) {
                return false;
            }
            if (left.type != right.type
                    || !equals(left.displayText, right.displayText)
                    || !equals(left.actionText, right.actionText)
                    || left.replacementStart != right.replacementStart
                    || left.replacementEnd != right.replacementEnd) {
                return false;
            }
        }
        return true;
    }

    private static boolean equals(final CharSequence left, final CharSequence right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.toString().contentEquals(right);
    }
}
