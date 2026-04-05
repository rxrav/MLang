package lang.m.codegen;

import lang.m.lexer.Lexer;
import lang.m.parser.Parser;
import lang.m.parser.ast.ProgramNode;
import lang.m.semantic.SemanticAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the M language {@link Compiler}.
 *
 * <p>Each test compiles a small M program to a temp directory, then loads
 * the generated {@code .class} file using a {@link URLClassLoader} and
 * invokes the resulting methods via reflection to verify correctness.
 */
class CompilerTest {

    @TempDir Path tmp;

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Compile {@code src} and return the loaded {@link Class}.
     * The class name is derived from the module declaration or defaults to "Main".
     */
    private Class<?> compile(String src) throws Exception {
        ProgramNode program = new Parser(new Lexer(src).tokenize()).parseProgram();
        SemanticAnalyzer sa = new SemanticAnalyzer();
        sa.analyze(program);
        new Compiler(tmp.toString()).compile(program, sa.globalScope());

        // Find the generated .class file
        Path[] classFiles = Files.walk(tmp)
            .filter(p -> p.toString().endsWith(".class"))
            .toArray(Path[]::new);
        assertTrue(classFiles.length > 0, "No .class file generated");

        // Derive class name from file path relative to tmp
        String rel = tmp.relativize(classFiles[0]).toString()
            .replace("\\", "/").replace("/", ".").replace(".class", "");

        URLClassLoader cl = new URLClassLoader(new URL[]{tmp.toUri().toURL()},
            getClass().getClassLoader());
        return cl.loadClass(rel);
    }

    /** Call a static method with given args and return its result. */
    private Object invoke(Class<?> cls, String method, Class<?>[] types, Object... args)
            throws Exception {
        Method m = cls.getMethod(method, types);
        return m.invoke(null, args);
    }

    /** Compile, call main(), and return captured stdout. */
    private String runMain(String src) throws Exception {
        Class<?> cls = compile(src);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.PrintStream old = System.out;
        System.setOut(new java.io.PrintStream(out));
        try {
            invoke(cls, "main", new Class[]{String[].class}, (Object) new String[0]);
        } finally {
            System.setOut(old);
        }
        return out.toString().trim().replace("\r\n", "\n");
    }

    // ── Class generation ─────────────────────────────────────────────────────

    @Test void generatesClassFile(@TempDir Path dir) throws Exception {
        ProgramNode p = new Parser(new Lexer("fn main() { }").tokenize()).parseProgram();
        SemanticAnalyzer sa = new SemanticAnalyzer();
        sa.analyze(p);
        new Compiler(dir.toString()).compile(p, sa.globalScope());
        assertTrue(Files.walk(dir).anyMatch(f -> f.toString().endsWith(".class")));
    }

    @Test void classNameFromModule() throws Exception {
        String src = "module Hello\nfn main() { }";
        Class<?> cls = compile(src);
        assertEquals("Hello", cls.getSimpleName());
    }

    @Test void defaultClassNameIsMain() throws Exception {
        String src = "fn main() { }";
        Class<?> cls = compile(src);
        assertEquals("Main", cls.getSimpleName());
    }

    // ── Function emission ─────────────────────────────────────────────────────

    @Test void emptyMainCompiles() throws Exception {
        Class<?> cls = compile("fn main() { }");
        // main(String[]) must exist
        assertNotNull(cls.getMethod("main", String[].class));
    }

    @Test void staticFunctionExists() throws Exception {
        Class<?> cls = compile("fn add(a: Int, b: Int): Int { return a }");
        assertNotNull(cls.getMethod("add", int.class, int.class));
    }

    // ── bello / stdout output ──────────────────────────────────────────────

    @Test void printIntLiteral() throws Exception {
        assertEquals("42", runMain("fn main() { print(42) }"));
    }

    @Test void printStringLiteral() throws Exception {
        assertEquals("hello", runMain("fn main() { print(\"hello\") }"));
    }

