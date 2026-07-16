package edu.cqie.paiclidemo.rag;

/**
 * 代码块数据模型 —— RAG 系统的最小检索单元。
 * <p>
 * 每个 CodeChunk 代表源码文件中的一个"语义片段"，可以是：
 * <ul>
 *   <li><b>file</b> — 整个文件（非 Java 文件或小文件的兜底策略）</li>
 *   <li><b>class</b> — 类级别：类声明 + 签名（保留结构概览）</li>
 *   <li><b>method</b> — 方法级别：完整方法体（最精确的检索粒度）</li>
 * </ul>
 * <p>
 * 设计要点：
 * <ul>
 *   <li>使用 Java record，天然支持 equals/hashCode/toString，不可变且线程安全</li>
 *   <li>{@link #toEmbeddingText()} 生成 {@code [type:name] content} 格式，
 *       让 Embedding 模型同时感知块类型和代码内容，提升向量质量</li>
 *   <li>startLine / endLine 记录行号范围，检索结果可以直接跳转到源码位置</li>
 * </ul>
 * <p>
 * 使用示例：
 * <pre>{@code
 * // 方法级分块
 * CodeChunk chunk = CodeChunk.methodChunk(
 *     "src/Agent.java", "Agent.run",
 *     "public String run(String input) { ... }", 42, 120);
 *
 * // 生成 Embedding 文本
 * String text = chunk.toEmbeddingText();
 * // → "[method:Agent.run] public String run(String input) { ... }"
 * }</pre>
 *
 * @param filePath  文件相对路径（如 "src/main/java/com/example/Agent.java"）
 * @param chunkType 块类型：file / class / method
 * @param name      名称标识（文件路径 / 类名 / 类名.方法名）
 * @param content   代码内容（用于 Embedding 向量化和检索结果展示）
 * @param startLine 起始行号（1-based；file 级别为 0）
 * @param endLine   结束行号（1-based；file 级别为 0）
 * @author Fonzo
 * @date 2026/07/16
 */
public record CodeChunk(String filePath, String chunkType, String name,
                        String content, int startLine, int endLine) {

    // ==================== 静态工厂方法 ====================
    // 工厂方法隐藏了 chunkType 字符串，避免调用方写错类型名

    /**
     * 构造文件级代码块。
     * <p>
     * 用于非 Java 文件或过小的文件，整块作为一个 chunk。
     * filePath 同时作为 name，行号范围为 0-0（不精确到行）。
     *
     * @param filePath 文件相对路径
     * @param content  文件完整内容
     */
    public static CodeChunk fileChunk(String filePath, String content) {
        return new CodeChunk(filePath, "file", filePath, content, 0, 0);
    }

    /**
     * 构造类级代码块。
     * <p>
     * 保留类的声明部分（package + import + class 签名 + 前几行），
     * 给检索提供"这个类是做什么的"概览信息。
     *
     * @param filePath  文件相对路径
     * @param className 类名（如 "Agent"）
     * @param content   类声明代码片段
     * @param startLine 类声明起始行
     * @param endLine   类声明结束行
     */
    public static CodeChunk classChunk(String filePath, String className,
                                       String content, int startLine, int endLine) {
        return new CodeChunk(filePath, "class", className, content, startLine, endLine);
    }

    /**
     * 构造方法级代码块。
     * <p>
     * 最精确的检索粒度。当用户问"Agent 的 run 方法怎么实现的"时，
     * 方法级 chunk 能直接命中，而不需要返回整个类。
     *
     * @param filePath   文件相对路径
     * @param methodName 方法全限定名（如 "Agent.run"）
     * @param content    完整方法体代码
     * @param startLine  方法起始行
     * @param endLine    方法结束行
     */
    public static CodeChunk methodChunk(String filePath, String methodName,
                                        String content, int startLine, int endLine) {
        return new CodeChunk(filePath, "method", methodName, content, startLine, endLine);
    }

    // ==================== Embedding 文本生成 ====================

    /**
     * 生成用于 Embedding 向量化的文本表示。
     * <p>
     * 格式：{@code [chunkType:name] content}
     * <p>
     * 前缀 {@code [type:name]} 的作用：让 Embedding 模型在编码时感知块的类型和名称。
     * 例如 {@code [method:Agent.run]} 会让向量在语义空间中更接近"方法实现"相关的查询，
     * 而不是和"类概述"的查询混在一起。
     * <p>
     * 示例输出：
     * <pre>
     * [method:Agent.run] public String run(String userInput) {
     *     memoryManager.addUserMessage(userInput);
     *     ...
     * }
     * </pre>
     */
    public String toEmbeddingText() {
        return String.format("[%s:%s] %s", chunkType, name, content);
    }
}
