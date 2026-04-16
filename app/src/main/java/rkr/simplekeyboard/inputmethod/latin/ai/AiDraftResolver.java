package rkr.simplekeyboard.inputmethod.latin.ai;

import android.text.TextUtils;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import rkr.simplekeyboard.inputmethod.latin.RichInputConnection;
import rkr.simplekeyboard.inputmethod.latin.common.Constants;

public final class AiDraftResolver {
    public static final class DraftText {
        public final int start;
        public final int end;
        public final String text;

        DraftText(final int start, final int end, final String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }

    public DraftText resolve(final RichInputConnection connection, final InputConnection inputConnection) {
        final DraftText extracted = resolveExtractedText(inputConnection);
        if (extracted != null) {
            return extracted;
        }
        return resolveSafeSurrounding(connection);
    }

    public DraftText resolve(final InputConnection inputConnection,
            final RichInputConnection connection) {
        return resolve(connection, inputConnection);
    }

    private DraftText resolveExtractedText(final InputConnection inputConnection) {
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
        return new DraftText(start, start + extractedText.text.length(), extractedText.text.toString());
    }

    private DraftText resolveSafeSurrounding(final RichInputConnection connection) {
        if (connection == null || !connection.hasCursorPosition()) {
            return null;
        }
        final int start = connection.getCachedTextStart();
        final String before = connection.getTextBeforeCursorCache();
        final String after = connection.getTextAfterCursorCache();
        if (start != 0 || before == null || after == null) {
            return null;
        }
        if (before.length() >= Constants.EDITOR_CONTENTS_CACHE_SIZE
                || after.length() >= Constants.EDITOR_CONTENTS_CACHE_SIZE) {
            return null;
        }
        final String text = before + after;
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        return new DraftText(0, text.length(), text);
    }
}
