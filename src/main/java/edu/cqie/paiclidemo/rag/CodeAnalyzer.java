package edu.cqie.paiclidemo.rag;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 代码分析器 —— 基于 JavaParser AST 构建代码关系图谱。
 * <p>
 * <b>工作原理</b>：解析 Java 源文件的 AST（抽象语法树），
 * 从中提取类/方法之间的结构化关系，生成 {@link CodeRelation} 列表。
 * <p>
 * <b>提取的关系类型</b>：
 * <pre>
 *  ┌────────────┬─────────────────────────────────────────────────┐
 *  │ extends    │ 类继承：Agent extends BaseAgent                  │
 *  │ implements │ 接口实现：GLMClient implements LlmClient         │
 *  │ imports    │ 非 JDK 导入：import edu.cqie...Agent             │
 *  │ contains   │ 类包含方法：Agent contains Agent.run             │
 *  │ calls      │ 方法调用：Agent.run calls memoryManager.add      │
 *  └────────────┴─────────────────────────────────────────────────┘
 * </pre>
 * <p>
 * <b>注意</b>：calls 关系是简化版的 —— 只记录被调用方法的名称，
 * 不做完整的类型解析（JavaParser 需要 symbol solver 才能做到精确解析）。
 * 对于 Demo 项目来说这已经足够展示关系图谱了。
 *
 * @author Fonzo
 * @date 2026/07/16
 */
public class CodeAnalyzer {

    private final JavaParser parser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

    // ==================== 核心 API ====================

    /**
     * 分析单个 Java 文件，提取所有代码关系。
     * <p>
     * 解析失败时返回空列表（静默处理，不抛异常），
     * 保证单个文件的解析错误不会中断整个索引流程。
     *
     * @param filePath Java 源文件路径
     * @return 提取到的关系列表（可能为空）
     */
    public List<CodeRelation> analyzeFile(Path filePath) throws IOException {
        String content = Files.readString(filePath);
        String filePathStr = filePath.toString();
        List<CodeRelation> relations = new ArrayList<>();

        ParseResult<CompilationUnit> result = parser.parse(content);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            return relations;
        }

        CompilationUnit cu = result.getResult().get();

        // ① 提取导入关系
        extractImports(filePathStr, cu, relations);

        // ② 提取类级别关系（extends, implements, contains, calls）
        extractClassRelations(filePathStr, cu, relations);

        return relations;
    }

    // ==================== 导入关系 ====================

    /**
     * 提取非 JDK 的 import 语句作为依赖关系。
     * <p>
     * 过滤掉 java.* 和 javax.* 的标准库导入，只保留项目内 / 第三方库的导入，
     * 这些更可能是有意义的代码依赖。
     */
    private void extractImports(String filePath, CompilationUnit cu, List<CodeRelation> relations) {
        for (ImportDeclaration imp : cu.getImports()) {
            String importName = imp.getNameAsString();
            String simpleName = importName.substring(importName.lastIndexOf('.') + 1);
            // 只记录非 JDK 导入
            if (!importName.startsWith("java.") && !importName.startsWith("javax.")) {
                relations.add(new CodeRelation(
                        filePath, "file", null, simpleName, "imports"));
            }
        }
    }

    // ==================== 类级别关系 ====================

    /**
     * 提取类/接口的结构化关系：extends、implements、contains、calls。
     */
    private void extractClassRelations(String filePath, CompilationUnit cu, List<CodeRelation> relations) {
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            String className = clazz.getNameAsString();

            // extends 关系
            clazz.getExtendedTypes().forEach(ext ->
                    relations.add(new CodeRelation(
                            filePath, className, null, ext.getNameAsString(), "extends")));

            // implements 关系
            clazz.getImplementedTypes().forEach(impl ->
                    relations.add(new CodeRelation(
                            filePath, className, null, impl.getNameAsString(), "implements")));

            // contains 关系：类包含方法
            clazz.getMethods().forEach(method -> {
                String methodName = method.getNameAsString();
                relations.add(new CodeRelation(
                        filePath, className, filePath, className + "." + methodName, "contains"));
            });

            // calls 关系：方法内调用的其他方法
            clazz.findAll(MethodCallExpr.class).forEach(call -> {
                String callee = call.getNameAsString();
                Optional<MethodDeclaration> parentMethod = findParentMethod(call);
                if (parentMethod.isPresent()) {
                    String caller = className + "." + parentMethod.get().getNameAsString();
                    relations.add(new CodeRelation(
                            filePath, caller, null, callee, "calls"));
                }
            });
        });
    }

    // ==================== AST 工具方法 ====================

    /**
     * 沿 AST 父链向上查找，定位当前节点所在的方法声明。
     * <p>
     * 用于确定 "谁调用了谁"：当遍历到 MethodCallExpr 时，
     * 通过此方法找到包含它的外层 MethodDeclaration，从而知道调用者是谁。
     */
    private Optional<MethodDeclaration> findParentMethod(Node node) {
        Node current = node;
        while (current != null) {
            if (current instanceof MethodDeclaration method) {
                return Optional.of(method);
            }
            current = current.getParentNode().orElse(null);
        }
        return Optional.empty();
    }
}
