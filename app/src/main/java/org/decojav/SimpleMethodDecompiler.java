package org.decojav;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.decojav.deobf.ModuleRegistry;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.commons.JSRInlinerAdapter;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public class SimpleMethodDecompiler {

    // Per-method debug names from LocalVariableTable (slot → source name).
    // Populated by decompile() from mn.localVariables; read by StructuredEmitter.
    static final ThreadLocal<Map<Integer, String>> DEBUG_NAMES = ThreadLocal.withInitial(HashMap::new);

    // Simple name of the enclosing class, set by writeClassAsJava so constructors are named correctly.
    static final ThreadLocal<String> ENCLOSING_CLASS = new ThreadLocal<>();

    // Rich type names for object-typed locals when the exact class is inferrable from context.
    // Maps local slot → Java simple type string (e.g., "JPanel", "String[]").
    // Written by handleStore; read by StructuredEmitter.formatAssign.
    static final ThreadLocal<Map<Integer, String>> RICH_TYPES = ThreadLocal.withInitial(HashMap::new);

    // -------------------------------------------------------------------------
    // Primitive type system
    // -------------------------------------------------------------------------

    public enum JType {
        Int("int"),
        Long("long"),
        Float("float"),
        Double("double"),
        Boolean("boolean"),
        Byte("byte"),
        Short("short"),
        Char("char"),
        String("String"),
        Object("Object"),
        Void("void"),
        Unknown("var");

        private final String javaName;

        JType(String javaName) {
            this.javaName = javaName;
        }

        public String javaName() {
            return javaName;
        }
    }

    public enum UnoOp {
        NEG("-");

        private final String symbol;

        UnoOp(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }

    public enum BinOp {
        ADD("+"), SUB("-"), MUL("*"), DIV("/"), REM("%"),
        AND("&"), OR("|"), XOR("^"), SHR(">>"), SHL("<<"), USHR(">>>");

        private final String symbol;

        BinOp(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }

    public enum StoreOp {
        I(JType.Int), L(JType.Long), F(JType.Float), D(JType.Double), A(JType.Object);

        private final JType type;

        StoreOp(JType type) {
            this.type = type;
        }

        public JType type() {
            return type;
        }
    }

    public enum CmpOp {
        EQ("=="), NE("!="), LT("<"), GE(">="), GT(">"), LE("<=");

        private final String symbol;

        CmpOp(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }

    // -------------------------------------------------------------------------
    // Expression IR
    // -------------------------------------------------------------------------

    public sealed interface Expr permits
            ConstExpr, NullExpr, LocalRef,
            BinaryExpr, UnaryExpr, CastExpr, RefCastExpr,
            CmpExpr, CompareResultExpr, TernaryExpr,
            InvokeExpr, InvokeStaticExpr, InvokeDynamicExpr,
            FieldGetExpr, StaticFieldGetExpr,
            NewObjectExpr, NewArrayExpr, MultiNewArrayExpr,
            ArrayLoadExpr, ArrayLengthExpr,
            InstanceofExpr, CaughtExceptionExpr {
        JType type();
    }

    public record ConstExpr(Object value, JType type) implements Expr {}

    public record NullExpr() implements Expr {
        @Override
        public JType type() { return JType.Object; }
    }

    public record LocalRef(int slot, String name, JType type) implements Expr {}

    public record BinaryExpr(BinOp op, Expr left, Expr right) implements Expr {
        @Override
        public JType type() { return left.type(); }
    }

    public record UnaryExpr(UnoOp op, Expr value) implements Expr {
        @Override
        public JType type() { return value.type(); }
    }

    /** Primitive widening/narrowing cast (I2L, D2F, etc.). */
    public record CastExpr(JType targetType, Expr value) implements Expr {
        @Override
        public JType type() { return targetType; }
    }

    /** Reference type cast (CHECKCAST). */
    public record RefCastExpr(String targetType, Expr value) implements Expr {
        @Override
        public JType type() { return JType.Object; }
    }

    public record CmpExpr(CmpOp op, Expr left, Expr right) implements Expr {
        @Override
        public JType type() { return JType.Boolean; }
    }

    /** Intermediate result of LCMP / FCMP* / DCMP* before a branch. */
    public record CompareResultExpr(Expr left, Expr right) implements Expr {
        @Override
        public JType type() { return JType.Int; }
    }

    /** INVOKEVIRTUAL / INVOKESPECIAL / INVOKEINTERFACE. */
    public record InvokeExpr(
            String owner, String name, String desc,
            Expr object, List<Expr> args, JType returnType) implements Expr {
        @Override
        public JType type() { return returnType; }
    }

    /** INVOKESTATIC. */
    public record InvokeStaticExpr(
            String owner, String name, String desc,
            List<Expr> args, JType returnType) implements Expr {
        @Override
        public JType type() { return returnType; }
    }

    /** INVOKEDYNAMIC (lambdas, string concatenation, etc.). */
    public record InvokeDynamicExpr(
            String name, String desc,
            List<Expr> args, JType returnType,
            List<Object> bsmArgs) implements Expr {
        @Override
        public JType type() { return returnType; }
    }

    /** GETFIELD. */
    public record FieldGetExpr(String owner, String name, JType fieldType, Expr object) implements Expr {
        @Override
        public JType type() { return fieldType; }
    }

    /** GETSTATIC. */
    public record StaticFieldGetExpr(String owner, String name, JType fieldType) implements Expr {
        @Override
        public JType type() { return fieldType; }
    }

    /**
     * NEW + INVOKESPECIAL &lt;init&gt; fusion.
     * Mutable so we can fill in constructor arguments after we encounter &lt;init&gt;.
     */
    public static final class NewObjectExpr implements Expr {
        final String className;
        List<Expr> ctorArgs; // null until <init> is processed

        NewObjectExpr(String className) {
            this.className = className;
        }

        public List<Expr> ctorArgs() { return ctorArgs; }

        @Override
        public JType type() { return JType.Object; }

        @Override
        public String toString() {
            return "NewObjectExpr(" + className + ", " + ctorArgs + ")";
        }
    }

    /** NEWARRAY / ANEWARRAY. */
    public record NewArrayExpr(String elementTypeName, Expr size) implements Expr {
        @Override
        public JType type() { return JType.Object; }
    }

    /** MULTIANEWARRAY — e.g. new int[a][b]. descriptor is the full array type like "[[I". */
    public record MultiNewArrayExpr(String descriptor, List<Expr> dimensions) implements Expr {
        @Override
        public JType type() { return JType.Object; }
    }

    /** xALOAD. */
    public record ArrayLoadExpr(Expr array, Expr index, JType elementType) implements Expr {
        @Override
        public JType type() { return elementType; }
    }

    /** ARRAYLENGTH. */
    public record ArrayLengthExpr(Expr array) implements Expr {
        @Override
        public JType type() { return JType.Int; }
    }

    /** INSTANCEOF. */
    public record InstanceofExpr(Expr value, String typeName) implements Expr {
        @Override
        public JType type() { return JType.Boolean; }
    }

    /** Synthetic placeholder for the exception object pushed by the JVM at a catch handler entry. */
    public record CaughtExceptionExpr(String exceptionType) implements Expr {
        @Override
        public JType type() { return JType.Object; }
    }

    /** Ternary expression: {@code condition ? thenVal : elseVal}. */
    public record TernaryExpr(Expr condition, Expr thenVal, Expr elseVal) implements Expr {
        @Override
        public JType type() { return thenVal.type(); }
    }

    // -------------------------------------------------------------------------
    // Statement IR
    // -------------------------------------------------------------------------

    public sealed interface IrStmt permits
            IrLabel, IrAssign, IrReturn, IrVoidReturn, IrGoto, IrIf, IrInc,
            IrExprStmt, IrFieldSet, IrStaticFieldSet, IrArrayStore, IrThrow, IrMonitor,
            IrSwitch, IrStringSwitch, IrTypeSwitch {}

    public record IrLabel(String name) implements IrStmt {}

    public record IrAssign(LocalRef target, Expr value) implements IrStmt {}

    public record IrReturn(Expr value) implements IrStmt {}

    public record IrVoidReturn() implements IrStmt {}

    public record IrGoto(String targetLabel) implements IrStmt {}

    public record IrIf(Expr condition, String trueLabel) implements IrStmt {}

    public record IrInc(LocalRef target, int delta) implements IrStmt {}

    /** Void method call or any expression used purely for its side effects. */
    public record IrExprStmt(Expr expr) implements IrStmt {}

    /** PUTFIELD. */
    public record IrFieldSet(Expr object, String owner, String name, Expr value) implements IrStmt {}

    /** PUTSTATIC. */
    public record IrStaticFieldSet(String owner, String name, Expr value) implements IrStmt {}

    /** xASTORE. */
    public record IrArrayStore(Expr array, Expr index, Expr value) implements IrStmt {}

    /** ATHROW. */
    public record IrThrow(Expr value) implements IrStmt {}

    /** MONITORENTER / MONITOREXIT (synchronized blocks). */
    public record IrMonitor(Expr object, boolean enter) implements IrStmt {}

    /** TABLESWITCH / LOOKUPSWITCH. keys[i] maps to caseLabels[i]; anything else → defaultLabel. */
    public record IrSwitch(Expr value, List<Integer> keys, List<String> caseLabels, String defaultLabel) implements IrStmt {}

    /**
     * Two-phase string switch folded back into a single switch(strVar).
     * stringKeys[i] → caseLabels[i]; anything else → defaultLabel.
     */
    public record IrStringSwitch(Expr value, List<String> stringKeys, List<String> caseLabels, String defaultLabel) implements IrStmt {}

    /**
     * Java 21 pattern-matching switch folded back from invokedynamic typeSwitch.
     * typeNames[i] / varNames[i] / varSlots[i] describe the pattern for caseLabels[i].
     * varSlots[i] == -1 means no pattern variable was captured for that arm.
     */
    public record IrTypeSwitch(
            Expr scrutinee,
            List<String>  typeNames,
            List<String>  varNames,
            List<Integer> varSlots,
            List<String>  caseLabels,
            String        defaultLabel) implements IrStmt {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public static String decompileMethod(Path classFile, String methodName) throws Exception {
        return decompileMethod(Files.readAllBytes(classFile), methodName);
    }

    public static String decompileMethod(byte[] bytes, String methodName) throws Exception {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);

        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals(methodName)) continue;
            return decompile(mn);
        }

        throw new IllegalArgumentException("Method not found: " + methodName);
    }

    /** Decompile a MethodNode directly (avoids re-reading class bytes). */
    public static String decompileMethodNode(MethodNode mn) {
        return decompile(mn);
    }

    // -------------------------------------------------------------------------
    // Core decompilation
    // -------------------------------------------------------------------------

    private static String decompile(MethodNode mn) {
        if (hasJsr(mn)) mn = inlineJsr(mn);

        // Stage 4: MethodNode modules (junk removal, opaque-predicate stripping, etc.)
        ModuleRegistry.INSTANCE.applyMethodNode(mn);
        Deque<Expr> stack = new ArrayDeque<>();
        Map<Integer, LocalRef> locals = new HashMap<>();
        Map<Integer, JType> localTypes = new HashMap<>();
        List<IrStmt> ir = new ArrayList<>();
        Map<LabelNode, String> labelNames = new HashMap<>();

        // Load debug names from LocalVariableTable (present when compiled with -g)
        Map<Integer, String> dbg = DEBUG_NAMES.get();
        dbg.clear();
        RICH_TYPES.get().clear();
        if (mn.localVariables != null) {
            for (LocalVariableNode lv : mn.localVariables) {
                dbg.put(lv.index, lv.name);
            }
        }

        initMethodArguments(mn, locals, localTypes);

        // Bug fix: at exception handler entry points the JVM pushes the caught exception.
        // Build a map so we can detect these labels and push a placeholder.
        Map<LabelNode, String> handlerLabelTypes = new HashMap<>();
        if (mn.tryCatchBlocks != null) {
            for (var tcb : mn.tryCatchBlocks) {
                handlerLabelTypes.put(tcb.handler,
                        tcb.type != null ? slashToDot(tcb.type) : "Throwable");
            }
        }

        // Tracks merge labels for the inline-ternary pattern (ternary value passed directly
        // to a call at the merge point rather than stored to a variable first).
        // Maps the GOTO target LabelNode → synthetic local slot holding the then-arm value.
        Map<LabelNode, Integer> mergeTernarySlot = new HashMap<>();
        int syntheticSlot = mn.maxLocals;

        for (AbstractInsnNode insn : mn.instructions) {
            if (insn instanceof LabelNode label) {
                ir.add(new IrLabel(labelName(label, labelNames)));
                // At exception handler entries the JVM pushes the caught exception.
                // Clear any stale stack state and push a placeholder so ASTORE succeeds.
                String exType = handlerLabelTypes.get(label);
                if (exType != null) {
                    stack.clear();
                    stack.push(new CaughtExceptionExpr(exType));
                } else {
                    // Inline ternary merge: the GOTO handler stored the then-arm value into a
                    // synthetic slot and recorded this label. Now the else-arm value is on top
                    // of the stack — capture it into the same slot and push the slot ref back
                    // so the subsequent call (INVOKEVIRTUAL etc.) uses it as its argument.
                    Integer synSlot = mergeTernarySlot.remove(label);
                    if (synSlot != null && !stack.isEmpty()) {
                        Expr elseVal = stack.pop();
                        localTypes.put(synSlot, elseVal.type());
                        LocalRef synRef = localFor(synSlot, locals, localTypes);
                        ir.add(new IrAssign(synRef, elseVal));
                        stack.push(synRef);
                    }
                }
                continue;
            }

            int op = insn.getOpcode();
            if (op < 0) continue; // pseudo-nodes: frames, line numbers

            switch (op) {

                // ---- null ----
                case Opcodes.ACONST_NULL -> stack.push(new NullExpr());

                // ---- int constants ----
                case Opcodes.ICONST_M1 -> stack.push(new ConstExpr(-1, JType.Int));
                case Opcodes.ICONST_0  -> stack.push(new ConstExpr(0, JType.Int));
                case Opcodes.ICONST_1  -> stack.push(new ConstExpr(1, JType.Int));
                case Opcodes.ICONST_2  -> stack.push(new ConstExpr(2, JType.Int));
                case Opcodes.ICONST_3  -> stack.push(new ConstExpr(3, JType.Int));
                case Opcodes.ICONST_4  -> stack.push(new ConstExpr(4, JType.Int));
                case Opcodes.ICONST_5  -> stack.push(new ConstExpr(5, JType.Int));

                // ---- long/float/double constants ----
                case Opcodes.LCONST_0 -> stack.push(new ConstExpr(0L, JType.Long));
                case Opcodes.LCONST_1 -> stack.push(new ConstExpr(1L, JType.Long));
                case Opcodes.FCONST_0 -> stack.push(new ConstExpr(0.0f, JType.Float));
                case Opcodes.FCONST_1 -> stack.push(new ConstExpr(1.0f, JType.Float));
                case Opcodes.FCONST_2 -> stack.push(new ConstExpr(2.0f, JType.Float));
                case Opcodes.DCONST_0 -> stack.push(new ConstExpr(0.0, JType.Double));
                case Opcodes.DCONST_1 -> stack.push(new ConstExpr(1.0, JType.Double));

                case Opcodes.BIPUSH, Opcodes.SIPUSH -> {
                    IntInsnNode n = (IntInsnNode) insn;
                    stack.push(new ConstExpr(n.operand, JType.Int));
                }

                case Opcodes.LDC -> {
                    LdcInsnNode n = (LdcInsnNode) insn;
                    stack.push(new ConstExpr(n.cst, inferConstType(n.cst)));
                }

                // ---- local loads ----
                case Opcodes.ILOAD -> {
                    VarInsnNode n = (VarInsnNode) insn;
                    localTypes.putIfAbsent(n.var, JType.Int);
                    stack.push(localFor(n.var, locals, localTypes));
                }
                case Opcodes.LLOAD -> {
                    VarInsnNode n = (VarInsnNode) insn;
                    localTypes.putIfAbsent(n.var, JType.Long);
                    stack.push(localFor(n.var, locals, localTypes));
                }
                case Opcodes.FLOAD -> {
                    VarInsnNode n = (VarInsnNode) insn;
                    localTypes.putIfAbsent(n.var, JType.Float);
                    stack.push(localFor(n.var, locals, localTypes));
                }
                case Opcodes.DLOAD -> {
                    VarInsnNode n = (VarInsnNode) insn;
                    localTypes.putIfAbsent(n.var, JType.Double);
                    stack.push(localFor(n.var, locals, localTypes));
                }
                case Opcodes.ALOAD -> {
                    VarInsnNode n = (VarInsnNode) insn;
                    localTypes.putIfAbsent(n.var, JType.Object);
                    stack.push(localFor(n.var, locals, localTypes));
                }

                // ---- local stores ----
                case Opcodes.ISTORE -> handleStore(stack, ir, locals, localTypes, ((VarInsnNode) insn).var, StoreOp.I);
                case Opcodes.LSTORE -> handleStore(stack, ir, locals, localTypes, ((VarInsnNode) insn).var, StoreOp.L);
                case Opcodes.FSTORE -> handleStore(stack, ir, locals, localTypes, ((VarInsnNode) insn).var, StoreOp.F);
                case Opcodes.DSTORE -> handleStore(stack, ir, locals, localTypes, ((VarInsnNode) insn).var, StoreOp.D);
                case Opcodes.ASTORE -> handleStore(stack, ir, locals, localTypes, ((VarInsnNode) insn).var, StoreOp.A);

                // ---- array loads ----
                case Opcodes.IALOAD -> { Expr i = stack.pop(); stack.push(new ArrayLoadExpr(stack.pop(), i, JType.Int)); }
                case Opcodes.LALOAD -> { Expr i = stack.pop(); stack.push(new ArrayLoadExpr(stack.pop(), i, JType.Long)); }
                case Opcodes.FALOAD -> { Expr i = stack.pop(); stack.push(new ArrayLoadExpr(stack.pop(), i, JType.Float)); }
                case Opcodes.DALOAD -> { Expr i = stack.pop(); stack.push(new ArrayLoadExpr(stack.pop(), i, JType.Double)); }
                case Opcodes.AALOAD -> { Expr i = stack.pop(); stack.push(new ArrayLoadExpr(stack.pop(), i, JType.Object)); }
                case Opcodes.BALOAD -> { Expr i = stack.pop(); stack.push(new ArrayLoadExpr(stack.pop(), i, JType.Byte)); }
                case Opcodes.CALOAD -> { Expr i = stack.pop(); stack.push(new ArrayLoadExpr(stack.pop(), i, JType.Char)); }
                case Opcodes.SALOAD -> { Expr i = stack.pop(); stack.push(new ArrayLoadExpr(stack.pop(), i, JType.Short)); }

                // ---- array stores ----
                case Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE,
                     Opcodes.AASTORE, Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE -> {
                    Expr value = stack.pop();
                    Expr index = stack.pop();
                    Expr array = stack.pop();
                    ir.add(new IrArrayStore(array, index, value));
                }

                // ---- stack manipulation ----
                case Opcodes.POP -> {
                    Expr top = stack.pop();
                    if (hasSideEffects(top)) ir.add(new IrExprStmt(top));
                }
                case Opcodes.POP2 -> {
                    // Category-2 value (long/double) counts as two slots
                    Expr top = stack.pop();
                    if (hasSideEffects(top)) ir.add(new IrExprStmt(top));
                    // If top was category-1, pop a second value
                    if (top.type() != JType.Long && top.type() != JType.Double && !stack.isEmpty()) {
                        Expr second = stack.pop();
                        if (hasSideEffects(second)) ir.add(new IrExprStmt(second));
                    }
                }
                case Opcodes.DUP -> stack.push(stack.peek());
                case Opcodes.DUP_X1 -> {
                    Expr v1 = stack.pop();
                    Expr v2 = stack.pop();
                    stack.push(v1);
                    stack.push(v2);
                    stack.push(v1);
                }
                case Opcodes.DUP_X2 -> {
                    Expr v1 = stack.pop();
                    Expr v2 = stack.pop();
                    Expr v3 = stack.pop();
                    stack.push(v1);
                    stack.push(v3);
                    stack.push(v2);
                    stack.push(v1);
                }
                case Opcodes.DUP2 -> {
                    Expr v1 = stack.pop();
                    Expr v2 = stack.pop();
                    stack.push(v2);
                    stack.push(v1);
                    stack.push(v2);
                    stack.push(v1);
                }
                case Opcodes.DUP2_X1 -> {
                    Expr v1 = stack.pop();
                    Expr v2 = stack.pop();
                    Expr v3 = stack.pop();
                    stack.push(v2);
                    stack.push(v1);
                    stack.push(v3);
                    stack.push(v2);
                    stack.push(v1);
                }
                case Opcodes.DUP2_X2 -> {
                    Expr v1 = stack.pop();
                    Expr v2 = stack.pop();
                    Expr v3 = stack.pop();
                    Expr v4 = stack.pop();
                    stack.push(v2);
                    stack.push(v1);
                    stack.push(v4);
                    stack.push(v3);
                    stack.push(v2);
                    stack.push(v1);
                }
                case Opcodes.SWAP -> {
                    Expr v1 = stack.pop();
                    Expr v2 = stack.pop();
                    stack.push(v1);
                    stack.push(v2);
                }

                // ---- arithmetic ----
                case Opcodes.IADD, Opcodes.LADD, Opcodes.FADD, Opcodes.DADD -> pushBinary(stack, BinOp.ADD);
                case Opcodes.ISUB, Opcodes.LSUB, Opcodes.FSUB, Opcodes.DSUB -> pushBinary(stack, BinOp.SUB);
                case Opcodes.IMUL, Opcodes.LMUL, Opcodes.FMUL, Opcodes.DMUL -> pushBinary(stack, BinOp.MUL);
                case Opcodes.IDIV, Opcodes.LDIV, Opcodes.FDIV, Opcodes.DDIV -> pushBinary(stack, BinOp.DIV);
                case Opcodes.IREM, Opcodes.LREM, Opcodes.FREM, Opcodes.DREM -> pushBinary(stack, BinOp.REM);
                case Opcodes.INEG, Opcodes.LNEG, Opcodes.FNEG, Opcodes.DNEG -> pushUno(stack, UnoOp.NEG);

                // ---- bitwise / shift ----
                case Opcodes.ISHL,  Opcodes.LSHL  -> pushBinary(stack, BinOp.SHL);
                case Opcodes.ISHR,  Opcodes.LSHR  -> pushBinary(stack, BinOp.SHR);
                case Opcodes.IUSHR, Opcodes.LUSHR -> pushBinary(stack, BinOp.USHR);
                case Opcodes.IAND,  Opcodes.LAND  -> pushBinary(stack, BinOp.AND);
                case Opcodes.IOR,   Opcodes.LOR   -> pushBinary(stack, BinOp.OR);
                case Opcodes.IXOR,  Opcodes.LXOR  -> pushBinary(stack, BinOp.XOR);

                case Opcodes.IINC -> {
                    IincInsnNode n = (IincInsnNode) insn;
                    localTypes.putIfAbsent(n.var, JType.Int);
                    ir.add(new IrInc(localFor(n.var, locals, localTypes), n.incr));
                }

                // ---- primitive type conversions ----
                case Opcodes.I2L -> { Expr v = stack.pop(); stack.push(new CastExpr(JType.Long,   v)); }
                case Opcodes.I2F -> { Expr v = stack.pop(); stack.push(new CastExpr(JType.Float,  v)); }
                case Opcodes.I2D -> { Expr v = stack.pop(); stack.push(new CastExpr(JType.Double, v)); }
                case Opcodes.L2I -> { Expr v = stack.pop(); stack.push(new CastExpr(JType.Int,    v)); }
                case Opcodes.L2F -> { Expr v = stack.pop(); stack.push(new CastExpr(JType.Float,  v)); }
                case Opcodes.L2D -> { Expr v = stack.pop(); stack.push(new CastExpr(JType.Double, v)); }
                case Opcodes.F2I -> { Expr v = stack.pop(); stack.push(new CastExpr(JType.Int,    v)); }
                case Opcodes.F2L -> { Expr v = stack.pop(); stack.push(new CastExpr(JType.Long,   v)); }
                case Opcodes.F2D -> { Expr v = stack.pop(); stack.push(new CastExpr(JType.Double, v)); }
                case Opcodes.D2I -> { Expr v = stack.pop(); stack.push(new CastExpr(JType.Int,    v)); }
                case Opcodes.D2L -> { Expr v = stack.pop(); stack.push(new CastExpr(JType.Long,   v)); }
                case Opcodes.D2F -> { Expr v = stack.pop(); stack.push(new CastExpr(JType.Float,  v)); }
                case Opcodes.I2B -> { Expr v = stack.pop(); stack.push(new CastExpr(JType.Byte,   v)); }
                case Opcodes.I2C -> { Expr v = stack.pop(); stack.push(new CastExpr(JType.Char,   v)); }
                case Opcodes.I2S -> { Expr v = stack.pop(); stack.push(new CastExpr(JType.Short,  v)); }

                // ---- comparison (produce int for branch) ----
                case Opcodes.LCMP, Opcodes.FCMPL, Opcodes.FCMPG, Opcodes.DCMPL, Opcodes.DCMPG -> {
                    Expr right = stack.pop();
                    Expr left  = stack.pop();
                    stack.push(new CompareResultExpr(left, right));
                }

                // ---- branches ----
                case Opcodes.GOTO -> {
                    JumpInsnNode n = (JumpInsnNode) insn;
                    // javac compiles ternaries and switch expressions as:
                    //   push value; GOTO merge; ...; merge: RETURN/STORE
                    // The linear scan would otherwise accumulate stale values from all arms.
                    // When exactly one value sits on the stack:
                    //   (a) merge target is a return → convert GOTO to IrReturn
                    //   (b) merge target is a store  → pre-emit the assignment now so the
                    //       then-arm value is captured before the else-arm overwrites the stack
                    // When more than one value is on the stack, a receiver/arg was pre-pushed
                    // for a call at the merge point (inline ternary: method(cond ? a : b)).
                    // Capture the top (then-arm value) into a fresh synthetic slot, leaving
                    // the pre-pushed items intact. The merge label handler will capture the
                    // else-arm value into the same slot so foldTernaries can fold them.
                    if (stack.size() == 1 && isGotoTargetReturn(n.label)) {
                        ir.add(new IrReturn(stack.pop()));
                    } else {
                        if (stack.size() == 1) {
                            VarInsnNode mergeStore = findGotoTargetStore(n.label);
                            if (mergeStore != null) {
                                handleStore(stack, ir, locals, localTypes,
                                            mergeStore.var, storeOpFor(mergeStore.getOpcode()));
                            }
                        } else if (stack.size() > 1) {
                            int syn = syntheticSlot++;
                            Expr thenVal = stack.pop();
                            localTypes.put(syn, thenVal.type());
                            ir.add(new IrAssign(localFor(syn, locals, localTypes), thenVal));
                            mergeTernarySlot.put(n.label, syn);
                        }
                        ir.add(new IrGoto(labelName(n.label, labelNames)));
                    }
                }
                case Opcodes.IFEQ -> emitIfZero(stack, ir, (JumpInsnNode) insn, CmpOp.EQ, labelNames);
                case Opcodes.IFNE -> emitIfZero(stack, ir, (JumpInsnNode) insn, CmpOp.NE, labelNames);
                case Opcodes.IFLT -> emitIfZero(stack, ir, (JumpInsnNode) insn, CmpOp.LT, labelNames);
                case Opcodes.IFGE -> emitIfZero(stack, ir, (JumpInsnNode) insn, CmpOp.GE, labelNames);
                case Opcodes.IFGT -> emitIfZero(stack, ir, (JumpInsnNode) insn, CmpOp.GT, labelNames);
                case Opcodes.IFLE -> emitIfZero(stack, ir, (JumpInsnNode) insn, CmpOp.LE, labelNames);

                case Opcodes.IF_ICMPEQ -> emitIfICmp(stack, ir, (JumpInsnNode) insn, CmpOp.EQ, labelNames);
                case Opcodes.IF_ICMPNE -> emitIfICmp(stack, ir, (JumpInsnNode) insn, CmpOp.NE, labelNames);
                case Opcodes.IF_ICMPLT -> emitIfICmp(stack, ir, (JumpInsnNode) insn, CmpOp.LT, labelNames);
                case Opcodes.IF_ICMPGE -> emitIfICmp(stack, ir, (JumpInsnNode) insn, CmpOp.GE, labelNames);
                case Opcodes.IF_ICMPGT -> emitIfICmp(stack, ir, (JumpInsnNode) insn, CmpOp.GT, labelNames);
                case Opcodes.IF_ICMPLE -> emitIfICmp(stack, ir, (JumpInsnNode) insn, CmpOp.LE, labelNames);

                case Opcodes.IF_ACMPEQ -> {
                    Expr right = stack.pop(); Expr left = stack.pop();
                    ir.add(new IrIf(new CmpExpr(CmpOp.EQ, left, right), labelName(((JumpInsnNode) insn).label, labelNames)));
                }
                case Opcodes.IF_ACMPNE -> {
                    Expr right = stack.pop(); Expr left = stack.pop();
                    ir.add(new IrIf(new CmpExpr(CmpOp.NE, left, right), labelName(((JumpInsnNode) insn).label, labelNames)));
                }
                case Opcodes.IFNULL -> {
                    Expr v = stack.pop();
                    ir.add(new IrIf(new CmpExpr(CmpOp.EQ, v, new NullExpr()), labelName(((JumpInsnNode) insn).label, labelNames)));
                }
                case Opcodes.IFNONNULL -> {
                    Expr v = stack.pop();
                    ir.add(new IrIf(new CmpExpr(CmpOp.NE, v, new NullExpr()), labelName(((JumpInsnNode) insn).label, labelNames)));
                }

                // ---- returns ----
                case Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.ARETURN -> {
                    // Guard: stack can be empty when GOTO→IrReturn already consumed the value
                    if (!stack.isEmpty()) ir.add(new IrReturn(stack.pop()));
                }
                case Opcodes.RETURN -> ir.add(new IrVoidReturn());

                // ---- field access ----
                case Opcodes.GETFIELD -> {
                    FieldInsnNode n = (FieldInsnNode) insn;
                    Expr object = stack.pop();
                    stack.push(new FieldGetExpr(slashToDot(n.owner), n.name, fromAsmType(Type.getType(n.desc)), object));
                }
                case Opcodes.PUTFIELD -> {
                    FieldInsnNode n = (FieldInsnNode) insn;
                    Expr value  = stack.pop();
                    Expr object = stack.pop();
                    if ("Z".equals(n.desc)) value = coerceBool(value);
                    ir.add(new IrFieldSet(object, slashToDot(n.owner), n.name, value));
                }
                case Opcodes.GETSTATIC -> {
                    FieldInsnNode n = (FieldInsnNode) insn;
                    stack.push(new StaticFieldGetExpr(slashToDot(n.owner), n.name, fromAsmType(Type.getType(n.desc))));
                }
                case Opcodes.PUTSTATIC -> {
                    FieldInsnNode n = (FieldInsnNode) insn;
                    Expr value = stack.pop();
                    if ("Z".equals(n.desc)) value = coerceBool(value);
                    ir.add(new IrStaticFieldSet(slashToDot(n.owner), n.name, value));
                }

                // ---- method invocation ----
                case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE -> {
                    MethodInsnNode n = (MethodInsnNode) insn;
                    List<Expr> args = popArgs(stack, n.desc);
                    Expr object = stack.pop();
                    JType ret = fromAsmType(Type.getReturnType(n.desc));
                    InvokeExpr invoke = new InvokeExpr(slashToDot(n.owner), n.name, n.desc, object, args, ret);
                    if (ret == JType.Void) ir.add(new IrExprStmt(invoke)); else stack.push(invoke);
                }
                case Opcodes.INVOKESPECIAL -> {
                    MethodInsnNode n = (MethodInsnNode) insn;
                    List<Expr> args = popArgs(stack, n.desc);
                    Expr object = stack.pop();

                    if (n.name.equals("<init>") && object instanceof NewObjectExpr newObj) {
                        // Fuse NEW + <init> into a single constructor expression.
                        // The DUP'd reference already sits beneath on the stack.
                        newObj.ctorArgs = args;
                    } else {
                        JType ret = fromAsmType(Type.getReturnType(n.desc));
                        InvokeExpr invoke = new InvokeExpr(slashToDot(n.owner), n.name, n.desc, object, args, ret);
                        if (ret == JType.Void) ir.add(new IrExprStmt(invoke)); else stack.push(invoke);
                    }
                }
                case Opcodes.INVOKESTATIC -> {
                    MethodInsnNode n = (MethodInsnNode) insn;
                    List<Expr> args = popArgs(stack, n.desc);
                    JType ret = fromAsmType(Type.getReturnType(n.desc));
                    InvokeStaticExpr invoke = new InvokeStaticExpr(slashToDot(n.owner), n.name, n.desc, args, ret);
                    if (ret == JType.Void) ir.add(new IrExprStmt(invoke)); else stack.push(invoke);
                }
                case Opcodes.INVOKEDYNAMIC -> {
                    InvokeDynamicInsnNode n = (InvokeDynamicInsnNode) insn;
                    List<Expr> args = popArgs(stack, n.desc);
                    JType ret = fromAsmType(Type.getReturnType(n.desc));
                    List<Object> bsmArgs = n.bsmArgs != null ? List.of(n.bsmArgs) : List.of();
                    InvokeDynamicExpr invoke = new InvokeDynamicExpr(n.name, n.desc, args, ret, bsmArgs);
                    if (ret == JType.Void) ir.add(new IrExprStmt(invoke)); else stack.push(invoke);
                }

                // ---- object / array creation ----
                case Opcodes.NEW -> {
                    TypeInsnNode n = (TypeInsnNode) insn;
                    stack.push(new NewObjectExpr(slashToDot(n.desc)));
                }
                case Opcodes.NEWARRAY -> {
                    IntInsnNode n = (IntInsnNode) insn;
                    String typeName = switch (n.operand) {
                        case 4  -> "boolean";
                        case 5  -> "char";
                        case 6  -> "float";
                        case 7  -> "double";
                        case 8  -> "byte";
                        case 9  -> "short";
                        case 10 -> "int";
                        case 11 -> "long";
                        default -> "unknown";
                    };
                    stack.push(new NewArrayExpr(typeName, stack.pop()));
                }
                case Opcodes.ANEWARRAY -> {
                    TypeInsnNode n = (TypeInsnNode) insn;
                    stack.push(new NewArrayExpr(simpleClassName(slashToDot(n.desc)), stack.pop()));
                }

                // ---- array length ----
                case Opcodes.ARRAYLENGTH -> stack.push(new ArrayLengthExpr(stack.pop()));

                // ---- type checks / casts ----
                case Opcodes.CHECKCAST -> {
                    TypeInsnNode n = (TypeInsnNode) insn;
                    Expr value = stack.pop();
                    stack.push(new RefCastExpr(slashToDot(n.desc), value));
                }
                case Opcodes.INSTANCEOF -> {
                    TypeInsnNode n = (TypeInsnNode) insn;
                    stack.push(new InstanceofExpr(stack.pop(), slashToDot(n.desc)));
                }

                // ---- exception ----
                case Opcodes.ATHROW -> ir.add(new IrThrow(stack.pop()));

                // ---- synchronized ----
                case Opcodes.MONITORENTER -> ir.add(new IrMonitor(stack.pop(), true));
                case Opcodes.MONITOREXIT  -> ir.add(new IrMonitor(stack.pop(), false));

                // ---- NOP ----
                case Opcodes.NOP -> { /* intentionally empty */ }

                // ---- switch ----
                case Opcodes.TABLESWITCH -> {
                    TableSwitchInsnNode n = (TableSwitchInsnNode) insn;
                    Expr value = stack.pop();
                    List<Integer> keys = new ArrayList<>();
                    List<String> caseLabels = new ArrayList<>();
                    for (int k = n.min; k <= n.max; k++) {
                        keys.add(k);
                        caseLabels.add(labelName(n.labels.get(k - n.min), labelNames));
                    }
                    ir.add(new IrSwitch(value, keys, caseLabels, labelName(n.dflt, labelNames)));
                }
                case Opcodes.LOOKUPSWITCH -> {
                    LookupSwitchInsnNode n = (LookupSwitchInsnNode) insn;
                    Expr value = stack.pop();
                    List<String> caseLabels = new ArrayList<>();
                    for (LabelNode lbl : n.labels) caseLabels.add(labelName(lbl, labelNames));
                    ir.add(new IrSwitch(value, new ArrayList<>(n.keys), caseLabels,
                            labelName(n.dflt, labelNames)));
                }

                // ---- multi-dimensional array ----
                case Opcodes.MULTIANEWARRAY -> {
                    MultiANewArrayInsnNode n = (MultiANewArrayInsnNode) insn;
                    List<Expr> dims = new ArrayList<>();
                    for (int d = 0; d < n.dims; d++) dims.add(0, stack.pop());
                    stack.push(new MultiNewArrayExpr(n.desc, dims));
                }

                default -> throw new UnsupportedOperationException("Unsupported opcode: " + op);
            }
        }

        // Stage 5: IR modules (constant folding, dead-store removal, string decryption, etc.)
        ModuleRegistry.INSTANCE.applyIr(ir);

        // Stage 5b: fold invokedynamic patterns back into readable Java
        foldStringSwitches(ir);
        foldTypeSwitches(ir);
        foldTernaries(ir);
        foldSwitchFallthroughArm(ir);
        foldArrayInits(ir);

        StructuredEmitter.DECLARED.get().clear();
        StructuredEmitter.DECLARED_NAMES.get().clear();
        StructuredEmitter.SLOT_RENAME.get().clear();
        return StructuredEmitter.emit(mn, ir);
    }

    // -------------------------------------------------------------------------
    // Expression printer
    // -------------------------------------------------------------------------

    /** Called by StructuredEmitter. */
    public static String printExprPublic(Expr expr) { return printExpr(expr); }

    /** Called by StructuredEmitter. */
    public static JType fromAsmTypePublic(Type type) { return fromAsmType(type); }

    private static String printExpr(Expr expr) {
        return switch (expr) {
            case ConstExpr c         -> formatConst(c.value());
            case NullExpr n          -> "null";
            case LocalRef l          -> StructuredEmitter.SLOT_RENAME.get().getOrDefault(l.slot(), l.name());
            case CastExpr c          -> "(" + c.targetType().javaName() + ") " + printExpr(c.value());
            case RefCastExpr c       -> "((" + simpleClassName(c.targetType()) + ") " + printExpr(c.value()) + ")";
            case UnaryExpr u         -> u.op().symbol() + printExpr(u.value());
            case BinaryExpr b        -> "(" + printExpr(b.left()) + " " + b.op().symbol() + " " + printExpr(b.right()) + ")";
            case CmpExpr c           -> foldCmp(c);
            case CompareResultExpr c -> "cmp(" + printExpr(c.left()) + ", " + printExpr(c.right()) + ")";
            case TernaryExpr t       -> printExpr(t.condition()) + " ? " + printExpr(t.thenVal()) + " : " + printExpr(t.elseVal());

            case InvokeExpr i when "<init>".equals(i.name()) -> {
                // super() or this() constructor delegation
                String recv = printExpr(i.object());
                yield (recv.equals("this") ? "super" : recv) + "(" + joinExprs(i.args()) + ")";
            }
            case InvokeExpr i        -> printExpr(i.object()) + "." + i.name() + "(" + joinExprs(i.args()) + ")";
            case InvokeStaticExpr i  -> simpleClassName(i.owner()) + "." + i.name() + "(" + joinExprs(i.args()) + ")";
            case InvokeDynamicExpr i when "makeConcatWithConstants".equals(i.name()) -> foldStringConcat(i);
            case InvokeDynamicExpr i when "makeConcat".equals(i.name()) ->
                    i.args().stream().map(SimpleMethodDecompiler::printExpr)
                            .reduce((a, b) -> a + " + " + b).orElse("\"\"");
            case InvokeDynamicExpr i when isLambdaBootstrap(i) -> foldLambdaRef(i);
            case InvokeDynamicExpr i -> "invokedynamic:" + i.name() + "(" + joinExprs(i.args()) + ")";

            case FieldGetExpr f      -> printExpr(f.object()) + "." + f.name();
            case StaticFieldGetExpr f -> simpleClassName(f.owner()) + "." + f.name();

            case NewObjectExpr n -> {
                String args = n.ctorArgs == null ? "?" : joinExprs(n.ctorArgs);
                yield "new " + simpleClassName(n.className) + "(" + args + ")";
            }
            case NewArrayExpr n      -> "new " + n.elementTypeName() + "[" + printExpr(n.size()) + "]";
            case MultiNewArrayExpr m -> {
                String d = m.descriptor();
                int rank = 0;
                while (rank < d.length() && d.charAt(rank) == '[') rank++;
                String base = d.substring(rank);
                String baseType = switch (base.charAt(0)) {
                    case 'I' -> "int";    case 'J' -> "long";
                    case 'F' -> "float";  case 'D' -> "double";
                    case 'B' -> "byte";   case 'C' -> "char";
                    case 'S' -> "short";  case 'Z' -> "boolean";
                    default  -> simpleClassName(slashToDot(base.substring(1, base.length() - 1)));
                };
                StringBuilder sb2 = new StringBuilder("new ").append(baseType);
                for (Expr dim : m.dimensions()) sb2.append("[").append(printExpr(dim)).append("]");
                for (int i = m.dimensions().size(); i < rank; i++) sb2.append("[]");
                yield sb2.toString();
            }
            case ArrayLoadExpr a     -> printExpr(a.array()) + "[" + printExpr(a.index()) + "]";
            case ArrayLengthExpr a   -> printExpr(a.array()) + ".length";
            case InstanceofExpr i    -> printExpr(i.value()) + " instanceof " + simpleClassName(i.typeName());
            case CaughtExceptionExpr c -> "_ex";
        };
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Simplifies comparisons against 0 where the left side is already boolean-like.
     * e.g. {@code instanceof T != 0} -&gt; {@code instanceof T}
     */
    private static String foldCmp(CmpExpr c) {
        boolean rightIsZero = c.right() instanceof ConstExpr ce && Integer.valueOf(0).equals(ce.value());
        if (rightIsZero) {
            boolean lhsIsBool = c.left() instanceof InstanceofExpr
                    || c.left().type() == JType.Boolean;
            if (lhsIsBool) {
                String base = printExpr(c.left());
                return switch (c.op()) {
                    case NE -> base;               // != 0 -> true when set
                    case EQ -> "!(" + base + ")";  // == 0 -> true when clear
                    default -> base + " " + c.op().symbol() + " 0";
                };
            }
        }
        return printExpr(c.left()) + " " + c.op().symbol() + " " + printExpr(c.right());
    }

        private static boolean isLambdaBootstrap(InvokeDynamicExpr i) {
        return i.bsmArgs().size() > 1 && i.bsmArgs().get(1) instanceof Handle;
    }

    private static String foldLambdaRef(InvokeDynamicExpr i) {
        Handle h = (Handle) i.bsmArgs().get(1);
        String implName = h.getName();
        String owner = simpleClassName(slashToDot(h.getOwner()));
        // Constructor reference: ClassName::new
        if ("<init>".equals(implName)) {
            String recv = i.args().isEmpty() ? owner : printExpr(i.args().get(0));
            return recv + "::new";
        }
        // Synthetic lambda body — can't reconstruct without decompiling the synthetic method
        if (implName.startsWith("lambda$")) {
            return "(" + joinExprs(i.args()) + ") -> { /* lambda */ }";
        }
        // Static or instance method reference
        String recv = i.args().isEmpty() ? owner : printExpr(i.args().get(0));
        return recv + "::" + implName;
    }

    /**
     * Folds makeConcatWithConstants back into infix `+` notation.
     * Recipe chars: '' = next dynamic arg, '' = next constant from bsmArgs[1+].
     */
    private static String foldStringConcat(InvokeDynamicExpr i) {
        if (i.bsmArgs().isEmpty() || !(i.bsmArgs().get(0) instanceof String recipe)) {
            return "invokedynamic:" + i.name() + "(" + joinExprs(i.args()) + ")";
        }
        List<String> pieces = new ArrayList<>();
        int dynIdx = 0, constIdx = 1;
        StringBuilder lit = new StringBuilder();
        for (int ci = 0; ci <= recipe.length(); ci++) {
            char ch = ci < recipe.length() ? recipe.charAt(ci) : 0;
            if (ci == recipe.length() || ch == '' || ch == '') {
                if (!lit.isEmpty()) {
                    pieces.add(formatConst(lit.toString()));
                    lit.setLength(0);
                }
                if (ch == '' && dynIdx < i.args().size()) {
                    pieces.add(printExpr(i.args().get(dynIdx++)));
                } else if (ch == '' && constIdx < i.bsmArgs().size()) {
                    Object c = i.bsmArgs().get(constIdx++);
                    pieces.add(c instanceof String s ? formatConst(s) : String.valueOf(c));
                }
            } else {
                lit.append(ch);
            }
        }
        return pieces.isEmpty() ? "\"\"" : String.join(" + ", pieces);
    }

    private static String labelName(LabelNode label, Map<LabelNode, String> names) {
        return names.computeIfAbsent(label, l -> "L" + names.size());
    }

    /** Converts ConstExpr(0/1, Int) to ConstExpr(false/true, Boolean) when a boolean is expected. */
    private static Expr coerceBool(Expr e) {
        if (e instanceof ConstExpr ce && ce.value() instanceof Integer iv) {
            return new ConstExpr(iv != 0, JType.Boolean);
        }
        return e;
    }

    private static String formatConst(Object value) {
        if (value == null)         return "null";
        if (value instanceof Boolean b) return b.toString();
        if (value instanceof String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                           .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
        }
        if (value instanceof Long l)   return l + "L";
        if (value instanceof Float f) {
            if (Float.isNaN(f))               return "Float.NaN";
            if (f == Float.POSITIVE_INFINITY) return "Float.POSITIVE_INFINITY";
            if (f == Float.NEGATIVE_INFINITY) return "Float.NEGATIVE_INFINITY";
            return f + "f";
        }
        if (value instanceof Double d) {
            if (Double.isNaN(d))               return "Double.NaN";
            if (d == Double.POSITIVE_INFINITY) return "Double.POSITIVE_INFINITY";
            if (d == Double.NEGATIVE_INFINITY) return "Double.NEGATIVE_INFINITY";
            return d.toString();
        }
        if (value instanceof Character c) return "'" + c + "'";
        return value.toString();
    }

    private static String joinExprs(List<Expr> exprs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < exprs.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(printExpr(exprs.get(i)));
        }
        return sb.toString();
    }

    static String simpleClassName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    private static String slashToDot(String s) {
        return s.replace('/', '.');
    }

    private static boolean hasSideEffects(Expr expr) {
        return expr instanceof InvokeExpr
            || expr instanceof InvokeStaticExpr
            || expr instanceof InvokeDynamicExpr
            || expr instanceof NewObjectExpr;
    }

    private static List<Expr> popArgs(Deque<Expr> stack, String desc) {
        Type[] argTypes = Type.getArgumentTypes(desc);
        Expr[] args = new Expr[argTypes.length];
        for (int i = argTypes.length - 1; i >= 0; i--) {
            Expr arg = stack.pop();
            if (argTypes[i].getSort() == Type.BOOLEAN) arg = coerceBool(arg);
            args[i] = arg;
        }
        return Arrays.asList(args);
    }

    private static JType inferConstType(Object value) {
        if (value instanceof Integer) return JType.Int;
        if (value instanceof Long)    return JType.Long;
        if (value instanceof Float)   return JType.Float;
        if (value instanceof Double)  return JType.Double;
        if (value instanceof String)  return JType.String;
        return JType.Object;
    }

    private static JType fromAsmType(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN -> JType.Boolean;
            case Type.BYTE    -> JType.Byte;
            case Type.SHORT   -> JType.Short;
            case Type.CHAR    -> JType.Char;
            case Type.INT     -> JType.Int;
            case Type.FLOAT   -> JType.Float;
            case Type.LONG    -> JType.Long;
            case Type.DOUBLE  -> JType.Double;
            case Type.VOID    -> JType.Void;
            case Type.ARRAY, Type.OBJECT -> {
                if ("java.lang.String".equals(type.getClassName())) yield JType.String;
                yield JType.Object;
            }
            default -> JType.Unknown;
        };
    }

    private static LocalRef localFor(int slot, Map<Integer, LocalRef> locals, Map<Integer, JType> localTypes) {
        return locals.compute(slot, (s, old) -> {
            JType type = localTypes.getOrDefault(slot, JType.Unknown);
            String name = DEBUG_NAMES.get().getOrDefault(s, old != null ? old.name() : "var" + s);
            return new LocalRef(s, name, type);
        });
    }

    private static void handleStore(
            Deque<Expr> stack, List<IrStmt> ir,
            Map<Integer, LocalRef> locals, Map<Integer, JType> localTypes,
            int slot, StoreOp op) {
        Expr value = stack.pop();
        JType inferredType = switch (op) {
            case I, L, F, D -> op.type();
            case A           -> value.type();
        };
        localTypes.put(slot, inferredType);
        if (op == StoreOp.A) {
            String rich = richTypeName(value);
            if (rich != null) RICH_TYPES.get().put(slot, rich);
            else RICH_TYPES.get().remove(slot);
        }
        ir.add(new IrAssign(localFor(slot, locals, localTypes), value));
    }

    /** Infers the concrete Java type name from an expression's structure when possible. */
    private static String richTypeName(Expr value) {
        return switch (value) {
            case NewObjectExpr n  -> simpleClassName(n.className);
            case NewArrayExpr n   -> n.elementTypeName() + "[]";
            case RefCastExpr r    -> simpleClassName(r.targetType());
            case LocalRef l       -> RICH_TYPES.get().get(l.slot());
            case ArrayLoadExpr a  -> {
                String arrType = switch (a.array()) {
                    case LocalRef lr     -> RICH_TYPES.get().get(lr.slot());
                    case NewArrayExpr na -> na.elementTypeName() + "[]";
                    default              -> null;
                };
                yield arrType != null && arrType.endsWith("[]")
                    ? arrType.substring(0, arrType.length() - 2) : null;
            }
            default -> null;
        };
    }

    private static void pushBinary(Deque<Expr> stack, BinOp op) {
        Expr right = stack.pop();
        Expr left  = stack.pop();
        stack.push(new BinaryExpr(op, left, right));
    }

    private static void pushUno(Deque<Expr> stack, UnoOp op) {
        stack.push(new UnaryExpr(op, stack.pop()));
    }

    private static void emitIfZero(
            Deque<Expr> stack, List<IrStmt> ir,
            JumpInsnNode n, CmpOp op, Map<LabelNode, String> labelNames) {
        Expr value = stack.pop();
        Expr cond = (value instanceof CompareResultExpr c)
                ? new CmpExpr(op, c.left(), c.right())
                : new CmpExpr(op, value, new ConstExpr(0, JType.Int));
        ir.add(new IrIf(cond, labelName(n.label, labelNames)));
    }

    private static void emitIfICmp(
            Deque<Expr> stack, List<IrStmt> ir,
            JumpInsnNode n, CmpOp op, Map<LabelNode, String> labelNames) {
        Expr right = stack.pop();
        Expr left  = stack.pop();
        ir.add(new IrIf(new CmpExpr(op, left, right), labelName(n.label, labelNames)));
    }

    /**
     * Returns true if the instruction immediately following {@code label} (skipping pseudo-nodes)
     * is a value-returning opcode (IRETURN, LRETURN, FRETURN, DRETURN, ARETURN).
     * Used to detect the "push value; GOTO merge; …; merge: IRETURN" pattern javac emits
     * for boolean short-circuit returns.
     */
    private static boolean isGotoTargetReturn(LabelNode label) {
        for (AbstractInsnNode n = label.getNext(); n != null; n = n.getNext()) {
            int op = n.getOpcode();
            if (op < 0) continue; // skip pseudo-nodes (frames, line numbers, labels)
            return op == Opcodes.IRETURN || op == Opcodes.LRETURN || op == Opcodes.FRETURN
                || op == Opcodes.DRETURN || op == Opcodes.ARETURN;
        }
        return false;
    }

    private static boolean hasJsr(MethodNode mn) {
        for (AbstractInsnNode n : mn.instructions) {
            int op = n.getOpcode();
            if (op == Opcodes.JSR || op == Opcodes.RET) return true;
        }
        return false;
    }

    private static MethodNode inlineJsr(MethodNode mn) {
        String[] exceptions = mn.exceptions != null
                ? mn.exceptions.toArray(new String[0]) : null;
        MethodNode out = new MethodNode(Opcodes.ASM9, mn.access, mn.name, mn.desc, mn.signature, exceptions);
        mn.accept(new JSRInlinerAdapter(out, mn.access, mn.name, mn.desc, mn.signature, exceptions));
        return out;
    }

    // -------------------------------------------------------------------------
    // invokedynamic folding passes
    // -------------------------------------------------------------------------

    /**
     * Detects the two-phase bytecode pattern javac emits for switch(String):
     *   Phase 1: LOOKUPSWITCH on String.hashCode() → blocks that call equals() and ISTORE idx
     *   Phase 2: LOOKUPSWITCH/TABLESWITCH on idx → actual case bodies
     * Folds both into a single IrStringSwitch node and removes the intermediate IR.
     */
    private static void foldStringSwitches(List<IrStmt> ir) {
        Map<String, Integer> labelPos = buildLabelIndex(ir);

        int i = 0;
        while (i < ir.size()) {
            if (!(ir.get(i) instanceof IrSwitch sw) || !isHashCodeSwitch(sw)) { i++; continue; }

            Expr strExpr = ((InvokeExpr) sw.value()).object();

            // All labels that the hash switch can jump to (cases + default)
            Set<String> allHashLabels = new LinkedHashSet<>(sw.caseLabels());
            allHashLabels.add(sw.defaultLabel());

            Map<Integer, String> idxToStr = new LinkedHashMap<>();
            String   phase2Label  = null;
            int      idxSlot      = -1;
            Set<Integer> deleteRange = new HashSet<>();
            deleteRange.add(i);   // hash switch itself

            // If strExpr is a temp copy of another variable (e.g., var3 = cmd), use the
            // original directly and remove the copy assignment from the output.
            // Note: the idx=-1 init (e.g., var4 = -1) sits between the string copy and the
            // hash switch in the IR, so we skip negative-constant assignments while scanning.
            if (strExpr instanceof LocalRef strRef) {
                for (int b = i - 1; b >= 0; b--) {
                    IrStmt bs = ir.get(b);
                    if (bs instanceof IrAssign ba && ba.target().slot() == strRef.slot()) {
                        strExpr = ba.value();
                        deleteRange.add(b);
                        break;
                    } else if (bs instanceof IrLabel) {
                        continue; // skip labels
                    } else if (bs instanceof IrAssign ba2
                            && ba2.value() instanceof ConstExpr ce
                            && ce.value() instanceof Integer iv && iv < 0) {
                        continue; // skip the idx=-1 initialization that precedes the hash switch
                    } else {
                        break;
                    }
                }
            }

            boolean valid   = true;
            String  lastStr = null;

            outer:
            for (int j = i + 1; j < ir.size(); j++) {
                IrStmt s = ir.get(j);

                if (s instanceof IrLabel lbl) {
                    if (phase2Label != null && lbl.name().equals(phase2Label)) {
                        deleteRange.add(j);   // phase2 label
                        // next statement must be the phase2 IrSwitch
                        int k = j + 1;
                        if (k >= ir.size() || !(ir.get(k) instanceof IrSwitch sw2)) { valid = false; break; }
                        if (!(sw2.value() instanceof LocalRef ref && ref.slot() == idxSlot)) { valid = false; break; }
                        deleteRange.add(k);   // phase2 switch

                        // Search backward from the hash switch to delete the initial idx=-1 store
                        for (int b = i - 1; b >= 0; b--) {
                            IrStmt bs = ir.get(b);
                            if (bs instanceof IrAssign ba && ba.target().slot() == idxSlot) {
                                deleteRange.add(b);
                            } else if (bs instanceof IrLabel) {
                                // skip past labels
                            } else {
                                break;
                            }
                        }

                        // Build final string → label list in phase2 key order
                        List<String> stringKeys  = new ArrayList<>();
                        List<String> finalLabels = new ArrayList<>();
                        for (int m = 0; m < sw2.keys().size(); m++) {
                            String str = idxToStr.get(sw2.keys().get(m));
                            if (str != null) {
                                stringKeys.add(str);
                                finalLabels.add(sw2.caseLabels().get(m));
                            }
                        }

                        IrStringSwitch ss = new IrStringSwitch(strExpr, stringKeys, finalLabels, sw2.defaultLabel());
                        List<IrStmt> newIr = new ArrayList<>(ir.size());
                        for (int m = 0; m < ir.size(); m++) {
                            if (m == i)                  newIr.add(ss);
                            else if (!deleteRange.contains(m)) newIr.add(ir.get(m));
                        }
                        ir.clear(); ir.addAll(newIr);
                        labelPos = buildLabelIndex(ir);
                        break outer;
                    }
                    // Any other label in this range belongs to the hash-case area
                    deleteRange.add(j);
                    lastStr = null;

                } else if (s instanceof IrIf iif) {
                    String str = extractEqualsStr(iif.condition());
                    if (str == null) { valid = false; break; }
                    lastStr = str;
                    deleteRange.add(j);

                } else if (s instanceof IrAssign assign
                           && assign.value() instanceof ConstExpr ce
                           && ce.value() instanceof Integer idxVal) {
                    int slot = assign.target().slot();
                    if (idxSlot == -1) idxSlot = slot;
                    else if (idxSlot != slot) { valid = false; break; }
                    if (idxVal >= 0 && lastStr != null) {
                        idxToStr.put(idxVal, lastStr);
                        lastStr = null;
                    }
                    deleteRange.add(j);

                } else if (s instanceof IrGoto g) {
                    if (phase2Label == null)               phase2Label = g.targetLabel();
                    else if (!phase2Label.equals(g.targetLabel())) { valid = false; break; }
                    deleteRange.add(j);

                } else {
                    // Unexpected statement inside hash-case area
                    if (phase2Label == null) { valid = false; break; }
                    // Already past hash-case area without finding phase2 label — give up
                    valid = false; break;
                }
            }

            if (!valid) { i++; continue; }
            // If valid and we found/folded, labelPos was rebuilt and ir[i] is now IrStringSwitch
            i++;
        }
    }

    /**
     * Detects Java 21 pattern-matching switch compiled via invokedynamic typeSwitch:
     *   INVOKEDYNAMIC typeSwitch(Object, int)int → TABLESWITCH on the result
     * Each arm starts with CHECKCAST → ASTORE (the pattern variable).
     * Folds into IrTypeSwitch; removes the CHECKCAST-IrAssign from each arm's flat IR.
     */
    private static void foldTypeSwitches(List<IrStmt> ir) {
        Map<String, Integer> labelPos = buildLabelIndex(ir);

        int i = 0;
        while (i < ir.size()) {
            if (!(ir.get(i) instanceof IrSwitch sw)) { i++; continue; }
            if (!(sw.value() instanceof InvokeDynamicExpr dyn)) { i++; continue; }
            if (!"typeSwitch".equals(dyn.name()) && !"enumSwitch".equals(dyn.name())) { i++; continue; }
            if (dyn.args().isEmpty()) { i++; continue; }

            Expr scrutinee  = dyn.args().get(0);   // Object arg; args[1] is the restart int
            List<Object> bsm = dyn.bsmArgs();

            List<String>  typeNames  = new ArrayList<>();
            List<String>  varNames   = new ArrayList<>();
            List<Integer> varSlots   = new ArrayList<>();
            List<String>  caseLabels = new ArrayList<>(sw.caseLabels());
            Set<Integer>  toDelete   = new HashSet<>();
            toDelete.add(i);   // the IrSwitch itself

            for (int k = 0; k < sw.keys().size(); k++) {
                int    caseIdx   = sw.keys().get(k);
                String caseLabel = sw.caseLabels().get(k);

                // Resolve type name from bootstrap args
                String typeName = "Object";
                if (caseIdx < bsm.size()) {
                    Object arg = bsm.get(caseIdx);
                    if (arg instanceof org.objectweb.asm.Type t)
                        typeName = simpleClassName(t.getClassName());
                }
                typeNames.add(typeName);

                // Find the CHECKCAST-IrAssign at the start of this case block
                Integer casePos = labelPos.get(caseLabel);
                String  varName = "var" + caseIdx;
                int     varSlot = -1;
                if (casePos != null) {
                    int j = casePos + 1;   // skip IrLabel
                    if (j < ir.size()
                            && ir.get(j) instanceof IrAssign assign
                            && assign.value() instanceof RefCastExpr) {
                        varName = assign.target().name();
                        varSlot = assign.target().slot();
                        toDelete.add(j);
                    }
                }
                varNames.add(varName);
                varSlots.add(varSlot);
            }

            // Remove the restart-counter init (IrAssign(slot, 0) right before the switch)
            if (dyn.args().size() >= 2 && dyn.args().get(1) instanceof LocalRef restartRef) {
                int restartSlot = restartRef.slot();
                for (int b = i - 1; b >= 0; b--) {
                    IrStmt bs = ir.get(b);
                    if (bs instanceof IrAssign ba && ba.target().slot() == restartSlot) {
                        toDelete.add(b);
                    } else if (bs instanceof IrLabel) {
                        // skip past labels
                    } else {
                        break;
                    }
                }
            }

            IrTypeSwitch ts = new IrTypeSwitch(scrutinee, typeNames, varNames, varSlots, caseLabels, sw.defaultLabel());
            List<IrStmt> newIr = new ArrayList<>(ir.size());
            for (int k = 0; k < ir.size(); k++) {
                if (k == i)                    newIr.add(ts);
                else if (!toDelete.contains(k)) newIr.add(ir.get(k));
            }
            ir.clear(); ir.addAll(newIr);
            labelPos = buildLabelIndex(ir);
            i++;
        }
    }

    private static Map<String, Integer> buildLabelIndex(List<IrStmt> ir) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < ir.size(); i++) {
            if (ir.get(i) instanceof IrLabel lbl) map.put(lbl.name(), i);
        }
        return map;
    }

    /** Returns the string literal inside an equals() comparison against 0, or null if not that pattern. */
    private static String extractEqualsStr(Expr condition) {
        if (!(condition instanceof CmpExpr c)) return null;
        if (c.op() != CmpOp.EQ) return null;
        if (!(c.right() instanceof ConstExpr ce && Integer.valueOf(0).equals(ce.value()))) return null;
        if (!(c.left() instanceof InvokeExpr inv)) return null;
        if (!"equals".equals(inv.name())) return null;
        if (inv.args().size() != 1) return null;
        if (!(inv.args().get(0) instanceof ConstExpr sc && sc.value() instanceof String s)) return null;
        return s;
    }

    private static boolean isHashCodeSwitch(IrSwitch sw) {
        return sw.value() instanceof InvokeExpr inv
            && "hashCode".equals(inv.name())
            && "()I".equals(inv.desc())
            && inv.args().isEmpty();
    }

    /**
     * Fixes the array-literal initialization pattern javac emits:
     *   ANEWARRAY; DUP; ICONST i; LDC v; AASTORE; ... ; ASTORE local
     *
     * The stack sim produces IrArrayStore(NewArrayExpr, i, v) × N followed by
     * IrAssign(local, NewArrayExpr), all referencing the SAME NewArrayExpr object.
     * That renders as illegal "new T[n][i] = v" syntax.
     *
     * Fix: move the IrAssign to just before the first IrArrayStore, then replace
     * the NewArrayExpr in each store with a LocalRef to the assigned local.
     */
    private static void foldArrayInits(List<IrStmt> ir) {
        for (int i = ir.size() - 1; i >= 0; i--) {
            if (!(ir.get(i) instanceof IrAssign assign)) continue;
            if (!(assign.value() instanceof NewArrayExpr nae)) continue;

            // Scan backward to find the contiguous block of IrArrayStore(same nae, ...)
            int first = i - 1;
            while (first >= 0 && ir.get(first) instanceof IrArrayStore as && as.array() == nae) {
                first--;
            }
            first++; // first is now the index of the first matching IrArrayStore

            if (first == i) continue; // no array stores — nothing to rewrite

            LocalRef local = assign.target();
            List<IrStmt> newIr = new ArrayList<>(ir.size());
            newIr.addAll(ir.subList(0, first));
            newIr.add(assign);                            // IrAssign moved to front
            for (int j = first; j < i; j++) {            // stores with updated array ref
                IrArrayStore as = (IrArrayStore) ir.get(j);
                newIr.add(new IrArrayStore(local, as.index(), as.value()));
            }
            // skip position i (the original IrAssign, now moved)
            newIr.addAll(ir.subList(i + 1, ir.size()));
            ir.clear();
            ir.addAll(newIr);
            i = Math.min(i, newIr.size()); // re-check from same area
        }
    }

    /**
     * If the first real instruction at {@code label} is a local-variable STORE, returns it;
     * otherwise returns null.  Used by the GOTO handler to detect the ternary pattern.
     */
    private static VarInsnNode findGotoTargetStore(LabelNode label) {
        for (AbstractInsnNode n = label.getNext(); n != null; n = n.getNext()) {
            int op = n.getOpcode();
            if (op < 0) continue; // skip pseudo-nodes
            return (op == Opcodes.ISTORE || op == Opcodes.LSTORE || op == Opcodes.FSTORE
                    || op == Opcodes.DSTORE || op == Opcodes.ASTORE)
                    ? (VarInsnNode) n : null;
        }
        return null;
    }

    private static StoreOp storeOpFor(int opcode) {
        return switch (opcode) {
            case Opcodes.ISTORE -> StoreOp.I;
            case Opcodes.LSTORE -> StoreOp.L;
            case Opcodes.FSTORE -> StoreOp.F;
            case Opcodes.DSTORE -> StoreOp.D;
            default             -> StoreOp.A;
        };
    }

    /**
     * Fixes the switch-expression compiled-as-switch pattern where the last arm has no
     * explicit GOTO (it falls through to the merge label), leaving a stray IrAssign at
     * the merge point that semantically belongs to the fallthrough arm.
     *
     * Detects:
     *   IrAssign(slot, X),  IrGoto(Lm)     ← one or more earlier arms
     *   IrLabel(L_last)                     ← fallthrough arm (empty body)
     *   IrLabel(Lm)                         ← merge label
     *   IrAssign(slot, Y)                   ← actually belongs to L_last
     *
     * Transforms to:
     *   IrAssign(slot, X),  IrGoto(Lm)
     *   IrLabel(L_last)
     *   IrAssign(slot, Y)                   ← moved here
     *   IrGoto(Lm)                          ← added
     *   IrLabel(Lm)                         ← no longer followed by stray assign
     */
    private static void foldSwitchFallthroughArm(List<IrStmt> ir) {
        for (int i = 0; i + 2 < ir.size(); i++) {
            if (!(ir.get(i)   instanceof IrLabel fallLbl)) continue;
            if (!(ir.get(i+1) instanceof IrLabel mrgLbl))  continue;
            if (!(ir.get(i+2) instanceof IrAssign mrgA))   continue;

            String mergeLabel = mrgLbl.name();
            int    slot       = mrgA.target().slot();

            // Confirm a prior arm: IrAssign(same slot) immediately followed by IrGoto(mergeLabel)
            boolean hasPriorArm = false;
            for (int k = 1; k < i; k++) {
                if (ir.get(k) instanceof IrGoto g && g.targetLabel().equals(mergeLabel)
                        && ir.get(k - 1) instanceof IrAssign a
                        && a.target().slot() == slot) {
                    hasPriorArm = true;
                    break;
                }
            }
            if (!hasPriorArm) continue;

            List<IrStmt> newIr = new ArrayList<>(ir.size() + 1);
            newIr.addAll(ir.subList(0, i + 1));      // ..., IrLabel(L_last)
            newIr.add(mrgA);                          // IrAssign moved to fallthrough arm
            newIr.add(new IrGoto(mergeLabel));        // explicit goto added
            newIr.add(mrgLbl);                        // IrLabel(Lm)
            newIr.addAll(ir.subList(i + 3, ir.size())); // skip mrgA (already moved)
            ir.clear();
            ir.addAll(newIr);
            i--; // re-check from same position
        }
    }

    /**
     * Detects the two-block ternary pattern that the GOTO pre-emit fix produces:
     *   IrIf(bytecodeCond, L_else)
     *   IrAssign(x, thenVal)           ← pre-emitted from GOTO handler
     *   IrGoto(L_merge)
     *   IrLabel(L_else)
     *   [optional additional labels]
     *   IrLabel(L_merge)
     *   IrAssign(x, elseVal)           ← merge store (always the else-arm value)
     *
     * Folds into a single IrAssign(x, TernaryExpr(srcCond, thenVal, elseVal)) placed at
     * the merge label position, removing the branch and all intermediate IR.
     */
    private static void foldTernaries(List<IrStmt> ir) {
        int i = 0;
        while (i + 4 < ir.size()) {
            if (!(ir.get(i)   instanceof IrIf     iif)
             || !(ir.get(i+1) instanceof IrAssign thenA)
             || !(ir.get(i+2) instanceof IrGoto   gotoM)) { i++; continue; }

            String elseLabel  = iif.trueLabel();
            String mergeLabel = gotoM.targetLabel();

            // The label immediately following the goto must be L_else
            if (!(ir.get(i+3) instanceof IrLabel lbl0)
                    || !lbl0.name().equals(elseLabel)) { i++; continue; }

            // Scan forward through any additional labels until we find L_merge
            int j = i + 4;
            while (j < ir.size() && ir.get(j) instanceof IrLabel lbl
                    && !lbl.name().equals(mergeLabel)) {
                j++;
            }
            if (j >= ir.size() || !(ir.get(j) instanceof IrLabel mrgLbl)
                    || !mrgLbl.name().equals(mergeLabel)) { i++; continue; }

            // The statement right after L_merge must be IrAssign to the same slot
            int k = j + 1;
            if (k >= ir.size() || !(ir.get(k) instanceof IrAssign elseA)
                    || elseA.target().slot() != thenA.target().slot()) { i++; continue; }

            // Fold: srcCond = negate(bytecodeCond)
            Expr srcCond = StructuredEmitter.negate(iif.condition());
            Expr ternary = new TernaryExpr(srcCond, thenA.value(), elseA.value());
            IrAssign folded = new IrAssign(thenA.target(), ternary);

            List<IrStmt> newIr = new ArrayList<>(ir.size());
            newIr.addAll(ir.subList(0, i));       // everything before the IrIf
            newIr.add(folded);                     // the folded ternary assignment
            newIr.addAll(ir.subList(k + 1, ir.size())); // everything after the else assign
            ir.clear();
            ir.addAll(newIr);
            // Don't advance i — re-check from the same position for nested ternaries
        }
    }

    private static void initMethodArguments(
            MethodNode mn, Map<Integer, LocalRef> locals, Map<Integer, JType> localTypes) {
        int slot = 0;
        boolean isStatic = (mn.access & Opcodes.ACC_STATIC) != 0;
        Map<Integer, String> dbg = DEBUG_NAMES.get();

        if (!isStatic) {
            localTypes.put(slot, JType.Object);
            locals.put(slot, new LocalRef(slot, dbg.getOrDefault(slot, "this"), JType.Object));
            slot++;
        }

        int argIdx = 0;
        for (Type argType : Type.getArgumentTypes(mn.desc)) {
            JType type = fromAsmType(argType);
            localTypes.put(slot, type);
            locals.put(slot, new LocalRef(slot, dbg.getOrDefault(slot, "arg" + argIdx), type));
            slot += argType.getSize();
            argIdx++;
        }
    }
}
