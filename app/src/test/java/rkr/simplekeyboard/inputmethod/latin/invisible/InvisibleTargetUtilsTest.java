package rkr.simplekeyboard.inputmethod.latin.invisible;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class InvisibleTargetUtilsTest {
    @Test
    public void fromSelection_returnsSelectionRange() {
        final InvisibleTargetUtils.TargetText targetText =
                InvisibleTargetUtils.fromSelection(2, 6, "text");

        assertEquals(2, targetText.start);
        assertEquals(6, targetText.end);
        assertEquals("text", targetText.text);
        assertEquals(InvisibleTargetResolver.TargetSource.SELECTION, targetText.source);
    }

    @Test
    public void fromSurroundingText_returnsCombinedRange() {
        final InvisibleTargetUtils.TargetText targetText =
                InvisibleTargetUtils.fromSurroundingText(10, "hi", " there");

        assertEquals(10, targetText.start);
        assertEquals(18, targetText.end);
        assertEquals("hi there", targetText.text);
        assertEquals(InvisibleTargetResolver.TargetSource.SURROUNDING_TEXT, targetText.source);
    }

    @Test
    public void fromSurroundingText_returnsNullForInvalidStart() {
        assertNull(InvisibleTargetUtils.fromSurroundingText(-1, "hi", " there"));
    }

    @Test
    public void fromSelection_returnsNullForEmptyText() {
        assertNull(InvisibleTargetUtils.fromSelection(1, 3, ""));
    }
}