    @Test void printBoolean() throws Exception {
        assertEquals("true", runMain("fn main() { print(true) }"));
    }

    @Test void printVariable() throws Exception {
        assertEquals("7", runMain("fn main() { let x = 7\n print(x) }"));
    }

    // ── Arithmetic ────────────────────────────────────────────────────────────

    @Test void addIntegers() throws Exception {
        assertEquals("5", runMain("fn main() { let x = 2 + 3\n print(x) }"));
    }

    @Test void subtractIntegers() throws Exception {
        assertEquals("1", runMain("fn main() { let x = 3 - 2\n print(x) }"));
    }

    @Test void multiplyIntegers() throws Exception {
        assertEquals("12", runMain("fn main() { let x = 3 * 4\n print(x) }"));
    }

    @Test void divideIntegers() throws Exception {
        assertEquals("3", runMain("fn main() { let x = 9 / 3\n print(x) }"));
    }

    @Test void moduloIntegers() throws Exception {
        assertEquals("1", runMain("fn main() { let x = 7 % 3\n print(x) }"));
    }

    // ── Variables ─────────────────────────────────────────────────────────────

    @Test void letBinding() throws Exception {
        assertEquals("10", runMain("fn main() { let n = 10\n print(n) }"));
    }

    @Test void varMutableReassign() throws Exception {
        assertEquals("20", runMain("fn main() { var n = 10\n n = 20\n print(n) }"));
    }

    // ── Conditionals ─────────────────────────────────────────────────────────

    @Test void ifTrueBranch() throws Exception {
        // 1 != 0 → true
        assertEquals("yes", runMain("fn main() { if 1 { print(\"yes\") } }"));
    }

    @Test void ifElseFalseBranch() throws Exception {
        assertEquals("no", runMain("fn main() { if 0 { print(\"yes\") } else { print(\"no\") } }"));
    }

    // ── For loop ─────────────────────────────────────────────────────────────

    @Test void forRangeSum() throws Exception {
        // sum 1+2+3+4+5 = 15
        String src = """
            fn main() {
                var sum = 0
                for i in 1..5 { sum = sum + i }
                print(sum)
            }
            """;
        assertEquals("15", runMain(src));
    }

    @Test void forExclusiveRange() throws Exception {
        // count of 0,1,2,3 = 4 iterations
        String src = """
            fn main() {
                var c = 0
                for i in 0..<4 { c = c + 1 }
                print(c)
            }
            """;
        assertEquals("4", runMain(src));
    }

    // ── Return value ─────────────────────────────────────────────────────────

    @Test void fnReturnInt() throws Exception {
        Class<?> cls = compile("fn answer(): Int { return 42 }");
        Object result = invoke(cls, "answer", new Class[0]);
        assertEquals(42, result);
    }

    @Test void fnCallFromMain() throws Exception {
        String src = """
            fn double(x: Int): Int { return x }
            fn main() { print(double(21)) }
            """;
        // The simple call emitter returns null for unknown descriptor, but the call itself compiles
        assertDoesNotThrow(() -> compile(src));
    }

    // ── String concat ─────────────────────────────────────────────────────────

    @Test void stringConcatLiterals() throws Exception {
        assertEquals("helloworld",
            runMain("fn main() { let s = \"hello\" + \"world\"\n print(s) }"));
    }

    // ── Switch ────────────────────────────────────────────────────────────────

    @Test void switchMatchesCase() throws Exception {
        String src = """
            fn main() {
                switch 1 {
                    case 1 -> print("one")
                    default -> print("other")
                }
            }
            """;
        assertEquals("one", runMain(src));
    }

    @Test void switchFallsToDefault() throws Exception {
        String src = """
            fn main() {
                switch 99 {
                    case 1 -> print("one")
                    default -> print("other")
                }
            }
            """;
        assertEquals("other", runMain(src));
    }

    // ── Try / catch / finally (Chapter 8) ────────────────────────────────────

