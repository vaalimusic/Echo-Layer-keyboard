package rkr.simplekeyboard.inputmethod.latin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

final class SuggestionWordUtils {
    static final class WordContext {
        final int start;
        final int end;
        final String word;

        WordContext(final int start, final int end, final String word) {
            this.start = start;
            this.end = end;
            this.word = word;
        }
    }

    private static final String[] COMMON_WORDS = {
            "ok", "okay", "hello", "thanks", "please", "yes", "no", "today", "tomorrow",
            "later", "meeting", "password", "love", "sorry", "hi",
            "привет", "как", "дела", "ок", "спасибо", "пожалуйста", "да", "нет",
            "сегодня", "завтра", "потом", "встреча", "пароль", "люблю", "извини"
    };

    private SuggestionWordUtils() {}

    static WordContext getCurrentWord(final String before, final String after,
            final int cachedTextStart) {
        if (cachedTextStart < 0) {
            return null;
        }
        final String safeBefore = before == null ? "" : before;
        final String safeAfter = after == null ? "" : after;

        int beforeStart = safeBefore.length();
        while (beforeStart > 0 && isWordCharacter(safeBefore.charAt(beforeStart - 1))) {
            beforeStart--;
        }

        int afterEnd = 0;
        while (afterEnd < safeAfter.length() && isWordCharacter(safeAfter.charAt(afterEnd))) {
            afterEnd++;
        }

        if (beforeStart == safeBefore.length() && afterEnd == 0) {
            return null;
        }

        final String word = safeBefore.substring(beforeStart) + safeAfter.substring(0, afterEnd);
        if (word.isEmpty()) {
            return null;
        }
        final int start = cachedTextStart + beforeStart;
        final int end = start + word.length();
        return new WordContext(start, end, word);
    }

    static List<String> buildSuggestions(final String word) {
        final LinkedHashSet<String> suggestions = new LinkedHashSet<>();
        if (word == null || word.isEmpty()) {
            return new ArrayList<>();
        }
        final String normalizedWord = word.toLowerCase();
        for (final String commonWord : COMMON_WORDS) {
            if (commonWord.startsWith(normalizedWord) || normalizedWord.startsWith(commonWord)) {
                suggestions.add(applyOriginalCase(commonWord, word));
            }
        }
        if (suggestions.isEmpty()) {
            suggestions.add(word);
            if (word.length() > 1) {
                final String capitalized = capitalize(word);
                if (!capitalized.equals(word)) {
                    suggestions.add(capitalized);
                }
                final String lowerCased = word.toLowerCase();
                if (!lowerCased.equals(word)) {
                    suggestions.add(lowerCased);
                }
                final String upperCased = word.toUpperCase();
                if (!upperCased.equals(word)) {
                    suggestions.add(upperCased);
                }
            }
        }
        return new ArrayList<>(suggestions);
    }

    static boolean isWordCharacter(final char c) {
        return Character.isLetterOrDigit(c) || c == '\'' || c == '_';
    }

    static String capitalize(final String word) {
        if (word == null || word.isEmpty()) {
            return word;
        }
        final char first = word.charAt(0);
        final char upper = Character.toUpperCase(first);
        if (first == upper) {
            return word;
        }
        return upper + word.substring(1);
    }

    static String applyOriginalCase(final String candidate, final String originalWord) {
        if (candidate == null || candidate.isEmpty() || originalWord == null || originalWord.isEmpty()) {
            return candidate;
        }
        if (Character.isUpperCase(originalWord.charAt(0))) {
            return capitalize(candidate);
        }
        return candidate;
    }
}
