package rkr.simplekeyboard.inputmethod.latin.invisible;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.SurroundingText;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.TextUtils;
import android.widget.PopupMenu;
import android.widget.Toast;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rkr.simplekeyboard.inputmethod.R;
import rkr.simplekeyboard.inputmethod.keyboard.MainKeyboardView;
import rkr.simplekeyboard.inputmethod.latin.LatinIME;
import rkr.simplekeyboard.inputmethod.latin.RichInputConnection;
import rkr.simplekeyboard.inputmethod.latin.common.Constants;
import rkr.simplekeyboard.inputmethod.latin.settings.Settings;
import rkr.simplekeyboard.inputmethod.latin.settings.SettingsValues;

public final class InvisibleMessageController {
    private static final int MAX_CHAT_SAFE_ENCODED_LENGTH = 1800;
    private static final int MAX_GENERAL_SAFE_ENCODED_LENGTH = 4000;
    private static final int LONG_MESSAGE_THRESHOLD = 220;
    private static final int MENU_ENCODE = 1;
    private static final int MENU_ENCODE_WITH_COVER = 2;
    private static final int MENU_DECODE = 3;
    private static final int MENU_SWITCH_MODE = 4;
    private static final int MENU_EMOJI_MARKER = 5;
    private static final int MENU_PANIC = 6;
    private static final long PANIC_WINDOW_MILLIS = 1800L;

    private final LatinIME mLatinIme;
    private final InvisibleCodec mCodec;
    private final PassphraseStore mPassphraseStore;
    private final CoverTemplateRepository mTemplateRepository;
    private final InvisibleTargetResolver mTargetResolver;
    private final ExecutorService mEncodingExecutor;

    private long mPanicWindowStart;
    private int mPanicPressCount;
    private String mLastAutoDecodedFieldText;
    private volatile boolean mEncodeInProgress;
    private int mEncodeRequestId;
    private volatile boolean mCompactFallbackPending;

    public InvisibleMessageController(final LatinIME latinIme, final SharedPreferences prefs) {
        mLatinIme = latinIme;
        mCodec = new CompositeInvisibleCodec();
        mPassphraseStore = new PassphraseStore(prefs, Settings.PREF_INVISIBLE_PASSPHRASE);
        mTemplateRepository = new CoverTemplateRepository(
                prefs,
                Settings.PREF_INVISIBLE_COVER_TEMPLATES,
                Settings.PREF_INVISIBLE_DEFAULT_TEMPLATE);
        mTargetResolver = new InvisibleTargetResolver();
        mEncodingExecutor = Executors.newSingleThreadExecutor();
    }

    public boolean handleQuickLockPress() {
        final SettingsValues settings = Settings.getInstance().getCurrent();
        if (!settings.mInvisibleFeatureEnabled) {
            showToast(R.string.invisible_feature_disabled);
            return true;
        }
        if (settings.mInvisiblePanicGestureEnabled && maybeTriggerPanic()) {
            return true;
        }
        encodeCurrent(settings.mInvisiblePreferCoverByDefault, true);
        return true;
    }

