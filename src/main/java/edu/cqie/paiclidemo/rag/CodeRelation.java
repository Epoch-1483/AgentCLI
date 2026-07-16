package edu.cqie.paiclidemo.rag;

/**
 * 代码关系数据模型 —— 代码关系图谱中的一条有向边。
 * <p>
 * 用于记录类/方法之间的依赖关系，支持以下 5 种关系类型：
 * <ul>
 *   <li><b>extends</b> — 类继承（如 {@code Agent extends BaseAgent}）</li>
 *   <li><b>implements</b> — 接口实现（如 {@code GLMClient implements LlmClient}）</li>
 *   <li><b>imports</b> — 非 JDK 导入依赖</li>
 *   <li><b>calls</b> — 方法调用（如 {@code Agent.run()} 调用了 {@code memoryManager.addUserMessage()}）</li>
 *   <li><b>contains</b> — 类包含方法</li>
 * </ul>
 * <p>
 * 关系数据由 {@code CodeAnalyzer} 通过 AST 解析提取，
 * 存储在 VectorStore 的 {@code code_relations} 表中，
 * 可通过 {@code /graph} 命令查询。
 *
 * @param fromFile     源文件路径
 * @param fromName     源名称（类名或 "类名.方法名"）
 * @param toFile       目标文件路径（可能为 null）
 * @param toName       目标名称
 * @param relationType 关系类型：extends / implements / imports / calls / contains
 * @author Fonzo
 * @date 2026/07/16
 */
public record CodeRelation(String fromFile, String fromName,
                           String toFile, String toName,
                           String relationType) {
}
