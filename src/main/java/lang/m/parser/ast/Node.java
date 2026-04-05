package lang.m.parser.ast;

/**
 * Base sealed type for every node in the M Abstract Syntax Tree.
 *
 * <p>The sealed hierarchy lets the {@link lang.m.semantic.SemanticAnalyzer}
 * and the {@link lang.m.codegen.Compiler} use exhaustive
 * {@code switch} pattern-matching (Java 21) without a default arm:
 * <pre>
 *   switch (node) {
 *       case LetNode l   -> ...
 *       case IfNode  i   -> ...
 *       // etc.
 *   }
 * </pre>
 *
 * <p>All concrete node types are Java {@code record}s (immutable value objects).
 */
public sealed interface Node
    permits
        ProgramNode,
        FnNode,
        LetNode,
        VarNode,
        ReturnNode,
        IfNode,
        ForNode,
        SwitchNode,
        TryNode,
        CallNode,
        LambdaNode,
        SpawnVthreadNode,
        AsyncFnNode,
        AwaitNode,
        ThrowNode,
        PrintNode,
        PanicNode,
        ExitNode,
        LiteralNode,
        IdentNode,
        BinaryNode,
        UnaryNode,
        BlockNode,
        AssignNode
{}
