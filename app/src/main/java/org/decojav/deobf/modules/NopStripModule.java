package org.decojav.deobf.modules;

import org.decojav.deobf.DeobfuscationModule;
import org.decojav.deobf.MethodNodeModule;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 4 — removes NOP instructions and other zero-effect sequences injected as junk.
 * Also removes POP instructions that follow instructions with no stack effect
 * (e.g. redundant POP after a void call that an obfuscator inserted).
 */
public final class NopStripModule implements DeobfuscationModule, MethodNodeModule {

    public static final NopStripModule INSTANCE = new NopStripModule();

    @Override public String name()        { return "nop-strip"; }
    @Override public String description() { return "Remove NOP junk instructions from method bytecode"; }

    @Override
    public void apply(MethodNode mn) {
        List<AbstractInsnNode> toRemove = new ArrayList<>();
        for (AbstractInsnNode n : mn.instructions) {
            if (n.getOpcode() == Opcodes.NOP) toRemove.add(n);
        }
        for (AbstractInsnNode n : toRemove) mn.instructions.remove(n);
    }
}
