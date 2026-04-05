package lang.m.parser.ast;

/**
 * A mutable binding declaration ({@code var}).
 *
 * <p>M syntax: {@code var name: Type = expr}
 * The type annotation is optional when the type can be inferred.
 *
 * <p>Unlike {@code let}, the generated local variable allows
 * reassignment via {@link AssignNode}.
 *
 * @param name        the binding identifier
 * @param type        declared M type string, or {@code null} if inferred
 * @param initializer the initial value expression
 */
public record VarNode(String name, String type, Node initializer) implements Node {}
