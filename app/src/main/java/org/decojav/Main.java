package org.decojav;

import org.decojav.deobf.DeobfuscationModule;
import org.decojav.deobf.ModuleRegistry;
import org.decojav.deobf.modules.ConstantFoldModule;
import org.decojav.deobf.modules.DeadStoreModule;
import org.decojav.deobf.modules.NopStripModule;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.RecordComponentNode;
import org.objectweb.asm.tree.TypeInsnNode;

import javax.tools.ToolProvider;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class Main {

    private static final String[] BLOCKED_PREFIXES = {"/proc/", "/sys/", "/dev/", "/etc/"};

    public static void main(String[] args) throws Exception {
        boolean showOpcodes = false;
        boolean listModules = false;
        String  filePath    = null;
        String  classFilter = null;
        Path    modulesDir  = null;
        Set<String> deobfNames = new HashSet<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--opcodes", "-o" -> showOpcodes = true;
                case "--list-modules"  -> listModules = true;
                case "--deobf" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("--deobf requires a value: --deobf name1,name2");
                        printUsage(); System.exit(1);
                    }
                    deobfNames.addAll(Arrays.asList(args[++i].split(",")));
                }
                case "--modules-dir" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("--modules-dir requires a path");
                        printUsage(); System.exit(1);
                    }
                    modulesDir = Paths.get(args[++i]);
                }
                default -> {
                    if (arg.startsWith("-")) {
                        System.err.println("Unknown option: " + arg);
                        printUsage(); System.exit(1);
                    }
                    if (filePath == null) filePath = arg;
                    else if (classFilter == null) classFilter = arg;
                    else {
                        System.err.println("Error: too many arguments.");
                        printUsage(); System.exit(1);
                    }
                }
            }
        }

        // ---- initialise deobfuscation module system ----

        // Register built-in modules (always available, run unless --deobf limits the set)
        ModuleRegistry registry = ModuleRegistry.INSTANCE;
        registry.register(NopStripModule.INSTANCE);
        registry.register(ConstantFoldModule.INSTANCE);
        registry.register(DeadStoreModule.INSTANCE);
        if (modulesDir != null) {
            registry.loadFromDirectory(modulesDir);  // no-op until Phase J2
        } else {
            // TODO Phase J2: auto-discover modules/ next to the run script
            Path defaultModulesDir = Paths.get(System.getenv().getOrDefault("DECOJAV_CWD", "."), "modules");
            if (Files.isDirectory(defaultModulesDir)) {
                registry.loadFromDirectory(defaultModulesDir); // no-op until Phase J2
            }
        }
        if (!deobfNames.isEmpty()) registry.setActiveNames(deobfNames);

        // ---- --list-modules ----
        if (listModules) {
            listModules(registry);
            return;
        }

        if (filePath == null) {
            printUsage();
            return;
        }

        Path target = Paths.get(filePath);
        validateFile(target);

        if (filePath.endsWith(".jar")) {
            if (classFilter != null && classFilter.endsWith(".class")) {
                classFilter = classFilter.substring(0, classFilter.length() - 6);
            }
            decompileJar(target, classFilter, showOpcodes);
            return;
        }

        if (classFilter != null) {
            System.err.println("Error: class name filter is only valid for .jar files.");
            System.exit(1);
        }

        if (filePath.endsWith(".java")) {
            target = compileJava(target);
        }

        if (!Files.exists(target)) {
            System.err.println("File not found: " + target);
            System.exit(1);
        }

        // Stage 2: class bytes
        byte[] bytes = ModuleRegistry.INSTANCE.applyClassBytes(Files.readAllBytes(target));
        System.out.println("=== " + target.getFileName() + " ===\n");
        MainDecompiler.printFileMetadata(target);
        printClassMethods(bytes, showOpcodes, System.out);
    }

    // -------------------------------------------------------------------------
    // JAR decompilation
    // -------------------------------------------------------------------------

    private static void decompileJar(Path jarPath, String classFilter, boolean showOpcodes) throws Exception {
        String jarName = jarPath.getFileName().toString();
        String baseName = jarName.substring(0, jarName.length() - 4);
        String cwd = System.getenv("DECOJAV_CWD");
        Path outDir = cwd != null ? Paths.get(cwd, baseName) : Paths.get(baseName);

        boolean simpleNameFilter = classFilter != null
                && !classFilter.contains(".") && !classFilter.contains("/");
        String filterSlash = classFilter != null ? classFilter.replace('.', '/') : null;

        if (classFilter == null) {
            Files.createDirectories(outDir);
            System.out.println("Decompiling " + jarName + " → " + outDir.getFileName() + "/");
        }

        boolean found = false;
        int count = 0;

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(jarPath.toFile()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) { zis.closeEntry(); continue; }
                String name = entry.getName();
                if (!name.endsWith(".class")) { zis.closeEntry(); continue; }

                byte[] bytes;
                try {
                    bytes = zis.readAllBytes();
                } catch (Exception e) {
                    System.err.println("  [skip] " + name + ": " + e.getMessage());
                    zis.closeEntry();
                    continue;
                }

                // Stage 1: ZIP entry bytes
                bytes = ModuleRegistry.INSTANCE.applyZipEntry(name, bytes);
                if (bytes == null) { zis.closeEntry(); continue; } // module signalled: skip

                String nameWithoutExt = name.substring(0, name.length() - 6);

                if (classFilter != null) {
                    boolean matches;
                    if (simpleNameFilter) {
                        String simpleName = nameWithoutExt.contains("/")
                                ? nameWithoutExt.substring(nameWithoutExt.lastIndexOf('/') + 1)
                                : nameWithoutExt;
                        matches = simpleName.equals(classFilter);
                    } else {
                        matches = nameWithoutExt.equals(filterSlash);
                    }
                    if (matches) {
                        System.out.println("=== " + name + " (from " + jarName + ") ===\n");
                        printClassMethods(bytes, showOpcodes, System.out);
                        found = true;
                        break;
                    }
                } else {
                    Path outFile = outDir.resolve(nameWithoutExt + ".java");
                    Files.createDirectories(outFile.getParent());
                    try (PrintStream ps = new PrintStream(new FileOutputStream(outFile.toFile()))) {
                        writeClassAsJava(ps, bytes, showOpcodes);
                    }
                    System.out.println("  " + nameWithoutExt + ".java");
                    count++;
                }

                zis.closeEntry();
            }
        }

        if (classFilter != null && !found) {
            System.err.println("Class not found in JAR: " + classFilter);
            System.exit(1);
        }
        if (classFilter == null) {
            System.out.println("Done. " + count + " class" + (count == 1 ? "" : "es") + " written to "
                    + outDir.getFileName() + "/");
        }
    }

    // -------------------------------------------------------------------------
    // Per-class output helpers
    // -------------------------------------------------------------------------

    /**
     * Applies stages 2 (class bytes) and 3 (ClassNode) and returns the parsed ClassNode.
     * All class parsing goes through here so modules are never bypassed.
     */
    private static ClassNode parseClass(byte[] bytes) {
        // Stage 2: class bytes modules
        bytes = ModuleRegistry.INSTANCE.applyClassBytes(bytes);

        org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(bytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        // Stage 3: ClassNode modules
        ModuleRegistry.INSTANCE.applyClassNode(cn);

        return cn;
    }

    /** Prints each method to {@code out} in the same format as the .class flow. */
    private static void printClassMethods(byte[] bytes, boolean showOpcodes, PrintStream out) {
        ClassNode cn = parseClass(bytes);

        for (MethodNode mn : cn.methods) {
            if (mn.name.equals("<clinit>")) continue;
            if (mn.name.equals("<init>") && mn.desc.equals("()V")) continue;
            out.println("\n--- " + mn.name + " ---");
            try {
                out.println(SimpleMethodDecompiler.decompileMethod(bytes, mn.name));
                if (showOpcodes) {
                    out.println("  // opcodes:");
                    out.print(MainDecompiler.disassembleMethod(mn));
                }
            } catch (UnsupportedOperationException e) {
                out.println("  [unsupported opcode] " + e.getMessage());
            } catch (Throwable e) {
                out.println("  [error] " + e.getMessage());
            }
        }
    }

    /** Writes a class as a .java file with package/class wrapper around decompiled methods. */
    private static void writeClassAsJava(PrintStream out, byte[] bytes, boolean showOpcodes) {
        ClassNode cn = parseClass(bytes);

        String className = cn.name;
        String simpleName = className.contains("/")
                ? className.substring(className.lastIndexOf('/') + 1)
                : className;

        if (className.contains("/")) {
            out.println("package " + className.substring(0, className.lastIndexOf('/')).replace('/', '.') + ";");
            out.println();
        }

        List<String> imports = buildImports(cn);
        if (!imports.isEmpty()) {
            imports.forEach(out::println);
            out.println();
        }

        boolean isRecord = (cn.access & Opcodes.ACC_RECORD) != 0;

        // Build record component info (used in header, field filtering, method filtering)
        List<RecordComponentNode> components =
                (isRecord && cn.recordComponents != null) ? cn.recordComponents : List.of();
        Set<String> componentNames = new HashSet<>();
        Map<String, String> componentAccessorDescs = new HashMap<>();
        for (RecordComponentNode rc : components) {
            componentNames.add(rc.name);
            componentAccessorDescs.put(rc.name, "()" + rc.descriptor);
        }

        // Class header: access + keyword + name + extends + implements
        StringBuilder classHeader = new StringBuilder();
        classHeader.append(classAccess(cn.access)).append(classKeyword(cn.access)).append(simpleName);

        // Records: append (compType compName, ...) instead of class body declaration
        if (isRecord && !components.isEmpty()) {
            classHeader.append("(");
            for (int i = 0; i < components.size(); i++) {
                if (i > 0) classHeader.append(", ");
                RecordComponentNode rc = components.get(i);
                classHeader.append(descToJavaType(rc.descriptor)).append(" ").append(rc.name);
            }
            classHeader.append(")");
        }

        if (cn.superName != null
                && !cn.superName.equals("java/lang/Object")
                && !cn.superName.equals("java/lang/Enum")
                && !cn.superName.equals("java/lang/Record")) {
            classHeader.append(" extends ").append(simpleClassName(cn.superName));
        }
        if (cn.interfaces != null && !cn.interfaces.isEmpty()) {
            String kw = (cn.access & Opcodes.ACC_INTERFACE) != 0 ? " extends " : " implements ";
            classHeader.append(kw);
            for (int i = 0; i < cn.interfaces.size(); i++) {
                if (i > 0) classHeader.append(", ");
                classHeader.append(simpleClassName(cn.interfaces.get(i)));
            }
        }
        out.println(classHeader + " {");

        // Fields — skip record component backing fields (they're declared in the header)
        if (cn.fields != null) {
            for (FieldNode fn : cn.fields) {
                if (isRecord && componentNames.contains(fn.name)) continue;
                out.println();
                out.println("    " + fieldAccess(fn.access) + descToJavaType(fn.desc) + " " + fn.name + ";");
            }
        }

        // Build canonical constructor descriptor for records (to skip it)
        String canonicalCtorDesc = null;
        if (isRecord) {
            StringBuilder cd = new StringBuilder("(");
            for (RecordComponentNode rc : components) cd.append(rc.descriptor);
            cd.append(")V");
            canonicalCtorDesc = cd.toString();
        }

        // Methods (including non-trivial constructors)
        SimpleMethodDecompiler.ENCLOSING_CLASS.set(simpleName);
        try {
            for (MethodNode mn : cn.methods) {
                if (mn.name.equals("<clinit>")) continue;
                if (mn.name.equals("<init>") && isTrivialConstructor(mn)) continue;

                // Skip auto-generated record methods
                if (isRecord) {
                    if (mn.name.equals("<init>") && mn.desc.equals(canonicalCtorDesc)) continue;
                    if (mn.name.equals("toString") && mn.desc.equals("()Ljava/lang/String;")) continue;
                    if (mn.name.equals("hashCode") && mn.desc.equals("()I")) continue;
                    if (mn.name.equals("equals")   && mn.desc.equals("(Ljava/lang/Object;)Z")) continue;
                    String accDesc = componentAccessorDescs.get(mn.name);
                    if (accDesc != null && accDesc.equals(mn.desc)) continue;
                }

                out.println();
                String label = mn.name.equals("<init>") ? simpleName : mn.name;
                out.println("    // --- " + label + " ---");
                try {
                    for (String line : SimpleMethodDecompiler.decompileMethodNode(mn).split("\n", -1)) {
                        out.println("    " + line);
                    }
                    if (showOpcodes) {
                        out.println("    // opcodes:");
                        for (String line : MainDecompiler.disassembleMethod(mn).split("\n", -1)) {
                            out.println("    " + line);
                        }
                    }
                } catch (UnsupportedOperationException e) {
                    out.println("    // [unsupported] " + e.getMessage());
                } catch (Throwable e) {
                    out.println("    // [error] " + e.getMessage());
                }
            }
        } finally {
            SimpleMethodDecompiler.ENCLOSING_CLASS.remove();
        }

        out.println("}");
    }

    private static String classAccess(int access) {
        StringBuilder sb = new StringBuilder();
        if ((access & Opcodes.ACC_PUBLIC) != 0)   sb.append("public ");
        if ((access & Opcodes.ACC_ABSTRACT) != 0
                && (access & Opcodes.ACC_INTERFACE) == 0) sb.append("abstract ");
        if ((access & Opcodes.ACC_FINAL) != 0)    sb.append("final ");
        return sb.toString();
    }

    private static String classKeyword(int access) {
        if ((access & Opcodes.ACC_ANNOTATION) != 0) return "@interface ";
        if ((access & Opcodes.ACC_INTERFACE) != 0)  return "interface ";
        if ((access & Opcodes.ACC_ENUM) != 0)        return "enum ";
        if ((access & Opcodes.ACC_RECORD) != 0)      return "record ";
        return "class ";
    }

    private static String fieldAccess(int access) {
        StringBuilder sb = new StringBuilder();
        if ((access & Opcodes.ACC_PUBLIC)    != 0) sb.append("public ");
        else if ((access & Opcodes.ACC_PROTECTED) != 0) sb.append("protected ");
        else if ((access & Opcodes.ACC_PRIVATE)   != 0) sb.append("private ");
        if ((access & Opcodes.ACC_STATIC) != 0) sb.append("static ");
        if ((access & Opcodes.ACC_FINAL)  != 0) sb.append("final ");
        if ((access & Opcodes.ACC_VOLATILE)   != 0) sb.append("volatile ");
        if ((access & Opcodes.ACC_TRANSIENT)  != 0) sb.append("transient ");
        return sb.toString();
    }

    private static String descToJavaType(String desc) {
        int arrays = 0;
        while (arrays < desc.length() && desc.charAt(arrays) == '[') arrays++;
        String base = desc.substring(arrays);
        String javaBase = switch (base) {
            case "I" -> "int";    case "J" -> "long";
            case "F" -> "float";  case "D" -> "double";
            case "Z" -> "boolean"; case "B" -> "byte";
            case "C" -> "char";   case "S" -> "short";
            case "V" -> "void";
            default  -> {
                // Object type: Ljava/lang/String; → String
                String cls = base.startsWith("L") && base.endsWith(";")
                        ? base.substring(1, base.length() - 1) : base;
                yield simpleClassName(cls);
            }
        };
        return javaBase + "[]".repeat(arrays);
    }

    private static String simpleClassName(String slashName) {
        int slash = slashName.lastIndexOf('/');
        String dotName = slash >= 0 ? slashName.substring(slash + 1) : slashName;
        // Handle inner classes: Outer$Inner → Inner
        int dollar = dotName.lastIndexOf('$');
        return dollar >= 0 ? dotName.substring(dollar + 1) : dotName;
    }

    /**
     * Collects all external class references from the ClassNode and returns sorted
     * "import pkg.Name;" strings. References in java.lang and the same package as cn
     * are excluded. On simple-name collision, the first-seen class wins.
     */
    private static List<String> buildImports(ClassNode cn) {
        String thisPackage = cn.name.contains("/")
                ? cn.name.substring(0, cn.name.lastIndexOf('/'))
                : "";

        // simpleName → "import fully.qualified.Name;"  (first-seen wins on collision)
        Map<String, String> imports = new LinkedHashMap<>();

        addType(cn.superName, thisPackage, imports);
        if (cn.interfaces != null)       cn.interfaces.forEach(n -> addType(n, thisPackage, imports));
        if (cn.recordComponents != null) cn.recordComponents.forEach(rc -> addFromDesc(rc.descriptor, thisPackage, imports));
        if (cn.fields != null)           cn.fields.forEach(fn -> addFromDesc(fn.desc, thisPackage, imports));
        if (cn.methods != null) {
            for (MethodNode mn : cn.methods) {
                addFromDesc(mn.desc, thisPackage, imports);
                if (mn.instructions == null) continue;
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof MethodInsnNode m) {
                        addType(m.owner, thisPackage, imports);
                        addFromDesc(m.desc, thisPackage, imports);
                    } else if (insn instanceof FieldInsnNode f) {
                        addType(f.owner, thisPackage, imports);
                        addFromDesc(f.desc, thisPackage, imports);
                    } else if (insn instanceof TypeInsnNode t) {
                        addType(t.desc, thisPackage, imports);
                    } else if (insn instanceof MultiANewArrayInsnNode m) {
                        addFromDesc(m.desc, thisPackage, imports);
                    } else if (insn instanceof LdcInsnNode l && l.cst instanceof Type t
                            && t.getSort() == Type.OBJECT) {
                        addType(t.getInternalName(), thisPackage, imports);
                    }
                }
            }
        }

        return imports.values().stream().sorted().toList();
    }

    /** Registers a single internal class name (e.g., {@code java/util/List}) as an import candidate. */
    private static void addType(String internalName, String thisPackage, Map<String, String> imports) {
        if (internalName == null) return;
        String name = internalName;
        // Strip leading array dimensions
        int i = 0;
        while (i < name.length() && name.charAt(i) == '[') i++;
        name = name.substring(i);
        // Strip descriptor form: Ljava/lang/String; → java/lang/String
        if (name.startsWith("L") && name.endsWith(";")) name = name.substring(1, name.length() - 1);
        if (name.length() <= 1 || !name.contains("/")) return; // primitive or default pkg

        String pkg = name.substring(0, name.lastIndexOf('/'));
        if (pkg.equals("java/lang") || pkg.equals(thisPackage)) return;

        String simpleName = simpleClassName(name);
        String fqn = name.replace('/', '.').replace('$', '.');
        imports.putIfAbsent(simpleName, "import " + fqn + ";");
    }

    /** Extracts all {@code Lsome/Class;} references from a descriptor and registers each. */
    private static void addFromDesc(String desc, String thisPackage, Map<String, String> imports) {
        if (desc == null) return;
        int i = 0;
        while (i < desc.length()) {
            if (desc.charAt(i) == 'L') {
                int end = desc.indexOf(';', i + 1);
                if (end < 0) break;
                addType(desc.substring(i + 1, end), thisPackage, imports);
                i = end + 1;
            } else {
                i++;
            }
        }
    }

    /** A trivial constructor only calls super() and returns — no additional logic. */
    private static boolean isTrivialConstructor(MethodNode mn) {
        int realOps = 0;
        boolean hasInit = false;
        for (var n : mn.instructions) {
            int op = n.getOpcode();
            if (op < 0) continue;
            realOps++;
            if (op == Opcodes.INVOKESPECIAL
                    && ((org.objectweb.asm.tree.MethodInsnNode) n).name.equals("<init>")) {
                hasInit = true;
            }
        }
        return realOps <= 3 && hasInit;
    }

    // -------------------------------------------------------------------------
    // Module listing
    // -------------------------------------------------------------------------

    private static void listModules(ModuleRegistry registry) {
        // TODO Phase J2: modules loaded from modules/ directory will appear here
        var modules = registry.allModules();
        if (modules.isEmpty()) {
            System.out.println("No deobfuscation modules registered.");
            System.out.println("(Place module JARs in the modules/ directory to load them.)");
            return;
        }
        System.out.println("Registered deobfuscation modules:");
        for (DeobfuscationModule m : modules) {
            System.out.printf("  %-30s %s%n", m.name(), m.description());
        }
    }

    // -------------------------------------------------------------------------
    // Validation & compilation helpers
    // -------------------------------------------------------------------------

    private static void printUsage() {
        System.out.println("Usage: ./run [options] <file.java|file.class|file.jar> [ClassName]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --opcodes, -o          Print raw bytecode opcodes for each method");
        System.out.println("  --list-modules         List all loaded deobfuscation modules");
        System.out.println("  --deobf <n1>[,<n2>]   Run only the named deobfuscation module(s)");
        System.out.println("  --modules-dir <path>   Load module JARs from this directory");
        System.out.println("                         (default: modules/ next to the run script)");
        System.out.println();
        System.out.println("Arguments:");
        System.out.println("  file.java        Compile and decompile a Java source file");
        System.out.println("  file.class       Decompile a compiled class file");
        System.out.println("  file.jar         Decompile a JAR; all classes written to a new folder");
        System.out.println("  ClassName        (JAR only) Decompile only this class; prints to stdout");
        System.out.println("                   Accepts: Foo  or  com.example.Foo  or  com/example/Foo");
        System.out.println();
        System.out.println("Without ClassName, all classes in the JAR are written to a folder");
        System.out.println("  named after the JAR  (e.g., App.jar → App/).");
        System.out.println();
        System.out.println("Only .java, .class, and .jar files are accepted.");
        System.out.println("Reading from /proc, /sys, /dev, /etc is not allowed.");
    }

    private static void validateFile(Path path) {
        String name = path.getFileName().toString();
        if (!name.endsWith(".java") && !name.endsWith(".class") && !name.endsWith(".jar")) {
            System.err.println("Error: only .java, .class, and .jar files are supported (got: " + name + ")");
            System.exit(1);
        }
        String abs = path.toAbsolutePath().normalize().toString();
        for (String blocked : BLOCKED_PREFIXES) {
            if (abs.startsWith(blocked)) {
                System.err.println("Error: reading from " + blocked.replaceAll("/$", "") + " is not allowed.");
                System.exit(1);
            }
        }
    }

    private static Path compileJava(Path src) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            System.err.println("JavaCompiler not available – run with a JDK");
            System.exit(1);
        }
        Path outDir = Files.createTempDirectory("decojav-");
        int rc = compiler.run(null, null, null, "-g", "-d", outDir.toString(), src.toString());
        if (rc != 0) { System.err.println("Compilation failed"); System.exit(1); }
        return Files.walk(outDir)
                .filter(p -> p.toString().endsWith(".class"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No .class produced"));
    }
}
