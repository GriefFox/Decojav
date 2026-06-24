package org.decojav.deobf;

/**
 * Base marker interface for all deobfuscation modules.
 *
 * Implement one of the six stage-specific sub-interfaces instead of this directly:
 *   ZipEntryModule   — raw bytes per ZIP entry (stage 1)
 *   ClassBytesModule — raw bytes of one .class file (stage 2)
 *   ClassNodeModule  — parsed ASM ClassNode (stage 3)
 *   MethodNodeModule — parsed ASM MethodNode per method (stage 4)
 *   IrModule         — flat IR statement list after stack simulation (stage 5)
 *   CfgModule        — basic-block CFG after control-flow construction (stage 6)
 *
 * TODO Phase J1: move this interface (and its sub-interfaces) into a separate
 *   decojav-api subproject so module authors can compile against it without
 *   depending on the full decompiler internals.
 */
public interface DeobfuscationModule {

    /** Unique identifier used with --deobf to activate this module. */
    String name();

    /** One-line description printed by --list-modules. */
    String description();
}
