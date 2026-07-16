package edu.cqie.paiclidemo.rag;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EmbeddingClient 单元测试。
 * <p>
 * 使用 OkHttp MockWebServer 模拟真实的 HTTP API 响应，
 * 验证 Ollama / OpenAI 两种后端的请求格式、响应解析、错误处理。
 */
@DisplayName("EmbeddingClient 向量化客户端")
class EmbeddingClientTest {

    private MockWebServer mockServer;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    /** 获取 MockServer 的基地址（如 http://localhost:xxxxx） */
    private String mockBaseUrl() {
        return mockServer.url("").toString();
    }

    // ==================== 边界输入 ====================

    @Nested
    @DisplayName("边界输入处理")
    class EdgeInputs {

        @Test
        @DisplayName("null 输入 → 返回空数组（不调用 API）")
        void nullInput() throws IOException {
            EmbeddingClient client = new EmbeddingClient("ollama", "test", "http://unused", "");
            float[] result = client.embed(null);
            assertEquals(0, result.length);
        }

        @Test
        @DisplayName("空字符串 → 返回空数组（不调用 API）")
        void emptyInput() throws IOException {
            EmbeddingClient client = new EmbeddingClient("ollama", "test", "http://unused", "");
            float[] result = client.embed("");
            assertEquals(0, result.length);
        }
    }

    // ==================== Ollama 后端 ====================

    @Nested
    @DisplayName("Ollama 后端")
    class OllamaBackend {

        @Test
        @DisplayName("正确解析 Ollama 响应格式 { embedding: [...] }")
        void parseOllamaResponse() throws Exception {
            mockServer.enqueue(new MockResponse()
                    .setBody("""
                            {"embedding": [0.1, -0.2, 0.3, 0.4, 0.5]}
                            """)
                    .setHeader("Content-Type", "application/json"));

            EmbeddingClient client = new EmbeddingClient(
                    "ollama", "nomic-embed-text:latest",
                    mockBaseUrl().replaceAll("/$", ""), "");

            float[] embedding = client.embed("Hello world");

            assertEquals(5, embedding.length);
            assertEquals(0.1f, embedding[0], 0.001f);
            assertEquals(-0.2f, embedding[1], 0.001f);
            assertEquals(0.3f, embedding[2], 0.001f);
        }

        @Test
        @DisplayName("请求体包含 model 和 prompt 字段（Ollama 用 prompt 而非 input）")
        void ollamaRequestBody() throws Exception {
            mockServer.enqueue(new MockResponse()
                    .setBody("{\"embedding\": [0.1]}")
                    .setHeader("Content-Type", "application/json"));

            EmbeddingClient client = new EmbeddingClient(
                    "ollama", "nomic-embed-text:latest",
                    mockBaseUrl().replaceAll("/$", ""), "");

            client.embed("测试文本");

            RecordedRequest request = mockServer.takeRequest();
            assertEquals("POST", request.getMethod());
            assertTrue(request.getPath().endsWith("/api/embeddings"),
                    "URL 应以 /api/embeddings 结尾，实际: " + request.getPath());

            String body = request.getBody().readUtf8();
            assertTrue(body.contains("\"model\":\"nomic-embed-text:latest\""),
                    "请求体应包含 model 字段");
            assertTrue(body.contains("\"prompt\":\"测试文本\""),
                    "Ollama 应使用 prompt 字段（非 input）");
        }

        @Test
        @DisplayName("Ollama 不需要 Authorization 头")
        void ollamaNoAuth() throws Exception {
            mockServer.enqueue(new MockResponse()
                    .setBody("{\"embedding\": [0.1]}")
                    .setHeader("Content-Type", "application/json"));

            EmbeddingClient client = new EmbeddingClient(
                    "ollama", "test", mockBaseUrl().replaceAll("/$", ""), "");

            client.embed("test");

            RecordedRequest request = mockServer.takeRequest();
            assertNull(request.getHeader("Authorization"),
                    "Ollama 不应发送 Authorization 头");
        }

        @Test
        @DisplayName("Ollama 响应格式错误 → 抛出 IOException")
        void ollamaBadResponse() {
            mockServer.enqueue(new MockResponse()
                    .setBody("{\"error\": \"model not found\"}")
                    .setHeader("Content-Type", "application/json"));

            EmbeddingClient client = new EmbeddingClient(
                    "ollama", "bad-model", mockBaseUrl().replaceAll("/$", ""), "");

            IOException ex = assertThrows(IOException.class, () -> client.embed("test"));
            assertTrue(ex.getMessage().contains("格式不正确"));
        }
    }

