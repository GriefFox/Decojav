package org.decojav;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodNode;

import java.util.*;
import java.util.LinkedHashMap;

import org.decojav.deobf.ModuleRegistry;
import org.decojav.SimpleMethodDecompiler.*;

/**
 * Converts a CFG of BasicBlocks back into structured Java-like source text.
 *
 * Algorithm overview:
 *   1. DFS colouring to detect back edges → loop headers.
 *   2. For each loop header: BFS backward from the latch to collect the body set.
 *   3. Recursive emitSequence() descent:
 *        - loop header (detected before body emission) → emitWhileLoop / do-while
 *        - IrGoto  → recurse on target (or emit break/implicit-continue)
 *        - IrIf    → emitIfElse (finds merge via BFS intersection)
 *        - terminal stmts → emit and stop
 */
public final class StructuredEmitter {

    // ---- DFS colour constants ----
    private static final int WHITE = 0, GRAY = 1, BLACK = 2;

    private final List<BasicBlock>        blocks;
    private final JType                   returnType;
    private final Map<String, BasicBlock> labelToBlock;
    private final Set<BasicBlock>         loopHeaders  = new HashSet<>();
    // loopBodies[h] = set of blocks inside the loop headed by h (includes h itself)
    private final Map<BasicBlock, Set<BasicBlock>> loopBodies = new HashMap<>();

    private StructuredEmitter(List<BasicBlock> blocks, JType returnType, Map<String, BasicBlock> labelToBlock) {
        this.blocks       = blocks;
        this.returnType   = returnType;
        this.labelToBlock = labelToBlock;
        detectLoops();
    }

    // -------------------------------------------------------------------------
    // Public entry point
    // -------------------------------------------------------------------------