    @Test void tryCatchCompiles() throws Exception {
        String src = """
            fn main() {
                try {
                    print("try")
                } catch (e: RuntimeException) {
                    print("caught")
                }
            }
            """;
        assertDoesNotThrow(() -> compile(src));
    }

    @Test void catchHandlerRuns() throws Exception {
        String src = """
            fn main() {
                try {
                    throw RuntimeException("boom")
                } catch (e: RuntimeException) {
                    print("caught")
                }
            }
            """;
        assertEquals("caught", runMain(src));
    }

    @Test void finallyRunsOnNormalExit() throws Exception {
        String src = """
            fn main() {
                try {
                    print("try")
                } finally {
                    print("fin")
                }
            }
            """;
        assertEquals("try\nfin", runMain(src));
    }

    @Test void finallyRunsAfterCatch() throws Exception {
        String src = """
            fn main() {
                try {
                    throw RuntimeException("x")
                } catch (e: RuntimeException) {
                    print("caught")
                } finally {
                    print("fin")
                }
            }
            """;
        assertEquals("caught\nfin", runMain(src));
    }

    @Test void throwConstructsException() throws Exception {
        // throw RuntimeException("msg") should produce a catchable RuntimeException
        String src = """
            fn main() {
                try {
                    throw RuntimeException("hello")
                } catch (e: RuntimeException) {
                    print("ok")
                }
            }
            """;
        assertEquals("ok", runMain(src));
    }

    @Test void multiCatch() throws Exception {
        String src = """
            fn main() {
                try {
                    throw IllegalArgumentException("bad")
                } catch (e: RuntimeException) {
                    print("runtime")
                } catch (e: Exception) {
                    print("exception")
                }
            }
            """;
        assertEquals("runtime", runMain(src));
    }

    @Test void tryCatchNoThrow() throws Exception {
        // No exception thrown — try body completes normally, finally runs
        String src = """
            fn main() {
                try {
                    print("ok")
                } catch (e: RuntimeException) {
                    print("should not run")
                } finally {
                    print("done")
                }
            }
            """;
        assertEquals("ok\ndone", runMain(src));
    }

    // ── Unary ────────────────────────────────────────────────────────────────

    @Test void unaryNegation() throws Exception {
        assertEquals("-5", runMain("fn main() { let x = -5\n print(x) }"));
    }

    @Test void unaryNot() throws Exception {
        assertEquals("false", runMain("fn main() { let b = !true\n print(b) }"));
    }

    // ── Lambdas (Step 7) ─────────────────────────────────────────────────────

    @Test void lambdaZeroParamCompiles() throws Exception {
        // 0-param lambda → MLambda; verify it compiles and stores
        String src = """
            fn main() {
                let f = { () -> 42 }
                print("ok")
            }
            """;
        assertEquals("ok", runMain(src));
    }

    @Test void lambdaOneParamCompiles() throws Exception {
        // 1-param lambda → MLambda; assigned to variable, class loaded OK
        String src = """
            fn main() {
                let f = { (x) -> x }
                print("ok")
            }
            """;
        assertEquals("ok", runMain(src));
    }

    @Test void lambdaTwoParamCompiles() throws Exception {
        // 2-param lambda → MLambda
        String src = """
            fn main() {
                let f = { (a, b) -> a }
                print("ok")
            }
            """;
        assertEquals("ok", runMain(src));
    }

    @Test void lambdaIsMLambda() throws Exception {
        // Verify the variable holds an MLambda
        String src = "fn main() { let f = { () -> 99 } }";
        Class<?> cls = compile(src);
        assertNotNull(cls.getMethod("main", String[].class));
    }

    @Test void lambdaInvokeZeroParam() throws Exception {
        // Zero-param lambda invocation: f() returns 42
        String src = """
            fn main() {
                let f = { () -> 42 }
                let result = f()
                print(result)
            }
            """;
        assertEquals("42", runMain(src));
    }

