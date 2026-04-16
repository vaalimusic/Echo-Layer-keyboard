package rkr.simplekeyboard.inputmethod.latin;

import android.content.Context;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

final class SuggestionCandidateSource implements CandidateSource {
    private static final int MAX_SUGGESTIONS = 3;
    private final RuDictionaryRepository mDictionaryRepository;
    private final UserWordHistoryStore mUserWordHistoryStore;
    private String mLastWord = "";
    private int mLastStart = -1;
    private int mLastEnd = -1;
    private List<CandidateItem> mItems = Collections.emptyList();

    SuggestionCandidateSource(final Context context) {
        mDictionaryRepository = new RuDictionaryRepository(context);
        mUserWordHistoryStore = new UserWordHistoryStore(context);
    }

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
            final List<CandidateItem> nextWordItems = buildNextWordItems(connection, before);
            updateItems(nextWordItems, "", -1, -1);
            return;
        }
        if (hasSameWord(wordContext.word, wordContext.start, wordContext.end)) {
            return;
        }

        final List<String> suggestions = buildSuggestions(
                wordContext.word, connection.getTextAfterCursorCache());
        final java.util.ArrayList<CandidateItem> items = new java.util.ArrayList<>(MAX_SUGGESTIONS);
        for (final String suggestion : suggestions) {
            items.add(CandidateItem.createSuggestionItem(
                    suggestion, wordContext.start, wordContext.end));
            if (items.size() >= MAX_SUGGESTIONS) {
                break;
            }
        }
        updateItems(centerPrimarySuggestion(items), wordContext.word, wordContext.start, wordContext.end);
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

    private List<String> buildSuggestions(final String word, final String afterCursor) {
        final LinkedHashSet<String> rankedSuggestions = new LinkedHashSet<>();
        rankedSuggestions.addAll(mUserWordHistoryStore.getSuggestions(word, word, MAX_SUGGESTIONS));
        rankedSuggestions.addAll(mDictionaryRepository.getSuggestions(word, MAX_SUGGESTIONS * 4));
        rankedSuggestions.addAll(SuggestionWordUtils.buildSuggestions(word));
        final ArrayList<String> suggestions = new ArrayList<>(MAX_SUGGESTIONS);
        for (final String suggestion : rankedSuggestions) {
            suggestions.add(suggestion);
            if (suggestions.size() >= MAX_SUGGESTIONS) {
                break;
            }
        }
        injectPunctuationCandidate(suggestions, word, afterCursor);
        return suggestions;
    }

    private List<CandidateItem> buildNextWordItems(final RichInputConnection connection,
            final String beforeCursor) {
        if (connection == null || !TextUtils.isEmpty(connection.getTextAfterCursorCache())) {
            return Collections.emptyList();
        }
        final int cursorPosition = connection.getExpectedSelectionStart();
        final int cachedStart = connection.getCachedTextStart();
        if (cursorPosition < 0 || cachedStart < 0 || beforeCursor == null) {
            return Collections.emptyList();
        }
        if (beforeCursor.isEmpty()) {
            return Collections.emptyList();
        }
        final char lastChar = beforeCursor.charAt(beforeCursor.length() - 1);
        if (!Character.isWhitespace(lastChar) && SuggestionWordUtils.isWordCharacter(lastChar)) {
            return Collections.emptyList();
        }
        final SuggestionWordUtils.WordContext previousWord = SuggestionWordUtils.getPreviousWord(
                beforeCursor, beforeCursor.length(), cachedStart);
        if (previousWord == null || TextUtils.isEmpty(previousWord.word)) {
            return Collections.emptyList();
        }
        final List<String> nextWords = mUserWordHistoryStore.getNextWordSuggestions(
                previousWord.word, MAX_SUGGESTIONS);
        if (nextWords.isEmpty()) {
            return Collections.emptyList();
        }
        final ArrayList<CandidateItem> items = new ArrayList<>(MAX_SUGGESTIONS);
        for (final String nextWord : nextWords) {
            items.add(CandidateItem.createSuggestionItem(nextWord, cursorPosition, cursorPosition));
            if (items.size() >= MAX_SUGGESTIONS) {
                break;
            }
        }
        return centerPrimarySuggestion(items);
    }

    private void injectPunctuationCandidate(final ArrayList<String> suggestions,
            final String originalWord, final String afterCursor) {
        if (suggestions.isEmpty() || !shouldOfferPunctuationCandidate(afterCursor)) {
            return;
        }
        final String punctuationCandidate = buildPunctuationCandidate(
                suggestions.get(0), originalWord);
        if (punctuationCandidate == null || suggestions.contains(punctuationCandidate)) {
            return;
        }
        if (suggestions.size() >= MAX_SUGGESTIONS) {
            suggestions.set(MAX_SUGGESTIONS - 1, punctuationCandidate);
        } else {
            suggestions.add(punctuationCandidate);
        }
    }

    private static boolean shouldOfferPunctuationCandidate(final String afterCursor) {
        if (afterCursor == null || afterCursor.isEmpty()) {
            return true;
        }
        final char nextChar = afterCursor.charAt(0);
        return !Character.isWhitespace(nextChar)
                && !Character.isLetterOrDigit(nextChar)
                ? false
                : true;
    }

    private String buildPunctuationCandidate(final String primarySuggestion,
            final String originalWord) {
        if (primarySuggestion == null || primarySuggestion.isEmpty()) {
            return null;
        }
        final String punctuation = chooseLikelyPunctuation(originalWord);
        return punctuation == null ? null : primarySuggestion + punctuation;
    }

    private String chooseLikelyPunctuation(final String word) {
        if (word == null || word.isEmpty()) {
            return null;
        }
        final String learnedPunctuation = mUserWordHistoryStore.getPreferredPunctuation(word);
        if (learnedPunctuation != null && !learnedPunctuation.isEmpty()) {
            return learnedPunctuation;
        }
        final String lowerWord = word.toLowerCase();
        if (lowerWord.startsWith("привет") || lowerWord.startsWith("здравств")
                || lowerWord.startsWith("hello") || lowerWord.startsWith("hi")) {
            return "!";
        }
        if (lowerWord.startsWith("как") || lowerWord.startsWith("почему")
                || lowerWord.startsWith("зачем") || lowerWord.startsWith("где")
                || lowerWord.startsWith("when") || lowerWord.startsWith("why")
                || lowerWord.startsWith("how") || lowerWord.startsWith("where")) {
            return "?";
        }
        if (lowerWord.length() <= 3) {
            return ",";
        }
        return ".";
    }

    private static List<CandidateItem> centerPrimarySuggestion(final List<CandidateItem> items) {
        if (items == null || items.size() < 3) {
            return items;
        }
        final ArrayList<CandidateItem> reordered = new ArrayList<>(items.size());
        reordered.add(items.get(1));
        reordered.add(items.get(0));
        for (int index = 2; index < items.size(); index++) {
            reordered.add(items.get(index));
        }
        return reordered;
    }

}
