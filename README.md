# decojav

A from-scratch Java bytecode decompiler with a pluggable deobfuscation pipeline. Given a `.class`, `.jar`, or `.java` file, decojav parses the bytecode with [ASM](https://asm.ow2.io/) and reconstructs readable Java source — control flow (if/else, loops, switches), expressions, and types — rather than just disassembling opcodes.

## Features

- Decompiles `.class` files, whole `.jar` archives, or `.java` source (compiled on the fly) into readable Java
- Reconstructs structured control flow (loops, if/else, switches) from a flat instruction stream instead of emitting `goto`/label soup
- Optional raw opcode dump alongside the decompiled source (`--opcodes`)
- A six-stage deobfuscation module pipeline that can rewrite bytecode/IR at any point between "raw class bytes" and "basic-block CFG"
- Three built-in deobfuscation modules: NOP stripping, constant folding, and dead-store elimination
- Class-file metadata inspection (magic, version, access flags, constant pool size, superclass/interfaces)

## Usage

```
./run [options] <file.java|file.class|file.jar> [ClassName]
```

Options:

| Flag | Description |
|---|---|
| `--opcodes`, `-o` | Print raw bytecode opcodes for each method alongside the decompiled source |
| `--list-modules` | List all registered deobfuscation modules |
| `--deobf <n1>[,<n2>]` | Run only the named deobfuscation module(s) instead of all of them |
| `--modules-dir <path>` | Load module JARs from this directory (default: `modules/` next to the run script) |

Arguments:

- `file.java` — compiled with the system JDK and then decompiled
- `file.class` — decompiled directly
- `file.jar` — every class is decompiled and written to a folder named after the JAR (e.g. `App.jar` → `App/`)
- `ClassName` — (JAR only) decompile just this one class and print it to stdout instead of writing a folder. Accepts `Foo`, `com.example.Foo`, or `com/example/Foo`.

Only `.java`, `.class`, and `.jar` files are accepted.

### Examples

```
./run Hello.class
./run --opcodes Hello.class
./run App.jar                  # unpacks every class into App/
./run App.jar com.example.Foo  # decompile just one class, print to stdout
./run --list-modules
./run --deobf constant-fold,dead-store Hello.class
```

## Building

The project uses the Gradle wrapper (Java 21 toolchain):

```
./gradlew build      # compile + run tests
./gradlew test       # run tests only
./gradlew :app:fatJar # build a self-contained decojav.jar in the repo root
```

The fat JAR bundles ASM and Guava and can be run standalone: `java -jar decojav.jar <args>`.

## Architecture

The decompiler runs each class through a pipeline of stages; a `DeobfuscationModule` can hook into any one of them:

1. **ZIP entry bytes** (`ZipEntryModule`) — raw bytes of a single entry inside a JAR, before it's even recognized as a class
2. **Class bytes** (`ClassBytesModule`) — raw `.class` bytes, before `ClassReader` parses them
3. **ClassNode** (`ClassNodeModule`) — the parsed ASM `ClassNode`, before methods are decompiled
4. **MethodNode** (`MethodNodeModule`) — a single method's ASM `MethodNode`, before stack simulation
5. **IR** (`IrModule`) — the flat list of IR statements produced by stack simulation
6. **CFG** (`CfgModule`) — the basic-block control-flow graph, after control-flow reconstruction

Modules are registered on `ModuleRegistry.INSTANCE` (built-ins are wired up in `Main`), applied in registration order within each stage, and can be restricted at runtime with `--deobf`. `ModuleRegistry.loadFromDirectory` is stubbed in for loading third-party module JARs via `ServiceLoader` (not fully wired up yet — see `TODO Phase J2` comments).

Key source files (`app/src/main/java/org/decojav/`):

- `Main.java` — CLI entry point, argument parsing, JAR unpacking, `.java` output formatting
- `MainDecompiler.java` — class-file metadata printing and raw opcode disassembly (via ASM's `Textifier`)
- `SimpleMethodDecompiler.java` — core decompiler: stack simulation, expression/statement IR, type inference
- `StructuredEmitter.java` — turns the IR/CFG into structured Java source text (loops, if/else, switches)
- `BasicBlock.java` / `CfgBuilder.java` — control-flow graph construction from the instruction stream
- `deobf/` — the module system: `DeobfuscationModule`, the six stage interfaces, `ModuleRegistry`, and built-in modules under `deobf/modules/`

## Testing

Tests live under `app/src/test/java/org/decojav/` and use JUnit 5. Most tests compile a small Java snippet at runtime with the system compiler, run it through the decompiler, and assert on the resulting output (e.g. that a loop reconstructs correctly, or that a given opcode round-trips).

```
./gradlew test
```

## Known bugs / limitations

- **`try`/`catch`/`finally` isn't reconstructed.** `CfgBuilder` doesn't process `MethodNode.tryCatchBlocks` or exception-handler edges, and `StructuredEmitter` has no `try`/`catch` emission path at all, so exception-handling code doesn't come back out as structured Java (see `SimpleMethodDecompiler.java` around the `CaughtExceptionExpr` handling, and the absence of any try/catch logic in `StructuredEmitter.java`).
- **Real lambda bodies aren't decompiled**, only method references. `foldLambdaRef` in `SimpleMethodDecompiler.java` turns `ClassName::method` / `::new` into correct code, but a captured lambda body (`name.startsWith("lambda$")`) is emitted as a stub: `(args) -> { /* lambda */ }` rather than the actual logic.
- **No labeled `break`/`continue`.** `StructuredEmitter.emitSequence` only tracks a single `loopExit`/loop-header pair, so `break`/`continue` targeting an outer loop from inside a nested loop isn't representable — it will at best break/continue the wrong (innermost) loop.
- **Unsupported opcodes abort the whole method**, not just the offending expression. `SimpleMethodDecompiler` throws `UnsupportedOperationException` on unrecognized opcodes, and `Main` catches it per-method and prints a `[unsupported opcode]` placeholder instead of any of that method's body.
- **Inner/anonymous/local classes are decompiled independently**, not nested inside their enclosing class. Each `.class` entry in a JAR (including `Outer$1`, `Outer$Inner`, etc.) becomes its own flat top-level `.java` file rather than being reassembled into the outer class.
- **External module loading is unfinished.** `--modules-dir` and the default `modules/` directory lookup are wired up in the CLI and `ModuleRegistry.loadFromDirectory` has a working `ServiceLoader` implementation, but the call sites in `Main.java` are commented `// no-op until Phase J2` and this path isn't covered by tests — treat dynamic module loading as unverified.