    // ==================== OpenAI 兼容后端 ====================

    @Nested
    @DisplayName("OpenAI 兼容后端（zhipu / glm）")
    class OpenAIBackend {

        @Test
        @DisplayName("正确解析 OpenAI 格式 { data: [{ embedding: [...] }] }")
        void parseOpenAIResponse() throws Exception {
            mockServer.enqueue(new MockResponse()
                    .setBody("""
                            {"data": [{"embedding": [0.5, 0.6, 0.7], "index": 0}]}
                            """)
                    .setHeader("Content-Type", "application/json"));

            EmbeddingClient client = new EmbeddingClient(
                    "zhipu", "embedding-3",
                    mockBaseUrl().replaceAll("/$", ""), "test-api-key");

            float[] embedding = client.embed("Hello");

            assertEquals(3, embedding.length);
            assertEquals(0.5f, embedding[0], 0.001f);
            assertEquals(0.6f, embedding[1], 0.001f);
            assertEquals(0.7f, embedding[2], 0.001f);
        }

        @Test
        @DisplayName("请求体使用 input 字段（OpenAI 标准，非 prompt）")
        void openAIRequestBody() throws Exception {
            mockServer.enqueue(new MockResponse()
                    .setBody("{\"data\": [{\"embedding\": [0.1]}]}")
                    .setHeader("Content-Type", "application/json"));

            EmbeddingClient client = new EmbeddingClient(
                    "glm", "embedding-3",
                    mockBaseUrl().replaceAll("/$", ""), "my-key");

            client.embed("搜索文本");

            RecordedRequest request = mockServer.takeRequest();
            assertTrue(request.getPath().endsWith("/embeddings"),
                    "URL 应以 /embeddings 结尾");

            String body = request.getBody().readUtf8();
            assertTrue(body.contains("\"input\":\"搜索文本\""),
                    "OpenAI 兼容应使用 input 字段");
            assertTrue(body.contains("\"model\":\"embedding-3\""),
                    "请求体应包含 model 字段");
        }

        @Test
        @DisplayName("附带 Bearer Token Authorization 头")
        void openAIAuthHeader() throws Exception {
            mockServer.enqueue(new MockResponse()
                    .setBody("{\"data\": [{\"embedding\": [0.1]}]}")
                    .setHeader("Content-Type", "application/json"));

            EmbeddingClient client = new EmbeddingClient(
                    "openai", "text-embedding-3-small",
                    mockBaseUrl().replaceAll("/$", ""), "sk-test-123");

            client.embed("test");

            RecordedRequest request = mockServer.takeRequest();
            assertEquals("Bearer sk-test-123", request.getHeader("Authorization"));
        }

        @Test
        @DisplayName("provider='glm' 也走 OpenAI 兼容路径")
        void glmProvider() throws Exception {
            mockServer.enqueue(new MockResponse()
                    .setBody("{\"data\": [{\"embedding\": [1.0, 2.0]}]}")
                    .setHeader("Content-Type", "application/json"));

            EmbeddingClient client = new EmbeddingClient(
                    "glm", "embedding-3",
                    mockBaseUrl().replaceAll("/$", ""), "key");

            float[] result = client.embed("测试");
            assertEquals(2, result.length);
            assertEquals(1.0f, result[0], 0.001f);
        }

        @Test
        @DisplayName("OpenAI data 为空数组 → 抛出 IOException")
        void openAIEmptyData() {
            mockServer.enqueue(new MockResponse()
                    .setBody("{\"data\": []}")
                    .setHeader("Content-Type", "application/json"));

            EmbeddingClient client = new EmbeddingClient(
                    "openai", "model", mockBaseUrl().replaceAll("/$", ""), "key");

            assertThrows(IOException.class, () -> client.embed("test"));
        }
    }

    // ==================== HTTP 错误处理 ====================

    @Nested
    @DisplayName("HTTP 错误处理")
    class HttpErrors {

        @Test
        @DisplayName("API 返回 500 → 抛出 IOException，包含状态码")
        void serverError() {
            mockServer.enqueue(new MockResponse().setResponseCode(500).setBody("Internal Server Error"));

            EmbeddingClient client = new EmbeddingClient(
                    "ollama", "test", mockBaseUrl().replaceAll("/$", ""), "");

            IOException ex = assertThrows(IOException.class, () -> client.embed("test"));
            assertTrue(ex.getMessage().contains("500"));
        }

