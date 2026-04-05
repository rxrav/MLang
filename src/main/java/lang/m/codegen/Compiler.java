package lang.m.codegen;

import lang.m.parser.ast.*;
import lang.m.runtime.MRuntime;
import lang.m.semantic.Scope;
import lang.m.semantic.SemanticAnalyzer;
import lang.m.semantic.Symbol;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.objectweb.asm.Opcodes.*;

/**
 * Walks the semantically-analysed AST and emits JVM bytecode using the
 * <a href="https://asm.ow2.io/">ASM 9.7</a> library.
 *
 * <p>Each {@code .m} source file produces one {@code .class} file targeting
 * Java 21 (class file version 65.0). The class is named after the
 * {@code module} declaration, or derived from the source filename when
 * no module is declared.
 *
 * <h2>Code-generation strategy</h2>
 * <ul>
 *   <li>Top-level {@code fn} → {@code public static} methods.</li>
 *   <li>{@code fn main()} → {@code public static void main(String[])}.</li>
 *   <li>{@code async fn f()} → two methods: a synthetic {@code __async$f()Object}
 *       body + a {@code f()CompletableFuture} wrapper via
 *       {@code CompletableFuture.supplyAsync()}.</li>
 *   <li>{@code async fn main()} → {@code public static void main(String[])} that
 *       calls {@code supplyAsync(() -> __async$main()).join()} — the async boundary
 *       analogous to Python's {@code asyncio.run()}.</li>
 *   <li>Primitives kept as JVM primitives throughout; reference types use
 *       {@code java/lang/Object} when the exact type is unknown ({@code Any}).</li>
 *   <li>{@code print}/{@code panic}/{@code exit} → {@code invokestatic MRuntime.*}.</li>
 *   <li>{@code spawn_vthread} → {@code invokedynamic} Runnable +
 *       {@code Thread.ofVirtual().start(runnable)}; all spawned threads are joined
 *       at function exit via {@code MRuntime.joinAll(List&lt;Thread&gt;)}.</li>
 * </ul>
 */
public class Compiler {

    // ── Constants ────────────────────────────────────────────────────────────

    /** JVM class-file version for Java 21. */
    private static final int CLASS_VERSION = V21;

    private static final String RUNTIME_CLASS =
        MRuntime.class.getName().replace('.', '/');   // "lang/m/runtime/MRuntime"

    private static final String OBJECT_CLASS = "java/lang/Object";
    private static final String STRING_CLASS = "java/lang/String";
    private static final String STRING_BUILDER_CLASS = "java/lang/StringBuilder";
    private static final String COMPLETABLE_FUTURE_CLASS =
        CompletableFuture.class.getName().replace('.', '/');

    /** Internal name of the {@link MRuntime.MLambda} functional interface. */
    private static final String MLAMBDA_CLASS =
        MRuntime.class.getName().replace('.', '/') + "$MLambda";
    /** SAM descriptor for {@link MRuntime.MLambda#call}. */
    private static final String MLAMBDA_SAM_DESC = "([Ljava/lang/Object;)Ljava/lang/Object;";

    // ── State ────────────────────────────────────────────────────────────────

    private final String outputDir;

    // Per-class state (reset for each compiled file)
    private ClassWriter          cw;
    private String               className;       // slash-separated, e.g. "Hello"
    private Scope                globalScope;
    private Map<String, String>  fnDescriptors;   // fn name → JVM descriptor
    private int                  lambdaCounter;   // synthetic lambda method index

    // Per-method state (reset for each generated method body)
    private GeneratorAdapter         mg;
    private Map<String, Integer>     localSlots; // name → JVM slot index
    private Map<String, String>      localTypes; // name → M type ("Int", "Str", …)
    private int                      nextLocal;  // next available JVM slot
    /** JVM slot of the {@code ArrayList<Thread>} used to collect spawn_vthread threads,
     *  or {@code -1} when the current function contains no {@code spawn_vthread} stmts. */
    private int                      spawnSlot = -1;
    /** Maps local lambda variable names to their declared return M type.
     *  Populated in {@code emitLetVar} when the initializer is a {@link LambdaNode}.
     *  Reset at the start of each top-level function. */
    private Map<String, String>      lambdaReturnTypes = new HashMap<>();

