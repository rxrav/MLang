package lang.m.parser.ast;

/**
 * An immutable binding declaration ({@code let}).
 *
 * <p>M syntax: {@code let name: Type = expr}
 * The type annotation is optional when the type can be inferred from
 * the initialiser expression.
 *
 * <p>The code generator emits a local variable with the {@code final}
 * semantic — reassignment is a semantic error.
 *
 * @param name        the binding identifier
 * @param type        declared M type string, or {@code null} if inferred
 * @param initializer the expression whose value the binding holds
 */
public record LetNode(String name, String type, Node initializer) implements Node {}
