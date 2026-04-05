package lang.m.lexer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lexer — converts M source text into a flat list of {@link Token}s.
 *
 * <p>The lexer is a single-pass, character-by-character scanner that:
 * <ol>
 *   <li>Skips whitespace ({@code ' '}, {@code '\t'}, {@code '\r'}, {@code '\n'})
 *       and line comments ({@code //} to end-of-line).</li>
 *   <li>Classifies each non-whitespace position as a string, number,
 *       identifier/keyword, or operator/delimiter.</li>
 *   <li>Tracks {@code line} and {@code col} counters for error reporting.</li>
 * </ol>
 *
 * <h2>String interpolation</h2>
 * Interpolated strings are split into alternating segments and expression tokens:
 * <pre>
 *   "Hello, ${name}!"  →  STRING_LIT("Hello, ")  INTERP_START  IDENT(name)  INTERP_END  STRING_LIT("!")
 * </pre>
 * Nested braces inside {@code ${...}} are tracked by depth so that
 * inner {@code {}} blocks do not prematurely terminate the interpolation.
 *
 * <h2>Number suffixes</h2>
 * <pre>
 *   42       → INT_LIT
 *   42L / 42l→ LONG_LIT
 *   3.14     → DOUBLE_LIT  (decimal point alone implies double)
 *   3.14f    → FLOAT_LIT
 *   3.14d    → DOUBLE_LIT  (explicit double suffix)
 * </pre>
 *
 * <p>Throws {@link LexerException} on any unrecognised character.
 */
public class Lexer {

    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
        // Built-in keywords
        Map.entry("print",        TokenType.PRINT),
        Map.entry("panic",        TokenType.PANIC),
        Map.entry("spawn_vthread",TokenType.SPAWN_VTHREAD),
        Map.entry("async",        TokenType.ASYNC),
        Map.entry("await",        TokenType.AWAIT),
        Map.entry("exit",         TokenType.EXIT),
        Map.entry("catch",        TokenType.CATCH),
        Map.entry("throw",        TokenType.THROW),
        // Standard keywords
        Map.entry("let",     TokenType.LET),
        Map.entry("var",     TokenType.VAR),
        Map.entry("fn",      TokenType.FN),
        Map.entry("return",  TokenType.RETURN),
        Map.entry("type",    TokenType.TYPE),
        Map.entry("if",      TokenType.IF),
        Map.entry("else",    TokenType.ELSE),
        Map.entry("for",     TokenType.FOR),
        Map.entry("in",      TokenType.IN),
        Map.entry("switch",  TokenType.SWITCH),
        Map.entry("case",    TokenType.CASE),
        Map.entry("default", TokenType.DEFAULT),
        Map.entry("try",     TokenType.TRY),
        Map.entry("finally", TokenType.FINALLY),
        Map.entry("module",  TokenType.MODULE),
        Map.entry("import",  TokenType.IMPORT),
        Map.entry("true",    TokenType.TRUE),
        Map.entry("false",   TokenType.FALSE),
        Map.entry("null",    TokenType.NULL),
        Map.entry("as",      TokenType.AS),
        Map.entry("is",      TokenType.IS)
    );

    private final String source;
    private int pos  = 0;
    private int line = 1;
    private int col  = 1;

    public Lexer(String source) {
        this.source = source;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Tokenize the entire source and return an ordered list of tokens.
     * The last element is always {@link TokenType#EOF}.
     *
     * @return immutable-ordered list of tokens
     * @throws LexerException if the source contains an unrecognised character
     */
    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        while (pos < source.length()) {
            skipWhitespaceAndComments();
            if (pos < source.length()) {
                scanNext(tokens);
            }
        }
        tokens.add(new Token(TokenType.EOF, "", line, col));
        return tokens;
    }

    // ── Primitive helpers ─────────────────────────────────────────────────────

    /** @return the character at the current position without consuming it */
    private char current() {
        return source.charAt(pos);
    }

    /**
     * Peek ahead without consuming any characters.
     *
     * @param offset number of positions ahead of {@code pos} to look at
     * @return the character at {@code pos + offset}, or {@code '\0'} if out of bounds
     */
    private char peek(int offset) {
        int p = pos + offset;
        return (p < source.length()) ? source.charAt(p) : '\0';
    }

    /**
     * Consume the current character, advance {@code pos}, and update
     * the {@code line}/{@code col} counters.
     *
     * @return the consumed character
     */
    private char advance() {
        char c = source.charAt(pos++);
        if (c == '\n') { line++; col = 1; }
        else           { col++;           }
        return c;
    }

    private void skipWhitespaceAndComments() {
        while (pos < source.length()) {
            char c = current();
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                advance();
            } else if (c == '/' && peek(1) == '/') {
                // Line comment — skip to end of line
                while (pos < source.length() && current() != '\n') advance();
            } else {
                break;
            }
        }
    }

    // ── Top-level dispatcher ──────────────────────────────────────────────────

    private void scanNext(List<Token> out) {
        char c = current();

        if (c == '"') {
            scanString(out);
            return;
        }
        if (Character.isDigit(c)) {
            out.add(scanNumber());
            return;
        }
        if (Character.isLetter(c) || c == '_') {
            out.add(scanIdentOrKeyword());
            return;
        }
        out.add(scanSymbol());
    }

    // ── String literal (with interpolation) ──────────────────────────────────

    private void scanString(List<Token> out) {
        int startLine = line;
        int startCol  = col;
        advance(); // consume opening "

        StringBuilder sb = new StringBuilder();

        while (pos < source.length()) {
            char c = current();

            if (c == '"') {
                advance(); // consume closing "
                out.add(new Token(TokenType.STRING_LIT, sb.toString(), startLine, startCol));
                return;
            }

            if (c == '\\') {
                // Escape sequence
                advance(); // consume backslash
                if (pos < source.length()) {
                    char esc = advance();
                    switch (esc) {
                        case 'n'  -> sb.append('\n');
                        case 't'  -> sb.append('\t');
                        case 'r'  -> sb.append('\r');
                        case '"'  -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '$'  -> sb.append('$');
                        default   -> { sb.append('\\'); sb.append(esc); }
                    }
                }
                continue;
            }

            if (c == '$' && peek(1) == '{') {
                // Emit accumulated string segment (may be empty)
                out.add(new Token(TokenType.STRING_LIT, sb.toString(), startLine, startCol));
                sb.setLength(0);

                // Emit INTERP_START for ${
                int interpLine = line; int interpCol = col;
                advance(); advance(); // consume '$' and '{'
                out.add(new Token(TokenType.INTERP_START, "${", interpLine, interpCol));

                // Lex expression tokens until matching closing brace, tracking nested braces
                int depth = 1;
                while (pos < source.length() && depth > 0) {
                    skipWhitespaceAndComments();
                    if (pos >= source.length()) break;

                    char ch = current();
                    if (ch == '{') {
                        depth++;
                        out.add(new Token(TokenType.LBRACE, "{", line, col));
                        advance();
                    } else if (ch == '}') {
                        depth--;
                        if (depth == 0) {
                            out.add(new Token(TokenType.INTERP_END, "}", line, col));
                            advance();
                        } else {
                            out.add(new Token(TokenType.RBRACE, "}", line, col));
                            advance();
                        }
                    } else {
                        scanNext(out);
                    }
                }

                // Next string segment starts here
                startLine = line;
                startCol  = col;
                continue;
            }

            sb.append(c);
            advance();
        }

        // Unterminated string — emit whatever was collected
        out.add(new Token(TokenType.STRING_LIT, sb.toString(), startLine, startCol));
    }

    // ── Number literal ────────────────────────────────────────────────────────

    private Token scanNumber() {
        int startLine = line;
        int startCol  = col;
        StringBuilder sb = new StringBuilder();
        boolean hasDecimal = false;

        while (pos < source.length() && Character.isDigit(current())) {
            sb.append(advance());
        }

        // Decimal point — only consume if followed by a digit (avoids eating '..' ranges)
        if (pos < source.length() && current() == '.' && Character.isDigit(peek(1))) {
            hasDecimal = true;
            sb.append(advance()); // consume '.'
            while (pos < source.length() && Character.isDigit(current())) {
                sb.append(advance());
            }
        }

        // Type suffix
        if (pos < source.length()) {
            char suffix = current();
            if (suffix == 'L' || suffix == 'l') {
                advance();
                return new Token(TokenType.LONG_LIT, sb.toString(), startLine, startCol);
            }
            if (suffix == 'f' || suffix == 'F') {
                advance();
                return new Token(TokenType.FLOAT_LIT, sb.toString(), startLine, startCol);
            }
            if (suffix == 'd' || suffix == 'D') {
                advance();
                return new Token(TokenType.DOUBLE_LIT, sb.toString(), startLine, startCol);
            }
        }

        if (hasDecimal) {
            return new Token(TokenType.DOUBLE_LIT, sb.toString(), startLine, startCol);
        }
        return new Token(TokenType.INT_LIT, sb.toString(), startLine, startCol);
    }

    // ── Identifier or keyword ─────────────────────────────────────────────────

    private Token scanIdentOrKeyword() {
        int startLine = line;
        int startCol  = col;
        StringBuilder sb = new StringBuilder();

        while (pos < source.length() && (Character.isLetterOrDigit(current()) || current() == '_')) {
            sb.append(advance());
        }

        String word = sb.toString();

        // Standalone _ is the wildcard pattern
        if (word.equals("_")) {
            return new Token(TokenType.WILDCARD, "_", startLine, startCol);
        }

        TokenType kw = KEYWORDS.get(word);
        if (kw != null) {
            return new Token(kw, word, startLine, startCol);
        }
        return new Token(TokenType.IDENT, word, startLine, startCol);
    }

    // ── Operators and delimiters ──────────────────────────────────────────────

    private Token scanSymbol() {
        int startLine = line;
        int startCol  = col;
        char c = advance(); // consume first character

        return switch (c) {
            // Single-character delimiters
            case '(' -> new Token(TokenType.LPAREN,    "(", startLine, startCol);
            case ')' -> new Token(TokenType.RPAREN,    ")", startLine, startCol);
            case '[' -> new Token(TokenType.LBRACKET,  "[", startLine, startCol);
            case ']' -> new Token(TokenType.RBRACKET,  "]", startLine, startCol);
            case '{' -> new Token(TokenType.LBRACE,    "{", startLine, startCol);
            case '}' -> new Token(TokenType.RBRACE,    "}", startLine, startCol);
            case ',' -> new Token(TokenType.COMMA,     ",", startLine, startCol);
            case ':' -> new Token(TokenType.COLON,     ":", startLine, startCol);
            case ';' -> new Token(TokenType.SEMICOLON, ";", startLine, startCol);
            case '+' -> new Token(TokenType.PLUS,      "+", startLine, startCol);
            case '%' -> new Token(TokenType.PERCENT,   "%", startLine, startCol);
            case '/' -> new Token(TokenType.SLASH,     "/", startLine, startCol);

            // - or ->
            case '-' -> {
                if (pos < source.length() && current() == '>') {
                    advance();
                    yield new Token(TokenType.ARROW, "->", startLine, startCol);
                }
                yield new Token(TokenType.MINUS, "-", startLine, startCol);
            }

            // * or **
            case '*' -> {
                if (pos < source.length() && current() == '*') {
                    advance();
                    yield new Token(TokenType.STARSTAR, "**", startLine, startCol);
                }
                yield new Token(TokenType.STAR, "*", startLine, startCol);
            }

            // = or == or ===
            case '=' -> {
                if (pos < source.length() && current() == '=') {
                    advance();
                    if (pos < source.length() && current() == '=') {
                        advance();
                        yield new Token(TokenType.REF_EQ, "===", startLine, startCol);
                    }
                    yield new Token(TokenType.EQ, "==", startLine, startCol);
                }
                yield new Token(TokenType.ASSIGN, "=", startLine, startCol);
            }

            // ! or !=
            case '!' -> {
                if (pos < source.length() && current() == '=') {
                    advance();
                    yield new Token(TokenType.NEQ, "!=", startLine, startCol);
                }
                yield new Token(TokenType.NOT, "!", startLine, startCol);
            }

            // < or <=
            case '<' -> {
                if (pos < source.length() && current() == '=') {
                    advance();
                    yield new Token(TokenType.LTE, "<=", startLine, startCol);
                }
                yield new Token(TokenType.LT, "<", startLine, startCol);
            }

            // > or >=
            case '>' -> {
                if (pos < source.length() && current() == '=') {
                    advance();
                    yield new Token(TokenType.GTE, ">=", startLine, startCol);
                }
                yield new Token(TokenType.GT, ">", startLine, startCol);
            }

            // && only (bare & is not valid in M)
            case '&' -> {
                if (pos < source.length() && current() == '&') {
                    advance();
                    yield new Token(TokenType.AND, "&&", startLine, startCol);
                }
                throw new LexerException("Unexpected character '&' — did you mean '&&'?", startLine, startCol);
            }

            // || or |>
            case '|' -> {
                if (pos < source.length() && current() == '|') {
                    advance();
                    yield new Token(TokenType.OR, "||", startLine, startCol);
                }
                if (pos < source.length() && current() == '>') {
                    advance();
                    yield new Token(TokenType.PIPE, "|>", startLine, startCol);
                }
                throw new LexerException("Unexpected character '|' — did you mean '||' or '|>'?", startLine, startCol);
            }

            // ?. or ?:
            case '?' -> {
                if (pos < source.length() && current() == '.') {
                    advance();
                    yield new Token(TokenType.QUESTION_DOT, "?.", startLine, startCol);
                }
                if (pos < source.length() && current() == ':') {
                    advance();
                    yield new Token(TokenType.ELVIS, "?:", startLine, startCol);
                }
                throw new LexerException("Unexpected character '?' — did you mean '?.' or '?:'?", startLine, startCol);
            }

            // .  or  ..  or  ..<
            case '.' -> {
                if (pos < source.length() && current() == '.') {
                    advance(); // consume second '.'
                    if (pos < source.length() && current() == '<') {
                        advance();
                        yield new Token(TokenType.RANGE_EXCL, "..<", startLine, startCol);
                    }
                    yield new Token(TokenType.RANGE_INCL, "..", startLine, startCol);
                }
                yield new Token(TokenType.DOT, ".", startLine, startCol);
            }

            default -> throw new LexerException("Unexpected character '" + c + "'", startLine, startCol);
        };
    }
}
