package rkr.simplekeyboard.inputmethod.latin.invisible;

import android.util.Base64;

import java.nio.ByteBuffer;

public final class InvisiblePayloadEnvelope {
    private static final String MAGIC = "IVK1";

    public final int version;
    public final InvisibleMode mode;
    public final boolean compressed;
    public final byte[] salt;
    public final byte[] nonce;
    public final byte[] ciphertext;

    public InvisiblePayloadEnvelope(final int version, final InvisibleMode mode, final byte[] salt,
            final byte[] nonce, final byte[] ciphertext) {
        this(version, mode, false, salt, nonce, ciphertext);
    }

    public InvisiblePayloadEnvelope(final int version, final InvisibleMode mode, final boolean compressed,
            final byte[] salt, final byte[] nonce, final byte[] ciphertext) {
        this.version = version;
        this.mode = mode;
        this.compressed = compressed;
        this.salt = salt;
        this.nonce = nonce;
        this.ciphertext = ciphertext;
    }

    public String serialize() {
        return MAGIC
                + ":" + version
                + ":" + mode.name()
                + ":" + (compressed ? "1" : "0")
                + ":" + encode(salt)
                + ":" + encode(nonce)
                + ":" + encode(ciphertext);
    }

    public static InvisiblePayloadEnvelope parse(final String serialized) {
        final String[] parts = serialized.split(":");
        if ((parts.length != 6 && parts.length != 7) || !MAGIC.equals(parts[0])) {
            throw new IllegalArgumentException("Invalid payload envelope");
        }
        final boolean compressed;
        final int saltIndex;
        if (parts.length == 7) {
            compressed = "1".equals(parts[3]);
            saltIndex = 4;
        } else {
            compressed = false;
            saltIndex = 3;
        }
        return new InvisiblePayloadEnvelope(
                Integer.parseInt(parts[1]),
                InvisibleMode.valueOf(parts[2]),
                compressed,
                decode(parts[saltIndex]),
                decode(parts[saltIndex + 1]),
                decode(parts[saltIndex + 2]));
    }

    public String serializeCompact() {
        final ByteBuffer buffer = ByteBuffer.allocate(2 + salt.length + nonce.length + ciphertext.length);
        buffer.put((byte) version);
        buffer.put((byte) ((mode.ordinal() & 0x0F) | (compressed ? 0x10 : 0)));
        buffer.put(salt);
        buffer.put(nonce);
        buffer.put(ciphertext);
        return encode(buffer.array());
    }

    public static InvisiblePayloadEnvelope parseCompact(final String serialized) {
        final byte[] bytes = decode(serialized);
        if (bytes.length < 2 + 16 + 12) {
            throw new IllegalArgumentException("Invalid compact payload envelope");
        }
        final int version = bytes[0] & 0xFF;
        final int flags = bytes[1] & 0xFF;
        final int modeOrdinal = flags & 0x0F;
        final boolean compressed = (flags & 0x10) != 0;
        final InvisibleMode[] modes = InvisibleMode.values();
        if (modeOrdinal < 0 || modeOrdinal >= modes.length) {
            throw new IllegalArgumentException("Invalid compact payload mode");
        }
        final ByteBuffer buffer = ByteBuffer.wrap(bytes, 2, bytes.length - 2);
        final byte[] salt = new byte[16];
        final byte[] nonce = new byte[12];
        final byte[] ciphertext = new byte[buffer.remaining() - salt.length - nonce.length];
        buffer.get(salt);
        buffer.get(nonce);
        buffer.get(ciphertext);
        return new InvisiblePayloadEnvelope(version, modes[modeOrdinal], compressed, salt, nonce, ciphertext);
    }

    private static String encode(final byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP | Base64.URL_SAFE);
    }

    private static byte[] decode(final String value) {
        return Base64.decode(value, Base64.NO_WRAP | Base64.URL_SAFE);
    }
}
