package lang.m.parser.ast;

/**
 * A reference to a named variable, parameter, or function.
 *
 * <p>The semantic analyser resolves the name to a {@link lang.m.semantic.Symbol}
 * and annotates its type. The code generator then emits the appropriate
 * {@code xLOAD} or {@code GETSTATIC} instruction.
 *
 * @param name the identifier string as it appears in source
 */
public record IdentNode(String name) implements Node {}
