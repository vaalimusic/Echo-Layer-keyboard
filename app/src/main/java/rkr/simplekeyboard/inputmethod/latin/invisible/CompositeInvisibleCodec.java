package rkr.simplekeyboard.inputmethod.latin.invisible;

import android.security.keystore.KeyProperties;
import android.text.TextUtils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public final class CompositeInvisibleCodec implements InvisibleCodec {
    private static final int VERSION = 2;
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final SecureRandom RNG = new SecureRandom();

    // Use neutral format characters for new messages. Legacy ZWJ/ZWNJ payloads remain decodable.
    private static final char INVISIBLE_ZERO = '\u2060';
    private static final char INVISIBLE_ONE = '\u2061';
    private static final String INVISIBLE_START = "\u2062\u2062";
    private static final String INVISIBLE_END = "\u2062\u2063";
    private static final char LEGACY_INVISIBLE_ZERO = '\u200c';
    private static final char LEGACY_INVISIBLE_ONE = '\u200d';
    private static final String LEGACY_INVISIBLE_START = "\u2063\u2063";
    private static final String LEGACY_INVISIBLE_END = "\u2063\u2062";

    private static final char[] HOMOGLYPH_ZERO = {'A', 'B', 'C', 'E', 'H', 'I', 'J', 'K', 'M', 'O', 'P', 'S', 'T', 'X', 'Y', 'a', 'c', 'e', 'i', 'j', 'o', 'p', 's', 'x', 'y'};
    private static final char[] HOMOGLYPH_ONE = {'А', 'В', 'С', 'Е', 'Н', 'І', 'Ј', 'К', 'М', 'О', 'Р', 'Ѕ', 'Т', 'Х', 'Ү', 'а', 'с', 'е', 'і', 'ј', 'о', 'р', 'ѕ', 'х', 'у'};
    private static final char SPACE_ZERO = ' ';
    private static final char SPACE_ONE = '\u00a0';
    private static final String FRAME_PREFIX = "IVK1|";
    private static final String FRAME_SUFFIX = "|END|";
    private static final String VISIBLE_TOKEN_START = "⟦e:";
    private static final String VISIBLE_TOKEN_END = "⟧";

    @Override
    public EncodeResult encode(final String plainText, final char[] passphrase, final InvisibleMode mode,
            final String coverTemplate) {
        if (mode == InvisibleMode.AUTO) {
            for (final InvisibleMode candidate : Arrays.asList(InvisibleMode.INVISIBLE_UNICODE,
                    InvisibleMode.HOMOGLYPH, InvisibleMode.WHITESPACE, InvisibleMode.VISIBLE_TOKEN)) {
                final ValidationResult validation = supports(plainText, candidate, coverTemplate);
                if (!validation.isSupported) {
                    continue;
                }
                final EncodeResult result = encode(plainText, passphrase, candidate, coverTemplate);
                final DecodeResult roundtrip = decode(result.encodedText, passphrase);
                if (roundtrip != null && plainText.equals(roundtrip.decodedText)) {
                    return result;
                }
            }
            throw new IllegalArgumentException("No invisible mode is supported");
        }

        final String carrier = buildCarrier(plainText, coverTemplate);
        final InvisiblePayloadEnvelope envelope = buildEnvelope(plainText, passphrase, mode);
        final byte[] frameBytes = (FRAME_PREFIX + envelope.serialize() + FRAME_SUFFIX)
                .getBytes(StandardCharsets.UTF_8);

        switch (mode) {
        case INVISIBLE_UNICODE:
            return new EncodeResult(embedInvisible(carrier, frameBytes, coverTemplate), mode);
        case HOMOGLYPH:
            return new EncodeResult(embedHomoglyph(carrier, frameBytes), mode);
        case WHITESPACE:
            return new EncodeResult(embedWhitespace(carrier, frameBytes), mode);
        case VISIBLE_TOKEN:
            return new EncodeResult(embedVisibleToken(carrier, envelope.serialize(), coverTemplate), mode);
        default:
            throw new IllegalArgumentException("Unsupported invisible mode: " + mode);
        }
    }

    @Override
    public DecodeResult decode(final String encodedText, final char[] passphrase) {
        final InvisiblePayloadEnvelope visibleEnvelope = extractVisibleTokenEnvelope(encodedText);
        if (visibleEnvelope != null) {
            return new DecodeResult(decrypt(visibleEnvelope, passphrase), visibleEnvelope.mode);
        }

        for (final InvisibleMode mode : Arrays.asList(InvisibleMode.INVISIBLE_UNICODE,
                InvisibleMode.HOMOGLYPH, InvisibleMode.WHITESPACE, InvisibleMode.VISIBLE_TOKEN)) {
            final String frame;
            switch (mode) {
            case INVISIBLE_UNICODE:
                frame = extractInvisible(encodedText);
                break;
            case HOMOGLYPH:
                frame = extractHomoglyph(encodedText);
                break;
            case WHITESPACE:
                frame = extractWhitespace(encodedText);
                break;
            case VISIBLE_TOKEN:
                frame = null;
                break;
            default:
                frame = null;
            }
            if (frame == null || !frame.startsWith(FRAME_PREFIX) || !frame.endsWith(FRAME_SUFFIX)) {
                continue;
            }
            final String serializedEnvelope = frame.substring(
                    FRAME_PREFIX.length(), frame.length() - FRAME_SUFFIX.length());
            final InvisiblePayloadEnvelope envelope = InvisiblePayloadEnvelope.parse(serializedEnvelope);
            return new DecodeResult(decrypt(envelope, passphrase), envelope.mode);
        }
        return null;
    }

    @Override
    public ValidationResult supports(final String text, final InvisibleMode mode, final String coverTemplate) {
        final String carrier = buildCarrier(text, coverTemplate);
        if (TextUtils.isEmpty(carrier)) {
            return new ValidationResult(false, "Empty carrier");
        }
        switch (mode) {
        case INVISIBLE_UNICODE:
            return new ValidationResult(true, null);
        case HOMOGLYPH:
            return new ValidationResult(countHomoglyphSlots(carrier) >= 64,
                    "Not enough homoglyph-compatible characters");
        case WHITESPACE:
            return new ValidationResult(countSpaceSlots(carrier) >= 16,
                    "Not enough whitespace slots");
        case AUTO:
        case VISIBLE_TOKEN:
            return new ValidationResult(true, null);
        default:
            return new ValidationResult(false, "Unsupported mode");
        }
    }

    private static InvisiblePayloadEnvelope buildEnvelope(final String plainText, final char[] passphrase,
            final InvisibleMode mode) {
        try {
            final byte[] salt = new byte[SALT_BYTES];
            final byte[] nonce = new byte[NONCE_BYTES];
            RNG.nextBytes(salt);
            RNG.nextBytes(nonce);
            final byte[] plainBytes = plainText.getBytes(StandardCharsets.UTF_8);
            final byte[] compressedBytes = compress(plainBytes);
            final boolean useCompressed = compressedBytes != null && compressedBytes.length < plainBytes.length;
            final byte[] payloadBytes = useCompressed ? compressedBytes : plainBytes;
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), new GCMParameterSpec(128, nonce));
            final byte[] ciphertext = cipher.doFinal(payloadBytes);
            return new InvisiblePayloadEnvelope(VERSION, mode, useCompressed, salt, nonce, ciphertext);
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("Unable to encrypt hidden message", e);
        }
    }

    private static String decrypt(final InvisiblePayloadEnvelope envelope, final char[] passphrase) {
        try {
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, envelope.salt),
                    new GCMParameterSpec(128, envelope.nonce));
            final byte[] decryptedBytes = cipher.doFinal(envelope.ciphertext);
            final byte[] plainBytes = envelope.compressed ? decompress(decryptedBytes) : decryptedBytes;
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (final GeneralSecurityException e) {
            throw new IllegalArgumentException("Unable to decrypt hidden message", e);
        } catch (final DataFormatException e) {
            throw new IllegalArgumentException("Unable to decompress hidden message", e);
        }
    }

    private static SecretKey deriveKey(final char[] passphrase, final byte[] salt)
            throws GeneralSecurityException {
        final SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        final KeySpec spec = new PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, 256);
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), KeyProperties.KEY_ALGORITHM_AES);
    }

    private static String buildCarrier(final String text, final String coverTemplate) {
        if (!TextUtils.isEmpty(coverTemplate) && CoverTemplateRepository.isValidTemplate(coverTemplate)) {
            return coverTemplate.replace(CoverTemplateRepository.PAYLOAD_MARKER, "");
        }
        return text;
    }

    private static String embedInvisible(final String carrier, final byte[] payloadBytes,
            final String coverTemplate) {
        final String hiddenPayload = INVISIBLE_START + bytesToInvisible(payloadBytes) + INVISIBLE_END;
        if (!TextUtils.isEmpty(coverTemplate) && CoverTemplateRepository.isValidTemplate(coverTemplate)) {
            return coverTemplate.replace(CoverTemplateRepository.PAYLOAD_MARKER, hiddenPayload);
        }
        return carrier + hiddenPayload;
    }

    private static String extractInvisible(final String encodedText) {
        final String currentFrame = extractInvisibleFrame(encodedText,
                INVISIBLE_START, INVISIBLE_END, INVISIBLE_ZERO, INVISIBLE_ONE);
        if (currentFrame != null) {
            return currentFrame;
        }
        return extractInvisibleFrame(encodedText,
                LEGACY_INVISIBLE_START, LEGACY_INVISIBLE_END,
                LEGACY_INVISIBLE_ZERO, LEGACY_INVISIBLE_ONE);
    }

    private static String extractInvisibleFrame(final String encodedText,
            final String startMarker, final String endMarker,
            final char zeroChar, final char oneChar) {
        final int start = encodedText.indexOf(startMarker);
        final int end = encodedText.indexOf(endMarker);
        if (start < 0 || end <= start) {
            return null;
        }
        final String body = encodedText.substring(start + startMarker.length(), end);
        return new String(invisibleToBytes(body, zeroChar, oneChar), StandardCharsets.UTF_8);
    }

    private static String bytesToInvisible(final byte[] bytes) {
        final StringBuilder builder = new StringBuilder(bytes.length * 8);
        for (final byte aByte : bytes) {
            for (int bit = 7; bit >= 0; bit--) {
                builder.append(((aByte >> bit) & 1) == 1 ? INVISIBLE_ONE : INVISIBLE_ZERO);
            }
        }
        return builder.toString();
    }

    private static byte[] invisibleToBytes(final String body, final char zeroChar, final char oneChar) {
        final ByteBuffer buffer = ByteBuffer.allocate(body.length() / 8 + 1);
        int value = 0;
        int bits = 0;
        for (int i = 0; i < body.length(); i++) {
            final char current = body.charAt(i);
            if (current != zeroChar && current != oneChar) {
                continue;
            }
            value = (value << 1) | (current == oneChar ? 1 : 0);
            bits++;
            if (bits == 8) {
                buffer.put((byte) value);
                value = 0;
                bits = 0;
            }
        }
        return Arrays.copyOf(buffer.array(), buffer.position());
    }

    private static String embedHomoglyph(final String carrier, final byte[] payloadBytes) {
        final boolean[] bits = toBits(payloadBytes);
        final StringBuilder builder = new StringBuilder(carrier);
        int bitIndex = 0;
        for (int i = 0; i < builder.length() && bitIndex < bits.length; i++) {
            final int pairIndex = indexOfHomoglyph(builder.charAt(i));
            if (pairIndex < 0) {
                continue;
            }
            builder.setCharAt(i, bits[bitIndex] ? HOMOGLYPH_ONE[pairIndex] : HOMOGLYPH_ZERO[pairIndex]);
            bitIndex++;
        }
        if (bitIndex < bits.length) {
            throw new IllegalArgumentException("Carrier is too small for homoglyph mode");
        }
        return builder.toString();
    }

    private static String extractHomoglyph(final String encodedText) {
        final ArrayList<Boolean> bits = new ArrayList<>();
        for (int i = 0; i < encodedText.length(); i++) {
            final char current = encodedText.charAt(i);
            final int zeroIndex = indexOf(HOMOGLYPH_ZERO, current);
            if (zeroIndex >= 0) {
                bits.add(false);
                continue;
            }
            final int oneIndex = indexOf(HOMOGLYPH_ONE, current);
            if (oneIndex >= 0) {
                bits.add(true);
            }
        }
        return bytesToFrame(bits);
    }

    private static String embedWhitespace(final String carrier, final byte[] payloadBytes) {
        final boolean[] bits = toBits(payloadBytes);
        final StringBuilder builder = new StringBuilder(carrier);
        int bitIndex = 0;
        for (int i = 0; i < builder.length() && bitIndex < bits.length; i++) {
            if (builder.charAt(i) != SPACE_ZERO) {
                continue;
            }
            builder.setCharAt(i, bits[bitIndex] ? SPACE_ONE : SPACE_ZERO);
            bitIndex++;
        }
        if (bitIndex < bits.length) {
            throw new IllegalArgumentException("Carrier is too small for whitespace mode");
        }
        return builder.toString();
    }

    private static String extractWhitespace(final String encodedText) {
        final ArrayList<Boolean> bits = new ArrayList<>();
        for (int i = 0; i < encodedText.length(); i++) {
            final char current = encodedText.charAt(i);
            if (current == SPACE_ZERO) {
                bits.add(false);
            } else if (current == SPACE_ONE) {
                bits.add(true);
            }
        }
        return bytesToFrame(bits);
    }

    private static String embedVisibleToken(final String carrier, final String serializedEnvelope,
            final String coverTemplate) {
        final InvisiblePayloadEnvelope envelope = InvisiblePayloadEnvelope.parse(serializedEnvelope);
        final String token = VISIBLE_TOKEN_START + envelope.serializeCompact() + VISIBLE_TOKEN_END;
        if (!TextUtils.isEmpty(coverTemplate) && CoverTemplateRepository.isValidTemplate(coverTemplate)) {
            return coverTemplate.replace(CoverTemplateRepository.PAYLOAD_MARKER, token);
        }
        return carrier + " " + token;
    }

    private static InvisiblePayloadEnvelope extractVisibleTokenEnvelope(final String encodedText) {
        final int start = encodedText.indexOf(VISIBLE_TOKEN_START);
        final int end = encodedText.indexOf(VISIBLE_TOKEN_END,
                start < 0 ? 0 : start + VISIBLE_TOKEN_START.length());
        if (start < 0 || end <= start) {
            return null;
        }
        final String tokenBody = encodedText.substring(start + VISIBLE_TOKEN_START.length(), end);
        try {
            return InvisiblePayloadEnvelope.parseCompact(tokenBody);
        } catch (final RuntimeException ignored) {
            try {
                return InvisiblePayloadEnvelope.parse(tokenBody);
            } catch (final RuntimeException ignoredLegacy) {
                return null;
            }
        }
    }

    private static String bytesToFrame(final List<Boolean> bits) {
        final ByteBuffer buffer = ByteBuffer.allocate(bits.size() / 8 + 1);
        int value = 0;
        int count = 0;
        for (final boolean bit : bits) {
            value = (value << 1) | (bit ? 1 : 0);
            count++;
            if (count == 8) {
                buffer.put((byte) value);
                count = 0;
                value = 0;
            }
        }
        final String decoded = new String(Arrays.copyOf(buffer.array(), buffer.position()), StandardCharsets.UTF_8);
        final int start = decoded.indexOf(FRAME_PREFIX);
        final int end = decoded.indexOf(FRAME_SUFFIX);
        if (start < 0 || end < start) {
            return null;
        }
        return decoded.substring(start, end + FRAME_SUFFIX.length());
    }

    private static boolean[] toBits(final byte[] payloadBytes) {
        final boolean[] bits = new boolean[payloadBytes.length * 8];
        int index = 0;
        for (final byte payloadByte : payloadBytes) {
            for (int bit = 7; bit >= 0; bit--) {
                bits[index++] = ((payloadByte >> bit) & 1) == 1;
            }
        }
        return bits;
    }

    private static int countHomoglyphSlots(final String carrier) {
        int count = 0;
        for (int i = 0; i < carrier.length(); i++) {
            if (indexOfHomoglyph(carrier.charAt(i)) >= 0) {
                count++;
            }
        }
        return count;
    }

    private static int countSpaceSlots(final String carrier) {
        int count = 0;
        for (int i = 0; i < carrier.length(); i++) {
            if (carrier.charAt(i) == SPACE_ZERO) {
                count++;
            }
        }
        return count;
    }

    private static int indexOfHomoglyph(final char value) {
        int index = indexOf(HOMOGLYPH_ZERO, value);
        if (index >= 0) {
            return index;
        }
        return indexOf(HOMOGLYPH_ONE, value);
    }

    private static int indexOf(final char[] values, final char target) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == target) {
                return i;
            }
        }
        return -1;
    }

    private static byte[] compress(final byte[] input) {
        final Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(input);
        deflater.finish();
        final byte[] buffer = new byte[Math.max(64, input.length)];
        final ByteBuffer output = ByteBuffer.allocate(input.length + 64);
        while (!deflater.finished()) {
            final int count = deflater.deflate(buffer);
            if (count <= 0) {
                break;
            }
            if (output.remaining() < count) {
                final ByteBuffer expanded = ByteBuffer.allocate(output.capacity() * 2 + count);
                output.flip();
                expanded.put(output);
                output.clear();
                return compressToExpanded(deflater, buffer, expanded);
            }
            output.put(buffer, 0, count);
        }
        deflater.end();
        return Arrays.copyOf(output.array(), output.position());
    }

    private static byte[] compressToExpanded(final Deflater deflater, final byte[] buffer,
            final ByteBuffer output) {
        while (!deflater.finished()) {
            final int count = deflater.deflate(buffer);
            if (count <= 0) {
                break;
            }
            if (output.remaining() < count) {
                final ByteBuffer expanded = ByteBuffer.allocate(output.capacity() * 2 + count);
                output.flip();
                expanded.put(output);
                return compressToExpanded(deflater, buffer, expanded);
            }
            output.put(buffer, 0, count);
        }
        deflater.end();
        return Arrays.copyOf(output.array(), output.position());
    }

    private static byte[] decompress(final byte[] input) throws DataFormatException {
        final Inflater inflater = new Inflater();
        inflater.setInput(input);
        final byte[] buffer = new byte[Math.max(128, input.length * 2)];
        final ByteBuffer output = ByteBuffer.allocate(Math.max(256, input.length * 4));
        while (!inflater.finished()) {
            final int count = inflater.inflate(buffer);
            if (count == 0) {
                if (inflater.needsInput()) {
                    break;
                }
            } else if (output.remaining() < count) {
                final ByteBuffer expanded = ByteBuffer.allocate(output.capacity() * 2 + count);
                output.flip();
                expanded.put(output);
                output.clear();
                return decompressToExpanded(inflater, buffer, expanded);
            } else {
                output.put(buffer, 0, count);
            }
        }
        inflater.end();
        return Arrays.copyOf(output.array(), output.position());
    }

    private static byte[] decompressToExpanded(final Inflater inflater, final byte[] buffer,
            final ByteBuffer output) throws DataFormatException {
        while (!inflater.finished()) {
            final int count = inflater.inflate(buffer);
            if (count == 0) {
                if (inflater.needsInput()) {
                    break;
                }
            } else if (output.remaining() < count) {
                final ByteBuffer expanded = ByteBuffer.allocate(output.capacity() * 2 + count);
                output.flip();
                expanded.put(output);
                return decompressToExpanded(inflater, buffer, expanded);
            } else {
                output.put(buffer, 0, count);
            }
        }
        inflater.end();
        return Arrays.copyOf(output.array(), output.position());
    }
}
