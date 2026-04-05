package lang.m.parser.ast;

/**
 * Await a {@code Future<T>} — block until the value is available ({@code await}).
 *
 * <p>M syntax: {@code await expr}
 *
 * <p>The operand must produce a {@code CompletableFuture}; the result type is
 * the unwrapped value. The code generator emits {@code CompletableFuture.join()}
 * (blocking join on the caller's thread).
 *
 * @param future the expression producing the {@code CompletableFuture} to await
 */
public record AwaitNode(Node future) implements Node {}
