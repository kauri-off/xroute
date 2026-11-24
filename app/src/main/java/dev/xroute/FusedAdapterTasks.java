package dev.xroute;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

final class FusedAdapterTasks {
    private static final String PACKAGE = "com.google.android.gms.tasks.";
    private static final Executor DIRECT = Runnable::run;

    final Class<?> task;
    private final Method successful;
    private final Method forResult;
    private final Method onSuccess;
    private final Method onComplete;

    FusedAdapterTasks(ClassLoader loader) throws ReflectiveOperationException {
        task = Class.forName(PACKAGE + "Task", false, loader);
        Class<?> tasks = Class.forName(PACKAGE + "Tasks", false, loader);
        Class<?> success = Class.forName(PACKAGE + "SuccessContinuation", false, loader);
        Class<?> complete = Class.forName(PACKAGE + "OnCompleteListener", false, loader);
        successful = task.getMethod("isSuccessful");
        forResult = tasks.getMethod("forResult", Object.class);
        onSuccess = task.getMethod("onSuccessTask", Executor.class, success);
        onComplete = task.getMethod("addOnCompleteListener", Executor.class, complete);
    }

    Object mapSuccess(Object original, UnaryOperator<Object> map) {
        if (!task.isInstance(original)) return original;
        try {
            Object continuation = listener(onSuccess, "then", value -> forResult.invoke(null, map.apply(value)));
            return onSuccess.invoke(original, DIRECT, continuation);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return original;
        }
    }

    boolean observe(Object original, Consumer<Boolean> observer) {
        if (!task.isInstance(original)) return false;
        try {
            Object listener = listener(onComplete, "onComplete", completed -> {
                observer.accept((Boolean) successful.invoke(completed));
                return null;
            });
            onComplete.invoke(original, DIRECT, listener);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static Object listener(Method registration, String name, Dispatch dispatch) {
        Class<?> type = registration.getParameterTypes()[1];
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getName().equals(name)) return dispatch.apply(args[0]);
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> type.getName();
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
    }

    private interface Dispatch {
        Object apply(Object value) throws ReflectiveOperationException;
    }
}
