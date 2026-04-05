package lang.m.parser.ast;

import java.util.List;

/**
 * A {@code switch} / {@code case} expression.
 *
 * <p>M syntax:
 * <pre>
 *   switch value {
 *       case 1   -> "one"
 *       case 2   -> "two"
 *       default  -> "other"
 *   }
 * </pre>
 * Case patterns may be integer literals, string literals, or type
 * patterns (e.g. {@code case Circle(r)}).
 *
 * <p>The code generator maps integer-only switches to
 * {@code tableswitch}/{@code lookupswitch} and type-pattern
 * switches to {@code instanceof} check chains.
 *
 * @param subject       the expression being matched
 * @param cases         ordered list of {@link CaseNode} arms
 * @param defaultBranch the {@code default} arm expression, or {@code null} if absent
 */
public record SwitchNode(Node subject, List<CaseNode> cases, Node defaultBranch) implements Node {}