    public static String emit(MethodNode mn, List<IrStmt> flatIr) {
        Map<String, BasicBlock> labelToBlock = new LinkedHashMap<>();
        List<BasicBlock> blocks = CfgBuilder.build(flatIr, labelToBlock);

        // Stage 6: CFG modules (dead-block removal, control-flow unflattening, etc.)
        ModuleRegistry.INSTANCE.applyCfg(blocks);

        if (blocks.isEmpty()) return buildHeader(mn) + " {}";

        // Pre-declare parameter slots so they are not re-annotated with types
        boolean isStatic = (mn.access & Opcodes.ACC_STATIC) != 0;
        Set<Integer> declared      = DECLARED.get();
        Set<String>  declaredNames = DECLARED_NAMES.get();
        Map<Integer, String> dbgNames = SimpleMethodDecompiler.DEBUG_NAMES.get();
        int slot = 0;
        if (!isStatic) {
            declared.add(slot);
            declaredNames.add(dbgNames.getOrDefault(slot, "this"));
            slot++;
        }
        int argIdx = 0;
        for (Type argType : Type.getArgumentTypes(mn.desc)) {
            declared.add(slot);
            declaredNames.add(dbgNames.getOrDefault(slot, "arg" + argIdx));
            slot += argType.getSize();
            argIdx++;
        }

        JType retType = SimpleMethodDecompiler.fromAsmTypePublic(Type.getReturnType(mn.desc));
        StructuredEmitter se = new StructuredEmitter(blocks, retType, labelToBlock);

        StringBuilder sb = new StringBuilder();
        sb.append(buildHeader(mn)).append(" {\n");
        se.emitSequence(blocks.get(0), null, null, new HashSet<>(), sb, "    ");
        sb.append("}");

        // Strip redundant trailing `return;` at the end of void methods
        String result = sb.toString();
        if (Type.getReturnType(mn.desc).equals(Type.VOID_TYPE)) {
            String trailing = "    return;\n}";
            if (result.endsWith(trailing)) {
                result = result.substring(0, result.length() - trailing.length()) + "}";
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Loop detection
    // -------------------------------------------------------------------------

    private void detectLoops() {
        int[] colour = new int[blocks.size()];
        dfs(blocks.get(0), colour);
    }

    private void dfs(BasicBlock bb, int[] colour) {
        colour[bb.id] = GRAY;
        for (BasicBlock s : bb.succ) {
            if (colour[s.id] == GRAY) {
                // Back edge bb → s: s is a loop header
                loopHeaders.add(s);
                Set<BasicBlock> body = loopBodies.computeIfAbsent(s, k -> new HashSet<>());
                body.add(s);
                collectLoopBody(bb, s, body);
            } else if (colour[s.id] == WHITE) {
                dfs(s, colour);
            }
        }
        colour[bb.id] = BLACK;
    }

    /** BFS backward from latch, bounded by header, to collect loop body. */
    private void collectLoopBody(BasicBlock latch, BasicBlock header, Set<BasicBlock> body) {
        Queue<BasicBlock> q = new ArrayDeque<>();
        q.add(latch);
        while (!q.isEmpty()) {
            BasicBlock cur = q.poll();
            if (!body.add(cur)) continue;
            if (cur == header) continue;
            q.addAll(cur.pred);
        }
    }

    // -------------------------------------------------------------------------
    // Core recursive emitter
    // -------------------------------------------------------------------------

    /**
     * Emit blocks starting at {@code block}, stopping before {@code stop}.
     *
     * @param stop      block to stop at (exclusive) — used to bound if/loop arms
     * @param loopExit  the exit block of the innermost enclosing loop (for break)
     * @param visited   blocks already emitted in this call chain
     */
    private void emitSequence(
            BasicBlock block,
            BasicBlock stop,
            BasicBlock loopExit,
            Set<BasicBlock> visited,
            StringBuilder sb,
            String indent) {

        if (block == null || block == stop || !visited.add(block)) return;

        // If this block is a loop header, handle the entire loop construct here —
        // before emitting the block's own body stmts, because do-while loops need
        // those body stmts printed INSIDE the loop braces.
        if (loopHeaders.contains(block) && block.terminator instanceof IrIf iif) {
            BasicBlock trueSucc  = block.succ.get(0);
            BasicBlock falseSucc = block.succ.get(1);
            emitWhileLoop(block, iif, trueSucc, falseSucc, stop, loopExit, visited, sb, indent);
            return;
        }

        // Emit body statements
        for (IrStmt s : block.body) {
            sb.append(indent).append(printStmt(s)).append(";\n");
        }

        // Handle terminator
        switch (block.terminator) {
            case IrVoidReturn v -> sb.append(indent).append("return;\n");
            case IrReturn r -> {
                String val = (returnType == JType.Boolean && r.value() instanceof ConstExpr ce
                              && ce.value() instanceof Integer i)
                             ? (i != 0 ? "true" : "false")
                             : printExpr(r.value());
                sb.append(indent).append("return ").append(val).append(";\n");
            }
            case IrThrow      t -> sb.append(indent).append("throw ").append(printExpr(t.value())).append(";\n");

            case IrGoto g -> {
                BasicBlock target = block.succ.get(0);
                if (target == stop) {
                    // Reaching the loop header again: implicit continue — don't emit
                } else if (target == loopExit) {
                    sb.append(indent).append("break;\n");
                } else {
                    emitSequence(target, stop, loopExit, visited, sb, indent);
                }
            }

            // Loop headers are already handled above; only non-header IrIf reaches here.
            case IrIf iif -> emitIfElse(
                    iif, block.succ.get(0), block.succ.get(1),
                    stop, loopExit, visited, sb, indent);

            case IrSwitch sw       -> emitSwitch(sw, stop, loopExit, visited, sb, indent);
            case IrStringSwitch ss -> emitStringSwitch(ss, stop, loopExit, visited, sb, indent);
            case IrTypeSwitch ts   -> emitTypeSwitch(ts, stop, loopExit, visited, sb, indent);

            default -> { /* non-terminator stmts should never appear here */ }
        }
    }

    // -------------------------------------------------------------------------
    // While / do-while loop
    // -------------------------------------------------------------------------

    private void emitWhileLoop(
            BasicBlock header,
            IrIf iif,
            BasicBlock trueSucc,
            BasicBlock falseSucc,
            BasicBlock stop,
            BasicBlock outerLoopExit,
            Set<BasicBlock> outerVisited,
            StringBuilder sb,
            String indent) {

        Set<BasicBlock> body = loopBodies.getOrDefault(header, Set.of());

        // Determine which successor is the loop exit
        boolean trueIsExit = !body.contains(trueSucc);
        BasicBlock exitBlock = trueIsExit ? trueSucc  : falseSucc;
        BasicBlock bodyStart = trueIsExit ? falseSucc : trueSucc;

        if (!header.body.isEmpty() && bodyStart == header) {
            // DO-WHILE: body stmts live in the header block (self-loop back edge).
            // Condition is at the end and the back edge is the true branch.
            Expr doWhileCond = trueIsExit ? negate(iif.condition()) : iif.condition();
            sb.append(indent).append("do {\n");
            for (IrStmt s : header.body) {
                sb.append(indent).append("    ").append(printStmt(s)).append(";\n");
            }
            sb.append(indent).append("} while (").append(printExpr(doWhileCond)).append(");\n");
        } else {
            // WHILE: condition is in the header; body starts in a separate block.
            Expr whileCond = trueIsExit ? negate(iif.condition()) : iif.condition();

            // Emit any stmts in the header block itself (usually empty for while-loops)
            for (IrStmt s : header.body) {
                sb.append(indent).append(printStmt(s)).append(";\n");
            }

            sb.append(indent).append("while (").append(printExpr(whileCond)).append(") {\n");
            Set<BasicBlock> bodyVisited = new HashSet<>();
            bodyVisited.add(header); // prevent re-entering the header from inside the body
            emitSequence(bodyStart, header, exitBlock, bodyVisited, sb, indent + "    ");
            sb.append(indent).append("}\n");
            outerVisited.addAll(bodyVisited);
        }

        emitSequence(exitBlock, stop, outerLoopExit, outerVisited, sb, indent);
    }

    // -------------------------------------------------------------------------
    // If / if-else
    // -------------------------------------------------------------------------

    private void emitIfElse(
            IrIf iif,
            BasicBlock trueSucc,
            BasicBlock falseSucc,
            BasicBlock stop,
            BasicBlock loopExit,
            Set<BasicBlock> visited,
            StringBuilder sb,
            String indent) {

        // javac convention: IrIf(bytecodeCond) jumps when bytecodeCond is true.
        // The fall-through (falseSucc) is the "then" arm in source.
        // The jump target (trueSucc) is the "else" arm or the merge.
        //
        // So source-level condition = negate(bytecodeCond).

        BasicBlock merge = findMerge(trueSucc, falseSucc, stop);

        if (merge == trueSucc) {
            // No else: if (srcCond) { falseSucc } then continue at merge
            sb.append(indent).append("if (").append(printExpr(negate(iif.condition()))).append(") {\n");
            emitSequence(falseSucc, merge, loopExit, new HashSet<>(visited), sb, indent + "    ");
            sb.append(indent).append("}\n");
            emitSequence(merge, stop, loopExit, visited, sb, indent);

        } else if (merge == falseSucc) {
            // Inverted no-else
            sb.append(indent).append("if (").append(printExpr(iif.condition())).append(") {\n");
            emitSequence(trueSucc, merge, loopExit, new HashSet<>(visited), sb, indent + "    ");
            sb.append(indent).append("}\n");
            emitSequence(merge, stop, loopExit, visited, sb, indent);

        } else if (merge == null) {
            // Neither arm has a common merge — both exit (return/throw) or loop back to stop.
            //
            // Priority 1: one arm is a "loop continue" (leads only to stop).
            //   Emit: if (!cond) { exitArm }; continueArm
            // Priority 2: one arm is a trivial exit (empty body + return/throw).
            //   Emit the complex arm inside the if, trivial arm as fall-through.
            boolean trueGoesToStop  = stop != null && allPathsReachStop(trueSucc,  stop);
            boolean falseGoesToStop = stop != null && allPathsReachStop(falseSucc, stop);

            if (trueGoesToStop) {
                sb.append(indent).append("if (").append(printExpr(negate(iif.condition()))).append(") {\n");
                emitSequence(falseSucc, stop, loopExit, new HashSet<>(visited), sb, indent + "    ");
                sb.append(indent).append("}\n");
                emitSequence(trueSucc, stop, loopExit, visited, sb, indent);
            } else if (falseGoesToStop) {
                sb.append(indent).append("if (").append(printExpr(iif.condition())).append(") {\n");
                emitSequence(trueSucc, stop, loopExit, new HashSet<>(visited), sb, indent + "    ");
                sb.append(indent).append("}\n");
                emitSequence(falseSucc, stop, loopExit, visited, sb, indent);
            } else {
                boolean trueIsSimpleExit  = trueSucc.body.isEmpty()  && trueSucc.isExit();
                boolean falseIsSimpleExit = falseSucc.body.isEmpty() && falseSucc.isExit();

                if (trueIsSimpleExit) {
                    sb.append(indent).append("if (").append(printExpr(negate(iif.condition()))).append(") {\n");
                    emitSequence(falseSucc, stop, loopExit, new HashSet<>(visited), sb, indent + "    ");
                    sb.append(indent).append("}\n");
                    emitSequence(trueSucc, stop, loopExit, visited, sb, indent);
                } else if (falseIsSimpleExit) {
                    sb.append(indent).append("if (").append(printExpr(iif.condition())).append(") {\n");
                    emitSequence(trueSucc, stop, loopExit, new HashSet<>(visited), sb, indent + "    ");
                    sb.append(indent).append("}\n");
                    emitSequence(falseSucc, stop, loopExit, visited, sb, indent);
                } else {
                    sb.append(indent).append("if (").append(printExpr(negate(iif.condition()))).append(") {\n");
                    emitSequence(falseSucc, stop, loopExit, new HashSet<>(visited), sb, indent + "    ");
                    sb.append(indent).append("} else {\n");
                    emitSequence(trueSucc,  stop, loopExit, new HashSet<>(visited), sb, indent + "    ");
                    sb.append(indent).append("}\n");
                }
            }

        } else {
            // Full if-else with merge point
            sb.append(indent).append("if (").append(printExpr(negate(iif.condition()))).append(") {\n");
            emitSequence(falseSucc, merge, loopExit, new HashSet<>(visited), sb, indent + "    ");
            sb.append(indent).append("} else {\n");
            emitSequence(trueSucc,  merge, loopExit, new HashSet<>(visited), sb, indent + "    ");
            sb.append(indent).append("}\n");
            emitSequence(merge, stop, loopExit, visited, sb, indent);
        }
    }

    // -------------------------------------------------------------------------
    // Switch
    // -------------------------------------------------------------------------

    private void emitSwitch(
            IrSwitch sw,
            BasicBlock stop, BasicBlock loopExit,
            Set<BasicBlock> visited,
            StringBuilder sb, String indent) {

        BasicBlock defaultBlock = labelToBlock.get(sw.defaultLabel());

        // Group keys by target block, preserving order of first appearance
        LinkedHashMap<BasicBlock, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < sw.keys().size(); i++) {
            BasicBlock target = labelToBlock.get(sw.caseLabels().get(i));
            groups.computeIfAbsent(target, k -> new ArrayList<>()).add(sw.keys().get(i));
        }

        // Collect unique arms for merge detection
        List<BasicBlock> allArms = new ArrayList<>(groups.keySet());
        if (defaultBlock != null && !allArms.contains(defaultBlock)) allArms.add(defaultBlock);
        BasicBlock merge = findSwitchMerge(allArms, stop);

        sb.append(indent).append("switch (").append(printExpr(sw.value())).append(") {\n");

        boolean defaultEmitted = false;
        for (Map.Entry<BasicBlock, List<Integer>> entry : groups.entrySet()) {
            BasicBlock arm = entry.getKey();
            for (int key : entry.getValue()) {
                sb.append(indent).append("    case ").append(key).append(":\n");
            }
            if (arm == defaultBlock) {
                sb.append(indent).append("    default:\n");
                defaultEmitted = true;
            }
            if (arm != null && arm != merge) {
                emitSequence(arm, merge, loopExit, new HashSet<>(visited), sb, indent + "        ");
                if (needsBreak(arm, merge)) sb.append(indent).append("        break;\n");
            } else {
                sb.append(indent).append("        break;\n");
            }
        }

        if (!defaultEmitted) {
            sb.append(indent).append("    default:\n");
            if (defaultBlock != null && defaultBlock != merge) {
                emitSequence(defaultBlock, merge, loopExit, new HashSet<>(visited), sb, indent + "        ");
                if (needsBreak(defaultBlock, merge)) sb.append(indent).append("        break;\n");
            } else {
                sb.append(indent).append("        break;\n");
            }
        }

        sb.append(indent).append("}\n");
        if (merge != null) emitSequence(merge, stop, loopExit, visited, sb, indent);
    }

    // -------------------------------------------------------------------------
    // String switch
    // -------------------------------------------------------------------------

    private void emitStringSwitch(
            IrStringSwitch sw,
            BasicBlock stop, BasicBlock loopExit,
            Set<BasicBlock> visited,
            StringBuilder sb, String indent) {

        BasicBlock defaultBlock = labelToBlock.get(sw.defaultLabel());

        // Group string keys by target block, preserving insertion order
        LinkedHashMap<BasicBlock, List<String>> groups = new LinkedHashMap<>();
        for (int i = 0; i < sw.stringKeys().size(); i++) {
            BasicBlock arm = labelToBlock.get(sw.caseLabels().get(i));
            groups.computeIfAbsent(arm, k -> new ArrayList<>()).add(sw.stringKeys().get(i));
        }

        List<BasicBlock> allArms = new ArrayList<>(groups.keySet());
        if (defaultBlock != null && !allArms.contains(defaultBlock)) allArms.add(defaultBlock);
        BasicBlock merge = findSwitchMerge(allArms, stop);

        sb.append(indent).append("switch (").append(printExpr(sw.value())).append(") {\n");

        boolean defaultEmitted = false;
        for (Map.Entry<BasicBlock, List<String>> entry : groups.entrySet()) {
            BasicBlock arm = entry.getKey();
            for (String key : entry.getValue()) {
                sb.append(indent).append("    case ")
                  .append('"').append(key.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
                  .append(":\n");
            }
            if (arm == defaultBlock) {
                sb.append(indent).append("    default:\n");
                defaultEmitted = true;
            }
            if (arm != null && arm != merge) {
                emitSequence(arm, merge, loopExit, new HashSet<>(visited), sb, indent + "        ");
                if (needsBreak(arm, merge)) sb.append(indent).append("        break;\n");
            } else {
                sb.append(indent).append("        break;\n");
            }
        }

        if (!defaultEmitted) {
            sb.append(indent).append("    default:\n");
            if (defaultBlock != null && defaultBlock != merge) {
                emitSequence(defaultBlock, merge, loopExit, new HashSet<>(visited), sb, indent + "        ");
                if (needsBreak(defaultBlock, merge)) sb.append(indent).append("        break;\n");
            } else {
                sb.append(indent).append("        break;\n");
            }
        }

        sb.append(indent).append("}\n");
        if (merge != null) emitSequence(merge, stop, loopExit, visited, sb, indent);
    }

    // -------------------------------------------------------------------------
    // Pattern-matching type switch
    // -------------------------------------------------------------------------

    private void emitTypeSwitch(
            IrTypeSwitch ts,
            BasicBlock stop, BasicBlock loopExit,
            Set<BasicBlock> visited,
            StringBuilder sb, String indent) {

        BasicBlock defaultBlock = labelToBlock.get(ts.defaultLabel());

        // Group case indices by target block
        LinkedHashMap<BasicBlock, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < ts.caseLabels().size(); i++) {
            BasicBlock arm = labelToBlock.get(ts.caseLabels().get(i));
            groups.computeIfAbsent(arm, k -> new ArrayList<>()).add(i);
        }

        List<BasicBlock> allArms = new ArrayList<>(groups.keySet());
        if (defaultBlock != null && !allArms.contains(defaultBlock)) allArms.add(defaultBlock);
        BasicBlock merge = findSwitchMerge(allArms, stop);

        sb.append(indent).append("switch (").append(printExpr(ts.scrutinee())).append(") {\n");

        boolean defaultEmitted = false;
        for (Map.Entry<BasicBlock, List<Integer>> entry : groups.entrySet()) {
            BasicBlock arm     = entry.getKey();
            List<Integer> idxs = entry.getValue();

            if (arm == defaultBlock && idxs.size() == 1) {
                sb.append(indent).append("    default -> {\n");
                defaultEmitted = true;
            } else {
                for (int idx : idxs) {
                    String typeName = ts.typeNames().get(idx);
                    String varName  = ts.varNames().get(idx);
                    int    varSlot  = ts.varSlots().get(idx);
                    // Pre-declare the pattern variable slot so body won't re-emit it
                    if (varSlot >= 0) {
                        DECLARED.get().add(varSlot);
                        DECLARED_NAMES.get().add(varName);
                    }
                    sb.append(indent).append("    case ").append(typeName)
                      .append(" ").append(varName).append(" -> {\n");
                }
            }

            if (arm != null && arm != merge) {
                emitSequence(arm, merge, loopExit, new HashSet<>(visited), sb, indent + "        ");
            }
            sb.append(indent).append("    }\n");
        }

        if (!defaultEmitted) {
            sb.append(indent).append("    default -> {\n");
            if (defaultBlock != null && defaultBlock != merge) {
                emitSequence(defaultBlock, merge, loopExit, new HashSet<>(visited), sb, indent + "        ");
            }
            sb.append(indent).append("    }\n");
        }

        sb.append(indent).append("}\n");
        if (merge != null) emitSequence(merge, stop, loopExit, visited, sb, indent);
    }

    private BasicBlock findSwitchMerge(List<BasicBlock> arms, BasicBlock stop) {
        if (arms.isEmpty()) return null;
        // Skip exit-only arms (throw/return) — they never reach a merge point and would
        // otherwise make the intersection empty for switch expressions with a default throw.
        Set<BasicBlock> common = null;
        for (BasicBlock arm : arms) {
            if (arm.isExit()) continue;
            Set<BasicBlock> r = reachable(arm, stop);
            if (r.isEmpty()) continue;
            if (common == null) common = new HashSet<>(r);
            else common.retainAll(r);
        }
        if (common == null || common.isEmpty()) return null;
        Set<BasicBlock> armSet = new HashSet<>(arms);
        // BFS from first non-exit arm: find the earliest block in common that isn't an arm itself
        BasicBlock startArm = arms.stream().filter(a -> !a.isExit()).findFirst().orElse(arms.get(0));
        Queue<BasicBlock> q = new ArrayDeque<>();
        Set<BasicBlock> seen = new HashSet<>();
        q.add(startArm);
        while (!q.isEmpty()) {
            BasicBlock cur = q.poll();
            if (!seen.add(cur)) continue;
            if (common.contains(cur) && !armSet.contains(cur)) return cur;
            if (cur == stop || cur.isExit()) continue;
            q.addAll(cur.succ);
        }
        return null;
    }

    private boolean needsBreak(BasicBlock arm, BasicBlock merge) {
        if (merge == null) return false;
        Deque<BasicBlock> q = new ArrayDeque<>();
        Set<BasicBlock> seen = new HashSet<>();
        q.add(arm);
        while (!q.isEmpty()) {
            BasicBlock cur = q.poll();
            if (!seen.add(cur)) continue;
            if (cur == merge) return true;
            if (cur.isExit()) continue;
            q.addAll(cur.succ);
        }
        return false;
    }

    /**
     * Returns true if every path from {@code start} eventually reaches {@code stop}
     * without first hitting an exit node (return/throw).
     * Used to detect "loop continue" arms — simple single-hop or complex nested branches.
     */
    private boolean allPathsReachStop(BasicBlock start, BasicBlock stop) {
        Deque<BasicBlock> q = new ArrayDeque<>();
        Set<BasicBlock> seen = new HashSet<>();
        q.add(start);
        while (!q.isEmpty()) {
            BasicBlock cur = q.poll();
            if (!seen.add(cur)) continue;
            if (cur == stop) continue;    // this path terminates at stop — OK
            if (cur.isExit()) return false; // this path escapes without reaching stop
            q.addAll(cur.succ);
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Merge point detection (BFS intersection)
    // -------------------------------------------------------------------------

    /**
     * Returns the first block reachable from both {@code a} and {@code b}
     * (ignoring {@code stop}), or null if both paths only reach exit nodes.
     */
    private BasicBlock findMerge(BasicBlock a, BasicBlock b, BasicBlock stop) {
        Set<BasicBlock> fromA = reachable(a, stop);
        // BFS from b; first block also in fromA is the merge
        Queue<BasicBlock> q  = new ArrayDeque<>();
        Set<BasicBlock> seen = new HashSet<>();
        q.add(b);
        seen.add(b);
        while (!q.isEmpty()) {
            BasicBlock cur = q.poll();
            if (fromA.contains(cur)) return cur;
            if (cur == stop || cur.isExit()) continue;
            for (BasicBlock s : cur.succ) {
                if (seen.add(s)) q.add(s);
            }
        }
        return null;
    }

    private Set<BasicBlock> reachable(BasicBlock start, BasicBlock stop) {
        Set<BasicBlock> set = new HashSet<>();
        Queue<BasicBlock> q = new ArrayDeque<>();
        q.add(start);
        set.add(start);
        while (!q.isEmpty()) {
            BasicBlock cur = q.poll();
            if (cur == stop || cur.isExit()) continue;
            for (BasicBlock s : cur.succ) {
                if (set.add(s)) q.add(s);
            }
        }
        return set;
    }

    // -------------------------------------------------------------------------
    // Expression / statement printers (delegated to SimpleMethodDecompiler)
    // -------------------------------------------------------------------------

    private static String printExpr(Expr e) {
        return SimpleMethodDecompiler.printExprPublic(e);
    }

    private static String printStmt(IrStmt s) {
        return switch (s) {
            case IrAssign    a -> formatAssign(a);
            case IrInc i -> {
                String incName = SLOT_RENAME.get().getOrDefault(i.target().slot(), i.target().name());
                yield i.delta() < 0
                        ? incName + " -= " + (-i.delta())
                        : incName + " += " + i.delta();
            }
            case IrExprStmt  e -> printExpr(e.expr());
            case IrFieldSet  f -> printExpr(f.object()) + "." + f.name() + " = " + printExpr(f.value());
            case IrStaticFieldSet f -> simpleClass(f.owner()) + "." + f.name() + " = " + printExpr(f.value());
            case IrArrayStore a -> printExpr(a.array()) + "[" + printExpr(a.index()) + "] = " + printExpr(a.value());
            case IrMonitor   m -> (m.enter() ? "monitorenter" : "monitorexit") + "(" + printExpr(m.object()) + ")";
            default            -> "/* unhandled stmt: " + s + " */";
        };
    }

    // Tracks which local slots have been declared to avoid duplicate type annotations.
    // Cleared before each decompile call by SimpleMethodDecompiler; parameters are
    // pre-populated by emit() so they are never re-declared with a type.
    static final ThreadLocal<Set<Integer>>     DECLARED       = ThreadLocal.withInitial(HashSet::new);
    // Tracks which variable names have been declared. When a new slot carries a name
    // already declared by a different slot, the slot gets a unique suffix rename.
    static final ThreadLocal<Set<String>>      DECLARED_NAMES = ThreadLocal.withInitial(HashSet::new);
    // Override map: slot → renamed identifier. Populated when a name conflict is detected.
    // All expression emission for LocalRef/IrInc checks here first.
    static final ThreadLocal<Map<Integer,String>> SLOT_RENAME  = ThreadLocal.withInitial(HashMap::new);

    private static String declaredTypeName(IrAssign a) {
        String rich = SimpleMethodDecompiler.RICH_TYPES.get().get(a.target().slot());
        return rich != null ? rich : a.target().type().javaName();
    }

    private static String formatAssign(IrAssign a) {
        Set<Integer> declared = DECLARED.get();
        Map<Integer, String> renames = SLOT_RENAME.get();
        boolean isNewSlot = declared.add(a.target().slot());
        if (isNewSlot) {
            // Respect any rename already registered for this slot (e.g., pattern-var pre-declare)
            String name = renames.getOrDefault(a.target().slot(), a.target().name());
            if (DECLARED_NAMES.get().add(name)) {
                return declaredTypeName(a) + " " + name + " = " + printExpr(a.value());
            }
            // Name collision with a different slot: generate a unique suffixed name
            int suffix = 2;
            String candidate;
            do { candidate = a.target().name() + suffix++; }
            while (!DECLARED_NAMES.get().add(candidate));
            renames.put(a.target().slot(), candidate);
            return declaredTypeName(a) + " " + candidate + " = " + printExpr(a.value());
        }
        // Slot already declared — emit a plain (re-)assignment, respecting any rename
        String compound = tryCompoundAssign(a);
        if (compound != null) return compound;
        String name = renames.getOrDefault(a.target().slot(), a.target().name());
        return name + " = " + printExpr(a.value());
    }

    // -------------------------------------------------------------------------
    // Condition negation
    // -------------------------------------------------------------------------

    /**
     * Try to fold {@code target = target OP rhs} into a compound assignment {@code target OP= rhs}.
     * Returns null if the assignment does not match the pattern.
     */
    private static String tryCompoundAssign(IrAssign a) {
        if (!(a.value() instanceof BinaryExpr b)) return null;
        if (!(b.left() instanceof LocalRef lref) || lref.slot() != a.target().slot()) return null;
        String op = switch (b.op()) {
            case ADD  -> "+";
            case SUB  -> "-";
            case MUL  -> "*";
            case DIV  -> "/";
            case REM  -> "%";
            case AND  -> "&";
            case OR   -> "|";
            case XOR  -> "^";
            case SHL  -> "<<";
            case SHR  -> ">>";
            case USHR -> ">>>";
        };
        String name = SLOT_RENAME.get().getOrDefault(a.target().slot(), a.target().name());
        return name + " " + op + "= " + printExpr(b.right());
    }

    static Expr negate(Expr cond) {
        if (cond instanceof CmpExpr c) {
            CmpOp flipped = switch (c.op()) {
                case EQ -> CmpOp.NE;
                case NE -> CmpOp.EQ;
                case LT -> CmpOp.GE;
                case GE -> CmpOp.LT;
                case GT -> CmpOp.LE;
                case LE -> CmpOp.GT;
            };
            return new CmpExpr(flipped, c.left(), c.right());
        }
        // Fallback: wrap in a != 0 comparison
        return new CmpExpr(CmpOp.EQ, cond, new ConstExpr(0, JType.Int));
    }

    // -------------------------------------------------------------------------
    // Method header builder
    // -------------------------------------------------------------------------

    /** Converts an ASM Type to its Java source name, preserving concrete class and array types. */
    private static String asmTypeToJavaName(Type type) {
        return switch (type.getSort()) {
            case Type.OBJECT -> simpleClass(type.getClassName());
            case Type.ARRAY  -> {
                Type elem = type.getElementType();
                int dims = type.getDimensions();
                String base = elem.getSort() == Type.OBJECT
                    ? simpleClass(elem.getClassName())
                    : SimpleMethodDecompiler.fromAsmTypePublic(elem).javaName();
                yield base + "[]".repeat(dims);
            }
            default -> SimpleMethodDecompiler.fromAsmTypePublic(type).javaName();
        };
    }

    private static String buildHeader(MethodNode mn) {
        StringBuilder sb = new StringBuilder();
        int acc = mn.access;
        boolean isStatic = (acc & Opcodes.ACC_STATIC) != 0;
        Type[] argTypes  = Type.getArgumentTypes(mn.desc);
        Map<Integer, String> dbg = SimpleMethodDecompiler.DEBUG_NAMES.get();

        if      ((acc & Opcodes.ACC_PUBLIC)    != 0) sb.append("public ");
        else if ((acc & Opcodes.ACC_PROTECTED) != 0) sb.append("protected ");
        else if ((acc & Opcodes.ACC_PRIVATE)   != 0) sb.append("private ");
        if (isStatic)                                 sb.append("static ");
        if ((acc & Opcodes.ACC_FINAL)        != 0)   sb.append("final ");
        if ((acc & Opcodes.ACC_SYNCHRONIZED) != 0)   sb.append("synchronized ");
        if ((acc & Opcodes.ACC_ABSTRACT)     != 0)   sb.append("abstract ");
        if (mn.name.equals("<init>")) {
            // Constructor: no return type; use the enclosing class simple name if available
            String enclosing = SimpleMethodDecompiler.ENCLOSING_CLASS.get();
            sb.append(enclosing != null ? enclosing : "<init>").append("(");
        } else {
            sb.append(asmTypeToJavaName(Type.getReturnType(mn.desc))).append(" ").append(mn.name).append("(");
        }

        int paramSlot = isStatic ? 0 : 1;
        for (int i = 0; i < argTypes.length; i++) {
            if (i > 0) sb.append(", ");
            String paramName = dbg.getOrDefault(paramSlot, "arg" + i);
            sb.append(asmTypeToJavaName(argTypes[i])).append(" ").append(paramName);
            paramSlot += argTypes[i].getSize();
        }
        sb.append(")");
        return sb.toString();
    }

    private static String simpleClass(String fqn) {
        return SimpleMethodDecompiler.simpleClassName(fqn);
    }
}
