package org.decojav.deobf;

import org.decojav.BasicBlock;
import org.decojav.SimpleMethodDecompiler.IrStmt;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Central registry for deobfuscation modules.
 *
 * Modules are applied in registration order within each stage.
 * Use {@link #INSTANCE} to access the singleton.
 *
 * Pipeline stages (call order):
 *   1. applyZipEntry    — raw bytes per ZIP entry
 *   2. applyClassBytes  — raw bytes of one .class file
 *   3. applyClassNode   — parsed ASM ClassNode
 *   4. applyMethodNode  — parsed ASM MethodNode
 *   5. applyIr          — flat IR list after stack simulation
 *   6. applyCfg         — basic-block CFG after control-flow construction
 */
public final class ModuleRegistry {

    public static final ModuleRegistry INSTANCE = new ModuleRegistry();

    private final List<ZipEntryModule>   zipEntryModules   = new ArrayList<>();
    private final List<ClassBytesModule> classBytesModules = new ArrayList<>();
    private final List<ClassNodeModule>  classNodeModules  = new ArrayList<>();
    private final List<MethodNodeModule> methodNodeModules = new ArrayList<>();
    private final List<IrModule>         irModules         = new ArrayList<>();
    private final List<CfgModule>        cfgModules        = new ArrayList<>();

    /** If non-empty only modules whose name() is in this set are applied. */
    private Set<String> activeNames = new HashSet<>();

    private ModuleRegistry() {}

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    public void register(DeobfuscationModule module) {
        if (module instanceof ZipEntryModule   m) zipEntryModules.add(m);
        if (module instanceof ClassBytesModule m) classBytesModules.add(m);
        if (module instanceof ClassNodeModule  m) classNodeModules.add(m);
        if (module instanceof MethodNodeModule m) methodNodeModules.add(m);
        if (module instanceof IrModule         m) irModules.add(m);
        if (module instanceof CfgModule        m) cfgModules.add(m);
    }

    /** Restrict which modules run by name. Empty set = all registered modules run. */
    public void setActiveNames(Set<String> names) {
        this.activeNames = new HashSet<>(names);
    }

    // -------------------------------------------------------------------------
    // Discovery
    // -------------------------------------------------------------------------

    /**
     * Scans {@code modulesDir} for *.jar files and loads all DeobfuscationModule
     * implementations declared via META-INF/services/org.decojav.deobf.DeobfuscationModule.
     * Broken or incompatible JARs are skipped with a warning.
     */
    public void loadFromDirectory(java.nio.file.Path modulesDir) {
        if (!Files.isDirectory(modulesDir)) return;
        try (var stream = Files.list(modulesDir)) {
            stream.filter(p -> p.toString().endsWith(".jar")).forEach(jar -> {
                try {
                    URL url = jar.toUri().toURL();
                    URLClassLoader cl = new URLClassLoader(new URL[]{url},
                            Thread.currentThread().getContextClassLoader());
                    ServiceLoader.load(DeobfuscationModule.class, cl)
                            .forEach(m -> {
                                register(m);
                                System.err.println("[modules] loaded: " + m.name()
                                        + " from " + jar.getFileName());
                            });
                } catch (Throwable t) {
                    System.err.println("[modules] skip " + jar.getFileName() + ": " + t.getMessage());
                }
            });
        } catch (Exception e) {
            System.err.println("[modules] error scanning " + modulesDir + ": " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Pipeline apply methods
    // -------------------------------------------------------------------------

    /**
     * Stage 1 — apply to raw ZIP entry bytes.
     * @return modified bytes, or null if a module signals the entry should be skipped
     */
    public byte[] applyZipEntry(String entryName, byte[] bytes) {
        for (ZipEntryModule m : zipEntryModules) {
            if (!isActive(m)) continue;
            try {
                byte[] result = m.apply(entryName, bytes);
                if (result == null) return null;
                bytes = result;
            } catch (Throwable t) {
                warn(m, "zip entry " + entryName, t);
            }
        }
        return bytes;
    }

    /** Stage 2 — apply to raw .class file bytes before ClassReader. */
    public byte[] applyClassBytes(byte[] bytes) {
        for (ClassBytesModule m : classBytesModules) {
            if (!isActive(m)) continue;
            try {
                bytes = m.apply(bytes);
            } catch (Throwable t) {
                warn(m, "class bytes", t);
            }
        }
        return bytes;
    }

    /** Stage 3 — apply to parsed ClassNode before methods are decompiled. */
    public void applyClassNode(ClassNode cn) {
        for (ClassNodeModule m : classNodeModules) {
            if (!isActive(m)) continue;
            try {
                m.apply(cn);
            } catch (Throwable t) {
                warn(m, cn.name, t);
            }
        }
    }

    /** Stage 4 — apply to each MethodNode before stack simulation. */
    public void applyMethodNode(MethodNode mn) {
        for (MethodNodeModule m : methodNodeModules) {
            if (!isActive(m)) continue;
            try {
                m.apply(mn);
            } catch (Throwable t) {
                warn(m, mn.name + mn.desc, t);
            }
        }
    }

    /** Stage 5 — apply to the flat IR list after stack simulation. */
    public void applyIr(List<IrStmt> ir) {
        for (IrModule m : irModules) {
            if (!isActive(m)) continue;
            try {
                m.apply(ir);
            } catch (Throwable t) {
                warn(m, "IR list", t);
            }
        }
    }

    /** Stage 6 — apply to the CFG after basic-block construction. */
    public void applyCfg(List<BasicBlock> blocks) {
        for (CfgModule m : cfgModules) {
            if (!isActive(m)) continue;
            try {
                m.apply(blocks);
            } catch (Throwable t) {
                warn(m, "CFG", t);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Introspection
    // -------------------------------------------------------------------------

    public List<DeobfuscationModule> allModules() {
        List<DeobfuscationModule> all = new ArrayList<>();
        all.addAll(zipEntryModules);
        all.addAll(classBytesModules);
        all.addAll(classNodeModules);
        all.addAll(methodNodeModules);
        all.addAll(irModules);
        all.addAll(cfgModules);
        return Collections.unmodifiableList(all);
    }

    public boolean isEmpty() {
        return allModules().isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean isActive(DeobfuscationModule m) {
        return activeNames.isEmpty() || activeNames.contains(m.name());
    }

    private static void warn(DeobfuscationModule m, String context, Throwable t) {
        System.err.println("[" + m.name() + "] error at " + context + ": " + t.getMessage());
    }
}
