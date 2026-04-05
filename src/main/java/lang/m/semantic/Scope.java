package lang.m.semantic;

import java.util.HashMap;
import java.util.Map;

/**
 * A symbol table representing one lexical scope in an M program.
 *
 * <p>Scopes form a parent-chain (linked list) mirroring the block
 * nesting of the source program:
 * <pre>
 *   global scope
 *     └─ fn scope
 *         └─ if-block scope
 *             └─ for-loop scope
 * </pre>
 *
 * <p>Symbol lookup walks up the chain, so inner scopes shadow outer ones.
 * Symbol definition always writes into the current (innermost) scope.
 *
 * <p>Fully implemented in Step 5 (Semantic Analyzer).
 */
public class Scope {

    private final Scope parent;
    private final Map<String, Symbol> symbols = new HashMap<>();

    public Scope(Scope parent) {
        this.parent = parent;
    }

    /**
     * Define a new {@link Symbol} in this scope.
     * Silently shadows any symbol with the same name in a parent scope.
     *
     * @param symbol the symbol to register
     */
    public void define(Symbol symbol) {
        symbols.put(symbol.name(), symbol);
    }

    /**
     * Look up a symbol by name, walking up the scope chain.
     *
     * @param name identifier to look up
     * @return the nearest matching {@link Symbol}, or {@code null} if not found
     */
    public Symbol resolve(String name) {
        Symbol s = symbols.get(name);
        if (s != null) return s;
        if (parent != null) return parent.resolve(name);
        return null;
    }

    /** @return {@code true} if this scope has no parent (i.e. module/global scope) */
    public boolean isGlobal() {
        return parent == null;
    }

    /**
     * Check whether {@code name} is defined directly in this (innermost) scope,
     * without walking the parent chain.
     *
     * @param name the identifier to check
     * @return {@code true} if {@code name} has been defined in this exact scope
     */
    public boolean hasLocal(String name) {
        return symbols.containsKey(name);
    }

    /**
     * @return the enclosing scope, or {@code null} if this is the global scope
     */
    public Scope parent() {
        return parent;
    }
}