    /**
     * @param outputDir directory where {@code .class} files will be written
     */
    public Compiler(String outputDir) {
        this.outputDir = outputDir;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Compile {@code program} to a {@code .class} file in the configured output
     * directory.
     *
     * @param program the typed, scope-resolved AST root
     * @param scope   the global {@link Scope} produced by {@link SemanticAnalyzer}
     * @throws IOException if the class file cannot be written
     */
    public void compile(ProgramNode program, Scope scope) throws IOException {
        this.globalScope = scope;

        // Determine class name from module declaration
        className = resolveClassName(program);

        // Create class writer (COMPUTE_FRAMES lets ASM compute stack maps for us)
        cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(CLASS_VERSION, ACC_PUBLIC | ACC_SUPER, className, null,
            OBJECT_CLASS, null);
        cw.visitSource(className + ".m", null);

        // Collect top-level functions (pass 1 — needed for forward calls)
        List<FnNode> fns = new ArrayList<>();
        for (Node decl : program.declarations()) {
            switch (decl) {
                case FnNode fn -> fns.add(fn);
                case AsyncFnNode b -> fns.add(b.fn());
                default -> { /* module/import let stubs — skip */ }
            }
        }

        // Emit a default no-arg constructor (required by the JVM spec)
        emitDefaultConstructor();

        lambdaCounter = 0;

        // Pre-compute function descriptors so forward calls resolve correctly.
        // async fn descriptors use CompletableFuture as return type.
        fnDescriptors = new HashMap<>();
        for (FnNode fn : fns) {
            if ("main".equals(fn.name()) && fn.params().isEmpty()) {
                // Both sync and async main() use the JVM entry-point descriptor
                fnDescriptors.put(fn.name(), "([Ljava/lang/String;)V");
            } else if (fn.isAsync()) {
                String paramDesc = buildParamDescriptor(fn);
                fnDescriptors.put(fn.name(),
                    paramDesc + "Ljava/util/concurrent/CompletableFuture;");
            } else {
                fnDescriptors.put(fn.name(), buildDescriptor(fn));
            }
        }

        // Emit all functions
        for (FnNode fn : fns) {
            if (fn.isAsync()) emitAsyncFunction(fn);
            else              emitFunction(fn);
        }

        cw.visitEnd();

        // Write .class file
        byte[] bytecode = cw.toByteArray();
        Path outPath = Path.of(outputDir, className + ".class");
        Files.createDirectories(outPath.getParent());
        Files.write(outPath, bytecode);
    }

    // ── Class-level helpers ──────────────────────────────────────────────────

    /** Determine the JVM class name from a module declaration, or fall back to "Main". */
    private static String resolveClassName(ProgramNode program) {
        for (Node decl : program.declarations()) {
            if (decl instanceof LetNode l && "__module__".equals(l.name())) {
                // Convert dotted module name to slash-separated class name
                // Use the last segment as the class name (e.g. com.example.app → app)
                String mod = ((LiteralNode) l.initializer()).value().toString();
                // Use full dotted path → slashed: com.example → com/example
                return mod.replace('.', '/');
            }
        }
        return "Main";
    }

    /** Emit a {@code public <init>()V} default constructor. */
    private void emitDefaultConstructor() {
        GeneratorAdapter init = new GeneratorAdapter(ACC_PUBLIC, Method.getMethod("void <init>()"), null, null, cw);
        init.visitCode();
        init.loadThis();
        init.invokeConstructor(Type.getType(Object.class), Method.getMethod("void <init>()"));
        init.returnValue();
        init.endMethod();
    }

    // ── Function emission ────────────────────────────────────────────────────

    /**
     * Emit an {@code async fn} as either two or three JVM methods depending on
     * whether this is the program entry point:
     *
     * <p><b>Regular {@code async fn f()}:</b>
     * <ol>
     *   <li>A synthetic {@code __async$f} method containing the body,
     *       returning {@code Object} (fits {@code Supplier.get()}).</li>
     *   <li>A public {@code f()CompletableFuture} wrapper that calls
     *       {@code CompletableFuture.supplyAsync(() -> __async$f(args))}.</li>
     * </ol>
     *
     * <p><b>{@code async fn main()}:</b>
     * <ol>
     *   <li>Same synthetic {@code __async$main} body method.</li>
     *   <li>A {@code public static void main(String[])} JVM entry point that calls
     *       {@code supplyAsync(() -> __async$main()).join()} — analogous to
     *       {@code asyncio.run()} in Python or {@code runBlocking} in Kotlin.</li>
     * </ol>
     */
    private void emitAsyncFunction(FnNode fn) {
        // ── 1. Emit synthetic body method ──────────────────────────────────
        // Descriptor: same params, but returns Object (erased for Supplier)
        String bodyName = "__async$" + fn.name();
        String paramDesc = buildParamDescriptor(fn);
        String bodyDesc = paramDesc + "Ljava/lang/Object;";

        GeneratorAdapter savedMg    = mg;
        Map<String,Integer> savedSlots = localSlots;
        Map<String,String>  savedTypes = localTypes;
        Map<String,String>  savedLambdaRetTypes = lambdaReturnTypes;
        int savedNext = nextLocal;
        int savedSpawnSlot = spawnSlot;

        mg = new GeneratorAdapter(
            ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC,
            new Method(bodyName, bodyDesc),
            null, null, cw);
        mg.visitCode();
        localSlots = new HashMap<>();
        localTypes  = new HashMap<>();
        lambdaReturnTypes = new HashMap<>();
        nextLocal   = 0;
        spawnSlot  = -1;

        int slot = 0;
        for (ParamNode p : fn.params()) {
            String mType = p.type() != null ? p.type() : "Any";
            localSlots.put(p.name(), slot);
            localTypes.put(p.name(), mType);
            slot += ("Long".equals(mType) || "Double".equals(mType)) ? 2 : 1;
        }
        nextLocal = slot;

        boolean hasSpawnStmts = containsSpawn(fn.body().statements());
        if (hasSpawnStmts) {
            spawnSlot = nextLocal++;
            mg.newInstance(Type.getType(java.util.ArrayList.class));
            mg.dup();
            mg.invokeConstructor(Type.getType(java.util.ArrayList.class),
                Method.getMethod("void <init>()"));
            mg.visitVarInsn(ASTORE, spawnSlot);
        }
        for (Node stmt : fn.body().statements()) emitStatement(stmt);
        emitSpawnJoinAll();
        mg.visitInsn(ACONST_NULL); // unit return
        mg.visitInsn(ARETURN);
        mg.endMethod();

        mg         = savedMg;
        localSlots = savedSlots;
        localTypes = savedTypes;
        lambdaReturnTypes = savedLambdaRetTypes;
        nextLocal   = savedNext;
        spawnSlot  = savedSpawnSlot;

        // ── 2. Emit public wrapper method ─────────────────────────────────
        //
        // For async fn main(): emit the JVM entry point main([Ljava/lang/String;)V
        //   that calls supplyAsync(() -> __async$main()).join() then returns void.
        //
        // For any other async fn: emit the CompletableFuture-returning wrapper
        //   as before.
        boolean isAsyncMain = "main".equals(fn.name()) && fn.params().isEmpty();

        if (isAsyncMain) {
            mg = new GeneratorAdapter(ACC_PUBLIC | ACC_STATIC,
                new Method("main", "([Ljava/lang/String;)V"), null, null, cw);
            mg.visitCode();
            localSlots = new HashMap<>();
            localTypes  = new HashMap<>();
            nextLocal   = 1; // slot 0 = String[] args, not exposed to M code

            emitSupplierForAsyncBody(bodyName, bodyDesc);
            mg.invokeStatic(
                Type.getObjectType(COMPLETABLE_FUTURE_CLASS),
                new Method("supplyAsync",
                    "(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;"));
            mg.invokeVirtual(
                Type.getObjectType(COMPLETABLE_FUTURE_CLASS),
                new Method("join", "()Ljava/lang/Object;"));
            mg.pop(); // discard null body result
            mg.visitInsn(RETURN);
            mg.endMethod();
        } else {
            String wrapDesc = paramDesc + "Ljava/util/concurrent/CompletableFuture;";
            mg = new GeneratorAdapter(ACC_PUBLIC | ACC_STATIC,
                new Method(fn.name(), wrapDesc), null, null, cw);
            mg.visitCode();
            localSlots = new HashMap<>();
            localTypes  = new HashMap<>();
            nextLocal   = 0;
            slot = 0;
            for (ParamNode p : fn.params()) {
                String mType = p.type() != null ? p.type() : "Any";
                localSlots.put(p.name(), slot);
                localTypes.put(p.name(), mType);
                slot += ("Long".equals(mType) || "Double".equals(mType)) ? 2 : 1;
            }
            nextLocal = slot;

            // Build a Supplier that forwards all args to the body method.
            if (fn.params().isEmpty()) {
                emitSupplierForAsyncBody(bodyName, bodyDesc);
            } else {
                emitCapturingSupplierForAsyncBody(fn, bodyName, bodyDesc);
            }

            mg.invokeStatic(
                Type.getObjectType(COMPLETABLE_FUTURE_CLASS),
                new Method("supplyAsync",
                    "(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;"));
            mg.visitInsn(ARETURN);
            mg.endMethod();
        }

        mg         = savedMg;
        localSlots = savedSlots;
        localTypes = savedTypes;
        nextLocal   = savedNext;
    }

    /** Emit an invokedynamic that creates {@code Supplier<Object>} backed by {@code bodyName()}. */
    private void emitSupplierForAsyncBody(String bodyName, String bodyDesc) {
        Handle bootstrap = lambdaMetafactoryHandle();
        Handle implHandle = new Handle(H_INVOKESTATIC, className, bodyName, bodyDesc, false);
        mg.visitInvokeDynamicInsn(
            "get", "()Ljava/util/function/Supplier;",
            bootstrap,
            Type.getMethodType("()Ljava/lang/Object;"),
            implHandle,
            Type.getMethodType("()Ljava/lang/Object;"));
    }

    /**
     * For an async fn with parameters, emit a capturing lambda:
     * a synthetic {@code lambda$N(p1, p2, ...) -> Object} method that
     * forwards to the async body, then wire it to {@code Supplier} via
     * invokedynamic, capturing the current param values off the stack.
     */
    private void emitCapturingSupplierForAsyncBody(FnNode fn, String bodyName, String bodyDesc) {
        String capName = "lambda$" + (lambdaCounter++);
        // Descriptor for the capture lambda: (P1 P2 ...) -> Object
        String capDesc = bodyDesc; // same params, Object return

        // Push all params onto the stack (they are already in JVM slots)
        for (ParamNode p : fn.params()) {
            String mType = p.type() != null ? p.type() : "Any";
            mg.visitVarInsn(TypeMap.loadOpcode(mType),
                localSlots.get(p.name()));
        }

        // Emit synthetic capture lambda body
        GeneratorAdapter savedMg    = mg;
        Map<String,Integer> savedSlots = localSlots;
        Map<String,String>  savedTypes = localTypes;
        int savedNext = nextLocal;

        mg = new GeneratorAdapter(
            ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC,
            new Method(capName, capDesc),
            null, null, cw);
        mg.visitCode();
        localSlots = new HashMap<>();
        localTypes  = new HashMap<>();
        nextLocal   = 0;
        int slot = 0;
        for (ParamNode p : fn.params()) {
            String mType = p.type() != null ? p.type() : "Any";
            localSlots.put(p.name(), slot);
            localTypes.put(p.name(), mType);
            slot += ("Long".equals(mType) || "Double".equals(mType)) ? 2 : 1;
        }
        nextLocal = slot;
        // Load each param and forward to actual body
        for (ParamNode p : fn.params()) {
            String mType = p.type() != null ? p.type() : "Any";
            mg.visitVarInsn(TypeMap.loadOpcode(mType), localSlots.get(p.name()));
        }
        mg.visitMethodInsn(INVOKESTATIC, className, bodyName, bodyDesc, false);
        mg.visitInsn(ARETURN);
        mg.endMethod();

        mg         = savedMg;
        localSlots = savedSlots;
        localTypes = savedTypes;
        nextLocal   = savedNext;

        // invokedynamic: capture the params already on stack, wire to Supplier
        // Captured-param type string for invokedynamic factory site descriptor
        String factoryDesc = buildParamDescriptor(fn) + "Ljava/util/function/Supplier;";
        Handle bootstrap = lambdaMetafactoryHandle();
        Handle implHandle = new Handle(H_INVOKESTATIC, className, capName, capDesc, false);
        mg.visitInvokeDynamicInsn(
            "get", factoryDesc,
            bootstrap,
            Type.getMethodType("()Ljava/lang/Object;"),
            implHandle,
            Type.getMethodType("()Ljava/lang/Object;"));
    }

    private static Handle lambdaMetafactoryHandle() {
        return new Handle(
            H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory",
            "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
            + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
            + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
            + "Ljava/lang/invoke/CallSite;",
            false);
    }

    /**
     * Emit a single function as a {@code public static} JVM method.
     * {@code fn main()} with no parameters maps to {@code main(String[])}.
     */
    private void emitFunction(FnNode fn) {
        boolean isMain = "main".equals(fn.name()) && fn.params().isEmpty();
        String descriptor = isMain ? "([Ljava/lang/String;)V" : buildDescriptor(fn);
        int access = ACC_PUBLIC | ACC_STATIC;

        mg = new GeneratorAdapter(access, new Method(fn.name(), descriptor), null, null, cw);
        mg.visitCode();

        // Set up local variable tracking
        localSlots = new HashMap<>();
        localTypes  = new HashMap<>();
        lambdaReturnTypes = new HashMap<>();
        nextLocal   = 0;

        if (isMain) {
            // main(String[] args) — args is at JVM slot 0, not exposed to M code
            nextLocal = 1;
        } else {
            // Register params at their JVM slots (Long/Double occupy 2 slots each)
            int slot = 0;
            for (ParamNode p : fn.params()) {
                String mType = p.type() != null ? p.type() : "Any";
                localSlots.put(p.name(), slot);
                localTypes.put(p.name(), mType);
                slot += ("Long".equals(mType) || "Double".equals(mType)) ? 2 : 1;
            }
            nextLocal = slot;
        }

        // Emit body statements
        boolean hasSpawnStmts = containsSpawn(fn.body().statements());
        int savedSpawnSlot = spawnSlot;
        spawnSlot = -1;
        if (hasSpawnStmts) {
            // Allocate a local ArrayList<Thread> to collect spawned threads
            spawnSlot = nextLocal++;
            mg.newInstance(Type.getType(java.util.ArrayList.class));
            mg.dup();
            mg.invokeConstructor(Type.getType(java.util.ArrayList.class),
                Method.getMethod("void <init>()"));
            mg.visitVarInsn(ASTORE, spawnSlot);
        }
        for (Node stmt : fn.body().statements()) {
            emitStatement(stmt);
        }

        // Join spawn_vthread threads before the implicit return
        emitSpawnJoinAll();
        spawnSlot = savedSpawnSlot;

        // Terminate method — unreachable after explicit return, required by ASM
        String retType = fn.returnType();
        if (retType == null || "void".equals(retType) || "Unit".equals(retType) || isMain) {
            mg.visitInsn(RETURN);
        } else {
            switch (retType) {
                case "Int", "Bool" -> { mg.push(0);    mg.visitInsn(IRETURN); }
                case "Long"        -> { mg.push(0L);   mg.visitInsn(LRETURN); }
                case "Float"       -> { mg.push(0.0f); mg.visitInsn(FRETURN); }
                case "Double"      -> { mg.push(0.0);  mg.visitInsn(DRETURN); }
                default            -> { mg.visitInsn(ACONST_NULL); mg.visitInsn(ARETURN); }
            }
        }

        mg.endMethod();
    }

    /**
     * Build a JVM method descriptor from an M {@link FnNode}.
     * Unknown / {@code Any} types map to {@code Ljava/lang/Object;}.
     */
    private static String buildDescriptor(FnNode fn) {
        return buildParamDescriptor(fn) + mTypeToDescriptor(fn.returnType());
    }

    /** Build only the parameter portion of a JVM descriptor, e.g. {@code "(ILjava/lang/Object;)"}. */
    private static String buildParamDescriptor(FnNode fn) {
        StringBuilder sb = new StringBuilder("(");
        for (ParamNode p : fn.params()) {
            sb.append(mTypeToDescriptor(p.type()));
        }
        sb.append(')');
        return sb.toString();
    }

    /** Map an M type name to a JVM type descriptor. */
    static String mTypeToDescriptor(String mType) {
        if (mType == null) return "V";
        String d = TypeMap.DESCRIPTORS.get(mType);
        if (d != null) return d;
        // Generic or unknown: use Object
        if (mType.startsWith("Future<") || mType.startsWith("List<")
                || mType.startsWith("Map<") || mType.startsWith("Set<")) {
            return "Ljava/lang/Object;";
        }
        return "Ljava/lang/Object;";
    }

    // ── Statement emitters ───────────────────────────────────────────────────

    private void emitStatement(Node node) {
        switch (node) {
            case LetNode l  -> emitLetVar(l.name(), l.type(), l.initializer(), false);
            case VarNode v  -> emitLetVar(v.name(), v.type(), v.initializer(), true);
            case AssignNode a -> {
                emitExpr(a.value());
                Integer slot = localSlots.get(a.name());
                if (slot != null) {
                    String mType = localTypes.getOrDefault(a.name(), "Any");
                    mg.visitVarInsn(TypeMap.storeOpcode(mType), slot);
                }
            }
            case ReturnNode r -> {
                if (r.value() != null) {
                    emitExpr(r.value());
                    if (spawnSlot >= 0) {
                        // Value is on stack — stash it, join threads, reload
                        String valType = inferredType(r.value());
                        int tmpSlot = nextLocal++;
                        mg.visitVarInsn(TypeMap.storeOpcode(valType), tmpSlot);
                        emitSpawnJoinAll();
                        mg.visitVarInsn(TypeMap.loadOpcode(valType), tmpSlot);
                    }
                } else {
                    emitSpawnJoinAll();
                }
                mg.returnValue();
            }
            case IfNode i    -> emitIf(i);
            case ForNode f   -> emitFor(f);
            case SwitchNode s -> emitSwitch(s);
            case TryNode t   -> emitTry(t);
            case ThrowNode b  -> emitThrow(b);
            case PrintNode b  -> emitPrint(b.value());
            case PanicNode b -> emitPanic(b.message());
            case ExitNode c   -> emitExit(c.code());
            case SpawnVthreadNode m -> emitSpawnVthread(m);
            case BlockNode bl -> { for (Node s : bl.statements()) emitStatement(s); }
            case FnNode fn         -> { if (fn.isAsync()) emitAsyncFunction(fn); else emitFunction(fn); }
            case AsyncFnNode bee   -> { if (bee.fn().isAsync()) emitAsyncFunction(bee.fn()); else emitFunction(bee.fn()); }
            // Expression statements (e.g. a bare call whose return value is discarded)
            default -> {
                emitExpr(node);
                String t = inferredType(node);
                if ("Long".equals(t) || "Double".equals(t)) mg.pop2();
                else mg.pop();
            }
        }
    }

    private void emitLetVar(String name, String declaredType, Node init, boolean mutable) {
        String mType = declaredType != null ? declaredType : inferredType(init);
        emitExpr(init);
        int slot = nextLocal;
        nextLocal += ("Long".equals(mType) || "Double".equals(mType)) ? 2 : 1;
        localSlots.put(name, slot);
        localTypes.put(name, mType);
        mg.visitVarInsn(TypeMap.storeOpcode(mType), slot);
        // Track lambda return type so call-sites can infer the result type
        if (init instanceof LambdaNode lam && lam.returnType() != null) {
            lambdaReturnTypes.put(name, lam.returnType());
        }
    }

    // ── Control flow ─────────────────────────────────────────────────────────

    private void emitIf(IfNode node) {
        emitExpr(node.condition());
        ensureBoolean(node.condition());

        Label elseLabel = mg.newLabel();
        Label endLabel  = mg.newLabel();

        mg.ifZCmp(GeneratorAdapter.EQ, elseLabel); // branch if condition == 0 (false)
        emitBlock(node.then());

        if (node.otherwise() != null) {
            mg.goTo(endLabel);
            mg.mark(elseLabel);
            emitStatement(node.otherwise());
            mg.mark(endLabel);
        } else {
            mg.mark(elseLabel);
        }
    }

    private void emitFor(ForNode node) {
        // Evaluate iterable; handle int range (BinaryNode with ".." or "..<")
        if (node.iterable() instanceof BinaryNode b
                && (b.op().equals("..") || b.op().equals("..<"))) {
            emitIntRangeFor(node.variable(), b, node.body());
        } else {
            // Fallback: iterable is any expression — emit as for-each using Iterator
            emitIterableFor(node.variable(), node.iterable(), node.body());
        }
    }

    /** for i in 1..10  or  for i in 0..<n — classical int counter loop */
    private void emitIntRangeFor(String varName, BinaryNode range, BlockNode body) {
        int startSlot = nextLocal++;
        int endSlot   = nextLocal++;

        emitExpr(range.left());
        mg.visitVarInsn(ISTORE, startSlot);
        localSlots.put(varName, startSlot);
        localTypes.put(varName, "Int");

        emitExpr(range.right());
        mg.visitVarInsn(ISTORE, endSlot);

        Label loopStart = mg.newLabel();
        Label loopEnd   = mg.newLabel();

        mg.mark(loopStart);
        mg.visitVarInsn(ILOAD, startSlot);
        mg.visitVarInsn(ILOAD, endSlot);

        // "..<" is exclusive (exit when i >= end), ".." is inclusive (exit when i > end)
        int cmpOp = range.op().equals("..<") ? GeneratorAdapter.GE : GeneratorAdapter.GT;
        mg.ifICmp(cmpOp, loopEnd);

        for (Node stmt : body.statements()) emitStatement(stmt);

        mg.visitIincInsn(startSlot, 1);
        mg.goTo(loopStart);
        mg.mark(loopEnd);
    }

    /** for item in list — uses Java Iterator */
    private void emitIterableFor(String varName, Node iterable, BlockNode body) {
        emitExpr(iterable);
        mg.checkCast(Type.getType("Ljava/lang/Iterable;"));
        mg.invokeInterface(Type.getType("Ljava/lang/Iterable;"),
            new Method("iterator", "()Ljava/util/Iterator;"));
        int iterSlot = nextLocal++;
        mg.visitVarInsn(ASTORE, iterSlot);

        int elemSlot = nextLocal++;
        localSlots.put(varName, elemSlot);
        localTypes.put(varName, "Any");

        Label loopStart = mg.newLabel();
        Label loopEnd   = mg.newLabel();

        mg.mark(loopStart);
        mg.visitVarInsn(ALOAD, iterSlot);
        mg.invokeInterface(Type.getType("Ljava/util/Iterator;"),
            new Method("hasNext", "()Z"));
        mg.ifZCmp(GeneratorAdapter.EQ, loopEnd);

        mg.visitVarInsn(ALOAD, iterSlot);
        mg.invokeInterface(Type.getType("Ljava/util/Iterator;"),
            new Method("next", "()Ljava/lang/Object;"));
        mg.visitVarInsn(ASTORE, elemSlot);

        for (Node stmt : body.statements()) emitStatement(stmt);
        mg.goTo(loopStart);
        mg.mark(loopEnd);
    }

    private void emitSwitch(SwitchNode node) {
        String subjectType = inferredType(node.subject());
        boolean isPrimInt  = Set.of("Int", "Bool", "Byte", "Char").contains(subjectType);

        // Evaluate subject once and stash it
        int subjectSlot = nextLocal++;
        emitExpr(node.subject());
        mg.visitVarInsn(TypeMap.storeOpcode(subjectType), subjectSlot);

        Label endLabel = mg.newLabel();

        for (CaseNode c : node.cases()) {
            Label nextCase = mg.newLabel();
            mg.visitVarInsn(TypeMap.loadOpcode(subjectType), subjectSlot);
            emitExpr(c.pattern());

            if (isPrimInt) {
                mg.ifICmp(GeneratorAdapter.NE, nextCase);
            } else {
                mg.invokeVirtual(Type.getType(Object.class),
                    Method.getMethod("boolean equals(Object)"));
                mg.ifZCmp(GeneratorAdapter.EQ, nextCase);
            }

            emitStatement(c.body());
            mg.goTo(endLabel);
            mg.mark(nextCase);
        }

        if (node.defaultBranch() != null) {
            emitStatement(node.defaultBranch());
        }

        mg.mark(endLabel);
    }

    private void emitTry(TryNode node) {
        Label tryStart = mg.newLabel();
        Label tryEnd   = mg.newLabel();
        Label afterAll = mg.newLabel();
        boolean hasFinalizer = node.finalizer() != null;

        mg.mark(tryStart);
        emitBlock(node.body());
        mg.mark(tryEnd);

        // Normal exit: inline finally then jump past handlers
        if (hasFinalizer) emitBlock(node.finalizer());
        mg.goTo(afterAll);

        // Typed catch handlers — each covers tryStart..tryEnd
        for (CatchNode h : node.handlers()) {
            String internalEx = resolveExType(h.exceptionType());
            mg.catchException(tryStart, tryEnd, Type.getObjectType(internalEx));
            // Exception is on the stack — store it in a new local
            int exSlot = nextLocal++;
            localSlots.put(h.varName(), exSlot);
            localTypes.put(h.varName(), "Any");
            mg.visitVarInsn(ASTORE, exSlot);
            emitBlock(h.body());
            if (hasFinalizer) emitBlock(node.finalizer());
            mg.goTo(afterAll);
        }

        // Catch-all handler: ensures finally runs for uncaught exceptions
        if (hasFinalizer) {
            mg.catchException(tryStart, tryEnd, null); // null → catch any Throwable
            int tmpSlot = nextLocal++;
            mg.visitVarInsn(ASTORE, tmpSlot);
            emitBlock(node.finalizer());
            mg.visitVarInsn(ALOAD, tmpSlot);
            mg.visitInsn(org.objectweb.asm.Opcodes.ATHROW);
        }

        mg.mark(afterAll);
    }

    /** Map a short exception name to a JVM internal name (e.g. RuntimeException → java/lang/RuntimeException). */
    private String resolveExType(String name) {
        if (name == null)           return "java/lang/Throwable";
        if (name.contains("."))     return name.replace('.', '/');
        if (name.contains("/"))     return name;
        return "java/lang/" + name; // assume java.lang for simple names
    }

    /**
     * Emit a throw statement.
     * If the exception expression is a constructor call like {@code RuntimeException("msg")},
     * emit {@code NEW / DUP / args / INVOKESPECIAL <init>}; otherwise just load the value.
     */
    private void emitThrow(ThrowNode b) {
        Node ex = b.exception();
        if (ex instanceof CallNode c
                && c.callee() instanceof IdentNode id
                && !id.name().isEmpty()
                && Character.isUpperCase(id.name().charAt(0))
                && !fnDescriptors.containsKey(id.name())) {
            // Treat as constructor call: new ExType(args)
            emitConstructorCall(id.name(), c.args());
        } else {
            emitExpr(ex);
        }
        mg.visitInsn(org.objectweb.asm.Opcodes.ATHROW);
    }

    /** Emit {@code new InternalName(String? arg)} — supports 0 or 1 String argument. */
    private void emitConstructorCall(String simpleName, java.util.List<Node> args) {
        String internalName = resolveExType(simpleName);
        Type exType = Type.getObjectType(internalName);
        mg.newInstance(exType);
        mg.dup();
        if (args.isEmpty()) {
            mg.invokeConstructor(exType, new Method("<init>", "()V"));
        } else {
            // Emit first arg as String; coerce to String if needed
            emitExpr(args.get(0));
            if (!"Str".equals(inferredType(args.get(0)))) {
                mg.invokeVirtual(Type.getType(Object.class),
                    new Method("toString", "()Ljava/lang/String;"));
            }
            mg.invokeConstructor(exType,
                new Method("<init>", "(Ljava/lang/String;)V"));
        }
    }

    // ── Built-in emitters ─────────────────────────────────────────────────────

    private void emitPrint(Node value) {
        // Pick the best print overload based on inferred type
        String type = inferredType(value);
        emitExpr(value);
        String descriptor = switch (type) {
            case "Int"  -> "(I)V";
            case "Long" -> "(J)V";
            case "Double", "Float" -> "(D)V";
            case "Bool" -> "(Z)V";
            default     -> "(Ljava/lang/Object;)V";
        };
        mg.invokeStatic(Type.getObjectType(RUNTIME_CLASS),
            new Method("print", descriptor));
    }

    private void emitPanic(Node msg) {
        emitExpr(msg);
        // Ensure String on stack
        if (!"Str".equals(inferredType(msg))) {
            mg.invokeVirtual(Type.getType(Object.class),
                new Method("toString", "()Ljava/lang/String;"));
        }
        mg.invokeStatic(Type.getObjectType(RUNTIME_CLASS),
            new Method("panic", "(Ljava/lang/String;)V"));
    }

    private void emitExit(Node code) {
        emitExpr(code);
        ensureInt(code);
        mg.invokeStatic(Type.getObjectType(RUNTIME_CLASS),
            new Method("exit", "(I)V"));
        mg.visitInsn(RETURN); // exit() calls System.exit — JVM still needs a RETURN after invokeStatic
    }

    /**
     * Emit {@code Thread.ofVirtual().start(runnable)} for a {@code spawn_vthread} statement.
     *
     * <p>The target (a call or lambda block) is wrapped in a {@code Runnable}
     * via {@code invokedynamic}.
     */
    /** Returns true if any statement in the list (deeply) is a {@link SpawnVthreadNode}.
     *  Does NOT descend into nested {@code fn} declarations (they track their own). */
    private static boolean containsSpawn(java.util.List<Node> stmts) {
        for (Node s : stmts) if (nodeHasSpawn(s)) return true;
        return false;
    }

    private static boolean nodeHasSpawn(Node node) {
        return switch (node) {
            case SpawnVthreadNode ignored  -> true;
            case BlockNode b              -> containsSpawn(b.statements());
            case IfNode i                 -> nodeHasSpawn(i.then())
                                            || (i.otherwise() != null && nodeHasSpawn(i.otherwise()));
            case ForNode f                -> nodeHasSpawn(f.body());
            case SwitchNode sw            -> {
                for (CaseNode c : sw.cases()) if (nodeHasSpawn(c.body())) yield true;
                yield sw.defaultBranch() != null && nodeHasSpawn(sw.defaultBranch());
            }
            case TryNode t                -> {
                if (nodeHasSpawn(t.body())) yield true;
                for (CatchNode h : t.handlers()) if (nodeHasSpawn(h.body())) yield true;
                yield t.finalizer() != null && nodeHasSpawn(t.finalizer());
            }
            default                       -> false;
        };
    }

    /** Emit {@code MRuntime.joinAll(spawnThreadList)} — waits for all spawned virtual threads. */
    private void emitSpawnJoinAll() {
        if (spawnSlot < 0) return;
        mg.visitVarInsn(ALOAD, spawnSlot);
        mg.invokeStatic(Type.getObjectType(RUNTIME_CLASS),
            new Method("joinAll", "(Ljava/util/List;)V"));
    }

    private void emitSpawnVthread(SpawnVthreadNode m) {
        String runnableName = "lambda$" + (lambdaCounter++);
        String runnableDesc = "()V";

        // ── Emit synthetic Runnable body -----------------------------------
        GeneratorAdapter savedMg    = mg;
        Map<String,Integer> savedSlots = localSlots;
        Map<String,String>  savedTypes = localTypes;
        int savedNext = nextLocal;
        int savedSpawnSlot = spawnSlot; // Runnable body has its own scope

        mg = new GeneratorAdapter(
            ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC,
            new Method(runnableName, runnableDesc),
            null, null, cw);
        mg.visitCode();
        localSlots = new HashMap<>(savedSlots); // capture current scope
        localTypes  = new HashMap<>(savedTypes);
        nextLocal   = savedNext;
        spawnSlot  = -1; // Runnable body never has its own spawn list

        Node target = m.target();
        if (target instanceof LambdaNode lam && lam.params().isEmpty()) {
            // spawn_vthread { block } — emit block body
            if (lam.body() instanceof BlockNode blk) {
                for (Node stmt : blk.statements()) emitStatement(stmt);
            } else {
                emitStatement(lam.body());
            }
        } else {
            // spawn_vthread fnCall(args) — emit as statement (handles void correctly)
            emitStatement(target);
        }
        mg.visitInsn(RETURN);
        mg.endMethod();

        mg         = savedMg;
        localSlots = savedSlots;
        localTypes = savedTypes;
        nextLocal   = savedNext;
        spawnSlot  = savedSpawnSlot;

        // ── invokedynamic: () -> Runnable backed by runnableName ------------
        Handle bootstrap = lambdaMetafactoryHandle();
        Handle implHandle = new Handle(H_INVOKESTATIC, className, runnableName, runnableDesc, false);
        mg.visitInvokeDynamicInsn(
            "run", "()Ljava/lang/Runnable;",
            bootstrap,
            Type.getMethodType("()V"),
            implHandle,
            Type.getMethodType("()V"));

        // Thread.ofVirtual().start(runnable)
        mg.invokeStatic(Type.getType(Thread.class),
            new Method("ofVirtual", "()Ljava/lang/Thread$Builder$OfVirtual;"));
        mg.swap();
        //   invokedynamic -> [Runnable]
        //   invokeStatic ofVirtual -> [Runnable, OfVirtual]
        //   swap -> [OfVirtual, Runnable]
        //   invokeInterface start(Runnable)Thread -> [Thread]
        mg.invokeInterface(
            Type.getObjectType("java/lang/Thread$Builder$OfVirtual"),
            new Method("start", "(Ljava/lang/Runnable;)Ljava/lang/Thread;"));
        // Stack: [Thread] — add to spawn list or discard
        if (spawnSlot >= 0) {
            mg.visitVarInsn(ALOAD, spawnSlot); // [Thread, List]
            mg.swap();                          // [List, Thread]
            mg.invokeInterface(Type.getType(java.util.List.class),
                new Method("add", "(Ljava/lang/Object;)Z"));
            mg.pop(); // discard boolean result from add()
        } else {
            mg.pop(); // discard Thread (fire-and-forget fallback)
        }
    }

    // ── Expression emitters ───────────────────────────────────────────────────

    /**
     * Emit bytecode that leaves one value on the operand stack representing
     * the result of {@code node}.
     */
    private void emitExpr(Node node) {
        switch (node) {
            case LiteralNode l -> emitLiteral(l);
            case IdentNode id -> {
                Integer slot = localSlots.get(id.name());
                if (slot != null) {
                    String mType = localTypes.getOrDefault(id.name(), "Any");
                    mg.visitVarInsn(TypeMap.loadOpcode(mType), slot);
                } else {
                    mg.visitInsn(ACONST_NULL);
                }
            }
            case BinaryNode b  -> emitBinary(b);
            case UnaryNode  u  -> emitUnary(u);
            case CallNode   c  -> emitCall(c);
            case PrintNode  b  -> { emitPrint(b.value());  mg.visitInsn(ACONST_NULL); } // returns void
            case PanicNode b  -> { emitPanic(b.message()); mg.visitInsn(ACONST_NULL); }
            case ExitNode   c  -> { emitExit(c.code());    mg.visitInsn(ACONST_NULL); }
            case BlockNode  bl -> { for (Node s : bl.statements()) emitStatement(s); mg.visitInsn(ACONST_NULL); }
            case LambdaNode  l -> emitLambda(l);
            case AwaitNode   h -> emitHm(h);
            default -> mg.visitInsn(ACONST_NULL); // safe placeholder for unimplemented nodes
        }
    }

    private void emitLiteral(LiteralNode l) {
        switch (l.kind()) {
            case "int"    -> mg.push((int)    l.value());
            case "long"   -> mg.push((long)   l.value());
            case "float"  -> mg.push((float)  l.value());
            case "double" -> mg.push((double) l.value());
            case "bool"   -> mg.push((boolean) l.value());
            case "string" -> mg.push((String)  l.value());
            case "null"   -> mg.visitInsn(ACONST_NULL);
            default       -> mg.visitInsn(ACONST_NULL);
        }
    }

    private void emitBinary(BinaryNode b) {
        switch (b.op()) {
            // String concat with + handled separately
            case "+" -> {
                if ("Str".equals(inferredType(b.left())) || "Str".equals(inferredType(b.right()))) {
                    emitStringConcat(b.left(), b.right());
                } else {
                    emitArithmetic(b.left(), GeneratorAdapter.ADD, b.right());
                }
            }
            case "-"  -> emitArithmetic(b.left(), GeneratorAdapter.SUB, b.right());
            case "*"  -> emitArithmetic(b.left(), GeneratorAdapter.MUL, b.right());
            case "/"  -> emitArithmetic(b.left(), GeneratorAdapter.DIV, b.right());
            case "%"  -> emitArithmetic(b.left(), GeneratorAdapter.REM, b.right());
            case "**" -> emitPow(b.left(), b.right());
            case "&&" -> emitLogicalAnd(b.left(), b.right());
            case "||" -> emitLogicalOr(b.left(), b.right());
            case "==", "!=" -> emitEqualityOp(b.left(), b.op(), b.right());
            case "==="      -> emitRefEquality(b.left(), b.right());
            case "<", "<=", ">", ">=" -> emitRelational(b.left(), b.op(), b.right());
            case "."  -> emitMemberAccess(b.left(), b.right());
            case "?." -> emitMemberAccess(b.left(), b.right()); // null-safe: simplified in Step 7
            case "..", "..<" -> emitRangeLiteral(b.left(), b.op(), b.right());
            default -> mg.visitInsn(ACONST_NULL);
        }
    }

    private void emitArithmetic(Node left, int op, Node right) {
        emitExpr(left);
        emitExpr(right);
        String type = inferredType(left);
        switch (type) {
            case "Long"   -> mg.math(op, Type.LONG_TYPE);
            case "Float"  -> mg.math(op, Type.FLOAT_TYPE);
            case "Double" -> mg.math(op, Type.DOUBLE_TYPE);
            default       -> mg.math(op, Type.INT_TYPE);
        }
    }

    private void emitPow(Node base, Node exp) {
        // Java.lang.Math.pow(double, double) → double
        emitExpr(base);
        toDouble(inferredType(base));
        emitExpr(exp);
        toDouble(inferredType(exp));
        mg.invokeStatic(Type.getType(Math.class),
            new Method("pow", "(DD)D"));
    }

    private void emitStringConcat(Node left, Node right) {
        // StringBuilder approach — Step 7 upgrades to invokedynamic StringConcatFactory
        mg.newInstance(Type.getType(StringBuilder.class));
        mg.dup();
        mg.invokeConstructor(Type.getType(StringBuilder.class),
            Method.getMethod("void <init>()"));

        emitExpr(left);
        appendToStringBuilder(inferredType(left));

        emitExpr(right);
        appendToStringBuilder(inferredType(right));

        mg.invokeVirtual(Type.getType(StringBuilder.class),
            Method.getMethod("String toString()"));
    }

    private void appendToStringBuilder(String type) {
        String desc = switch (type) {
            case "Int"    -> "(I)Ljava/lang/StringBuilder;";
            case "Long"   -> "(J)Ljava/lang/StringBuilder;";
            case "Float"  -> "(F)Ljava/lang/StringBuilder;";
            case "Double" -> "(D)Ljava/lang/StringBuilder;";
            case "Bool"   -> "(Z)Ljava/lang/StringBuilder;";
            case "Str"    -> "(Ljava/lang/String;)Ljava/lang/StringBuilder;";
            default       -> "(Ljava/lang/Object;)Ljava/lang/StringBuilder;";
        };
        mg.invokeVirtual(Type.getType(StringBuilder.class),
            new Method("append", desc));
    }

    private void emitLogicalAnd(Node left, Node right) {
        Label falseLabel = mg.newLabel();
        Label endLabel   = mg.newLabel();
        emitExpr(left);
        mg.ifZCmp(GeneratorAdapter.EQ, falseLabel);
        emitExpr(right);
        mg.goTo(endLabel);
        mg.mark(falseLabel);
        mg.push(false);
        mg.mark(endLabel);
    }

    private void emitLogicalOr(Node left, Node right) {
        Label trueLabel = mg.newLabel();
        Label endLabel  = mg.newLabel();
        emitExpr(left);
        mg.ifZCmp(GeneratorAdapter.NE, trueLabel);
        emitExpr(right);
        mg.goTo(endLabel);
        mg.mark(trueLabel);
        mg.push(true);
        mg.mark(endLabel);
    }

    private void emitEqualityOp(Node left, String op, Node right) {
        String type = inferredType(left);
        boolean isPrimitive = Set.of("Int","Long","Float","Double","Bool","Byte","Char")
            .contains(type);
        emitExpr(left);
        emitExpr(right);
        Label trueLabel = mg.newLabel();
        Label endLabel  = mg.newLabel();
        if (isPrimitive) {
            int cmp = "==".equals(op) ? GeneratorAdapter.EQ : GeneratorAdapter.NE;
            if ("Long".equals(type)) {
                mg.visitInsn(LCMP);
                mg.ifZCmp(cmp, trueLabel);
            } else {
                mg.ifICmp(cmp, trueLabel);
            }
        } else {
            // reference: use Object.equals
            mg.invokeVirtual(Type.getType(Object.class),
                Method.getMethod("boolean equals(Object)"));
            if ("!=".equals(op)) {
                // invert
                mg.push(1);
                mg.visitInsn(IXOR);
            }
            return;
        }
        mg.push(false);
        mg.goTo(endLabel);
        mg.mark(trueLabel);
        mg.push(true);
        mg.mark(endLabel);
    }

    private void emitRefEquality(Node left, Node right) {
        emitExpr(left);
        emitExpr(right);
        Label trueLabel = mg.newLabel();
        Label endLabel  = mg.newLabel();
        mg.ifCmp(Type.getType(Object.class), GeneratorAdapter.EQ, trueLabel);
        mg.push(false);
        mg.goTo(endLabel);
        mg.mark(trueLabel);
        mg.push(true);
        mg.mark(endLabel);
    }

    private void emitRelational(Node left, String op, Node right) {
        emitExpr(left);
        emitExpr(right);
        Label trueLabel = mg.newLabel();
        Label endLabel  = mg.newLabel();
        int cmp = switch (op) {
            case "<"  -> GeneratorAdapter.LT;
            case "<=" -> GeneratorAdapter.LE;
            case ">"  -> GeneratorAdapter.GT;
            case ">=" -> GeneratorAdapter.GE;
            default   -> GeneratorAdapter.EQ;
        };
        mg.ifICmp(cmp, trueLabel);
        mg.push(false);
        mg.goTo(endLabel);
        mg.mark(trueLabel);
        mg.push(true);
        mg.mark(endLabel);
    }

    private void emitMemberAccess(Node obj, Node member) {
        // Plain field/property access (not a method call) — emit object, leave on stack.
        // Full field-read support requires a known owner type; for now push object only.
        emitExpr(obj);
    }

    /**
     * Emit a member method call: {@code obj.method(args)}.
     *
     * <p>For well-known receiver types (currently {@code Str} / {@code java.lang.String})
     * a direct {@code invokevirtual} is emitted.  For {@code toString()} on any object
     * {@code invokevirtual Object.toString()} is used.  Everything else delegates to
     * {@link lang.m.runtime.MRuntime#dynCall} via reflection so that arbitrary Java
     * method calls work at runtime without a full static type system.
     */
    private void emitMemberCall(Node obj, String methodName, List<Node> args) {
        // ── Static call: ClassName.method(args) ──────────────────────────────
        // Detected when the receiver is a bare identifier starting with an
        // upper-case letter that is NOT a local variable (e.g. Thread, Math).
        if (obj instanceof IdentNode id
                && Character.isUpperCase(id.name().charAt(0))
                && (localTypes == null || !localTypes.containsKey(id.name()))
                && (fnDescriptors == null || !fnDescriptors.containsKey(id.name()))) {
            // MRuntime.staticCall(className, method, args...)
            mg.push(id.name());
            mg.push(methodName);
            mg.push(args.size());
            mg.visitTypeInsn(ANEWARRAY, OBJECT_CLASS);
            for (int i = 0; i < args.size(); i++) {
                mg.dup();
                mg.push(i);
                String argType = inferredType(args.get(i));
                emitExpr(args.get(i));
                boxIfPrimitive(argType);
                mg.visitInsn(AASTORE);
            }
            mg.invokeStatic(Type.getObjectType(RUNTIME_CLASS),
                new Method("staticCall",
                    "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;"));
            return;
        }

        String receiverType = inferredType(obj);
        emitExpr(obj);

        // Known String methods — emit direct invokevirtual
        if ("Str".equals(receiverType)) {
            Type strType = Type.getType(String.class);
            switch (methodName) {
                case "length"      -> { mg.invokeVirtual(strType, new Method("length",      "()I")); boxIfPrimitive("Int");  return; }
                case "toUpperCase" -> { mg.invokeVirtual(strType, new Method("toUpperCase", "()Ljava/lang/String;")); return; }
                case "toLowerCase" -> { mg.invokeVirtual(strType, new Method("toLowerCase", "()Ljava/lang/String;")); return; }
                case "trim"        -> { mg.invokeVirtual(strType, new Method("trim",        "()Ljava/lang/String;")); return; }
                case "isEmpty"     -> { mg.invokeVirtual(strType, new Method("isEmpty",     "()Z")); boxIfPrimitive("Bool"); return; }
                case "isBlank"     -> { mg.invokeVirtual(strType, new Method("isBlank",     "()Z")); boxIfPrimitive("Bool"); return; }
                case "strip"       -> { mg.invokeVirtual(strType, new Method("strip",       "()Ljava/lang/String;")); return; }
                case "charAt" -> {
                    for (Node a : args) emitExpr(a);
                    mg.invokeVirtual(strType, new Method("charAt", "(I)C"));
                    boxIfPrimitive("Int"); // char → int (widened) → box as Integer
                    return;
                }
                case "substring" -> {
                    if (args.size() == 1) {
                        emitExpr(args.get(0));
                        mg.invokeVirtual(strType, new Method("substring", "(I)Ljava/lang/String;"));
                    } else if (args.size() == 2) {
                        emitExpr(args.get(0)); emitExpr(args.get(1));
                        mg.invokeVirtual(strType, new Method("substring", "(II)Ljava/lang/String;"));
                    }
                    return;
                }
                case "contains" -> {
                    emitExpr(args.get(0));
                    mg.checkCast(Type.getType("Ljava/lang/CharSequence;"));
                    mg.invokeVirtual(strType, new Method("contains", "(Ljava/lang/CharSequence;)Z"));
                    return;
                }
                case "startsWith" -> {
                    emitExpr(args.get(0));
                    mg.invokeVirtual(strType, new Method("startsWith", "(Ljava/lang/String;)Z"));
                    return;
                }
                case "endsWith" -> {
                    emitExpr(args.get(0));
                    mg.invokeVirtual(strType, new Method("endsWith", "(Ljava/lang/String;)Z"));
                    return;
                }
                case "replace" -> {
                    emitExpr(args.get(0));
                    emitExpr(args.get(1));
                    mg.invokeVirtual(strType, new Method("replace",
                        "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;"));
                    return;
                }
                case "split" -> {
                    emitExpr(args.get(0));
                    mg.invokeVirtual(strType, new Method("split", "(Ljava/lang/String;)[Ljava/lang/String;"));
                    return;
                }
            }
        }

        // toString() works on any Object
        if ("toString".equals(methodName) && args.isEmpty()) {
            mg.invokeVirtual(Type.getType(Object.class),
                new Method("toString", "()Ljava/lang/String;"));
            return;
        }

        // General case: MRuntime.dynCall(Object recv, String method, Object... args)
        mg.push(methodName);
        mg.push(args.size());
        mg.visitTypeInsn(ANEWARRAY, OBJECT_CLASS);
        for (int i = 0; i < args.size(); i++) {
            mg.dup();
            mg.push(i);
            String argType = inferredType(args.get(i));
            emitExpr(args.get(i));
            boxIfPrimitive(argType);
            mg.visitInsn(AASTORE);
        }
        mg.invokeStatic(Type.getObjectType(RUNTIME_CLASS),
            new Method("dynCall",
                "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;"));
    }

    private void emitRangeLiteral(Node left, String op, Node right) {
        // Ranges are represented as int pairs on the stack — real iterator support is Step 7.
        // For now just push the start value so code that uses it doesn't crash.
        emitExpr(left);
    }

    private void emitEquals() {
        // Compare two Objects (or ints) on stack for equality; leave int (0/1) on stack.
        // Simplified: use Object.equals — Step 7 will specialized for primitives.
        mg.invokeVirtual(Type.getType(Object.class),
            Method.getMethod("boolean equals(Object)"));
    }

    private void emitUnary(UnaryNode u) {
        emitExpr(u.operand());
        switch (u.op()) {
            case "-" -> {
                String t = inferredType(u.operand());
                switch (t) {
                    case "Long"   -> mg.math(GeneratorAdapter.NEG, Type.LONG_TYPE);
                    case "Float"  -> mg.math(GeneratorAdapter.NEG, Type.FLOAT_TYPE);
                    case "Double" -> mg.math(GeneratorAdapter.NEG, Type.DOUBLE_TYPE);
                    default       -> mg.math(GeneratorAdapter.NEG, Type.INT_TYPE);
                }
            }
            case "!" -> {
                // boolean NOT: XOR with 1
                mg.push(1);
                mg.visitInsn(IXOR);
            }
        }
    }

    private void emitCall(CallNode c) {
        if (c.callee() instanceof IdentNode id) {
            // Constructor call: UpperCase name not defined as an M function
            if (!id.name().isEmpty()
                    && Character.isUpperCase(id.name().charAt(0))
                    && (fnDescriptors == null || !fnDescriptors.containsKey(id.name()))) {
                emitConstructorCall(id.name(), c.args());
                return;
            }
            // Lambda variable call: name is a local slot, not a top-level function
            if (localSlots != null && localSlots.containsKey(id.name())
                    && (fnDescriptors == null || !fnDescriptors.containsKey(id.name()))) {
                mg.visitVarInsn(ALOAD, localSlots.get(id.name()));
                mg.checkCast(Type.getObjectType(MLAMBDA_CLASS));
                // Pack arguments into Object[]
                mg.push(c.args().size());
                mg.visitTypeInsn(ANEWARRAY, OBJECT_CLASS);
                for (int i = 0; i < c.args().size(); i++) {
                    mg.dup();
                    mg.push(i);
                    String argType = inferredType(c.args().get(i));
                    emitExpr(c.args().get(i));
                    boxIfPrimitive(argType);
                    mg.visitInsn(AASTORE);
                }
                mg.invokeInterface(
                    Type.getObjectType(MLAMBDA_CLASS),
                    new Method("call", MLAMBDA_SAM_DESC));
                // Unbox result if the lambda has a declared return type
                String lambdaRetType = lambdaReturnTypes != null
                    ? lambdaReturnTypes.get(id.name()) : null;
                if (lambdaRetType != null) unboxIfPrimitive(lambdaRetType);
                return;
            }
            for (Node arg : c.args()) emitExpr(arg);
            String desc = fnDescriptors != null
                ? fnDescriptors.getOrDefault(id.name(), "()Ljava/lang/Object;")
                : "()Ljava/lang/Object;";
            mg.invokeStatic(Type.getObjectType(className),
                new Method(id.name(), desc));
            // emitExpr contract: always leave one value on the stack.
            // Void-returning calls push nothing — push null so callers can pop it.
            if (isVoidDescriptor(desc)) mg.visitInsn(ACONST_NULL);
        } else if (c.callee() instanceof BinaryNode b && ".".equals(b.op())) {
            // obj.method(args) — use emitMemberCall for proper virtual dispatch
            if (b.right() instanceof IdentNode methodId) {
                emitMemberCall(b.left(), methodId.name(), c.args());
            } else {
                // Dynamic callee — push null placeholder
                mg.visitInsn(ACONST_NULL);
            }
        } else {
            // Unknown callee — emit null placeholder
            mg.visitInsn(ACONST_NULL);
        }
    }

    /** Returns true if the JVM method descriptor has a void return type. */
    private static boolean isVoidDescriptor(String desc) {
        int rparen = desc.lastIndexOf(')');
        return rparen >= 0 && rparen + 1 < desc.length() && desc.charAt(rparen + 1) == 'V';
    }

    private String fnDescriptorForSymbol(Symbol sym) {
        // We stored the return type in the symbol; build a minimal descriptor.
        // Arg types are not stored in Symbol, so use Object for all params when unknown.
        return "()Ljava/lang/Object;";
    }

    private void emitLambda(LambdaNode l) {
        int nParams = l.params().size();
        String lambdaMethodName = "lambda$" + (lambdaCounter++);
        // Synthetic body always takes Object[] and returns Object
        String lambdaDesc = MLAMBDA_SAM_DESC;

        // ---- Save per-method state ------------------------------------------
        GeneratorAdapter savedMg    = mg;
        Map<String,Integer> savedSlots = localSlots;
        Map<String,String>  savedTypes = localTypes;
        Map<String,String>  savedLambdaRetTypes = lambdaReturnTypes;
        int savedNext = nextLocal;

        // ---- Emit synthetic lambda body method ------------------------------
        mg = new GeneratorAdapter(
            ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC,
            new Method(lambdaMethodName, lambdaDesc),
            null, null, cw);
        mg.visitCode();
        localSlots = new HashMap<>();
        localTypes  = new HashMap<>();
        lambdaReturnTypes = new HashMap<>();
        // Slot 0 = Object[] args; slots 1..N = unpacked param values
        int nextLambdaLocal = 1;

        for (int i = 0; i < nParams; i++) {
            String pName = l.params().get(i).name();
            // Use declared type; default to "Any" so untyped params stay as Object
            String declType = l.params().get(i).type() != null
                ? l.params().get(i).type() : "Any";
            int slot = nextLambdaLocal;
            nextLambdaLocal += ("Long".equals(declType) || "Double".equals(declType)) ? 2 : 1;
            // Unpack: Object[] args[i] -> local slot, unboxing to the declared type
            mg.loadArg(0);       // Object[] args
            mg.push(i);          // index
            mg.arrayLoad(Type.getType(Object.class));  // args[i] as Object
            unboxIfPrimitive(declType);
            localSlots.put(pName, slot);
            localTypes.put(pName, declType);
            mg.visitVarInsn(TypeMap.storeOpcode(declType), slot);
        }
        nextLocal = nextLambdaLocal;

        if (l.body() instanceof BlockNode blk) {
            for (Node stmt : blk.statements()) emitStatement(stmt);
            mg.visitInsn(ACONST_NULL);
        } else {
            emitExpr(l.body());
            boxIfPrimitive(inferredType(l.body()));
        }
        mg.visitInsn(ARETURN);
        mg.endMethod();

        // ---- Restore per-method state ---------------------------------------
        mg              = savedMg;
        localSlots      = savedSlots;
        localTypes      = savedTypes;
        lambdaReturnTypes = savedLambdaRetTypes;
        nextLocal       = savedNext;

        // ---- Emit invokedynamic → MLambda -----------------------------------
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
            "call",
            "()L" + MLAMBDA_CLASS + ";",
            bootstrap,
            Type.getMethodType(MLAMBDA_SAM_DESC),   // erased SAM type
            implHandle,
            Type.getMethodType(lambdaDesc));         // instantiated type
    }

