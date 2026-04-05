package lang.m.parser.ast;

/**
 * Print a value to stdout ({@code print}).
 *
 * <p>MLang syntax: {@code print(expr)}
 *
 * <p>The code generator emits
 * {@code invokestatic MRuntime.print(...)}, choosing the appropriate
 * primitive overload when the static type is known.
 *
 * @param value the expression to print
 */
public record PrintNode(Node value) implements Node {}
