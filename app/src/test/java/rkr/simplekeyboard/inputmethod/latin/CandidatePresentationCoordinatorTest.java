package rkr.simplekeyboard.inputmethod.latin;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CandidatePresentationCoordinatorTest {
    @Test
    public void showDecode_marksDecodeVisible_untilCleared() {
        final RecordingController controller = new RecordingController();
        final CandidatePresentationCoordinator coordinator =
                new CandidatePresentationCoordinator(controller, new SuggestionCandidateSource());

        coordinator.showDecode("decoded");

        assertTrue(coordinator.isDecodeVisible());
        assertTrue(coordinator.hasVisibleContent());
        assertFalse(controller.cleared);
        assertTrue(controller.shownItems instanceof DecodeCandidateSource);
    }

    @Test
    public void clear_resetsDecodeVisibilityAndClearsController() {
        final RecordingController controller = new RecordingController();
        final CandidatePresentationCoordinator coordinator =
                new CandidatePresentationCoordinator(controller, new SuggestionCandidateSource());

        coordinator.showDecode("decoded");
        coordinator.clear();

        assertFalse(coordinator.isDecodeVisible());
        assertFalse(coordinator.hasVisibleContent());
        assertTrue(controller.cleared);
    }

    @Test
    public void showDecode_deduplicatesSamePayload() {
        final RecordingController controller = new RecordingController();
        final CandidatePresentationCoordinator coordinator =
                new CandidatePresentationCoordinator(controller, new SuggestionCandidateSource());

        coordinator.showDecode("decoded");
        coordinator.showDecode("decoded");

        assertEquals(1, controller.showSourceCalls);
    }

    private static final class RecordingController implements CandidateStripController {
        CandidateSource shownItems;
        boolean cleared;
        int showSourceCalls;

        @Override
        public void bindView(final CandidateStripView view) {
        }

        @Override
        public void bindFallbackView(final CandidateStripView view) {
        }

        @Override
        public void showItems(final List<CandidateItem> items) {
            shownItems = () -> items;
        }

        @Override
        public void showSource(final CandidateSource source) {
            shownItems = source;
            showSourceCalls++;
        }

        @Override
        public void clear() {
            cleared = true;
        }
    }
}
