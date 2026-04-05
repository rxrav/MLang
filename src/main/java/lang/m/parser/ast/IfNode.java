package lang.m.parser.ast;

/**
 * An {@code if}/{@code else} conditional.
 *
 * <p>M syntax:
 * <pre>
 *   if condition { ... } else if other { ... } else { ... }
 * </pre>
 * Chained {@code else if} arms are represented as an {@code IfNode}
 * nested inside the {@code otherwise} field of the outer node.
 *
 * @param condition the boolean expression to test
 * @param then      block executed when {@code condition} is true
 * @param otherwise the else branch — either a {@link BlockNode} (bare else)
 *                  or another {@link IfNode} (else-if); may be {@code null}
 */
public record IfNode(Node condition, BlockNode then, Node otherwise) implements Node {}
