package lang.m.parser;

import lang.m.lexer.Token;
import lang.m.lexer.TokenType;
import lang.m.parser.ast.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts a flat token stream from the {@link lang.m.lexer.Lexer}
 * into a structured AST rooted at {@link lang.m.parser.ast.ProgramNode}.
 *
 * <p>Implemented as a hand-written <b>recursive descent parser</b> with a
 * <b>Pratt (top-down operator precedence)</b> expression sub-parser.
 * Each grammar rule maps to one private {@code parse*()} method.
 *
 * <h2>Grammar outline (informal)</h2>
 * <pre>
 *   program      &rar; (moduleDecl | importDecl | fnDecl | letDecl | varDecl)*
 *   fnDecl       &rar; ('bee')? 'fn' IDENT '(' params ')' (':' type)? (block | '=' expr)
 *   letDecl      &rar; 'let' IDENT (':' type)? '=' expr
 *   varDecl      &rar; 'var' IDENT (':' type)? '=' expr
 *   statement    &rar; letDecl | varDecl | ifStmt | forStmt | switchStmt | tryStmt
 *                | returnStmt | throwStmt | spawnVthreadStmt | assignOrExprStmt
 * </pre>
 *
 * <p>Throws {@link ParseException} on grammar violations with line/column info.
 */
public class Parser {

    // Pratt precedence levels
    private static final int PREC_NONE   = 0;
    private static final int PREC_PIPE   = 2;
    private static final int PREC_OR     = 3;
    private static final int PREC_AND    = 4;
    private static final int PREC_EQ     = 5;
    private static final int PREC_CMP    = 6;
    private static final int PREC_RANGE  = 7;
    private static final int PREC_ADD    = 8;
    private static final int PREC_MUL    = 9;
    private static final int PREC_POW    = 10;
    private static final int PREC_UNARY  = 11;
    private static final int PREC_CALL   = 12;

    private final List<Token> tokens;
    private int pos = 0;

    /** @param tokens token list from the Lexer */
    public Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    // Public API

    /**
     * Parse the entire token stream and return the root AST node.
     *
     * @return a {@link ProgramNode} containing all top-level declarations
     * @throws ParseException if the source violates the M grammar
     */
    public ProgramNode parseProgram() {
        List<Node> decls = new ArrayList<>();
        while (!isAtEnd()) {
            decls.add(parseTopLevel());
        }
        return new ProgramNode(decls);
    }

    // Token navigation helpers

    private Token peek() { return tokens.get(pos); }

    private Token peekAt(int offset) {
        int i = pos + offset;
        return i < tokens.size() ? tokens.get(i) : tokens.get(tokens.size() - 1);
    }

    private Token advance() {
        Token t = tokens.get(pos);
        if (!isAtEnd()) pos++;
        return t;
    }

    private boolean match(TokenType type) {
        if (check(type)) { advance(); return true; }
        return false;
    }

    private Token expect(TokenType type) {
        if (!check(type)) {
            Token cur = peek();
            throw new ParseException(
                "Expected " + type + " but found " + cur.type() + " ('" + cur.value() + "')",
                cur.line(), cur.col());
        }
        return advance();
    }

    private boolean check(TokenType type) { return peek().type() == type; }
    private boolean isAtEnd() { return peek().type() == TokenType.EOF; }

    // Top-level declarations

    private Node parseTopLevel() {
        if (check(TokenType.MODULE)) return parseModule();
        if (check(TokenType.IMPORT)) return parseImport();
        if (check(TokenType.ASYNC))  return parseFn(true);
        if (check(TokenType.FN))     return parseFn(false);
        if (check(TokenType.LET))    return parseLet();
        if (check(TokenType.VAR))    return parseVar();
        Token t = peek();
        throw new ParseException("Unexpected token '" + t.value() + "' at top level", t.line(), t.col());
    }

    /** module com.example.app */
    private Node parseModule() {
        expect(TokenType.MODULE);
        StringBuilder name = new StringBuilder(expect(TokenType.IDENT).value());
        while (check(TokenType.DOT)) { advance(); name.append('.').append(expect(TokenType.IDENT).value()); }
        return new LetNode("__module__", "Str", new LiteralNode("string", name.toString()));
    }

