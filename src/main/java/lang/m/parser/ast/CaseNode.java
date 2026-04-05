package lang.m.parser.ast;

/**
 * One arm of a {@link SwitchNode}.
 *
 * <p>M syntax: {@code case pattern -> body}
 *
 * @param pattern the value or type pattern to match against the switch subject
 * @param body    the expression or block executed when the pattern matches
 */
public record CaseNode(Node pattern, Node body) {}
