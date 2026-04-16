package rkr.simplekeyboard.inputmethod.latin;

import java.util.List;

public interface CandidateStripController {
    void bindView(CandidateStripView view);
    void bindFallbackView(CandidateStripView view);
    void showItems(List<CandidateItem> items);
    void showSource(CandidateSource source);
    void clear();
    void hide();
}
