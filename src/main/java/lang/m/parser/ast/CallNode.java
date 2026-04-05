package lang.m.parser.ast;

import java.util.List;

/**
 * A function or method call expression.
 *
 * <p>M syntax:
 * <pre>
 *   add(1, 2)          // top-level function call
 *   list.filter(fn)    // method call via dot access
 * </pre>
 *
 * @param callee the expression that resolves to the callable
 *               (typically an {@link IdentNode} or a dot-access {@link BinaryNode})
 * @param args   the ordered list of argument expressions
 */
public record CallNode(Node callee, List<Node> args) implements Node {}
