package com.termux.app.terminal;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The user's Claude API key, encrypted with a key that never leaves the Android Keystore. Only
 * the last 4 characters are kept readable, to show which key is set. The key is never logged.
 */
final class ClaudeApiKeyStore {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "termux_claude_api";
    private static final String PREFS_NAME = "claude_api";
    private static final String KEY_CIPHERTEXT = "key";
    private static final String KEY_LAST4 = "last4";
    private static final int GCM_TAG_BITS = 128;

    private ClaudeApiKeyStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    static boolean hasKey(Context context) {
        return prefs(context).contains(KEY_CIPHERTEXT);
    }

    /** A safe way to show the key, like "sk-ant-…abcd", or null if none is set. */
    @Nullable
    static String describe(Context context) {
        String last4 = prefs(context).getString(KEY_LAST4, null);
        return hasKey(context) ? "sk-ant-…" + (last4 == null ? "" : last4) : null;
    }

    static boolean save(Context context, String apiKey) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] ciphertext = cipher.doFinal(apiKey.getBytes(StandardCharsets.UTF_8));
            String stored = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP);
            String last4 = apiKey.length() > 4 ? apiKey.substring(apiKey.length() - 4) : "";
            prefs(context).edit().putString(KEY_CIPHERTEXT, stored).putString(KEY_LAST4, last4).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** The decrypted key, or null. Call only right before a request; don't keep it around. */
    @Nullable
    static String load(Context context) {
        String stored = prefs(context).getString(KEY_CIPHERTEXT, null);
        if (stored == null) return null;
        try {
            int colon = stored.indexOf(':');
            byte[] iv = Base64.decode(stored.substring(0, colon), Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(stored.substring(colon + 1), Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    static void clear(Context context) {
        prefs(context).edit().remove(KEY_CIPHERTEXT).remove(KEY_LAST4).apply();
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(ALIAS)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        return generator.generateKey();
    }

}
