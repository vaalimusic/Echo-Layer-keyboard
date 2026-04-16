package rkr.simplekeyboard.inputmethod.latin.ai;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class AiRewriteClient {
    public static final class Config {
        public final AiProvider provider;
        public final String baseUrl;
        public final String apiKey;
        public final String model;
        public final String systemPrompt;

        public Config(final AiProvider provider, final String baseUrl, final String apiKey,
                final String model, final String systemPrompt) {
            this.provider = provider;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.model = model;
            this.systemPrompt = systemPrompt;
        }
    }

    public String rewrite(final Config config, final String sourceText) throws IOException, JSONException {
        final String endpoint = normalizeEndpoint(config.provider, config.baseUrl);
        switch (config.provider) {
            case OPENAI:
            case OPENROUTER:
                return executeOpenAiCompatible(endpoint, config, sourceText);
            case OLLAMA:
                return executeOllama(endpoint, config, sourceText);
            case YANDEX:
                return executeYandex(endpoint, config, sourceText);
            default:
                throw new IOException("Unsupported AI provider");
        }
    }

    public String rewrite(final AiProvider provider, final String baseUrl, final String apiKey,
            final String model, final String systemPrompt, final String sourceText)
            throws IOException, JSONException {
        return rewrite(new Config(provider, baseUrl, apiKey, model, systemPrompt), sourceText);
    }

    private String normalizeEndpoint(final AiProvider provider, final String configuredBaseUrl) {
        String endpoint = TextUtils.isEmpty(configuredBaseUrl)
                ? provider.defaultUrl : configuredBaseUrl.trim();
        if (!endpoint.contains("://")) {
            endpoint = "http://" + endpoint;
        }
        switch (provider) {
            case OLLAMA:
                endpoint = appendPathIfMissing(endpoint, "/api/chat");
                break;
            case OPENAI:
            case OPENROUTER:
                endpoint = appendPathIfMissing(endpoint, "/chat/completions");
                break;
            case YANDEX:
                endpoint = appendPathIfMissing(endpoint, "/foundationModels/v1/completion");
                break;
            default:
                break;
        }
        return endpoint;
    }

    private String appendPathIfMissing(final String endpoint, final String requiredPath) {
        if (endpoint.endsWith(requiredPath)) {
            return endpoint;
        }
        if (endpoint.endsWith("/")) {
            final String trimmedPath = requiredPath.startsWith("/")
                    ? requiredPath.substring(1) : requiredPath;
            if (endpoint.endsWith(trimmedPath)) {
                return endpoint;
            }
            return endpoint + trimmedPath;
        }
        if (endpoint.contains("/api/chat") || endpoint.contains("/chat/completions")
                || endpoint.contains("/foundationModels/v1/completion")) {
            return endpoint;
        }
        return endpoint + requiredPath;
    }

    private String executeOpenAiCompatible(final String endpoint, final Config config,
            final String sourceText) throws IOException, JSONException {
        final JSONObject body = new JSONObject();
        body.put("model", config.model);
        body.put("temperature", 0.4);
        body.put("messages", buildOpenAiMessages(config.systemPrompt, sourceText));
        final JSONObject response = executeJsonRequest(endpoint, body, "Bearer " + config.apiKey, config.provider);
        final JSONArray choices = response.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            return null;
        }
        final JSONObject message = choices.getJSONObject(0).optJSONObject("message");
        return message == null ? null : message.optString("content", null);
    }

    private String executeOllama(final String endpoint, final Config config,
            final String sourceText) throws IOException, JSONException {
        final JSONObject body = new JSONObject();
        body.put("model", config.model);
        body.put("stream", false);
        body.put("messages", buildOpenAiMessages(config.systemPrompt, sourceText));
        final JSONObject response = executeJsonRequest(endpoint, body, null, config.provider);
        final JSONObject message = response.optJSONObject("message");
        return message == null ? null : message.optString("content", null);
    }

    private String executeYandex(final String endpoint, final Config config,
            final String sourceText) throws IOException, JSONException {
        final JSONObject body = new JSONObject();
        body.put("modelUri", config.model);
        final JSONObject completionOptions = new JSONObject();
        completionOptions.put("stream", false);
        completionOptions.put("temperature", 0.4);
        completionOptions.put("maxTokens", "2000");
        body.put("completionOptions", completionOptions);
        final JSONArray messages = new JSONArray();
        final String finalSystemPrompt = buildSystemPrompt(config.systemPrompt);
        if (!TextUtils.isEmpty(finalSystemPrompt)) {
            final JSONObject system = new JSONObject();
            system.put("role", "system");
            system.put("text", finalSystemPrompt);
            messages.put(system);
        }
        final JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("text", sourceText);
        messages.put(user);
        body.put("messages", messages);
        final JSONObject response = executeJsonRequest(endpoint, body, "Api-Key " + config.apiKey, config.provider);
        final JSONObject result = response.optJSONObject("result");
        if (result == null) {
            return null;
        }
        final JSONArray alternatives = result.optJSONArray("alternatives");
        if (alternatives == null || alternatives.length() == 0) {
            return null;
        }
        final JSONObject message = alternatives.getJSONObject(0).optJSONObject("message");
        return message == null ? null : message.optString("text", null);
    }

    private JSONArray buildOpenAiMessages(final String systemPrompt,
            final String sourceText) throws JSONException {
        final JSONArray messages = new JSONArray();
        final String finalSystemPrompt = buildSystemPrompt(systemPrompt);
        if (!TextUtils.isEmpty(finalSystemPrompt)) {
            final JSONObject system = new JSONObject();
            system.put("role", "system");
            system.put("content", finalSystemPrompt);
            messages.put(system);
        }
        final JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", sourceText);
        messages.put(user);
        return messages;
    }

    private JSONObject executeJsonRequest(final String endpoint, final JSONObject body,
            final String authorizationHeader, final AiProvider provider)
            throws IOException, JSONException {
        final HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        if (!TextUtils.isEmpty(authorizationHeader)) {
            connection.setRequestProperty("Authorization", authorizationHeader);
        }
        if (provider == AiProvider.OPENROUTER) {
            connection.setRequestProperty("HTTP-Referer", "https://echo-layer.local");
            connection.setRequestProperty("X-Title", "Echo Layer");
        }
        try (BufferedOutputStream output = new BufferedOutputStream(connection.getOutputStream())) {
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
        final int code = connection.getResponseCode();
        final boolean success = code >= 200 && code < 300;
        final String payload = readAll(success
                ? new BufferedInputStream(connection.getInputStream())
                : new BufferedInputStream(connection.getErrorStream()));
        connection.disconnect();
        if (!success) {
            throw new IOException("HTTP " + code + ": " + payload);
        }
        return new JSONObject(payload);
    }

    private String buildSystemPrompt(final String userPrompt) {
        final String basePrompt = "You rewrite the user's draft before sending it. "
                + "Return only the final rewritten message text with no explanations, no quotes, "
                + "and no markdown. Preserve meaning, links, names, numbers, and language unless "
                + "the style instruction explicitly changes tone.";
        if (TextUtils.isEmpty(userPrompt)) {
            return basePrompt;
        }
        return basePrompt + " Style instruction: " + userPrompt.trim();
    }

    private static String readAll(final BufferedInputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        try (BufferedInputStream input = inputStream;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
