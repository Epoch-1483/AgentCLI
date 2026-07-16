package edu.cqie.paiclidemo.config;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DotEnv 单元测试。
 * <p>
 * 覆盖 .env 文件解析、引号去除、注释/空行跳过、不存在文件处理等场景。
 *
 * @author Fonzer
 * @date 2026/07/16
 */
class DotEnvTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // 每个测试前清除缓存，确保隔离
        DotEnv.reload();
    }

    @AfterEach
    void tearDown() {
        DotEnv.reload();
    }

    // ==================== parseFile 解析测试 ====================

    @Nested
    @DisplayName("parseFile — .env 文件解析")
    class ParseFileTests {

        @Test
        @DisplayName("正常解析 KEY=VALUE 格式")
        void parseBasicKeyValue() throws IOException {
            Path envFile = tempDir.resolve(".env");
            Files.writeString(envFile, "GLM_API_KEY=sk-test-123\nEMBEDDING_PROVIDER=zhipu\n",
                    StandardCharsets.UTF_8);

            Map<String, String> result = DotEnv.parseFile(envFile);

            assertEquals(2, result.size());
            assertEquals("sk-test-123", result.get("GLM_API_KEY"));
            assertEquals("zhipu", result.get("EMBEDDING_PROVIDER"));
        }

        @Test
        @DisplayName("跳过注释行和空行")
        void skipCommentsAndBlankLines() throws IOException {
            Path envFile = tempDir.resolve(".env");
            String content = """
                    # 这是注释
                    GLM_API_KEY=sk-abc

                    # 另一个注释
                    MODEL=glm-4
                    """;
            Files.writeString(envFile, content, StandardCharsets.UTF_8);

            Map<String, String> result = DotEnv.parseFile(envFile);

            assertEquals(2, result.size());
            assertEquals("sk-abc", result.get("GLM_API_KEY"));
            assertEquals("glm-4", result.get("MODEL"));
        }

        @Test
        @DisplayName("去除双引号")
        void stripDoubleQuotes() throws IOException {
            Path envFile = tempDir.resolve(".env");
            Files.writeString(envFile, "API_KEY=\"sk-quoted-value\"\n", StandardCharsets.UTF_8);

            Map<String, String> result = DotEnv.parseFile(envFile);

            assertEquals("sk-quoted-value", result.get("API_KEY"));
        }

        @Test
        @DisplayName("去除单引号")
        void stripSingleQuotes() throws IOException {
            Path envFile = tempDir.resolve(".env");
            Files.writeString(envFile, "API_KEY='sk-single-quoted'\n", StandardCharsets.UTF_8);

            Map<String, String> result = DotEnv.parseFile(envFile);

            assertEquals("sk-single-quoted", result.get("API_KEY"));
        }

        @Test
        @DisplayName("不匹配的引号原样保留")
        void mismatchedQuotesKeptAsIs() throws IOException {
            Path envFile = tempDir.resolve(".env");
            Files.writeString(envFile, "KEY=\"value'\n", StandardCharsets.UTF_8);

            Map<String, String> result = DotEnv.parseFile(envFile);

            assertEquals("\"value'", result.get("KEY"));
        }

        @Test
        @DisplayName("value 中包含 = 号时正确解析")
        void valueContainsEquals() throws IOException {
            Path envFile = tempDir.resolve(".env");
            Files.writeString(envFile, "URL=https://api.example.com/v4?key=abc&token=xyz\n",
                    StandardCharsets.UTF_8);

            Map<String, String> result = DotEnv.parseFile(envFile);

            assertEquals("https://api.example.com/v4?key=abc&token=xyz", result.get("URL"));
        }

        @Test
        @DisplayName("跳过没有 = 号的行")
        void skipLinesWithoutEquals() throws IOException {
            Path envFile = tempDir.resolve(".env");
            String content = "VALID_KEY=valid\ninvalid_line\nANOTHER=ok\n";
            Files.writeString(envFile, content, StandardCharsets.UTF_8);

            Map<String, String> result = DotEnv.parseFile(envFile);

            assertEquals(2, result.size());
            assertEquals("valid", result.get("VALID_KEY"));
            assertEquals("ok", result.get("ANOTHER"));
        }

        @Test
        @DisplayName("key 两端空格被去除")
        void trimKeyWhitespace() throws IOException {
            Path envFile = tempDir.resolve(".env");
            Files.writeString(envFile, "  MY_KEY  =my_value\n", StandardCharsets.UTF_8);

            Map<String, String> result = DotEnv.parseFile(envFile);

            assertEquals("my_value", result.get("MY_KEY"));
        }

        @Test
        @DisplayName("value 两端空格被去除")
        void trimValueWhitespace() throws IOException {
            Path envFile = tempDir.resolve(".env");
            Files.writeString(envFile, "KEY=  spaced_value  \n", StandardCharsets.UTF_8);

            Map<String, String> result = DotEnv.parseFile(envFile);

            assertEquals("spaced_value", result.get("KEY"));
        }

        @Test
        @DisplayName("文件不存在时返回空 Map")
        void nonExistentFileReturnsEmpty() {
            Path noFile = tempDir.resolve("nonexistent.env");

            Map<String, String> result = DotEnv.parseFile(noFile);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("空文件返回空 Map")
        void emptyFileReturnsEmpty() throws IOException {
            Path envFile = tempDir.resolve(".env");
            Files.writeString(envFile, "", StandardCharsets.UTF_8);

            Map<String, String> result = DotEnv.parseFile(envFile);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("只有注释的文件返回空 Map")
        void commentsOnlyReturnsEmpty() throws IOException {
            Path envFile = tempDir.resolve(".env");
            Files.writeString(envFile, "# comment 1\n# comment 2\n", StandardCharsets.UTF_8);

            Map<String, String> result = DotEnv.parseFile(envFile);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("value 为空字符串时保留")
        void emptyValuePreserved() throws IOException {
            Path envFile = tempDir.resolve(".env");
            Files.writeString(envFile, "EMPTY_KEY=\n", StandardCharsets.UTF_8);

            Map<String, String> result = DotEnv.parseFile(envFile);

            assertTrue(result.containsKey("EMPTY_KEY"));
            assertEquals("", result.get("EMPTY_KEY"));
        }

        @Test
        @DisplayName("Windows 换行符正确处理")
        void windowsLineEndings() throws IOException {
            Path envFile = tempDir.resolve(".env");
            Files.writeString(envFile, "KEY1=val1\r\nKEY2=val2\r\n", StandardCharsets.UTF_8);

            Map<String, String> result = DotEnv.parseFile(envFile);

            assertEquals("val1", result.get("KEY1"));
            assertEquals("val2", result.get("KEY2"));
        }
    }

    // ==================== get 优先级测试 ====================

    @Nested
    @DisplayName("get — 配置优先级")
    class GetTests {

        @Test
        @DisplayName("所有来源都没有时返回默认值")
        void returnsDefaultWhenNotFound() {
            String value = DotEnv.get("COMPLETELY_NONEXISTENT_KEY_XYZ_123", "my_default");
            assertEquals("my_default", value);
        }

        @Test
        @DisplayName("默认值为 null 时返回 null")
        void returnsNullDefaultWhenNotFound() {
            String value = DotEnv.get("COMPLETELY_NONEXISTENT_KEY_XYZ_123", null);
            assertNull(value);
        }
    }
}
