package lang.m;

import lang.m.codegen.Compiler;
import lang.m.lexer.Lexer;
import lang.m.lexer.LexerException;
import lang.m.lexer.Token;
import lang.m.parser.ParseException;
import lang.m.parser.Parser;
import lang.m.parser.ast.LetNode;
import lang.m.parser.ast.LiteralNode;
import lang.m.parser.ast.Node;
import lang.m.parser.ast.ProgramNode;
import lang.m.semantic.SemanticAnalyzer;
import lang.m.semantic.SemanticException;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Entry point for the MLang compiler ({@code mcompile}).
 *
 * <p>The compiler follows this pipeline:
 * <pre>
 *   Source (.mlang)
 *     └─► Lexer        → List&lt;Token&gt;
 *     └─► Parser       → ProgramNode (AST)
 *     └─► SemanticAnalyzer (type resolution, scope checking)
 *     └─► Compiler     → .class files (Java 21 / ASM 9.7)
 * </pre>
 *
 * <h2>CLI Usage</h2>
 * <pre>
 *   mcompile &lt;file.mlang&gt;                compile to .class in same directory
 *   mcompile &lt;file.mlang&gt; -o &lt;outdir&gt;   compile to .class in outdir
 *   mcompile &lt;file.mlang&gt; -r [args...]   compile and run main()
 *   mcompile &lt;file.mlang&gt; --tokens       dump token stream and exit
 *   mcompile &lt;file.mlang&gt; --ast          dump AST and exit
 *   mcompile --version                    print compiler version and exit
 *   mcompile --help                       print this help and exit
 * </pre>
 */
public class Main {

    static final String VERSION = "0.1.0";

    /**
     * Compiler entry point.
     *
     * @param args command-line arguments (see class Javadoc for usage)
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        String sourceFile  = null;
        String outDir      = null;
        boolean dumpTokens = false;
        boolean dumpAst    = false;
        boolean run        = false;
        String[] runArgs   = new String[0];

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--version", "-v" -> {
                    System.out.println("MLang compiler v" + VERSION);
                    return;
                }
                case "--help", "-h" -> {
                    printHelp();
                    return;
                }
                case "--tokens" -> dumpTokens = true;
                case "--ast"    -> dumpAst    = true;
                case "-r", "--run" -> {
                    run = true;
                    // everything after -r is forwarded to main() as program args
                    int remaining = args.length - i - 1;
                    runArgs = new String[remaining];
                    System.arraycopy(args, i + 1, runArgs, 0, remaining);
                    i = args.length; // consume the rest
                }
                case "-o" -> {
                    if (i + 1 >= args.length) {
                        err("-o requires an output directory");
                        System.exit(1);
                    }
                    outDir = args[++i];
                }
                default -> {
                    if (sourceFile != null) {
                        err("unexpected argument '" + args[i] + "'");
                        System.exit(1);
                    }
                    sourceFile = args[i];
                }
            }
        }

        if (sourceFile == null) {
            printUsage();
            System.exit(1);
        }

        Path sourcePath = Path.of(sourceFile);
        if (!Files.exists(sourcePath)) {
            err("file not found: " + sourceFile);
            System.exit(1);
        }
        if (!sourceFile.endsWith(".mlang")) {
            err("source file must have .mlang extension");
            System.exit(1);
        }

        String source;
        try {
            source = Files.readString(sourcePath);
        } catch (IOException e) {
            err("error reading file: " + e.getMessage());
            System.exit(1);
            return;
        }

        String outputDir = (outDir != null) ? outDir
            : sourcePath.getParent() != null ? sourcePath.getParent().toString() : ".";

        try {
            // Lex
            List<Token> tokens = new Lexer(source).tokenize();
            if (dumpTokens) {
                tokens.forEach(t -> System.out.printf("  %-16s %s%n", t.type(), t.value()));
                return;
            }

            // Parse
            ProgramNode program = new Parser(tokens).parseProgram();
            if (dumpAst) {
                System.out.println(program);
                return;
            }

            // Semantic analysis
            SemanticAnalyzer sa = new SemanticAnalyzer();
            sa.analyze(program);

            // Codegen
            String className = resolveClassName(program);
            new Compiler(outputDir).compile(program, sa.globalScope());
            System.err.println("[mcompile] " + sourceFile + " → " + outputDir + "/" + className + ".class");

            // Optionally run
            if (run) {
                runClass(outputDir, className, runArgs);
            }

        } catch (LexerException | ParseException | SemanticException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            err("error writing output: " + e.getMessage());
            System.exit(1);
        }
    }

    /** Load the compiled class and invoke its {@code main(String[])} method. */
    private static void runClass(String outputDir, String className, String[] args)
            throws IOException {
        // URLClassLoader requires the directory URL to end with '/' to be treated as a classpath root
        Path dirPath = Path.of(outputDir).toAbsolutePath().normalize();
        String dirUri = dirPath.toUri().toString();
        if (!dirUri.endsWith("/")) dirUri += "/";
        URL[] urls = { new URL(dirUri) };
        try (URLClassLoader cl = new URLClassLoader(urls,
                Thread.currentThread().getContextClassLoader())) {
            Class<?> cls = cl.loadClass(className.replace('/', '.'));
            cls.getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            System.err.println("[mcompile] Runtime error: " + (cause != null ? cause : e));
            System.exit(1);
        } catch (ReflectiveOperationException e) {
            System.err.println("[mcompile] Could not run '" + className + "': " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Mirrors {@code Compiler.resolveClassName}: reads the {@code __module__}
     * synthetic let inserted by the Parser for a {@code module} declaration,
     * or returns {@code "Main"} when none is present.
     */
    private static String resolveClassName(ProgramNode program) {
        for (Node decl : program.declarations()) {
            if (decl instanceof LetNode l && "__module__".equals(l.name()
                    ) && l.initializer() instanceof LiteralNode lit) {
                String mod = lit.value().toString();
                return mod.replace('.', '/');
            }
        }
        return "Main";
    }

    private static void err(String message) {
        System.err.println("[mcompile] Error: " + message);
    }

    private static void printUsage() {
        System.err.println("Usage: mcompile <file.mlang> [-o <outdir>] [-r [args...]] [--tokens] [--ast]");
        System.err.println("       mcompile --help | --version");
    }

    private static void printHelp() {
        System.out.println("MLang compiler v" + VERSION);
        System.out.println();
        System.out.println("USAGE");
        System.out.println("  mcompile <file.mlang>                  Compile to .class in same directory");
        System.out.println("  mcompile <file.mlang> -o <outdir>      Compile to .class in <outdir>");
        System.out.println("  mcompile <file.mlang> -r [args...]     Compile then run main()");
        System.out.println("  mcompile <file.mlang> --tokens         Dump token stream and exit");
        System.out.println("  mcompile <file.mlang> --ast            Dump AST and exit");
        System.out.println("  mcompile --version  (-v)                Print version and exit");
        System.out.println("  mcompile --help     (-h)                Print this help and exit");
        System.out.println();
        System.out.println("EXAMPLES");
        System.out.println("  mcompile hello.mlang                   Produces Main.class");
        System.out.println("  mcompile hello.mlang -r                Compile and run (no extra args)");
        System.out.println("  mcompile hello.mlang -r Alice 42       Compile and run with args");
        System.out.println("  mcompile hello.mlang -o build/         Emit class to build/");
    }
}

