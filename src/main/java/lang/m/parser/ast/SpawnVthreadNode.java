package lang.m.parser.ast;

/**
 * Spawn a concurrent task as a Java 21 virtual thread ({@code spawn_vthread}).
 *
 * <p>MLang syntax:
 * <pre>
 *   spawn_vthread crunch(42)   // spawn a named-function call
 *   spawn_vthread { doWork() } // spawn an anonymous lambda
 * </pre>
 *
 * The code generator emits:
 * <pre>
 *   Thread.ofVirtual().start(() -&gt; target)
 * </pre>
 *
 * The spawning function waits for all spawned threads before returning.
 * Use {@code bee}/{@code await} for structured async.
 *
 * @param target the expression to execute in the new thread—
 *               either a {@link CallNode} or a {@link LambdaNode}
 */
public record SpawnVthreadNode(Node target) implements Node {}
