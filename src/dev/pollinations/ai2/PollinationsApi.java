package dev.pollinations.ai2;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * The Pollinations calls, in plain Java.
 *
 * <p>It uses {@link HttpURLConnection} rather than a newer client because this
 * runs on Android inside App Inventor and Kodular, and because keeping it free
 * of Android and App Inventor types means it can be tested on a desktop JVM
 * against a local stub server.
 */
public class PollinationsApi {

    public static final String DEFAULT_BASE_URL = "https://gen.pollinations.ai";
    public static final String ENTER_URL = "https://enter.pollinations.ai";

    public static final String DEFAULT_TEXT_MODEL = "openai/gpt-6-sol";
    public static final String DEFAULT_IMAGE_MODEL = "black-forest-labs/flux.2-klein-4b";

    /** The image endpoint refuses anything under 256 px per side. */
    public static final int MIN_IMAGE_SIZE = 256;

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 120000;

    private String baseUrl;
    private String enterUrl;
    private String apiKey;

    public PollinationsApi(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL, ENTER_URL);
    }

    public PollinationsApi(String apiKey, String baseUrl, String enterUrl) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = trimSlash(baseUrl);
        this.enterUrl = trimSlash(enterUrl);
    }

    /** A failure worth showing to the user, never a stack trace. */
    public static class PollinationsException extends Exception {
        public PollinationsException(String message) {
            super(message);
        }
    }

    /** One panel of a device-flow sign-in. */
    public static class DeviceCode {
        public final String deviceCode;
        public final String userCode;
        public final String verificationUrl;
        public final int intervalSeconds;

        DeviceCode(String deviceCode, String userCode, String verificationUrl, int intervalSeconds) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.verificationUrl = verificationUrl;
            this.intervalSeconds = intervalSeconds;
        }
    }

    public void setApiKey(String key) {
        this.apiKey = key == null ? "" : key.trim();
    }

    public String getApiKey() {
        return apiKey;
    }

    // ---------------------------------------------------------------- text

    /** Asks a text model and returns its answer. */
    public String ask(String systemPrompt, String userPrompt, String model) throws PollinationsException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userPrompt));
        body.add("messages", messages);

        JsonObject answer = postJson("/v1/chat/completions", body.toString());
        JsonArray choices = answer.getAsJsonArray("choices");
        if (choices == null || choices.size() == 0) {
            throw new PollinationsException("The model returned no answer");
        }
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        if (message == null || !message.has("content")) {
            throw new PollinationsException("The model returned no answer");
        }
        return message.get("content").getAsString().trim();
    }

    // --------------------------------------------------------------- image

    /** Renders a prompt and returns a shareable image URL. */
    public String imageUrl(String prompt, String model, int size) throws PollinationsException {
        JsonObject body = new JsonObject();
        body.addProperty("prompt", prompt);
        body.addProperty("model", model);
        body.addProperty("size", clampSide(size) + "x" + clampSide(size));
        body.addProperty("response_format", "url");

        JsonObject answer = postJson("/v1/images/generations", body.toString());
        JsonArray data = answer.getAsJsonArray("data");
        if (data == null || data.size() == 0) {
            throw new PollinationsException("The model returned no image");
        }
        JsonObject first = data.get(0).getAsJsonObject();
        if (first.has("url")) {
            return first.get("url").getAsString();
        }
        throw new PollinationsException("The model returned no image URL");
    }

    /** Renders a prompt and writes the image next to the given file. */
    public String downloadImage(String prompt, String model, int size, File target, String apiKeyOverride)
            throws PollinationsException {
        int side = clampSide(size);
        String path = "/image/" + encode(prompt)
                + "?model=" + encode(model)
                + "&width=" + side
                + "&height=" + side
                + "&nologo=true";
        byte[] bytes = getBytes(path, apiKeyOverride);
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("cannot create " + parent);
            }
            OutputStream out = new java.io.FileOutputStream(target);
            try {
                out.write(bytes);
            } finally {
                out.close();
            }
        } catch (IOException error) {
            throw new PollinationsException("Could not save the image: " + error.getMessage());
        }
        return target.getAbsolutePath();
    }

    // --------------------------------------------------------------- audio

    /** Transcribes a recording with the speech-to-text endpoint. */
    public String transcribe(File audio, String model) throws PollinationsException {
        String boundary = "----pollinations" + System.currentTimeMillis();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try {
            writeField(body, boundary, "model", model);
            writeFile(body, boundary, "file", audio);
            body.write(("--" + boundary + "--\r\n").getBytes(UTF8));
        } catch (IOException error) {
            throw new PollinationsException("Could not read the recording: " + error.getMessage());
        }

        HttpURLConnection connection = null;
        try {
            connection = open("/v1/audio/transcriptions", "POST", apiKey);
            connection.setRequestProperty("content-type", "multipart/form-data; boundary=" + boundary);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.size());
            OutputStream out = connection.getOutputStream();
            try {
                out.write(body.toByteArray());
            } finally {
                out.close();
            }
            String response = readBody(connection);
            if (connection.getResponseCode() / 100 != 2) {
                throw new PollinationsException(describeError(connection.getResponseCode(), response));
            }
            JsonObject parsed = new JsonParser().parse(response).getAsJsonObject();
            if (parsed.has("text")) {
                return parsed.get("text").getAsString().trim();
            }
            return response.trim();
        } catch (IOException error) {
            throw new PollinationsException("Transcription failed: " + error.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ------------------------------------------------------------- vision

    /** Asks a text model about a local image, which the Web component cannot do. */
    public String askAboutImage(File image, String question, String model) throws PollinationsException {
        String dataUri;
        try {
            dataUri = "data:" + guessMime(image) + ";base64," + toBase64(readAll(image));
        } catch (IOException error) {
            throw new PollinationsException("Could not read the image: " + error.getMessage());
        }

        JsonObject imagePart = new JsonObject();
        imagePart.addProperty("type", "image_url");
        JsonObject url = new JsonObject();
        url.addProperty("url", dataUri);
        imagePart.add("image_url", url);

        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", question);

        JsonArray content = new JsonArray();
        content.add(textPart);
        content.add(imagePart);

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.add("content", content);

        JsonArray messages = new JsonArray();
        messages.add(message);

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);

        JsonObject answer = postJson("/v1/chat/completions", body.toString());
        JsonArray choices = answer.getAsJsonArray("choices");
        if (choices == null || choices.size() == 0) {
            throw new PollinationsException("The model returned no answer");
        }
        return choices.get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString().trim();
    }

    // ------------------------------------------------------- device flow

    /** Starts a device-flow sign-in: no API key ships inside the app. */
    public DeviceCode requestDeviceCode() throws PollinationsException {
        JsonObject body = new JsonObject();
        body.addProperty("scope", "profile usage");
        JsonObject answer = postJsonTo(enterUrl + "/api/device/code", body.toString());
        return new DeviceCode(
                answer.get("device_code").getAsString(),
                answer.get("user_code").getAsString(),
                answer.has("verification_uri_complete")
                        ? answer.get("verification_uri_complete").getAsString()
                        : answer.get("verification_uri").getAsString(),
                answer.has("interval") ? answer.get("interval").getAsInt() : 5);
    }

    /**
     * Polls until the user approves.
     *
     * @return the minted API key, or null while the approval is still pending
     */
    public String pollDeviceCode(DeviceCode code) throws PollinationsException {
        JsonObject body = new JsonObject();
        body.addProperty("device_code", code.deviceCode);
        HttpURLConnection connection = null;
        try {
            connection = openAbsolute(enterUrl + "/api/device/token", "POST", "");
            connection.setRequestProperty("content-type", "application/json");
            connection.setDoOutput(true);
            OutputStream out = connection.getOutputStream();
            try {
                out.write(body.toString().getBytes(UTF8));
            } finally {
                out.close();
            }
            String response = readBody(connection);
            JsonObject parsed = new JsonParser().parse(response).getAsJsonObject();
            if (parsed.has("access_token")) {
                return parsed.get("access_token").getAsString();
            }
            String error = parsed.has("error") ? parsed.get("error").getAsString() : "";
            if ("authorization_pending".equals(error) || error.isEmpty()) {
                return null;
            }
            throw new PollinationsException(
                    parsed.has("error_description") ? parsed.get("error_description").getAsString() : error);
        } catch (IOException error) {
            throw new PollinationsException("Sign-in failed: " + error.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // -------------------------------------------------------------- models

    /** Lists the model ids of one kind, so nobody has to guess one. */
    public List<String> models(boolean images) throws PollinationsException {
        String path = images ? "/image/models" : "/text/models";
        HttpURLConnection connection = null;
        try {
            connection = open(path, "GET", "");
            String response = readBody(connection);
            if (connection.getResponseCode() / 100 != 2) {
                throw new PollinationsException(describeError(connection.getResponseCode(), response));
            }
            JsonArray array = new JsonParser().parse(response).getAsJsonArray();
            List<String> names = new ArrayList<String>();
            for (JsonElement entry : array) {
                if (entry.isJsonObject() && entry.getAsJsonObject().has("name")) {
                    names.add(entry.getAsJsonObject().get("name").getAsString());
                }
            }
            return names;
        } catch (IOException error) {
            throw new PollinationsException("Could not read the model list: " + error.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ------------------------------------------------------------ helpers

    private static JsonObject message(String role, String content) {
        JsonObject entry = new JsonObject();
        entry.addProperty("role", role);
        entry.addProperty("content", content);
        return entry;
    }

    private JsonObject postJson(String path, String body) throws PollinationsException {
        return postJsonTo(baseUrl + path, body);
    }

    private JsonObject postJsonTo(String url, String body) throws PollinationsException {
        HttpURLConnection connection = null;
        try {
            connection = openAbsolute(url, "POST", apiKey);
            connection.setRequestProperty("content-type", "application/json");
            connection.setDoOutput(true);
            OutputStream out = connection.getOutputStream();
            try {
                out.write(body.getBytes(UTF8));
            } finally {
                out.close();
            }
            String response = readBody(connection);
            if (connection.getResponseCode() / 100 != 2) {
                throw new PollinationsException(describeError(connection.getResponseCode(), response));
            }
            return new JsonParser().parse(response).getAsJsonObject();
        } catch (IOException error) {
            throw new PollinationsException("Could not reach Pollinations: " + error.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private byte[] getBytes(String path, String apiKeyOverride) throws PollinationsException {
        HttpURLConnection connection = null;
        try {
            String key = apiKeyOverride == null || apiKeyOverride.trim().isEmpty() ? apiKey : apiKeyOverride.trim();
            connection = open(path, "GET", key);
            if (connection.getResponseCode() / 100 != 2) {
                String response = readBody(connection);
                throw new PollinationsException(describeError(connection.getResponseCode(), response));
            }
            return readAll(connection.getInputStream());
        } catch (IOException error) {
            throw new PollinationsException("Could not fetch the image: " + error.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private HttpURLConnection open(String path, String method, String key) throws IOException {
        return openAbsolute(baseUrl + path, method, key);
    }

    private HttpURLConnection openAbsolute(String url, String method, String key) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("accept", "*/*");
        if (key != null && !key.isEmpty()) {
            connection.setRequestProperty("authorization", "Bearer " + key);
        }
        return connection;
    }

    private static String readBody(HttpURLConnection connection) throws IOException {
        InputStream stream = connection.getResponseCode() / 100 == 2
                ? connection.getInputStream()
                : connection.getErrorStream();
        if (stream == null) {
            return "";
        }
        return new String(readAll(stream), UTF8);
    }

    /**
     * Turns an error response into one line. The API reports limits and missing
     * models inside JSON, and an app should show that instead of a stack trace.
     */
    static String describeError(int status, String body) {
        try {
            JsonObject parsed = new JsonParser().parse(body).getAsJsonObject();
            if (parsed.has("error")) {
                JsonElement error = parsed.get("error");
                if (error.isJsonObject() && error.getAsJsonObject().has("message")) {
                    return error.getAsJsonObject().get("message").getAsString();
                }
                if (error.isJsonPrimitive()) {
                    return error.getAsString();
                }
            }
        } catch (RuntimeException ignored) {
            // Not JSON: fall through to the status code.
        }
        return "Pollinations answered HTTP " + status;
    }

    private static void writeField(ByteArrayOutputStream body, String boundary, String name, String value)
            throws IOException {
        body.write(("--" + boundary + "\r\n").getBytes(UTF8));
        body.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(UTF8));
        body.write((value + "\r\n").getBytes(UTF8));
    }

    private static void writeFile(ByteArrayOutputStream body, String boundary, String name, File file)
            throws IOException {
        body.write(("--" + boundary + "\r\n").getBytes(UTF8));
        body.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\""
                + file.getName() + "\"\r\n").getBytes(UTF8));
        body.write(("Content-Type: " + guessMime(file) + "\r\n\r\n").getBytes(UTF8));
        body.write(readAll(file));
        body.write("\r\n".getBytes(UTF8));
    }

    static int clampSide(int size) {
        return Math.max(MIN_IMAGE_SIZE, Math.min(size, 2048));
    }

    static String guessMime(File file) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".m4a")) {
            return "audio/mp4";
        }
        if (name.endsWith(".ogg")) {
            return "audio/ogg";
        }
        if (name.endsWith(".wav")) {
            return "audio/wav";
        }
        return "application/octet-stream";
    }

    private static byte[] readAll(File file) throws IOException {
        return readAll(new FileInputStream(file));
    }

    private static byte[] readAll(InputStream stream) throws IOException {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } finally {
            stream.close();
        }
    }

    /**
     * Base64 without java.util.Base64, which only exists from Android API 26,
     * and without android.util.Base64, which would tie this class to Android.
     */
    static String toBase64(byte[] data) {
        final char[] alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        StringBuilder out = new StringBuilder((data.length + 2) / 3 * 4);
        for (int i = 0; i < data.length; i += 3) {
            int remaining = data.length - i;
            int chunk = (data[i] & 0xFF) << 16;
            if (remaining > 1) {
                chunk |= (data[i + 1] & 0xFF) << 8;
            }
            if (remaining > 2) {
                chunk |= data[i + 2] & 0xFF;
            }
            out.append(alphabet[(chunk >> 18) & 0x3F]);
            out.append(alphabet[(chunk >> 12) & 0x3F]);
            out.append(remaining > 1 ? alphabet[(chunk >> 6) & 0x3F] : '=');
            out.append(remaining > 2 ? alphabet[chunk & 0x3F] : '=');
        }
        return out.toString();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (IOException error) {
            return value;
        }
    }

    private static String trimSlash(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
