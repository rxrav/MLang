> ⚠️
> **Vibe Coded Project** — MLang is a toy programming language built purely to learn how
> compilers work: lexing, parsing, semantic analysis, and JVM bytecode generation via
> ASM.  It is **not production-ready** and has no stability guarantees.  The goal was
> to explore how `async`/`await` and goroutine-style concurrency (replicated here as
> `spawn_vthread`) can be compiled down to Java virtual threads and `CompletableFuture`
> under the hood.
>
> **Java 21 is required** to compile and run MLang programs.  The compiler targets
> class file version 65.0 (Java 21) and `spawn_vthread` relies on the `Thread.ofVirtual()`
> API introduced in Java 21.

# M Language Compiler

M is a statically-typed, JVM-targeting programming language with a clean, Kotlin/Groovy-inspired syntax and
first-class async/await and virtual-thread concurrency. It compiles to Java 21 bytecode via the
[ASM 9.7](https://asm.ow2.io/) library.

---

## Features

| Feature | M keyword |
|---|---|
| Immutable binding | `let` |
| Mutable binding | `var` |
| Function | `fn` |
| Async function | `async fn` |
| Await a future | `await` |
| Spawn virtual thread | `spawn_vthread` |
| Pattern switch | `switch` / `case` |
| Exception handling | `try` / `catch` / `finally` |
| Throw | `throw` |
| Print to stdout | `print(...)` |
| Fatal panic | `panic(...)` |
| Exit program | `exit(n)` |

---

## Requirements

| Dependency | Version |
|---|---|
| JDK | 21+ |
| Maven | 3.9+ |

---

## Build

```bash
# Compile
mvn compile

# Run tests
mvn test

# Package standalone fat jar → target/mcompile.jar
mvn package
```

---

## Usage

```bash
# Compile a source file
java -jar target/mcompile.jar examples/01_hello_world.mlang

# Compile to a specific output directory
java -jar target/mcompile.jar examples/01_hello_world.mlang -o out/

# Dump the token stream (debug)
java -jar target/mcompile.jar examples/01_hello_world.mlang --tokens

# Dump the AST (debug)
java -jar target/mcompile.jar examples/01_hello_world.mlang --ast
```

---

## Quick Syntax Tour

```m
module hello

fn main() {
    // Variables
    let name: Str = "World"
    var count = 0

    // String interpolation
    print("Hello, ${name}!")

    // For loop
    for i in 1..5 {
        count = count + i
    }

    // Switch
    switch count {
        case 15  -> print("Sum is correct")
        default  -> print("Unexpected: ${count}")
    }

    // Exception handling
    try {
        throw RuntimeException("test")
    } catch (e: RuntimeException) {
        print("Caught: ${e.message}")
    } finally {
        print("Always runs")
    }

    // Lambdas — first-class, invoke like a function call
    let double = { (x: Int): Int -> x * 2 }
    let result = double(21)          // 42
    print("double(21) = ${result}")

    // Async — await is only valid inside an async fn
    exit(0)
}

// async fn main() is the correct async entry point
async fn main() {
    let result = await fetchData(42)
    print(result)

    // Spawn virtual thread (fire-and-collect side effect)
    spawn_vthread crunch(1)
    spawn_vthread crunch(2)
}

async fn fetchData(id: Int): Str {
    // call another async fn and await its result
    let tag = await resolveTag(id)
    return "data-${tag}"
}

async fn resolveTag(id: Int): Str {
    return "item-${id}"
}

fn crunch(id: Int) {
    print("spawn_vthread ${id} working...")
}
```

See [docs/LANGUAGE_REFERENCE.md](docs/LANGUAGE_REFERENCE.md) for the full language specification.

---

## Project Layout

```
MLang/
├── pom.xml                          Maven build (ASM 9.7, JUnit 5, Java 21)
├── examples/                        14 annotated .mlang example programs
└── src/
    ├── main/java/lang/m/
    │   ├── Main.java                CLI entry point (mc)
    │   ├── lexer/                   Lexer, Token, TokenType, LexerException
    │   ├── parser/
    │   │   ├── Parser.java          Recursive-descent parser (Step 4)
    │   │   └── ast/                 22 sealed AST node records
    │   ├── semantic/                SemanticAnalyzer, Scope, Symbol (Step 5)
    │   ├── codegen/                 Compiler, TypeMap (Steps 6-9)
    │   └── runtime/                 MRuntime (print, panic, exit)
    └── test/java/lang/m/
        ├── lexer/LexerTest.java                61 lexer unit tests
        ├── parser/ParserTest.java              57 parser unit tests
        ├── semantic/SemanticAnalyzerTest.java  44 semantic unit tests
        ├── codegen/CompilerTest.java           74 codegen integration tests
```

---

## Compilation Pipeline Status

| Step | Component | Status |
|---|---|---|
| 1 | Project scaffold | ✅ Done |
| 2 | Lexer | ✅ Done |
| 3 | AST nodes | ✅ Done |
| 4 | Parser | ✅ Done |
| 5 | Semantic analyser | ✅ Done |
| 6 | Core codegen | ✅ Done |
| 7 | Expression codegen (lambdas, interpolation, member access) | ✅ Done |
| 8 | Exception codegen (`try`/`catch`/`finally`/`throw`) | ✅ Done |
| 9 | Async + virtual threads (`async fn`, `await`, `spawn_vthread`) | ✅ Done |
| 10 | CLI polish (`--tokens`, `--ast`, `-r` run flag) | ✅ Done |

---

## Current Limitations

### No Object Construction

MLang is **not an object-oriented language** (yet). Anything that requires the JVM `new`
keyword — instantiating classes, creating objects, calling constructors — is **not
supported**. This includes:

- `new ArrayList()`, `new HashMap()`, `new File(...)`, etc.
- User-defined classes or data classes
- Inheritance, interfaces, or method overriding

Java standard library types can still be accessed today via `staticCall` (static methods)
and `dynCall` (instance methods on objects returned from static calls), but you cannot
construct instances directly from MLang source.

### Advanced Data Structures — Not Yet Implemented

Native collection types are **not yet implemented**:

| Type | Backed by | Status |
|---|---|---|
| Array / List | `java.util.ArrayList` | Upcoming |
| Map | `java.util.HashMap` | Upcoming |
| Set | `java.util.HashSet` | Upcoming |

Literal syntax (`[1, 2, 3]`, `{"key": value}`), index access (`arr[0]`, `map["key"]`),
and index assignment are all planned. See
[roadmaps/UPCOMING_DATA_STRUCTURES.md](UPCOMING_DATA_STRUCTURES.md) for the full implementation
plan.

---

## Documentation

In-depth reference material lives in the `docs/` folder:

| File | Description |
|---|---|
| [docs/LANGUAGE_REFERENCE.md](docs/LANGUAGE_REFERENCE.md) | Full language specification — syntax, types, operators, control flow, exceptions, async |
| [docs/HOW_MLANG_WORKS.md](docs/HOW_MLANG_WORKS.md) | End-to-end compiler pipeline walkthrough — lexer → parser → semantic analysis → codegen → class file |
| [docs/HOW_ASM_IS_USED.md](docs/HOW_ASM_IS_USED.md) | Detailed reference for every ASM 9.7 pattern used during bytecode generation |

---

## Releases

A pre-built fat jar is available in `releases/mcompile.jar`.  It bundles the compiler
and the `MRuntime` support library into a single self-contained artifact — no Maven build
required to use it.

```bash
# Compile only — writes <name>.class next to the source file
java -jar releases/mcompile.jar examples/01_hello_world.mlang

# Compile and run immediately
java -jar releases/mcompile.jar examples/01_hello_world.mlang -r

# Compile to a specific output directory, then run
java -jar releases/mcompile.jar examples/01_hello_world.mlang -o out/ -r

# Pass arguments to the program (everything after -r is forwarded)
java -jar releases/mcompile.jar my_program.mlang -r arg1 arg2
```

### Why `-r` is required to run compiled programs

Compiling a `.mlang` file produces a plain `.class` file, but that class depends on
`MRuntime` — the standard-library helper that implements `print`, `dynCall`,
`staticCall`, `joinAll`, and other built-ins.  `MRuntime` ships inside `mcompile.jar`
itself, not as a separate artifact.

If you compile without `-r` and then try to run with `java` directly, the JVM cannot
find `MRuntime` and throws `NoClassDefFoundError`.  Using `-r` avoids this: the
compiler stays resident and uses its own class loader as the parent when it loads your
compiled class, making all of `mcompile.jar`'s internals — including `MRuntime` —
automatically visible at runtime.

If you prefer a two-step workflow you can add the jar to the classpath manually:

```bash
java -jar releases/mcompile.jar examples/01_hello_world.mlang -o out/
java -cp "out/;releases/mcompile.jar" Main
```

---

## License

MIT
