package lang.m.parser.ast;

/**
 * A binary infix expression.
 *
 * <p>Supported operators ({@code op} string values):
 * <pre>
 *   Arithmetic : +  -  *  /  %  **
 *   Comparison : ==  !=  &lt;  &lt;=  &gt;  &gt;=  ===
 *   Logical    : &amp;&amp;  ||
 *   Pipeline   : |&gt;
 *   Range      : ..  ..&lt;
 *   Access     : .  ?.
 * </pre>
 *
 * @param left  left-hand side operand
 * @param op    the operator string (see above)
 * @param right right-hand side operand
 */
public record BinaryNode(Node left, String op, Node right) implements Node {}
