package rkr.simplekeyboard.inputmethod.latin;

import android.content.Context;
import android.content.res.AssetManager;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

final class RuDictionaryRepository {
    private static final String DICTIONARY_ASSET_PATH = "dictionaries/ru/index.dic";
    private static final int MAX_DICTIONARY_WORDS = 200_000;

    private final Context mContext;
    private volatile List<String> mCachedWords;

    RuDictionaryRepository(final Context context) {
        mContext = context;
    }

    public List<String> getSuggestions(final String word, final int maxSuggestions) {
        if (TextUtils.isEmpty(word)) {
            return Collections.emptyList();
        }
        final String normalizedWord = word.toLowerCase();
        final List<String> dictionaryWords = getWords();
        if (dictionaryWords.isEmpty()) {
            return Collections.emptyList();
        }
        final LinkedHashSet<String> suggestions = new LinkedHashSet<>();
        for (final String candidate : dictionaryWords) {
            if (TextUtils.isEmpty(candidate)) {
                continue;
            }
            if (candidate.startsWith(normalizedWord) || normalizedWord.startsWith(candidate)) {
                suggestions.add(SuggestionWordUtils.applyOriginalCase(candidate, word));
                if (suggestions.size() >= maxSuggestions) {
                    break;
                }
            }
        }
        return new ArrayList<>(suggestions);
    }

    private List<String> getWords() {
        final List<String> cached = mCachedWords;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (mCachedWords == null) {
                mCachedWords = loadWords();
            }
            return mCachedWords;
        }
    }

    private List<String> loadWords() {
        final AssetManager assetManager = mContext.getAssets();
        try (InputStream inputStream = assetManager.open(DICTIONARY_ASSET_PATH);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            final ArrayList<String> words = new ArrayList<>();
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (TextUtils.isEmpty(line)) {
                    continue;
                }
                if (firstLine) {
                    firstLine = false;
                    if (line.matches("\\d+")) {
                        continue;
                    }
                }
                final String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                final int slashIndex = trimmed.indexOf('/');
                final String word = slashIndex >= 0 ? trimmed.substring(0, slashIndex) : trimmed;
                final String normalized = word.toLowerCase();
                if (!normalized.isEmpty()) {
                    words.add(normalized);
                }
                if (words.size() >= MAX_DICTIONARY_WORDS) {
                    break;
                }
            }
            return words;
        } catch (final IOException e) {
            return Collections.emptyList();
        }
    }
}