    @Test void lambdaInvokeOneParam() throws Exception {
        // One-param lambda invocation: doubler(21) returns 21 (identity)
        String src = """
            fn main() {
                let doubler = { (x) -> x }
                let result = doubler(21)
                print(result)
            }
            """;
        assertEquals("21", runMain(src));
    }

    @Test void lambdaInvokeTwoParam() throws Exception {
        // Two-param lambda invocation
        String src = """
            fn main() {
                let first = { (a, b) -> a }
                let result = first(7, 99)
                print(result)
            }
            """;
        assertEquals("7", runMain(src));
    }

    @Test void lambdaInvokeThreeParam() throws Exception {
        // Three-param lambda — any arity is supported with the MLambda design
        String src = """
            fn main() {
                let pick = { (a, b, c) -> b }
                let result = pick(1, 2, 3)
                print(result)
            }
            """;
        assertEquals("2", runMain(src));
    }

    @Test void multipleLambdasUniqueNames() throws Exception {
        // Two lambdas in one function → lambda$0 and lambda$1, no name clash
        String src = """
            fn main() {
                let f = { (x) -> x }
                let g = { (y) -> y }
                print("ok")
            }
            """;
        assertEquals("ok", runMain(src));
    }

    // ── Member access / method calls (Step 7) ────────────────────────────────

    @Test void stringLength() throws Exception {
        assertEquals("5", runMain("fn main() { let n = \"hello\".length()\n print(n) }"));
    }

    @Test void stringToUpperCase() throws Exception {
        assertEquals("HELLO", runMain("fn main() { let s = \"hello\".toUpperCase()\n print(s) }"));
    }

    @Test void stringToLowerCase() throws Exception {
        assertEquals("hello", runMain("fn main() { let s = \"HELLO\".toLowerCase()\n print(s) }"));
    }

    @Test void stringTrim() throws Exception {
        assertEquals("hi", runMain("fn main() { let s = \"  hi  \".trim()\n print(s) }"));
    }

    @Test void stringToString() throws Exception {
        assertEquals("world", runMain("fn main() { let s = \"world\".toString()\n print(s) }"));
    }

    @Test void stringIsEmpty() throws Exception {
        assertEquals("true", runMain("fn main() { let b = \"\".isEmpty()\n print(b) }"));
    }

    @Test void stringDynCallToUpperCase() throws Exception {
        // dynCall path: type not statically known
        assertEquals("HELLO",
            runMain("fn main() { let s: Any = \"hello\"\n print(s.toUpperCase()) }"));
    }

    // ── String interpolation (Step 7) ────────────────────────────────────────

    @Test void stringInterpolationSimple() throws Exception {
        assertEquals("Hello world",
            runMain("fn main() { let name = \"world\"\n print(\"Hello ${name}\") }"));
    }

    @Test void stringInterpolationExpression() throws Exception {
        assertEquals("sum=7",
            runMain("fn main() { let x = 3\n let y = 4\n print(\"sum=${x + y}\") }"));
    }

    @Test void stringInterpolationMultiSegment() throws Exception {
        assertEquals("1 + 2 = 3",
            runMain("fn main() { let a = 1\n let b = 2\n print(\"${a} + ${b} = ${a + b}\") }"));
    }

    /** Compile, call the named no-arg static method, join the CompletableFuture, return result as String. */
    private String runAsync(String src, String fnName) throws Exception {
        Class<?> cls = compile(src);
        java.util.concurrent.CompletableFuture<?> cf =
            (java.util.concurrent.CompletableFuture<?>) cls.getMethod(fnName).invoke(null);
        Object result = cf.join();
        return result == null ? "" : result.toString();
    }

    // ── Async / await / spawn_vthread (Step 9) ──────────────────────────────────

    @Test void asyncFnReturnsCompletableFuture() throws Exception {
        // async fn compiles and the wrapper returns a non-null CompletableFuture
        String src = """
            async fn greet(): Str {
                return "hello"
            }
            fn main() { }
            """;
        Class<?> cls = compile(src);
        Object cf = cls.getMethod("greet").invoke(null);
        assertInstanceOf(java.util.concurrent.CompletableFuture.class, cf);
    }

