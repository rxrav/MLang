package lang.m.parser.ast;

import java.util.List;

/**
 * The root of every M AST.
 *
 * <p>Holds the ordered list of top-level declarations found in one
 * {@code .m} source file: module declaration, imports, function
 * definitions, and top-level let/var bindings.
 *
 * @param declarations top-level nodes in source order
 */
public record ProgramNode(List<Node> declarations) implements Node {}
