package org.decojav.deobf;

import org.objectweb.asm.tree.ClassNode;

/**
 * Stage 3 — called with the fully parsed ASM ClassNode, before methods are decompiled.
 *
 * Use this stage to:
 *   - Strip bogus ACC_SYNTHETIC / ACC_BRIDGE flags
 *   - Rename classes, fields, and methods from a mapping file
 *   - Remove obfuscator-inserted synthetic fields or methods
 */
public interface ClassNodeModule extends DeobfuscationModule {

    /** Modify {@code cn} in place. */
    void apply(ClassNode cn);
}