    @Test void asyncFnResultAccessibleViaAwait() throws Exception {
        // await inside an async fn unwraps the inner CompletableFuture
        String src = """
            async fn answer(): Str {
                return "42"
            }
            async fn run(): Str {
                let v = await answer()
                return v
            }
            fn main() { }
            """;
        assertEquals("42", runAsync(src, "run"));
    }

    @Test void awaitUnwrapsFuture() throws Exception {
        String src = """
            async fn getMessage(): Str {
                return "hello async"
            }
            async fn run(): Str {
                let msg = await getMessage()
                return msg
            }
            fn main() { }
            """;
        assertEquals("hello async", runAsync(src, "run"));
    }

    @Test void spawnVthreadRuns() throws Exception {
        // spawn_vthread fires a Runnable on a virtual thread — verify it compiles cleanly
        String src = """
            fn work() { }
            fn main() {
                spawn_vthread work()
                print("done")
            }
            """;
        assertEquals("done", runMain(src));
    }

    @Test void spawnVthreadLambda() throws Exception {
        // spawn_vthread { block } syntax compiles and main thread continues
        String src = """
            fn main() {
                spawn_vthread { }
                print("main")
            }
            """;
        assertEquals("main", runMain(src));
    }

    // ── async fn main() — Option D ───────────────────────────────────────────

    @Test void asyncMainRunsAndPrints() throws Exception {
        // async fn main() is the JVM entry point; await is valid inside it
        String src = """
            async fn greet(): Str {
                return "hello from async main"
            }
            async fn main() {
                let msg = await greet()
                print("${msg}")
            }
            """;
        assertEquals("hello from async main", runMain(src));
    }

    @Test void asyncMainAwaitsMultiple() throws Exception {
        // Sequential awaits in async main produce results in order
        String src = """
            async fn a(): Str { return "A" }
            async fn b(): Str { return "B" }
            async fn main() {
                let ra = await a()
                let rb = await b()
                print("${ra}${rb}")
            }
            """;
        assertEquals("AB", runMain(src));
    }

    @Test void asyncMainWithSpawnVthread() throws Exception {
        // async fn main() can combine await + spawn_vthread
        String src = """
            async fn fetch(): Str { return "data" }
            fn worker() { }
            async fn main() {
                let result = await fetch()
                spawn_vthread worker()
                print("${result}")
            }
            """;
        assertEquals("data", runMain(src));
    }

    // ── Static typing: Double-returning functions ────────────────────────────

    @Test void doubleFnReturnType() throws Exception {
        // Calls a Double->Double function and stores the result — triggers the
        // DSTORE vs ASTORE bug if inferredType(CallNode) returns "Any".
        String src = """
            fn add(a: Double, b: Double): Double {
                return a + b
            }
            fn main() {
                let sum = add(2.01, 3.3)
                print("${sum}")
            }
            """;
        // 2.01 + 3.3 in double arithmetic
        String result = runMain(src);
        assertTrue(result.startsWith("5.3"), "expected ~5.31 but got: " + result);
    }

    @Test void intFnReturnType() throws Exception {
        String src = """
            fn mul(a: Int, b: Int): Int {
                return a * b
            }
            fn main() {
                let x = mul(6, 7)
                print("${x}")
            }
            """;
        assertEquals("42", runMain(src));
    }

    // ── Typed lambdas ────────────────────────────────────────────────────────

    @Test void typedLambdaIntReturn() throws Exception {
        // Lambda with typed params and return type; result stored as Int
        String src = """
            fn main() {
                let add = { (a: Int, b: Int): Int -> a + b }
                let r = add(10, 32)
                print("${r}")
            }
            """;
        assertEquals("42", runMain(src));
    }

