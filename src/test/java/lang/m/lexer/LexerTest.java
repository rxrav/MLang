package lang.m.lexer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LexerTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<Token> lex(String src) {
        return new Lexer(src).tokenize();
    }

    private Token first(String src) {
        return lex(src).get(0);
    }

    private void assertType(String src, TokenType expected) {
        assertEquals(expected, first(src).type(), "source: " + src);
    }

    // ── Empty / EOF ───────────────────────────────────────────────────────────

    @Test void emptySourceReturnsEof() {
        var t = lex("");
        assertEquals(1, t.size());
        assertEquals(TokenType.EOF, t.get(0).type());
    }

    @Test void blankSourceReturnsEof() {
        var t = lex("   \n\t  ");
        assertEquals(1, t.size());
        assertEquals(TokenType.EOF, t.get(0).type());
    }

    // ── Built-in keywords ─────────────────────────────────────────────────────

    @Test void builtinKeywords() {
        assertType("print",         TokenType.PRINT);
        assertType("panic",         TokenType.PANIC);
        assertType("spawn_vthread", TokenType.SPAWN_VTHREAD);
        assertType("async",         TokenType.ASYNC);
        assertType("await",         TokenType.AWAIT);
        assertType("exit",          TokenType.EXIT);
        assertType("catch",         TokenType.CATCH);
        assertType("throw",         TokenType.THROW);
    }

    // ── Standard keywords ─────────────────────────────────────────────────────

    @Test void standardKeywords() {
        assertType("let",     TokenType.LET);
        assertType("var",     TokenType.VAR);
        assertType("fn",      TokenType.FN);
        assertType("return",  TokenType.RETURN);
        assertType("type",    TokenType.TYPE);
        assertType("if",      TokenType.IF);
        assertType("else",    TokenType.ELSE);
        assertType("for",     TokenType.FOR);
        assertType("in",      TokenType.IN);
        assertType("switch",  TokenType.SWITCH);
        assertType("case",    TokenType.CASE);
        assertType("default", TokenType.DEFAULT);
        assertType("try",     TokenType.TRY);
        assertType("finally", TokenType.FINALLY);
        assertType("module",  TokenType.MODULE);
        assertType("import",  TokenType.IMPORT);
        assertType("true",    TokenType.TRUE);
        assertType("false",   TokenType.FALSE);
        assertType("null",    TokenType.NULL);
        assertType("as",      TokenType.AS);
        assertType("is",      TokenType.IS);
    }

    // ── Identifiers ───────────────────────────────────────────────────────────

    @Test void simpleIdentifier() {
        Token t = first("myVar");
        assertEquals(TokenType.IDENT, t.type());
        assertEquals("myVar", t.value());
    }

    @Test void identifierWithDigits() {
        Token t = first("x1");
        assertEquals(TokenType.IDENT, t.type());
        assertEquals("x1", t.value());
    }

    @Test void identifierWithUnderscore() {
        Token t = first("my_var");
        assertEquals(TokenType.IDENT, t.type());
        assertEquals("my_var", t.value());
    }

    @Test void leadingUnderscoreIdentifier() {
        Token t = first("_private");
        assertEquals(TokenType.IDENT, t.type());
        assertEquals("_private", t.value());
    }

    @Test void standaloneUnderscoreIsWildcard() {
        assertType("_", TokenType.WILDCARD);
    }

    @Test void keywordPrefixIsStillIdent() {
        // "letter" starts with "let" but is not the keyword
        Token t = first("letter");
        assertEquals(TokenType.IDENT, t.type());
        assertEquals("letter", t.value());
    }

    // ── Integer literals ──────────────────────────────────────────────────────

    @Test void intLiteral() {
        Token t = first("42");
        assertEquals(TokenType.INT_LIT, t.type());
        assertEquals("42", t.value());
    }

    @Test void zeroIntLiteral() {
        assertEquals(TokenType.INT_LIT, first("0").type());
    }

    @Test void longLiteralUppercase() {
        Token t = first("42L");
        assertEquals(TokenType.LONG_LIT, t.type());
        assertEquals("42", t.value());
    }

    @Test void longLiteralLowercase() {
        assertEquals(TokenType.LONG_LIT, first("99l").type());
    }

    // ── Floating-point literals ───────────────────────────────────────────────

    @Test void doubleLiteralImplicit() {
        Token t = first("3.14");
        assertEquals(TokenType.DOUBLE_LIT, t.type());
        assertEquals("3.14", t.value());
    }

    @Test void doubleLiteralExplicitSuffix() {
        assertEquals(TokenType.DOUBLE_LIT, first("1.0d").type());
    }

    @Test void floatLiteralSuffix() {
        Token t = first("3.14f");
        assertEquals(TokenType.FLOAT_LIT, t.type());
        assertEquals("3.14", t.value());
    }

    @Test void intFollowedByRangeNotDouble() {
        // "1..5" should lex as INT_LIT RANGE_INCL INT_LIT, not DOUBLE_LIT
        var t = lex("1..5");
        assertEquals(TokenType.INT_LIT,    t.get(0).type());
        assertEquals(TokenType.RANGE_INCL, t.get(1).type());
        assertEquals(TokenType.INT_LIT,    t.get(2).type());
    }

    // ── String literals ───────────────────────────────────────────────────────

    @Test void plainString() {
        Token t = first("\"hello\"");
        assertEquals(TokenType.STRING_LIT, t.type());
        assertEquals("hello", t.value());
    }

    @Test void emptyString() {
        Token t = first("\"\"");
        assertEquals(TokenType.STRING_LIT, t.type());
        assertEquals("", t.value());
    }

    @Test void stringWithNewlineEscape() {
        Token t = first("\"line\\nnew\"");
        assertEquals(TokenType.STRING_LIT, t.type());
        assertEquals("line\nnew", t.value());
    }

    @Test void stringWithTabEscape() {
        Token t = first("\"col\\there\"");
        assertEquals("col\there", t.value());
    }

    @Test void stringWithQuoteEscape() {
        Token t = first("\"say \\\"hi\\\"\"");
        assertEquals("say \"hi\"", t.value());
    }

    @Test void stringWithDollarEscape() {
        // \$ prevents interpolation
        Token t = first("\"price: \\$5\"");
        assertEquals(TokenType.STRING_LIT, t.type());
        assertEquals("price: $5", t.value());
    }

    // ── String interpolation ──────────────────────────────────────────────────

    @Test void interpolationSimple() {
        // "Hello, ${name}!"
        var t = lex("\"Hello, ${name}!\"");
        assertEquals(TokenType.STRING_LIT,   t.get(0).type());
        assertEquals("Hello, ",              t.get(0).value());
        assertEquals(TokenType.INTERP_START, t.get(1).type());
        assertEquals(TokenType.IDENT,        t.get(2).type());
        assertEquals("name",                 t.get(2).value());
        assertEquals(TokenType.INTERP_END,   t.get(3).type());
        assertEquals(TokenType.STRING_LIT,   t.get(4).type());
        assertEquals("!",                    t.get(4).value());
        assertEquals(TokenType.EOF,          t.get(5).type());
    }

    @Test void interpolationAtStart() {
        // "${x} done"
        var t = lex("\"${x} done\"");
        assertEquals(TokenType.STRING_LIT,   t.get(0).type());
        assertEquals("",                     t.get(0).value());
        assertEquals(TokenType.INTERP_START, t.get(1).type());
        assertEquals(TokenType.IDENT,        t.get(2).type());
        assertEquals(TokenType.INTERP_END,   t.get(3).type());
        assertEquals(TokenType.STRING_LIT,   t.get(4).type());
        assertEquals(" done",                t.get(4).value());
    }

    @Test void interpolationMultiple() {
        // "${a} + ${b}"  — two interpolations in one string
        var t = lex("\"${a} + ${b}\"");
        assertEquals(TokenType.INTERP_START, t.get(1).type());
        assertEquals(TokenType.INTERP_END,   t.get(3).type());
        assertEquals(TokenType.STRING_LIT,   t.get(4).type());  // " + "
        assertEquals(TokenType.INTERP_START, t.get(5).type());
        assertEquals(TokenType.INTERP_END,   t.get(7).type());
    }

    @Test void interpolationWithExpression() {
        // "${a + b}"  — expression inside interpolation
        var t = lex("\"${a + b}\"");
        assertEquals(TokenType.INTERP_START, t.get(1).type());
        assertEquals(TokenType.IDENT,        t.get(2).type());  // a
        assertEquals(TokenType.PLUS,         t.get(3).type());
        assertEquals(TokenType.IDENT,        t.get(4).type());  // b
        assertEquals(TokenType.INTERP_END,   t.get(5).type());
    }

    // ── Arithmetic operators ──────────────────────────────────────────────────

    @Test void arithmeticOperators() {
        assertType("+",  TokenType.PLUS);
        assertType("-",  TokenType.MINUS);
        assertType("*",  TokenType.STAR);
        assertType("/",  TokenType.SLASH);
        assertType("%",  TokenType.PERCENT);
        assertType("**", TokenType.STARSTAR);
    }

    // ── Comparison operators ──────────────────────────────────────────────────

    @Test void comparisonOperators() {
        assertType("==",  TokenType.EQ);
        assertType("!=",  TokenType.NEQ);
        assertType("<",   TokenType.LT);
        assertType("<=",  TokenType.LTE);
        assertType(">",   TokenType.GT);
        assertType(">=",  TokenType.GTE);
        assertType("===", TokenType.REF_EQ);
    }

    @Test void tripleEqualsDistinctFromDouble() {
        // Make sure === does not lex as EQ + ASSIGN
        Token t = first("===");
        assertEquals(TokenType.REF_EQ, t.type());
    }

    // ── Logical operators ─────────────────────────────────────────────────────

    @Test void logicalOperators() {
        assertType("&&", TokenType.AND);
        assertType("||", TokenType.OR);
        assertType("!",  TokenType.NOT);
    }

    // ── Special / compound operators ─────────────────────────────────────────

    @Test void arrowOperator() {
        assertType("->", TokenType.ARROW);
    }

    @Test void pipeOperator() {
        assertType("|>", TokenType.PIPE);
    }

    @Test void nullSafeAccess() {
        assertType("?.", TokenType.QUESTION_DOT);
    }

    @Test void elvisOperator() {
        assertType("?:", TokenType.ELVIS);
    }

    @Test void rangeInclusive() {
        assertType("..", TokenType.RANGE_INCL);
    }

    @Test void rangeExclusive() {
        assertType("..<", TokenType.RANGE_EXCL);
    }

    @Test void assignOperator() {
        assertType("=", TokenType.ASSIGN);
    }

    // ── Delimiters ────────────────────────────────────────────────────────────

    @Test void delimiters() {
        assertType("(", TokenType.LPAREN);
        assertType(")", TokenType.RPAREN);
        assertType("{", TokenType.LBRACE);
        assertType("}", TokenType.RBRACE);
        assertType("[", TokenType.LBRACKET);
        assertType("]", TokenType.RBRACKET);
        assertType(",", TokenType.COMMA);
        assertType(":", TokenType.COLON);
        assertType(";", TokenType.SEMICOLON);
        assertType(".", TokenType.DOT);
    }

    // ── Comments ──────────────────────────────────────────────────────────────

    @Test void lineCommentSkipped() {
        var t = lex("// this is a comment\n42");
        assertEquals(TokenType.INT_LIT, t.get(0).type());
        assertEquals("42", t.get(0).value());
    }

    @Test void inlineCommentSkipped() {
        var t = lex("42 // trailing comment");
        assertEquals(TokenType.INT_LIT, t.get(0).type());
        assertEquals(TokenType.EOF,     t.get(1).type());
    }

    @Test void commentDoesNotAffectNextLine() {
        var t = lex("let // comment\nx = 1");
        assertEquals(TokenType.LET,   t.get(0).type());
        assertEquals(TokenType.IDENT, t.get(1).type());
        assertEquals("x",             t.get(1).value());
    }

    // ── Position tracking ─────────────────────────────────────────────────────

    @Test void firstTokenPosition() {
        var t = lex("fn main");
        assertEquals(1, t.get(0).line());
        assertEquals(1, t.get(0).col());
    }

    @Test void secondTokenColumn() {
        var t = lex("fn main");
        // "fn" is 2 chars + 1 space = col 4 for "main"
        assertEquals(1, t.get(1).line());
        assertEquals(4, t.get(1).col());
    }

    @Test void newlineAdvancesLine() {
        var t = lex("let x\nlet y");
        // tokens: LET(0) IDENT(1) LET(2) IDENT(3) EOF(4)
        // second 'let' is at index 2, line 2, col 1
        assertEquals(2, t.get(2).line());
        assertEquals(1, t.get(2).col());
    }

    // ── Multi-token expressions ───────────────────────────────────────────────

    @Test void simpleAddExpression() {
        var t = lex("x + y");
        assertEquals(TokenType.IDENT, t.get(0).type());
        assertEquals(TokenType.PLUS,  t.get(1).type());
        assertEquals(TokenType.IDENT, t.get(2).type());
    }

    @Test void fnSignature() {
        var t = lex("fn add(a: Int, b: Int): Int");
        assertEquals(TokenType.FN,    t.get(0).type());
        assertEquals(TokenType.IDENT, t.get(1).type()); // add
        assertEquals(TokenType.LPAREN,t.get(2).type());
        assertEquals(TokenType.IDENT, t.get(3).type()); // a
        assertEquals(TokenType.COLON, t.get(4).type());
        assertEquals(TokenType.IDENT, t.get(5).type()); // Int
        assertEquals(TokenType.COMMA, t.get(6).type());
    }

    @Test void exclusiveRangeExpression() {
        var t = lex("0..<len");
        assertEquals(TokenType.INT_LIT,    t.get(0).type());
        assertEquals(TokenType.RANGE_EXCL, t.get(1).type());
        assertEquals(TokenType.IDENT,      t.get(2).type());
        assertEquals("len",                t.get(2).value());
    }

    @Test void pipelineExpression() {
        var t = lex("nums |> map");
        assertEquals(TokenType.IDENT, t.get(0).type());
        assertEquals(TokenType.PIPE,  t.get(1).type());
        assertEquals(TokenType.IDENT, t.get(2).type());
    }

    // ── MLang-specific constructs ─────────────────────────────────────────────────

    @Test void spawnVthreadStatement() {
        var t = lex("spawn_vthread crunch(1)");
        assertEquals(TokenType.SPAWN_VTHREAD, t.get(0).type());
        assertEquals(TokenType.IDENT,         t.get(1).type());
        assertEquals("crunch",                t.get(1).value());
        assertEquals(TokenType.LPAREN,        t.get(2).type());
        assertEquals(TokenType.INT_LIT,       t.get(3).type());
    }

    @Test void asyncFunctionSignature() {
        var t = lex("async fn fetch(): Future");
        assertEquals(TokenType.ASYNC,   t.get(0).type());
        assertEquals(TokenType.FN,    t.get(1).type());
        assertEquals(TokenType.IDENT, t.get(2).type());
        assertEquals("fetch",         t.get(2).value());
    }

    @Test void awaitExpression() {
        var t = lex("await fetchUser(id)");
        assertEquals(TokenType.AWAIT,    t.get(0).type());
        assertEquals(TokenType.IDENT, t.get(1).type());
    }

    @Test void tryCatchFinally() {
        var t = lex("try { } catch (e: Ex) { } finally { }");
        assertEquals(TokenType.TRY,     t.get(0).type());
        assertEquals(TokenType.LBRACE,  t.get(1).type());
        assertEquals(TokenType.RBRACE,  t.get(2).type());
        assertEquals(TokenType.CATCH,    t.get(3).type());
        assertEquals(TokenType.FINALLY, t.get(11).type());
    }

    @Test void throwExpression() {
        var t = lex("throw RuntimeException(\"msg\")");
        assertEquals(TokenType.THROW,  t.get(0).type());
        assertEquals(TokenType.IDENT, t.get(1).type());
        assertEquals("RuntimeException", t.get(1).value());
    }

    @Test void printCall() {
        var t = lex("print(\"hi\")");
        assertEquals(TokenType.PRINT,      t.get(0).type());
        assertEquals(TokenType.LPAREN,     t.get(1).type());
        assertEquals(TokenType.STRING_LIT, t.get(2).type());
        assertEquals("hi",                 t.get(2).value());
    }

    @Test void exitCall() {
        var t = lex("exit(0)");
        assertEquals(TokenType.EXIT,    t.get(0).type());
        assertEquals(TokenType.LPAREN,  t.get(1).type());
        assertEquals(TokenType.INT_LIT, t.get(2).type());
        assertEquals("0",               t.get(2).value());
    }

    @Test void lambdaArrow() {
        var t = lex("{ (x) -> x * 2 }");
        assertEquals(TokenType.LBRACE,  t.get(0).type());
        assertEquals(TokenType.LPAREN,  t.get(1).type());
        assertEquals(TokenType.IDENT,   t.get(2).type());
        assertEquals(TokenType.RPAREN,  t.get(3).type());
        assertEquals(TokenType.ARROW,   t.get(4).type());
    }

    // ── Error cases ───────────────────────────────────────────────────────────

    @Test void unexpectedCharThrows() {
        assertThrows(LexerException.class, () -> lex("@invalid"));
    }

    @Test void bareAmpersandThrows() {
        assertThrows(LexerException.class, () -> lex("a & b"));
    }

    @Test void barePipeThrows() {
        assertThrows(LexerException.class, () -> lex("a | b"));
    }
}
