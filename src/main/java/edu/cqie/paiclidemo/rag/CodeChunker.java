package edu.cqie.paiclidemo.rag;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 代码分块器 —— 将源码文件切分为适合 Embedding 的语义粒度。
 * <p>
 * <b>核心思路</b>：摒弃传统的按字数硬切，改为按代码结构切分，减少语义噪音。
 * <p>
 * 分块策略：
 * <pre>
 * ┌─────────────────────────────────────────────────────┐
 * │  .java 文件                                         │
 * │  └─ AST 解析（JavaParser, Java 17 语言级别）         │
 * │     ├─ classChunk: 类声明 + 签名（前 5 行概览）      │
 * │     └─ methodChunk: 每个方法的完整方法体             │
 * │                                                     │
 * │  非 .java 文件（或 AST 解析失败）                    │
 * │  └─ 按行分段，每段 ≤ 2000 字符                       │
 * │     └─ fileChunk: filePath#1, filePath#2, ...       │
 * └─────────────────────────────────────────────────────┘
 * </pre>
 * <p>
 * <b>为什么方法级分块最重要？</b><br>
 * 当用户问"Agent 的 run 方法怎么实现的"，方法级 chunk 能直接命中完整的方法体，
 * 而不是返回一整个大文件让用户自己去翻。检索精度最高，噪音最小。
 * <p>
 * <b>为什么类级 chunk 也要保留？</b><br>
 * 类级 chunk 提供"这个类是做什么的"概览信息。当用户问"ToolRegistry 是什么"时，
 * 类级 chunk（包含声明 + 字段签名）比任何单个方法都更有代表性。
 *
 * @author Fonzo
 * @date 2026/07/16
 */
public class CodeChunker {

    /**
     * JavaParser 实例，设置为 Java 17 语言级别。
     * <p>
     * 支持 text block（"""）、record、sealed class、pattern matching 等新语法。
     * 如果不设置，默认是 Java 8 级别，解析高版本语法会报错。
     */
    private final JavaParser parser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

    /**
     * 单个 chunk 最大字符数。
     * <p>
     * 中文约 1 字符 ≈ 2~3 token，2000 字符 ≈ 4000~6000 token，
     * 安全适配常见的 8192 token Embedding 模型上下文窗口。
     */
    static final int MAX_CHUNK_CHARS = 2000;

    // ==================== 公开 API ====================

    /**
     * 对单个文件进行分块（读取文件内容后委托给 {@link #chunkContent}）。
     *
     * @param filePath 文件路径（读取内容）
     * @return 代码块列表
     * @throws IOException 文件读取失败
     */
    public List<CodeChunk> chunkFile(Path filePath) throws IOException {
        String content = Files.readString(filePath);
        return chunkContent(filePath.toString(), content);
    }

    /**
     * 对字符串内容进行分块（无需文件系统，方便测试和流式处理）。
     * <p>
     * 分块逻辑：
     * <ol>
     *   <li>检查文件扩展名 —— 非 .java 走通用文本分块</li>
     *   <li>.java 文件用 JavaParser 做 AST 解析</li>
     *   <li>AST 解析成功 → 按类/方法分块</li>
     *   <li>AST 解析失败 → 回退到通用文本分块</li>
     * </ol>
     *
     * @param filePath 文件路径（用于 CodeChunk.filePath 和扩展名判断）
     * @param content  文件内容
     * @return 代码块列表
     */
    public List<CodeChunk> chunkContent(String filePath, String content) {
        // 非 Java 文件：按大小分段
        if (!filePath.endsWith(".java")) {
            return chunkLargeText(filePath, content);
        }

        // Java 文件：AST 解析分块
        return chunkJavaFile(filePath, content);
    }

    // ==================== Java AST 分块 ====================

    /**
     * 用 JavaParser 解析 Java 文件，按类和方法两级粒度分块。
     * <p>
     * 解析流程：
     * <pre>
     * CompilationUnit（.java 文件）
     *  └─ ClassOrInterfaceDeclaration（每个类/接口）
     *      ├─ → classChunk（类声明头 + 前 5 行）
     *      └─ MethodDeclaration（每个方法）
     *          └─ → methodChunk（完整方法体）
     * </pre>
     * <p>
     * 容错：解析失败或没有类声明时，回退到 {@link #chunkLargeText}。
     */
    private List<CodeChunk> chunkJavaFile(String filePath, String content) {
        List<CodeChunk> chunks = new ArrayList<>();
        ParseResult<CompilationUnit> result = parser.parse(content);

        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            // AST 解析失败 → 回退到按行分段
            return chunkLargeText(filePath, content);
        }

