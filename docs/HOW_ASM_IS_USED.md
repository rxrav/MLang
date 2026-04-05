# How ASM is Used in MLang

A complete technical reference for every way the [ASM 9.7](https://asm.ow2.io/) bytecode
library is used inside `Compiler.java` to generate JVM class files from MLang source.

---

## Table of Contents

1. [What ASM Is](#1-what-asm-is)
2. [ASM Classes Used](#2-asm-classes-used)
3. [Class File Initialisation](#3-class-file-initialisation)
4. [Method Creation with GeneratorAdapter](#4-method-creation-with-generatoradapter)
5. [Local Variable Slots](#5-local-variable-slots)
6. [Emitting Literals](#6-emitting-literals)
7. [Arithmetic and Math](#7-arithmetic-and-math)
8. [String Concatenation](#8-string-concatenation)
9. [Comparison and Logical Operators](#9-comparison-and-logical-operators)
10. [Control Flow — Labels and Jumps](#10-control-flow--labels-and-jumps)
11. [For Loops](#11-for-loops)
12. [Switch Statements](#12-switch-statements)
13. [Exception Handling](#13-exception-handling)
14. [Method Calls](#14-method-calls)
15. [Object Construction](#15-object-construction)
16. [Boxing and Unboxing](#16-boxing-and-unboxing)
17. [Lambdas and invokedynamic](#17-lambdas-and-invokedynamic)
18. [Async Functions and CompletableFuture](#18-async-functions-and-completablefuture)
19. [Virtual Threads — spawn_vthread](#19-virtual-threads--spawn_vthread)
20. [Type Widening for Math.pow](#20-type-widening-for-mathpow)
21. [Class File Output](#21-class-file-output)
22. [Why COMPUTE_FRAMES Matters](#22-why-compute_frames-matters)

---

## 1. What ASM Is

ASM is a low-level Java bytecode manipulation library. Unlike frameworks such as
CGLIB or ByteBuddy that work at a higher abstraction level, ASM gives direct control
over every instruction, label, and stack frame in the output class file.

MLang uses ASM version 9.7 (the version that supports Java 21 class files —
class file version 65.0).

The three ASM modules used:

| Maven artifact | Purpose |
|---|---|
| `asm` | Core visitor API — `ClassWriter`, `MethodVisitor` |
| `asm-commons` | `GeneratorAdapter`, `Method` helpers |
| `asm-util` | `CheckClassAdapter`, `TraceClassVisitor` (debug) |

---

## 2. ASM Classes Used

| ASM class | Role in MLang |
|---|---|
| `ClassWriter` | Accumulates the full binary class file |
| `GeneratorAdapter` | High-level method emitter — wraps `MethodVisitor` |
| `Method` | Immutable name + descriptor pair for method references |
| `Type` | Represents a JVM type — used for descriptors and opcodes |
| `Handle` | Method handle reference used in `invokedynamic` bootstrap args |
| `Label` | Branch target for `GOTO`, `IF*`, loop heads, exception ranges |

---

## 3. Class File Initialisation

```java
cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
cw.visit(
    V21,                          // class file version 65.0 (Java 21)
    ACC_PUBLIC | ACC_SUPER,       // access flags
    className,                    // slash-separated class name, e.g. "Main"
    null,                         // generic signature (none)
    "java/lang/Object",           // superclass
    null                          // interfaces (none)
);
cw.visitSource(className + ".m", null);
```

`cw.visit(...)` writes the class file header. From this point every method and field
added to `cw` becomes part of the binary output.

A **default constructor** is always emitted, even if the program never uses `new`:

```java
GeneratorAdapter init = new GeneratorAdapter(
    ACC_PUBLIC,
    Method.getMethod("void <init>()"),
    null, null, cw);
init.visitCode();
init.loadThis();
init.invokeConstructor(Type.getType(Object.class), Method.getMethod("void <init>()"));
init.returnValue();
init.endMethod();
```

This calls `super()` (`Object.<init>`) to satisfy the JVM's requirement that every
constructor must chain to its superclass constructor.

---

## 4. Method Creation with GeneratorAdapter

Every MLang `fn` (including synthetic lambda bodies and async helpers) becomes one JVM
method. `GeneratorAdapter` is constructed for each method:

```java
mg = new GeneratorAdapter(
    ACC_PUBLIC | ACC_STATIC,        // access flags
    new Method("main", "([Ljava/lang/String;)V"),  // name + JVM descriptor
    null,   // generic signature
    null,   // declared exceptions
    cw      // class writer to attach method to
);
mg.visitCode();   // required — marks the start of method body
// ... emit instructions ...
mg.endMethod();   // required — finalises the method
```

### Descriptor building

JVM method descriptors are built from M type names via `TypeMap.DESCRIPTORS`:

```
fn add(a: Int, b: Int): Int  →  "(II)I"
fn main()                    →  "([Ljava/lang/String;)V"
fn shout(s: Str): Str        →  "(Ljava/lang/String;)Ljava/lang/String;"
async fn fetch(): Str        →  "()Ljava/util/concurrent/CompletableFuture;"
```

The `buildDescriptor(fn)` helper iterates parameters and appends each type descriptor,
then appends the return type descriptor.

### Access flags per method type

| Method | Flags |
|---|---|
| `fn main()` | `ACC_PUBLIC | ACC_STATIC` |
| Other `fn` | `ACC_PUBLIC | ACC_STATIC` |
| `async fn` body (`__async$name`) | `ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC` |
| Lambda body (`lambda$N`) | `ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC` |
| Default constructor | `ACC_PUBLIC` |

---

## 5. Local Variable Slots

The JVM assigns local variables by zero-based integer slot index. MLang tracks slots
in a parallel `Map<String, Integer> localSlots` (name → slot) and
`Map<String, String> localTypes` (name → M type).

Critical rules:

- `Long` and `Double` occupy **two consecutive slots** — `nextLocal` advances by 2
- All other types (including `Str`, `Any`, `Int`, `Bool`) occupy **one slot**
- `fn main(String[])` reserves slot 0 for the `String[]` args parameter (never
  exposed to M code) — `nextLocal` starts at 1
- Regular functions start at slot 0 for the first parameter

Reading a local variable:
```java
mg.visitVarInsn(TypeMap.loadOpcode(mType), slot);
// loadOpcode: Int/Bool/Byte/Char → ILOAD, Long → LLOAD, Float → FLOAD,
//             Double → DLOAD, Str/Any/ref → ALOAD
```

Writing a local variable:
```java
mg.visitVarInsn(TypeMap.storeOpcode(mType), slot);
// storeOpcode: same logic → ISTORE / LSTORE / FSTORE / DSTORE / ASTORE
```

Incrementing an integer loop counter:
```java
mg.visitIincInsn(slot, 1);   // IINC — fastest path, no load/add/store needed
```

---

## 6. Emitting Literals

`GeneratorAdapter.push(...)` has overloads for all primitive and String types:

```java
mg.push(42);          // BIPUSH / SIPUSH / LDC_W (int)       → ICONST, BIPUSH, ...
mg.push(42L);         // LDC2_W (long)
mg.push(3.14f);       // LDC (float)
mg.push(3.14);        // LDC2_W (double)
mg.push(true);        // ICONST_1
mg.push(false);       // ICONST_0
mg.push("hello");     // LDC "hello"
mg.visitInsn(ACONST_NULL);  // ACONST_NULL (null literal)
```

ASM automatically chooses the most compact encoding — `ICONST_0` through `ICONST_5`
for small integers, `BIPUSH` for bytes, `SIPUSH` for shorts, `LDC` for larger values.

---

## 7. Arithmetic and Math

`GeneratorAdapter.math(op, type)` emits the correct typed arithmetic opcode:

```java
emitExpr(left);               // push left operand
emitExpr(right);              // push right operand
mg.math(GeneratorAdapter.ADD, Type.INT_TYPE);   // IADD
mg.math(GeneratorAdapter.SUB, Type.LONG_TYPE);  // LSUB
mg.math(GeneratorAdapter.MUL, Type.FLOAT_TYPE); // FMUL
mg.math(GeneratorAdapter.DIV, Type.DOUBLE_TYPE);// DDIV
mg.math(GeneratorAdapter.REM, Type.INT_TYPE);   // IREM
```

The M type of the left operand drives which JVM type is used. Unknown / `Any` types
default to `INT_TYPE`.

**Negation** (`-x`):
```java
mg.math(GeneratorAdapter.NEG, Type.INT_TYPE);   // INEG
```

**Power** (`**`) — always uses `Math.pow(double, double)`:
```java
emitExpr(base);
toDouble(inferredType(base));   // I2D / L2D / F2D if needed
emitExpr(exp);
toDouble(inferredType(exp));
mg.invokeStatic(Type.getType(Math.class), new Method("pow", "(DD)D"));
```

`toDouble()` emits the JVM widening conversion opcodes:
- `I2D` — int to double
- `L2D` — long to double
- `F2D` — float to double

---

## 8. String Concatenation

All string concatenation (including string interpolation) uses a `StringBuilder` chain:

```java
mg.newInstance(Type.getType(StringBuilder.class));   // NEW StringBuilder
mg.dup();                                             // DUP
mg.invokeConstructor(Type.getType(StringBuilder.class),
    Method.getMethod("void <init>()"));              // INVOKESPECIAL <init>

emitExpr(left);
appendToStringBuilder(inferredType(left));           // INVOKEVIRTUAL append(T)

emitExpr(right);
appendToStringBuilder(inferredType(right));          // INVOKEVIRTUAL append(T)

mg.invokeVirtual(Type.getType(StringBuilder.class),
    Method.getMethod("String toString()"));          // INVOKEVIRTUAL toString
```

`appendToStringBuilder` picks the best `append` overload by M type to avoid unnecessary
boxing:

```java
String desc = switch (type) {
    case "Int"    -> "(I)Ljava/lang/StringBuilder;";
    case "Long"   -> "(J)Ljava/lang/StringBuilder;";
    case "Float"  -> "(F)Ljava/lang/StringBuilder;";
    case "Double" -> "(D)Ljava/lang/StringBuilder;";
    case "Bool"   -> "(Z)Ljava/lang/StringBuilder;";
    case "Str"    -> "(Ljava/lang/String;)Ljava/lang/StringBuilder;";
    default       -> "(Ljava/lang/Object;)Ljava/lang/StringBuilder;";
};
mg.invokeVirtual(Type.getType(StringBuilder.class), new Method("append", desc));
```

String interpolation (`"Hello, ${name}!"`) is desugared by the parser into nested
`BinaryNode("+", ...)` nodes. The code generator then lowers each `+` that touches a
string through this same `StringBuilder` path.

---

## 9. Comparison and Logical Operators

### Equality (`==`, `!=`)

For **primitive types** (`Int`, `Bool`, `Long`, etc.):

```java
// Long needs LCMP first
mg.visitInsn(LCMP);
mg.ifZCmp(GeneratorAdapter.EQ, trueLabel);

// Int / Bool: direct integer comparison
mg.ifICmp(GeneratorAdapter.EQ, trueLabel);  // IF_ICMPEQ
```

For **reference types** (`Str`, `Any`, objects):

```java
mg.invokeVirtual(Type.getType(Object.class),
    Method.getMethod("boolean equals(Object)"));
// Returns int (0/1) already, no extra label needed
```

For `!=`: the boolean result is flipped with `IXOR 1`.

### Reference equality (`===`)

```java
mg.ifCmp(Type.getType(Object.class), GeneratorAdapter.EQ, trueLabel);
// Emits: IF_ACMPEQ — true only when both references point to the same object
```

### Relational (`<`, `<=`, `>`, `>=`)

```java
int cmp = switch (op) {
    case "<"  -> GeneratorAdapter.LT;   // IF_ICMPLT
    case "<=" -> GeneratorAdapter.LE;   // IF_ICMPLE
    case ">"  -> GeneratorAdapter.GT;   // IF_ICMPGT
    case ">=" -> GeneratorAdapter.GE;   // IF_ICMPGE
};
mg.ifICmp(cmp, trueLabel);
mg.push(false);
mg.goTo(endLabel);
mg.mark(trueLabel);
mg.push(true);
mg.mark(endLabel);
```

### Short-circuit logical AND (`&&`)

```java
Label falseLabel = mg.newLabel();
Label endLabel   = mg.newLabel();
emitExpr(left);
mg.ifZCmp(GeneratorAdapter.EQ, falseLabel);  // IFEQ — short-circuit if left is false
emitExpr(right);
mg.goTo(endLabel);
mg.mark(falseLabel);
mg.push(false);
mg.mark(endLabel);
```

### Short-circuit logical OR (`||`)

```java
Label trueLabel = mg.newLabel();
Label endLabel  = mg.newLabel();
emitExpr(left);
mg.ifZCmp(GeneratorAdapter.NE, trueLabel);   // IFNE — short-circuit if left is true
emitExpr(right);
mg.goTo(endLabel);
mg.mark(trueLabel);
mg.push(true);
mg.mark(endLabel);
```

### Logical NOT (`!`)

```java
emitExpr(operand);
mg.push(1);
mg.visitInsn(IXOR);   // XOR with 1 flips a boolean int (0→1, 1→0)
```

---

## 10. Control Flow — Labels and Jumps

Every `if`, `for`, `switch`, `try`, and short-circuit operator uses ASM `Label` objects.

```java
Label elseLabel = mg.newLabel();   // creates an unplaced label
Label endLabel  = mg.newLabel();

// Conditionally jump to elseLabel when condition is false (== 0)
mg.ifZCmp(GeneratorAdapter.EQ, elseLabel);   // IFEQ elseLabel

// ... emit then-branch ...

mg.goTo(endLabel);     // GOTO endLabel

mg.mark(elseLabel);    // places elseLabel HERE in the bytecode stream

// ... emit else-branch ...

mg.mark(endLabel);     // places endLabel HERE
```

ASM resolves forward references automatically — you can `goTo` a label before
`mark`ing it. All offsets are patched when `endMethod()` is called.

---

## 11. For Loops

### Integer range loop (`for i in 1..10` or `for i in 0..<n`)

```java
// Emit start, store in counter slot
emitExpr(range.left());
mg.visitVarInsn(ISTORE, startSlot);

// Emit end bound, store in end slot
emitExpr(range.right());
mg.visitVarInsn(ISTORE, endSlot);

Label loopStart = mg.newLabel();
Label loopEnd   = mg.newLabel();

mg.mark(loopStart);
mg.visitVarInsn(ILOAD, startSlot);
mg.visitVarInsn(ILOAD, endSlot);

// ".." inclusive → exit when i > end:  IF_ICMPGT loopEnd
// "..<" exclusive → exit when i >= end: IF_ICMPGE loopEnd
mg.ifICmp(cmpOp, loopEnd);

// ... body ...

mg.visitIincInsn(startSlot, 1);  // IINC — increment counter
mg.goTo(loopStart);              // GOTO back to loop head
mg.mark(loopEnd);
```

### Collection for-each loop (`for x in collection`)

```java
emitExpr(iterable);
mg.checkCast(Type.getType("Ljava/lang/Iterable;"));
mg.invokeInterface(Type.getType("Ljava/lang/Iterable;"),
    new Method("iterator", "()Ljava/util/Iterator;"));
mg.visitVarInsn(ASTORE, iterSlot);    // store Iterator

mg.mark(loopStart);
mg.visitVarInsn(ALOAD, iterSlot);
mg.invokeInterface(Type.getType("Ljava/util/Iterator;"),
    new Method("hasNext", "()Z"));    // INVOKEINTERFACE hasNext
mg.ifZCmp(GeneratorAdapter.EQ, loopEnd);  // exit if false

mg.visitVarInsn(ALOAD, iterSlot);
mg.invokeInterface(Type.getType("Ljava/util/Iterator;"),
    new Method("next", "()Ljava/lang/Object;"));  // INVOKEINTERFACE next
mg.visitVarInsn(ASTORE, elemSlot);    // store element

// ... body ...

mg.goTo(loopStart);
mg.mark(loopEnd);
```

---

## 12. Switch Statements

Compiled as an **if-else chain** (no `tableswitch` / `lookupswitch`):

```java
// Stash subject in a local slot
emitExpr(subject);
mg.visitVarInsn(storeOpcode, subjectSlot);

for (CaseNode c : cases) {
    Label nextCase = mg.newLabel();
    mg.visitVarInsn(loadOpcode, subjectSlot);
    emitExpr(c.pattern());

    if (isPrimInt) {
        mg.ifICmp(GeneratorAdapter.NE, nextCase);   // IF_ICMPNE nextCase
    } else {
        // Reference: Object.equals
        mg.invokeVirtual(Type.getType(Object.class),
            Method.getMethod("boolean equals(Object)"));
        mg.ifZCmp(GeneratorAdapter.EQ, nextCase);   // IFEQ nextCase — if !equals()
    }

    emitStatement(c.body());
    mg.goTo(endLabel);
    mg.mark(nextCase);
}

if (defaultBranch != null) emitStatement(defaultBranch);
mg.mark(endLabel);
```

---

## 13. Exception Handling

### Exception table entries

ASM's `catchException` writes an entry into the class file's exception table:

```java
mg.mark(tryStart);
emitBlock(tryBody);
mg.mark(tryEnd);

// Normal path: inline finally, jump past handlers
if (hasFinalizer) emitBlock(finalizer);
mg.goTo(afterAll);

// Catch handler — catchException registers: [tryStart, tryEnd) → handler
mg.catchException(tryStart, tryEnd, Type.getObjectType("java/lang/RuntimeException"));
int exSlot = nextLocal++;
mg.visitVarInsn(ASTORE, exSlot);    // exception is on stack — store it
emitBlock(handlerBody);
if (hasFinalizer) emitBlock(finalizer);
mg.goTo(afterAll);
```

### `finally` implementation

`finally` blocks are **duplicated** at every exit path:

1. After the normal `try` body exits (before `goTo afterAll`)
2. After every `catch` handler exits (before `goTo afterAll`)
3. In a catch-all handler for uncaught exceptions:

```java
// Catch-all ensures finally runs even for uncaught exceptions
mg.catchException(tryStart, tryEnd, null);  // null = catch any Throwable
int tmpSlot = nextLocal++;
mg.visitVarInsn(ASTORE, tmpSlot);           // save exception
emitBlock(finalizer);                       // run finally
mg.visitVarInsn(ALOAD, tmpSlot);            // reload exception
mg.visitInsn(ATHROW);                       // rethrow
```

### Throw

```java
mg.visitInsn(ATHROW);   // ATHROW — pops Throwable from stack, throws it
```

For `throw RuntimeException("msg")` (constructor form), `NEW/DUP/INVOKESPECIAL` is
emitted first (see [Section 15](#15-object-construction)).

---

## 14. Method Calls

### INVOKESTATIC — top-level MLang functions

```java
mg.invokeStatic(
    Type.getObjectType(className),     // owner class (the compiled .m file)
    new Method(fnName, descriptor));   // name + JVM descriptor
```

### INVOKESTATIC — MRuntime built-ins

```java
mg.invokeStatic(
    Type.getObjectType("lang/m/runtime/MRuntime"),
    new Method("print", "(Ljava/lang/Object;)V"));

mg.invokeStatic(
    Type.getObjectType("lang/m/runtime/MRuntime"),
    new Method("dynCall",
        "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;"));

mg.invokeStatic(
    Type.getObjectType("lang/m/runtime/MRuntime"),
    new Method("staticCall",
        "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;"));
```

### INVOKEVIRTUAL — String methods (direct, no reflection)

```java
mg.invokeVirtual(
    Type.getType(String.class),
    new Method("toUpperCase", "()Ljava/lang/String;"));

mg.invokeVirtual(
    Type.getType(String.class),
    new Method("replace",
        "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;"));

mg.invokeVirtual(
    Type.getType(StringBuilder.class),
    new Method("toString", "()Ljava/lang/String;"));
```

### INVOKEINTERFACE — Iterator / List / MLambda

```java
mg.invokeInterface(
    Type.getType("Ljava/util/Iterator;"),
    new Method("hasNext", "()Z"));

mg.invokeInterface(
    Type.getObjectType("lang/m/runtime/MRuntime$MLambda"),
    new Method("call", "([Ljava/lang/Object;)Ljava/lang/Object;"));
```

### INVOKESPECIAL — constructors

```java
mg.invokeConstructor(
    Type.getType(Object.class),
    Method.getMethod("void <init>()"));
```

### Low-level `visitMethodInsn`

Used when calling target synthetic methods within the same class (e.g. forwarding to
`__async$body` from a capturing lambda):

```java
mg.visitMethodInsn(INVOKESTATIC, className, bodyName, bodyDesc, false);
```

---

## 15. Object Construction

The JVM requires three instructions to construct a new object:

```java
mg.newInstance(exType);   // NEW  — allocates uninitialised instance; leaves reference on stack
mg.dup();                 // DUP  — duplicate reference (one for <init> call, one to keep)
// ... push constructor arguments ...
mg.invokeConstructor(exType, new Method("<init>", "(Ljava/lang/String;)V")); // INVOKESPECIAL
```

This pattern is used in MLang for `throw ExType("message")` constructor calls, and for
internal allocation of `StringBuilder`, `ArrayList`, and `CompletableFuture`.

If `dup()` was not emitted, the `INVOKESPECIAL` call would consume the only reference,
leaving nothing on the stack.

---

## 16. Boxing and Unboxing

### Boxing — primitive → wrapper (`boxIfPrimitive`)

Required when passing primitives into `Object[]` arrays (lambda args, `dynCall` args,
`staticCall` args):

```java
case "Int"    → mg.invokeStatic(Integer.class,  "valueOf", "(I)Ljava/lang/Integer;")
case "Long"   → mg.invokeStatic(Long.class,     "valueOf", "(J)Ljava/lang/Long;")
case "Float"  → mg.invokeStatic(Float.class,    "valueOf", "(F)Ljava/lang/Float;")
case "Double" → mg.invokeStatic(Double.class,   "valueOf", "(D)Ljava/lang/Double;")
case "Bool"   → mg.invokeStatic(Boolean.class,  "valueOf", "(Z)Ljava/lang/Boolean;")
// Str / Any: already a reference — no-op
```

### Unboxing — Object → primitive (`unboxIfPrimitive`)

Required after:
- Lambda calls (`MLambda.call` returns `Object`)
- `await` (CompletableFuture.join() returns `Object`)
- `dynCall` / `staticCall` returns

The declared return type of the lambda or function drives which unbox sequence is emitted:

```java
case "Int"  → checkCast(Number) + invokevirtual Number.intValue()    → IRETURN
case "Long" → checkCast(Number) + invokevirtual Number.longValue()   → LRETURN
case "Float"→ checkCast(Number) + invokevirtual Number.floatValue()  → FRETURN
case "Double"→checkCast(Number) + invokevirtual Number.doubleValue() → DRETURN
case "Bool" → checkCast(Boolean) + invokevirtual Boolean.booleanValue() → IRETURN
case "Str"  → checkCast(String)      // reference cast only — no primitive unbox
case "Any"  → no-op                  // stays as Object
```

`Number` is used as the cast target (not `Integer`/`Long` etc.) so that widening numeric
conversions work at runtime — an `Integer` returned from `dynCall` can be unboxed as
`doubleValue()` without a `ClassCastException`.

This is the most critical correctness mechanism in the compiler. Without it, the JVM
bytecode verifier rejects the class: it sees `Object` on the stack but `String` (or an
`int`) is expected by the subsequent instruction.

---

## 17. Lambdas and invokedynamic

### Overview

Lambdas compile into two things:
1. A **synthetic static method** that contains the lambda body
2. An **`invokedynamic` instruction** at the lambda expression site that produces a
   `MLambda` instance backed by that method

### Synthetic lambda body

The body method always has the `MLambda` SAM descriptor:
```
([Ljava/lang/Object;)Ljava/lang/Object;
```

The compiler switches `mg` to point at a new `GeneratorAdapter` targeting the synthetic
method, emits the body with parameter unpacking, then restores `mg`.

Parameter unpacking from `Object[]`:
```java
mg.loadArg(0);                              // ALOAD 0 — the Object[] args
mg.push(i);                                 // BIPUSH i
mg.arrayLoad(Type.getType(Object.class));   // AALOAD — args[i] as Object
unboxIfPrimitive(declaredType);             // cast/unbox to declared type
mg.visitVarInsn(storeOpcode, slot);         // xSTORE into local
```

Return: the body's result is **boxed** before `ARETURN`:
```java
emitExpr(bodyExpr);
boxIfPrimitive(inferredType(bodyExpr));
mg.visitInsn(ARETURN);
```

### invokedynamic instruction

```java
Handle bootstrap = new Handle(
    H_INVOKESTATIC,
    "java/lang/invoke/LambdaMetafactory",
    "metafactory",
    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
    + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
    + "Ljava/lang/invoke/CallSite;",
    false);

Handle implHandle = new Handle(
    H_INVOKESTATIC, className, lambdaMethodName, lambdaDesc, false);

mg.visitInvokeDynamicInsn(
    "call",                                          // SAM method name
    "()L" + MLAMBDA_CLASS + ";",                     // factory descriptor (no captures)
    bootstrap,                                       // bootstrap method handle
    Type.getMethodType(MLAMBDA_SAM_DESC),            // erased SAM type
    implHandle,                                      // implementation method handle
    Type.getMethodType(lambdaDesc));                 // instantiated method type
```

`LambdaMetafactory.metafactory` is the standard JVM bootstrap method — the same one
Java's own lambda expressions use. On first execution it generates an anonymous class
implementing `MLambda`; subsequent calls reuse the cached instance.

### Lambda call site

```java
mg.visitVarInsn(ALOAD, localSlots.get(lambdaName));   // load lambda variable
mg.checkCast(Type.getObjectType(MLAMBDA_CLASS));       // CHECKCAST MLambda

// Pack arguments into Object[]
mg.push(argCount);
mg.visitTypeInsn(ANEWARRAY, "java/lang/Object");       // ANEWARRAY
for (int i = 0; i < args.size(); i++) {
    mg.dup();                                          // DUP ref
    mg.push(i);                                        // index
    emitExpr(args.get(i));
    boxIfPrimitive(argType);
    mg.visitInsn(AASTORE);                             // AASTORE
}

mg.invokeInterface(
    Type.getObjectType(MLAMBDA_CLASS),
    new Method("call", "([Ljava/lang/Object;)Ljava/lang/Object;"));

unboxIfPrimitive(declaredReturnType);                 // narrow Object back to T
```

---

## 18. Async Functions and CompletableFuture

### Synthetic body method

`async fn f(a: Int): Str` compiles its body into:

```java
new GeneratorAdapter(
    ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC,
    new Method("__async$f", "(I)Ljava/lang/Object;"),
    null, null, cw);
```

The return descriptor is always `Object` (erased) so the body can be passed to
`Supplier.get()`.

### Zero-parameter wrapper

For a parameterless async fn, a zero-capture `Supplier` is wired via `invokedynamic`:

```java
mg.visitInvokeDynamicInsn(
    "get",
    "()Ljava/util/function/Supplier;",
    bootstrapHandle,
    Type.getMethodType("()Ljava/lang/Object;"),   // erased Supplier.get type
    implHandle,
    Type.getMethodType("()Ljava/lang/Object;"));  // instantiated type

mg.invokeStatic(CompletableFuture, "supplyAsync",
    "(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;");
mg.visitInsn(ARETURN);
```

### Parameterised wrapper

For `async fn f(a: Int, b: Str)`, a **capturing lambda** is generated — a secondary
synthetic method `lambda$N(I, Ljava/lang/String;)Object` that forwards to
`__async$f`. The `invokedynamic` factory descriptor includes the parameter types as
captured arguments:

```
invokedynamic get (ILjava/lang/String;)Ljava/util/function/Supplier;
```

The JVM fills in the captured values at the factory call site, producing a `Supplier`
that holds `a` and `b` and will forward them to `__async$f` when `get()` is called.

### `async fn main()` — the entry point special case

```java
// Emits: public static void main(String[])V
mg = new GeneratorAdapter(ACC_PUBLIC | ACC_STATIC,
    new Method("main", "([Ljava/lang/String;)V"), null, null, cw);

// Create Supplier wrapping __async$main
emitSupplierForAsyncBody("__async$main", "()Ljava/lang/Object;");

// supplyAsync(supplier)
mg.invokeStatic(CompletableFuture, "supplyAsync", ...);

// .join() — blocks the main thread until async body completes
mg.invokeVirtual(CompletableFuture, new Method("join", "()Ljava/lang/Object;"));
mg.pop();           // discard null result (async main returns Unit)
mg.visitInsn(RETURN);
```

### `await expr`

```java
emitExpr(futureExpr);
mg.checkCast(Type.getObjectType("java/util/concurrent/CompletableFuture"));
mg.invokeVirtual(
    Type.getObjectType("java/util/concurrent/CompletableFuture"),
    new Method("join", "()Ljava/lang/Object;"));
// result is Object on stack — unboxIfPrimitive(declaredType) follows at call site
```

---

## 19. Virtual Threads — spawn_vthread

### Runnable generation

Each `spawn_vthread` generates a synthetic `()V` static method (the Runnable body):

```java
new GeneratorAdapter(
    ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC,
    new Method("lambda$N", "()V"),
    null, null, cw);
// ... emit target body ...
mg.visitInsn(RETURN);
```

### invokedynamic → Runnable

```java
mg.visitInvokeDynamicInsn(
    "run",
    "()Ljava/lang/Runnable;",
    bootstrapHandle,
    Type.getMethodType("()V"),       // erased Runnable.run type
    implHandle,
    Type.getMethodType("()V"));      // instantiated type
```

### Thread.ofVirtual().start(runnable)

```java
mg.invokeStatic(Thread.class, new Method("ofVirtual",
    "()Ljava/lang/Thread$Builder$OfVirtual;"));

mg.swap();   // stack was [Runnable, OfVirtual] → swap to [OfVirtual, Runnable]

mg.invokeInterface(
    Type.getObjectType("java/lang/Thread$Builder$OfVirtual"),
    new Method("start", "(Ljava/lang/Runnable;)Ljava/lang/Thread;"));
// Stack: [Thread]
```

### Collecting threads for structured concurrency

```java
mg.visitVarInsn(ALOAD, spawnSlot);     // load ArrayList<Thread>
mg.swap();                             // [Thread, List] → [List, Thread]
mg.invokeInterface(Type.getType(java.util.List.class),
    new Method("add", "(Ljava/lang/Object;)Z"));
mg.pop();                              // discard boolean result from add()
```

At every function return point:

```java
mg.visitVarInsn(ALOAD, spawnSlot);
mg.invokeStatic(MRuntime, new Method("joinAll", "(Ljava/util/List;)V"));
```

---

## 20. Type Widening for Math.pow

`**` always calls `Math.pow(double, double)`. Operands of other numeric types are
widened using JVM conversion opcodes:

```java
private void toDouble(String type) {
    switch (type) {
        case "Int"   -> mg.visitInsn(I2D);   // int    → double
        case "Long"  -> mg.visitInsn(L2D);   // long   → double
        case "Float" -> mg.visitInsn(F2D);   // float  → double
        // Double: no-op — already double
    }
}
```

These are single-instruction widening conversions with no data loss for the integer
types, and a precision trade-off for `Float`.

---

## 21. Class File Output

After all methods are emitted:

```java
cw.visitEnd();
byte[] bytecode = cw.toByteArray();
Path outPath = Path.of(outputDir, className + ".class");
Files.createDirectories(outPath.getParent());
Files.write(outPath, bytecode);
```

`cw.toByteArray()` triggers ASM's final pass: it resolves all label forward references,
computes max stack and locals (`COMPUTE_MAXS`), and assembles the complete stack map
frames (`COMPUTE_FRAMES`) into the binary bytecode array conforming to the Java class
file format (JVMS §4).

---

## 22. Why COMPUTE_FRAMES Matters

The JVM verifier (since Java 7) requires **StackMapTable** attributes in every method
body. These table entries describe the exact types of every local variable and operand
stack slot at every branch target and exception handler entry point.

Computing these manually for a language with exception handling, loops, nested lambdas,
and conditional branches would be extremely complex and error-prone. `COMPUTE_FRAMES`
delegates this entirely to ASM, which performs a data-flow analysis over the emitted
instructions and generates correct frames automatically.

The trade-off: `COMPUTE_FRAMES` requires ASM to resolve all class hierarchies it
encounters during frame computation. For most common types (`java.lang.String`,
`java.lang.Object`, `Number`) this works without a class loader. For hierarchy
resolution of custom types, ASM would need a class loader reference — but MLang avoids
this by typing most values as `Object` / `Any` when the exact type is not statically
known.

`COMPUTE_MAXS` similarly computes the maximum operand stack depth and maximum local
variable count, so `mg.visitMaxs(0, 0)` (which would otherwise be wrong) is never
needed — ASM fills in the correct values.