    /**
     * Box a JVM primitive to its corresponding wrapper type.
     * No-op for reference types (already on stack as Object).
     */
    private void boxIfPrimitive(String mType) {
        switch (mType) {
            case "Int"    -> mg.invokeStatic(Type.getType(Integer.class),
                                new Method("valueOf", "(I)Ljava/lang/Integer;"));
            case "Long"   -> mg.invokeStatic(Type.getType(Long.class),
                                new Method("valueOf", "(J)Ljava/lang/Long;"));
            case "Float"  -> mg.invokeStatic(Type.getType(Float.class),
                                new Method("valueOf", "(F)Ljava/lang/Float;"));
            case "Double" -> mg.invokeStatic(Type.getType(Double.class),
                                new Method("valueOf", "(D)Ljava/lang/Double;"));
            case "Bool"   -> mg.invokeStatic(Type.getType(Boolean.class),
                                new Method("valueOf", "(Z)Ljava/lang/Boolean;"));
            // Reference types (Str, Any, etc.) are already objects — do nothing
        }
    }

    private void emitHm(AwaitNode h) {
        emitExpr(h.future());
        mg.checkCast(Type.getObjectType(COMPLETABLE_FUTURE_CLASS));
        mg.invokeVirtual(Type.getObjectType(COMPLETABLE_FUTURE_CLASS),
            new Method("join", "()Ljava/lang/Object;"));
    }

