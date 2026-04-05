# How MLang Works

A deep-dive into the MLang compilation pipeline — from raw source text to
executable JVM bytecode.

---

## Table of Contents

1. [Overview](#1-overview)
2. [Entry Point — Main.java](#2-entry-point--mainjava)
3. [Stage 1 — Lexer](#3-stage-1--lexer)
4. [Stage 2 — AST Nodes](#4-stage-2--ast-nodes)
5. [Stage 3 — Parser](#5-stage-3--parser)
6. [Stage 4 — Semantic Analyzer](#6-stage-4--semantic-analyzer)
7. [Stage 5 — TypeMap](#7-stage-5--typemap)
8. [Stage 6 — Compiler (Bytecode Generation)](#8-stage-6--compiler-bytecode-generation)
9. [Runtime Support — MRuntime](#9-runtime-support--mruntime)
10. [Lambdas — MLambda and invokedynamic](#10-lambdas--mlambda-and-invokedynamic)
11. [Async and Virtual Threads](#11-async-and-virtual-threads)
12. [Class File Output](#12-class-file-output)
13. [End-to-End Example](#13-end-to-end-example)

---

## 1. Overview

MLang is an **ahead-of-time compiler** — it reads a `.mlang` source file and writes
a standard JVM `.class` file. No interpreter, no VM of its own. The output class file
runs on any Java 21+ JVM with `java` directly.

```
Source (.mlang)
    │
    ▼
┌─────────┐   List<Token>   ┌──────────┐   ProgramNode (AST)     ┌────────────────────┐
│  Lexer  │ ──────────────▶ │  Parser  │ ──────────────── ────▶ │ SemanticAnalyzer   │
└─────────┘                 └──────────┘                         └────────────────────┘
                                                                           │
                                                                  typed + scoped AST
                                                                           │
                                                                           ▼
                                                                  ┌──────────────┐
                                                                  │   Compiler   │  ◀─── TypeMap
                                                                  └──────────────┘
                                                                           │
                                                                    .class file (Java 21)
                                                                           │
                                                                           ▼
                                                                    java Main  (runs)
```

**Key library**: [ASM 9.7](https://asm.ow2.io/) — a low-level bytecode manipulation
library used to emit JVM instructions directly. MLang uses ASM's `ClassWriter`,
`GeneratorAdapter`, and `LambdaMetafactory` support.

---

## 2. Entry Point — Main.java

`lang.m.Main` is the compiler CLI (`mcompile`). It:

1. Parses CLI flags (`--tokens`, `--ast`, `-o`, `-r`, `--version`, `--help`)
2. Reads the `.mlang` source file from disk
3. Runs the four pipeline stages in sequence
4. Writes the resulting `.class` file(s) to the output directory
5. Optionally reflectively invokes `main()` on the compiled class when `-r` is passed

```
mcompile <file.mlang>                  compile → .class in same directory
mcompile <file.mlang> -o out/          compile → .class in out/
mcompile <file.mlang> -r               compile and immediately run main()
mcompile <file.mlang> --tokens         dump token stream, exit
mcompile <file.mlang> --ast            dump AST, exit
```

---

## 3. Stage 1 — Lexer

**File**: `src/main/java/lang/m/lexer/Lexer.java`

The lexer is a **single-pass character scanner**. It reads the source string from left
to right and produces a flat `List<Token>`. Each `Token` is a record of:

```java
record Token(TokenType type, String value, int line, int col)
```

### What the lexer does

- Skips whitespace (`' '`, `'\t'`, `'\r'`, `'\n'`) and `//` line comments
- Classifies each non-whitespace position as a keyword, identifier, literal, operator,
  or delimiter
- Tracks `line` and `col` for error messages
- Terminates the list with a sentinel `EOF` token

### Keyword recognition

All reserved words are looked up in a static `Map<String, TokenType>`:

```
let var fn return type if else for in switch case default
try catch finally throw async await module import
true false null as is print panic spawn_vthread exit
```

Any identifier not in this map becomes a plain `IDENT` token.

### Number literal suffixes

| Suffix | Token type |
|---|---|
| `42` (no suffix) | `INT_LIT` |
| `42L` or `42l` | `LONG_LIT` |
| `3.14` (decimal) | `DOUBLE_LIT` |
| `3.14f` | `FLOAT_LIT` |
| `3.14d` | `DOUBLE_LIT` |

### String interpolation

Strings containing `${...}` are split into alternating segments:

```
"Hello, ${name}!"
  →  STRING_LIT("Hello, ")  INTERP_START  IDENT(name)  INTERP_END  STRING_LIT("!")
```

Nested brace depth is tracked so inner `{}` blocks don't prematurely close the
interpolation. The parser then stitches these segments into a chain of `BinaryNode("+")`
additions which the code generator lowers to `StringBuilder.append()` calls.

### Full token type list

```
PRINT PANIC SPAWN_VTHREAD EXIT ASYNC AWAIT          ← built-in keywords
LET VAR FN RETURN TYPE
IF ELSE FOR IN SWITCH CASE DEFAULT
TRY CATCH FINALLY THROW
MODULE IMPORT TRUE FALSE NULL AS IS
INT_LIT LONG_LIT FLOAT_LIT DOUBLE_LIT STRING_LIT    ← literals
IDENT
PLUS MINUS STAR SLASH PERCENT STARSTAR              ← arithmetic
EQ NEQ LT LTE GT GTE REF_EQ                        ← comparison
AND OR NOT ASSIGN ARROW PIPE                        ← logical / control
QUESTION_DOT ELVIS RANGE_INCL RANGE_EXCL DOT        ← special
WILDCARD
LPAREN RPAREN LBRACE RBRACE LBRACKET RBRACKET       ← delimiters
COMMA COLON SEMICOLON
INTERP_START INTERP_END                             ← interpolation markers
NEWLINE EOF
```

---

## 4. Stage 2 — AST Nodes

**Package**: `src/main/java/lang/m/parser/ast/`

The AST is a tree of **sealed Java records**. Every node implements the marker
interface `Node`. Records are immutable by default — no mutation after construction.

### All 27 node types

| Node | Represents |
|---|---|
| `ProgramNode(List<Node> declarations)` | Root of the tree — entire file |
| `FnNode(name, params, returnType, body, isAsync)` | `fn` declaration |
| `AsyncFnNode(FnNode fn)` | `async fn` wrapper |
| `ParamNode(name, type)` | One function parameter |
| `BlockNode(List<Node> stmts)` | `{ ... }` block |
| `LetNode(name, type, value)` | `let x = ...` |
| `VarNode(name, type, value)` | `var x = ...` |
| `AssignNode(name, value)` | `x = ...` |
| `ReturnNode(value)` | `return expr` |
| `IfNode(cond, then, otherwise)` | `if / else` |
| `ForNode(var, iterable, body)` | `for x in ...` |
| `SwitchNode(subject, cases, default)` | `switch { case ... }` |
| `CaseNode(pattern, body)` | One `case` arm |
| `TryNode(body, handlers, finalizer)` | `try / catch / finally` |
| `CatchNode(varName, exType, body)` | One `catch` clause |
| `ThrowNode(value)` | `throw expr` |
| `SpawnVthreadNode(target)` | `spawn_vthread` statement |
| `AwaitNode(expr)` | `await expr` |
| `LambdaNode(params, returnType, body)` | `{ (x) -> expr }` |
| `CallNode(callee, args)` | Function or method call |
| `BinaryNode(left, op, right)` | Binary operation, member access (`.`), range (`..`) |
| `UnaryNode(op, operand)` | `-x`, `!x` |
| `LiteralNode(kind, value)` | Number, string, bool, null literals |
| `IdentNode(name)` | Identifier reference |
| `PrintNode(value)` | `print(...)` |
| `PanicNode(message)` | `panic(...)` |
| `ExitNode(code)` | `exit(n)` |

---

## 5. Stage 3 — Parser

**File**: `src/main/java/lang/m/parser/Parser.java`

The parser is a **hand-written recursive descent parser** with a
**Pratt (top-down operator precedence) expression parser** for expressions.

### Two-phase structure

1. **Statement parsing** — `parseStatement()` dispatches on the next token type to
   dedicated `parseIf()`, `parseFor()`, `parseTry()`, etc. methods
2. **Expression parsing** — `parseExpr(minPrec)` implements Pratt precedence climbing;
   `infixPrec()` maps each infix `TokenType` to an integer precedence level

### Operator precedence table (high → low)

| Prec | Operators |
|---|---|
| 12 | `()` `.` `?.` (call, member access) |
| 10 | `**` (right-associative) |
| 9 | `*` `/` `%` |
| 8 | `+` `-` |
| 7 | `..` `..<` |
| 6 | `<` `<=` `>` `>=` |
| 5 | `==` `!=` `===` |
| 4 | `&&` |
| 3 | `\|\|` |
| 2 | `\|>` (pipe) |

### Notable parsing rules

- **Function bodies**: accept both block form `{ ... }` and expression form `= expr`
  (the latter is immediately wrapped in a `BlockNode(ReturnNode(...))`)
- **Lambda vs block**: `{` followed immediately by `(params)` or `->` is a lambda;
  `{` followed by a statement is a block
- **Pipe operator**: `left |> right` is rewritten to `CallNode(right, [left])` inline
  during parsing
- **Member access**: `x.y` and `x?.y` both parse to `BinaryNode(x, ".", IdentNode(y))`
  and `BinaryNode(x, "?.", IdentNode(y))` respectively; the code generator handles the
  distinction
- **String interpolation**: `INTERP_START` tokens inside a string trigger recursive
  `parseExpr()` calls; segments are chained with `BinaryNode("+", ...)`

### Error handling

`ParseException` carries `line` and `col` from the offending token for precise error
messages.

---

## 6. Stage 4 — Semantic Analyzer

**File**: `src/main/java/lang/m/semantic/SemanticAnalyzer.java`

The semantic analyzer walks the AST **before** code generation and enforces:

### Two-pass analysis

**Pass 1** — registers all top-level `fn` and `async fn` names into the global scope,
so functions can reference each other regardless of declaration order (mutual recursion).

**Pass 2** — full recursive AST walk:

| Rule | Enforcement |
|---|---|
| `let` immutability | `AssignNode` to a `let`-bound name → `SemanticException` |
| `await` context | `AwaitNode` outside `async fn` → `SemanticException` |
| Duplicate bindings | Same name in same scope → `SemanticException` |
| Type inference | Fills in missing `: Type` annotations from literal/expression types |
| Scope nesting | Each `{ }` block creates a child `Scope`; lookups walk up to parent |

### Scope / Symbol

`Scope` is a linked-list of hash maps:
```java
record Symbol(String name, String type, boolean mutable, int slot)
```
`slot` is the JVM local variable slot number, assigned incrementally. `Long` and
`Double` occupy two consecutive slots; all others occupy one.

Unknown identifiers (Java class names used in `throw`, interop calls, etc.) silently
resolve to type `"Any"` rather than throwing — this is intentional to support natural
Java interop without requiring import declarations everywhere.

---

## 7. Stage 5 — TypeMap

**File**: `src/main/java/lang/m/codegen/TypeMap.java`

A static lookup table. The code generator consults it constantly to avoid scattering
string comparisons across emitter methods.

### M type → JVM type

| M type | JVM descriptor | JVM internal class | Slots |
|---|---|---|---|
| `Int` | `I` | — | 1 |
| `Long` | `J` | — | 2 |
| `Float` | `F` | — | 1 |
| `Double` | `D` | — | 2 |
| `Bool` | `Z` | — | 1 |
| `Byte` | `B` | — | 1 |
| `Char` | `C` | — | 1 |
| `Str` | `Ljava/lang/String;` | `java/lang/String` | 1 |
| `Any` | `Ljava/lang/Object;` | `java/lang/Object` | 1 |
| `Unit` | `V` | — | return only |

`TypeMap` also exposes helpers: `loadOpcode(mType)` → `ILOAD`/`LLOAD`/`FLOAD`/
`DLOAD`/`ALOAD`, and `storeOpcode(mType)` → their `xSTORE` counterparts.

---

## 8. Stage 6 — Compiler (Bytecode Generation)

**File**: `src/main/java/lang/m/codegen/Compiler.java`

This is the core of MLang. It uses **ASM 9.7**'s `ClassWriter` and `GeneratorAdapter`
to emit Java 21 bytecode instruction by instruction.

### ClassWriter configuration

```java
new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS)
```

`COMPUTE_FRAMES` tells ASM to automatically compute all stack map frames required by
the JVM verifier. This is essential — computing frames manually for control flow with
exceptions, loops, and branches is extremely error-prone.

### Class structure emitted

```
public class <ClassName> {
    public <init>()V                         // default constructor (always emitted)
    public static void main(String[])        // from fn main() or async fn main()
    public static <T> <fnN>(params)ret       // one per top-level fn
    private static Object __async$<fn>()     // synthetic body for each async fn
    private static Object lambda$<n>(Object[]) // synthetic body for each lambda
}
```

### Key emitter methods

#### `emitLetVar` / `emitAssign`
Evaluates the RHS expression, stores result in the next available local slot via
`xSTORE`. `Long` and `Double` advance the slot counter by 2.

#### `emitFn`
1. Builds a JVM method descriptor from parameter types and return type using `TypeMap`
2. Calls `mg.visitCode()`, walks the body block, emits `xRETURN` at the end
3. If the function contains any `spawn_vthread`, pre-allocates a local `ArrayList<Thread>`
   slot and emits `MRuntime.joinAll(list)` before every return point

#### `emitBinary`
Dispatches on the operator string:
- `+` on strings → `StringBuilder.append()` chain
- `+` `-` `*` `/` `%` on numerics → `GeneratorAdapter.math(op, type)`
- `**` → `Math.pow(double, double)`
- `==` `!=` on primitives → `if_icmpeq`; on references → `Object.equals()`
- `===` → `if_acmpeq` (reference identity)
- `<` `<=` `>` `>=` → `GeneratorAdapter.ifCmp()`
- `&&` / `||` → short-circuit with jump labels
- `.` / `?.` → `emitMemberAccess()`
- `..` / `..<` → `emitRangeLiteral()` (creates a two-int range object)

#### `emitCall`
Three dispatch paths:
1. **Named function call** `f(args)` — `invokestatic ClassName.f(desc)`
2. **Static Java method** `ClassName.method(args)` — `invokestatic MRuntime.staticCall(...)`
   via reflection
3. **Instance method** `obj.method(args)` — `invokestatic MRuntime.dynCall(...)` via
   reflection

#### `emitSwitch`
Compiled as an **if-else chain** with integer `if_icmpne` for `Int` subjects and
`Object.equals()` for reference subjects. No JVM `tableswitch` / `lookupswitch` yet.

#### `emitTry`
Uses ASM's `catchException(start, end, exType)` to register exception table entries.
`finally` blocks are duplicated at each exit path (normal return and each `catch` exit) —
the standard JVM `finally` implementation strategy.

#### `emitFor`
- `for i in a..b` → integer counter loop with `iinc` / `if_icmpgt`
- `for i in a..<b` → same but `if_icmpge`
- `for x in collection` → `Iterable.iterator()` + `Iterator.hasNext()` / `Iterator.next()`

#### `unboxIfPrimitive`
After any call that returns `Object` (lambda calls, `dynCall`, `await.join()`), the
declared return type drives a cast or unbox:
- `Int`, `Bool` etc. → `checkCast(Box)` + `intValue()`/`booleanValue()` etc.
- `Str` → `checkCast(String)` (reference cast only, no unboxing)
- `Any` → left as `Object` (no-op)

This is the critical method that keeps the JVM verifier happy when generic `Object`
returns are narrowed back to their real types.

---

## 9. Runtime Support — MRuntime

**File**: `src/main/java/lang/m/runtime/MRuntime.java`

`MRuntime` is a **final utility class** whose static methods are called directly from
compiled MLang bytecode via `invokestatic`.

### Methods

| Method | Called from | Does |
|---|---|---|
| `print(Object)` | `print(x)` | `System.out.println(value)` |
| `print(int/long/double/boolean)` | `print(x)` with known primitive type | Avoids autoboxing |
| `panic(String)` | `panic(msg)` | Throws `PanicError` (extends `Error`) — bypasses all `catch` |
| `exit(int)` | `exit(n)` | `System.exit(code)` |
| `joinAll(List<Thread>)` | End of any fn with `spawn_vthread` | Joins all spawned virtual threads |
| `dynCall(Object, String, Object...)` | `obj.method(args)` where type is unknown | Reflective instance method dispatch |
| `staticCall(String, String, Object...)` | `ClassName.method(args)` | Reflective static method dispatch |

### `PanicError`

`PanicError extends Error`, not `Exception`. This is intentional: Java `catch` blocks
only intercept `Throwable` and `Exception`; `Error` subclasses pass through all user
`catch` clauses and are truly unrecoverable.

### Reflection in `dynCall` / `staticCall`

Both methods walk `Class.getMethods()` matching by name and argument count, then call
`coerceArgs()` to widen boxed `Number` arguments to the declared parameter types
(`Long`, `Integer`, `Double`, etc.). This allows MLang's numeric types to call Java
methods naturally without explicit casting at the call site.

---

## 10. Lambdas — MLambda and invokedynamic

### The MLambda interface

```java
@FunctionalInterface
interface MLambda {
    Object call(Object[] args);
}
```

All lambdas in MLang implement this single interface regardless of arity. Arguments
are packed into an `Object[]` at the call site and unpacked inside the generated method.
Return type is always `Object` at the interface boundary — `unboxIfPrimitive()` handles
the type narrowing at the call site.

### Compilation strategy

When the compiler encounters `{ (x: Int, y: Int): Int -> x + y }`:

1. **Generates a synthetic static method** in the same class:
   ```java
   private static Object lambda$0(Object[] __args) {
       int x = (Integer) __args[0];
       int y = (Integer) __args[1];
       int __result = x + y;
       return __result;   // autoboxed to Integer
   }
   ```

2. **Emits an `invokedynamic` instruction** at the lambda expression site using
   `LambdaMetafactory`. This is the standard JVM approach used by Java itself for
   lambda expressions — it generates an anonymous class on first call and reuses it.

3. **Call sites** invoke `MLambda.call(Object[])`:
   - Args are individually pushed and packed into an `Object[]` (`ANEWARRAY`, `AASTORE` loop)
   - `invokeinterface MLambda.call([Object)Object`
   - `unboxIfPrimitive(declaredReturnType)` narrows the `Object` result

---

## 11. Async and Virtual Threads

### `async fn`

Each `async fn f(params): T` compiles into **two JVM methods**:

```
private static Object __async$f(params) {
    // original body, returns Object (boxed T)
}

public static CompletableFuture<T> f(params) {
    return CompletableFuture.supplyAsync(() -> __async$f(params));
}
```

Callers get back a `CompletableFuture`. `await expr` compiles to:
```
checkCast CompletableFuture
invokevirtual CompletableFuture.join() → Object
unboxIfPrimitive(T)
```

### `async fn main()`

The special case: `async fn main()` must match the JVM entry point signature
`public static void main(String[])V`. So it compiles to:

```java
public static void main(String[] args) {
    CompletableFuture.supplyAsync(() -> __async$main()).join();
}
```

This is analogous to Python's `asyncio.run()` or Kotlin's `runBlocking {}`.

### `spawn_vthread`

Each `spawn_vthread target` compiles to:

1. Wrap `target` in a `Runnable` via `invokedynamic` (same `LambdaMetafactory` path)
2. `Thread.ofVirtual().start(runnable)` — returns a `Thread` object
3. `ASTORE` that thread into a per-function `ArrayList<Thread>` held in a local slot

At **every exit point** of a function that contains `spawn_vthread`:
```
invokestatic MRuntime.joinAll(ArrayList<Thread>)
```
This is **structured concurrency** — no virtual thread is ever leaked. They are always
joined before the enclosing function returns.

---

## 12. Class File Output

The compiler produces a single `.class` file per source file. It targets:

- **Java class file version 65.0** (Java 21)
- **One class per file** — class name is derived from the `module` declaration, or from
  the source filename (without extension) when `module` is absent
- All top-level functions → `public static` methods
- A synthetic `public <init>()V` default constructor is always emitted (required by the
  JVM class format)
- `ClassWriter.COMPUTE_FRAMES | COMPUTE_MAXS` — ASM computes all stack map frames and
  max stack/locals automatically

The `.class` file is a standard JVM class; it can be run with plain `java`:

```bash
java -cp <outdir>:<runtime_jar> Main
```

where `<runtime_jar>` provides `MRuntime` (needed for `print`, `panic`, `exit`,
`dynCall`, `staticCall`, `joinAll`).

---

## 13. End-to-End Example

**Source** (`hello.mlang`):
```m
fn main() {
    let name: Str = "World"
    print("Hello, ${name}!")
}
```

**Stage 1 — Lexer output** (simplified):
```
FN IDENT("main") LPAREN RPAREN LBRACE
  LET IDENT("name") COLON IDENT("Str") ASSIGN STRING_LIT("World")
  PRINT LPAREN
    STRING_LIT("Hello, ") INTERP_START IDENT("name") INTERP_END STRING_LIT("!")
  RPAREN
RBRACE EOF
```

**Stage 2 & 3 — AST**:
```
ProgramNode
  FnNode("main", [], Unit,
    BlockNode(
      LetNode("name", "Str", LiteralNode("string", "World"))
      PrintNode(
        BinaryNode(
          BinaryNode(LiteralNode("Hello, "), "+", IdentNode("name")),
          "+",
          LiteralNode("!")))))
```

**Stage 4 — Semantic output**: `name` → `Symbol("name", "Str", false, slot=1)`.

**Stage 6 — Bytecode emitted** (pseudocode):
```
public static void main(String[]):
  LDC "World"
  ASTORE 1                              // let name = "World"

  NEW StringBuilder
  DUP
  INVOKESPECIAL StringBuilder.<init>
  LDC "Hello, "
  INVOKEVIRTUAL StringBuilder.append(String)
  ALOAD 1                               // name
  INVOKEVIRTUAL StringBuilder.append(String)
  LDC "!"
  INVOKEVIRTUAL StringBuilder.append(String)
  INVOKEVIRTUAL StringBuilder.toString()

  INVOKESTATIC MRuntime.print(Object)   // print(...)
  RETURN
```

**Output** when run:
```
Hello, World!
```
