package rkr.simplekeyboard.inputmethod.latin.invisible;

public interface InvisibleCodec {
    EncodeResult encode(String plainText, char[] passphrase, InvisibleMode mode, String coverTemplate);
    DecodeResult decode(String encodedText, char[] passphrase);
    ValidationResult supports(String text, InvisibleMode mode, String coverTemplate);

    final class ValidationResult {
        public final boolean isSupported;
        public final String message;

        public ValidationResult(final boolean supported, final String message) {
            isSupported = supported;
            this.message = message;
        }
    }

    final class EncodeResult {
        public final String encodedText;
        public final InvisibleMode mode;

        public EncodeResult(final String encodedText, final InvisibleMode mode) {
            this.encodedText = encodedText;
            this.mode = mode;
        }
    }

    final class DecodeResult {
        public final String decodedText;
        public final InvisibleMode mode;

        public DecodeResult(final String decodedText, final InvisibleMode mode) {
            this.decodedText = decodedText;
            this.mode = mode;
        }
    }
}
