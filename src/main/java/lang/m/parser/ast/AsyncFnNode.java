package lang.m.parser.ast;

/**
 * An async function declaration ({@code async fn}).
 *
 * <p>M syntax:
 * <pre>
 *   async fn fetchUser(id: Int): Str {
 *       let resp = await httpGet("https://api.example.com/users/${id}")
 *       return resp
 *   }
 * </pre>
 *
 * The code generator wraps the body in
 * {@code CompletableFuture.supplyAsync(...)}, so the declared return type
 * is always resolved asynchronously.
 *
 * @param fn the underlying function declaration with {@code isAsync == true}
 */
public record AsyncFnNode(FnNode fn) implements Node {}
