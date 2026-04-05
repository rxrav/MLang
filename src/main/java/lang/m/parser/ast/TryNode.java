package lang.m.parser.ast;

import java.util.List;

/**
 * A structured exception-handling block.
 *
 * <p>M syntax:
 * <pre>
 *   try {
 *       riskyOp()
 *   } oops (e: IOException) {
 *       log(e.message)
 *   } oops (e: Exception) {
 *       log(e.message)
 *   } finally {
 *       cleanup()
 *   }
 * </pre>
 *
 * The code generator emits one {@code visitTryCatchBlock} per
 * {@link CatchNode} handler, in declaration order.
 *
 * @param body      the protected block
 * @param handlers  ordered list of {@link CatchNode} catch arms (may be empty)
 * @param finalizer the {@code finally} block, or {@code null} if absent
 */
public record TryNode(
    BlockNode body,
    List<CatchNode> handlers,
    BlockNode finalizer
) implements Node {}
