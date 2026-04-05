package lang.m.runtime;

/**
 * MRuntime — built-in helper methods callable from compiled MLang programs.
 *
 * <p>The code generator emits calls to these static methods directly:
 * <pre>
 *   print(x)   →  invokestatic MRuntime.print(Ljava/lang/Object;)V
 *   panic(msg) →  invokestatic MRuntime.panic(Ljava/lang/String;)V
 *   exit(code) →  invokestatic MRuntime.exit(I)V
 * </pre>
 *
 * <p>Primitive overloads of {@code print} are provided to avoid autoboxing
 * overhead on hot print paths.
 *
 * <p>This class is final and cannot be instantiated.
 */
public final class MRuntime {

    private MRuntime() {}

    /**
     * {@code print} — print any value to stdout followed by a newline.
     * This is the MLang equivalent of {@code System.out.println}.
     *
     * @param value the value to print; {@code null} prints the string {@code "null"}
     */
    public static void print(Object value) {
        System.out.println(value);
    }

    // Primitive overloads — called by the code generator when the static type
    // of the argument is known to be a JVM primitive, avoiding autoboxing.
    /** @see #print(Object) */
    public static void print(int value)     { System.out.println(value); }
    public static void print(long value)    { System.out.println(value); }
    public static void print(double value)  { System.out.println(value); }
    public static void print(boolean value) { System.out.println(value); }

    /**
     * {@code panic} — trigger a fatal, unrecoverable panic.
     *
     * <p>Throws {@link PanicError}, which extends {@link Error} rather
     * than {@link Exception}. This means it deliberately bypasses all user
     * {@code catch} blocks and propagates to the top of the call stack.
     *
     * @param message description of why the program is in an unrecoverable state
     * @throws PanicError always
     */
    public static void panic(String message) {
        throw new PanicError("[panic] FATAL: " + message);
    }

    /**
     * {@code exit} — exit the MLang program with the given status code.
     *
     * <p>Delegates directly to {@link System#exit(int)}.
     * A code of {@code 0} signals successful termination;
     * any non-zero value signals an error.
     *
     * @param code exit status code
     */
    public static void exit(int code) {
        System.exit(code);
    }

    /**
     * {@code joinAll} — called by compiled code at function exit to wait for
     * every virtual thread spawned by {@code spawn_vthread} statements in that function.
     *
     * @param threads the list of {@link Thread} objects returned by
     *                {@code Thread.ofVirtual().start(runnable)}; may be empty
     */
    public static void joinAll(java.util.List<Thread> threads) {
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Dynamic method dispatch via reflection — used by the code generator for
     * member-access calls whose receiver type is not statically known.
     *
     * <p>Walks the public methods of {@code recv}'s runtime class and invokes
     * the first one whose name matches {@code method} and whose parameter count
     * equals {@code args.length}.
     *
     * @param recv   the receiving object (must not be {@code null})
     * @param method the method name to invoke
     * @param args   zero or more arguments
     * @return the method's return value, or {@code null} for {@code void} methods
     * @throws RuntimeException wrapping any reflective or invocation error
     */
    public static Object dynCall(Object recv, String method, Object... args) {
        if (recv == null) throw new NullPointerException(
            "dynCall: receiver is null for method '" + method + "'");
        try {
            for (java.lang.reflect.Method m : recv.getClass().getMethods()) {
                if (!m.getName().equals(method) || m.getParameterCount() != args.length) continue;
                Object[] coerced = coerceArgs(m.getParameterTypes(), args);
                if (coerced == null) continue;
                return m.invoke(recv, coerced);
            }
            throw new NoSuchMethodException(
                "No method '" + method + "' with " + args.length
                + " arg(s) on " + recv.getClass().getSimpleName());
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException r) throw r;
            if (cause instanceof Error er) throw er;
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("dynCall failed for '" + method + "'", e);
        }
    }

    /**
     * Invoke a static method on a Java class by name.
     *
     * @param className simple or fully-qualified class name (e.g. {@code "Thread"},
     *                  {@code "java.lang.Math"})
     * @param method    static method name
     * @param args      call arguments
     * @return return value, or {@code null} for {@code void} methods
     */
    public static Object staticCall(String className, String method, Object... args) {
        try {
            Class<?> cls;
            try {
                cls = Class.forName(className);
            } catch (ClassNotFoundException e) {
                cls = Class.forName("java.lang." + className);
            }
            for (java.lang.reflect.Method m : cls.getMethods()) {
                if (!m.getName().equals(method)
                        || !java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        || m.getParameterCount() != args.length) continue;
                Object[] coerced = coerceArgs(m.getParameterTypes(), args);
                if (coerced == null) continue;
                return m.invoke(null, coerced);
            }
            throw new NoSuchMethodException(
                "No static method '" + method + "' with " + args.length
                + " arg(s) on " + className);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException r) throw r;
            if (cause instanceof Error er) throw er;
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("staticCall failed for '" + className + "." + method + "'", e);
        }
    }

    /** Widen numeric boxed args to match the declared parameter types, or return null on mismatch. */
    private static Object[] coerceArgs(Class<?>[] types, Object[] args) {
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            Class<?> t = types[i];
            if (a instanceof Number n) {
                if      (t == long.class    || t == Long.class)      { out[i] = n.longValue();           }
                else if (t == int.class     || t == Integer.class)   { out[i] = n.intValue();            }
                else if (t == double.class  || t == Double.class)    { out[i] = n.doubleValue();         }
                else if (t == float.class   || t == Float.class)     { out[i] = n.floatValue();          }
                else if (t == short.class   || t == Short.class)     { out[i] = n.shortValue();          }
                else if (t == byte.class    || t == Byte.class)      { out[i] = n.byteValue();           }
                else if (t == char.class    || t == Character.class) { out[i] = (char) n.intValue();     }
                else if (t.isInstance(a))                            { out[i] = a;                       }
                else return null;
            } else if (a == null || t.isAssignableFrom(a.getClass()) || t.isInstance(a)) {
                out[i] = a;
            } else {
                return null;
            }
        }
        return out;
    }

    // ── Lambda functional interface ───────────────────────────────────────────

    /**
     * Unified functional interface for all MLang lambdas, regardless of arity.
     * Parameters are passed as a single {@code Object[]} array; the lambda
     * unpacks them by position. The return value is always {@code Object}
     * (boxed if a primitive).
     */
    @FunctionalInterface
    public interface MLambda {
        Object call(Object[] args);
    }

    // ── Internal error type ───────────────────────────────────────────────────

    /**
     * Thrown by {@link #panic(String)}.
     *
     * <p>Extends {@link Error} (not {@link RuntimeException}) so that it
     * propagates through all {@code catch} blocks in user code
     * and cannot be accidentally swallowed.
     */
    public static final class PanicError extends Error {
        public PanicError(String message) {
            super(message);
        }
    }
}
