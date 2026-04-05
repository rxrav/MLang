package lang.m.parser;

/**
 * Thrown by the {@link Parser} when the token stream does not match
 * the expected M grammar.
 *
 * <p>The message always includes the source location in the format:
 * {@code [mc] Parse error at <line>:<col> — <description>}
 */
public class ParseException extends RuntimeException {

    private final int line;
    private final int col;

    /**
     * @param message human-readable description of the grammar violation
     * @param line    1-based source line number of the offending token
     * @param col     1-based column offset of the offending token
     */
    public ParseException(String message, int line, int col) {
        super("[mc] Parse error at " + line + ":" + col + " \u2014 " + message);
        this.line = line;
        this.col  = col;
    }

    /** @return 1-based source line where the error occurred */
    public int getLine() { return line; }

    /** @return 1-based column offset where the error occurred */
    public int getCol()  { return col;  }
}
