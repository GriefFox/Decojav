package org.decojav;

import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for step-1 instruction coverage:
 * stack ops, method invocations, field access, object/array creation.
 *
 * Each test compiles a small Java snippet at runtime, decompiles a named
 * method, and asserts the output contains the expected tokens.
 */
class InstructionCoverageTest {

    // -------------------------------------------------------------------------
    // Compile helper
    // -------------------------------------------------------------------------

    /**
     * Compiles {@code source} as {@code <className>.java} and returns the
     * resulting bytecode of the primary class.
     */
    private static byte[] compile(String className, String source) throws Exception {
        Path tmpDir = Files.createTempDirectory("decojav-test-");
        Path srcFile = tmpDir.resolve(className + ".java");
        Files.writeString(srcFile, source);

        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JavaCompiler not available – run with a JDK, not a JRE");

        var errStream = new ByteArrayOutputStream();
        int rc = compiler.run(null, null, new PrintStream(errStream), "-g", srcFile.toString());
        if (rc != 0) {
            fail("Compilation failed:\n" + errStream);
        }

        return Files.readAllBytes(tmpDir.resolve(className + ".class"));
    }

    private static String decompile(byte[] bytes, String method) throws Exception {
        return SimpleMethodDecompiler.decompileMethod(bytes, method);
    }

    // -------------------------------------------------------------------------
    // Static method invocation  (INVOKESTATIC)
    // -------------------------------------------------------------------------

    @Test
    void staticMethodCall() throws Exception {
        byte[] bytes = compile("StaticCall", """
                public class StaticCall {
                    public static int add(int a, int b) { return a + b; }
                    public static int callAdd() { return add(3, 4); }
                }
                """);

        String out = decompile(bytes, "callAdd");
        assertContains(out, "StaticCall.add(");
        assertContains(out, "3");
        assertContains(out, "4");
        assertContains(out, "return");
    }

    // -------------------------------------------------------------------------
    // Virtual method invocation  (INVOKEVIRTUAL)
    // -------------------------------------------------------------------------

    @Test
    void virtualMethodCall() throws Exception {
        byte[] bytes = compile("VirtualCall", """
                public class VirtualCall {
                    public String lower(String s) { return s.toLowerCase(); }
                }
                """);

        String out = decompile(bytes, "lower");
        assertContains(out, ".toLowerCase()");
        assertContains(out, "return");
    }

    // -------------------------------------------------------------------------
    // Void method call emitted as a statement  (INVOKEVIRTUAL + POP pattern)
    // -------------------------------------------------------------------------

    @Test
    void voidMethodCallAsStatement() throws Exception {
        byte[] bytes = compile("VoidCall", """
                public class VoidCall {
                    public void printHello() {
                        System.out.println("hello");
                    }
                }
                """);

        String out = decompile(bytes, "printHello");
        assertContains(out, "System.out");
        assertContains(out, ".println(");
        assertContains(out, "\"hello\"");
    }

    // -------------------------------------------------------------------------
    // Instance field read  (GETFIELD)
    // -------------------------------------------------------------------------

    @Test
    void getInstanceField() throws Exception {
        byte[] bytes = compile("FieldGet", """
                public class FieldGet {
                    int value;
                    public int getValue() { return value; }
                }
                """);

        String out = decompile(bytes, "getValue");
        assertContains(out, "this.value");
        assertContains(out, "return");
    }

    // -------------------------------------------------------------------------
    // Instance field write  (PUTFIELD)
    // -------------------------------------------------------------------------

    @Test
    void putInstanceField() throws Exception {
        byte[] bytes = compile("FieldSet", """
                public class FieldSet {
                    int value;
                    public void setValue(int v) { value = v; }
                }
                """);

        String out = decompile(bytes, "setValue");
        assertContains(out, "this.value");
        assertContains(out, "=");
    }

    // -------------------------------------------------------------------------
    // Static field read  (GETSTATIC)
    // -------------------------------------------------------------------------

    @Test
    void getStaticField() throws Exception {
        byte[] bytes = compile("StaticGet", """
                public class StaticGet {
                    static int MAX = 100;
                    public static int getMax() { return MAX; }
                }
                """);

        String out = decompile(bytes, "getMax");
        assertContains(out, "StaticGet.MAX");
        assertContains(out, "return");
    }

    // -------------------------------------------------------------------------
    // Static field write  (PUTSTATIC)
    // -------------------------------------------------------------------------

    @Test
    void putStaticField() throws Exception {
        byte[] bytes = compile("StaticSet", """
                public class StaticSet {
                    static int MAX = 100;
                    public static void setMax(int v) { MAX = v; }
                }
                """);

        String out = decompile(bytes, "setMax");
        assertContains(out, "StaticSet.MAX");
        assertContains(out, "=");
    }

    // -------------------------------------------------------------------------
    // Object creation  (NEW + INVOKESPECIAL <init>)
    // -------------------------------------------------------------------------

    @Test
    void newObjectNoArgs() throws Exception {
        byte[] bytes = compile("NewObj", """
                public class NewObj {
                    public StringBuilder make() { return new StringBuilder(); }
                }
                """);

        String out = decompile(bytes, "make");
        assertContains(out, "new StringBuilder()");
        assertContains(out, "return");
    }

