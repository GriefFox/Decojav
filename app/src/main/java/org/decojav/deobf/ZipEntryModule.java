package org.decojav.deobf;

/**
 * Stage 1 — called for every entry read from a JAR, before ASM parses it.
 *
 * Use this stage to:
 *   - Decrypt encrypted class bytes before the ClassReader sees them
 *   - Patch magic numbers or other byte-level tricks
 *   - Filter out entries entirely (return null to skip)
 */
public interface ZipEntryModule extends DeobfuscationModule {

    /**
     * @param entryName  the ZIP entry path, e.g. "com/example/Foo.class"
     * @param rawBytes   the raw bytes of this entry
     * @return modified bytes, the original bytes unchanged, or null to skip the entry
     */
    byte[] apply(String entryName, byte[] rawBytes);
}
