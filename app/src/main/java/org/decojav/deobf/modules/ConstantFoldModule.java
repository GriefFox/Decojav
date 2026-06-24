package org.decojav.deobf.modules;

import org.decojav.SimpleMethodDecompiler.BinaryExpr;
import org.decojav.SimpleMethodDecompiler.BinOp;
import org.decojav.SimpleMethodDecompiler.ConstExpr;
import org.decojav.SimpleMethodDecompiler.Expr;
import org.decojav.SimpleMethodDecompiler.IrArrayStore;
import org.decojav.SimpleMethodDecompiler.IrAssign;
import org.decojav.SimpleMethodDecompiler.IrExprStmt;
import org.decojav.SimpleMethodDecompiler.IrFieldSet;
import org.decojav.SimpleMethodDecompiler.IrIf;
import org.decojav.SimpleMethodDecompiler.IrReturn;
import org.decojav.SimpleMethodDecompiler.IrStaticFieldSet;
import org.decojav.SimpleMethodDecompiler.IrStmt;
import org.decojav.SimpleMethodDecompiler.IrThrow;
import org.decojav.SimpleMethodDecompiler.JType;
import org.decojav.deobf.DeobfuscationModule;
import org.decojav.deobf.IrModule;

import java.util.List;
import java.util.ListIterator;

/**
 * Stage 5 — folds BinaryExpr(ConstExpr, ConstExpr) into a single ConstExpr.
 * Handles int, long, float, and double arithmetic and bitwise operations.
 */
public final class ConstantFoldModule implements DeobfuscationModule, IrModule {

    public static final ConstantFoldModule INSTANCE = new ConstantFoldModule();

    @Override public String name()        { return "constant-fold"; }
    @Override public String description() { return "Fold constant arithmetic expressions at the IR level"; }

    @Override
    public void apply(List<IrStmt> ir) {
        for (int i = 0; i < ir.size(); i++) {
            ir.set(i, foldStmt(ir.get(i)));
        }
    }

    private static IrStmt foldStmt(IrStmt s) {
        return switch (s) {
            case IrAssign   a -> new IrAssign(a.target(), foldExpr(a.value()));
            case IrReturn   r -> new IrReturn(foldExpr(r.value()));
            case IrIf       i -> new IrIf(foldExpr(i.condition()), i.trueLabel());
            case IrExprStmt e -> new IrExprStmt(foldExpr(e.expr()));
            case IrFieldSet f -> new IrFieldSet(foldExpr(f.object()), f.owner(), f.name(), foldExpr(f.value()));
            case IrStaticFieldSet f -> new IrStaticFieldSet(f.owner(), f.name(), foldExpr(f.value()));
            case IrArrayStore a -> new IrArrayStore(foldExpr(a.array()), foldExpr(a.index()), foldExpr(a.value()));
            case IrThrow      t -> new IrThrow(foldExpr(t.value()));
            default -> s;
        };
    }

    static Expr foldExpr(Expr e) {
        if (!(e instanceof BinaryExpr b)) return e;
        Expr l = foldExpr(b.left());
        Expr r = foldExpr(b.right());
        if (!(l instanceof ConstExpr cl) || !(r instanceof ConstExpr cr)) {
            return l == b.left() && r == b.right() ? b : new BinaryExpr(b.op(), l, r);
        }
        Object lv = cl.value(), rv = cr.value();
        try {
            Object result = switch (b.op()) {
                case ADD  -> add(lv, rv);
                case SUB  -> sub(lv, rv);
                case MUL  -> mul(lv, rv);
                case DIV  -> div(lv, rv);
                case REM  -> rem(lv, rv);
                case AND  -> and(lv, rv);
                case OR   -> or(lv, rv);
                case XOR  -> xor(lv, rv);
                case SHL  -> shl(lv, rv);
                case SHR  -> shr(lv, rv);
                case USHR -> ushr(lv, rv);
            };
            if (result != null) return new ConstExpr(result, cl.type());
        } catch (ArithmeticException ignored) {}
        return new BinaryExpr(b.op(), l, r);
    }

    private static Object add(Object l, Object r) {
        if (l instanceof Integer a && r instanceof Integer b) return a + b;
        if (l instanceof Long    a && r instanceof Long    b) return a + b;
        if (l instanceof Float   a && r instanceof Float   b) return a + b;
        if (l instanceof Double  a && r instanceof Double  b) return a + b;
        return null;
    }
    private static Object sub(Object l, Object r) {
        if (l instanceof Integer a && r instanceof Integer b) return a - b;
        if (l instanceof Long    a && r instanceof Long    b) return a - b;
        if (l instanceof Float   a && r instanceof Float   b) return a - b;
        if (l instanceof Double  a && r instanceof Double  b) return a - b;
        return null;
    }
    private static Object mul(Object l, Object r) {
        if (l instanceof Integer a && r instanceof Integer b) return a * b;
        if (l instanceof Long    a && r instanceof Long    b) return a * b;
        if (l instanceof Float   a && r instanceof Float   b) return a * b;
        if (l instanceof Double  a && r instanceof Double  b) return a * b;
        return null;
    }
    private static Object div(Object l, Object r) {
        if (l instanceof Integer a && r instanceof Integer b) return a / b;
        if (l instanceof Long    a && r instanceof Long    b) return a / b;
        if (l instanceof Float   a && r instanceof Float   b) return a / b;
        if (l instanceof Double  a && r instanceof Double  b) return a / b;
        return null;
    }
    private static Object rem(Object l, Object r) {
        if (l instanceof Integer a && r instanceof Integer b) return a % b;
        if (l instanceof Long    a && r instanceof Long    b) return a % b;
        if (l instanceof Float   a && r instanceof Float   b) return a % b;
        if (l instanceof Double  a && r instanceof Double  b) return a % b;
        return null;
    }
    private static Object and(Object l, Object r) {
        if (l instanceof Integer a && r instanceof Integer b) return a & b;
        if (l instanceof Long    a && r instanceof Long    b) return a & b;
        return null;
    }
    private static Object or(Object l, Object r) {
        if (l instanceof Integer a && r instanceof Integer b) return a | b;
        if (l instanceof Long    a && r instanceof Long    b) return a | b;
        return null;
    }
    private static Object xor(Object l, Object r) {
        if (l instanceof Integer a && r instanceof Integer b) return a ^ b;
        if (l instanceof Long    a && r instanceof Long    b) return a ^ b;
        return null;
    }
    private static Object shl(Object l, Object r) {
        if (l instanceof Integer a && r instanceof Integer b) return a << b;
        if (l instanceof Long    a && r instanceof Integer b) return a << b;
        return null;
    }
    private static Object shr(Object l, Object r) {
        if (l instanceof Integer a && r instanceof Integer b) return a >> b;
        if (l instanceof Long    a && r instanceof Integer b) return a >> b;
        return null;
    }
    private static Object ushr(Object l, Object r) {
        if (l instanceof Integer a && r instanceof Integer b) return a >>> b;
        if (l instanceof Long    a && r instanceof Integer b) return a >>> b;
        return null;
    }
}
