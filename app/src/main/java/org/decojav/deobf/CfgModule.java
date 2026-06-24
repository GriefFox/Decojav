package org.decojav.deobf;

import org.decojav.BasicBlock;

import java.util.List;

/**
 * Stage 6 — called with the basic-block CFG after control-flow construction,
 * before structured code emission.
 *
 * Use this stage to:
 *   - Remove unreachable blocks (dead code elimination)
 *   - Undo control-flow flattening (switch-dispatcher pattern)
 *
 * TODO Phase J1: BasicBlock should move to decojav-api alongside IrStmt.
 */
public interface CfgModule extends DeobfuscationModule {

    /** Modify {@code blocks} in place (remove blocks, redirect edges, etc.). */
    void apply(List<BasicBlock> blocks);
}