    // ── Utility helpers ───────────────────────────────────────────────────────

    private void emitBlock(BlockNode b) {
        for (Node stmt : b.statements()) emitStatement(stmt);
    }

    /** Ensure the top-of-stack int is boxed if needed; for now this is a no-op. */
    private void ensureBoolean(Node cond) {
        // If the condition is a non-bool expression (e.g. Int), treat non-zero as true.
        // The JVM's IFEQ/IFNE already handles this.
    }

    private void ensureInt(Node code) {
        // If value is a boxed Integer, unbox it. Simplified for Step 6.
    }

    /** Widen a primitive to double for Math.pow. */
    private void toDouble(String type) {
        switch (type) {
            case "Int"   -> mg.visitInsn(I2D);
            case "Long"  -> mg.visitInsn(L2D);
            case "Float" -> mg.visitInsn(F2D);
        }
    }

    /** Get the ASM {@link Type} for a given M type name. */
    private static Type asmType(String mType) {
        return switch (mType) {
            case "Int",  "Bool", "Byte", "Char" -> Type.INT_TYPE;
            case "Long"   -> Type.LONG_TYPE;
            case "Float"  -> Type.FLOAT_TYPE;
            case "Double" -> Type.DOUBLE_TYPE;
            default       -> Type.getType(Object.class);
        };
    }

