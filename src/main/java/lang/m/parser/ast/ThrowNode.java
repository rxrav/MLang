package lang.m.parser.ast;

/**
 * Throw an exception ({@code throw}).
 *
 * <p>M syntax: {@code throw RuntimeException("invalid state")}
 *
 * <p>The code generator evaluates the expression, verifies it is a subtype of
 * {@code Throwable}, then emits {@code ATHROW}.
 *
 * @param exception the expression producing the throwable object
 */
public record ThrowNode(Node exception) implements Node {}
