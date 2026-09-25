package org.lsposed.lspd.impl;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.lsposed.lspd.nativebridge.HookBridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedBridge;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.errors.HookFailedError;

public class LSPosedBridge {

    private static final String TAG = "LSPosed-Bridge";

    private static final String castException = "Return value's type from hook callback does not match the hooked method";

    private static final Method getCause;

    private static final Method constructorNewInstance;

    static {
        Method tmp;
        try {
            tmp = InvocationTargetException.class.getMethod("getCause");
        } catch (Throwable e) {
            tmp = null;
        }
        getCause = tmp;
        Method newInstance = null;
        try {
            newInstance = Constructor.class.getMethod("newInstance", Object[].class);
        } catch (Throwable ignored) {
        }
        constructorNewInstance = newInstance;
    }

    public static void log(String text) {
        Log.i(TAG, text);
    }

    public static void log(Throwable t) {
        String logStr = Log.getStackTraceString(t);
        Log.e(TAG, logStr);
    }

    /**
     * Registration record for an interceptor-chain (API 102+) hook.
     * Instances are stored natively per hooked executable and snapshotted on every call,
     * so replacing or removing a hook never affects in-flight calls.
     */
    public static final class HookRecord {
        @NonNull
        final Executable method;
        @NonNull
        final XposedInterface.Hooker hooker;
        final int priority;
        @NonNull
        final XposedInterface.ExceptionMode exceptionMode;
        @Nullable
        final String id;
        @NonNull
        final String moduleId;
        volatile boolean valid = true;

        HookRecord(@NonNull Executable method, @NonNull XposedInterface.Hooker hooker,
                   int priority, @NonNull XposedInterface.ExceptionMode exceptionMode,
                   @Nullable String id, @NonNull String moduleId) {
            this.method = method;
            this.hooker = hooker;
            this.priority = priority;
            this.exceptionMode = exceptionMode;
            this.id = id;
            this.moduleId = moduleId;
        }
    }

    /**
     * Handle returned to modules and to the framework itself for chain hooks.
     */
    public static final class HookHandleImpl implements XposedInterface.HookHandle {
        @NonNull
        final HookRecord record;

        HookHandleImpl(@NonNull HookRecord record) {
            this.record = record;
        }

        @NonNull
        @Override
        public Executable getExecutable() {
            return record.method;
        }

        @Override
        public void unhook() {
            synchronized (hookLock) {
                if (!record.valid) {
                    return;
                }
                record.valid = false;
                try {
                    HookBridge.unhookMethod(true, record.method, record);
                } catch (Throwable t) {
                    log(t);
                }
                removeIdMapping(record);
            }
        }

        @Nullable
        @Override
        public String getId() {
            return record.id;
        }

        @NonNull
        @Override
        public XposedInterface.HookHandle replaceHook(@NonNull XposedInterface.Hooker hooker) {
            if (hooker == null) {
                throw new IllegalArgumentException("hooker should not be null!");
            }
            synchronized (hookLock) {
                if (!record.valid) {
                    throw new IllegalStateException("This hook handle is no longer valid");
                }
                var newRecord = new HookRecord(record.method, hooker, record.priority,
                        record.exceptionMode, record.id, record.moduleId);
                if (!HookBridge.hookMethod(true, record.method, NativeHooker.class, record.priority, newRecord)) {
                    throw new HookFailedError("Cannot hook " + record.method);
                }
                record.valid = false;
                try {
                    HookBridge.unhookMethod(true, record.method, record);
                } catch (Throwable t) {
                    log(t);
                }
                var newHandle = new HookHandleImpl(newRecord);
                if (record.id != null) {
                    putIdMapping(newRecord, newHandle);
                }
                return newHandle;
            }
        }
    }

    /**
     * Builder returned by {@link XposedInterface#hook(Executable)}.
     */
    public static final class HookBuilderImpl implements XposedInterface.HookBuilder {
        @NonNull
        private final Executable origin;
        @NonNull
        private final String moduleId;
        private int priority = XposedInterface.PRIORITY_DEFAULT;
        @NonNull
        private XposedInterface.ExceptionMode exceptionMode = XposedInterface.ExceptionMode.DEFAULT;
        @Nullable
        private String id;

