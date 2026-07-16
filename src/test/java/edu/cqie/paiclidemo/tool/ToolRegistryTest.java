package edu.cqie.paiclidemo.tool;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cqie.paiclidemo.llm.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static edu.cqie.paiclidemo.tool.ToolRegistry.Param;
import static edu.cqie.paiclidemo.tool.ToolRegistry.createParameters;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolRegistry 单元测试 —— 覆盖注册、执行、参数解析、边界情况。
 */
class ToolRegistryTest {

    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
    }

    // ==================== 注册 ====================

    @Nested
    @DisplayName("工具注册")
    class RegisterTests {

        @Test
        @DisplayName("注册后可以查找到工具")
        void registerAndFind() {
            registry.register("test_tool", "测试工具",
                    createParameters(new Param("input", "string", "输入")),
                    args -> "结果");

            assertTrue(registry.hasTool("test_tool"));
            assertFalse(registry.hasTool("nonexistent"));
        }

        @Test
        @DisplayName("注册多个工具")
        void registerMultiple() {
            registry.register("tool_a", "A", createParameters(), args -> "A");
            registry.register("tool_b", "B", createParameters(), args -> "B");

            assertTrue(registry.hasTool("tool_a"));
            assertTrue(registry.hasTool("tool_b"));
            assertEquals(2, registry.getToolDefinitions().size());
        }

        @Test
        @DisplayName("重复注册同名工具 → 覆盖（不报错）")
        void reRegisterSameName() {
            registry.register("tool", "V1", createParameters(), args -> "V1结果");
            registry.register("tool", "V2", createParameters(), args -> "V2结果");

            // 执行时应该用 V2
            ToolCallResult result = executeWithName("tool", "{}");
            assertEquals("V2结果", result.result);
        }
    }

    // ==================== 执行 ====================

    @Nested
    @DisplayName("工具执行")
    class ExecuteTests {

        @Test
        @DisplayName("正常执行：参数传递和结果返回")
        void normalExecution() {
            registry.register("greet", "打招呼",
                    createParameters(new Param("name", "string", "名字")),
                    args -> "你好, " + args.get("name") + "!");

            LlmClient.ToolCall call = new LlmClient.ToolCall("call_1",
                    new LlmClient.ToolCall.Function("greet", "{\"name\": \"Alice\"}"));

            String result = registry.executeTool(call);
            assertEquals("你好, Alice!", result);
        }

        @Test
        @DisplayName("无参数工具：空 JSON 对象")
        void noArgsTool() {
            registry.register("now", "当前时间", createParameters(),
                    args -> "2026-01-01");

            LlmClient.ToolCall call = new LlmClient.ToolCall("call_1",
                    new LlmClient.ToolCall.Function("now", "{}"));

            assertEquals("2026-01-01", registry.executeTool(call));
        }

        @Test
        @DisplayName("调用不存在的工具 → 返回错误消息（不抛异常）")
        void executeNonexistentTool() {
            LlmClient.ToolCall call = new LlmClient.ToolCall("call_1",
                    new LlmClient.ToolCall.Function("unknown_tool", "{}"));

            String result = registry.executeTool(call);
            assertTrue(result.contains("未找到工具"));
            assertTrue(result.contains("unknown_tool"));
        }

        @Test
        @DisplayName("工具执行抛出异常 → 捕获并返回错误消息")
        void toolThrowsException() {
            registry.register("bad_tool", "会报错的工具", createParameters(),
                    args -> { throw new RuntimeException("内部错误"); });

            LlmClient.ToolCall call = new LlmClient.ToolCall("call_1",
                    new LlmClient.ToolCall.Function("bad_tool", "{}"));

            String result = registry.executeTool(call);
            assertTrue(result.contains("执行出错"));
            assertTrue(result.contains("内部错误"));
        }

        @Test
        @DisplayName("参数值为 null（args.get 返回 null）→ 工具自己处理")
        void missingParameter() {
            registry.register("safe_tool", "安全工具",
                    createParameters(new Param("input", "string", "输入")),
                    args -> {
                        String input = args.get("input");
                        return input == null ? "未提供输入" : input;
                    });

            LlmClient.ToolCall call = new LlmClient.ToolCall("call_1",
                    new LlmClient.ToolCall.Function("safe_tool", "{}"));

            String result = registry.executeTool(call);
            assertEquals("未提供输入", result);
        }
    }

    // ==================== 参数解析边界 ====================

    @Nested
    @DisplayName("参数解析边界")
    class ArgumentParsingTests {

        @Test
        @DisplayName("null 参数字符串 → 空 Map")
        void nullArguments() {
            registry.register("tool", "工具", createParameters(),
                    args -> "参数数: " + args.size());

            LlmClient.ToolCall call = new LlmClient.ToolCall("call_1",
                    new LlmClient.ToolCall.Function("tool", null));

            assertEquals("参数数: 0", registry.executeTool(call));
        }

        @Test
        @DisplayName("空字符串参数 → 空 Map")
        void emptyArguments() {
            registry.register("tool", "工具", createParameters(),
                    args -> "参数数: " + args.size());

            LlmClient.ToolCall call = new LlmClient.ToolCall("call_1",
                    new LlmClient.ToolCall.Function("tool", ""));

            assertEquals("参数数: 0", registry.executeTool(call));
        }

        @Test
        @DisplayName("多个参数 → 全部解析")
        void multipleParams() {
            registry.register("tool", "工具",
                    createParameters(
                            new Param("a", "string", "参数A"),
                            new Param("b", "string", "参数B")
                    ),
                    args -> args.get("a") + "+" + args.get("b"));

            LlmClient.ToolCall call = new LlmClient.ToolCall("call_1",
                    new LlmClient.ToolCall.Function("tool", "{\"a\": \"hello\", \"b\": \"world\"}"));

            assertEquals("hello+world", registry.executeTool(call));
        }

        @Test
        @DisplayName("数值参数 → asText() 转换为字符串")
        void numericParam() {
            registry.register("tool", "工具", createParameters(),
                    args -> args.get("count"));

            LlmClient.ToolCall call = new LlmClient.ToolCall("call_1",
                    new LlmClient.ToolCall.Function("tool", "{\"count\": 42}"));

            assertEquals("42", registry.executeTool(call));
        }
    }

    // ==================== getToolDefinitions ====================

    @Nested
    @DisplayName("工具定义转换")
    class DefinitionTests {

        @Test
        @DisplayName("空注册表 → 空列表")
        void emptyRegistry() {
            assertTrue(registry.getToolDefinitions().isEmpty());
        }

        @Test
        @DisplayName("转换后的定义包含名称和描述")
        void definitionContents() {
            registry.register("calc", "计算器",
                    createParameters(new Param("expr", "string", "表达式")),
                    args -> "0");

            List<LlmClient.Tool> defs = registry.getToolDefinitions();
            assertEquals(1, defs.size());

            LlmClient.Tool def = defs.get(0);
            assertEquals("calc", def.name());
            assertEquals("计算器", def.description());
            assertNotNull(def.parameters());
        }
    }

    // ==================== createParameters ====================

    @Nested
    @DisplayName("createParameters JSON Schema 构建")
    class CreateParametersTests {

        @Test
        @DisplayName("无参数 → 空 properties")
        void noParams() {
            JsonNode schema = createParameters();
            assertEquals("object", schema.get("type").asText());
            assertTrue(schema.get("properties").isEmpty());
        }

        @Test
        @DisplayName("必填参数 → 出现在 required 数组中")
        void requiredParam() {
            JsonNode schema = createParameters(
                    new Param("name", "string", "姓名"));  // 默认 required=true

            JsonNode props = schema.get("properties");
            assertNotNull(props.get("name"));
            assertEquals("string", props.get("name").get("type").asText());

            JsonNode required = schema.get("required");
            assertTrue(required.isArray());
            assertEquals("name", required.get(0).asText());
        }

        @Test
        @DisplayName("可选参数 → 不出现在 required 中")
        void optionalParam() {
            JsonNode schema = createParameters(
                    new Param("format", "string", "格式", false));

            JsonNode required = schema.get("required");
            assertEquals(0, required.size());
        }

        @Test
        @DisplayName("混合参数 → 只有必填的在 required 中")
        void mixedParams() {
            JsonNode schema = createParameters(
                    new Param("required_field", "string", "必填"),
                    new Param("optional_field", "string", "可选", false));

            JsonNode required = schema.get("required");
            assertEquals(1, required.size());
            assertEquals("required_field", required.get(0).asText());
        }
    }

    // ==================== 辅助方法 ====================

    private record ToolCallResult(String result) {}

    private ToolCallResult executeWithName(String toolName, String argsJson) {
        LlmClient.ToolCall call = new LlmClient.ToolCall("call_1",
                new LlmClient.ToolCall.Function(toolName, argsJson));
        return new ToolCallResult(registry.executeTool(call));
    }
}
