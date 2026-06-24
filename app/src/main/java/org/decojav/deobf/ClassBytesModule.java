package org.decojav.deobf;

/**
 * Stage 2 — called with the raw bytes of one .class file, before ASM parses them.
 *
 * Use this stage to:
 *   - Patch class bytes that would confuse ASM (e.g. invalid attribute lengths)
 *   - Decrypt or decompress class-level encryption not handled at the ZIP level
 */
public interface ClassBytesModule extends DeobfuscationModule {

    /**
     * @param classBytes  raw bytes of a .class file
     * @return modified bytes or the original bytes unchanged (must not return null)
     */
    byte[] apply(byte[] classBytes);
}
