package org.decojav.deobf;

import org.objectweb.asm.tree.MethodNode;

/**
 * Stage 4 — called for each method before the stack simulator runs.
 *
 * Use this stage to:
 *   - Strip injected NOP sequences and junk instructions
 *   - Remove opaque predicates (always-true/false constant branches)
 *   - Normalise exception-handler-as-goto tricks
 */
public interface MethodNodeModule extends DeobfuscationModule {

    /** Modify {@code mn} in place (its instruction list, try-catch blocks, etc.). */
    void apply(MethodNode mn);
}
