package lang.m.parser.ast;

/**
 * A {@code for} / {@code in} iterator loop.
 *
 * <p>M syntax:
 * <pre>
 *   for item in list   { ... }   // iterate a collection
 *   for i    in 1..10  { ... }   // inclusive integer range
 *   for i    in 0..<n  { ... }   // exclusive integer range
 * </pre>
 *
 * The code generator emits an iterator pattern for collections and an
 * {@code IntStream} / counter loop for integer range literals.
 *
 * @param variable the loop-variable identifier (immutable inside the body)
 * @param iterable the expression producing the iterable or range
 * @param body     the loop body executed once per element
 */
public record ForNode(String variable, Node iterable, BlockNode body) implements Node {}
