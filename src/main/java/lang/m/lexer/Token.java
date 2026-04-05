package lang.m.lexer;

/**
 * An immutable value produced by the {@link Lexer} representing one
 * indivisible unit of M source text.
 *
 * <p>Tokens carry their exact raw text from the source, allowing the
 * parser to reconstruct values (e.g. numeric literals) and emit
 * accurate error messages with precise source locations.
 *
 * @param type   classification of this token (see {@link TokenType})
 * @param value  raw text extracted from the source
 * @param line   1-based line number in the source file
 * @param col    1-based column offset in the source file
 */
public record Token(TokenType type, String value, int line, int col) {

    @Override
    public String toString() {
        return String.format("Token(%s, %s, %d:%d)", type, value, line, col);
    }
}
