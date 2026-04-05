package lang.m.codegen;

import org.objectweb.asm.Opcodes;

import java.util.Map;

/**
 * Static look-up tables mapping M type names to their JVM equivalents.
 *
 * <p>Used throughout the {@link Compiler} to emit correctly-typed
 * bytecode instructions without scattering type-string comparisons
 * across the code generator.
 *
 * <h2>M → JVM type mapping</h2>
 * <pre>
 *   Int    → I   (int)
 *   Long   → J   (long)
 *   Float  → F   (float)
 *   Double → D   (double)
 *   Bool   → Z   (boolean)
 *   Byte   → B   (byte)
 *   Char   → C   (char)
 *   Str    → Ljava/lang/String;
 *   Any    → Ljava/lang/Object;
 *   Unit   → V   (void)
 * </pre>
 */
public final class TypeMap {

    private TypeMap() {}

    /**
     * M type name → JVM field/method descriptor character or ref descriptor.
     * E.g. {@code "Int"} → {@code "I"}, {@code "Str"} → {@code "Ljava/lang/String;"}
     */
    public static final Map<String, String> DESCRIPTORS = Map.ofEntries(
        Map.entry("Int",    "I"),
        Map.entry("Long",   "J"),
        Map.entry("Float",  "F"),
        Map.entry("Double", "D"),
        Map.entry("Bool",   "Z"),
        Map.entry("Byte",   "B"),
        Map.entry("Char",   "C"),
        Map.entry("Str",    "Ljava/lang/String;"),
        Map.entry("Any",    "Ljava/lang/Object;"),
        Map.entry("Unit",   "V")
    );

    /**
     * M type name → JVM internal class name (slash-separated).
     * Only populated for reference types that have a corresponding class.
     * E.g. {@code "Str"} → {@code "java/lang/String"}
     */
    public static final Map<String, String> CLASS_NAMES = Map.ofEntries(
        Map.entry("Str",    "java/lang/String"),
        Map.entry("Any",    "java/lang/Object"),
        Map.entry("List",   "java/util/List"),
        Map.entry("Map",    "java/util/Map"),
        Map.entry("Set",    "java/util/Set"),
        Map.entry("Future", "java/util/concurrent/CompletableFuture")
    );

    /**
     * Return the correct JVM {@code xLOAD} opcode for the given M type.
     * Reference types (including {@code Str}, {@code Any}) all use {@code ALOAD}.
     *
     * @param mType M type name, e.g. {@code "Int"}, {@code "Str"}
     * @return the corresponding ASM {@link org.objectweb.asm.Opcodes} constant
     */
    public static int loadOpcode(String mType) {
        return switch (mType) {
            case "Int", "Bool", "Byte", "Char" -> Opcodes.ILOAD;
            case "Long"   -> Opcodes.LLOAD;
            case "Float"  -> Opcodes.FLOAD;
            case "Double" -> Opcodes.DLOAD;
            default       -> Opcodes.ALOAD;
        };
    }

    /**
     * Return the correct JVM {@code xSTORE} opcode for the given M type.
     *
     * @param mType M type name
     * @return the corresponding ASM {@link org.objectweb.asm.Opcodes} constant
     */
    public static int storeOpcode(String mType) {
        return switch (mType) {
            case "Int", "Bool", "Byte", "Char" -> Opcodes.ISTORE;
            case "Long"   -> Opcodes.LSTORE;
            case "Float"  -> Opcodes.FSTORE;
            case "Double" -> Opcodes.DSTORE;
            default       -> Opcodes.ASTORE;
        };
    }

    /**
     * Return the correct JVM {@code xRETURN} opcode for the given M type.
     * {@code Unit} maps to {@code RETURN} (void).
     *
     * @param mType M return type name
     * @return the corresponding ASM {@link org.objectweb.asm.Opcodes} constant
     */
    public static int returnOpcode(String mType) {
        return switch (mType) {
            case "Int", "Bool", "Byte", "Char" -> Opcodes.IRETURN;
            case "Long"   -> Opcodes.LRETURN;
            case "Float"  -> Opcodes.FRETURN;
            case "Double" -> Opcodes.DRETURN;
            case "Unit"   -> Opcodes.RETURN;
            default       -> Opcodes.ARETURN;
        };
    }
}
