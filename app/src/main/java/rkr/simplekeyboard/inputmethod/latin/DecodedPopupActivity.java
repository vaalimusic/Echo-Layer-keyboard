package rkr.simplekeyboard.inputmethod.latin;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

import rkr.simplekeyboard.inputmethod.R;

public final class DecodedPopupActivity extends Activity {
    private static final String EXTRA_DECODED_TEXT = "decoded_text";
    private EditText mEditText;

    public static Intent createIntent(final Context context, final CharSequence decodedText) {
        final Intent intent = new Intent(context, DecodedPopupActivity.class);
        intent.putExtra(EXTRA_DECODED_TEXT, decodedText);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_NO_HISTORY);
        return intent;
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final CharSequence decodedText = getIntent().getCharSequenceExtra(EXTRA_DECODED_TEXT);
        if (TextUtils.isEmpty(decodedText)) {
            finish();
            return;
        }

        final int horizontalPadding = (int) (24 * getResources().getDisplayMetrics().density);
        final int verticalPadding = (int) (18 * getResources().getDisplayMetrics().density);
        final int spacing = (int) (12 * getResources().getDisplayMetrics().density);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);

        mEditText = new EditText(this);
        mEditText.setText(decodedText);
        mEditText.setTextIsSelectable(true);
        mEditText.setMinLines(4);
        mEditText.setMaxLines(12);
        mEditText.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        root.addView(mEditText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.END);
        final LinearLayout.LayoutParams buttonRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonRowParams.topMargin = spacing;

        final Button closeButton = new Button(this);
        closeButton.setText(android.R.string.cancel);
        closeButton.setOnClickListener(v -> finish());
        buttonRow.addView(closeButton);

        final Button copyButton = new Button(this);
        copyButton.setText(R.string.invisible_decoded_dialog_copy);
        copyButton.setOnClickListener(v -> {
            final ClipboardManager clipboard = (ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText(
                        "Echo Layer decoded text", mEditText.getText()));
            }
        });
        final LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        copyParams.leftMargin = spacing;
        buttonRow.addView(copyButton, copyParams);

        root.addView(buttonRow, buttonRowParams);
        setTitle(R.string.invisible_decoded_dialog_title);
        setContentView(root);
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        final CharSequence decodedText = intent.getCharSequenceExtra(EXTRA_DECODED_TEXT);
        if (mEditText != null && !TextUtils.isEmpty(decodedText)) {
            mEditText.setText(decodedText);
        }
    }
}
