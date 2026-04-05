package lang.m.semantic;

/**
 * Thrown by {@link SemanticAnalyzer} when the AST violates the M language rules.
 *
 * <p>Known rule violations:
 * <ul>
 *   <li>Re-assignment to an immutable {@code let} binding</li>
 *   <li>Use of {@code await} outside an {@code async fn}</li>
 *   <li>Duplicate variable declaration in the same scope</li>
 * </ul>
 */
public class SemanticException extends RuntimeException {

    /**
     * @param message human-readable description of the violation
     */
    public SemanticException(String message) {
        super("[mc] Semantic error — " + message);
    }
}
