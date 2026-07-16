package edu.cqie.paiclidemo.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExpressionParser 单元测试 —— 覆盖运算优先级、边界输入、错误处理。
 * <p>
 * ExpressionParser 是 Main 的包级可见静态内部类，
 * 测试类在同一个包 (edu.cqie.paiclidemo.cli) 下可以直接访问。
 */
class ExpressionParserTest {

    private double eval(String expr) {
        return new Main.ExpressionParser(expr).parse();
    }

    // ==================== 基本运算 ====================

    @Nested
    @DisplayName("基本四则运算")
    class BasicArithmetic {

        @ParameterizedTest
        @CsvSource({
                "1+1, 2",
                "10-3, 7",
                "4*5, 20",
                "15/3, 5",
                "0+0, 0",
                "100-100, 0"
        })
        @DisplayName("加减乘除")
        void basicOps(String expr, double expected) {
            assertEquals(expected, eval(expr), 0.001);
        }

        @Test
        @DisplayName("小数运算")
        void decimalOps() {
            assertEquals(3.14, eval("3.14"), 0.001);
            assertEquals(5.5, eval("2.5+3.0"), 0.001);
            assertEquals(0.1, eval("0.3-0.2"), 0.1);  // 浮点精度宽松
        }

        @Test
        @DisplayName("除法产生小数")
        void divisionWithDecimal() {
            assertEquals(3.333, eval("10/3"), 0.01);
            assertEquals(0.5, eval("1/2"), 0.001);
        }
    }

    // ==================== 运算优先级 ====================

    @Nested
    @DisplayName("运算优先级")
    class Precedence {

        @Test
        @DisplayName("乘法优先于加法：2+3*4 = 14")
        void mulBeforeAdd() {
            assertEquals(14, eval("2+3*4"), 0.001);
        }

        @Test
        @DisplayName("除法优先于减法：10-6/2 = 7")
        void divBeforeSub() {
            assertEquals(7, eval("10-6/2"), 0.001);
        }

        @Test
        @DisplayName("幂运算优先于乘法：2*3**2 = 18")
        void powerBeforeMul() {
            assertEquals(18, eval("2*3**2"), 0.001);
        }

        @Test
        @DisplayName("混合运算：1+2*3**2 = 19")
        void mixedPrecedence() {
            assertEquals(19, eval("1+2*3**2"), 0.001);
        }
    }

    // ==================== 幂运算 ====================

    @Nested
    @DisplayName("幂运算")
    class Power {

        @Test
        @DisplayName("2**10 = 1024")
        void twoPowTen() {
            assertEquals(1024, eval("2**10"), 0.001);
        }

        @Test
        @DisplayName("3**0 = 1")
        void anyToZero() {
            assertEquals(1, eval("3**0"), 0.001);
        }

        @Test
        @DisplayName("5**1 = 5")
        void anyToOne() {
            assertEquals(5, eval("5**1"), 0.001);
        }

        @Test
        @DisplayName("2**0.5 = √2 ≈ 1.414")
        void sqrt() {
            assertEquals(Math.sqrt(2), eval("2**0.5"), 0.001);
        }
    }

    // ==================== 括号 ====================

    @Nested
    @DisplayName("括号")
    class Parentheses {

        @Test
        @DisplayName("(2+3)*4 = 20")
        void overridePrecedence() {
            assertEquals(20, eval("(2+3)*4"), 0.001);
        }

        @Test
        @DisplayName("嵌套括号：((1+2)*(3+4)) = 21")
        void nestedParens() {
            assertEquals(21, eval("((1+2)*(3+4))"), 0.001);
        }

        @Test
        @DisplayName("深层嵌套：(((5))) = 5")
        void deepNesting() {
            assertEquals(5, eval("(((5)))"), 0.001);
        }

        @Test
        @DisplayName("括号与幂：(2+3)**2 = 25")
        void parenWithPower() {
            assertEquals(25, eval("(2+3)**2"), 0.001);
        }
    }

    // ==================== 负数 ====================

    @Nested
    @DisplayName("负数处理")
    class NegativeNumbers {

        @Test
        @DisplayName("-5 = -5")
        void simpleNegative() {
            assertEquals(-5, eval("-5"), 0.001);
        }

        @Test
        @DisplayName("-3+7 = 4")
        void negativePlusPositive() {
            assertEquals(4, eval("-3+7"), 0.001);
        }

        @Test
        @DisplayName("--5 = 5（双重取反）")
        void doubleNegative() {
            assertEquals(5, eval("--5"), 0.001);
        }

        @Test
        @DisplayName("-(3+2) = -5")
        void negativeGroup() {
            assertEquals(-5, eval("-(3+2)"), 0.001);
        }