        public HookBuilderImpl(@NonNull Executable origin, @NonNull String moduleId) {
            this.origin = origin;
            this.moduleId = moduleId;
        }

        @Override
        public XposedInterface.HookBuilder setPriority(int priority) {
            this.priority = priority;
            return this;
        }

        @Override
        public XposedInterface.HookBuilder setExceptionMode(@NonNull XposedInterface.ExceptionMode mode) {
            if (mode == null) {
                throw new IllegalArgumentException("mode should not be null!");
            }
            this.exceptionMode = mode;
            return this;
        }

        @Override
        public XposedInterface.HookBuilder setId(@Nullable String id) {
            this.id = id;
            return this;
        }

        @NonNull
        @Override
        public XposedInterface.HookHandle intercept(@NonNull XposedInterface.Hooker hooker) {
            if (hooker == null) {
                throw new IllegalArgumentException("hooker should not be null!");
            }
            validateOrigin(origin);
            synchronized (hookLock) {
                if (id != null) {
                    var existing = lookupIdMapping(moduleId, origin, id);
                    if (existing != null) {
                        return existing.replaceHook(hooker);
                    }
                }
                var record = new HookRecord(origin, hooker, priority,
                        resolveExceptionMode(exceptionMode), id, moduleId);
                if (!HookBridge.hookMethod(true, origin, NativeHooker.class, priority, record)) {
                    throw new HookFailedError("Cannot hook " + origin);
                }
                var handle = new HookHandleImpl(record);
                if (id != null) {
                    putIdMapping(record, handle);
                }
                return handle;
            }
        }
    }

    private static final Object hookLock = new Object();

    /**
     * Entry point for creating hook builders. A null module id identifies
     * framework-internal hooks (used by the framework itself, e.g. Startup).
     */
    @NonNull
    public static XposedInterface.HookBuilder hook(@Nullable String moduleId, @NonNull Executable origin) {
        return new HookBuilderImpl(origin, normalizeModuleId(moduleId));
    }

    private static final Map<String, Map<Executable, Map<String, HookHandleImpl>>> idMap = new ConcurrentHashMap<>();

    private static final String FRAMEWORK_MODULE_ID = "[[framework]]";

    @NonNull
    static String normalizeModuleId(@Nullable String moduleId) {
        return moduleId != null ? moduleId : FRAMEWORK_MODULE_ID;
    }

    @Nullable
    private static HookHandleImpl lookupIdMapping(@NonNull String moduleId, @NonNull Executable origin, @NonNull String id) {
        var byExecutable = idMap.get(normalizeModuleId(moduleId));
        if (byExecutable == null) {
            return null;
        }
        var byId = byExecutable.get(origin);
        if (byId == null) {
            return null;
        }
        return byId.get(id);
    }