    /** import java.io.File  or  import com.example.* */
    private Node parseImport() {
        expect(TokenType.IMPORT);
        StringBuilder path = new StringBuilder(expect(TokenType.IDENT).value());
        while (check(TokenType.DOT)) {
            advance();
            if (check(TokenType.STAR)) { advance(); path.append(".*"); break; }
            path.append('.').append(expect(TokenType.IDENT).value());
        }
        return new LetNode("__import__", "Str", new LiteralNode("string", path.toString()));
    }

    /**
     * Parse a function declaration.
     * ('async')? 'fn' IDENT '(' params ')' (':' type)? (block | '=' expr)
     *
     * @param isAsync true when {@code async} precedes {@code fn}
     */
    private Node parseFn(boolean isAsync) {
        if (isAsync) expect(TokenType.ASYNC);
        expect(TokenType.FN);
        String name = expect(TokenType.IDENT).value();
        expect(TokenType.LPAREN);
        List<ParamNode> params = parseParams();
        expect(TokenType.RPAREN);
        String returnType = null;
        if (match(TokenType.COLON)) returnType = parseType();
        BlockNode body;
        if (check(TokenType.ASSIGN)) {
            advance();
            body = new BlockNode(List.of(new ReturnNode(parseExpr(PREC_NONE))));
        } else {
            body = parseBlock();
        }
        FnNode fn = new FnNode(name, params, returnType, body, isAsync);
        return isAsync ? new AsyncFnNode(fn) : fn;
    }

    private List<ParamNode> parseParams() {
        List<ParamNode> params = new ArrayList<>();
        if (check(TokenType.RPAREN)) return params;
        do {
            String n = expect(TokenType.IDENT).value();
            String t = null;
            if (match(TokenType.COLON)) t = parseType();
            params.add(new ParamNode(n, t));
        } while (match(TokenType.COMMA));
        return params;
    }

    /** Parse a type name, including generic forms like List<Int>. */
    private String parseType() {
        StringBuilder type = new StringBuilder(expect(TokenType.IDENT).value());
        if (check(TokenType.LT)) {
            advance();
            type.append('<').append(parseType());
            if (match(TokenType.COMMA)) type.append(',').append(parseType());
            expect(TokenType.GT);
            type.append('>');
        }
        return type.toString();
    }

    // Statements

    private BlockNode parseBlock() {
        expect(TokenType.LBRACE);
        List<Node> stmts = new ArrayList<>();
        while (!check(TokenType.RBRACE) && !isAtEnd()) stmts.add(parseStatement());
        expect(TokenType.RBRACE);
        return new BlockNode(stmts);
    }

    private Node parseStatement() {
        return switch (peek().type()) {
            case LET    -> parseLet();
            case VAR    -> parseVar();
            case IF     -> parseIf();
            case FOR    -> parseFor();
            case SWITCH -> parseSwitch();
            case TRY    -> parseTry();
            case RETURN -> parseReturn();
            case THROW  -> parseThrow();
            case SPAWN_VTHREAD -> parseSpawnVthread();
            case ASYNC  -> parseFn(true);
            case FN     -> parseFn(false);
            default     -> parseAssignOrExpr();
        };
    }

    /** let name: Type = expr */
    private Node parseLet() {
        expect(TokenType.LET);
        String name = expect(TokenType.IDENT).value();
        String type = null;
        if (match(TokenType.COLON)) type = parseType();
        expect(TokenType.ASSIGN);
        return new LetNode(name, type, parseExpr(PREC_NONE));
    }

    /** var name: Type = expr */
    private Node parseVar() {
        expect(TokenType.VAR);
        String name = expect(TokenType.IDENT).value();
        String type = null;
        if (match(TokenType.COLON)) type = parseType();
        expect(TokenType.ASSIGN);
        return new VarNode(name, type, parseExpr(PREC_NONE));
    }

