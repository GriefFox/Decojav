package org.decojav;

import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for step-2: structured control flow reconstruction.
 * Each test compiles a snippet, decompiles a method, and asserts
 * that goto/label statements are gone and the right control structure appears.
 */
class StructuredEmitterTest {

    // -------------------------------------------------------------------------
    // Shared compile helper
    // -------------------------------------------------------------------------

    private static byte[] compile(String className, String source) throws Exception {
        Path tmpDir = Files.createTempDirectory("decojav-cfg-test-");
        Path src    = tmpDir.resolve(className + ".java");
        Files.writeString(src, source);

        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JavaCompiler unavailable");

        var err = new ByteArrayOutputStream();
        int rc = compiler.run(null, null, new PrintStream(err), src.toString());
        if (rc != 0) fail("Compilation failed:\n" + err);

        return Files.readAllBytes(tmpDir.resolve(className + ".class"));
    }

    private static String decompile(byte[] bytes, String method) throws Exception {
        return SimpleMethodDecompiler.decompileMethod(bytes, method);
    }

    private static void noGotos(String out) {
        assertFalse(out.contains("goto "),  "Output should not contain 'goto':\n"  + out);
        assertFalse(out.contains(":\n") && out.contains("goto"),
                "Output should not contain label targets:\n" + out);
    }

    // -------------------------------------------------------------------------
    // while loop
    // -------------------------------------------------------------------------

    @Test
    void whileLoop() throws Exception {
        byte[] bytes = compile("WhileLoop", """
                public class WhileLoop {
                    public static int sumUpTo(int n) {
                        int sum = 0, i = 0;
                        while (i < n) { sum += i; i++; }
                        return sum;
                    }
                }
                """);

        String out = decompile(bytes, "sumUpTo");
        noGotos(out);
        assertTrue(out.contains("while ("), "Expected while loop:\n" + out);
        assertTrue(out.contains("return"),  "Expected return:\n" + out);
    }

    // -------------------------------------------------------------------------
    // for loop  (compiles to same bytecode shape as while)
    // -------------------------------------------------------------------------

    @Test
    void forLoop() throws Exception {
        byte[] bytes = compile("ForLoop", """
                public class ForLoop {
                    public static int factorial(int n) {
                        int r = 1;
                        for (int i = 2; i <= n; i++) r *= i;
                        return r;
                    }
                }
                """);

        String out = decompile(bytes, "factorial");
        noGotos(out);
        assertTrue(out.contains("while ("), "Expected while (for-loop desugars to while):\n" + out);
        assertTrue(out.contains("return"),  "Expected return:\n" + out);
    }

    // -------------------------------------------------------------------------
    // if (no else)
    // -------------------------------------------------------------------------

    @Test
    void ifNoElse() throws Exception {
        byte[] bytes = compile("IfNoElse", """
                public class IfNoElse {
                    public static int abs(int x) {
                        if (x < 0) x = -x;
                        return x;
                    }
                }
                """);

        String out = decompile(bytes, "abs");
        noGotos(out);
        assertTrue(out.contains("if ("),   "Expected if:\n" + out);
        assertFalse(out.contains("} else"), "Expected no else:\n" + out);
        assertTrue(out.contains("return"),  "Expected return:\n" + out);
    }

    // -------------------------------------------------------------------------
    // if-else
    // -------------------------------------------------------------------------

    @Test
    void ifElse() throws Exception {
        byte[] bytes = compile("IfElse", """
                public class IfElse {
                    public static String sign(int x) {
                        if (x > 0) return "positive";
                        else       return "non-positive";
                    }
                }
                """);

        String out = decompile(bytes, "sign");
        noGotos(out);
        assertTrue(out.contains("if ("),    "Expected if:\n" + out);
        assertTrue(out.contains("return"),  "Expected return:\n" + out);
    }

    // -------------------------------------------------------------------------
    // nested if-else (else-if chain)
    // -------------------------------------------------------------------------

    @Test
    void nestedIfElse() throws Exception {
        byte[] bytes = compile("NestedIf", """
                public class NestedIf {
                    public static String classify(int x) {
                        if (x > 0)      return "positive";
                        else if (x < 0) return "negative";
                        else            return "zero";
                    }
                }
                """);

        String out = decompile(bytes, "classify");
        noGotos(out);
        assertTrue(out.contains("if ("),   "Expected if:\n" + out);
        assertTrue(out.contains("return"), "Expected return:\n" + out);
    }

