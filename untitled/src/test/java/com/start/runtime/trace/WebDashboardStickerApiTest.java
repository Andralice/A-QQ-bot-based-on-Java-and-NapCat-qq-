package com.start.runtime.trace;

import com.start.service.StickerIngestService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 以浏览器实际调用的 HTTP 路径验证表情包审阅闭环。 */
class WebDashboardStickerApiTest {

    private Path tempDir;
    private Path jsonPath;
    private Path imagePath;
    private WebDashboardListener listener;
    private HttpClient client;
    private URI baseUri;
    private String oldJson;
    private String oldDir;
    private String oldHost;
    private String oldPort;
    private String oldToken;

    @BeforeEach
    void setUp() throws Exception {
        oldJson = System.getProperty("sticker.json");
        oldDir = System.getProperty("sticker.dir");
        oldHost = System.getProperty("dashboard.host");
        oldPort = System.getProperty("dashboard.port");
        oldToken = System.getProperty("dashboard.token");

        tempDir = Files.createTempDirectory("candybear-sticker-api-");
        Path imageDir = Files.createDirectories(tempDir.resolve("stickers"));
        jsonPath = tempDir.resolve("stickers.json");
        imagePath = imageDir.resolve("sample.gif");
        Files.write(imagePath, "GIF89a-test".getBytes(StandardCharsets.US_ASCII));
        Files.writeString(jsonPath, "[{\"id\":\"review-1\",\"file\":\"sample.gif\","
                + "\"keywords\":[\"旧关键词\"],\"autoKeywords\":[\"自动标签\"],"
                + "\"description\":\"一张待审阅图片\",\"sourceGroup\":\"123\","
                + "\"createdAt\":1700000000000}]", StandardCharsets.UTF_8);

        System.setProperty("sticker.json", jsonPath.toString());
        System.setProperty("sticker.dir", imageDir.toString());
        System.setProperty("dashboard.host", "127.0.0.1");
        System.setProperty("dashboard.port", "0");
        System.setProperty("dashboard.token", "review-token");

        StickerIngestService.init();
        listener = new WebDashboardListener();
        listener.start();
        HttpServer server = (HttpServer) field(listener, "server");
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (listener != null) listener.stop();
        restore("sticker.json", oldJson);
        restore("sticker.dir", oldDir);
        restore("dashboard.host", oldHost);
        restore("dashboard.port", oldPort);
        restore("dashboard.token", oldToken);
        if (tempDir != null) {
            try (var paths = Files.walk(tempDir)) {
                paths.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                });
            }
        }
    }

    @Test
    void reviewApiListsPreviewsUpdatesAndDeletesSticker() throws Exception {
        HttpResponse<String> unauthorized = send("GET", "/api/stickers", null, false);
        assertEquals(401, unauthorized.statusCode());

        HttpResponse<String> list = send("GET", "/api/stickers", null, true);
        assertEquals(200, list.statusCode());
        assertTrue(list.body().contains("review-1"));
        assertTrue(list.body().contains("旧关键词"));

        HttpResponse<byte[]> image = sendBytes("/api/stickers/image?id=review-1");
        assertEquals(200, image.statusCode());
        assertEquals("image/gif", image.headers().firstValue("Content-Type").orElse(""));
        assertEquals("GIF89a-test", new String(image.body(), StandardCharsets.US_ASCII));

        HttpResponse<String> update = send("PATCH", "/api/stickers/review-1",
                "{\"keywords\":[\"开心\",\"可爱\"]}", true);
        assertEquals(200, update.statusCode());
        assertTrue(update.body().contains("开心"));
        assertTrue(Files.readString(jsonPath).contains("可爱"));

        HttpResponse<String> deleted = send("DELETE", "/api/stickers/review-1", null, true);
        assertEquals(200, deleted.statusCode());
        assertTrue(deleted.body().contains("\"ok\":true"));
        assertFalse(Files.exists(imagePath));
        assertEquals("[]", send("GET", "/api/stickers", null, true).body());
    }

    private HttpResponse<String> send(String method, String path, String body, boolean auth) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path));
        if (auth) builder.header("X-Dashboard-Token", "review-token");
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> sendBytes(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(baseUri.resolve(path))
                        .header("X-Dashboard-Token", "review-token")
                        .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