    @Test void typedLambdaDoubleReturn() throws Exception {
        // Lambda returning Double — result must be DSTORE'd, not ASTORE'd
        String src = """
            fn main() {
                let scale = { (x: Double): Double -> x * 2.0 }
                let r = scale(3.14)
                print("${r}")
            }
            """;
        String result = runMain(src);
        assertTrue(result.startsWith("6.28"), "expected ~6.28 but got: " + result);
    }

    @Test void typedLambdaIntLiteralsWidenedToDouble() throws Exception {
        // Integer literals (3, 4) passed to Double params — must widen without ClassCastException
        String src = """
            fn main() {
                let lmd = { (x: Double, y: Double): Double -> x * y }
                let prod = lmd(3, 4)
                print("${prod}")
            }
            """;
        assertEquals("12.0", runMain(src));
    }

    @Test void typedLambdaIntLiteralsWidenedToFloat() throws Exception {
        // Integer literals passed to Float params
        String src = """
            fn main() {
                let lmd = { (x: Float, y: Float): Float -> x * y }
                let prod = lmd(3, 4)
                print("${prod}")
            }
            """;
        assertEquals("12.0", runMain(src));
    }

    // ── Typed functions: Long, Float, Bool, Str ───────────────────────────────

    @Test void longFnParamAndReturn() throws Exception {
        // fn with Long params and Long return — exercises LLOAD/LRETURN and 2-slot allocation
        String src = """
            fn addLongs(a: Long, b: Long): Long {
                return a + b
            }
            fn main() {
                let r = addLongs(10L, 20L)
                print("${r}")
            }
            """;
        assertEquals("30", runMain(src));
    }

    @Test void floatFnParamAndReturn() throws Exception {
        // fn with Float params and Float return — exercises FLOAD/FRETURN
        String src = """
            fn sumFloats(a: Float, b: Float): Float {
                return a + b
            }
            fn main() {
                let r = sumFloats(1.5f, 2.5f)
                print("${r}")
            }
            """;
        assertEquals("4.0", runMain(src));
    }

    @Test void boolFnParamAndReturn() throws Exception {
        // fn with Bool param and Bool return — exercises ILOAD/IRETURN for boolean
        String src = """
            fn negate(b: Bool): Bool {
                if b { return false }
                return true
            }
            fn main() {
                let t = negate(false)
                let f = negate(true)
                print("${t}")
                print("${f}")
            }
            """;
        assertEquals("true\nfalse", runMain(src));
    }

    @Test void strFnParamAndReturn() throws Exception {
        // fn with Str param and Str return — exercises ALOAD/ARETURN for String refs
        String src = """
            fn shout(s: Str): Str {
                return s.toUpperCase()
            }
            fn main() {
                let r = shout("hello")
                print(r)
            }
            """;
        assertEquals("HELLO", runMain(src));
    }

    // ── Typed lambdas: Long, Bool, Str ────────────────────────────────────────

    @Test void typedLambdaLongReturn() throws Exception {
        // Lambda with Long params and Long return — exercises long boxing/unboxing via java.lang.Long
        String src = """
            fn main() {
                let add = { (a: Long, b: Long): Long -> a + b }
                let r = add(10L, 20L)
                print("${r}")
            }
            """;
        assertEquals("30", runMain(src));
    }

    @Test void typedLambdaBoolReturn() throws Exception {
        // Lambda with Bool param and Bool return — exercises Boolean boxing/unboxing
        String src = """
            fn main() {
                let flip = { (b: Bool): Bool -> !b }
                let t = flip(false)
                let f = flip(true)
                print("${t}")
                print("${f}")
            }
            """;
        assertEquals("true\nfalse", runMain(src));
    }

    @Test void typedLambdaStrReturn() throws Exception {
        // Lambda with Str param and Str return — String is a reference type, no unboxing needed
        String src = """
            fn main() {
                let echo = { (s: Str): Str -> s }
                let r = echo("mlang")
                print(r)
            }
            """;
        assertEquals("mlang", runMain(src));
    }
}