        @Test
        @DisplayName("2*-3 = -6")
        void multiplyNegative() {
            assertEquals(-6, eval("2*-3"), 0.001);
        }
    }

    // ==================== 空格处理 ====================

    @Nested
    @DisplayName("空格处理")
    class Whitespace {

        @Test
        @DisplayName("含空格：' 2 + 3 ' = 5")
        void withSpaces() {
            assertEquals(5, eval(" 2 + 3 "), 0.001);
        }

        @Test
        @DisplayName("大量空格：'  10  *  5  ' = 50")
        void lotsOfSpaces() {
            assertEquals(50, eval("  10  *  5  "), 0.001);
        }

        @Test
        @DisplayName("Tab 和混合空白")
        void mixedWhitespace() {
            assertEquals(8, eval("\t4 +\t4"), 0.001);
        }
    }

    // ==================== 边界：单个数字 ====================

    @Test
    @DisplayName("单个数字：42")
    void singleNumber() {
        assertEquals(42, eval("42"), 0.001);
    }

    @Test
    @DisplayName("零：0")
    void zero() {
        assertEquals(0, eval("0"), 0.001);
    }

    @Test
    @DisplayName("大数字：999999999")
    void largeNumber() {
        assertEquals(999999999, eval("999999999"), 0.001);
    }

    // ==================== 除法特殊值 ====================

    @Nested
    @DisplayName("除法边界")
    class DivisionEdgeCases {

        @Test
        @DisplayName("除以零 → Infinity")
        void divideByZero() {
            double result = eval("1/0");
            assertTrue(Double.isInfinite(result), "1/0 应为 Infinity");
        }

        @Test
        @DisplayName("0/0 → NaN")
        void zeroDivZero() {
            double result = eval("0/0");
            assertTrue(Double.isNaN(result), "0/0 应为 NaN");
        }

        @Test
        @DisplayName("-1/0 → -Infinity")
        void negativeDivZero() {
            double result = eval("-1/0");
            assertTrue(result == Double.NEGATIVE_INFINITY);
        }
    }

    // ==================== 错误输入 ====================

    @Nested
    @DisplayName("非法输入")
    class InvalidInput {

        @Test
        @DisplayName("空表达式 → 异常")
        void emptyExpression() {
            assertThrows(Exception.class, () -> eval(""));
        }

        @Test
        @DisplayName("只有空格 → 异常")
        void onlySpaces() {
            assertThrows(Exception.class, () -> eval("   "));
        }

        @Test
        @DisplayName("缺少右括号 → 异常")
        void missingClosingParen() {
            assertThrows(Exception.class, () -> eval("(2+3"));
        }

        @Test
        @DisplayName("多余的右括号 → 异常")
        void extraClosingParen() {
            assertThrows(Exception.class, () -> eval("2+3)"));
        }

        @Test
        @DisplayName("连续加号 2++3 → 异常（parseUnary 只处理负号，不处理正号）")
        void consecutivePlus() {
            assertThrows(Exception.class, () -> eval("2++3"));
        }

        @Test
        @DisplayName("孤立小数点 → 异常（NumberFormatException）")
        void loneDot() {
            assertThrows(Exception.class, () -> eval("."));
        }

        @Test
        @DisplayName("双小数点 1.2.3 → 异常")
        void doubleDot() {
            assertThrows(Exception.class, () -> eval("1.2.3"));
        }

        @Test
        @DisplayName("只有运算符 → 异常")
        void onlyOperator() {
            assertThrows(Exception.class, () -> eval("+"));
        }
    }

    // ==================== 复杂表达式 ====================

    @Nested
    @DisplayName("复杂表达式")
    class ComplexExpressions {

        @Test
        @DisplayName("(1+2)*(3+4)/(5-3) = 10.5")
        void complexFormula() {
            assertEquals(10.5, eval("(1+2)*(3+4)/(5-3)"), 0.001);
        }

        @Test
        @DisplayName("2**2**3 —— 解析器不支持链式幂运算（已知局限）")
        void powerAssociativity() {
            // parsePower 只处理一次 **，剩余 "**3" 被 parseTerm 误当作 * 处理，导致异常
            // 这是一个已知的局限性（幂运算不支持右结合链式）
            assertThrows(Exception.class, () -> eval("2**2**3"));
        }

        @Test
        @DisplayName("长表达式：1+2+3+4+5+6+7+8+9+10 = 55")
        void longAdditionChain() {
            assertEquals(55, eval("1+2+3+4+5+6+7+8+9+10"), 0.001);
        }
    }
}
