package lang.m.parser.ast;

/**
 * A single formal parameter in a function or lambda signature.
 *
 * <p>M syntax: {@code name: Type} or just {@code name} (type inferred)
 *
 * @param name the parameter identifier
 * @param type the declared M type string, or {@code null} when omitted
 */
public record ParamNode(String name, String type) {}
