package lang.m.semantic;

/**
 * A resolved symbol stored in a {@link Scope}.
 *
 * <p>Symbols represent variables ({@code let}/@{code var}),
 * function parameters, or function names after semantic analysis.
 *
 * @param name    the identifier name as it appears in source
 * @param type    the resolved M type string, e.g. {@code "Int"}, {@code "Str"},
 *                {@code "Future<Int>"}; may be {@code null} before type inference
 * @param mutable {@code true} if declared with {@code var};
 *                {@code false} for {@code let} (immutable)
 * @param slot    JVM local variable table index used by the code generator;
 *                {@code -1} for module-level statics not yet assigned a slot
 */
public record Symbol(String name, String type, boolean mutable, int slot) {}
