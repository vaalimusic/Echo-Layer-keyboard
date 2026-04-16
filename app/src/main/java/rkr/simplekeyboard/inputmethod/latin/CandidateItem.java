package rkr.simplekeyboard.inputmethod.latin;

public final class CandidateItem {
    public final CandidateItemType type;
    public final CharSequence displayText;
    public final CharSequence actionText;
    public final int replacementStart;
    public final int replacementEnd;

    private CandidateItem(final CandidateItemType type, final CharSequence displayText,
            final CharSequence actionText, final int replacementStart, final int replacementEnd) {
        this.type = type;
        this.displayText = displayText;
        this.actionText = actionText;
        this.replacementStart = replacementStart;
        this.replacementEnd = replacementEnd;
    }

    public static CandidateItem createDecodeItem(final CharSequence decodedText) {
        if (isEmpty(decodedText)) {
            throw new IllegalArgumentException("decodedText must not be empty");
        }
        return new CandidateItem(CandidateItemType.DECODE, decodedText, decodedText, -1, -1);
    }

    public static CandidateItem createSuggestionItem(final CharSequence suggestionText,
            final int replacementStart, final int replacementEnd) {
        if (isEmpty(suggestionText)) {
            throw new IllegalArgumentException("suggestionText must not be empty");
        }
        if (replacementStart < 0 || replacementEnd < replacementStart) {
            throw new IllegalArgumentException("Invalid replacement range");
        }
        return new CandidateItem(CandidateItemType.SUGGESTION, suggestionText, suggestionText,
                replacementStart, replacementEnd);
    }

    public static CandidateItem createStatusItem(final CharSequence statusText) {
        if (isEmpty(statusText)) {
            throw new IllegalArgumentException("statusText must not be empty");
        }
        return new CandidateItem(CandidateItemType.STATUS, statusText, statusText, -1, -1);
    }

    private static boolean isEmpty(final CharSequence text) {
        return text == null || text.length() == 0;
    }
}
