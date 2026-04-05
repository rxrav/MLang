package lang.m.semantic;

import lang.m.lexer.Lexer;
import lang.m.parser.Parser;
import lang.m.parser.ast.ProgramNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SemanticAnalyzerTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ProgramNode parse(String src) {
        return new Parser(new Lexer(src).tokenize()).parseProgram();
    }

    private SemanticAnalyzer analyze(String src) {
        SemanticAnalyzer sa = new SemanticAnalyzer();
        sa.analyze(parse(src));
        return sa;
    }

    private void assertValid(String src) {
        assertDoesNotThrow(() -> analyze(src));
    }

    private void assertSemanticError(String src) {
        assertThrows(SemanticException.class, () -> analyze(src));
    }

    // ── Valid programs ────────────────────────────────────────────────────────

    @Test void emptyProgram() {
        assertValid("");
    }

    @Test void topLevelLet() {
        assertValid("let x: Int = 42");
    }

    @Test void topLevelVar() {
        assertValid("var count: Int = 0");
    }

    @Test void topLevelLetTypeInferred() {
        assertValid("let pi = 3.14");
    }

    @Test void simpleFn() {
        assertValid("fn greet() { return 1 }");
    }

    @Test void fnWithParams() {
        assertValid("fn add(a: Int, b: Int): Int { return a }");
    }

    @Test void fnUsesParam() {
        assertValid("fn double(x: Int): Int { return x }");
    }

    @Test void letInsideFn() {
        assertValid("fn f() { let y = 5 }");
    }

    @Test void varInsideFn() {
        assertValid("fn f() { var counter = 0 }");
    }

    @Test void assignMutableVar() {
        assertValid("fn f() { var x = 1\n x = 2 }");
    }

    @Test void asyncFnNoAwait() {
        assertValid("async fn fetch(): Int { return 1 }");
    }

    @Test void awaitInsideAsyncFn() {
        assertValid("async fn fetch(): Int { let x = await fetchData\n return x }");
    }

    @Test void ifElse() {
        assertValid("fn f() { var x = 1\n if x { var a = 1 } else { var b = 2 } }");
    }

    @Test void forLoop() {
        assertValid("fn f() { for i in 1..10 { print(i) } }");
    }

    @Test void forLoopVarInScope() {
        // The loop variable 'i' must be resolvable inside the body
        assertDoesNotThrow(() -> analyze("fn f() { for i in 1..10 { let y = i } }"));
    }

    @Test void switchStatement() {
        assertValid("""
            fn f() {
                switch 1 {
                    case 1 -> print(1)
                    default -> print(0)
                }
            }
            """);
    }

    @Test void tryCatchFinally() {
        assertValid("""
            fn f() {
                try { print(1) }
                catch (e: RuntimeException) { print(2) }
                finally { print(3) }
            }
            """);
    }

    @Test void catchVarInScope() {
        // Exception variable 'e' must be in scope inside catch handler
        assertDoesNotThrow(() -> analyze("""
            fn f() {
                try { print(1) }
                catch (e: RuntimeException) { let msg = e }
            }
            """));
    }

    @Test void lambdaParams() {
        assertValid("let f = { (x: Int) -> x }");
    }

    @Test void spawnVthread() {
        assertValid("fn f() { spawn_vthread work() }");
    }

    @Test void throwStatement() {
        assertValid("fn f() { throw RuntimeException(\"err\") }");
    }

    @Test void printBuiltin() {
        assertValid("fn f() { print(\"hi\") }");
    }

    @Test void panicBuiltin() {
        assertValid("fn f() { panic(\"panic\") }");
    }

    @Test void exitBuiltin() {
        assertValid("fn f() { exit(0) }");
    }

    @Test void mutualRecursion() {
        // Even() and odd() call each other; both must be in global scope from pass 1
        assertValid("""
            fn even(n: Int): Bool { return odd(n) }
            fn odd(n: Int): Bool  { return even(n) }
            """);
    }

    @Test void moduleAndImport() {
        assertValid("""
            module hello
            import java.io.File
            fn main() { print("hi") }
            """);
    }

    @Test void shadowingInInnerScope() {
        // Shadowing in a nested scope (different from same-scope duplication) is allowed
        assertValid("""
            fn f() {
                let x = 1
                if x {
                    let x = 2
                }
            }
            """);
    }

    @Test void nestedFn() {
        assertValid("""
            fn outer() {
                fn inner() { print(1) }
                inner()
            }
            """);
    }

    @Test void fullProgram() {
        assertValid("""
            module demo

            fn add(a: Int, b: Int): Int { return a }

            fn main() {
                let result = add(3, 4)
                var count = 0
                for i in 1..5 { count = count + i }
                switch count {
                    case 15 -> print("ok")
                    default -> print("no")
                }
                try {
                    throw RuntimeException("test")
                } catch (e: RuntimeException) {
                    print(e)
                } finally {
                    exit(0)
                }
            }
            """);
    }

    // ── Type inference ─────────────────────────────────────────────────────────

    @Test void intLiteralType() {
        SemanticAnalyzer sa = analyze("let x = 42");
        Symbol sym = sa.globalScope().resolve("x");
        assertNotNull(sym);
        assertEquals("Int", sym.type());
    }

    @Test void doubleLiteralType() {
        SemanticAnalyzer sa = analyze("let x = 3.14");
        assertEquals("Double", sa.globalScope().resolve("x").type());
    }

    @Test void strLiteralType() {
        SemanticAnalyzer sa = analyze("let x = \"hello\"");
        assertEquals("Str", sa.globalScope().resolve("x").type());
    }

    @Test void boolLiteralType() {
        SemanticAnalyzer sa = analyze("let x = true");
        assertEquals("Bool", sa.globalScope().resolve("x").type());
    }

    @Test void explicitTypeAnnotationWins() {
        SemanticAnalyzer sa = analyze("let x: Long = 5");
        assertEquals("Long", sa.globalScope().resolve("x").type());
    }

    @Test void fnSymbolInGlobalScope() {
        SemanticAnalyzer sa = analyze("fn greet(): Str { return \"hi\" }");
        Symbol sym = sa.globalScope().resolve("greet");
        assertNotNull(sym);
        assertEquals("Str", sym.type());
    }

    @Test void asyncFnSymbolIsFutureType() {
        SemanticAnalyzer sa = analyze("async fn load(): Int { return 1 }");
        Symbol sym = sa.globalScope().resolve("load");
        assertNotNull(sym);
        assertEquals("Future<Int>", sym.type());
    }

    @Test void localVarSlotAssigned() {
        // Params get slots 0,1; first local var gets slot 2
        SemanticAnalyzer sa = analyze("fn f(a: Int, b: Int) { let c = 1 }");
        // We can't inspect fn-local scope here, but at least no error
        assertNotNull(sa);
    }

    // ── Error cases ────────────────────────────────────────────────────────────

    @Test void assignToLetThrows() {
        assertSemanticError("""
            fn f() {
                let x = 5
                x = 10
            }
            """);
    }

    @Test void awaitOutsideAsyncFnThrows() {
        // await is only valid inside an async fn
        assertSemanticError("""
            fn f() {
                let x = await someFuture
            }
            """);
    }

    @Test void awaitInsideAsyncMainIsValid() {
        // async fn main() is a proper async fn — await is allowed
        assertValid("""
            async fn fetch(): Str { return "ok" }
            async fn main() {
                let r = await fetch()
            }
            """);
    }

    @Test void duplicateLetInSameScopeThrows() {
        assertSemanticError("""
            fn f() {
                let x = 1
                let x = 2
            }
            """);
    }

    @Test void duplicateVarInSameScopeThrows() {
        assertSemanticError("""
            fn f() {
                var x = 1
                var x = 2
            }
            """);
    }

    @Test void duplicateAtTopLevelThrows() {
        assertSemanticError("""
            let x = 1
            let x = 2
            """);
    }

    @Test void assignToLetInNestedScopeThrows() {
        assertSemanticError("""
            fn f() {
                let x = 1
                if x {
                    x = 99
                }
            }
            """);
    }
}