        CompilationUnit cu = result.getResult().get();

        // 遍历所有类/接口声明
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            int classStart = clazz.getBegin().map(p -> p.line).orElse(0);
            int classEnd = clazz.getEnd().map(p -> p.line).orElse(0);
            String className = clazz.getNameAsString();

            // --- 类级别 chunk ---
            // 提取类声明头部：从 class 行开始，最多取前 5 行（含字段签名概览）
            // Math.min(classStart + 5, classEnd) 防止类太小时越界
            String classHeader = extractLines(content, classStart,
                    Math.min(classStart + 5, classEnd));

            chunks.add(CodeChunk.classChunk(
                    filePath, className, classHeader, classStart, classEnd));

            // --- 方法级别 chunk ---
            clazz.getMethods().forEach(method -> {
                int methodStart = method.getBegin().map(p -> p.line).orElse(0);
                int methodEnd = method.getEnd().map(p -> p.line).orElse(0);

                // getDeclarationAsString(false, false, false) 返回简洁签名：
                // 参数1=false: 不含修饰符（public/private）
                // 参数2=false: 不含注解
                // 参数3=false: 不含泛型参数
                // 例如：run(String userInput) 而非 public String run(String userInput)
                String methodSignature = method.getDeclarationAsString(false, false, false);
                String methodContent = extractLines(content, methodStart, methodEnd);

                chunks.add(CodeChunk.methodChunk(
                        filePath,
                        className + "." + methodSignature,
                        methodContent, methodStart, methodEnd));
            });
        });

        // AST 没解析出任何类（如 package-info.java 或空文件）→ 回退
        if (chunks.isEmpty()) {
            return chunkLargeText(filePath, content);
        }

        return chunks;
    }

    // ==================== 通用文本分块 ====================

    /**
     * 将大文本按行分段，每段不超过 {@link #MAX_CHUNK_CHARS} 字符。
     * <p>
     * 分段策略：
     * <ol>
     *   <li>按 {@code \r?\n} 拆分为行数组</li>
     *   <li>逐行累加，当累加器超过 MAX_CHUNK_CHARS 时切一刀</li>
     *   <li>每段命名为 {@code filePath#段号}（如 README.md#1）</li>
     *   <li>记录每段的起始/结束行号，方便检索后跳转源码</li>
     * </ol>
     * <p>
     * 如果内容本身不超过 MAX_CHUNK_CHARS，直接返回一个 fileChunk。
     */
    private List<CodeChunk> chunkLargeText(String filePath, String content) {
        if (content.length() <= MAX_CHUNK_CHARS) {
            return List.of(CodeChunk.fileChunk(filePath, content));
        }

        List<CodeChunk> chunks = new ArrayList<>();
        String[] lines = content.split("\r?\n");
        StringBuilder segment = new StringBuilder();
        int segIndex = 1;
        int startLine = 1;

        for (int i = 0; i < lines.length; i++) {
            // 预判：加上当前行会不会超限？
            if (segment.length() + lines[i].length() + 1 > MAX_CHUNK_CHARS && !segment.isEmpty()) {
                // 切一刀：把当前累加器作为一个段
                chunks.add(new CodeChunk(filePath, "file",
                        filePath + "#" + segIndex, segment.toString().trim(), startLine, i));
                segment.setLength(0);
                segIndex++;
                startLine = i + 1;
            }
            segment.append(lines[i]).append("\n");
        }

        // 最后一段（可能不满 MAX_CHUNK_CHARS）
        if (!segment.isEmpty()) {
            chunks.add(new CodeChunk(filePath, "file",
                    filePath + "#" + segIndex, segment.toString().trim(), startLine, lines.length));
        }

        return chunks;
    }

    // ==================== 工具方法 ====================

    /**
     * 从文本内容中提取指定行范围的子串。
     * <p>
     * 行号为 1-based（与 JavaParser 的 Position.line 一致）。
     * 例如 extractLines(content, 10, 15) 提取第 10~15 行（含两端）。
     *
     * @param content   完整文件内容
     * @param startLine 起始行号（1-based，含）
     * @param endLine   结束行号（1-based，含）
     * @return 提取的行文本（已 trim）
     */
    String extractLines(String content, int startLine, int endLine) {
        String[] lines = content.split("\r?\n");
        StringBuilder sb = new StringBuilder();
        for (int i = startLine - 1; i < Math.min(endLine, lines.length); i++) {
            if (i >= 0) {
                sb.append(lines[i]).append("\n");
            }
        }
        return sb.toString().trim();
    }
}