    // -------------------------------------------------------------------------
    // early return inside branch (no else needed)
    // -------------------------------------------------------------------------

    @Test
    void earlyReturn() throws Exception {
        byte[] bytes = compile("EarlyReturn", """
                public class EarlyReturn {
                    public static int safeDiv(int a, int b) {
                        if (b == 0) return 0;
                        return a / b;
                    }
                }
                """);

        String out = decompile(bytes, "safeDiv");
        noGotos(out);
        assertTrue(out.contains("if ("),   "Expected if:\n" + out);
        assertTrue(out.contains("return"), "Expected return:\n" + out);
    }

    // -------------------------------------------------------------------------
    // Loop with early-return break pattern
    // -------------------------------------------------------------------------

    @Test
    void loopWithEarlyReturn() throws Exception {
        byte[] bytes = compile("LoopReturn", """
                public class LoopReturn {
                    public static int findFirst(int[] arr, int target) {
                        for (int i = 0; i < arr.length; i++) {
                            if (arr[i] == target) return i;
                        }
                        return -1;
                    }
                }
                """);

        String out = decompile(bytes, "findFirst");
        noGotos(out);
        assertTrue(out.contains("while ("), "Expected while:\n" + out);
        assertTrue(out.contains("if ("),    "Expected if inside loop:\n" + out);
        assertTrue(out.contains("return"),  "Expected return:\n" + out);
    }

    // -------------------------------------------------------------------------
    // Duplicate variable declarations (same name, different slots)
    // -------------------------------------------------------------------------

    @Test
    void noDuplicateVariableDeclarations() throws Exception {
        // This class uses the same variable name "item" in two separate for-loops.
        // When javac assigns different slots to each "item", both would previously
        // emit "Object item = ..." causing a duplicate-declaration compile error.
        byte[] bytes = compile("DupVars", """
                import java.util.List;
                public class DupVars {
                    public static int countMatches(List<String> list, String target) {
                        int count = 0;
                        for (int i = 0; i < list.size(); i++) {
                            String item = list.get(i);
                            if (item.equals(target)) count++;
                        }
                        for (int j = 0; j < list.size(); j++) {
                            String item = list.get(j);
                            if (item.isEmpty()) count--;
                        }
                        return count;
                    }
                }
                """);

        String out = decompile(bytes, "countMatches");
        noGotos(out);
        // Count occurrences of "item =" (type-annotated declarations look like "Type item =")
        long decls = out.lines()
                .filter(l -> l.matches(".*\\b\\w+ item =.*"))
                .count();
        assertTrue(decls <= 1,
                "Expected at most one type-annotated 'item' declaration, found " + decls + ":\n" + out);
        assertTrue(out.contains("return"), "Expected return:\n" + out);
    }

    // -------------------------------------------------------------------------
    // Ternary assignment: x = cond ? a : b
    // -------------------------------------------------------------------------

    @Test
    void ternaryAssignment() throws Exception {
        byte[] bytes = compile("TernaryAssign", """
                public class TernaryAssign {
                    public static int clamp(int x, int lo, int hi) {
                        int a = x < lo ? lo : x;
                        int b = a > hi ? hi : a;
                        return b;
                    }
                }
                """);

        String out = decompile(bytes, "clamp");
        noGotos(out);
        assertTrue(out.contains("?"),      "Expected ternary operator:\n" + out);
        assertTrue(out.contains(":"),      "Expected ternary colon:\n" + out);
        assertTrue(out.contains("return"), "Expected return:\n" + out);
        // The ternary must not produce empty if/else bodies
        assertFalse(out.contains("} else {\n    }") || out.contains("{\n    }\n"),
                "Expected no empty if/else branches:\n" + out);
    }

    // -------------------------------------------------------------------------
    // Throw inside if (requirePositive pattern)
    // -------------------------------------------------------------------------

    @Test
    void throwInsideIf() throws Exception {
        byte[] bytes = compile("ThrowIf", """
                public class ThrowIf {
                    public static void check(int v) {
                        if (v < 0) throw new IllegalArgumentException("negative");
                    }
                }
                """);

        String out = decompile(bytes, "check");
        noGotos(out);
        assertTrue(out.contains("if ("),   "Expected if:\n" + out);
        assertTrue(out.contains("throw"),  "Expected throw:\n" + out);
    }
}
