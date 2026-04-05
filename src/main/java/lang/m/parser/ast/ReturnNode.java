package lang.m.parser.ast;

/**
 * A {@code return} statement.
 *
 * <p>M syntax: {@code return expr}
 * In void ({@code Unit}) functions, the value may be {@code null}.
 *
 * @param value the expression to return, or {@code null} for a bare {@code return}
 */
public record ReturnNode(Node value) implements Node {}
