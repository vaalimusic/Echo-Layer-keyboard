package rkr.simplekeyboard.inputmethod.latin;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class SuggestionWordUtilsTest {
    @Test
    public void getCurrentWord_usesBeforeAndAfterCursor() {
        final SuggestionWordUtils.WordContext context =
                SuggestionWordUtils.getCurrentWord("meet", "ing now", 10);

        assertNotNull(context);
        assertEquals(10, context.start);
        assertEquals(17, context.end);
        assertEquals("meeting", context.word);
    }

    @Test
    public void buildSuggestions_prefersCommonWords() {
        final List<String> suggestions = SuggestionWordUtils.buildSuggestions("при");

        assertTrue(suggestions.contains("привет") || suggestions.contains("Привет"));
        assertTrue(suggestions.size() <= 3);
    }

    @Test
    public void buildSuggestions_fallsBackToCaseVariants() {
        final List<String> suggestions = SuggestionWordUtils.buildSuggestions("echo");

        assertEquals("echo", suggestions.get(0));
        assertTrue(suggestions.contains("Echo"));
        assertTrue(suggestions.contains("ECHO"));
    }

    @Test
    public void buildSuggestions_returnsEmptyForEmptyInput() {
        assertTrue(SuggestionWordUtils.buildSuggestions("").isEmpty());
    }

    @Test
    public void getCurrentWord_returnsNullWithoutWordCharacters() {
        assertEquals(null, SuggestionWordUtils.getCurrentWord("foo ", " bar", 10));
    }
}
