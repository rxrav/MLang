package lang.m.parser.ast;

/**
 * A unary prefix expression.
 *
 * <p>Supported operators:
 * <ul>
 *   <li>{@code -} — numeric negation ({@code INEG}, {@code DNEG}, etc.)</li>
 *   <li>{@code !} — logical NOT (XOR with 1 on an int boolean)</li>
 * </ul>
 *
 * @param op      the operator string ({@code "-"} or {@code "!"})
 * @param operand the expression the operator is applied to
 */
public record UnaryNode(String op, Node operand) implements Node {}