    /** if cond { } else if cond { } else { } */
    private Node parseIf() {
        expect(TokenType.IF);
        Node cond = parseExpr(PREC_NONE);
        BlockNode then = parseBlock();
        Node otherwise = null;
        if (match(TokenType.ELSE)) otherwise = check(TokenType.IF) ? parseIf() : parseBlock();
        return new IfNode(cond, then, otherwise);
    }

    /** for item in iterable { } */
    private Node parseFor() {
        expect(TokenType.FOR);
        String var = expect(TokenType.IDENT).value();
        expect(TokenType.IN);
        Node iterable = parseExpr(PREC_NONE);
        return new ForNode(var, iterable, parseBlock());
    }

    /** switch subject { case v -> expr ... default -> expr } */
    private Node parseSwitch() {
        expect(TokenType.SWITCH);
        Node subject = parseExpr(PREC_NONE);
        expect(TokenType.LBRACE);
        List<CaseNode> cases = new ArrayList<>();
        Node defaultBranch = null;
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            if (match(TokenType.CASE)) {
                Node pattern = parseExpr(PREC_NONE);
                expect(TokenType.ARROW);
                Node body = check(TokenType.LBRACE) ? parseBlock() : parseExpr(PREC_NONE);
                cases.add(new CaseNode(pattern, body));
            } else if (match(TokenType.DEFAULT)) {
                expect(TokenType.ARROW);
                defaultBranch = check(TokenType.LBRACE) ? parseBlock() : parseExpr(PREC_NONE);
            } else {
                Token t = peek();
                throw new ParseException("Expected 'case' or 'default' inside switch", t.line(), t.col());
            }
        }
        expect(TokenType.RBRACE);
        return new SwitchNode(subject, cases, defaultBranch);
    }

    /** try { } catch (e: Ex) { } finally { } */
    private Node parseTry() {
        expect(TokenType.TRY);
        BlockNode body = parseBlock();
        List<CatchNode> handlers = new ArrayList<>();
        while (check(TokenType.CATCH)) {
            advance();
            expect(TokenType.LPAREN);
            String varName = expect(TokenType.IDENT).value();
            expect(TokenType.COLON);
            String exType = parseType();
            expect(TokenType.RPAREN);
            handlers.add(new CatchNode(varName, exType, parseBlock()));
        }
        BlockNode finalizer = null;
        if (match(TokenType.FINALLY)) finalizer = parseBlock();
        return new TryNode(body, handlers, finalizer);
    }

    /** return expr  or bare return */
    private Node parseReturn() {
        expect(TokenType.RETURN);
        Node value = null;
        if (!check(TokenType.RBRACE) && !check(TokenType.SEMICOLON) && !isAtEnd())
            value = parseExpr(PREC_NONE);
        return new ReturnNode(value);
    }

    /** throw expr */
    private Node parseThrow() {
        expect(TokenType.THROW);
        return new ThrowNode(parseExpr(PREC_NONE));
    }

    /** spawn_vthread fnCall(args)  or  spawn_vthread { block } */
    private Node parseSpawnVthread() {
        expect(TokenType.SPAWN_VTHREAD);
        Node target = check(TokenType.LBRACE) ? parseLambdaBlock() : parseExpr(PREC_NONE);
        return new SpawnVthreadNode(target);
    }

    /** x = expr  or plain expression statement */
    private Node parseAssignOrExpr() {
        if (check(TokenType.IDENT) && peekAt(1).type() == TokenType.ASSIGN) {
            String name = advance().value();
            advance(); // consume '='
            return new AssignNode(name, parseExpr(PREC_NONE));
        }
        return parseExpr(PREC_NONE);
    }

    // Pratt expression parser

    /**
     * Parse an expression at the given minimum precedence level.
     *
     * @param minPrec minimum precedence (use PREC_NONE for a full expression)
     */
    private Node parseExpr(int minPrec) {
        Node left = parseUnary();
        while (true) {
            int prec = infixPrec(peek().type());
            if (prec <= minPrec) break;
            Token op = advance();
            Node right;
            if (op.type() == TokenType.STARSTAR) {
                right = parseExpr(prec - 1); // right-associative
            } else if (op.type() == TokenType.PIPE) {
                right = parseExpr(prec);
                left = new CallNode(right, List.of(left)); // left |> right  -> right(left)
                continue;
            } else {
                right = parseExpr(prec);
            }
            left = new BinaryNode(left, op.value(), right);
        }
        return left;
    }

    private int infixPrec(TokenType t) {
        return switch (t) {
            case PIPE                          -> PREC_PIPE;
            case OR                            -> PREC_OR;
            case AND                           -> PREC_AND;
            case EQ, NEQ, REF_EQ               -> PREC_EQ;
            case LT, LTE, GT, GTE, IS          -> PREC_CMP;
            case RANGE_INCL, RANGE_EXCL        -> PREC_RANGE;
            case PLUS, MINUS                   -> PREC_ADD;
            case STAR, SLASH, PERCENT          -> PREC_MUL;
            case STARSTAR                      -> PREC_POW;
            case LPAREN, DOT, QUESTION_DOT     -> PREC_CALL;
            default                            -> PREC_NONE;
        };
    }

    private Node parseUnary() {
        if (check(TokenType.MINUS) || check(TokenType.NOT)) {
            Token op = advance();
            return new UnaryNode(op.value(), parseUnary());
        }
        return parsePostfix(parsePrimary());
    }

    private Node parsePostfix(Node left) {
        while (true) {
            if (check(TokenType.LPAREN)) {
                advance();
                List<Node> args = parseArgs();
                expect(TokenType.RPAREN);
                left = new CallNode(left, args);
            } else if (check(TokenType.DOT) || check(TokenType.QUESTION_DOT)) {
                Token dot = advance();
                String member = expect(TokenType.IDENT).value();
                left = new BinaryNode(left, dot.value(), new IdentNode(member));
            } else {
                break;
            }
        }
        return left;
    }

    private List<Node> parseArgs() {
        List<Node> args = new ArrayList<>();
        if (check(TokenType.RPAREN)) return args;
        do { args.add(parseExpr(PREC_NONE)); } while (match(TokenType.COMMA));
        return args;
    }

    // Primary expressions

    /**
     * Parse a primary (atomic) expression.
     */
    private Node parsePrimary() {
        Token t = peek();
        return switch (t.type()) {
            case INT_LIT    -> { advance(); yield new LiteralNode("int",    Integer.parseInt(t.value())); }
            case LONG_LIT   -> { advance(); yield new LiteralNode("long",   Long.parseLong(t.value())); }
            case FLOAT_LIT  -> { advance(); yield new LiteralNode("float",  Float.parseFloat(t.value())); }
            case DOUBLE_LIT -> { advance(); yield new LiteralNode("double", Double.parseDouble(t.value())); }
            case TRUE       -> { advance(); yield new LiteralNode("bool",   true); }
            case FALSE      -> { advance(); yield new LiteralNode("bool",   false); }
            case NULL       -> { advance(); yield new LiteralNode("null",   null); }
            case STRING_LIT -> parseStringLitOrInterp();
            case LPAREN     -> { advance(); Node inner = parseExpr(PREC_NONE); expect(TokenType.RPAREN); yield inner; }
            case LBRACE     -> parseLambdaBlock();
            case IDENT      -> { advance(); yield new IdentNode(t.value()); }
            case WILDCARD   -> { advance(); yield new IdentNode("_"); }
            case PRINT      -> parsePrint();
            case PANIC      -> parsePanic();
            case EXIT       -> parseExit();
            case AWAIT      -> parseAwait();
            default -> throw new ParseException("Unexpected token '" + t.value() + "' in expression", t.line(), t.col());
        };
    }

    /**
     * Parse a string literal, stitching interpolation segments into BinaryNode("+") chains.
     */
    private Node parseStringLitOrInterp() {
        Token strTok = advance();
        Node result = new LiteralNode("string", strTok.value());
        while (check(TokenType.INTERP_START)) {
            advance();
            Node expr = parseExpr(PREC_NONE);
            expect(TokenType.INTERP_END);
            result = new BinaryNode(result, "+", expr);
            if (check(TokenType.STRING_LIT)) {
                Token suffix = advance();
                if (!suffix.value().isEmpty())
                    result = new BinaryNode(result, "+", new LiteralNode("string", suffix.value()));
            }
        }
        return result;
    }

    /**
     * Parse a lambda or anonymous block.
     * Lambdas: { (x, y) -> expr }, { (x: Int, y: Double): Int -> expr }
     * Blocks:  { stmt... }
     */
    private Node parseLambdaBlock() {
        expect(TokenType.LBRACE);
        if (isLambdaHead()) {
            expect(TokenType.LPAREN);
            List<ParamNode> params = check(TokenType.RPAREN) ? List.of() : parseLambdaParams();
            expect(TokenType.RPAREN);
            // Optional return type annotation: ): ReturnType ->
            String returnType = null;
            if (match(TokenType.COLON)) returnType = parseType();
            expect(TokenType.ARROW);
            Node body = check(TokenType.LBRACE) ? parseBlock() : parseExpr(PREC_NONE);
            expect(TokenType.RBRACE);
            return new LambdaNode(params, returnType, body);
        }
        List<Node> stmts = new ArrayList<>();
        while (!check(TokenType.RBRACE) && !isAtEnd()) stmts.add(parseStatement());
        expect(TokenType.RBRACE);
        return new BlockNode(stmts);
    }

    /**
     * Lambda head detected by: opening {@code (} followed eventually by
     * {@code )} and then optionally {@code : Type} and then {@code ->}.
     * e.g. {@code { () -> ... }} or {@code { (x, y: Int): Int -> ... }}
     */
    private boolean isLambdaHead() {
        if (!check(TokenType.LPAREN)) return false;
        // Scan forward past matching ) to see if -> (or : Type ->) follows
        int i = 1;
        int depth = 1;
        while (pos + i < tokens.size() && depth > 0) {
            TokenType tt = tokens.get(pos + i).type();
            if (tt == TokenType.LPAREN) depth++;
            else if (tt == TokenType.RPAREN) depth--;
            i++;
        }
        if (pos + i >= tokens.size()) return false;
        // Allow optional return-type annotation: ): ReturnType ->
        if (tokens.get(pos + i).type() == TokenType.COLON) {
            i++; // skip ':'
            // Skip the type tokens until we hit ARROW or something unexpected
            while (pos + i < tokens.size()) {
                TokenType tt = tokens.get(pos + i).type();
                if (tt == TokenType.ARROW) break;
                if (tt == TokenType.LBRACE || tt == TokenType.RBRACE
                        || tt == TokenType.SEMICOLON || tt == TokenType.EOF) return false;
                i++;
            }
        }
        return pos + i < tokens.size()
            && tokens.get(pos + i).type() == TokenType.ARROW;
    }

    private List<ParamNode> parseLambdaParams() {
        List<ParamNode> params = new ArrayList<>();
        do {
            String name = expect(TokenType.IDENT).value();
            String type = null;
            if (match(TokenType.COLON)) type = parseType();
            params.add(new ParamNode(name, type));
        } while (match(TokenType.COMMA));
        return params;
    }

    // Built-in keywords

    /** print(expr) */
    private Node parsePrint() {
        expect(TokenType.PRINT); expect(TokenType.LPAREN);
        Node value = parseExpr(PREC_NONE);
        expect(TokenType.RPAREN);
        return new PrintNode(value);
    }

    /** panic(expr) */
    private Node parsePanic() {
        expect(TokenType.PANIC); expect(TokenType.LPAREN);
        Node msg = parseExpr(PREC_NONE);
        expect(TokenType.RPAREN);
        return new PanicNode(msg);
    }

    /** exit(expr) */
    private Node parseExit() {
        expect(TokenType.EXIT); expect(TokenType.LPAREN);
        Node code = parseExpr(PREC_NONE);
        expect(TokenType.RPAREN);
        return new ExitNode(code);
    }

    /** await expr */
    private Node parseAwait() {
        expect(TokenType.AWAIT);
        return new AwaitNode(parseExpr(PREC_CALL));
    }
}
