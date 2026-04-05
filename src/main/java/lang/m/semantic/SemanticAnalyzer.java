package lang.m.semantic;

import lang.m.parser.ast.*;

/**
 * Walks the AST produced by the {@link lang.m.parser.Parser} and performs:
 * <ol>
 *   <li><b>Scope building</b> — defines every binding in the correct {@link Scope}
 *       and assigns JVM local-variable slot numbers.</li>
 *   <li><b>Type inference</b> — propagates types from literals and declarations
 *       through expressions; fills in missing annotations on {@code let}/{@code var}.</li>
 *   <li><b>Mutability enforcement</b> — throws when a {@code let} binding is
 *       re-assigned via {@code AssignNode}.</li>
 *   <li><b>Async-context enforcement</b> — throws when {@code await} appears outside
 *       an {@code async fn} (including {@code async fn main()}).</li>
 *   <li><b>Duplicate-binding detection</b> — throws when {@code let} or {@code var}
 *       redeclares a name in the exact same scope.</li>
 * </ol>
 *
 * <p>Unknown identifiers (e.g. Java class names used in {@code throw} or
 * static interop calls) silently resolve to type {@code "Any"} instead of
 * throwing, enabling natural Java interop in this toy compiler.
 *
 * <p>Throws {@link SemanticException} on rule violations.
 */
public class SemanticAnalyzer {

    private Scope currentScope = new Scope(null); // global scope
    private boolean inAsyncFn   = false;
    private int     nextSlot    = 0;

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Analyze and type-annotate the AST.
     *
     * @param program the root AST node from the parser
     * @throws SemanticException on any semantic rule violation
     */
    public void analyze(ProgramNode program) {
        // Pass 1: register all top-level function names so mutual recursion works,
        // and so functions declared after main() are still resolvable.
        for (Node decl : program.declarations()) {
            switch (decl) {
                case FnNode fn -> currentScope.define(
                    new Symbol(fn.name(), fn.returnType() != null ? fn.returnType() : "Any", false, -1));
                case AsyncFnNode bee -> {
                    FnNode fn  = bee.fn();
                    String inner = fn.returnType() != null ? fn.returnType() : "Any";
                    currentScope.define(new Symbol(fn.name(), "Future<" + inner + ">", false, -1));
                }
                default -> { /* let/var/module/import handled in pass 2 */ }
            }
        }
        // Pass 2: full analysis
        for (Node decl : program.declarations()) {
            analyzeNode(decl);
        }
    }

    /**
     * Returns the global scope after analysis — useful for tests that inspect
     * top-level symbols.
     */
    public Scope globalScope() {
        return currentScope; // by the time analyze() returns we're back at global
    }

    // ── Core visitor ────────────────────────────────────────────────────────