    @Test
    void newObjectWithArgs() throws Exception {
        byte[] bytes = compile("NewObjArgs", """
                public class NewObjArgs {
                    public StringBuilder make(String s) { return new StringBuilder(s); }
                }
                """);

        String out = decompile(bytes, "make");
        assertContains(out, "new StringBuilder(");
        assertContains(out, "return");
    }

    // -------------------------------------------------------------------------
    // Exception throw  (NEW + INVOKESPECIAL <init> + ATHROW)
    // -------------------------------------------------------------------------

    @Test
    void throwNewException() throws Exception {
        byte[] bytes = compile("Throw", """
                public class Throw {
                    public void check(int v) {
                        if (v < 0) throw new IllegalArgumentException("negative");
                    }
                }
                """);

        String out = decompile(bytes, "check");
        assertContains(out, "throw");
        assertContains(out, "new IllegalArgumentException(");
        assertContains(out, "\"negative\"");
    }

    // -------------------------------------------------------------------------
    // Primitive array creation  (NEWARRAY)
    // -------------------------------------------------------------------------

    @Test
    void newPrimitiveArray() throws Exception {
        byte[] bytes = compile("NewArr", """
                public class NewArr {
                    public int[] create(int n) { return new int[n]; }
                }
                """);

        String out = decompile(bytes, "create");
        assertContains(out, "new int[");
        assertContains(out, "return");
    }

    // -------------------------------------------------------------------------
    // Reference array creation  (ANEWARRAY)
    // -------------------------------------------------------------------------

    @Test
    void newReferenceArray() throws Exception {
        byte[] bytes = compile("NewRefArr", """
                public class NewRefArr {
                    public String[] create(int n) { return new String[n]; }
                }
                """);

        String out = decompile(bytes, "create");
        assertContains(out, "new ");
        assertContains(out, "[");
        assertContains(out, "return");
    }

    // -------------------------------------------------------------------------
    // Array element read  (IALOAD / AALOAD)
    // -------------------------------------------------------------------------

    @Test
    void arrayLoad() throws Exception {
        byte[] bytes = compile("ArrLoad", """
                public class ArrLoad {
                    public int first(int[] arr) { return arr[0]; }
                }
                """);

        String out = decompile(bytes, "first");
        assertContains(out, "[0]");
        assertContains(out, "return");
    }

    @Test
    void arrayLoadWithIndex() throws Exception {
        byte[] bytes = compile("ArrLoadIdx", """
                public class ArrLoadIdx {
                    public int get(int[] arr, int i) { return arr[i]; }
                }
                """);

        String out = decompile(bytes, "get");
        assertContains(out, "[");
        assertContains(out, "return");
    }

    // -------------------------------------------------------------------------
    // Array element write  (IASTORE / AASTORE)
    // -------------------------------------------------------------------------

    @Test
    void arrayStore() throws Exception {
        byte[] bytes = compile("ArrStore", """
                public class ArrStore {
                    public void set(int[] arr, int val) { arr[0] = val; }
                }
                """);

        String out = decompile(bytes, "set");
        assertContains(out, "[0]");
        assertContains(out, "=");
    }

    // -------------------------------------------------------------------------
    // Array length  (ARRAYLENGTH)
    // -------------------------------------------------------------------------

    @Test
    void arrayLength() throws Exception {
        byte[] bytes = compile("ArrLen", """
                public class ArrLen {
                    public int len(int[] arr) { return arr.length; }
                }
                """);

        String out = decompile(bytes, "len");
        assertContains(out, ".length");
        assertContains(out, "return");
    }

    // -------------------------------------------------------------------------
    // instanceof  (INSTANCEOF)
    // -------------------------------------------------------------------------

    @Test
    void instanceofCheck() throws Exception {
        byte[] bytes = compile("InstOf", """
                public class InstOf {
                    public boolean isString(Object obj) { return obj instanceof String; }
                }
                """);

        String out = decompile(bytes, "isString");
        assertContains(out, "instanceof");
        assertContains(out, "String");
        assertContains(out, "return");
    }

    // -------------------------------------------------------------------------
    // DUP / SWAP patterns
    // -------------------------------------------------------------------------

    @Test
    void dupUsedInChainedCall() throws Exception {
        byte[] bytes = compile("Chained", """
                public class Chained {
                    public String chain() {
                        return new StringBuilder().append("hi").toString();
                    }
                }
                """);

        // StringBuilder() + .append("hi") + .toString() — exercises NEW, DUP,
        // INVOKESPECIAL <init>, INVOKEVIRTUAL append, INVOKEVIRTUAL toString
        String out = decompile(bytes, "chain");
        assertContains(out, "new StringBuilder()");
        assertContains(out, ".append(");
        assertContains(out, ".toString()");
        assertContains(out, "return");
    }

    // -------------------------------------------------------------------------
    // Assertion helper
    // -------------------------------------------------------------------------

    private static void assertContains(String output, String token) {
        assertTrue(output.contains(token),
                "Expected output to contain \"" + token + "\"\nActual output:\n" + output);
    }
}
