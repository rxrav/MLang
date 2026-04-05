package lang.m.parser.ast;

import java.util.List;

/**
 * A function declaration.
 *
 * <p>M syntax:
 * <pre>
 *   fn greet(name: Str): Str { return "Hello, ${name}" }
 *   fn add(a: Int, b: Int) = a + b          // expression body
 *   bee fn fetch(id: Int): Future&lt;Str&gt; { ... }  // async
 * </pre>
 *
 * @param name       the function name as it appears in source
 * @param params     ordered list of formal parameters
 * @param returnType the declared return type, or {@code null} when omitted
 * @param body       the function body block
 * @param isAsync    {@code true} when the function was declared with {@code bee}
 */
public record FnNode(
    String name,
    List<ParamNode> params,
    String returnType,
    BlockNode body,
    boolean isAsync
) implements Node {}
