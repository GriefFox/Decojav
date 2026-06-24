package org.decojav;

import java.util.*;

import org.decojav.SimpleMethodDecompiler.*;

/**
 * Converts a flat list of IrStmt (from the stack simulator) into a CFG of BasicBlocks.
 *
 * Algorithm:
 *   Pass 1 – collect all labels that appear as jump targets.
 *   Pass 2 – walk the IR; start a new block at method entry, after every terminator,
 *             and at every jumped-to label. Emit an implicit IrGoto for fall-through
 *             when a new block starts mid-sequence.
 *   Pass 3 – link succ/pred edges.
 */
public final class CfgBuilder {

    private CfgBuilder() {}

    /**
     * @param ir         flat IR produced by the stack simulator
     * @param labelToBlock  output map: label name → owning BasicBlock
     * @return ordered list of BasicBlocks; index 0 is the entry block
     */
    public static List<BasicBlock> build(
            List<IrStmt> ir,
            Map<String, BasicBlock> labelToBlock) {

        // ---- Pass 1: find jumped-to labels ----
        Set<String> jumpTargets = new HashSet<>();
        for (IrStmt s : ir) {
            if (s instanceof IrGoto   g)  jumpTargets.add(g.targetLabel());
            if (s instanceof IrIf     i)  jumpTargets.add(i.trueLabel());
            if (s instanceof IrSwitch sw) {
                jumpTargets.addAll(sw.caseLabels());
                jumpTargets.add(sw.defaultLabel());
            }
        }

        // ---- Pass 2: split into blocks ----
        List<BasicBlock> blocks   = new ArrayList<>();
        List<IrStmt>    body      = new ArrayList<>();
        String          curLabel  = null;
        int             nextId    = 0;
        boolean afterTerminator   = true; // method entry always opens a block

        for (IrStmt s : ir) {

            if (s instanceof IrLabel lbl) {
                boolean isTarget = jumpTargets.contains(lbl.name());
                if (isTarget || afterTerminator) {
                    if (curLabel != null || !body.isEmpty()) {
                        // Close the current block with an implicit fall-through goto
                        BasicBlock bb = finishBlock(nextId++, curLabel, body, new IrGoto(lbl.name()));
                        blocks.add(bb);
                        if (curLabel != null) labelToBlock.put(curLabel, bb);
                        body = new ArrayList<>();
                    }
                    curLabel = lbl.name();
                }
                afterTerminator = false;
                continue;
            }

            if (isTerminator(s)) {
                BasicBlock bb = finishBlock(nextId++, curLabel, body, s);
                blocks.add(bb);
                if (curLabel != null) labelToBlock.put(curLabel, bb);
                body     = new ArrayList<>();
                curLabel = null;
                afterTerminator = true;
            } else {
                body.add(s);
                afterTerminator = false;
            }
        }

        // Flush any trailing statements
        if (curLabel != null || !body.isEmpty()) {
            BasicBlock bb = finishBlock(nextId, curLabel, body, new IrVoidReturn());
            blocks.add(bb);
            if (curLabel != null) labelToBlock.put(curLabel, bb);
        }

        // ---- Pass 3: link succ/pred ----
        for (int i = 0; i < blocks.size(); i++) {
            BasicBlock bb   = blocks.get(i);
            BasicBlock next = (i + 1 < blocks.size()) ? blocks.get(i + 1) : null;

            switch (bb.terminator) {
                case IrGoto g -> {
                    BasicBlock t = labelToBlock.get(g.targetLabel());
                    if (t != null) link(bb, t);
                }
                case IrIf iif -> {
                    // succ[0] = true (jump target), succ[1] = false (fall-through)
                    BasicBlock t = labelToBlock.get(iif.trueLabel());
                    if (t    != null) link(bb, t);
                    if (next != null) link(bb, next);
                }
                case IrSwitch sw -> {
                    BasicBlock dflt = labelToBlock.get(sw.defaultLabel());
                    if (dflt != null) link(bb, dflt);
                    for (String lbl : sw.caseLabels()) {
                        BasicBlock t = labelToBlock.get(lbl);
                        if (t != null && !bb.succ.contains(t)) link(bb, t);
                    }
                }
                case IrStringSwitch ss -> {
                    BasicBlock dflt = labelToBlock.get(ss.defaultLabel());
                    if (dflt != null) link(bb, dflt);
                    for (String lbl : ss.caseLabels()) {
                        BasicBlock t = labelToBlock.get(lbl);
                        if (t != null && !bb.succ.contains(t)) link(bb, t);
                    }
                }
                case IrTypeSwitch ts -> {
                    BasicBlock dflt = labelToBlock.get(ts.defaultLabel());
                    if (dflt != null) link(bb, dflt);
                    for (String lbl : ts.caseLabels()) {
                        BasicBlock t = labelToBlock.get(lbl);
                        if (t != null && !bb.succ.contains(t)) link(bb, t);
                    }
                }
                case IrReturn r    -> { /* exit */ }
                case IrVoidReturn v -> { /* exit */ }
                case IrThrow t     -> { /* exit */ }
                default            -> { if (next != null) link(bb, next); }
            }
        }

        return blocks;
    }

    // ---- helpers ----

    private static BasicBlock finishBlock(int id, String label, List<IrStmt> body, IrStmt term) {
        BasicBlock bb = new BasicBlock(id, label, body);
        bb.terminator = term;
        return bb;
    }

    private static void link(BasicBlock from, BasicBlock to) {
        from.succ.add(to);
        to.pred.add(from);
    }

    private static boolean isTerminator(IrStmt s) {
        return s instanceof IrGoto
            || s instanceof IrIf
            || s instanceof IrSwitch
            || s instanceof IrStringSwitch
            || s instanceof IrTypeSwitch
            || s instanceof IrReturn
            || s instanceof IrVoidReturn
            || s instanceof IrThrow;
    }
}
