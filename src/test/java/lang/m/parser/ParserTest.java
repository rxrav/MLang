package lang.m.parser;

import lang.m.lexer.Lexer;
import lang.m.lexer.Token;
import lang.m.parser.ast.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParserTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ProgramNode parse(String src) {
        List<Token> tokens = new Lexer(src).tokenize();
        return new Parser(tokens).parseProgram();
    }

    private Node firstDecl(String src) {
        return parse(src).declarations().get(0);
    }

    // ── Module / import ───────────────────────────────────────────────────────

    @Test void moduleDeclaration() {
        Node n = firstDecl("module hello");
        assertInstanceOf(LetNode.class, n);
        assertEquals("__module__", ((LetNode) n).name());
        assertEquals("hello", ((LiteralNode) ((LetNode) n).initializer()).value());
    }

    @Test void dottedModuleDeclaration() {
        Node n = firstDecl("module com.example.app");
        assertEquals("com.example.app", ((LiteralNode) ((LetNode) n).initializer()).value());
    }

    @Test void importDeclaration() {
        Node n = firstDecl("import java.io.File");
        assertInstanceOf(LetNode.class, n);
        assertEquals("__import__", ((LetNode) n).name());
        assertEquals("java.io.File", ((LiteralNode) ((LetNode) n).initializer()).value());
    }

    @Test void wildcardImport() {
        Node n = firstDecl("import com.example.*");
        assertEquals("com.example.*", ((LiteralNode) ((LetNode) n).initializer()).value());
    }

    // ── Let / Var ─────────────────────────────────────────────────────────────

    @Test void letWithTypeAndLiteral() {
        Node n = firstDecl("let x: Int = 42");
        assertInstanceOf(LetNode.class, n);
        LetNode let = (LetNode) n;
        assertEquals("x",   let.name());
        assertEquals("Int", let.type());
        LiteralNode lit = (LiteralNode) let.initializer();
        assertEquals("int", lit.kind());
        assertEquals(42,    lit.value());
    }

    @Test void letInferredType() {
        Node n = firstDecl("let pi = 3.14");
        LetNode let = (LetNode) n;
        assertNull(let.type());
        assertEquals("double", ((LiteralNode) let.initializer()).kind());
    }

    @Test void varDeclaration() {
        Node n = firstDecl("var count: Int = 0");
        assertInstanceOf(VarNode.class, n);
        VarNode var = (VarNode) n;
        assertEquals("count", var.name());
        assertEquals("Int",   var.type());
    }

    @Test void letStringLiteral() {
        Node n = firstDecl("let name = \"Alice\"");
        LiteralNode lit = (LiteralNode) ((LetNode) n).initializer();
        assertEquals("string", lit.kind());
        assertEquals("Alice",  lit.value());
    }

    @Test void letBoolTrue() {
        Node n = firstDecl("let flag = true");
        assertEquals(true, ((LiteralNode) ((LetNode) n).initializer()).value());
    }

    @Test void letBoolFalse() {
        Node n = firstDecl("let flag = false");
        assertEquals(false, ((LiteralNode) ((LetNode) n).initializer()).value());
    }

    @Test void letNull() {
        Node n = firstDecl("let x = null");
        assertEquals("null", ((LiteralNode) ((LetNode) n).initializer()).kind());
    }

    // ── Function declarations ─────────────────────────────────────────────────

    @Test void simpleFnNoParams() {
        Node n = firstDecl("fn greet() { return 1 }");
        assertInstanceOf(FnNode.class, n);
        FnNode fn = (FnNode) n;
        assertEquals("greet", fn.name());
        assertTrue(fn.params().isEmpty());
        assertFalse(fn.isAsync());
    }

    @Test void fnWithParams() {
        Node n = firstDecl("fn add(a: Int, b: Int): Int { return a }");
        FnNode fn = (FnNode) n;
        assertEquals(2, fn.params().size());
        assertEquals("a",   fn.params().get(0).name());
        assertEquals("Int", fn.params().get(0).type());
        assertEquals("Int", fn.returnType());
    }

    @Test void fnExpressionBody() {
        Node n = firstDecl("fn double(x: Int): Int = x");
        FnNode fn = (FnNode) n;
        // expression body wraps in ReturnNode inside a BlockNode
        Node stmt = fn.body().statements().get(0);
        assertInstanceOf(ReturnNode.class, stmt);
    }

    @Test void asyncFnDeclaration() {
        Node n = firstDecl("async fn fetch(): Int { return 1 }");
        assertInstanceOf(AsyncFnNode.class, n);
        assertTrue(((AsyncFnNode) n).fn().isAsync());
    }

    // ── Expressions ───────────────────────────────────────────────────────────

    @Test void binaryAddExpr() {
        Node n = firstDecl("let x = a + b");
        BinaryNode bin = (BinaryNode) ((LetNode) n).initializer();
        assertEquals("+", bin.op());
        assertEquals("a", ((IdentNode) bin.left()).name());
        assertEquals("b", ((IdentNode) bin.right()).name());
    }

    @Test void operatorPrecedenceMulOverAdd() {
        // a + b * c  →  a + (b * c)
        Node n = firstDecl("let x = a + b * c");
        BinaryNode top = (BinaryNode) ((LetNode) n).initializer();
        assertEquals("+", top.op());
        BinaryNode right = (BinaryNode) top.right();
        assertEquals("*", right.op());
    }

    @Test void powerRightAssociative() {
        // 2 ** 3 ** 4  →  2 ** (3 ** 4)
        Node n = firstDecl("let x = 2 ** 3 ** 4");
        BinaryNode top = (BinaryNode) ((LetNode) n).initializer();
        assertEquals("**", top.op());
        assertInstanceOf(BinaryNode.class, top.right());
    }

    @Test void unaryNegation() {
        Node n = firstDecl("let x = -5");
        assertInstanceOf(UnaryNode.class, ((LetNode) n).initializer());
        assertEquals("-", ((UnaryNode) ((LetNode) n).initializer()).op());
    }

    @Test void unaryNot() {
        Node n = firstDecl("let x = !flag");
        assertEquals("!", ((UnaryNode) ((LetNode) n).initializer()).op());
    }

    @Test void functionCall() {
        Node n = firstDecl("let x = add(1, 2)");
        CallNode call = (CallNode) ((LetNode) n).initializer();
        assertEquals("add", ((IdentNode) call.callee()).name());
        assertEquals(2, call.args().size());
    }

    @Test void memberAccess() {
        Node n = firstDecl("let x = list.size");
        BinaryNode bin = (BinaryNode) ((LetNode) n).initializer();
        assertEquals(".", bin.op());
        assertEquals("list", ((IdentNode) bin.left()).name());
        assertEquals("size", ((IdentNode) bin.right()).name());
    }

    @Test void inclusiveRange() {
        Node n = firstDecl("let r = 1..10");
        BinaryNode bin = (BinaryNode) ((LetNode) n).initializer();
        assertEquals("..", bin.op());
    }

    @Test void exclusiveRange() {
        Node n = firstDecl("let r = 0..<n");
        BinaryNode bin = (BinaryNode) ((LetNode) n).initializer();
        assertEquals("..<", bin.op());
    }

    @Test void comparisonExpr() {
        Node n = firstDecl("let b = x == y");
        assertEquals("==", ((BinaryNode) ((LetNode) n).initializer()).op());
    }

    @Test void refEqualityExpr() {
        Node n = firstDecl("let b = x === y");
        assertEquals("===", ((BinaryNode) ((LetNode) n).initializer()).op());
    }

    @Test void logicalAnd() {
        Node n = firstDecl("let b = x && y");
        assertEquals("&&", ((BinaryNode) ((LetNode) n).initializer()).op());
    }

    @Test void logicalOr() {
        Node n = firstDecl("let b = x || y");
        assertEquals("||", ((BinaryNode) ((LetNode) n).initializer()).op());
    }

    @Test void groupedExpr() {
        // (a + b) * c  → top op should be *
        Node n = firstDecl("let x = (a + b) * c");
        assertEquals("*", ((BinaryNode) ((LetNode) n).initializer()).op());
    }

    // ── Lambdas ───────────────────────────────────────────────────────────────

    @Test void singleParamLambda() {
        Node n = firstDecl("let f = { (x) -> x }");
        LambdaNode lam = (LambdaNode) ((LetNode) n).initializer();
        assertEquals(1, lam.params().size());
        assertEquals("x", lam.params().get(0).name());
    }

    @Test void typedParamLambda() {
        Node n = firstDecl("let f = { (x: Int) -> x }");
        LambdaNode lam = (LambdaNode) ((LetNode) n).initializer();
        assertEquals("Int", lam.params().get(0).type());
    }

    @Test void multiParamLambda() {
        Node n = firstDecl("let f = { (a, b) -> a }");
        assertEquals(2, ((LambdaNode) ((LetNode) n).initializer()).params().size());
    }

    // ── Pipeline ──────────────────────────────────────────────────────────────

    @Test void pipeOperatorBecomesCall() {
        // x |> fn  →  CallNode(fn, [x])
        Node n = firstDecl("let r = x |> double");
        CallNode call = (CallNode) ((LetNode) n).initializer();
        assertEquals("double", ((IdentNode) call.callee()).name());
        assertEquals("x",      ((IdentNode) call.args().get(0)).name());
    }

    // ── If / else ─────────────────────────────────────────────────────────────

    @Test void simpleIf() {
        Node n = firstDecl("fn f() { if x { return 1 } }");
        IfNode ifNode = (IfNode) ((FnNode) n).body().statements().get(0);
        assertEquals("x", ((IdentNode) ifNode.condition()).name());
        assertNull(ifNode.otherwise());
    }

    @Test void ifElse() {
        Node n = firstDecl("fn f() { if x { return 1 } else { return 2 } }");
        IfNode ifNode = (IfNode) ((FnNode) n).body().statements().get(0);
        assertNotNull(ifNode.otherwise());
        assertInstanceOf(BlockNode.class, ifNode.otherwise());
    }

    @Test void ifElseIf() {
        Node n = firstDecl("fn f() { if a { return 1 } else if b { return 2 } }");
        IfNode ifNode = (IfNode) ((FnNode) n).body().statements().get(0);
        assertInstanceOf(IfNode.class, ifNode.otherwise());
    }

    // ── For loop ──────────────────────────────────────────────────────────────

    @Test void forInRange() {
        Node n = firstDecl("fn f() { for i in 1..10 { print(i) } }");
        ForNode forNode = (ForNode) ((FnNode) n).body().statements().get(0);
        assertEquals("i", forNode.variable());
        assertInstanceOf(BinaryNode.class, forNode.iterable());
    }

    @Test void forInList() {
        Node n = firstDecl("fn f() { for item in list { print(item) } }");
        ForNode forNode = (ForNode) ((FnNode) n).body().statements().get(0);
        assertEquals("item", forNode.variable());
        assertEquals("list", ((IdentNode) forNode.iterable()).name());
    }

    // ── Switch ────────────────────────────────────────────────────────────────

    @Test void switchWithCases() {
        Node n = firstDecl("""
            fn f() {
                switch code {
                    case 1 -> print(1)
                    case 2 -> print(2)
                    default -> print(0)
                }
            }
            """);
        SwitchNode sw = (SwitchNode) ((FnNode) n).body().statements().get(0);
        assertEquals("code", ((IdentNode) sw.subject()).name());
        assertEquals(2, sw.cases().size());
        assertNotNull(sw.defaultBranch());
    }

    @Test void switchNoDefault() {
        Node n = firstDecl("""
            fn f() {
                switch x { case 1 -> print(1) }
            }
            """);
        SwitchNode sw = (SwitchNode) ((FnNode) n).body().statements().get(0);
        assertEquals(1, sw.cases().size());
        assertNull(sw.defaultBranch());
    }

    // ── Try / catch / finally ─────────────────────────────────────────────────

    @Test void trySingleCatch() {
        Node n = firstDecl("""
            fn f() {
                try { print(1) } catch (e: Exception) { print(2) }
            }
            """);
        TryNode tr = (TryNode) ((FnNode) n).body().statements().get(0);
        assertEquals(1, tr.handlers().size());
        assertEquals("e", tr.handlers().get(0).varName());
        assertEquals("Exception", tr.handlers().get(0).exceptionType());
        assertNull(tr.finalizer());
    }

    @Test void tryMultipleCatch() {
        Node n = firstDecl("""
            fn f() {
                try { print(1) }
                catch (e: IOException) { print(2) }
                catch (e: Exception)   { print(3) }
            }
            """);
        TryNode tr = (TryNode) ((FnNode) n).body().statements().get(0);
        assertEquals(2, tr.handlers().size());
    }

    @Test void tryFinally() {
        Node n = firstDecl("""
            fn f() {
                try { print(1) } finally { print(2) }
            }
            """);
        TryNode tr = (TryNode) ((FnNode) n).body().statements().get(0);
        assertTrue(tr.handlers().isEmpty());
        assertNotNull(tr.finalizer());
    }

    @Test void tryCatchFinally() {
        Node n = firstDecl("""
            fn f() {
                try { print(1) }
                catch (e: Exception) { print(2) }
                finally { print(3) }
            }
            """);
        TryNode tr = (TryNode) ((FnNode) n).body().statements().get(0);
        assertEquals(1, tr.handlers().size());
        assertNotNull(tr.finalizer());
    }

    // ── Return / throw ─────────────────────────────────────────────────────────

    @Test void returnWithValue() {
        Node n = firstDecl("fn f() { return 42 }");
        ReturnNode ret = (ReturnNode) ((FnNode) n).body().statements().get(0);
        assertNotNull(ret.value());
        assertEquals(42, ((LiteralNode) ret.value()).value());
    }

    @Test void throwStatement() {
        Node n = firstDecl("fn f() { throw RuntimeException(\"bad\") }");
        assertInstanceOf(ThrowNode.class, ((FnNode) n).body().statements().get(0));
    }

    // ── spawn_vthread ─────────────────────────────────────────────────────────

    @Test void spawnVthreadNamedCall() {
        Node n = firstDecl("fn f() { spawn_vthread work(1) }");
        SpawnVthreadNode ms = (SpawnVthreadNode) ((FnNode) n).body().statements().get(0);
        assertInstanceOf(CallNode.class, ms.target());
    }

    @Test void spawnVthreadLambda() {
        Node n = firstDecl("fn f() { spawn_vthread { print(1) } }");
        SpawnVthreadNode ms = (SpawnVthreadNode) ((FnNode) n).body().statements().get(0);
        assertInstanceOf(BlockNode.class, ms.target());
    }

    // ── Assignment ────────────────────────────────────────────────────────────

    @Test void mutableAssignment() {
        Node n = firstDecl("fn f() { x = 5 }");
        AssignNode assign = (AssignNode) ((FnNode) n).body().statements().get(0);
        assertEquals("x", assign.name());
        assertEquals(5, ((LiteralNode) assign.value()).value());
    }

    // ── Built-ins ─────────────────────────────────────────────────────────────

    @Test void printBuiltin() {
        Node n = firstDecl("fn f() { print(\"hi\") }");
        assertInstanceOf(PrintNode.class, ((FnNode) n).body().statements().get(0));
    }

    @Test void panicBuiltin() {
        Node n = firstDecl("fn f() { panic(\"catch\") }");
        assertInstanceOf(PanicNode.class, ((FnNode) n).body().statements().get(0));
    }

    @Test void exitBuiltin() {
        Node n = firstDecl("fn f() { exit(0) }");
        assertInstanceOf(ExitNode.class, ((FnNode) n).body().statements().get(0));
    }

    @Test void hmExpr() {
        Node n = firstDecl("fn f() { let x = await fetch(1) }");
        assertInstanceOf(AwaitNode.class, ((LetNode) ((FnNode) n).body().statements().get(0)).initializer());
    }

    // ── Full program ──────────────────────────────────────────────────────────

    @Test void fullProgramParses() {
        String src = """
            module hello

            fn main() {
                let name: Str = "World"
                var count = 0
                for i in 1..5 {
                    count = count + i
                }
                switch count {
                    case 15 -> print("correct")
                    default -> print("wrong")
                }
                try {
                    throw RuntimeException("test")
                } catch (e: RuntimeException) {
                    print("caught")
                } finally {
                    print("done")
                }
                exit(0)
            }
            """;
        ProgramNode prog = parse(src);
        assertEquals(2, prog.declarations().size()); // module + fn
        assertInstanceOf(FnNode.class, prog.declarations().get(1));
    }

    // ── Error cases ───────────────────────────────────────────────────────────

    @Test void unexpectedTopLevelTokenThrows() {
        assertThrows(ParseException.class, () -> parse("123"));
    }

    @Test void missingFnBodyThrows() {
        assertThrows(ParseException.class, () -> parse("fn f("));
    }

    @Test void missingSwitchBraceThrows() {
        assertThrows(ParseException.class, () -> parse("fn f() { switch x { print(1) } }"));
    }
}
