package rkr.simplekeyboard.inputmethod.latin.invisible;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class PassphraseStore {
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "simple_keyboard_invisible_passphrase";

    private final SharedPreferences mPrefs;
    private final String mPrefsKey;

    public PassphraseStore(final SharedPreferences prefs, final String prefsKey) {
        mPrefs = prefs;
        mPrefsKey = prefsKey;
    }

    public boolean hasPassphrase() {
        return mPrefs.contains(mPrefsKey);
    }

    public void save(final char[] passphrase) throws GeneralSecurityException {
        final byte[] plaintext = new String(passphrase).getBytes(StandardCharsets.UTF_8);
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        final byte[] ciphertext = cipher.doFinal(plaintext);
        final byte[] nonce = cipher.getIV();
        final String storedValue = Base64.encodeToString(nonce, Base64.NO_WRAP | Base64.URL_SAFE)
                + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP | Base64.URL_SAFE);
        mPrefs.edit().putString(mPrefsKey, storedValue).apply();
    }

    public char[] load() throws GeneralSecurityException {
        final String storedValue = mPrefs.getString(mPrefsKey, null);
        if (storedValue == null) {
            return null;
        }
        final String[] parts = storedValue.split(":");
        if (parts.length != 2) {
            throw new GeneralSecurityException("Invalid encrypted passphrase");
        }
        final byte[] nonce = Base64.decode(parts[0], Base64.NO_WRAP | Base64.URL_SAFE);
        final byte[] ciphertext = Base64.decode(parts[1], Base64.NO_WRAP | Base64.URL_SAFE);
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, nonce));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8).toCharArray();
    }

    public void clear() {
        mPrefs.edit().remove(mPrefsKey).apply();
    }

    private SecretKey getOrCreateKey() throws GeneralSecurityException {
        final KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
        try {
            keyStore.load(null);
        } catch (final Exception e) {
            throw new GeneralSecurityException("Unable to load keystore", e);
        }
        final SecretKey existingKey = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        if (existingKey != null) {
            return existingKey;
        }
        final KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
