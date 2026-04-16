package rkr.simplekeyboard.inputmethod.latin.invisible;

import android.text.TextUtils;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import rkr.simplekeyboard.inputmethod.latin.RichInputConnection;
import rkr.simplekeyboard.inputmethod.latin.common.Constants;

final class InvisibleTargetResolver {
    TargetText resolve(final RichInputConnection connection) {
        if (connection == null) {
            return null;
        }
        final TargetText selection = resolveSelection(connection);
        if (selection != null) {
            return selection;
        }
        final TargetText extracted = resolveFullExtractedText();
        return extracted != null ? extracted : resolveSurrounding(connection);
    }

    TargetText resolveSelection(final RichInputConnection connection) {
        if (connection == null || !connection.hasSelection()) {
            return null;
        }
        final InvisibleTargetUtils.TargetText targetText = InvisibleTargetUtils.fromSelection(
                connection.getExpectedSelectionStart(),
                connection.getExpectedSelectionEnd(),
                connection.getSelectedTextCache());
        return targetText == null ? null : new TargetText(targetText);
    }

    TargetText resolveSurrounding(final RichInputConnection connection) {
        if (connection == null || !connection.hasCursorPosition()) {
            return null;
        }
        final InvisibleTargetUtils.TargetText targetText = InvisibleTargetUtils.fromSurroundingText(
                connection.getCachedTextStart(),
                connection.getTextBeforeCursorCache(),
                connection.getTextAfterCursorCache());
        return targetText == null ? null : new TargetText(targetText);
    }

    TargetText resolveFullExtractedText() {
        final InputConnection inputConnection = RichInputConnectionAccessor.getCurrentInputConnection();
        if (inputConnection == null) {
            return null;
        }
        final ExtractedTextRequest request = new ExtractedTextRequest();
        request.hintMaxChars = Constants.MAX_CHARACTERS_FOR_RECAPITALIZATION;
        request.hintMaxLines = Constants.MAX_CHARACTERS_FOR_RECAPITALIZATION;
        final ExtractedText extractedText = inputConnection.getExtractedText(request, 0);
        if (extractedText == null || TextUtils.isEmpty(extractedText.text)) {
            return null;
        }
        final int start = Math.max(0, extractedText.startOffset);
        return new TargetText(start, start + extractedText.text.length(),
                extractedText.text.toString(), TargetSource.EXTRACTED_TEXT);
    }

    enum TargetSource {
        SELECTION,
        EXTRACTED_TEXT,
        SURROUNDING_TEXT
    }

    static final class TargetText {
        final int start;
        final int end;
        final String text;
        final TargetSource source;

        TargetText(final int start, final int end, final String text, final TargetSource source) {
            this.start = start;
            this.end = end;
            this.text = text;
            this.source = source;
        }

        TargetText(final InvisibleTargetUtils.TargetText targetText) {
            this(targetText.start, targetText.end, targetText.text, targetText.source);
        }
    }

    static final class RichInputConnectionAccessor {
        private static InputConnection sCurrentInputConnection;

        private RichInputConnectionAccessor() {}

        static void setCurrentInputConnection(final InputConnection inputConnection) {
            sCurrentInputConnection = inputConnection;
        }

        static InputConnection getCurrentInputConnection() {
            return sCurrentInputConnection;
        }
    }
}
