package lang.m.parser.ast;

/**
 * Trigger a fatal, unrecoverable panic ({@code panic}).
 *
 * <p>MLang syntax: {@code panic("Config must not be null")}
 *
 * <p>The code generator emits
 * {@code invokestatic MRuntime.panic(Ljava/lang/String;)V},
 * which throws a {@code PanicError} (extends {@link Error})
 * that cannot be caught by user {@code catch} blocks.
 *
 * @param message the {@code Str} expression describing the panic reason
 */
public record PanicNode(Node message) implements Node {}
