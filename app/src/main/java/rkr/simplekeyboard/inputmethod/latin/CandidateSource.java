package rkr.simplekeyboard.inputmethod.latin;

import java.util.List;

interface CandidateSource {
    List<CandidateItem> getItems();
}