    private static void putIdMapping(@NonNull HookRecord record, @NonNull HookHandleImpl handle) {
        // Called with hookLock held
        assert record.id != null;
        idMap.computeIfAbsent(normalizeModuleId(record.moduleId), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(record.method, k -> new ConcurrentHashMap<>())
                .put(record.id, handle);
    }

    private static void removeIdMapping(@NonNull HookRecord record) {
        // Called with hookLock held
        if (record.id == null) {
            return;
        }
        var byExecutable = idMap.get(normalizeModuleId(record.moduleId));
        if (byExecutable == null) {
            return;
        }
        var byId = byExecutable.get(record.method);
        if (byId != null) {
            var handle = byId.get(record.id);
            if (handle != null && handle.record == record) {
                byId.remove(record.id);
            }
            if (byId.isEmpty()) {
                byExecutable.remove(record.method);
            }
        }
    }

    @NonNull
    static XposedInterface.ExceptionMode resolveExceptionMode(@NonNull XposedInterface.ExceptionMode mode) {
        // DEFAULT follows module.prop ("protective" unless specified there); without module.prop
        // parsing in the framework core, protective is the documented default.
        if (mode == XposedInterface.ExceptionMode.DEFAULT) {
            return XposedInterface.ExceptionMode.PROTECTIVE;
        }
        return mode;
    }

    static void validateOrigin(@NonNull Executable origin) {
        if (Modifier.isAbstract(origin.getModifiers())) {
            throw new IllegalArgumentException("Cannot hook abstract methods: " + origin);
        } else if (origin.getDeclaringClass().getClassLoader() == LSPosedBridge.class.getClassLoader()) {
            throw new IllegalArgumentException("Do not allow hooking inner methods");
        } else if (origin.getDeclaringClass() == Method.class && origin.getName().equals("invoke")) {
            throw new IllegalArgumentException("Cannot hook Method.invoke");
        } else if (constructorNewInstance != null && constructorNewInstance.equals(origin)) {
            throw new IllegalArgumentException("Cannot hook Constructor.newInstance");
        }
    }

    /**
     * Interceptor chain for a single hooked call. The terminal of the chain runs the
     * legacy (de.robv) callbacks together with the original executable, so legacy
     * modules keep working underneath the new chain model.
     */
    static final class ChainImpl implements XposedInterface.Chain {
        @NonNull
        final Executable executable;
        final Class<?> returnType;
        final boolean isStatic;
        final Object[] chainSnapshot;
        final Object[] legacySnapshot;
        final LSPosedHookCallback<Executable> legacyState;
        Object thisObject;
        Object[] args;
        int index = 0;
        // Protective-mode bookkeeping for the currently running hooker.
        boolean proceedInvoked;
        Object downstreamResult;
        Throwable downstreamThrown;

        ChainImpl(@NonNull Executable executable, Class<?> returnType, boolean isStatic,
                  Object thisObject, Object[] args,
                  Object[] chainSnapshot, Object[] legacySnapshot) {
            this.executable = executable;
            this.returnType = returnType;
            this.isStatic = isStatic;
            this.thisObject = thisObject;
            this.args = args;
            this.chainSnapshot = chainSnapshot;
            this.legacySnapshot = legacySnapshot;
            this.legacyState = new LSPosedHookCallback<>();
            this.legacyState.method = executable;
        }

        @NonNull
        @Override
        public Executable getExecutable() {
            return executable;
        }

        @Override
        public Object getThisObject() {
            return thisObject;
        }

        @NonNull
        @Override
        public List<Object> getArgs() {
            return Collections.unmodifiableList(Arrays.asList(args.clone()));
        }

        @Override
        public Object getArg(int index) {
            return args[index];
        }

        @Override
        public Object proceed() throws Throwable {
            return proceedInternal(thisObject, args);
        }

        @Override
        public Object proceed(@NonNull Object[] args) throws Throwable {
            if (args == null) {
                throw new IllegalArgumentException("args should not be null!");
            }
            return proceedInternal(thisObject, args);
        }

        @Override
        public Object proceedWith(@NonNull Object thisObject) throws Throwable {
            if (isStatic) {
                throw new IllegalStateException("Static method interceptors cannot change this object");
            }
            return proceedInternal(thisObject, this.args);
        }

        @Override
        public Object proceedWith(@NonNull Object thisObject, @NonNull Object[] args) throws Throwable {
            if (isStatic) {
                throw new IllegalStateException("Static method interceptors cannot change this object");
            }
            if (args == null) {
                throw new IllegalArgumentException("args should not be null!");
            }
            return proceedInternal(thisObject, args);
        }

        Object proceedInternal(Object thisObject, Object[] args) throws Throwable {
            this.thisObject = thisObject;
            this.args = args;
            proceedInvoked = true;
            try {
                Object result = invokeNext();
                downstreamResult = result;
                downstreamThrown = null;
                return result;
            } catch (Throwable t) {
                downstreamResult = null;
                downstreamThrown = t;
                throw t;
            }
        }

        private Object invokeNext() throws Throwable {
            if (index >= chainSnapshot.length) {
                return runTerminal();
            }
            var record = (HookRecord) chainSnapshot[index++];
            proceedInvoked = false;
            downstreamResult = null;
            downstreamThrown = null;
            try {
                return record.hooker.intercept(this);
            } catch (Throwable t) {
                if (resolveExceptionMode(record.exceptionMode) != XposedInterface.ExceptionMode.PROTECTIVE) {
                    throw t;
                }
                log(t);
                if (proceedInvoked) {
                    // The hook already proceeded: keep whatever the downstream produced.
                    if (downstreamThrown != null) {
                        throw downstreamThrown;
                    }
                    return downstreamResult;
                }
                // The hook failed before proceeding: continue the chain without it.
                return invokeNext();
            }
        }

        private Object runTerminal() throws Throwable {
            var callback = legacyState;
            callback.thisObject = thisObject;
            callback.args = args;
            callback.result = null;
            callback.throwable = null;
            callback.isSkipped = false;

            XposedBridge.LegacyApiSupport<Executable> legacy = null;
            if (legacySnapshot.length != 0) {
                legacy = new XposedBridge.LegacyApiSupport<>(callback, legacySnapshot);
                legacy.handleBefore();
            }

            if (!callback.isSkipped) {
                try {
                    var result = HookBridge.invokeOriginalMethod(executable, callback.thisObject, callback.args);
                    callback.setResult(result);
                } catch (InvocationTargetException e) {
                    var throwable = (Throwable) HookBridge.invokeOriginalMethod(getCause, e);
                    callback.setThrowable(throwable);
                }
            }

            if (legacy != null) {
                legacy.handleAfter();
            }

            var t = callback.getThrowable();
            if (t != null) {
                throw t;
            }
            return callback.getResult();
        }
    }

    public static class NativeHooker<T extends Executable> {
        private final Object params;

        private NativeHooker(Executable method) {
            var isStatic = Modifier.isStatic(method.getModifiers());
            Object returnType;
            if (method instanceof Method) {
                returnType = ((Method) method).getReturnType();
            } else {
                returnType = null;
            }
            params = new Object[]{
                    method,
                    returnType,
                    isStatic,
            };
        }

        // This method is quite critical. We should try not to use system methods to avoid
        // endless recursive
        public Object callback(Object[] args) throws Throwable {
            var array = ((Object[]) params);

            var method = (T) array[0];
            var returnType = (Class<?>) array[1];
            var isStatic = (Boolean) array[2];

            final Object thisObject;
            final Object[] hookArgs;
            if (isStatic) {
                thisObject = null;
                hookArgs = args;
            } else {
                thisObject = args[0];
                hookArgs = new Object[args.length - 1];
                //noinspection ManualArrayCopy
                for (int i = 0; i < args.length - 1; ++i) {
                    hookArgs[i] = args[i + 1];
                }
            }

            Object[][] snapshots = HookBridge.callbackSnapshot(HookRecord.class, method);
            Object[] chainSnapshot = snapshots[0];
            Object[] legacySnapshot = snapshots[1];

            if (chainSnapshot.length == 0 && legacySnapshot.length == 0) {
                try {
                    return HookBridge.invokeOriginalMethod(method, thisObject, hookArgs);
                } catch (InvocationTargetException ite) {
                    throw (Throwable) HookBridge.invokeOriginalMethod(getCause, ite);
                }
            }

            var chain = new ChainImpl(method, returnType, isStatic, thisObject, hookArgs, chainSnapshot, legacySnapshot);
            Object result;
            try {
                result = chain.proceedInternal(chain.thisObject, chain.args);
            } catch (Throwable t) {
                throw t;
            }

            // return
            if (returnType != null && !returnType.isPrimitive() && !HookBridge.instanceOf(result, returnType)) {
                throw new ClassCastException(castException);
            }
            return result;
        }
    }
}
