package lang.m.parser.ast;

/**
 * Reassignment to an existing mutable ({@code var}) binding.
 *
 * <p>M syntax: {@code name = expr}
 * The semantic analyser verifies that {@code name} was declared with
 * {@code var} and rejects attempts to reassign {@code let} bindings.
 *
 * @param name  the identifier of the mutable variable being updated
 * @param value the new value expression
 */
public record AssignNode(String name, Node value) implements Node {}
