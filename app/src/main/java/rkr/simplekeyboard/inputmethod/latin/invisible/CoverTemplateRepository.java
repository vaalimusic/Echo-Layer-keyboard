package rkr.simplekeyboard.inputmethod.latin.invisible;

import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class CoverTemplateRepository {
    public static final String PAYLOAD_MARKER = "{payload}";
    private static final String SEPARATOR = "\n";
    private static final List<String> DEFAULT_TEMPLATES = Collections.unmodifiableList(Arrays.asList(
            "Привет, как дела? " + PAYLOAD_MARKER,
            "Все нормально, позже напишу. " + PAYLOAD_MARKER,
            "Сегодня погода отличная. " + PAYLOAD_MARKER,
            "Я сейчас занят, отвечу потом. " + PAYLOAD_MARKER,
            "Скинь, пожалуйста, детали позже. " + PAYLOAD_MARKER
    ));

    private final SharedPreferences mPrefs;
    private final String mTemplatesKey;
    private final String mDefaultTemplateKey;

    public CoverTemplateRepository(final SharedPreferences prefs, final String templatesKey,
            final String defaultTemplateKey) {
        mPrefs = prefs;
        mTemplatesKey = templatesKey;
        mDefaultTemplateKey = defaultTemplateKey;
    }

    public List<String> getTemplates() {
        final String raw = mPrefs.getString(mTemplatesKey, "");
        if (TextUtils.isEmpty(raw)) {
            return DEFAULT_TEMPLATES;
        }
        final String[] parts = raw.split(SEPARATOR);
        final ArrayList<String> result = new ArrayList<>();
        for (final String part : parts) {
            final String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result.isEmpty() ? DEFAULT_TEMPLATES : result;
    }

    public void saveTemplates(final String rawTemplates) {
        mPrefs.edit().putString(mTemplatesKey, rawTemplates == null ? "" : rawTemplates.trim()).apply();
    }

    public String getDefaultTemplate() {
        final String template = mPrefs.getString(mDefaultTemplateKey, "");
        if (TextUtils.isEmpty(template)) {
            return DEFAULT_TEMPLATES.get(0);
        }
        return template;
    }

    public void saveDefaultTemplate(final String template) {
        mPrefs.edit().putString(mDefaultTemplateKey, template == null ? "" : template.trim()).apply();
    }

    public static boolean isValidTemplate(final String template) {
        return !TextUtils.isEmpty(template) && template.contains(PAYLOAD_MARKER);
    }

    public static String getDefaultTemplatesText() {
        return TextUtils.join(SEPARATOR, DEFAULT_TEMPLATES);
    }
}