    public void showLockMenu() {
        final SettingsValues settings = Settings.getInstance().getCurrent();
        if (!settings.mInvisibleFeatureEnabled) {
            showToast(R.string.invisible_feature_disabled);
            return;
        }
        final MainKeyboardView keyboardView = mLatinIme.getKeyboardSwitcher().getMainKeyboardView();
        if (keyboardView == null) {
            return;
        }
        final PopupMenu popupMenu = new PopupMenu(mLatinIme, keyboardView);
        popupMenu.getMenu().add(0, MENU_ENCODE, 0, R.string.invisible_action_encode);
        popupMenu.getMenu().add(0, MENU_ENCODE_WITH_COVER, 1, R.string.invisible_action_encode_with_cover);
        popupMenu.getMenu().add(0, MENU_DECODE, 2, R.string.invisible_action_decode);
        popupMenu.getMenu().add(0, MENU_SWITCH_MODE, 3, R.string.invisible_action_switch_mode);
        popupMenu.getMenu().add(0, MENU_EMOJI_MARKER, 4, R.string.invisible_action_emoji_marker);
        if (settings.mInvisiblePanicGestureEnabled) {
            popupMenu.getMenu().add(0, MENU_PANIC, 5, R.string.invisible_action_panic);
        }
        popupMenu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
            case MENU_ENCODE:
                encodeCurrent(false, false);
                return true;
            case MENU_ENCODE_WITH_COVER:
                encodeCurrent(true, false);
                return true;
            case MENU_DECODE:
                decodeCurrent();
                return true;
            case MENU_SWITCH_MODE:
                cycleMode();
                return true;
            case MENU_EMOJI_MARKER:
                mLatinIme.showEmojiMarkerDialog();
                return true;
            case MENU_PANIC:
                triggerPanic();
                return true;
            default:
                return false;
            }
        });
        popupMenu.show();
    }

    public void triggerPanic() {
        mPassphraseStore.clear();
        final SharedPreferences prefs = Settings.getInstance().getSharedPrefs();
        prefs.edit()
                .remove(Settings.PREF_INVISIBLE_COVER_TEMPLATES)
                .remove(Settings.PREF_INVISIBLE_DEFAULT_TEMPLATE)
                .putString(Settings.PREF_INVISIBLE_MODE, InvisibleMode.AUTO.getPreferenceValue())
                .apply();
        showToast(R.string.invisible_panic_done);
    }

    private void encodeCurrent(final boolean useCover, final boolean requireMasking) {
        if (mEncodeInProgress) {
            mLatinIme.showInvisibleStatusOverlay(
                    mLatinIme.getString(R.string.invisible_status_encoding));
            return;
        }
        final char[] passphrase = loadPassphrase();
        if (passphrase == null || passphrase.length == 0) {
            showToast(R.string.invisible_passphrase_missing);
            return;
        }
        final RichInputConnection connection = mLatinIme.getRichInputConnection();
        InvisibleTargetResolver.RichInputConnectionAccessor.setCurrentInputConnection(
                mLatinIme.getCurrentInputConnection());
        final InvisibleTargetResolver.TargetText targetText = mTargetResolver.resolve(connection);
        if (targetText == null || TextUtils.isEmpty(targetText.text)) {
            showToast(R.string.invisible_target_missing);
            return;
        }
        if (isUnsafePartialTarget(targetText, connection)) {
            mLatinIme.showInvisibleErrorOverlay(
                    mLatinIme.getString(R.string.invisible_target_partial));
            showToast(R.string.invisible_target_partial);
            return;
        }

        final String normalizedSourceText = normalizePlainTextForEncoding(targetText.text);
        final SettingsValues settings = Settings.getInstance().getCurrent();
        final InvisibleMode mode = settings.mInvisibleMode;
        final String template = resolveCoverTemplate(normalizedSourceText, useCover, requireMasking, settings);
        if ((useCover || requireMasking) && !CoverTemplateRepository.isValidTemplate(template)) {
            showToast(R.string.invisible_cover_invalid);
            return;
        }

        final InvisibleCodec.ValidationResult validation = mCodec.supports(normalizedSourceText, mode, template);
        if (!validation.isSupported && mode != InvisibleMode.AUTO) {
            showToast(R.string.invisible_mode_not_supported);
            return;
        }

        mEncodeInProgress = true;
        mCompactFallbackPending = false;
        final int requestId = ++mEncodeRequestId;
        mLatinIme.showInvisibleStatusOverlay(mLatinIme.getString(R.string.invisible_status_encoding));
        if (normalizedSourceText.length() >= LONG_MESSAGE_THRESHOLD) {
            mLatinIme.mHandler.postDelayed(() -> {
                if (mEncodeInProgress && requestId == mEncodeRequestId) {
                    mLatinIme.showInvisibleStatusOverlay(
                            mLatinIme.getString(R.string.invisible_status_compressing));
                }
            }, 180);
        }
        final RichInputConnection targetConnection = connection;
        final String sourceText = normalizedSourceText;
        final String targetTemplate = template;
        final InvisibleMode targetMode = mode;
        final SettingsValues targetSettings = settings;
        final InvisibleTargetResolver.TargetText resolvedTarget = targetText;
        mEncodingExecutor.execute(() -> runEncodeInBackground(requestId, targetConnection, sourceText,
                passphrase, targetMode, targetTemplate, targetSettings, resolvedTarget));
    }

    private void runEncodeInBackground(final int requestId, final RichInputConnection connection,
            final String sourceText, final char[] passphrase, final InvisibleMode mode,
            final String template, final SettingsValues settings,
            final InvisibleTargetResolver.TargetText targetText) {
        try {
            final InvisibleCodec.EncodeResult result =
                    mCodec.encode(sourceText, passphrase, mode, template);
            final CharSequence decoratedText = decorateEncodedText(result.encodedText, settings);
            if (!isEncodedMessageSizeSafe(decoratedText) && shouldUseCompactFallback()) {
                mCompactFallbackPending = true;
                mLatinIme.mHandler.post(() -> mLatinIme.showInvisibleStatusOverlay(
                        mLatinIme.getString(R.string.invisible_status_compact_mode)));
                final String compactTemplate = resolveCompactCoverTemplate();
                final InvisibleCodec.EncodeResult compactResult =
                        mCodec.encode(sourceText, passphrase, InvisibleMode.VISIBLE_TOKEN, compactTemplate);
                final CharSequence compactText = decorateEncodedText(compactResult.encodedText, settings);
                mLatinIme.mHandler.post(() -> finishEncodeOnMainThread(requestId, connection, targetText,
                        compactText));
                return;
            }
            mLatinIme.mHandler.post(() -> finishEncodeOnMainThread(requestId, connection, targetText,
                    decoratedText));
        } catch (final RuntimeException e) {
            mLatinIme.mHandler.post(() -> failEncodeOnMainThread(requestId,
                    R.string.invisible_error_generic));
        }
    }

    private void finishEncodeOnMainThread(final int requestId, final RichInputConnection connection,
            final InvisibleTargetResolver.TargetText targetText, final CharSequence decoratedText) {
        if (requestId != mEncodeRequestId) {
            return;
        }
        mEncodeInProgress = false;
        mCompactFallbackPending = false;
        if (!isEncodedMessageSizeSafe(decoratedText)) {
            mLatinIme.showInvisibleErrorOverlay(
                    mLatinIme.getString(R.string.invisible_message_too_large));
            showToast(R.string.invisible_message_too_large);
            return;
        }
        if (connection.replaceTextRange(targetText.start, targetText.end, decoratedText)) {
            mLatinIme.reloadInvisibleTextCache();
            maybeShowToast(R.string.invisible_success_encoded);
        } else {
            mLatinIme.showInvisibleErrorOverlay(
                    mLatinIme.getString(R.string.invisible_target_missing));
            showToast(R.string.invisible_target_missing);
        }
    }

    private void failEncodeOnMainThread(final int requestId, final int errorResId) {
        if (requestId != mEncodeRequestId) {
            return;
        }
        mEncodeInProgress = false;
        mCompactFallbackPending = false;
        mLatinIme.showInvisibleErrorOverlay(mLatinIme.getString(errorResId));
        showToast(errorResId);
    }

    private String resolveCoverTemplate(final String sourceText, final boolean useCover,
            final boolean requireMasking, final SettingsValues settings) {
        if (useCover) {
            return mTemplateRepository.getDefaultTemplate();
        }
        if (settings.mInvisibleFirstWordCoverEnabled) {
            final String firstWord = extractFirstWord(sourceText);
            if (!TextUtils.isEmpty(firstWord)) {
                final String firstWordTemplate = firstWord + CoverTemplateRepository.PAYLOAD_MARKER;
                if (canUseAsResilientCover(sourceText, firstWordTemplate, settings.mInvisibleMode)) {
                    return firstWordTemplate;
                }
            }
        }
        if (settings.mInvisiblePreferCoverByDefault || requireMasking) {
            final String defaultTemplate = mTemplateRepository.getDefaultTemplate();
            if (CoverTemplateRepository.isValidTemplate(defaultTemplate)) {
                return defaultTemplate;
            }
        }
        if (!requireMasking) {
            return null;
        }
        return null;
    }

    private boolean canUseAsResilientCover(final String sourceText, final String template,
            final InvisibleMode mode) {
        if (!CoverTemplateRepository.isValidTemplate(template)) {
            return false;
        }
        if (mode == InvisibleMode.HOMOGLYPH || mode == InvisibleMode.WHITESPACE) {
            return mCodec.supports(sourceText, mode, template).isSupported;
        }
        return mCodec.supports(sourceText, InvisibleMode.HOMOGLYPH, template).isSupported
                || mCodec.supports(sourceText, InvisibleMode.WHITESPACE, template).isSupported;
    }

    private static CharSequence decorateEncodedText(final String encodedText,
            final SettingsValues settings) {
        final String sanitizedText = sanitizeEncodedCarrier(encodedText);
        if (settings == null || TextUtils.isEmpty(settings.mInvisibleEmojiMarker)) {
            return sanitizedText;
        }
        return settings.mInvisibleEmojiMarker.trim() + sanitizedText;
    }

    private static String sanitizeEncodedCarrier(final String encodedText) {
        if (TextUtils.isEmpty(encodedText)) {
            return encodedText;
        }
        final StringBuilder builder = new StringBuilder(encodedText.length());
        boolean previousWasSpace = false;
        for (int index = 0; index < encodedText.length(); index++) {
            final char ch = encodedText.charAt(index);
            if (ch == '\r' || ch == '\n' || ch == '\u2028' || ch == '\u2029') {
                if (!previousWasSpace) {
                    builder.append(' ');
                    previousWasSpace = true;
                }
                continue;
            }
            builder.append(ch);
            previousWasSpace = ch == ' ';
        }
        return builder.toString().trim();
    }

    private static String normalizePlainTextForEncoding(final String text) {
        if (TextUtils.isEmpty(text)) {
            return text;
        }
        final StringBuilder builder = new StringBuilder(text.length());
        boolean previousWasSpace = false;
        for (int index = 0; index < text.length(); index++) {
            final char ch = text.charAt(index);
            if (ch == '\r' || ch == '\n' || ch == '\u2028' || ch == '\u2029' || ch == '\t') {
                if (!previousWasSpace) {
                    builder.append(' ');
                    previousWasSpace = true;
                }
                continue;
            }
            builder.append(ch);
            previousWasSpace = ch == ' ';
        }
        return builder.toString().trim();
    }

    private static String extractFirstWord(final String text) {
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        final int length = text.length();
        int start = 0;
        while (start < length && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        if (start >= length) {
            return null;
        }
        int end = start;
        while (end < length && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        return text.substring(start, end);
    }

    private boolean isEncodedMessageSizeSafe(final CharSequence encodedText) {
        if (encodedText == null) {
            return false;
        }
        final int maxLength = isMessagingAppEditor() ? MAX_CHAT_SAFE_ENCODED_LENGTH
                : MAX_GENERAL_SAFE_ENCODED_LENGTH;
        return encodedText.length() <= maxLength;
    }

    private boolean isMessagingAppEditor() {
        final EditorInfo editorInfo = mLatinIme.getCurrentInputEditorInfo();
        final String packageName = editorInfo == null ? null : editorInfo.packageName;
        if (TextUtils.isEmpty(packageName)) {
            return false;
        }
        final String normalized = packageName.toLowerCase();
        return normalized.contains("telegram")
                || normalized.contains("whatsapp")
                || normalized.contains("vk")
                || normalized.contains("vkontakte")
                || normalized.contains("messenger")
                || normalized.contains("discord")
                || normalized.contains("instagram")
                || normalized.contains("wechat")
                || normalized.contains("signal")
                || normalized.contains("skype");
    }

    private boolean shouldUseCompactFallback() {
        return isMessagingAppEditor();
    }

    private boolean isUnsafePartialTarget(final InvisibleTargetResolver.TargetText targetText,
            final RichInputConnection connection) {
        if (targetText == null
                || targetText.source != InvisibleTargetResolver.TargetSource.SURROUNDING_TEXT
                || connection == null) {
            return false;
        }
        if (targetText.start > 0) {
            return true;
        }
        final String before = connection.getTextBeforeCursorCache();
        final String after = connection.getTextAfterCursorCache();
        return (before != null && before.length() >= Constants.EDITOR_CONTENTS_CACHE_SIZE)
                || (after != null && after.length() >= Constants.EDITOR_CONTENTS_CACHE_SIZE);
    }

    private String resolveCompactCoverTemplate() {
        return CoverTemplateRepository.PAYLOAD_MARKER;
    }

    private void decodeCurrent() {
        final char[] passphrase = loadPassphrase();
        if (passphrase == null || passphrase.length == 0) {
            showToast(R.string.invisible_passphrase_missing);
            return;
        }
        final RichInputConnection connection = mLatinIme.getRichInputConnection();
        try {
            final InvisibleCodec.DecodeResult decoded = tryDecodeFromAvailableSources(connection,
                    passphrase);
            if (decoded == null || TextUtils.isEmpty(decoded.decodedText)) {
                mLatinIme.showInvisibleErrorOverlay(
                        mLatinIme.getString(R.string.invisible_decode_not_found));
                showToast(R.string.invisible_decode_not_found);
                return;
            }
            mLatinIme.showDecodedCandidate(decoded.decodedText);
            showToast(R.string.invisible_decode_payload_found);
        } catch (final RuntimeException e) {
            mLatinIme.showInvisibleErrorOverlay(
                    mLatinIme.getString(R.string.invisible_decode_failed));
            showToast(R.string.invisible_decode_failed);
        }
    }

    private InvisibleCodec.DecodeResult tryDecodeFromAvailableSources(
            final RichInputConnection connection, final char[] passphrase) {
        final InvisibleCodec.DecodeResult fieldDecoded =
                tryDecodeFromInputField(connection, passphrase);
        if (fieldDecoded != null && !TextUtils.isEmpty(fieldDecoded.decodedText)) {
            return fieldDecoded;
        }

        return tryDecodeFromClipboard(passphrase);
    }

    public void handleClipboardChanged() {
        final SettingsValues settings = Settings.getInstance().getCurrent();
        if (settings == null || !settings.mInvisibleFeatureEnabled) {
            return;
        }
        final char[] passphrase = loadPassphrase();
        if (passphrase == null || passphrase.length == 0) {
            return;
        }
        final String clipboardText = loadClipboardText();
        if (TextUtils.isEmpty(clipboardText)) {
            return;
        }
        final InvisibleCodec.DecodeResult decoded = tryDecodeFromClipboard(passphrase);
        if (decoded != null && !TextUtils.isEmpty(decoded.decodedText)) {
            if (mLatinIme.isInputViewShown()) {
                mLatinIme.showDecodedCandidate(decoded.decodedText);
            } else if (settings.mInvisibleClipboardPopupEnabled) {
                mLatinIme.showDecodedClipboardPopup(decoded.decodedText);
            } else {
                mLatinIme.showDecodedCandidate(decoded.decodedText);
            }
        }
    }

    public void handleInputFieldChanged() {
        final SettingsValues settings = Settings.getInstance().getCurrent();
        if (settings == null || !settings.mInvisibleFeatureEnabled) {
            return;
        }
        final char[] passphrase = loadPassphrase();
        if (passphrase == null || passphrase.length == 0) {
            return;
        }
        final RichInputConnection connection = mLatinIme.getRichInputConnection();
        final String directSelectedText = loadDirectSelectedText();
        final String directFieldText = loadDirectFieldText();
        final String fingerprint = buildFieldFingerprint(connection, directSelectedText, directFieldText);
        if (TextUtils.isEmpty(fingerprint) || fingerprint.equals(mLastAutoDecodedFieldText)) {
            return;
        }
        mLastAutoDecodedFieldText = fingerprint;
        final InvisibleCodec.DecodeResult decoded = tryDecodeFromInputField(
                connection, passphrase, directSelectedText, directFieldText);
        if (decoded != null && !TextUtils.isEmpty(decoded.decodedText)) {
            mLatinIme.showDecodedCandidate(decoded.decodedText);
        }
    }

    private InvisibleCodec.DecodeResult tryDecodeFromInputField(
            final RichInputConnection connection, final char[] passphrase) {
        return tryDecodeFromInputField(connection, passphrase,
                loadDirectSelectedText(), loadDirectFieldText());
    }

    private InvisibleCodec.DecodeResult tryDecodeFromInputField(
            final RichInputConnection connection, final char[] passphrase,
            final String directSelectedText, final String directFieldText) {
        final String directBeforeText = loadDirectTextBeforeCursor();
        final String directAfterText = loadDirectTextAfterCursor();
        for (final String candidate : collectInputFieldDecodeCandidates(
                connection, directSelectedText, directBeforeText, directAfterText, directFieldText)) {
            final InvisibleCodec.DecodeResult decoded = tryDecodeText(candidate, passphrase);
            if (decoded != null && !TextUtils.isEmpty(decoded.decodedText)) {
                return decoded;
            }
        }
        return null;
    }

    private List<String> collectInputFieldDecodeCandidates(final RichInputConnection connection,
            final String directSelectedText, final String directBeforeText,
            final String directAfterText, final String directFieldText) {
        final LinkedHashSet<String> candidates = new LinkedHashSet<>();
        final String cachedSelectedText = connection == null ? null : connection.getSelectedTextCache();
        final String cachedBeforeText = connection == null ? null : connection.getTextBeforeCursorCache();
        final String cachedAfterText = connection == null ? null : connection.getTextAfterCursorCache();
        addCandidate(candidates, cachedSelectedText);
        addCandidate(candidates, directSelectedText);

        final String cachedFieldText = buildCachedFieldText(connection);
        addCandidate(candidates, cachedFieldText);
        addCandidate(candidates, directFieldText);

        addSelectionExpansionCandidates(candidates, cachedSelectedText, cachedBeforeText, cachedAfterText);
        addSelectionExpansionCandidates(candidates, directSelectedText, directBeforeText, directAfterText);

        addWindowCandidates(candidates, cachedFieldText);
        addWindowCandidates(candidates, directFieldText);

        return new ArrayList<>(candidates);
    }

    private InvisibleCodec.DecodeResult tryDecodeText(final String text, final char[] passphrase) {
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        try {
            return mCodec.decode(text, passphrase);
        } catch (final RuntimeException ignored) {
            return null;
        }
    }

    private String loadClipboardText() {
        final ClipboardManager clipboard = (ClipboardManager)
                mLatinIme.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            return null;
        }
        final ClipData clipData = clipboard.getPrimaryClip();
        if (clipData == null || clipData.getItemCount() < 1) {
            return null;
        }
        final CharSequence text = clipData.getItemAt(0).coerceToText(mLatinIme);
        return text == null ? null : text.toString();
    }

    private String loadDirectSelectedText() {
        final InputConnection inputConnection = mLatinIme.getCurrentInputConnection();
        if (inputConnection == null) {
            return null;
        }
        final CharSequence selected = inputConnection.getSelectedText(0);
        return selected == null ? null : selected.toString();
    }

    private String loadDirectFieldText() {
        final InputConnection inputConnection = mLatinIme.getCurrentInputConnection();
        if (inputConnection == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            final SurroundingText surroundingText = inputConnection.getSurroundingText(
                    Constants.EDITOR_CONTENTS_CACHE_SIZE,
                    Constants.EDITOR_CONTENTS_CACHE_SIZE,
                    0);
            if (surroundingText == null || surroundingText.getText() == null) {
                return null;
            }
            return surroundingText.getText().toString();
        }
        final CharSequence before = inputConnection.getTextBeforeCursor(
                Constants.EDITOR_CONTENTS_CACHE_SIZE, 0);
        final CharSequence selected = inputConnection.getSelectedText(0);
        final CharSequence after = inputConnection.getTextAfterCursor(
                Constants.EDITOR_CONTENTS_CACHE_SIZE, 0);
        final String beforeText = before == null ? "" : before.toString();
        final String selectedText = selected == null ? "" : selected.toString();
        final String afterText = after == null ? "" : after.toString();
        final String combined = beforeText + selectedText + afterText;
        return combined.isEmpty() ? null : combined;
    }

    private String loadDirectTextBeforeCursor() {
        final InputConnection inputConnection = mLatinIme.getCurrentInputConnection();
        if (inputConnection == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            final SurroundingText surroundingText = inputConnection.getSurroundingText(
                    Constants.EDITOR_CONTENTS_CACHE_SIZE,
                    Constants.EDITOR_CONTENTS_CACHE_SIZE,
                    0);
            if (surroundingText == null || surroundingText.getText() == null) {
                return null;
            }
            return surroundingText.getText()
                    .subSequence(0, surroundingText.getSelectionStart())
                    .toString();
        }
        final CharSequence before = inputConnection.getTextBeforeCursor(
                Constants.EDITOR_CONTENTS_CACHE_SIZE, 0);
        return before == null ? null : before.toString();
    }

    private String loadDirectTextAfterCursor() {
        final InputConnection inputConnection = mLatinIme.getCurrentInputConnection();
        if (inputConnection == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            final SurroundingText surroundingText = inputConnection.getSurroundingText(
                    Constants.EDITOR_CONTENTS_CACHE_SIZE,
                    Constants.EDITOR_CONTENTS_CACHE_SIZE,
                    0);
            if (surroundingText == null || surroundingText.getText() == null) {
                return null;
            }
            return surroundingText.getText()
                    .subSequence(surroundingText.getSelectionEnd(),
                            surroundingText.getText().length())
                    .toString();
        }
        final CharSequence after = inputConnection.getTextAfterCursor(
                Constants.EDITOR_CONTENTS_CACHE_SIZE, 0);
        return after == null ? null : after.toString();
    }

    private static String buildCachedFieldText(final RichInputConnection connection) {
        if (connection == null) {
            return null;
        }
        final String before = connection.getTextBeforeCursorCache();
        final String selected = connection.getSelectedTextCache();
        final String after = connection.getTextAfterCursorCache();
        final String combined = (before == null ? "" : before)
                + (selected == null ? "" : selected)
                + (after == null ? "" : after);
        return combined.isEmpty() ? null : combined;
    }

    private static String buildFieldFingerprint(final RichInputConnection connection,
            final String directSelectedText, final String directFieldText) {
        final String cachedField = buildCachedFieldText(connection);
        final String selection = connection == null ? null : connection.getSelectedTextCache();
        final StringBuilder fingerprint = new StringBuilder();
        appendFingerprintPart(fingerprint, cachedField);
        appendFingerprintPart(fingerprint, selection);
        appendFingerprintPart(fingerprint, directFieldText);
        appendFingerprintPart(fingerprint, directSelectedText);
        return fingerprint.length() == 0 ? null : fingerprint.toString();
    }

    private static void addCandidate(final LinkedHashSet<String> candidates, final String text) {
        if (!TextUtils.isEmpty(text)) {
            candidates.add(text);
        }
    }

    private static void appendFingerprintPart(final StringBuilder builder, final String value) {
        if (TextUtils.isEmpty(value)) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('\n').append('-').append('-').append('-').append('\n');
        }
        builder.append(value);
    }

    private static void addWindowCandidates(final LinkedHashSet<String> candidates,
            final String fieldText) {
        if (TextUtils.isEmpty(fieldText)) {
            return;
        }
        final int[] tokenStarts = new int[24];
        final int[] tokenEnds = new int[24];
        int tokenCount = 0;
        boolean inToken = false;
        int tokenStart = 0;
        for (int index = 0; index < fieldText.length(); index++) {
            final char current = fieldText.charAt(index);
            if (!Character.isWhitespace(current)) {
                if (!inToken) {
                    inToken = true;
                    tokenStart = index;
                }
            } else if (inToken) {
                if (tokenCount == tokenStarts.length) {
                    break;
                }
                tokenStarts[tokenCount] = tokenStart;
                tokenEnds[tokenCount] = index;
                tokenCount++;
                inToken = false;
            }
        }
        if (inToken && tokenCount < tokenStarts.length) {
            tokenStarts[tokenCount] = tokenStart;
            tokenEnds[tokenCount] = fieldText.length();
            tokenCount++;
        }
        for (int startIndex = 0; startIndex < tokenCount; startIndex++) {
            final int maxEndIndex = Math.min(tokenCount, startIndex + 8);
            for (int endIndex = startIndex; endIndex < maxEndIndex; endIndex++) {
                addCandidate(candidates,
                        fieldText.substring(tokenStarts[startIndex], tokenEnds[endIndex]));
            }
        }
    }

    private static void addSelectionExpansionCandidates(final LinkedHashSet<String> candidates,
            final String selectedText, final String beforeText, final String afterText) {
        if (TextUtils.isEmpty(selectedText)) {
            return;
        }
        addCandidate(candidates, selectedText);
        final String safeBefore = beforeText == null ? "" : beforeText;
        final String safeAfter = afterText == null ? "" : afterText;
        if (!safeAfter.isEmpty()) {
            addCandidate(candidates, selectedText + safeAfter);
            addCandidate(candidates, selectedText + slicePrefix(safeAfter, 32));
            addCandidate(candidates, selectedText + slicePrefix(safeAfter, 96));
            addCandidate(candidates, selectedText + slicePrefix(safeAfter, 256));
        }
        if (!safeBefore.isEmpty()) {
            addCandidate(candidates, sliceSuffix(safeBefore, 32) + selectedText);
            addCandidate(candidates, sliceSuffix(safeBefore, 96) + selectedText);
        }
        if (!safeBefore.isEmpty() || !safeAfter.isEmpty()) {
            addCandidate(candidates, sliceSuffix(safeBefore, 96) + selectedText
                    + slicePrefix(safeAfter, 96));
            addCandidate(candidates, safeBefore + selectedText + safeAfter);
        }
    }

    private static String slicePrefix(final String value, final int maxChars) {
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        return value.substring(0, Math.min(value.length(), maxChars));
    }

    private static String sliceSuffix(final String value, final int maxChars) {
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        return value.substring(Math.max(0, value.length() - maxChars));
    }

    private InvisibleCodec.DecodeResult tryDecodeFromClipboard(final char[] passphrase) {
        final String clipboardText = loadClipboardText();
        if (TextUtils.isEmpty(clipboardText)) {
            return null;
        }
        final LinkedHashSet<String> candidates = new LinkedHashSet<>();
        addCandidate(candidates, clipboardText);
        addWindowCandidates(candidates, clipboardText);
        for (final String candidate : candidates) {
            final InvisibleCodec.DecodeResult decoded = tryDecodeText(candidate, passphrase);
            if (decoded != null && !TextUtils.isEmpty(decoded.decodedText)) {
                return decoded;
            }
        }
        return null;
    }

    private void cycleMode() {
        final SharedPreferences prefs = Settings.getInstance().getSharedPrefs();
        final InvisibleMode current = Settings.getInstance().getCurrent().mInvisibleMode;
        final InvisibleMode next;
        switch (current) {
        case AUTO:
            next = InvisibleMode.INVISIBLE_UNICODE;
            break;
        case INVISIBLE_UNICODE:
            next = InvisibleMode.HOMOGLYPH;
            break;
        case HOMOGLYPH:
            next = InvisibleMode.WHITESPACE;
            break;
        default:
            next = InvisibleMode.AUTO;
            break;
        }
        prefs.edit().putString(Settings.PREF_INVISIBLE_MODE, next.getPreferenceValue()).apply();
        showToast(R.string.invisible_mode_switched);
    }

    private boolean maybeTriggerPanic() {
        final long now = SystemClock.uptimeMillis();
        if (now - mPanicWindowStart > PANIC_WINDOW_MILLIS) {
            mPanicWindowStart = now;
            mPanicPressCount = 0;
        }
        mPanicPressCount++;
        if (mPanicPressCount >= 5) {
            mPanicPressCount = 0;
            triggerPanic();
            return true;
        }
        return false;
    }

    private char[] loadPassphrase() {
        try {
            return mPassphraseStore.load();
        } catch (final GeneralSecurityException e) {
            return null;
        }
    }

    private void maybeShowToast(final int resId) {
        if (Settings.getInstance().getCurrent().mInvisibleShowStatusToasts) {
            showToast(resId);
        }
    }

    private void showToast(final int resId) {
        Toast.makeText(mLatinIme, resId, Toast.LENGTH_SHORT).show();
    }
}