    /**
     * Recursively analyzes {@code node} and returns its inferred M type string.
     *
     * @return inferred type (e.g. {@code "Int"}, {@code "Bool"}),
     *         {@code "void"} for statements, or {@code "Any"} when unknown
     */
    private String analyzeNode(Node node) {
        return switch (node) {

            case ProgramNode p -> {
                for (Node d : p.declarations()) analyzeNode(d);
                yield "void";
            }

            case FnNode  fn        -> { analyzeFn(fn);           yield "void"; }
            case AsyncFnNode bee   -> { analyzeFn(bee.fn());     yield "void"; }

            // Skip module/import pseudo-lets injected by the parser
            case LetNode let when let.name().startsWith("__") -> "void";

            case LetNode let -> {
                String initType = analyzeNode(let.initializer());
                String type     = let.type() != null ? let.type() : initType;
                checkDuplicate(let.name());
                currentScope.define(new Symbol(let.name(), type, false, nextSlot++));
                yield type;
            }

            case VarNode var -> {
                String initType = analyzeNode(var.initializer());
                String type     = var.type() != null ? var.type() : initType;
                checkDuplicate(var.name());
                currentScope.define(new Symbol(var.name(), type, true, nextSlot++));
                yield type;
            }

            case AssignNode a -> {
                Symbol sym = currentScope.resolve(a.name());
                if (sym != null && !sym.mutable()) {
                    throw new SemanticException(
                        "Cannot reassign immutable binding '" + a.name() + "'");
                }
                yield analyzeNode(a.value());
            }

            case BlockNode b -> {
                pushScope();
                for (Node s : b.statements()) analyzeNode(s);
                popScope();
                yield "void";
            }

            case IfNode i -> {
                analyzeNode(i.condition());
                analyzeBlock(i.then());
                if (i.otherwise() != null) analyzeNode(i.otherwise());
                yield "void";
            }

            case ForNode f -> {
                String iterType = analyzeNode(f.iterable());
                pushScope();
                currentScope.define(
                    new Symbol(f.variable(), elementType(iterType), false, nextSlot++));
                for (Node s : f.body().statements()) analyzeNode(s);
                popScope();
                yield "void";
            }

            case SwitchNode sw -> {
                analyzeNode(sw.subject());
                for (CaseNode c : sw.cases()) {
                    analyzeNode(c.pattern());
                    analyzeNode(c.body());
                }
                if (sw.defaultBranch() != null) analyzeNode(sw.defaultBranch());
                yield "void";
            }

            case TryNode t -> {
                analyzeBlock(t.body());
                for (CatchNode h : t.handlers()) {
                    pushScope();
                    currentScope.define(
                        new Symbol(h.varName(), h.exceptionType(), false, nextSlot++));
                    for (Node s : h.body().statements()) analyzeNode(s);
                    popScope();
                }
                if (t.finalizer() != null) analyzeBlock(t.finalizer());
                yield "void";
            }

            case ReturnNode r -> {
                if (r.value() != null) analyzeNode(r.value());
                yield "void";
            }

            case ThrowNode  b -> { analyzeNode(b.exception()); yield "void"; }
            case PrintNode   b -> { analyzeNode(b.value());     yield "Void"; }
            case PanicNode  b -> { analyzeNode(b.message());   yield "Void"; }
            case ExitNode   c -> { analyzeNode(c.code());      yield "Void"; }

            case AwaitNode h -> {
                if (!inAsyncFn) {
                    throw new SemanticException(
                        "'await' can only be used inside an 'async fn'");
                }
                String futureType = analyzeNode(h.future());
                yield unwrapFuture(futureType);
            }

            case SpawnVthreadNode m -> { analyzeNode(m.target()); yield "Void"; }

            case LambdaNode l -> {
                pushScope();
                for (ParamNode p : l.params()) {
                    currentScope.define(
                        new Symbol(p.name(), p.type() != null ? p.type() : "Any", false, nextSlot++));
                }
                analyzeNode(l.body());
                popScope();
                yield "Lambda";
            }

            case CallNode c -> {
                analyzeNode(c.callee());
                for (Node arg : c.args()) analyzeNode(arg);
                yield "Any"; // full return-type tracking requires a complete type system
            }

            case BinaryNode b -> {
                String lt = analyzeNode(b.left());
                String rt = analyzeNode(b.right());
                yield binaryResultType(b.op(), lt, rt);
            }

            case UnaryNode u -> {
                String t = analyzeNode(u.operand());
                yield "!".equals(u.op()) ? "Bool" : t;
            }

            case IdentNode id -> {
                if ("_".equals(id.name())) yield "Any";
                Symbol sym = currentScope.resolve(id.name());
                // Unknown identifiers resolve to "Any" (allows Java class refs like RuntimeException)
                yield sym != null && sym.type() != null ? sym.type() : "Any";
            }

            case LiteralNode l -> switch (l.kind()) {
                case "int"    -> "Int";
                case "long"   -> "Long";
                case "float"  -> "Float";
                case "double" -> "Double";
                case "bool"   -> "Bool";
                case "string" -> "Str";
                default       -> "Any";
            };
        };
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Analyzes a function declaration.
     * If nested (not at global scope), also registers the function in the
     * current scope so it can be called from its siblings.
     */
    private void analyzeFn(FnNode fn) {
        if (!currentScope.isGlobal()) {
            // Nested fn: register in the enclosing scope
            String type = fn.isAsync()
                ? "Future<" + (fn.returnType() != null ? fn.returnType() : "Any") + ">"
                : (fn.returnType() != null ? fn.returnType() : "Any");
            currentScope.define(new Symbol(fn.name(), type, false, -1));
        }
        // else: already pre-registered in pass 1

        boolean savedAsync = inAsyncFn;
        int     savedSlot  = nextSlot;
        inAsyncFn = fn.isAsync();
        nextSlot  = 0;

        pushScope();
        for (ParamNode p : fn.params()) {
            currentScope.define(
                new Symbol(p.name(), p.type() != null ? p.type() : "Any", false, nextSlot++));
        }
        // Analyze body statements directly — do NOT call analyzeBlock() which
        // would create a redundant inner scope on top of the fn scope.
        for (Node stmt : fn.body().statements()) {
            analyzeNode(stmt);
        }
        popScope();

        inAsyncFn = savedAsync;
        nextSlot  = savedSlot;
    }

    /** Push a new child scope and make it current. */
    private void pushScope() {
        currentScope = new Scope(currentScope);
    }

    /** Pop the current scope back to its parent. */
    private void popScope() {
        currentScope = currentScope.parent();
    }

    /**
     * Analyze {@code block} in a fresh child scope.
     * Used for if/else/finally/switch bodies.
     */
    private void analyzeBlock(BlockNode block) {
        pushScope();
        for (Node s : block.statements()) analyzeNode(s);
        popScope();
    }

    /**
     * Throw if {@code name} is already defined in the innermost scope
     * (duplicate declaration in the same block).
     */
    private void checkDuplicate(String name) {
        if (currentScope.hasLocal(name)) {
            throw new SemanticException(
                "Duplicate declaration of '" + name + "' in the same scope");
        }
    }

    /** Returns the element type of a collection/range type string. */
    private static String elementType(String collectionType) {
        if (collectionType == null) return "Any";
        if ("Range".equals(collectionType) || "Int".equals(collectionType)) return "Int";
        if (collectionType.startsWith("List<") && collectionType.endsWith(">"))
            return collectionType.substring(5, collectionType.length() - 1);
        return "Any";
    }

    /** Unwraps {@code Future<T>} → {@code T}; otherwise returns {@code "Any"}. */
    private static String unwrapFuture(String type) {
        if (type != null && type.startsWith("Future<") && type.endsWith(">"))
            return type.substring(7, type.length() - 1);
        return "Any";
    }

    /** Infers the result type of a binary operation. */
    private static String binaryResultType(String op, String left, String right) {
        return switch (op) {
            case "==", "!=", "===", "<", "<=", ">", ">=", "is", "&&", "||" -> "Bool";
            case "..", "..<" -> "Range";
            case ".", "?."   -> "Any";
            default          -> left != null ? left : (right != null ? right : "Any");
        };
    }
}
