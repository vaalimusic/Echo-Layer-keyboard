package rkr.simplekeyboard.inputmethod.latin;

import java.util.Collections;
import java.util.List;

final class DecodeCandidateSource implements CandidateSource {
    private final CharSequence mDecodedText;

    DecodeCandidateSource(final CharSequence decodedText) {
        mDecodedText = decodedText;
    }

    @Override
    public List<CandidateItem> getItems() {
        if (isEmpty(mDecodedText)) {
            return Collections.emptyList();
        }
        return Collections.singletonList(CandidateItem.createDecodeItem(mDecodedText));
    }

    private static boolean isEmpty(final CharSequence text) {
        return text == null || text.length() == 0;
    }
}
