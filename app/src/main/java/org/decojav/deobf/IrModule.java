package org.decojav.deobf;

import org.decojav.SimpleMethodDecompiler.IrStmt;

import java.util.List;

/**
 * Stage 5 — called with the flat IR statement list produced by the stack simulator,
 * before control-flow reconstruction runs.
 *
 * Use this stage to:
 *   - Fold constant expressions (BinaryExpr(ConstExpr, ConstExpr) → ConstExpr)
 *   - Remove dead stores (IrAssign whose target is never read)
 *   - Decrypt string constants by evaluating static call expressions
 *
 * TODO Phase J1: IrStmt is currently a nested type inside SimpleMethodDecompiler.
 *   Move it to decojav-api so module authors don't need to import the decompiler internals.
 */
public interface IrModule extends DeobfuscationModule {

    /** Modify {@code ir} in place (add, remove, or replace statements). */
    void apply(List<IrStmt> ir);
}
