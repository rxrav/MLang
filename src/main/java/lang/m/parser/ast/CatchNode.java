package lang.m.parser.ast;

/**
 * One catch arm of a {@link TryNode} ({@code catch} block).
 *
 * <p>M syntax: {@code catch (varName: ExceptionType) { body }}
 *
 * @param varName       the name to which the caught exception is bound
 * @param exceptionType the fully-qualified or simple class name to catch,
 *                      e.g. {@code "IOException"}, {@code "Exception"}
 * @param body          the handler block executed when the exception type matches
 */
public record CatchNode(String varName, String exceptionType, BlockNode body) {}
