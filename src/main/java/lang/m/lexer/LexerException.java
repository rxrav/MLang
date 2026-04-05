package lang.m.lexer;

/**
 * Thrown by the {@link Lexer} when it encounters invalid or unexpected
 * characters in the M source text.
 *
 * <p>The message always includes the source location in the format:
 * {@code [mc] Lexer error at <line>:<col> — <description>}
 */
public class LexerException extends RuntimeException {
    private final int line;
    private final int col;

    /**
     * @param message human-readable description of the error
     * @param line    1-based source line number
     * @param col     1-based column offset
     */
    public LexerException(String message, int line, int col) {
        super("[mc] Lexer error at " + line + ":" + col + " — " + message);
        this.line = line;
        this.col  = col;
    }

    /** @return 1-based source line where the error occurred */
    public int getLine() { return line; }

    /** @return 1-based column offset where the error occurred */
    public int getCol()  { return col;  }
}
