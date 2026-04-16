/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (C) 2025 Raimondas Rimkus
 * Copyright (C) 2021 wittmane
 * Copyright (C) 2021 Maarten Trompper
 * Copyright (C) 2019 Micha LaQua
 * Copyright (C) 2019 Emmanuel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rkr.simplekeyboard.inputmethod.latin;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
import android.os.Build;
import android.os.Debug;
import android.os.IBinder;
import android.os.Message;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import rkr.simplekeyboard.inputmethod.R;
import rkr.simplekeyboard.inputmethod.compat.EditorInfoCompatUtils;
import rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat;
import rkr.simplekeyboard.inputmethod.event.Event;
import rkr.simplekeyboard.inputmethod.event.InputTransaction;
import rkr.simplekeyboard.inputmethod.keyboard.Keyboard;
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardActionListener;
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardId;
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardSwitcher;
import rkr.simplekeyboard.inputmethod.keyboard.MainKeyboardView;
import rkr.simplekeyboard.inputmethod.latin.ai.AiDraftResolver;
import rkr.simplekeyboard.inputmethod.latin.ai.AiProvider;
import rkr.simplekeyboard.inputmethod.latin.ai.AiRewriteClient;
import rkr.simplekeyboard.inputmethod.latin.common.Constants;
import rkr.simplekeyboard.inputmethod.latin.define.DebugFlags;
import rkr.simplekeyboard.inputmethod.latin.invisible.InvisibleMessageController;
import rkr.simplekeyboard.inputmethod.latin.inputlogic.InputLogic;
import rkr.simplekeyboard.inputmethod.latin.settings.Settings;
import rkr.simplekeyboard.inputmethod.latin.settings.SettingsActivity;
import rkr.simplekeyboard.inputmethod.latin.settings.SettingsValues;
import rkr.simplekeyboard.inputmethod.latin.utils.ApplicationUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.LeakGuardHandlerWrapper;
import rkr.simplekeyboard.inputmethod.latin.utils.ResourceUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.ViewLayoutUtils;

/**
 * Input method implementation for Qwerty'ish keyboard.
 */
