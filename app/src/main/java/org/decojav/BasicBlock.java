package org.decojav;

import java.util.ArrayList;
import java.util.List;

/**
 * A maximal straight-line sequence of IR statements with a single entry point
 * and a single exit (the terminator).
 *
 * For an IrIf terminator:
 *   succ[0] = true branch (the jump target)
 *   succ[1] = false branch (fall-through)
 */
public final class BasicBlock {

    public final int id;

    /** The label that starts this block, or null for the implicit entry block. */
    public final String entryLabel;

    /** Non-terminator statements. */
    public final List<SimpleMethodDecompiler.IrStmt> body;

    /** IrGoto | IrIf | IrReturn | IrVoidReturn | IrThrow */
    public SimpleMethodDecompiler.IrStmt terminator;

    public final List<BasicBlock> succ = new ArrayList<>(2);
    public final List<BasicBlock> pred = new ArrayList<>(2);

    public BasicBlock(int id, String entryLabel, List<SimpleMethodDecompiler.IrStmt> body) {
        this.id = id;
        this.entryLabel = entryLabel;
        this.body = List.copyOf(body);
    }

    public boolean isExit() {
        return terminator instanceof SimpleMethodDecompiler.IrReturn
            || terminator instanceof SimpleMethodDecompiler.IrVoidReturn
            || terminator instanceof SimpleMethodDecompiler.IrThrow;
    }

    @Override
    public String toString() {
        return "BB" + id + (entryLabel != null ? "[" + entryLabel + "]" : "");
    }
}
