# M Language Reference

> Version 0.1 — targets Java 21 / JVM class file version 65.0

---

## Table of Contents

1. [Source Files](#1-source-files)
2. [Keywords](#2-keywords)
3. [Types](#3-types)
4. [Variables](#4-variables)
5. [Functions](#5-functions)
6. [Operators](#6-operators)
7. [Control Flow](#7-control-flow)
8. [Exception Handling](#8-exception-handling)
9. [Lambdas and Pipelines](#9-lambdas-and-pipelines)
10. [Async and Concurrency](#10-async-and-concurrency)
11. [Modules and Imports](#11-modules-and-imports)
12. [Built-ins](#12-built-ins)
13. [String Interpolation](#13-string-interpolation)
14. [Formal Grammar](#14-formal-grammar-simplified-bnf)
15. [Semantic Rules](#15-semantic-rules)

---

## 1. Source Files

- Extension: `.mlang`
- One module per file
- Entry point: a top-level `fn main()` function
- Compiled to one `.class` file per source file

---

## 2. Keywords

### Standard Keywords

| Keyword | Purpose |
|---|---|
| `let` | Declare an immutable binding |
| `var` | Declare a mutable binding |
| `fn` | Declare a function |
| `return` | Return a value from a function |
| `type` | Declare a type alias |
| `if` | Conditional branch |
| `else` | Else branch |
| `for` | Iterator loop |
| `in` | Separator in for-loop / membership test |
| `switch` | Pattern-matching switch |
| `case` | One arm of a switch |
| `default` | Default arm of a switch |
| `try` | Protected block |
| `finally` | Always-runs cleanup block | 
| `module` | Declare the module name |
| `import` | Import a class or package |
| `true` | Boolean true literal |
| `false` | Boolean false literal |
| `null` | Null reference literal |
| `as` | Type cast |
| `is` | Type test (`instanceof`) |
| `_` | Wildcard / ignored binding |

### Built-in Keywords

| Keyword | Purpose |
|---|---|
| `print(x)` | Print to stdout |
| `panic(msg)` | Fatal panic (unrecoverable) |
| `spawn_vthread` | Spawn a virtual thread |
| `exit(n)` | Exit with status code |

Standard keywords used in their familiar forms: `async`, `await`, `try`, `catch`, `finally`, `throw`.

---

## 3. Types

### Primitive Types

| M Type | JVM Type | Notes |
|---|---|---|
| `Int` | `int` | 32-bit integer |
| `Long` | `long` | 64-bit integer |
| `Float` | `float` | 32-bit IEEE 754 |
| `Double` | `double` | 64-bit IEEE 754 |
| `Bool` | `boolean` | `true` or `false` |
| `Byte` | `byte` | 8-bit signed |
| `Char` | `char` | 16-bit Unicode |

### Reference Types

| M Type | JVM Type | Notes |
|---|---|---|
| `Str` | `java.lang.String` | |
| `Any` | `java.lang.Object` | |
| `Unit` | `void` | |
| `List<T>` | `java.util.List<T>` | Not yet implemented |
| `Map<K,V>` | `java.util.Map<K,V>` | Not yet implemented |
| `Set<T>` | `java.util.Set<T>` | Not yet implemented |
| `Future<T>` | `java.util.concurrent.CompletableFuture<T>` | Internal async return type |

### Nullable Types

> ⚠️ Not yet implemented. The `?` suffix is reserved syntax but the compiler does not yet emit null-checks or allow `null` in typed bindings.

Append `?` to any type to allow `null`:

```m
let x: Int? = null
let name: Str? = null
```

---

## 4. Variables

### Immutable — `let`

Bindings declared with `let` cannot be reassigned after initialisation.

```m
let name: Str = "Alice"
let pi = 3.14159          // type inferred as Double
```

### Mutable — `var`

Bindings declared with `var` can be reassigned using `=`.

```m
var count: Int = 0
count = count + 1
```

### Type Inference

The type annotation (`: Type`) is optional when the type can be inferred from the
right-hand side expression.

```m
let x = 42          // Int
let s = "hello"     // Str
let f = 1.5f        // Float
```

---

## 5. Functions

### Declaration

```m
fn name(param1: Type1, param2: Type2): ReturnType {
    // body
}
```

### Expression Body

Single-expression functions can omit the braces:

```m
fn add(a: Int, b: Int): Int = a + b
fn greet(name: Str): Str = "Hello, ${name}!"
```

### Entry Point

A synchronous entry point:

```m
fn main() {
    print("Hello, World!")
}
```

An async entry point — required when `await` is used at the top level:

```m
async fn main() {
    let data = await fetchSomething()
    print(data)
}
```

`async fn main()` compiles to the standard JVM `main(String[])V` entry point.
Internally the compiler wraps the body as:
```
main(String[]) { CompletableFuture.supplyAsync(() -> __async$main()).join() }
```
This is analogous to Python's `asyncio.run()` or Kotlin's `runBlocking { }`.

`main` takes no parameters and returns `Unit` (void).

### Default Parameter Values

> ⚠️ Not yet implemented. Default values are reserved syntax; omitting an argument currently causes a parse error.

```m
fn connect(host: Str, port: Int = 8080): Unit {
    print("Connecting to ${host}:${port}")
}
```

---

## 6. Operators

### Arithmetic

| Operator | Meaning |
|---|---|
| `+` | Addition |
| `-` | Subtraction |
| `*` | Multiplication |
| `/` | Division |
| `%` | Remainder |
| `**` | Power (`Math.pow`) |

### Comparison

| Operator | Meaning |
|---|---|
| `==` | Structural equality (calls `.equals()`) |
| `!=` | Not equal |
| `<` | Less than |
| `<=` | Less than or equal |
| `>` | Greater than |
| `>=` | Greater than or equal |
| `===` | Reference equality (`==` in Java) |

### Logical

| Operator | Meaning |
|---|---|
| `&&` | Logical AND |
| `\|\|` | Logical OR |
| `!` | Logical NOT |

### Special

| Operator | Meaning | Status |
|---|---|---|
| `?.` | Null-safe member access (`x?.field`) | Parsed; emitted as `.` (no null check yet) |
| `?:` | Elvis / null-coalesce: `x ?: default` | Not yet implemented |
| `\|>` | Pipe: `x \|> f` is equivalent to `f(x)` | Implemented |
| `..` | Inclusive integer range: `1..10` | Implemented |
| `..<` | Exclusive integer range: `0..<n` | Implemented |
| `as` | Type cast: `x as Int` | Not yet implemented |
| `is` | Type test: `x is Str` | Not yet implemented |

---

## 7. Control Flow

### If / Else

```m
if score > 90 {
    print("A")
} else if score > 75 {
    print("B")
} else {
    print("C")
}
```

`if` is also an expression:

```m
let grade = if score > 90 { "A" } else { "B" }
```

### For Loop

```m
// Iterate a collection
for item in list {
    print(item)
}

// Inclusive integer range
for i in 1..10 {
    print(i)
}

// Exclusive integer range
for i in 0..<list.size {
    print(list[i])
}
```

### Switch / Case

```m
switch statusCode {
    case 200 -> print("OK")
    case 404 -> print("Not Found")
    case 500 -> print("Server Error")
    default  -> print("Unknown: ${statusCode}")
}
```

Type-pattern matching:

```m
switch shape {
    case Circle(r)  -> 3.14 * r * r
    case Rect(w, h) -> w * h
    default         -> panic("Unknown shape")
}
```

`switch` is also an expression — the selected arm's value is returned:

```m
let label = switch code {
    case 1  -> "one"
    case 2  -> "two"
    default -> "many"
}
```

---

## 8. Exception Handling

### Try / catch / Finally

```m
try {
    let data = readFile("config.json")
    process(data)
} catch (e: IOException) {
    print("File error: ${e.message}")
} catch (e: Exception) {
    print("Error: ${e.message}")
} finally {
    cleanup()
}
```

- Multiple `catch` blocks are allowed; they are tested in declaration order.
- `finally` is optional; its block always runs regardless of exceptions.

### Throw

```m
throw RuntimeException("State is invalid")
throw IllegalArgumentException("n must be positive, got ${n}")
```

### Fatal Panic — `panic`

`panic` is not the same as `throw`. It throws a `PanicError` (extends `Error`)
which bypasses all `catch` blocks and cannot be caught by user code:

```m
if config == null {
    panic("Config must not be null — cannot continue")
}
```

---

## 9. Lambdas and Pipelines

### Lambda Syntax

```m
{ (param: Type) -> expression }      // explicit typed parameter
{ (x) -> x * 2 }                    // untyped parameter
{ (acc, x) -> acc + x }             // multiple parameters
{ () -> 42 }                         // zero parameters
{ (a, b, c) -> b }                   // any arity
```

Lambdas are first-class values. Invoke them exactly like a function call:

```m
let doubler = { (x) -> x * 2 }
let result  = doubler(21)            // result = 42

let add = { (a, b) -> a }
let sum = add(3, 4)                  // sum = 3 (first arg)
```

Lambdas compile to `invokedynamic` via `LambdaMetafactory`, backed by the
unified `MRuntime.MLambda` functional interface:
```java
@FunctionalInterface interface MLambda { Object call(Object[] args); }
```
Parameters of any arity are packed into an `Object[]` at the call site and
unpacked inside the generated synthetic method.

### Pipeline Operator `|>`

`x |> f` is equivalent to `f(x)`, where `f` is a named function or a lambda bound to a variable.

```m
let double = { (x: Int): Int -> x * 2 }
let square = { (x: Int): Int -> x * x }

let result = 5 |> double |> square   // square(double(5)) = 100
```

> ⚠️ The `it` implicit parameter is **not** implemented. Built-in collection HOFs (`filter`, `map`, `reduce`) do not exist yet. All lambdas must declare explicit parameters.

### Higher-Order Functions

> ⚠️ The `it` implicit lambda parameter is **not** implemented. The built-in collection methods `map`, `filter`, `each`, and `reduce` do not exist yet. Higher-order patterns work today by binding a lambda to a variable and passing it to a named function.

---

## 10. Async and Concurrency

### Async Function — `async fn`

Mark a function as async with `async`.

```m
async fn fetchUser(id: Int): Str {
    let resp = await httpGet("https://api.example.com/users/${id}")
    return resp.body
}
```

Compiled to two JVM methods:
1. A `private static synthetic __async$fetchUser(...)Object` containing the body.
2. A `public static fetchUser(...): CompletableFuture` wrapper that calls
   `CompletableFuture.supplyAsync(() -> __async$fetchUser(...))` and returns the future.

### Async Entry Point — `async fn main()`

When `await` is needed at the top level, declare `main` as async:

```m
async fn main() {
    let user = await fetchUser(1)
    print("Hello, ${user}!")

    // spawn_vthread is also valid inside async fn main
    spawn_vthread logEvent("startup")
}
```

`async fn main()` is compiled to the standard JVM entry point `main(String[])V`.
Passing `async fn main()` to a plain `fn main()` in the same file is a compile error.

Rules:
- `await` is **only valid inside an `async fn`** (including `async fn main()`).
- Using `await` in a plain `fn` is a `SemanticException`.

### Await — `await`

`await expr` blocks the current async fn's thread until the `Future<T>` resolves
and returns the unwrapped value `T`.

```m
async fn run(): Str {
    let a = await fetchUser(1)     // await in async fn — OK
    let b = await fetchScore()     // can chain multiple awaits
    return "${a}: ${b}"
}
```

Compiled to: `checkCast(CompletableFuture)` + `invokeVirtual CompletableFuture.join()`.

Rules:
- **Only valid inside an `async fn`** body (SemanticException otherwise).
- The operand must resolve to a `Future<T>` at runtime.

### Spawn Virtual Thread — `spawn_vthread`

`spawn_vthread` spawns a Java 21 virtual thread to run a side effect concurrently.
All spawned threads are **automatically joined** before the enclosing function returns
(structured concurrency — no thread leaks).

```m
// Spawn a named function call
spawn_vthread processOrder(id)
spawn_vthread sendEmail("user@example.com")

// Spawn an anonymous block
spawn_vthread {
    Thread.sleep(100)
    print("done")
}
```

Compiled to: `Thread.ofVirtual().start(runnable)`. The returned `Thread` is collected
in a per-function `ArrayList<Thread>`; `MRuntime.joinAll(list)` is emitted at every
function exit point.

Use `spawn_vthread` for **fire-and-collect side effects** (logging, analytics, I/O).
For values that must be retrieved, use `async fn` + `await`.

**Current limitations:**
- The `spawn_vthread` body/call-args cannot reference enclosing local variables
  (the Runnable body is a closed static method; variable capture is not yet implemented).
- Passing an `await` result directly as an argument to a typed-param async fn
  causes a `VerifyError` (checkCast not yet emitted at typed call sites).

---

## 11. Modules and Imports

### Module Declaration

```m
module com.example.myapp
```

Corresponds to a Java package and becomes the class name for the compiled output.

### Import

```m
import java.io.File
import java.util.List
import com.example.utils.*
```

---

## 12. Built-ins

These are globally available and do not require import.

### `print(value)`

Print any value to stdout followed by a newline.

```m
print("Hello, World!")
print(42)
print(true)
print(someList)
```

### `panic(message)`

Trigger a fatal, unrecoverable panic. Exits immediately; cannot be caught.

```m
panic("Invariant violated: list must not be empty")
```

### `exit(code)`

Exit the process with an integer status code.

```m
exit(0)    // success
exit(1)    // error
```

---

## 13. String Interpolation

Embed expressions inside string literals using `${...}`.

```m
let name = "Alice"
let age  = 30

print("Name: ${name}, Age: ${age}")
print("Sum: ${a + b}")
print("Upper: ${name.toUpperCase()}")
```

To include a literal `$` in a string, escape it with `\$`:

```m
print("Price: \$9.99")
```

Multi-character expressions, function calls, and arithmetic are all
valid inside `${...}`.

---

## 14. Formal Grammar (Simplified BNF)

```
program      ::= decl*
decl         ::= moduleDecl | importDecl | fnDecl | letDecl | varDecl
moduleDecl   ::= 'module' dottedIdent
importDecl   ::= 'import' dottedIdent ('.' '*')?
fnDecl       ::= ('async')? 'fn' IDENT '(' params? ')' (':' type)? (block | '=' expr)
letDecl      ::= 'let' IDENT (':' type)? '=' expr
varDecl      ::= 'var' IDENT (':' type)? '=' expr

params       ::= param (',' param)*
param        ::= IDENT (':' type)?
type         ::= IDENT ('<' type (',' type)? '>')?

block        ::= '{' stmt* '}'
stmt         ::= letDecl | varDecl | ifStmt | forStmt | switchStmt | tryStmt
               | returnStmt | throwStmt | spawnVthreadStmt | fnDecl | assignOrExpr
ifStmt       ::= 'if' expr block ('else' 'if' expr block)* ('else' block)?
forStmt      ::= 'for' IDENT 'in' expr block
switchStmt   ::= 'switch' expr '{' ('case' expr '->' (expr | block))* ('default' '->' (expr | block))? '}'
tryStmt      ::= 'try' block catchClause* ('finally' block)?
catchClause   ::= 'catch' '(' IDENT ':' type ')' block
returnStmt   ::= 'return' expr?
throwStmt     ::= 'throw' expr
spawnVthreadStmt   ::= 'spawn_vthread' (expr | block)
assignOrExpr ::= IDENT '=' expr | expr

expr         ::= binary
binary       ::= unary (INFIX_OP unary)*   -- Pratt precedence table below
unary        ::= ('-' | '!') unary | postfix
postfix      ::= primary ('(' args? ')' | '.' IDENT | '?.' IDENT)*
primary      ::= INT_LIT | LONG_LIT | FLOAT_LIT | DOUBLE_LIT | STRING_LIT
               | 'true' | 'false' | 'null' | IDENT | '(' expr ')' | lambdaOrBlock
               | printExpr | panicExpr | exitExpr | awaitExpr

lambdaOrBlock ::= '{' lambdaHead? stmt* '}'
lambdaHead   ::= (param (',' param)* ) '->'
printExpr    ::= 'print' '(' expr ')'
panicExpr    ::= 'panic' '(' expr ')'
exitExpr     ::= 'exit' '(' expr ')'
awaitExpr       ::= 'await' expr
```

### Operator Precedence (high → low)

| Precedence | Operators | Associativity |
|---|---|---|
| 12 | `()` `.` `?.` (call/member) | Left |
| 10 | `**` | Right |
| 9 | `*` `/` `%` | Left |
| 8 | `+` `-` | Left |
| 7 | `..` `..<` | Left |
| 6 | `<` `<=` `>` `>=` `is` | Left |
| 5 | `==` `!=` `===` | Left |
| 4 | `&&` | Left |
| 3 | `\|\|` | Left |
| 2 | `\|>` (pipe) | Left |

---

## 15. Semantic Rules

The semantic analyser enforces these rules after parsing:

### Immutability
`let` bindings cannot be reassigned. Only `var` bindings may appear on the
left-hand side of an assignment statement.

```m
let x = 5
x = 10       // SemanticException: Cannot reassign immutable binding 'x'

var y = 5
y = 10       // OK
```

### Async context
`await` may only appear inside an `async fn` body (including `async fn main()`):

```m
fn sync() {
    let x = await future   // SemanticException: 'await' can only be used inside an 'async fn'
}

async fn correct() {
    let x = await future   // OK
}

async fn main() {
    let x = await future   // OK — async fn main() is a proper async fn
}
```

### Duplicate declarations
Re-declaring the same name in the **same** scope is an error.
Shadowing a name from an **outer** scope is allowed.

```m
fn f() {
    let x = 1
    let x = 2      // SemanticException: Duplicate declaration of 'x'
}

fn g() {
    let x = 1
    if x {
        let x = 2  // OK — different (inner) scope
    }
}
```

### Scope rules
- Top-level `fn` declarations are all visible to each other (two-pass registration),
  enabling mutual recursion.
- Function parameters and local variables share the function scope.
- Each block (`{ }`) creates its own child scope.
- `for` loop variables live in the loop body scope.
- `catch` exception variables live in the handler scope.

### Type inference
The analyser infers types for literals and expressions when no explicit
annotation is given:

| Literal / Expression | Inferred type |
|---|---|
| `42`, `0` | `Int` |
| `42L` | `Long` |
| `3.14f` | `Float` |
| `3.14` | `Double` |
| `true`, `false` | `Bool` |
| `"hello"` | `Str` |
| `x == y`, `x < y`, `!b` | `Bool` |
| `1..10`, `0..<n` | `Range` |
| Unknown / Java refs | `Any` |

---

## 16. Code Generation (Step 6)

### Mapping M constructs to JVM bytecode

| M construct | JVM bytecode |
|---|---|
| `fn main()` | `public static void main(String[])` |
| `async fn main()` | `public static void main(String[])` — body runs via `supplyAsync(...).join()` |
| Other `fn` | `public static <name>(<params>)<retType>` |
| `async fn f()` | Two methods: `__async$f()Object` (body) + `f()CompletableFuture` (wrapper) |
| `let` / `var` | `xSTORE` at next available local slot |
| Assignment `x = e` | `xSTORE` to existing slot |
| Identifier load | `xLOAD` from slot |
| Integer arithmetic | `GeneratorAdapter.math(ADD/SUB/MUL/DIV/REM, INT_TYPE)` |
| String concat `+` | `StringBuilder.append()` chain |
| `if` / `else` | `ifZCmp` + `goTo` label pair |
| `for i in a..b` | Int counter loop with `iinc` / `if_icmpgt` |
| `for i in a..<b` | Int counter loop with `iinc` / `if_icmpge` |
| `for x in coll` | `Iterable.iterator()` + `Iterator.hasNext/next` loop |
| `switch` | if-else chain with `if_icmpne` (int) or `Object.equals` (ref) |
| `try / catch` | `catchException(start, end, ExType)` + `ASTORE` |
| `return e` | `xRETURN` matching method descriptor |
| `print(x)` | `invokestatic MRuntime.print(xType)V` |
| `panic(msg)` | `invokestatic MRuntime.panic(Str)V` |
| `exit(code)` | `invokestatic MRuntime.exit(I)V` |
| `{ (x) -> expr }` | `invokedynamic` LambdaMetafactory → `MRuntime.MLambda` |
| `{ (a, b) -> expr }` | `invokedynamic` LambdaMetafactory → `MRuntime.MLambda` |
| lambda call `f(args)` | `invokeinterface MRuntime$MLambda.call([Object])Object` |
| `str.length()` etc. | `invokevirtual java/lang/String.<method>` |
| `any.method(args)` | `invokestatic MRuntime.dynCall(Object,String,Object[])` (reflection) |
| `ClassName.method(args)` | `invokestatic MRuntime.staticCall(String,String,Object[])` (reflection) |
| string interpolation `"${e}"` | Chain of `BinaryNode("+")` → `StringBuilder` |
| `await future` | `checkCast(CompletableFuture)` + `invokeVirtual CompletableFuture.join()` |
| `spawn_vthread target` | `invokedynamic` Runnable + `Thread.ofVirtual().start(runnable)` |

### Class file layout
- One `.class` file per M source file.
- Class name derived from `module` declaration, or `Main` by default.
- All top-level functions become `public static` methods.
- A synthetic `public <init>()V` default constructor is always emitted.
- `ClassWriter.COMPUTE_FRAMES | COMPUTE_MAXS` — ASM computes stack frames.

### Implementation status

| Step | Component | Tests | Status |
|---|---|---|---|
| 1 | Scaffold | — | ✅ Done |
| 2 | Lexer | 61 | ✅ Done |
| 3 | AST nodes | — | ✅ Done |
| 4 | Parser | 57 | ✅ Done |
| 5 | Semantic analyser | 44 | ✅ Done |
| 6 | Core codegen | 28 | ✅ Done |
| 7 | Expression codegen (lambdas, interp, member access) | 44 | ✅ Done |
| 8 | Exception codegen (full `finally`, `throw`, multi-catch) | 6 | ✅ Done |
| 9 | Async + virtual threads (`async fn`, `await`, `spawn_vthread`, `async fn main()`) | 10 | ✅ Done |
| 10 | CLI polish (`--help`, `--version`, `-r` run flag) | — | ✅ Done |

**Total tests passing: 236 / 236**
