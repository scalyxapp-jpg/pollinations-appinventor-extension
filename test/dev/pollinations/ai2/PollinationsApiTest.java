package dev.pollinations.ai2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Talks to a local stub instead of Pollinations, so the request building, the
 * parsing and the error handling are all checked without a device, an account or
 * a network. The extension's block layer is a thin wrapper over what this covers.
 */
class PollinationsApiTest {

    private HttpServer server;
    private String baseUrl;
    private final List<Recorded> requests = new ArrayList<Recorded>();

    static class Recorded {
        String path;
        String method;
        String query;
        String authorization;
        String contentType;
        String body;
    }

    private void route(final String path, final int status, final String type, final String response) {
        server.createContext(path, new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                Recorded recorded = new Recorded();
                recorded.path = exchange.getRequestURI().getPath();
                recorded.method = exchange.getRequestMethod();
                recorded.query = exchange.getRequestURI().getQuery();
                recorded.authorization = exchange.getRequestHeaders().getFirst("authorization");
                recorded.contentType = exchange.getRequestHeaders().getFirst("content-type");
                recorded.body = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
                requests.add(recorded);

                byte[] payload = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("content-type", type);
                exchange.sendResponseHeaders(status, payload.length);
                OutputStream out = exchange.getResponseBody();
                out.write(payload);
                out.close();
            }
        });
    }

    @BeforeEach
    void start() throws IOException {
        requests.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private PollinationsApi api(String key) {
        return new PollinationsApi(key, baseUrl, baseUrl);
    }

    private Recorded last() {
        assertTrue(requests.size() > 0, "the stub received no request");
        return requests.get(requests.size() - 1);
    }

    @Test
    void askSendsTheKeyAndParsesTheAnswer() throws Exception {
        route("/v1/chat/completions", 200, "application/json",
                "{\"choices\":[{\"message\":{\"content\":\"  pong  \"}}]}");

        String answer = api("sk_test").ask("be brief", "say pong", "openai/gpt-6-sol");

        assertEquals("pong", answer, "the answer is trimmed");
        assertEquals("Bearer sk_test", last().authorization);
        assertTrue(last().body.contains("\"role\":\"system\""));
        assertTrue(last().body.contains("openai/gpt-6-sol"));
    }

    @Test
    void askSurfacesTheApiMessageInsteadOfAStackTrace() {
        route("/v1/chat/completions", 401, "application/json",
                "{\"success\":false,\"error\":{\"message\":\"A valid API key is required.\"}}");

        PollinationsApi.PollinationsException error = assertThrows(
                PollinationsApi.PollinationsException.class,
                () -> api("").ask("s", "u", "m"));
        assertEquals("A valid API key is required.", error.getMessage());
    }

    @Test
    void askReportsAnEmptyAnswer() {
        route("/v1/chat/completions", 200, "application/json", "{\"choices\":[]}");

        assertThrows(PollinationsApi.PollinationsException.class, () -> api("k").ask("s", "u", "m"));
    }

    @Test
    void imageUrlParsesTheStoredUrlAndAsksForAtLeast256Pixels() throws Exception {
        route("/v1/images/generations", 200, "application/json",
                "{\"data\":[{\"url\":\"https://media.pollinations.ai/abc\"}]}");

        String url = api("sk_test").imageUrl("a red cube", "black-forest-labs/flux.2-klein-4b", 128);

        assertEquals("https://media.pollinations.ai/abc", url);
        assertTrue(last().body.contains("\"size\":\"256x256\""),
                "the API refuses anything under 256 px, so 128 has to be raised");
    }

    @Test
    void downloadImageWritesTheBytesAndCarriesTheQuery() throws Exception {
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        server.createContext("/image/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                Recorded recorded = new Recorded();
                recorded.path = exchange.getRequestURI().getPath();
                recorded.query = exchange.getRequestURI().getQuery();
                recorded.authorization = exchange.getRequestHeaders().getFirst("authorization");
                requests.add(recorded);
                exchange.getResponseHeaders().add("content-type", "image/png");
                exchange.sendResponseHeaders(200, png.length);
                OutputStream out = exchange.getResponseBody();
                out.write(png);
                out.close();
            }
        });

        File target = new File(Files.createTempDirectory("pollinations").toFile(), "comic.png");
        String path = api("sk_test").downloadImage("a red cube", "flux.2-klein-4b", 512, target, null);

        assertEquals(target.getAbsolutePath(), path);
        assertTrue(target.exists(), "the image has to land on disk");
        assertEquals(png.length, target.length());
        assertTrue(last().query.contains("width=512"));
        assertTrue(last().query.contains("nologo=true"));
        assertTrue(last().path.startsWith("/image/"));
        assertEquals("Bearer sk_test", last().authorization);
    }

    @Test
    void downloadImageRejectsASmallSizeByClampingIt() throws Exception {
        server.createContext("/image/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                Recorded recorded = new Recorded();
                recorded.query = exchange.getRequestURI().getQuery();
                requests.add(recorded);
                exchange.sendResponseHeaders(200, 2);
                OutputStream out = exchange.getResponseBody();
                out.write(new byte[] {1, 2});
                out.close();
            }
        });

        File target = new File(Files.createTempDirectory("pollinations").toFile(), "small.png");
        api("k").downloadImage("x", "m", 8, target, "sk_override");

        assertTrue(last().query.contains("width=256"), "8 px would be refused by the API");
    }

    @Test
    void transcribeUploadsTheRecordingAsMultipart() throws Exception {
        route("/v1/audio/transcriptions", 200, "application/json", "{\"text\":\"hello world\"}");

        File audio = File.createTempFile("recording", ".m4a");
        Files.write(audio.toPath(), new byte[] {1, 2, 3, 4});

        String text = api("sk_test").transcribe(audio, "openai/whisper-1");

        assertEquals("hello world", text);
        assertTrue(last().contentType.startsWith("multipart/form-data; boundary="));
        assertTrue(last().body.contains("name=\"file\""));
        assertTrue(last().body.contains("name=\"model\""));
        assertTrue(last().body.contains("openai/whisper-1"));
        assertTrue(last().body.contains("audio/mp4"), "the part carries the recording's type");
    }

    @Test
    void askAboutImageSendsADataUri() throws Exception {
        route("/v1/chat/completions", 200, "application/json",
                "{\"choices\":[{\"message\":{\"content\":\"a red cube\"}}]}");

        File image = File.createTempFile("photo", ".png");
        Files.write(image.toPath(), new byte[] {(byte) 0x89, 'P', 'N', 'G'});

        String answer = api("sk_test").askAboutImage(image, "what is this?", "openai/gpt-6-sol");

        assertEquals("a red cube", answer);
        assertTrue(last().body.contains("data:image/png;base64,"));
        assertTrue(last().body.contains("what is this?"));
    }

    @Test
    void modelsParsesTheNames() throws Exception {
        route("/text/models", 200, "application/json",
                "[{\"name\":\"openai/gpt-6-sol\"},{\"name\":\"z-ai/glm-5.3\"}]");

        List<String> names = api("k").models(false);

        assertEquals(2, names.size());
        assertEquals("openai/gpt-6-sol", names.get(0));
    }

    @Test
    void deviceFlowReportsTheCodeAndWaitsForApproval() throws Exception {
        route("/api/device/code", 200, "application/json",
                "{\"device_code\":\"dev-1\",\"user_code\":\"ABCD-1234\","
                        + "\"verification_uri\":\"https://enter.pollinations.ai/device\","
                        + "\"verification_uri_complete\":\"https://enter.pollinations.ai/device?user_code=ABCD-1234\","
                        + "\"interval\":5}");
        route("/api/device/token", 200, "application/json", "{\"error\":\"authorization_pending\"}");

        PollinationsApi api = api("k");
        PollinationsApi.DeviceCode code = api.requestDeviceCode();

        assertEquals("ABCD-1234", code.userCode);
        assertEquals(5, code.intervalSeconds);
        assertTrue(code.verificationUrl.contains("user_code=ABCD-1234"));

        assertNull(api.pollDeviceCode(code), "pending is not an error");
    }

    @Test
    void deviceFlowReturnsTheMintedKey() throws Exception {
        route("/api/device/token", 200, "application/json", "{\"access_token\":\"sk_minted\"}");

        PollinationsApi api = api("k");
        PollinationsApi.DeviceCode code = new PollinationsApi.DeviceCode("dev-1", "ABCD", "url", 1);

        assertEquals("sk_minted", api.pollDeviceCode(code));
    }

    @Test
    void deviceFlowReportsADenial() {
        route("/api/device/token", 400, "application/json",
                "{\"error\":\"access_denied\",\"error_description\":\"The user denied the request\"}");

        PollinationsApi api = api("k");
        PollinationsApi.DeviceCode code = new PollinationsApi.DeviceCode("dev-1", "ABCD", "url", 1);

        PollinationsApi.PollinationsException error = assertThrows(
                PollinationsApi.PollinationsException.class, () -> api.pollDeviceCode(code));
        assertEquals("The user denied the request", error.getMessage());
    }

    @Test
    void base64MatchesTheStandard() {
        assertEquals("", PollinationsApi.toBase64(new byte[0]));
        assertEquals("aGVsbG8=", PollinationsApi.toBase64("hello".getBytes(StandardCharsets.UTF_8)));
        assertEquals("aGVsbG8h", PollinationsApi.toBase64("hello!".getBytes(StandardCharsets.UTF_8)));
        assertEquals("PD8+", PollinationsApi.toBase64("<?>".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void clampKeepsSizesInRange() {
        assertEquals(256, PollinationsApi.clampSide(8));
        assertEquals(256, PollinationsApi.clampSide(256));
        assertEquals(1024, PollinationsApi.clampSide(1024));
        assertEquals(2048, PollinationsApi.clampSide(99999));
    }

    @Test
    void mimeTypesComeFromTheFileEnding() {
        assertEquals("image/png", PollinationsApi.guessMime(new File("a.PNG")));
        assertEquals("audio/mp4", PollinationsApi.guessMime(new File("a.m4a")));
        assertEquals("application/octet-stream", PollinationsApi.guessMime(new File("a.bin")));
    }

    @Test
    void describeErrorReadsTheMessageAndFallsBackToTheStatus() {
        assertEquals("Insufficient balance",
                PollinationsApi.describeError(402, "{\"error\":{\"message\":\"Insufficient balance\"}}"));
        assertEquals("boom", PollinationsApi.describeError(500, "{\"error\":\"boom\"}"));
        assertEquals("Pollinations answered HTTP 503", PollinationsApi.describeError(503, "not json"));
    }

    private static byte[] readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = stream.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
