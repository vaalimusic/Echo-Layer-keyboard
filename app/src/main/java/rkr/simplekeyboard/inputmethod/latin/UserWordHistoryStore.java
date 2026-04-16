package rkr.simplekeyboard.inputmethod.latin;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class UserWordHistoryStore {
    private static final String PREFS_NAME = "user_word_history";
    private static final String KEY_PREFIX = "word_";
    private static final String PUNCT_PREFIX = "punct_";
    private static final String NEXT_PREFIX = "next_";
    private static final int MAX_WORD_LENGTH = 48;
    private static final int MIN_WORD_LENGTH = 2;
    private static final int MAX_COUNT = 9999;

    private final Context mContext;
    private SharedPreferences mPrefs;

    UserWordHistoryStore(final Context context) {
        mContext = context;
    }

    public void recordWord(final String word) {
        final String normalizedWord = normalizeWord(word);
        if (TextUtils.isEmpty(normalizedWord)) {
            return;
        }
        final SharedPreferences prefs = getPrefs();
        if (prefs == null) {
            return;
        }
        final String key = KEY_PREFIX + normalizedWord;
        final int currentCount = prefs.getInt(key, 0);
        prefs.edit().putInt(key, Math.min(MAX_COUNT, currentCount + 1)).apply();
    }

    public void recordWordWithPunctuation(final String word, final int punctuationCodePoint) {
        final String normalizedWord = normalizeWord(word);
        final String punctuation = normalizePunctuation(punctuationCodePoint);
        if (TextUtils.isEmpty(normalizedWord) || TextUtils.isEmpty(punctuation)) {
            return;
        }
        final SharedPreferences prefs = getPrefs();
        if (prefs == null) {
            return;
        }
        final String key = PUNCT_PREFIX + normalizedWord + "_" + punctuation;
        final int currentCount = prefs.getInt(key, 0);
        prefs.edit().putInt(key, Math.min(MAX_COUNT, currentCount + 1)).apply();
    }

    public void recordNextWord(final String previousWord, final String nextWord) {
        final String normalizedPrevious = normalizeWord(previousWord);
        final String normalizedNext = normalizeWord(nextWord);
        if (TextUtils.isEmpty(normalizedPrevious) || TextUtils.isEmpty(normalizedNext)) {
            return;
        }
        final SharedPreferences prefs = getPrefs();
        if (prefs == null) {
            return;
        }
        final String key = NEXT_PREFIX + normalizedPrevious + "_" + normalizedNext;
        final int currentCount = prefs.getInt(key, 0);
        prefs.edit().putInt(key, Math.min(MAX_COUNT, currentCount + 1)).apply();
    }

    public List<String> getSuggestions(final String prefix, final String originalWord,
            final int maxSuggestions) {
        final String normalizedPrefix = normalizeWord(prefix);
        if (TextUtils.isEmpty(normalizedPrefix) || maxSuggestions <= 0) {
            return Collections.emptyList();
        }
        final SharedPreferences prefs = getPrefs();
        if (prefs == null) {
            return Collections.emptyList();
        }
        final Map<String, ?> entries = prefs.getAll();
        if (entries.isEmpty()) {
            return Collections.emptyList();
        }
        final ArrayList<RankedWord> rankedWords = new ArrayList<>();
        for (final Map.Entry<String, ?> entry : entries.entrySet()) {
            final String key = entry.getKey();
            if (!key.startsWith(KEY_PREFIX)) {
                continue;
            }
            final Object value = entry.getValue();
            if (!(value instanceof Integer)) {
                continue;
            }
            final String candidate = key.substring(KEY_PREFIX.length());
            if (candidate.startsWith(normalizedPrefix) || normalizedPrefix.startsWith(candidate)) {
                rankedWords.add(new RankedWord(candidate, (Integer) value));
            }
        }
        if (rankedWords.isEmpty()) {
            return Collections.emptyList();
        }
        rankedWords.sort(Comparator
                .comparingInt((RankedWord word) -> word.count).reversed()
                .thenComparing(word -> word.value));
        final LinkedHashSet<String> suggestions = new LinkedHashSet<>();
        for (final RankedWord rankedWord : rankedWords) {
            suggestions.add(SuggestionWordUtils.applyOriginalCase(rankedWord.value, originalWord));
            if (suggestions.size() >= maxSuggestions) {
                break;
            }
        }
        return new ArrayList<>(suggestions);
    }

    public String getPreferredPunctuation(final String word) {
        final String normalizedWord = normalizeWord(word);
        if (TextUtils.isEmpty(normalizedWord)) {
            return null;
        }
        final SharedPreferences prefs = getPrefs();
        if (prefs == null) {
            return null;
        }
        final Map<String, ?> entries = prefs.getAll();
        if (entries.isEmpty()) {
            return null;
        }
        final String prefix = PUNCT_PREFIX + normalizedWord + "_";
        String bestPunctuation = null;
        int bestCount = 0;
        for (final Map.Entry<String, ?> entry : entries.entrySet()) {
            final String key = entry.getKey();
            if (!key.startsWith(prefix) || !(entry.getValue() instanceof Integer)) {
                continue;
            }
            final int count = (Integer) entry.getValue();
            final String punctuation = key.substring(prefix.length());
            if (count > bestCount) {
                bestCount = count;
                bestPunctuation = punctuation;
            }
        }
        return bestPunctuation;
    }

    public List<String> getNextWordSuggestions(final String previousWord, final int maxSuggestions) {
        final String normalizedPrevious = normalizeWord(previousWord);
        if (TextUtils.isEmpty(normalizedPrevious) || maxSuggestions <= 0) {
            return Collections.emptyList();
        }
        final SharedPreferences prefs = getPrefs();
        if (prefs == null) {
            return Collections.emptyList();
        }
        final Map<String, ?> entries = prefs.getAll();
        if (entries.isEmpty()) {
            return Collections.emptyList();
        }
        final String prefix = NEXT_PREFIX + normalizedPrevious + "_";
        final ArrayList<RankedWord> rankedWords = new ArrayList<>();
        for (final Map.Entry<String, ?> entry : entries.entrySet()) {
            final String key = entry.getKey();
            if (!key.startsWith(prefix) || !(entry.getValue() instanceof Integer)) {
                continue;
            }
            final String candidate = key.substring(prefix.length());
            rankedWords.add(new RankedWord(candidate, (Integer) entry.getValue()));
        }
        if (rankedWords.isEmpty()) {
            return Collections.emptyList();
        }
        rankedWords.sort(Comparator
                .comparingInt((RankedWord word) -> word.count).reversed()
                .thenComparing(word -> word.value));
        final ArrayList<String> suggestions = new ArrayList<>(maxSuggestions);
        for (final RankedWord rankedWord : rankedWords) {
            suggestions.add(rankedWord.value);
            if (suggestions.size() >= maxSuggestions) {
                break;
            }
        }
        return suggestions;
    }

    private static String normalizeWord(final String word) {
        if (TextUtils.isEmpty(word)) {
            return null;
        }
        final String trimmed = word.trim();
        if (trimmed.length() < MIN_WORD_LENGTH || trimmed.length() > MAX_WORD_LENGTH) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (!SuggestionWordUtils.isWordCharacter(trimmed.charAt(i))) {
                return null;
            }
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static String normalizePunctuation(final int codePoint) {
        switch (codePoint) {
            case ',':
            case '.':
            case '!':
            case '?':
            case ';':
            case ':':
                return String.valueOf((char) codePoint);
            default:
                return null;
        }
    }

    private SharedPreferences getPrefs() {
        if (mPrefs != null) {
            return mPrefs;
        }
        final Context context = mContext;
        if (context == null) {
            return null;
        }
        mPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return mPrefs;
    }

    private static final class RankedWord {
        final String value;
        final int count;

        RankedWord(final String value, final int count) {
            this.value = value;
            this.count = count;
        }
    }
}
