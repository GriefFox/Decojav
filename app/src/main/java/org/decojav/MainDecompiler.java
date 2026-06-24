package org.decojav;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

public class MainDecompiler {

    public static void printFileMetadata(Path classFile) throws IOException {
        byte[] bytes = Files.readAllBytes(classFile);
        ClassReader reader = new ClassReader(bytes);

        System.out.println("=== CLASS FILE METADATA ===");
        System.out.println("Magic: 0xCAFEBABE"); // все .class начинаются с этого
        System.out.println("Minor version: " + reader.readUnsignedShort(4));
        System.out.println("Major version: " + reader.readUnsignedShort(6));

        int access = reader.getAccess();
        System.out.println("Access flags: " + Integer.toHexString(access));

        System.out.println("Class name: " + reader.getClassName().replace('/', '.'));
        System.out.println("Super class: " + reader.getSuperName().replace('/', '.'));

        String[] interfaces = new String[reader.getInterfaces().length];
        for (int i = 0; i < interfaces.length; i++) {
            interfaces[i] = reader.getInterfaces()[i].replace('/', '.');
        }
        System.out.println("Interfaces: ");
        for (String iface : interfaces) {
            System.out.println("  " + iface);
        }

        System.out.println("Constant pool count: " + reader.getItemCount());
    }
    
    public static String disassembleMethod(MethodNode mn) {
        Textifier printer = new Textifier();
        TraceMethodVisitor tmv = new TraceMethodVisitor(printer);
        mn.accept(tmv);
        StringBuilder sb = new StringBuilder();
        for (Object chunk : printer.getText()) {
            sb.append("  ").append(chunk.toString().stripTrailing()).append("\n");
        }
        return sb.toString();
    }

}