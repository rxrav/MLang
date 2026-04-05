package lang.m.lexer;

/**
 * All token types recognized by the MLang {@link Lexer}.
 *
 * <p>Grouped into:
 * <ul>
 *   <li><b>Built-in keywords</b> — output, control, concurrency ({@code print}, {@code panic}, etc.)</li>
 *   <li><b>Standard keywords</b> — reserved words ({@code let}, {@code fn}, {@code if}, etc.)</li>
 *   <li><b>Literals</b> — numeric, boolean, string, and null literals</li>
 *   <li><b>Identifiers</b> — user-defined names</li>
 *   <li><b>Operators</b> — arithmetic, comparison, logical, and special operators</li>
 *   <li><b>Delimiters</b> — brackets, punctuation</li>
 *   <li><b>Interpolation markers</b> — {@code ${} and {@code }} inside strings</li>
 *   <li><b>EOF</b> — end of input sentinel</li>
 * </ul>
 */
public enum TokenType {

    // ── Built-in keywords ───────────────────────────────────────────────
    PRINT, PANIC, SPAWN_VTHREAD, EXIT,

    // ── Standard keywords ──────────────────────────────────────────────────
    LET, VAR, FN, RETURN, TYPE,
    IF, ELSE, FOR, IN, SWITCH, CASE, DEFAULT,
    TRY, CATCH, FINALLY, THROW,
    ASYNC, AWAIT,
    MODULE, IMPORT,
    TRUE, FALSE, NULL,
    AS, IS,

    // ── Literals ─────────────────────────────────────────────────────────────
    INT_LIT, LONG_LIT, FLOAT_LIT, DOUBLE_LIT, BOOL_LIT, STRING_LIT, NULL_LIT,

    // ── Identifiers ──────────────────────────────────────────────────────────
    IDENT,

    // ── Operators ────────────────────────────────────────────────────────────
    PLUS, MINUS, STAR, SLASH, PERCENT, STARSTAR,   // +  -  *  /  %  **
    EQ, NEQ, LT, LTE, GT, GTE,                     // == != <  <= >  >=
    REF_EQ,                                         // ===
    AND, OR, NOT,                                   // && || !
    ASSIGN,                                         // =
    ARROW,                                          // ->
    PIPE,                                           // |>
    QUESTION_DOT,                                   // ?.
    ELVIS,                                          // ?:
    RANGE_INCL,                                     // ..
    RANGE_EXCL,                                     // ..<
    DOT,                                            // .
    WILDCARD,                                       // _

    // ── Delimiters ───────────────────────────────────────────────────────────
    LPAREN, RPAREN,     // ( )
    LBRACE, RBRACE,     // { }
    LBRACKET, RBRACKET, // [ ]
    COMMA, COLON, SEMICOLON,

    // ── String interpolation markers ─────────────────────────────────────────
    INTERP_START, INTERP_END,   // ${ and }

    // ── Special ──────────────────────────────────────────────────────────────
    NEWLINE,
    EOF
}
