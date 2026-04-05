package lang.m.parser.ast;

import java.util.List;

/**
 * A sequence of statements enclosed in braces ({@code { }}).
 *
 * <p>A block introduces a new lexical {@link lang.m.semantic.Scope}.
 * Bindings declared inside are not visible outside.
 *
 * @param statements the ordered list of statements in the block;
 *                   the last statement may be an expression that
 *                   acts as the block's value (used by {@code if}
 *                   and {@code switch} expression forms)
 */
public record BlockNode(List<Node> statements) implements Node {}