    /**
     * Infer the M type of a node based on its static structure.
     * Mirrors the logic in {@link lang.m.semantic.SemanticAnalyzer} for use
     * during code generation without re-running the analyser.
     */
    private String inferredType(Node node) {
        return switch (node) {
            case LiteralNode l -> switch (l.kind()) {
                case "int"    -> "Int";
                case "long"   -> "Long";
                case "float"  -> "Float";
                case "double" -> "Double";
                case "bool"   -> "Bool";
                case "string" -> "Str";
                default       -> "Any";
            };
            case IdentNode id -> {
                if (localTypes != null) {
                    String t = localTypes.get(id.name());
                    if (t != null) yield t;
                }
                if (globalScope != null) {
                    Symbol sym = globalScope.resolve(id.name());
                    if (sym != null && sym.type() != null) yield sym.type();
                }
                yield "Any";
            }
            case CallNode c -> {
                if (c.callee() instanceof IdentNode id) {
                    // Lambda variable call: return the lambda's declared return type
                    if (lambdaReturnTypes != null) {
                        String rt = lambdaReturnTypes.get(id.name());
                        if (rt != null) yield rt;
                    }
                    // Top-level static function: extract return type from descriptor
                    if (fnDescriptors != null) {
                        String desc = fnDescriptors.get(id.name());
                        if (desc != null) yield descriptorReturnType(desc);
                    }
                }
                yield "Any";
            }
            case BinaryNode b -> switch (b.op()) {
                case "==","!=","===","<","<=",">",">=","&&","||","is" -> "Bool";
                case "..", "..<" -> "Range";
                default -> inferredType(b.left());
            };
            case UnaryNode u -> "!".equals(u.op()) ? "Bool" : inferredType(u.operand());
            default -> "Any";
        };
    }

