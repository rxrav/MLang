package lang.m.parser.ast;

import java.util.List;

/**
 * An anonymous function (closure / lambda) expression.
 *
 * <p>M syntax:
 * <pre>
 *   { (x: Int, y: Int): Int -> x + y }   // typed params + return type
 *   { (x) -> x * 2 }                     // untyped params, no return annotation
 *   { () -> 42 }                          // zero params
 * </pre>
 *
 * The code generator emits {@code invokedynamic} via
 * {@link java.lang.invoke.LambdaMetafactory} backed by
 * {@link lang.m.runtime.MRuntime.MLambda}.
 *
 * @param params     the formal parameters (may be empty for zero-arg lambdas)
 * @param returnType the declared return M type (e.g. {@code "Int"}), or {@code null} if omitted
 * @param body       the lambda body — an expression or a {@link BlockNode}
 */
public record LambdaNode(List<ParamNode> params, String returnType, Node body) implements Node {}
