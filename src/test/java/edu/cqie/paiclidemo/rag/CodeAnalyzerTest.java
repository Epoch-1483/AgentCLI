package edu.cqie.paiclidemo.rag;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CodeAnalyzer 单元测试。
 * <p>
 * 覆盖 extends / implements / contains / calls / imports 五种关系提取，
 * 以及解析失败和空文件的边界场景。
 *
 * @author Fonzo
 * @date 2026/07/16
 */
class CodeAnalyzerTest {

    @TempDir
    Path tempDir;

    private CodeAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new CodeAnalyzer();
    }

    // ==================== extends 关系 ====================

    @Test
    @DisplayName("提取类继承关系 (extends)")
    void extractExtendsRelation() throws IOException {
        String code = """
                package com.example;
                public class Dog extends Animal {
                    public void bark() {}
                }
                """;
        Path file = writeJavaFile("Dog.java", code);

        List<CodeRelation> relations = analyzer.analyzeFile(file);

        assertTrue(relations.stream().anyMatch(r ->
                r.relationType().equals("extends")
                        && r.fromName().equals("Dog")
                        && r.toName().equals("Animal")));
    }

    // ==================== implements 关系 ====================

    @Test
    @DisplayName("提取接口实现关系 (implements)")
    void extractImplementsRelation() throws IOException {
        String code = """
                package com.example;
                public class GLMClient implements LlmClient {
                    public String run(String input) { return ""; }
                }
                """;
        Path file = writeJavaFile("GLMClient.java", code);

        List<CodeRelation> relations = analyzer.analyzeFile(file);

        assertTrue(relations.stream().anyMatch(r ->
                r.relationType().equals("implements")
                        && r.fromName().equals("GLMClient")
                        && r.toName().equals("LlmClient")));
    }

    @Test
    @DisplayName("提取多接口实现关系")
    void extractMultipleImplements() throws IOException {
        String code = """
                package com.example;
                public class MyService implements Runnable, Comparable<MyService> {
                    public void run() {}
                    public int compareTo(MyService o) { return 0; }
                }
                """;
        Path file = writeJavaFile("MyService.java", code);

        List<CodeRelation> relations = analyzer.analyzeFile(file);

        long implCount = relations.stream()
                .filter(r -> r.relationType().equals("implements"))
                .count();
        assertEquals(2, implCount);
    }

    // ==================== contains 关系 ====================

    @Test
    @DisplayName("提取类包含方法关系 (contains)")
    void extractContainsRelation() throws IOException {
        String code = """
                package com.example;
                public class Calculator {
                    public int add(int a, int b) { return a + b; }
                    public int subtract(int a, int b) { return a - b; }
                }
                """;
        Path file = writeJavaFile("Calculator.java", code);

        List<CodeRelation> relations = analyzer.analyzeFile(file);

        assertTrue(relations.stream().anyMatch(r ->
                r.relationType().equals("contains")
                        && r.fromName().equals("Calculator")
                        && r.toName().equals("Calculator.add")));
        assertTrue(relations.stream().anyMatch(r ->
                r.relationType().equals("contains")
                        && r.fromName().equals("Calculator")
                        && r.toName().equals("Calculator.subtract")));
    }

    // ==================== calls 关系 ====================

    @Test
    @DisplayName("提取方法调用关系 (calls)")
    void extractCallsRelation() throws IOException {
        String code = """
                package com.example;
                public class Agent {
                    private ToolRegistry toolRegistry;
                    public String run(String input) {
                        toolRegistry.executeTool(null);
                        return "done";
                    }
                }
                """;
        Path file = writeJavaFile("Agent.java", code);

        List<CodeRelation> relations = analyzer.analyzeFile(file);

        assertTrue(relations.stream().anyMatch(r ->
                r.relationType().equals("calls")
                        && r.fromName().equals("Agent.run")
                        && r.toName().equals("executeTool")));
    }

    @Test
    @DisplayName("方法内无调用时不产生 calls 关系")
    void noCallsWhenNoMethodInvocations() throws IOException {
        String code = """
                package com.example;
                public class Simple {
                    public int getValue() { return 42; }
                }
                """;
        Path file = writeJavaFile("Simple.java", code);

        List<CodeRelation> relations = analyzer.analyzeFile(file);

        assertTrue(relations.stream().noneMatch(r -> r.relationType().equals("calls")));
    }

    // ==================== imports 关系 ====================

    @Test
    @DisplayName("提取非 JDK 导入关系 (imports)")
    void extractImportsRelation() throws IOException {
        String code = """
                package com.example;
                import edu.cqie.paiclidemo.agent.Agent;
                import java.util.List;
                public class Main {
                    public void run() {}
                }
                """;
        Path file = writeJavaFile("Main.java", code);

        List<CodeRelation> relations = analyzer.analyzeFile(file);

        // 非 JDK 导入应该被记录
        assertTrue(relations.stream().anyMatch(r ->
                r.relationType().equals("imports")
                        && r.toName().equals("Agent")));
        // JDK 导入应该被过滤
        assertTrue(relations.stream().noneMatch(r ->
                r.relationType().equals("imports")
                        && r.toName().equals("List")));
    }

    @Test
    @DisplayName("javax 导入也被过滤")
    void filterJavaxImports() throws IOException {
        String code = """
                package com.example;
                import javax.swing.JFrame;
                public class App {
                    public void show() {}
                }
                """;
        Path file = writeJavaFile("App.java", code);

        List<CodeRelation> relations = analyzer.analyzeFile(file);

        assertTrue(relations.stream().noneMatch(r -> r.relationType().equals("imports")));
    }

    // ==================== 综合场景 ====================

    @Test
    @DisplayName("完整类同时产生多种关系")
    void extractMultipleRelationTypes() throws IOException {
        String code = """
                package com.example;
                import edu.cqie.paiclidemo.llm.LlmClient;
                public class Agent extends BaseAgent implements Runnable {
                    private LlmClient llmClient;
                    public void run() {
                        llmClient.chat("hello");
                    }
                    public String getName() { return "agent"; }
                }
                """;
        Path file = writeJavaFile("Agent.java", code);

        List<CodeRelation> relations = analyzer.analyzeFile(file);

        // 应该有：1 imports + 1 extends + 1 implements + 2 contains + 1 calls = 至少 6 条
        assertTrue(relations.size() >= 6, "Expected >= 6 relations but got " + relations.size());

        // 验证各类型都存在
        assertTrue(relations.stream().anyMatch(r -> r.relationType().equals("imports")));
        assertTrue(relations.stream().anyMatch(r -> r.relationType().equals("extends")));
        assertTrue(relations.stream().anyMatch(r -> r.relationType().equals("implements")));
        assertTrue(relations.stream().anyMatch(r -> r.relationType().equals("contains")));
        assertTrue(relations.stream().anyMatch(r -> r.relationType().equals("calls")));
    }

    // ==================== 边界场景 ====================

    @Test
    @DisplayName("解析失败的 Java 文件返回空列表")
    void parseFailureReturnsEmpty() throws IOException {
        String badCode = "this is not valid java code!!!";
        Path file = writeJavaFile("Bad.java", badCode);

        List<CodeRelation> relations = analyzer.analyzeFile(file);

        assertTrue(relations.isEmpty());
    }

    @Test
    @DisplayName("空 Java 文件返回空列表")
    void emptyFileReturnsEmpty() throws IOException {
        Path file = writeJavaFile("Empty.java", "");

        List<CodeRelation> relations = analyzer.analyzeFile(file);

        assertTrue(relations.isEmpty());
    }

    @Test
    @DisplayName("接口定义也能提取关系")
    void interfaceRelations() throws IOException {
        String code = """
                package com.example;
                public interface LlmClient extends AutoCloseable {
                    String chat(String input);
                }
                """;
        Path file = writeJavaFile("LlmClient.java", code);

        List<CodeRelation> relations = analyzer.analyzeFile(file);

        // 接口也有 extends 关系
        assertTrue(relations.stream().anyMatch(r ->
                r.relationType().equals("extends")
                        && r.fromName().equals("LlmClient")
                        && r.toName().equals("AutoCloseable")));
        // 接口方法也有 contains 关系
        assertTrue(relations.stream().anyMatch(r ->
                r.relationType().equals("contains")
                        && r.fromName().equals("LlmClient")
                        && r.toName().equals("LlmClient.chat")));
    }

    // ==================== 工具方法 ====================

    private Path writeJavaFile(String fileName, String content) throws IOException {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