public class LatinIME extends InputMethodService implements KeyboardActionListener,
        RichInputMethodManager.SubtypeChangedListener {
    static final String TAG = LatinIME.class.getSimpleName();
    private static final boolean TRACE = false;

    private static final int EXTENDED_TOUCHABLE_REGION_HEIGHT = 100;
    private static final int PERIOD_FOR_AUDIO_AND_HAPTIC_FEEDBACK_IN_KEY_REPEAT = 2;
    private static final int PENDING_IMS_CALLBACK_DURATION_MILLIS = 800;
    private static final long INVISIBLE_LOCK_ARM_TIMEOUT_MILLIS = 1800L;
    static final long DELAY_DEALLOCATE_MEMORY_MILLIS = TimeUnit.SECONDS.toMillis(10);
    private static final String PREF_PENDING_DECODED_TEXT = "pref_pending_decoded_text_internal";

    final Settings mSettings;
    private Locale mLocale;
    final InputLogic mInputLogic = new InputLogic(this /* LatinIME */);

    // TODO: Move these {@link View}s to {@link KeyboardSwitcher}.
    private View mInputView;

    private RichInputMethodManager mRichImm;
    final KeyboardSwitcher mKeyboardSwitcher;
    private InvisibleMessageController mInvisibleMessageController;
    private final CandidateStripController mCandidateStripController =
            new CandidateStripControllerImpl(this);
    private SuggestionCandidateSource mSuggestionCandidateSource;
    private UserWordHistoryStore mUserWordHistoryStore;
    private CandidatePresentationCoordinator mCandidatePresentationCoordinator;
    private CharSequence mPendingDecodedCandidate;
    private boolean mIsHidingIme;
    private final Runnable mAutoDecodeInputFieldRunnable = () -> {
        if (mInvisibleMessageController != null && isInputViewShown()) {
            mInvisibleMessageController.handleInputFieldChanged();
        }
    };
    private ClipboardManager mClipboardManager;
    private boolean mClipboardDecodeListenerRegistered;
    private final ClipboardManager.OnPrimaryClipChangedListener mClipboardDecodeListener =
            this::handleClipboardChangedAsync;
    private final AiDraftResolver mAiDraftResolver = new AiDraftResolver();
    private final AiRewriteClient mAiRewriteClient = new AiRewriteClient();
    private final ExecutorService mAiExecutor = Executors.newSingleThreadExecutor();
    private boolean mAiRewriteInProgress;
    private boolean mInvisibleLockArmed;
    private final Runnable mInvisibleLockArmTimeoutRunnable = () ->
            cancelInvisibleLockArming(true);

    private AlertDialog mOptionsDialog;
    private AlertDialog mEmojiMarkerDialog;

    public final UIHandler mHandler = new UIHandler(this);

    public static final class UIHandler extends LeakGuardHandlerWrapper<LatinIME> {
        private static final int MSG_UPDATE_SHIFT_STATE = 0;
        private static final int MSG_PENDING_IMS_CALLBACK = 1;
        private static final int MSG_DEALLOCATE_MEMORY = 9;

        public UIHandler(final LatinIME ownerInstance) {
            super(ownerInstance);
        }

        @Override
        public void handleMessage(final Message msg) {
            final LatinIME latinIme = getOwnerInstance();
            if (latinIme == null) {
                return;
            }
            final KeyboardSwitcher switcher = latinIme.mKeyboardSwitcher;
            switch (msg.what) {
            case MSG_UPDATE_SHIFT_STATE:
                switcher.requestUpdatingShiftState(latinIme.getCurrentAutoCapsState(),
                        latinIme.getCurrentRecapitalizeState());
                break;
            case MSG_DEALLOCATE_MEMORY:
                latinIme.deallocateMemory();
                break;
            }
        }

        public void postUpdateShiftState() {
            removeMessages(MSG_UPDATE_SHIFT_STATE);
            sendMessage(obtainMessage(MSG_UPDATE_SHIFT_STATE));
        }

        public void postDeallocateMemory() {
            sendMessageDelayed(obtainMessage(MSG_DEALLOCATE_MEMORY),
                    DELAY_DEALLOCATE_MEMORY_MILLIS);
        }

        public void cancelDeallocateMemory() {
            removeMessages(MSG_DEALLOCATE_MEMORY);
        }

        public boolean hasPendingDeallocateMemory() {
            return hasMessages(MSG_DEALLOCATE_MEMORY);
        }

        // Working variables for the following methods.
        private boolean mIsOrientationChanging;
        private boolean mPendingSuccessiveImsCallback;
        private boolean mHasPendingStartInput;
        private boolean mHasPendingFinishInputView;
        private boolean mHasPendingFinishInput;
        private EditorInfo mAppliedEditorInfo;

        private void resetPendingImsCallback() {
            mHasPendingFinishInputView = false;
            mHasPendingFinishInput = false;
            mHasPendingStartInput = false;
        }

        private void executePendingImsCallback(final LatinIME latinIme, final EditorInfo editorInfo,
                boolean restarting) {
            if (mHasPendingFinishInputView) {
                latinIme.onFinishInputViewInternal(mHasPendingFinishInput);
            }
            if (mHasPendingFinishInput) {
                latinIme.onFinishInputInternal();
            }
            if (mHasPendingStartInput) {
                latinIme.onStartInputInternal(editorInfo, restarting);
            }
            resetPendingImsCallback();
        }

        public void onStartInput(final EditorInfo editorInfo, final boolean restarting) {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                // Typically this is the second onStartInput after orientation changed.
                mHasPendingStartInput = true;
            } else {
                if (mIsOrientationChanging && restarting) {
                    // This is the first onStartInput after orientation changed.
                    mIsOrientationChanging = false;
                    mPendingSuccessiveImsCallback = true;
                }
                final LatinIME latinIme = getOwnerInstance();
                if (latinIme != null) {
                    executePendingImsCallback(latinIme, editorInfo, restarting);
                    latinIme.onStartInputInternal(editorInfo, restarting);
                }
            }
        }

        public void onStartInputView(final EditorInfo editorInfo, final boolean restarting) {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)
                    && KeyboardId.equivalentEditorInfoForKeyboard(editorInfo, mAppliedEditorInfo)) {
                // Typically this is the second onStartInputView after orientation changed.
                resetPendingImsCallback();
            } else {
                if (mPendingSuccessiveImsCallback) {
                    // This is the first onStartInputView after orientation changed.
                    mPendingSuccessiveImsCallback = false;
                    resetPendingImsCallback();
                    sendMessageDelayed(obtainMessage(MSG_PENDING_IMS_CALLBACK),
                            PENDING_IMS_CALLBACK_DURATION_MILLIS);
                }
                final LatinIME latinIme = getOwnerInstance();
                if (latinIme != null) {
                    executePendingImsCallback(latinIme, editorInfo, restarting);
                    latinIme.onStartInputViewInternal(editorInfo, restarting);
                    mAppliedEditorInfo = editorInfo;
                }
                cancelDeallocateMemory();
            }
        }

        public void onFinishInputView(final boolean finishingInput) {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                // Typically this is the first onFinishInputView after orientation changed.
                mHasPendingFinishInputView = true;
            } else {
                final LatinIME latinIme = getOwnerInstance();
                if (latinIme != null) {
                    latinIme.onFinishInputViewInternal(finishingInput);
                    mAppliedEditorInfo = null;
                }
                if (!hasPendingDeallocateMemory()) {
                    postDeallocateMemory();
                }
            }
        }

        public void onFinishInput() {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                // Typically this is the first onFinishInput after orientation changed.
                mHasPendingFinishInput = true;
            } else {
                final LatinIME latinIme = getOwnerInstance();
                if (latinIme != null) {
                    executePendingImsCallback(latinIme, null, false);
                    latinIme.onFinishInputInternal();
                }
            }
        }
    }

    public LatinIME() {
        super();
        mSettings = Settings.getInstance();
        mKeyboardSwitcher = KeyboardSwitcher.getInstance();
    }

    @Override
    public void onCreate() {
        Settings.init(this);
        DebugFlags.init(PreferenceManagerCompat.getDeviceSharedPreferences(this));
        RichInputMethodManager.init(this);
        mRichImm = RichInputMethodManager.getInstance();
        mRichImm.setSubtypeChangeHandler(this);
        KeyboardSwitcher.init(this);
        AudioAndHapticFeedbackManager.init(this);
        super.onCreate();

        // TODO: Resolve mutual dependencies of {@link #loadSettings()} and
        // {@link #resetDictionaryFacilitatorIfNecessary()}.
        loadSettings();
        mSuggestionCandidateSource = new SuggestionCandidateSource(this);
        mUserWordHistoryStore = new UserWordHistoryStore(this);
        mCandidatePresentationCoordinator = new CandidatePresentationCoordinator(
                mCandidateStripController, mSuggestionCandidateSource);
        mInvisibleMessageController = new InvisibleMessageController(this,
                PreferenceManagerCompat.getDeviceSharedPreferences(this));
        mClipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        registerClipboardDecodeListener();

        // Register to receive ringer mode change.
        final IntentFilter filter = new IntentFilter();
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        registerReceiver(mRingerModeChangeReceiver, filter);
    }

    private void loadSettings() {
        mLocale = mRichImm.getCurrentSubtype().getLocaleObject();
        final EditorInfo editorInfo = getCurrentInputEditorInfo();
        final InputAttributes inputAttributes = new InputAttributes(editorInfo, isFullscreenMode());
        mSettings.loadSettings(inputAttributes);
        final SettingsValues currentSettingsValues = mSettings.getCurrent();
        AudioAndHapticFeedbackManager.getInstance().onSettingsChanged(currentSettingsValues);
    }

    @Override
    public void onDestroy() {
        mSettings.onDestroy();
        unregisterClipboardDecodeListener();
        unregisterReceiver(mRingerModeChangeReceiver);
        mAiExecutor.shutdownNow();
        super.onDestroy();
    }

    private boolean isImeSuppressedByHardwareKeyboard() {
        final KeyboardSwitcher switcher = KeyboardSwitcher.getInstance();
        return !onEvaluateInputViewShown() && switcher.isImeSuppressedByHardwareKeyboard(
                mSettings.getCurrent(), switcher.getKeyboardSwitchState());
    }

    @Override
    public boolean onEvaluateInputViewShown() {
        final boolean useOnScreen = super.onEvaluateInputViewShown();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            return useOnScreen;
        } else {
            return useOnScreen || mSettings.getCurrent().mUseOnScreen;
        }
    }

    @Override
    public void onConfigurationChanged(final Configuration conf) {
        SettingsValues settingsValues = mSettings.getCurrent();
        if (settingsValues.mHasHardwareKeyboard != Settings.readHasHardwareKeyboard(conf)) {
            // If the state of having a hardware keyboard changed, then we want to reload the
            // settings to adjust for that.
            // TODO: we should probably do this unconditionally here, rather than only when we
            // have a change in hardware keyboard configuration.
            loadSettings();
        }

        mKeyboardSwitcher.onConfigurationChanged();

        super.onConfigurationChanged(conf);
    }

    @Override
    public View onCreateInputView() {
        return mKeyboardSwitcher.onCreateInputView();
    }

    @Override
    public View onCreateCandidatesView() {
        final View view = LayoutInflater.from(this).inflate(R.layout.candidate_strip, null);
        if (view instanceof CandidateStripView) {
            mCandidateStripController.bindView((CandidateStripView) view);
        } else {
            mCandidateStripController.bindView(null);
        }
        return view;
    }

    @Override
    public void setInputView(final View view) {
        super.setInputView(view);
        mInputView = view;
        if (view instanceof InputView) {
            mCandidateStripController.bindFallbackView(
                    ((InputView) view).getInlineCandidateStripView());
        } else {
            mCandidateStripController.bindFallbackView(null);
        }
        updateSoftInputWindowLayoutParameters();
        view.requestApplyInsets();
    }

    @Override
    public void setCandidatesView(final View view) {
        super.setCandidatesView(view);
        if (view instanceof CandidateStripView) {
            mCandidateStripController.bindView((CandidateStripView) view);
        } else {
            mCandidateStripController.bindView(null);
        }
    }

    @Override
    public void onStartInput(final EditorInfo editorInfo, final boolean restarting) {
        mHandler.onStartInput(editorInfo, restarting);
    }

    @Override
    public void onStartInputView(final EditorInfo editorInfo, final boolean restarting) {
        mHandler.onStartInputView(editorInfo, restarting);
    }

    @Override
    public void onFinishInputView(final boolean finishingInput) {
        mInputLogic.clearCaches();
        mRichImm.resetSubtypeCycleOrder();
        mHandler.onFinishInputView(finishingInput);
    }

    @Override
    public void onFinishInput() {
        mHandler.onFinishInput();
    }

    @Override
    public void onCurrentSubtypeChanged() {
        mInputLogic.onSubtypeChanged();
        loadKeyboard();
    }

    void onStartInputInternal(final EditorInfo editorInfo, final boolean restarting) {
        super.onStartInput(editorInfo, restarting);

        // If the primary hint language does not match the current subtype language, then try
        // to switch to the primary hint language.
        // TODO: Support all the locales in EditorInfo#hintLocales.
        final Locale primaryHintLocale = EditorInfoCompatUtils.getPrimaryHintLocale(editorInfo);
        if (primaryHintLocale == null) {
            return;
        }
        mRichImm.setCurrentSubtype(primaryHintLocale);
    }

    void onStartInputViewInternal(final EditorInfo editorInfo, final boolean restarting) {
        super.onStartInputView(editorInfo, restarting);
        clearCandidateStrip();

        // Switch to the null consumer to handle cases leading to early exit below, for which we
        // also wouldn't be consuming gesture data.
        final KeyboardSwitcher switcher = mKeyboardSwitcher;
        switcher.updateKeyboardTheme();
        final MainKeyboardView mainKeyboardView = switcher.getMainKeyboardView();
        // If we are starting input in a different text field from before, we'll have to reload
        // settings, so currentSettingsValues can't be final.
        SettingsValues currentSettingsValues = mSettings.getCurrent();

        if (editorInfo == null) {
            Log.e(TAG, "Null EditorInfo in onStartInputView()");
            if (DebugFlags.DEBUG_ENABLED) {
                throw new NullPointerException("Null EditorInfo in onStartInputView()");
            }
            return;
        }
        if (DebugFlags.DEBUG_ENABLED) {
            Log.d(TAG, "onStartInputView: editorInfo:"
                    + String.format("inputType=0x%08x imeOptions=0x%08x",
                            editorInfo.inputType, editorInfo.imeOptions));
            Log.d(TAG, "All caps = "
                    + ((editorInfo.inputType & InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0)
                    + ", sentence caps = "
                    + ((editorInfo.inputType & InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0)
                    + ", word caps = "
                    + ((editorInfo.inputType & InputType.TYPE_TEXT_FLAG_CAP_WORDS) != 0));
        }
        Log.i(TAG, "Starting input. Cursor position = "
                + editorInfo.initialSelStart + "," + editorInfo.initialSelEnd +
                " Restarting = " + restarting);

        // In landscape mode, this method gets called without the input view being created.
        if (mainKeyboardView == null) {
            return;
        }

        final boolean inputTypeChanged = !currentSettingsValues.isSameInputType(editorInfo);
        final boolean isDifferentTextField = !restarting || inputTypeChanged;

        // The EditorInfo might have a flag that affects fullscreen mode.
        // Note: This call should be done by InputMethodService?
        updateFullscreenMode();

        // ALERT: settings have not been reloaded and there is a chance they may be stale.
        // In the practice, if it is, we should have gotten onConfigurationChanged so it should
        // be fine, but this is horribly confusing and must be fixed AS SOON AS POSSIBLE.

        // In some cases the input connection has not been reset yet and we can't access it. In
        // this case we will need to call loadKeyboard() later, when it's accessible, so that we
        // can go into the correct mode, so we need to do some housekeeping here.
        if (!isImeSuppressedByHardwareKeyboard()) {
            // The app calling setText() has the effect of clearing the composing
            // span, so we should reset our state unconditionally, even if restarting is true.
            // We also tell the input logic about the combining rules for the current subtype, so
            // it can adjust its combiners if needed.
            mInputLogic.startInput();

            // Some applications call onStartInputView without updating EditorInfo. In these cases
            // selection will be incorrect.
            mInputLogic.mConnection.reloadTextCache(editorInfo, restarting);
        }

        if (isDifferentTextField ||
                !currentSettingsValues.hasSameOrientation(getResources().getConfiguration())) {
            loadSettings();
        }
        if (isDifferentTextField) {
            mainKeyboardView.closing();
            currentSettingsValues = mSettings.getCurrent();

            switcher.loadKeyboard(editorInfo, currentSettingsValues, getCurrentAutoCapsState(),
                    getCurrentRecapitalizeState());
        } else {
            // TODO: Come up with a more comprehensive way to reset the keyboard layout when
            // a keyboard layout set doesn't get reloaded in this method.
            switcher.resetKeyboardStateToAlphabet(getCurrentAutoCapsState(),
                    getCurrentRecapitalizeState());
        }

        if (TRACE) Debug.startMethodTracing("/data/trace/latinime");
        refreshSuggestionCandidates();
        setCandidatesViewShown(false);
        showPendingDecodedCandidateIfNeeded();
        scheduleInvisibleInputFieldCheck();
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        if (isInputViewShown())
            setNavigationBarColor();
    }

    @Override
    public void onWindowHidden() {
        super.onWindowHidden();
        cancelInvisibleLockArming(false);
        mIsHidingIme = false;
        mHandler.removeCallbacks(mAutoDecodeInputFieldRunnable);
        dismissEmojiMarkerDialog();
        mCandidateStripController.hide();
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.closing();
        }
    }

    void onFinishInputInternal() {
        super.onFinishInput();
        cancelInvisibleLockArming(false);

        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.closing();
        }
    }

    void onFinishInputViewInternal(final boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        cancelInvisibleLockArming(false);
        mIsHidingIme = false;
        mHandler.removeCallbacks(mAutoDecodeInputFieldRunnable);
        dismissEmojiMarkerDialog();
        mCandidateStripController.hide();
    }

    protected void deallocateMemory() {
        mKeyboardSwitcher.deallocateMemory();
    }

    @Override
    public void onUpdateSelection(final int oldSelStart, final int oldSelEnd,
            final int newSelStart, final int newSelEnd,
            final int composingSpanStart, final int composingSpanEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                composingSpanStart, composingSpanEnd);
        final MainKeyboardView keyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (keyboardView != null && keyboardView.isInCursorMove()) {
            return;
        }

        Log.i(TAG, "Update Selection. Cursor position = " + newSelStart + "," + newSelEnd);

        mInputLogic.onUpdateSelection(newSelStart, newSelEnd);
        if (isInputViewShown()) {
            mInputLogic.reloadTextCache();

            mKeyboardSwitcher.requestUpdatingShiftState(getCurrentAutoCapsState(),
                    getCurrentRecapitalizeState());
            refreshSuggestionCandidates();
            scheduleInvisibleInputFieldCheck();
        }
    }

    @Override
    public void hideWindow() {
        cancelInvisibleLockArming(false);
        mIsHidingIme = false;
        mKeyboardSwitcher.onHideWindow();
        mHandler.removeCallbacks(mAutoDecodeInputFieldRunnable);
        dismissEmojiMarkerDialog();
        mCandidateStripController.hide();

        if (TRACE) Debug.stopMethodTracing();
        if (isShowingOptionDialog()) {
            mOptionsDialog.dismiss();
            mOptionsDialog = null;
        }
        super.hideWindow();
    }

    @Override
    public void onComputeInsets(final InputMethodService.Insets outInsets) {
        super.onComputeInsets(outInsets);
        // This method may be called before {@link #setInputView(View)}.
        if (mInputView == null) {
            return;
        }
        final View visibleKeyboardView = mKeyboardSwitcher.getVisibleKeyboardView();
        final View imeContentView;
        if (mInputView instanceof InputView) {
            final View keyboardContentContainer =
                    ((InputView) mInputView).getKeyboardContentContainer();
            imeContentView = keyboardContentContainer != null ? keyboardContentContainer
                    : visibleKeyboardView;
        } else {
            imeContentView = visibleKeyboardView;
        }
        if (visibleKeyboardView == null || imeContentView == null) {
            return;
        }
        final int inputHeight = mInputView.getHeight();
        if (isImeSuppressedByHardwareKeyboard() && !visibleKeyboardView.isShown()) {
            // If there is a hardware keyboard and a visible software keyboard view has been hidden,
            // no visual element will be shown on the screen.
            outInsets.contentTopInsets = inputHeight;
            outInsets.visibleTopInsets = inputHeight;
            return;
        }
        final int visibleTopY = inputHeight - imeContentView.getHeight();
        // Need to set expanded touchable region only if a keyboard view is being shown.
        if (imeContentView.isShown()) {
            final int touchLeft = 0;
            final int touchTop = mKeyboardSwitcher.isShowingMoreKeysPanel() ? 0 : visibleTopY;
            final int touchRight = imeContentView.getWidth();
            final int touchBottom = inputHeight
                    // Extend touchable region below the keyboard.
                    + EXTENDED_TOUCHABLE_REGION_HEIGHT;
            outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION;
            outInsets.touchableRegion.set(touchLeft, touchTop, touchRight, touchBottom);
        }
        outInsets.contentTopInsets = visibleTopY;
        outInsets.visibleTopInsets = visibleTopY;
    }

    @Override
    public boolean onShowInputRequested(final int flags, final boolean configChange) {
        if (isImeSuppressedByHardwareKeyboard()) {
            return true;
        }
        return super.onShowInputRequested(flags, configChange);
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        if (isImeSuppressedByHardwareKeyboard()) {
            // If there is a hardware keyboard, disable full screen mode.
            return false;
        }
        // Reread resource value here, because this method is called by the framework as needed.
        final boolean isFullscreenModeAllowed = Settings.readUseFullscreenMode(getResources());
        if (super.onEvaluateFullscreenMode() && isFullscreenModeAllowed) {
            // TODO: Remove this hack. Actually we should not really assume NO_EXTRACT_UI
            // implies NO_FULLSCREEN. However, the framework mistakenly does.  i.e. NO_EXTRACT_UI
            // without NO_FULLSCREEN doesn't work as expected. Because of this we need this
            // hack for now.  Let's get rid of this once the framework gets fixed.
            final EditorInfo ei = getCurrentInputEditorInfo();
            return !(ei != null && ((ei.imeOptions & EditorInfo.IME_FLAG_NO_EXTRACT_UI) != 0));
        }
        return false;
    }

    @Override
    public void updateFullscreenMode() {
        super.updateFullscreenMode();
        updateSoftInputWindowLayoutParameters();
    }

    private void updateSoftInputWindowLayoutParameters() {
        // Override layout parameters to expand {@link SoftInputWindow} to the entire screen.
        // See {@link InputMethodService#setinputView(View)} and
        // {@link SoftInputWindow#updateWidthHeight(WindowManager.LayoutParams)}.
        final Window window = getWindow().getWindow();
        ViewLayoutUtils.updateLayoutHeightOf(window, LayoutParams.MATCH_PARENT);
        // This method may be called before {@link #setInputView(View)}.
        if (mInputView != null) {
            // In non-fullscreen mode, {@link InputView} and its parent inputArea should expand to
            // the entire screen and be placed at the bottom of {@link SoftInputWindow}.
            // In fullscreen mode, these shouldn't expand to the entire screen and should be
            // coexistent with {@link #mExtractedArea} above.
            // See {@link InputMethodService#setInputView(View) and
            // com.android.internal.R.layout.input_method.xml.
            final int layoutHeight = isFullscreenMode()
                    ? LayoutParams.WRAP_CONTENT : LayoutParams.MATCH_PARENT;
            final View inputArea = window.findViewById(android.R.id.inputArea);
            ViewLayoutUtils.updateLayoutHeightOf(inputArea, layoutHeight);
            ViewLayoutUtils.updateLayoutGravityOf(inputArea, Gravity.BOTTOM);
            ViewLayoutUtils.updateLayoutHeightOf(mInputView, layoutHeight);
        }
    }

    int getCurrentAutoCapsState() {
        return mInputLogic.getCurrentAutoCapsState(mSettings.getCurrent(),
                mRichImm.getCurrentSubtype().getKeyboardLayoutSet());
    }

    int getCurrentRecapitalizeState() {
        return mInputLogic.getCurrentRecapitalizeState();
    }

    @Override
    public boolean onCustomRequest(final int requestCode) {
        switch (requestCode) {
            case Constants.CUSTOM_CODE_SHOW_INPUT_METHOD_PICKER:
                return showInputMethodPicker();
        }
        return false;
    }

    private boolean showInputMethodPicker() {
        if (isShowingOptionDialog()) {
            return false;
        }
        mOptionsDialog = mRichImm.showSubtypePicker(this,
                mKeyboardSwitcher.getMainKeyboardView().getWindowToken(), this);
        return mOptionsDialog != null;
    }

    public Locale getCurrentLayoutLocale() {
        return mLocale;
    }

    public KeyboardSwitcher getKeyboardSwitcher() {
        return mKeyboardSwitcher;
    }

    public RichInputConnection getRichInputConnection() {
        return mInputLogic.mConnection;
    }

    public void reloadInvisibleTextCache() {
        mInputLogic.reloadTextCache();
        refreshSuggestionCandidates();
        scheduleInvisibleInputFieldCheck();
    }

    public void showDecodedCandidate(final CharSequence decodedText) {
        if (!isInputViewShown()) {
            queuePendingDecodedCandidate(decodedText);
            return;
        }
        clearPendingDecodedCandidate();
        mCandidatePresentationCoordinator.showDecode(decodedText);
        setCandidatesViewShown(false);
    }

    public void showDecodedClipboardPopup(final CharSequence decodedText) {
        if (TextUtils.isEmpty(decodedText)) {
            return;
        }
        queuePendingDecodedCandidate(decodedText);
    }

    public boolean tryDeleteEncodedMessageFast() {
        return mInvisibleMessageController != null
                && mInvisibleMessageController.tryDeleteEncodedMessageAtCursor();
    }

    public void showInvisibleErrorOverlay(final CharSequence message) {
        if (TextUtils.isEmpty(message)) {
            clearCandidateStrip();
            return;
        }
        mCandidatePresentationCoordinator.clear();
        mCandidateStripController.showItems(java.util.Collections.singletonList(
                CandidateItem.createStatusItem(message)));
        setCandidatesViewShown(false);
    }

    public void showInvisibleStatusOverlay(final CharSequence message) {
        if (TextUtils.isEmpty(message)) {
            clearCandidateStrip();
            return;
        }
        mCandidatePresentationCoordinator.clear();
        mCandidateStripController.showItems(java.util.Collections.singletonList(
                CandidateItem.createStatusItem(message)));
        setCandidatesViewShown(false);
    }

    public void clearCandidateStrip() {
        mCandidatePresentationCoordinator.clear();
        mCandidateStripController.clear();
        setCandidatesViewShown(false);
    }

    private void handleInvisibleLockPress() {
        if (mInvisibleLockArmed) {
            cancelInvisibleLockArming(false);
            mInvisibleMessageController.handleQuickLockPress();
            return;
        }
        mInvisibleLockArmed = true;
        mHandler.removeCallbacks(mInvisibleLockArmTimeoutRunnable);
        mHandler.postDelayed(mInvisibleLockArmTimeoutRunnable,
                INVISIBLE_LOCK_ARM_TIMEOUT_MILLIS);
        showInvisibleStatusOverlay(getString(R.string.invisible_status_lock_armed));
    }

    private void cancelInvisibleLockArming(final boolean clearOverlay) {
        if (!mInvisibleLockArmed) {
            return;
        }
        mInvisibleLockArmed = false;
        mHandler.removeCallbacks(mInvisibleLockArmTimeoutRunnable);
        if (clearOverlay) {
            clearCandidateStrip();
        }
    }

    public boolean tryHandleAiRewriteBeforeEditorAction(final int actionId,
            final EditorInfo editorInfo) {
        final SettingsValues settingsValues = mSettings.getCurrent();
        if (!settingsValues.mAiEnabled || !settingsValues.mAiRewriteOnSend
                || !isAiRewriteAction(actionId, editorInfo)) {
            return false;
        }
        startAiRewrite(actionId);
        return true;
    }

    public boolean handleAiRewriteCurrentDraft() {
        final SettingsValues settingsValues = mSettings.getCurrent();
        if (!settingsValues.mAiEnabled) {
            return false;
        }
        startAiRewrite(EditorInfo.IME_ACTION_NONE);
        return true;
    }

    private void applyAiRewriteResult(final int actionId, final String originalText,
            final String rewrittenText) {
        mAiRewriteInProgress = false;
        if (TextUtils.isEmpty(rewrittenText)) {
            showInvisibleErrorOverlay(getString(R.string.ai_error_empty_response));
            return;
        }
        final AiDraftResolver.DraftText currentDraft = mAiDraftResolver.resolve(
                getCurrentInputConnection(), mInputLogic.mConnection);
        if (currentDraft == null || !TextUtils.equals(currentDraft.text, originalText)) {
            showInvisibleErrorOverlay(getString(R.string.ai_error_draft_changed));
            return;
        }
        final boolean replaced = mInputLogic.mConnection.replaceTextRange(
                currentDraft.start, currentDraft.end, rewrittenText);
        if (!replaced) {
            showInvisibleErrorOverlay(getString(R.string.ai_error_request_failed));
            return;
        }
        reloadInvisibleTextCache();
        if (actionId == EditorInfo.IME_ACTION_NONE) {
            showInvisibleStatusOverlay(getString(R.string.ai_status_rewritten));
        } else {
            showInvisibleStatusOverlay(getString(R.string.ai_status_sending));
            mInputLogic.mConnection.performEditorAction(actionId);
        }
    }

    private boolean isAiRewriteAction(final int actionId, final EditorInfo editorInfo) {
        if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE
                || actionId == EditorInfo.IME_ACTION_GO) {
            return true;
        }
        return editorInfo != null && !TextUtils.isEmpty(editorInfo.actionLabel);
    }

    private void startAiRewrite(final int actionId) {
        if (mAiRewriteInProgress) {
            showInvisibleStatusOverlay(getString(R.string.ai_status_rewriting));
            return;
        }
        final SettingsValues settingsValues = mSettings.getCurrent();
        final AiProvider provider = AiProvider.fromId(settingsValues.mAiProvider);
        if (TextUtils.isEmpty(settingsValues.mAiModel)) {
            showInvisibleErrorOverlay(getString(R.string.ai_error_missing_model));
            return;
        }
        if (provider.requiresApiKey && TextUtils.isEmpty(settingsValues.mAiApiKey)) {
            showInvisibleErrorOverlay(getString(R.string.ai_error_missing_api_key));
            return;
        }
        final AiDraftResolver.DraftText draftText = mAiDraftResolver.resolve(
                getCurrentInputConnection(), mInputLogic.mConnection);
        if (draftText == null || TextUtils.isEmpty(draftText.text)
                || TextUtils.isEmpty(draftText.text.trim())) {
            showInvisibleErrorOverlay(getString(R.string.ai_error_missing_draft));
            return;
        }
        mAiRewriteInProgress = true;
        showInvisibleStatusOverlay(getString(R.string.ai_status_rewriting));

        final String baseUrl = settingsValues.mAiBaseUrl;
        final String apiKey = settingsValues.mAiApiKey;
        final String model = settingsValues.mAiModel;
        final String systemPrompt = settingsValues.mAiSystemPrompt;
        final String originalText = draftText.text;

        mAiExecutor.execute(() -> {
            try {
                final String rewrittenText = mAiRewriteClient.rewrite(provider, baseUrl, apiKey,
                        model, systemPrompt, originalText);
                mHandler.post(() -> applyAiRewriteResult(actionId, originalText, rewrittenText));
            } catch (final Exception e) {
                Log.w(TAG, "AI rewrite failed", e);
                mHandler.post(() -> {
                    mAiRewriteInProgress = false;
                    showInvisibleErrorOverlay(getString(R.string.ai_error_request_failed));
                });
            }
        });
    }

    private void showPendingDecodedCandidateIfNeeded() {
        final CharSequence pendingDecodedCandidate = takePendingDecodedCandidate();
        if (TextUtils.isEmpty(pendingDecodedCandidate)) {
            return;
        }
        showDecodedCandidate(pendingDecodedCandidate);
    }

    public void applySuggestionCandidate(final CandidateItem item) {
        if (item == null || item.type != CandidateItemType.SUGGESTION
                || TextUtils.isEmpty(item.actionText)) {
            return;
        }
        final String committedWord = item.actionText.toString();
        mUserWordHistoryStore.recordWord(committedWord);
        final String beforeCursor = mInputLogic.mConnection.getTextBeforeCursorCache();
        final int cachedTextStart = mInputLogic.mConnection.getCachedTextStart();
        if (!TextUtils.isEmpty(beforeCursor) && cachedTextStart >= 0) {
            final int previousWordSearchEnd = Math.max(0, item.replacementStart - cachedTextStart);
            final SuggestionWordUtils.WordContext previousWord = SuggestionWordUtils.getPreviousWord(
                    beforeCursor, previousWordSearchEnd, cachedTextStart);
            if (previousWord != null && !TextUtils.isEmpty(previousWord.word)) {
                mUserWordHistoryStore.recordNextWord(previousWord.word, committedWord);
            }
        }
        final CharSequence suggestionText = buildSuggestionCommitText(item.actionText);
        mCandidatePresentationCoordinator.clear();
        setCandidatesViewShown(false);
        if (mInputLogic.mConnection.replaceTextRange(
                item.replacementStart, item.replacementEnd, suggestionText)) {
            reloadInvisibleTextCache();
        } else {
            clearCandidateStrip();
        }
    }

    private CharSequence buildSuggestionCommitText(final CharSequence suggestionText) {
        if (TextUtils.isEmpty(suggestionText) || !shouldAppendSpaceAfterSuggestion(suggestionText)) {
            return suggestionText;
        }
        return suggestionText + " ";
    }

    private boolean shouldAppendSpaceAfterSuggestion(final CharSequence suggestionText) {
        final String afterCursor = mInputLogic.mConnection.getTextAfterCursorCache();
        if (TextUtils.isEmpty(afterCursor)) {
            return true;
        }
        final char nextChar = afterCursor.charAt(0);
        if (Character.isWhitespace(nextChar)) {
            return false;
        }
        if (Character.isLetterOrDigit(nextChar)) {
            return true;
        }
        if (endsWithPunctuation(suggestionText)) {
            return false;
        }
        return !mSettings.getCurrent().isWordSeparator(nextChar);
    }

    private static boolean endsWithPunctuation(final CharSequence text) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        final char lastChar = text.charAt(text.length() - 1);
        return lastChar == ',' || lastChar == '.' || lastChar == '!' || lastChar == '?'
                || lastChar == ';' || lastChar == ':';
    }

    private void maybeLearnCurrentWordBeforeSeparator(final int codePoint) {
        if (codePoint <= 0 || !mSettings.getCurrent().isWordSeparator(codePoint)
                || mInputLogic.mConnection.hasSelection()) {
            return;
        }
        final String beforeCursor = mInputLogic.mConnection.getTextBeforeCursorCache();
        final int cachedTextStart = mInputLogic.mConnection.getCachedTextStart();
        final SuggestionWordUtils.WordContext wordContext = SuggestionWordUtils.getCurrentWord(
                beforeCursor,
                mInputLogic.mConnection.getTextAfterCursorCache(),
                cachedTextStart);
        if (wordContext != null && !TextUtils.isEmpty(wordContext.word)) {
            mUserWordHistoryStore.recordWord(wordContext.word);
            mUserWordHistoryStore.recordWordWithPunctuation(wordContext.word, codePoint);
            if (!TextUtils.isEmpty(beforeCursor) && cachedTextStart >= 0) {
                final int currentWordStartInBefore = Math.max(0, wordContext.start - cachedTextStart);
                final SuggestionWordUtils.WordContext previousWord =
                        SuggestionWordUtils.getPreviousWord(beforeCursor,
                                currentWordStartInBefore, cachedTextStart);
                if (previousWord != null && !TextUtils.isEmpty(previousWord.word)) {
                    mUserWordHistoryStore.recordNextWord(previousWord.word, wordContext.word);
                }
            }
        }
    }

    public void showDecodedTextDialog(final CharSequence decodedText) {
        if (TextUtils.isEmpty(decodedText)) {
            return;
        }
        startActivity(DecodedPopupActivity.createIntent(this, decodedText));
    }

    public void showEmojiMarkerDialog() {
        dismissEmojiMarkerDialog();
        final SharedPreferences prefs = Settings.getInstance().getSharedPrefs();
        final String currentMarker = prefs.getString(Settings.PREF_INVISIBLE_EMOJI_MARKER, "");
        final String[] emojiOptions = new String[] {
                "\uD83D\uDD12", "\uD83E\uDEE5", "\uD83D\uDC40", "\u2728",
                "\uD83D\uDCE9", "\uD83D\uDD10", "\uD83E\uDD2B", "\uD83E\uDDE0",
                "\uD83D\uDD11", "\uD83E\uDE84", "\uD83C\uDFAD", "\uD83D\uDC7B"
        };

        final int horizontalPadding = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 20, getResources().getDisplayMetrics());
        final int verticalPadding = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 12, getResources().getDisplayMetrics());
        final int cellPadding = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 10, getResources().getDisplayMetrics());

        final LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);

        final TextView currentValueView = new TextView(this);
        currentValueView.setText(TextUtils.isEmpty(currentMarker)
                ? getString(R.string.invisible_emoji_picker_none)
                : currentMarker);
        currentValueView.setGravity(Gravity.CENTER_HORIZONTAL);
        currentValueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        currentValueView.setPadding(0, 0, 0, verticalPadding);
        container.addView(currentValueView, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        final int columns = 4;
        for (int index = 0; index < emojiOptions.length; index += columns) {
            final LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_HORIZONTAL);
            final LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            if (index > 0) {
                rowParams.topMargin = cellPadding;
            }
            for (int column = 0; column < columns && index + column < emojiOptions.length; column++) {
                final String emoji = emojiOptions[index + column];
                final TextView emojiButton = new TextView(this);
                emojiButton.setText(emoji);
                emojiButton.setGravity(Gravity.CENTER);
                emojiButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
                emojiButton.setPadding(cellPadding, cellPadding, cellPadding, cellPadding);
                emojiButton.setClickable(true);
                emojiButton.setFocusable(true);
                final LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(
                        0, LayoutParams.WRAP_CONTENT, 1f);
                if (column > 0) {
                    cellParams.leftMargin = cellPadding / 2;
                }
                emojiButton.setOnClickListener(v -> {
                    prefs.edit().putString(Settings.PREF_INVISIBLE_EMOJI_MARKER, emoji).apply();
                    dismissEmojiMarkerDialog();
                });
                row.addView(emojiButton, cellParams);
            }
            container.addView(row, rowParams);
        }

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.invisible_emoji_picker_title)
                .setView(container)
                .setPositiveButton(R.string.invisible_emoji_picker_clear,
                        (d, which) -> prefs.edit()
                                .putString(Settings.PREF_INVISIBLE_EMOJI_MARKER, "")
                                .apply())
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        attachDialogToInputWindow(dialog);
        mEmojiMarkerDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (mEmojiMarkerDialog == dialog) {
                mEmojiMarkerDialog = null;
            }
        });
        dialog.show();
    }

    private void dismissEmojiMarkerDialog() {
        if (mEmojiMarkerDialog != null) {
            mEmojiMarkerDialog.dismiss();
            mEmojiMarkerDialog = null;
        }
    }

    private void attachDialogToInputWindow(final AlertDialog dialog) {
        final Window window = dialog.getWindow();
        if (window != null && mInputView != null) {
            final WindowManager.LayoutParams params = window.getAttributes();
            params.token = mInputView.getWindowToken();
            window.setAttributes(params);
            window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG);
        }
    }

    private void queuePendingDecodedCandidate(final CharSequence decodedText) {
        if (TextUtils.isEmpty(decodedText)) {
            return;
        }
        mPendingDecodedCandidate = decodedText;
        Settings.getInstance().getSharedPrefs().edit()
                .putString(PREF_PENDING_DECODED_TEXT, decodedText.toString())
                .apply();
    }

    private void clearPendingDecodedCandidate() {
        mPendingDecodedCandidate = null;
        Settings.getInstance().getSharedPrefs().edit()
                .remove(PREF_PENDING_DECODED_TEXT)
                .apply();
    }

    private CharSequence takePendingDecodedCandidate() {
        if (!TextUtils.isEmpty(mPendingDecodedCandidate)) {
            final CharSequence pending = mPendingDecodedCandidate;
            clearPendingDecodedCandidate();
            return pending;
        }
        final String persisted = Settings.getInstance().getSharedPrefs()
                .getString(PREF_PENDING_DECODED_TEXT, null);
        if (!TextUtils.isEmpty(persisted)) {
            clearPendingDecodedCandidate();
            return persisted;
        }
        return null;
    }

    @Override
    public void onMoveCursorPointer(int steps) {
        if (mInputLogic.mConnection.hasCursorPosition()) {
            if (TextUtils.getLayoutDirectionFromLocale(getCurrentLayoutLocale()) == View.LAYOUT_DIRECTION_RTL)
                steps = -steps;

            steps = mInputLogic.mConnection.getUnicodeSteps(steps, true);
            if (steps == 0) {
                return;
            }
            final int end = mInputLogic.mConnection.getExpectedSelectionEnd() + steps;
            final int start = mInputLogic.mConnection.hasSelection() ? mInputLogic.mConnection.getExpectedSelectionStart() : end;
            mInputLogic.mConnection.setSelection(start, end);
            hapticTickFeedback();
        } else {
            for (; steps < 0; steps++)
                mInputLogic.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT);
            for (; steps > 0; steps--)
                mInputLogic.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT);
            hapticTickFeedback();
        }
    }

    @Override
    public void onMoveDeletePointer(int steps) {
        if (mInputLogic.mConnection.hasCursorPosition()) {
            steps = mInputLogic.mConnection.getUnicodeSteps(steps, false);
            if (steps == 0) {
                return;
            }
            final int end = mInputLogic.mConnection.getExpectedSelectionEnd();
            final int start = mInputLogic.mConnection.getExpectedSelectionStart() + steps;
            mInputLogic.mConnection.setSelection(start, end);
            hapticTickFeedback();
        } else {
            for (; steps < 0; steps++)
                mInputLogic.sendDownUpKeyEvent(KeyEvent.KEYCODE_DEL);
            hapticTickFeedback();
        }
    }

    @Override
    public void onUpWithDeletePointerActive() {
        if (mInputLogic.mConnection.hasSelection())
            mInputLogic.mConnection.deleteSelectedText();
    }

    @Override
    public void onUpWithSpacePointerActive() {
        mInputLogic.reloadTextCache();
    }

    private boolean isShowingOptionDialog() {
        return mOptionsDialog != null && mOptionsDialog.isShowing();
    }

    public void switchToNextSubtype() {
        final IBinder token = getWindow().getWindow().getAttributes().token;
        mRichImm.switchToNextInputMethod(token, !shouldSwitchToOtherInputMethods(token));
    }

    // TODO: Instead of checking for alphabetic keyboard here, separate keycodes for
    // alphabetic shift and shift while in symbol layout and get rid of this method.
    private int getCodePointForKeyboard(final int codePoint) {
        if (Constants.CODE_SHIFT == codePoint) {
            final Keyboard currentKeyboard = mKeyboardSwitcher.getKeyboard();
            if (null != currentKeyboard && currentKeyboard.mId.isAlphabetKeyboard()) {
                return codePoint;
            }
            return Constants.CODE_SYMBOL_SHIFT;
        }
        return codePoint;
    }

    // Implementation of {@link KeyboardActionListener}.
    @Override
    public void onCodeInput(final int codePoint, final int x, final int y,
            final boolean isKeyRepeat) {
        maybeLearnCurrentWordBeforeSeparator(codePoint);
        if (codePoint != Constants.CODE_INVISIBLE_LOCK
                && codePoint != Constants.CODE_INVISIBLE_MENU) {
            cancelInvisibleLockArming(false);
        }
        if (codePoint != Constants.CODE_INVISIBLE_MENU) {
            clearCandidateStrip();
        }
        if (codePoint == Constants.CODE_INVISIBLE_LOCK) {
            handleInvisibleLockPress();
            return;
        }
        if (codePoint == Constants.CODE_INVISIBLE_MENU) {
            cancelInvisibleLockArming(false);
            mInvisibleMessageController.showLockMenu();
            return;
        }
        if (codePoint == Constants.CODE_AI_REWRITE) {
            if (!handleAiRewriteCurrentDraft()) {
                showInvisibleErrorOverlay(getString(R.string.ai_error_disabled));
            }
            return;
        }
        final Event event = createSoftwareKeypressEvent(getCodePointForKeyboard(codePoint), isKeyRepeat);
        onEvent(event);
    }

    // This method is public for testability of LatinIME, but also in the future it should
    // completely replace #onCodeInput.
    public void onEvent(final Event event) {
        final InputTransaction completeInputTransaction =
                mInputLogic.onCodeInput(mSettings.getCurrent(), event);
        updateStateAfterInputTransaction(completeInputTransaction);
        mKeyboardSwitcher.onEvent(event, getCurrentAutoCapsState(), getCurrentRecapitalizeState());
    }

    // A helper method to split the code point and the key code. Ultimately, they should not be
    // squashed into the same variable, and this method should be removed.
    // public for testing, as we don't want to copy the same logic into test code
    public static Event createSoftwareKeypressEvent(final int keyCodeOrCodePoint, final boolean isKeyRepeat) {
        final int keyCode;
        final int codePoint;
        if (keyCodeOrCodePoint <= 0) {
            keyCode = keyCodeOrCodePoint;
            codePoint = Event.NOT_A_CODE_POINT;
        } else {
            keyCode = Event.NOT_A_KEY_CODE;
            codePoint = keyCodeOrCodePoint;
        }
        return Event.createSoftwareKeypressEvent(codePoint, keyCode, isKeyRepeat);
    }

    // Called from PointerTracker through the KeyboardActionListener interface
    @Override
    public void onTextInput(final String rawText) {
        cancelInvisibleLockArming(false);
        clearCandidateStrip();
        // TODO: have the keyboard pass the correct key code when we need it.
        final Event event = Event.createSoftwareTextEvent(rawText, Constants.CODE_OUTPUT_TEXT);
        final InputTransaction completeInputTransaction =
                mInputLogic.onTextInput(mSettings.getCurrent(), event);
        updateStateAfterInputTransaction(completeInputTransaction);
        mKeyboardSwitcher.onEvent(event, getCurrentAutoCapsState(), getCurrentRecapitalizeState());
    }

    // Called from PointerTracker through the KeyboardActionListener interface
    @Override
    public void onFinishSlidingInput() {
        // User finished sliding input.
        mKeyboardSwitcher.onFinishSlidingInput(getCurrentAutoCapsState(),
                getCurrentRecapitalizeState());
    }

    private void loadKeyboard() {
        // Since we are switching languages, the most urgent thing is to let the keyboard graphics
        // update. LoadKeyboard does that, but we need to wait for buffer flip for it to be on
        // the screen. Anything we do right now will delay this, so wait until the next frame
        // before we do the rest, like reopening dictionaries and updating suggestions. So we
        // post a message.
        loadSettings();
        if (mKeyboardSwitcher.getMainKeyboardView() != null) {
            // Reload keyboard because the current language has been changed.
            mKeyboardSwitcher.loadKeyboard(getCurrentInputEditorInfo(), mSettings.getCurrent(),
                    getCurrentAutoCapsState(), getCurrentRecapitalizeState());
        }
    }

    /**
     * After an input transaction has been executed, some state must be updated. This includes
     * the shift state of the keyboard and suggestions. This method looks at the finished
     * inputTransaction to find out what is necessary and updates the state accordingly.
     * @param inputTransaction The transaction that has been executed.
     */
    private void updateStateAfterInputTransaction(final InputTransaction inputTransaction) {
        switch (inputTransaction.getRequiredShiftUpdate()) {
        case InputTransaction.SHIFT_UPDATE_LATER:
            mHandler.postUpdateShiftState();
            break;
        case InputTransaction.SHIFT_UPDATE_NOW:
            mKeyboardSwitcher.requestUpdatingShiftState(getCurrentAutoCapsState(),
                    getCurrentRecapitalizeState());
            break;
        default: // SHIFT_NO_UPDATE
        }
        refreshSuggestionCandidates();
        if (isInputViewShown() && mInvisibleMessageController != null) {
            scheduleInvisibleInputFieldCheck();
        }
    }

    private void refreshSuggestionCandidates() {
        final SettingsValues settingsValues = mSettings.getCurrent();
        if (settingsValues == null || settingsValues.mInputAttributes == null) {
            clearCandidateStrip();
            return;
        }
        mCandidatePresentationCoordinator.refreshSuggestions(
                mInputLogic.mConnection, settingsValues.mInputAttributes);
        setCandidatesViewShown(false);
    }

    private void registerClipboardDecodeListener() {
        if (mClipboardManager == null || mClipboardDecodeListenerRegistered) {
            return;
        }
        mClipboardManager.addPrimaryClipChangedListener(mClipboardDecodeListener);
        mClipboardDecodeListenerRegistered = true;
    }

    private void handleClipboardChangedAsync() {
        mHandler.post(() -> {
            if (mInvisibleMessageController != null) {
                mInvisibleMessageController.handleClipboardChanged();
            }
        });
    }

    private void unregisterClipboardDecodeListener() {
        if (mClipboardManager == null || !mClipboardDecodeListenerRegistered) {
            return;
        }
        mClipboardManager.removePrimaryClipChangedListener(mClipboardDecodeListener);
        mClipboardDecodeListenerRegistered = false;
    }

    private void hapticAndAudioFeedback(final int code, final int repeatCount) {
        final MainKeyboardView keyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (keyboardView != null && keyboardView.isInDraggingFinger()) {
            // No need to feedback while finger is dragging.
            return;
        }
        if (repeatCount > 0) {
            if (code == Constants.CODE_DELETE && !mInputLogic.mConnection.canDeleteCharacters()) {
                // No need to feedback when repeat delete key will have no effect.
                return;
            }
            // TODO: Use event time that the last feedback has been generated instead of relying on
            // a repeat count to thin out feedback.
            if (repeatCount % PERIOD_FOR_AUDIO_AND_HAPTIC_FEEDBACK_IN_KEY_REPEAT == 0) {
                return;
            }
        }
        final AudioAndHapticFeedbackManager feedbackManager = AudioAndHapticFeedbackManager.getInstance();
        if (repeatCount == 0) {
            // TODO: Reconsider how to perform haptic feedback when repeating key.
            feedbackManager.performHapticFeedback(keyboardView);
        }
        feedbackManager.performAudioFeedback(code);
    }

    private void hapticTickFeedback() {
        final AudioAndHapticFeedbackManager feedbackManager = AudioAndHapticFeedbackManager.getInstance();
        feedbackManager.performTickFeedback();
    }

    // Callback of the {@link KeyboardActionListener}. This is called when a key is depressed;
    // release matching call is {@link #onReleaseKey(int,boolean)} below.
    @Override
    public void onPressKey(final int primaryCode, final int repeatCount,
            final boolean isSinglePointer) {
        mKeyboardSwitcher.onPressKey(primaryCode, isSinglePointer, getCurrentAutoCapsState(),
                getCurrentRecapitalizeState());
        hapticAndAudioFeedback(primaryCode, repeatCount);
    }

    // Callback of the {@link KeyboardActionListener}. This is called when a key is released;
    // press matching call is {@link #onPressKey(int,int,boolean)} above.
    @Override
    public void onReleaseKey(final int primaryCode, final boolean withSliding) {
        mKeyboardSwitcher.onReleaseKey(primaryCode, withSliding, getCurrentAutoCapsState(),
                getCurrentRecapitalizeState());
    }

    // receive ringer mode change.
    private final BroadcastReceiver mRingerModeChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String action = intent.getAction();
            if (action.equals(AudioManager.RINGER_MODE_CHANGED_ACTION)) {
                AudioAndHapticFeedbackManager.getInstance().onRingerModeChanged();
            }
        }
    };

    public void launchSettings() {
        hideImeImmediately();
        final Intent intent = new Intent();
        intent.setClass(LatinIME.this, SettingsActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && isInputViewShown()) {
            hideImeImmediately();
            return mIsHidingIme || super.onKeyDown(keyCode, event);
        }
        return super.onKeyDown(keyCode, event);
    }

    private void scheduleInvisibleInputFieldCheck() {
        mHandler.removeCallbacks(mAutoDecodeInputFieldRunnable);
        mHandler.postDelayed(mAutoDecodeInputFieldRunnable, 24);
        mHandler.postDelayed(mAutoDecodeInputFieldRunnable, 96);
        mHandler.postDelayed(mAutoDecodeInputFieldRunnable, 220);
    }

    private void hideImeImmediately() {
        if (mIsHidingIme) {
            return;
        }
        mIsHidingIme = true;
        mHandler.removeCallbacks(mAutoDecodeInputFieldRunnable);
        if (isShowingOptionDialog()) {
            mOptionsDialog.dismiss();
            mOptionsDialog = null;
        }
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.closing();
        }
        requestHideSelf(0);
    }

    @Override
    protected void dump(final FileDescriptor fd, final PrintWriter fout, final String[] args) {
        super.dump(fd, fout, args);

        final Printer p = new PrintWriterPrinter(fout);
        p.println("LatinIME state :");
        p.println("  VersionCode = " + ApplicationUtils.getVersionCode(this));
        p.println("  VersionName = " + ApplicationUtils.getVersionName(this));
        final Keyboard keyboard = mKeyboardSwitcher.getKeyboard();
        final int keyboardMode = keyboard != null ? keyboard.mId.mMode : -1;
        p.println("  Keyboard mode = " + keyboardMode);
    }

    public boolean shouldSwitchToOtherInputMethods(final IBinder token) {
        // TODO: Revisit here to reorganize the settings. Probably we can/should use different
        // strategy once the implementation of
        // {@link InputMethodManager#shouldOfferSwitchingToNextInputMethod} is defined well.
        if (!mSettings.getCurrent().mImeSwitchEnabled) {
            return false;
        }
        return mRichImm.shouldOfferSwitchingToOtherInputMethods(token);
    }

    public boolean shouldShowLanguageSwitchKey() {
        if (mSettings.getCurrent().isLanguageSwitchKeyDisabled()) {
            return false;
        }
        if (mRichImm.hasMultipleEnabledSubtypes()) {
            return true;
        }

        final IBinder token = getWindow().getWindow().getAttributes().token;
        if (token == null) {
            return false;
        }
        return shouldSwitchToOtherInputMethods(token);
    }

    private void setNavigationBarColor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            final Window window = getWindow().getWindow();
            if (window == null) {
                return;
            }
            final SharedPreferences prefs = PreferenceManagerCompat.getDeviceSharedPreferences(this);
            final int keyboardColor = Settings.readKeyboardColor(prefs, this);
            window.setNavigationBarColor(keyboardColor);
            window.setNavigationBarContrastEnforced(false);
            final int flag = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            if (ResourceUtils.isBrightColor(keyboardColor)) {
                window.getInsetsController().setSystemBarsAppearance(flag, flag);
            } else {
                window.getInsetsController().setSystemBarsAppearance(0, flag);
            }
        }
    }
}
