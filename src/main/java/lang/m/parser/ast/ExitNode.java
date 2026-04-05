package lang.m.parser.ast;

/**
 * Exit the MLang program with an integer status code ({@code exit}).
 *
 * <p>MLang syntax:
 * <pre>
 *   exit(0)   // clean exit
 *   exit(1)   // exit with error
 * </pre>
 *
 * <p>The code generator emits
 * {@code invokestatic MRuntime.exit(I)V},
 * which delegates to {@link System#exit(int)}.
 *
 * @param code the {@code Int} expression for the exit status code
 */
public record ExitNode(Node code) implements Node {}
