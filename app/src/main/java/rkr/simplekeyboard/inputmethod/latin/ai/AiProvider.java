package rkr.simplekeyboard.inputmethod.latin.ai;

public enum AiProvider {
    OPENAI("openai", "https://api.openai.com/v1/chat/completions", true),
    OPENROUTER("openrouter", "https://openrouter.ai/api/v1/chat/completions", true),
    OLLAMA("ollama", "http://127.0.0.1:11434/api/chat", false),
    YANDEX("yandex", "https://llm.api.cloud.yandex.net/foundationModels/v1/completion", true);

    public final String preferenceValue;
    public final String defaultUrl;
    public final boolean requiresApiKey;

    AiProvider(final String preferenceValue, final String defaultUrl,
            final boolean requiresApiKey) {
        this.preferenceValue = preferenceValue;
        this.defaultUrl = defaultUrl;
        this.requiresApiKey = requiresApiKey;
    }

    public static AiProvider fromPreferenceValue(final String value) {
        for (final AiProvider provider : values()) {
            if (provider.preferenceValue.equals(value)) {
                return provider;
            }
        }
        return OPENAI;
    }

    public static AiProvider fromId(final String value) {
        return fromPreferenceValue(value);
    }
}