    /** Extract the M return type from a JVM method descriptor, e.g. {@code "(DD)D" → "Double"}. */
    private static String descriptorReturnType(String desc) {
        int rparen = desc.lastIndexOf(')');
        if (rparen < 0 || rparen + 1 >= desc.length()) return "Any";
        return switch (desc.substring(rparen + 1)) {
            case "I" -> "Int";
            case "J" -> "Long";
            case "F" -> "Float";
            case "D" -> "Double";
            case "Z" -> "Bool";
            case "V" -> "Unit";
            case "Ljava/lang/String;" -> "Str";
            default  -> "Any";
        };
    }

    /**
     * Unbox a boxed primitive on top of the operand stack to its JVM primitive.
     * For {@code Str}, emits a {@code checkCast} to {@code java/lang/String} so the
     * JVM verifier sees {@code String} (not {@code Object}) after a lambda or await
     * call that returns {@code Object} but is declared as {@code Str}.
     */
    private void unboxIfPrimitive(String mType) {
        // Use Number as the cast target for all numeric types so that widening
        // conversions work at runtime (e.g. Integer -> double, Integer -> float).
        Type NUMBER = Type.getType(Number.class);
        switch (mType) {
            case "Int"    -> { mg.checkCast(NUMBER);
                               mg.invokeVirtual(NUMBER, new Method("intValue",     "()I")); }
            case "Long"   -> { mg.checkCast(NUMBER);
                               mg.invokeVirtual(NUMBER, new Method("longValue",    "()J")); }
            case "Float"  -> { mg.checkCast(NUMBER);
                               mg.invokeVirtual(NUMBER, new Method("floatValue",   "()F")); }
            case "Double" -> { mg.checkCast(NUMBER);
                               mg.invokeVirtual(NUMBER, new Method("doubleValue",  "()D")); }
            case "Bool"   -> { mg.checkCast(Type.getType(Boolean.class));
                               mg.invokeVirtual(Type.getType(Boolean.class),
                                   new Method("booleanValue", "()Z")); }
            case "Str"    -> mg.checkCast(Type.getType(String.class));
            default -> { /* Any / other reference types — already on stack as Object */ }
        }
    }
}

