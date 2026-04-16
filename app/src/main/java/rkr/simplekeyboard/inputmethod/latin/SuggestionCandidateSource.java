package rkr.simplekeyboard.inputmethod.latin;

import java.util.Collections;
import java.util.List;

final class SuggestionCandidateSource implements CandidateSource {
    private static final int MAX_SUGGESTIONS = 3;
    private String mLastWord = "";
    private int mLastStart = -1;
    private int mLastEnd = -1;
    private List<CandidateItem> mItems = Collections.emptyList();

    public void update(final RichInputConnection connection, final InputAttributes inputAttributes) {
        if (connection == null || inputAttributes == null || !inputAttributes.mShouldShowSuggestions
                || connection.hasSelection() || !connection.hasCursorPosition()) {
            mItems = Collections.emptyList();
            return;
        }
        final String before = connection.getTextBeforeCursorCache();
        final String after = connection.getTextAfterCursorCache();
        final SuggestionWordUtils.WordContext wordContext =
                SuggestionWordUtils.getCurrentWord(before, after, connection.getCachedTextStart());
        if (wordContext == null || wordContext.word == null || wordContext.word.isEmpty()) {
            updateItems(Collections.emptyList(), "", -1, -1);
            return;
        }
        if (hasSameWord(wordContext.word, wordContext.start, wordContext.end)) {
            return;
        }

        final List<String> suggestions = SuggestionWordUtils.buildSuggestions(wordContext.word);
        final java.util.ArrayList<CandidateItem> items = new java.util.ArrayList<>(MAX_SUGGESTIONS);
        for (final String suggestion : suggestions) {
            items.add(CandidateItem.createSuggestionItem(
                    suggestion, wordContext.start, wordContext.end));
            if (items.size() >= MAX_SUGGESTIONS) {
                break;
            }
        }
        updateItems(items, wordContext.word, wordContext.start, wordContext.end);
    }

    @Override
    public List<CandidateItem> getItems() {
        return mItems;
    }

    private boolean hasSameWord(final String word, final int start, final int end) {
        return word != null && word.equals(mLastWord) && start == mLastStart && end == mLastEnd;
    }

    private void updateItems(final List<CandidateItem> items, final String word,
            final int start, final int end) {
        mItems = items == null ? Collections.emptyList() : items;
        mLastWord = word == null ? "" : word;
        mLastStart = start;
        mLastEnd = end;
    }

}
