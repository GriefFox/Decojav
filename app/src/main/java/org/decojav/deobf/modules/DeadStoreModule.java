package org.decojav.deobf.modules;

import org.decojav.SimpleMethodDecompiler.ArrayLengthExpr;
import org.decojav.SimpleMethodDecompiler.ArrayLoadExpr;
import org.decojav.SimpleMethodDecompiler.BinaryExpr;
import org.decojav.SimpleMethodDecompiler.CastExpr;
import org.decojav.SimpleMethodDecompiler.CmpExpr;
import org.decojav.SimpleMethodDecompiler.CompareResultExpr;
import org.decojav.SimpleMethodDecompiler.Expr;
import org.decojav.SimpleMethodDecompiler.FieldGetExpr;
import org.decojav.SimpleMethodDecompiler.InstanceofExpr;
import org.decojav.SimpleMethodDecompiler.InvokeDynamicExpr;
import org.decojav.SimpleMethodDecompiler.InvokeExpr;
import org.decojav.SimpleMethodDecompiler.InvokeStaticExpr;
import org.decojav.SimpleMethodDecompiler.IrArrayStore;
import org.decojav.SimpleMethodDecompiler.IrAssign;
import org.decojav.SimpleMethodDecompiler.IrExprStmt;
import org.decojav.SimpleMethodDecompiler.IrFieldSet;
import org.decojav.SimpleMethodDecompiler.IrIf;
import org.decojav.SimpleMethodDecompiler.IrReturn;
import org.decojav.SimpleMethodDecompiler.IrStaticFieldSet;
import org.decojav.SimpleMethodDecompiler.IrStmt;
import org.decojav.SimpleMethodDecompiler.IrSwitch;
import org.decojav.SimpleMethodDecompiler.IrThrow;
import org.decojav.SimpleMethodDecompiler.LocalRef;
import org.decojav.SimpleMethodDecompiler.MultiNewArrayExpr;
import org.decojav.SimpleMethodDecompiler.NewArrayExpr;
import org.decojav.SimpleMethodDecompiler.NewObjectExpr;
import org.decojav.SimpleMethodDecompiler.RefCastExpr;
import org.decojav.SimpleMethodDecompiler.UnaryExpr;
import org.decojav.deobf.DeobfuscationModule;
import org.decojav.deobf.IrModule;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stage 5 — removes IrAssign statements where the assigned local variable slot
 * is never subsequently read. Does a single-pass liveness approximation:
 * a slot is "live" if any LocalRef with that slot appears in any expression
 * anywhere in the method.
 *
 * Conservative: does not remove stores to slots used in earlier instructions
 * (i.e. it only removes stores to slots that have zero reads anywhere in the IR).
 */
public final class DeadStoreModule implements DeobfuscationModule, IrModule {

    public static final DeadStoreModule INSTANCE = new DeadStoreModule();

    @Override public String name()        { return "dead-store"; }
    @Override public String description() { return "Remove assignments to locals that are never read"; }

    @Override
    public void apply(List<IrStmt> ir) {
        Set<Integer> readSlots = new HashSet<>();
        for (IrStmt s : ir) collectReadSlots(s, readSlots);

        ir.removeIf(s -> s instanceof IrAssign a
                && !readSlots.contains(a.target().slot())
                && !hasSideEffects(a.value()));
    }

    private static void collectReadSlots(IrStmt s, Set<Integer> slots) {
        switch (s) {
            case IrAssign   a -> collectExpr(a.value(), slots);
            case IrReturn   r -> collectExpr(r.value(), slots);
            case IrIf       i -> collectExpr(i.condition(), slots);
            case IrExprStmt e -> collectExpr(e.expr(), slots);
            case IrFieldSet f -> { collectExpr(f.object(), slots); collectExpr(f.value(), slots); }
            case IrStaticFieldSet f -> collectExpr(f.value(), slots);
            case IrArrayStore a -> { collectExpr(a.array(), slots); collectExpr(a.index(), slots); collectExpr(a.value(), slots); }
            case IrThrow  t  -> collectExpr(t.value(), slots);
            case IrSwitch sw -> collectExpr(sw.value(), slots);
            default -> {}
        }
    }

    private static void collectExpr(Expr e, Set<Integer> slots) {
        switch (e) {
            case LocalRef l          -> slots.add(l.slot());
            case BinaryExpr b        -> { collectExpr(b.left(), slots); collectExpr(b.right(), slots); }
            case UnaryExpr u         -> collectExpr(u.value(), slots);
            case CastExpr c          -> collectExpr(c.value(), slots);
            case RefCastExpr c       -> collectExpr(c.value(), slots);
            case CmpExpr c           -> { collectExpr(c.left(), slots); collectExpr(c.right(), slots); }
            case CompareResultExpr c -> { collectExpr(c.left(), slots); collectExpr(c.right(), slots); }
            case InvokeExpr i        -> { collectExpr(i.object(), slots); i.args().forEach(a -> collectExpr(a, slots)); }
            case InvokeStaticExpr i  -> i.args().forEach(a -> collectExpr(a, slots));
            case InvokeDynamicExpr i -> i.args().forEach(a -> collectExpr(a, slots));
            case FieldGetExpr f      -> collectExpr(f.object(), slots);
            case NewArrayExpr n      -> collectExpr(n.size(), slots);
            case MultiNewArrayExpr m -> m.dimensions().forEach(d -> collectExpr(d, slots));
            case ArrayLoadExpr a     -> { collectExpr(a.array(), slots); collectExpr(a.index(), slots); }
            case ArrayLengthExpr a   -> collectExpr(a.array(), slots);
            case InstanceofExpr i    -> collectExpr(i.value(), slots);
            case NewObjectExpr n     -> { if (n.ctorArgs() != null) n.ctorArgs().forEach(a -> collectExpr(a, slots)); }
            default                  -> {}
        }
    }

    private static boolean hasSideEffects(Expr e) {
        return e instanceof InvokeExpr
            || e instanceof InvokeStaticExpr
            || e instanceof InvokeDynamicExpr
            || e instanceof NewObjectExpr;
    }
}
