package rkr.simplekeyboard.inputmethod.latin;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class CandidateItemTest {
    @Test
    public void createDecodeItem_setsDecodeTypeAndText() {
        final CandidateItem item = CandidateItem.createDecodeItem("echo");

        assertEquals(CandidateItemType.DECODE, item.type);
        assertEquals("echo", item.displayText);
        assertEquals("echo", item.actionText);
    }

    @Test
    public void createSuggestionItem_setsReplacementRange() {
        final CandidateItem item = CandidateItem.createSuggestionItem("echo", 3, 7);

        assertEquals(CandidateItemType.SUGGESTION, item.type);
        assertEquals(3, item.replacementStart);
        assertEquals(7, item.replacementEnd);
    }
}
