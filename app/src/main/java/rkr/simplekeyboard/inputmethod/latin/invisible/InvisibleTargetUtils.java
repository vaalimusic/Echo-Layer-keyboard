package rkr.simplekeyboard.inputmethod.latin.invisible;

final class InvisibleTargetUtils {
    static final class TargetText {
        final int start;
        final int end;
        final String text;
        final InvisibleTargetResolver.TargetSource source;

        TargetText(final int start, final int end, final String text,
                final InvisibleTargetResolver.TargetSource source) {
            this.start = start;
            this.end = end;
            this.text = text;
            this.source = source;
        }
    }

    private InvisibleTargetUtils() {}

    static TargetText fromSelection(final int selectionStart, final int selectionEnd,
            final String selectedText) {
        if (isEmpty(selectedText)) {
            return null;
        }
        return new TargetText(selectionStart, selectionEnd, selectedText,
                InvisibleTargetResolver.TargetSource.SELECTION);
    }

    static TargetText fromSurroundingText(final int cachedTextStart, final String before,
            final String after) {
        if (cachedTextStart < 0) {
            return null;
        }
        final String safeBefore = before == null ? "" : before;
        final String safeAfter = after == null ? "" : after;
        final String text = safeBefore + safeAfter;
        if (isEmpty(text)) {
            return null;
        }
        return new TargetText(cachedTextStart, cachedTextStart + text.length(), text,
                InvisibleTargetResolver.TargetSource.SURROUNDING_TEXT);
    }

    private static boolean isEmpty(final String text) {
        return text == null || text.isEmpty();
    }
}