        @Test
        @DisplayName("API 返回 401 → 抛出 IOException，包含状态码")
        void unauthorized() {
            mockServer.enqueue(new MockResponse().setResponseCode(401).setBody("Unauthorized"));

            EmbeddingClient client = new EmbeddingClient(
                    "openai", "model", mockBaseUrl().replaceAll("/$", ""), "bad-key");

            IOException ex = assertThrows(IOException.class, () -> client.embed("test"));
            assertTrue(ex.getMessage().contains("401"));
        }
    }

    // ==================== 输入截断 ====================

    @Nested
    @DisplayName("输入截断")
    class InputTruncation {

        @Test
        @DisplayName("超过 MAX_INPUT_CHARS 的文本被截断后发送")
        void longTextTruncated() throws Exception {
            mockServer.enqueue(new MockResponse()
                    .setBody("{\"embedding\": [0.1]}")
                    .setHeader("Content-Type", "application/json"));

            EmbeddingClient client = new EmbeddingClient(
                    "ollama", "test", mockBaseUrl().replaceAll("/$", ""), "");

            // 发送 3000 字符的文本（超过 MAX_INPUT_CHARS=2000）
            String longText = "a".repeat(3000);
            client.embed(longText);

            RecordedRequest request = mockServer.takeRequest();
            String body = request.getBody().readUtf8();

            // 截断后的 prompt 应该是 2000 个 'a'
            assertTrue(body.contains("\"prompt\":\"" + "a".repeat(2000) + "\""),
                    "长文本应被截断为 MAX_INPUT_CHARS 字符");
            assertFalse(body.contains("a".repeat(2001)),
                    "不应包含超过 2000 个字符");
        }

        @Test
        @DisplayName("恰好等于 MAX_INPUT_CHARS → 不截断")
        void exactBoundary() throws Exception {
            mockServer.enqueue(new MockResponse()
                    .setBody("{\"embedding\": [0.1]}")
                    .setHeader("Content-Type", "application/json"));

            EmbeddingClient client = new EmbeddingClient(
                    "ollama", "test", mockBaseUrl().replaceAll("/$", ""), "");

            String exactText = "b".repeat(EmbeddingClient.MAX_INPUT_CHARS);
            client.embed(exactText);

            RecordedRequest request = mockServer.takeRequest();
            String body = request.getBody().readUtf8();
            assertTrue(body.contains("\"prompt\":\"" + exactText + "\""));
        }
    }

    // ==================== 配置 ====================

    @Nested
    @DisplayName("配置与默认值")
    class Configuration {

        @Test
        @DisplayName("4 参数构造 → getter 返回对应值")
        void explicitConstructor() {
            EmbeddingClient client = new EmbeddingClient("zhipu", "embedding-3", "https://api.example.com", "key123");

            assertEquals("zhipu", client.getProvider());
            assertEquals("embedding-3", client.getModel());
            assertEquals("https://api.example.com", client.getBaseUrl());
        }

        @Test
        @DisplayName("getEnv 有值时返回值，无值时返回默认值")
        void getEnvFallback() {
            // 不存在的环境变量 → 返回默认值
            assertEquals("fallback", EmbeddingClient.getEnv("NONEXISTENT_VAR_12345", "fallback"));
        }

        @Test
        @DisplayName("未知 provider → 默认走 Ollama 路径")
        void unknownProviderDefaultsToOllama() throws Exception {
            mockServer.enqueue(new MockResponse()
                    .setBody("{\"embedding\": [0.9]}")
                    .setHeader("Content-Type", "application/json"));

            EmbeddingClient client = new EmbeddingClient(
                    "unknown_provider", "model",
                    mockBaseUrl().replaceAll("/$", ""), "");

            float[] result = client.embed("test");

            assertEquals(1, result.length);
            assertEquals(0.9f, result[0], 0.001f);

            // 验证走的是 Ollama 路径（/api/embeddings）
            RecordedRequest request = mockServer.takeRequest();
            assertTrue(request.getPath().contains("/api/embeddings"));
        }

        @Test
        @DisplayName("provider 大小写不敏感（ZhiPu → zhipu）")
        void caseInsensitiveProvider() throws Exception {
            mockServer.enqueue(new MockResponse()
                    .setBody("{\"data\": [{\"embedding\": [0.1]}]}")
                    .setHeader("Content-Type", "application/json"));

            EmbeddingClient client = new EmbeddingClient(
                    "ZhiPu", "model",
                    mockBaseUrl().replaceAll("/$", ""), "key");

            float[] result = client.embed("test");
            assertEquals(1, result.length);

            // 验证走的是 OpenAI 兼容路径（/embeddings）
            RecordedRequest request = mockServer.takeRequest();
            assertTrue(request.getPath().endsWith("/embeddings"));
        }
    }
}
