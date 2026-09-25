package org.lsposed.lspd.impl;

import android.annotation.SuppressLint;
import android.app.ActivityThread;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.lsposed.lspd.core.BuildConfig;
import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.nativebridge.HookBridge;
import org.lsposed.lspd.nativebridge.NativeAPI;
import org.lsposed.lspd.service.ILSPInjectedModuleService;
import org.lsposed.lspd.util.LspModuleClassLoader;

import java.io.File;
import java.io.FileNotFoundException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.error.HookFailedError;
import io.github.libxposed.api.error.XposedFrameworkError;


@SuppressLint("NewApi")
public class LSPosedContext implements XposedInterface {

    private static final String TAG = "LSPosedContext";

    public static boolean isSystemServer;
    public static String appDir;
    public static String processName;

    static final Set<XposedModule> modules = ConcurrentHashMap.newKeySet();

    private final String mPackageName;
    private final ApplicationInfo mApplicationInfo;
    private final ILSPInjectedModuleService service;
    private final Map<String, SharedPreferences> mRemotePrefs = new ConcurrentHashMap<>();

    LSPosedContext(String packageName, ApplicationInfo applicationInfo, ILSPInjectedModuleService service) {
        this.mPackageName = packageName;
        this.mApplicationInfo = applicationInfo;
        this.service = service;
    }

    @NonNull
    @Override
    public ApplicationInfo getModuleApplicationInfo() {
        return mApplicationInfo;
    }

    public static void callOnPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        for (XposedModule module : modules) {
            try {
                module.onPackageLoaded(param);
            } catch (Throwable t) {
                Log.e(TAG, "Error when calling onPackageLoaded of " + module.getModuleApplicationInfo().packageName, t);
            }
        }
    }

    public static void callOnSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        for (XposedModule module : modules) {
            try {
                module.onSystemServerStarting(param);
            } catch (Throwable t) {
                Log.e(TAG, "Error when calling onSystemServerStarting of " + module.getModuleApplicationInfo().packageName, t);
            }
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    public static boolean loadModule(ActivityThread at, Module module) {
        try {
            Log.d(TAG, "Loading module " + module.packageName);
            var sb = new StringBuilder();
            var abis = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
            for (String abi : abis) {
                sb.append(module.apkPath).append("!/lib/").append(abi).append(File.pathSeparator);
            }
            var librarySearchPath = sb.toString();
            var initLoader = XposedModule.class.getClassLoader();
            var mcl = LspModuleClassLoader.loadApk(module.apkPath, module.file.preLoadedDexes, librarySearchPath, initLoader);
            if (mcl.loadClass(XposedModule.class.getName()).getClassLoader() != initLoader) {
                Log.e(TAG, "  Cannot load module: " + module.packageName);
                Log.e(TAG, "  The Xposed API classes are compiled into the module's APK.");
                Log.e(TAG, "  This may cause strange issues and must be fixed by the module developer.");
                return false;
            }
            var ctx = new LSPosedContext(module.packageName, module.applicationInfo, module.service);
            var loadedParam = new XposedModuleInterface.ModuleLoadedParam() {
                @Override
                public boolean isSystemServer() {
                    return isSystemServer;
                }

                @NonNull
                @Override
                public String getProcessName() {
                    return processName;
                }
            };
            for (var entry : module.file.moduleClassNames) {
                var moduleClass = mcl.loadClass(entry);
                Log.d(TAG, "  Loading class " + moduleClass);
                if (!XposedModule.class.isAssignableFrom(moduleClass)) {
                    Log.e(TAG, "    This class doesn't implement any sub-interface of XposedModule, skipping it");
                    continue;
                }
                try {
                    var entryInstance = (XposedModule) moduleClass.getConstructor().newInstance();
                    entryInstance.attachFramework(ctx, () -> {
                        modules.remove(entryInstance);
                        Log.i(TAG, "Module entry detached: " + entry);
                    });
                    modules.add(entryInstance);
                    try {
                        entryInstance.onModuleLoaded(loadedParam);
                    } catch (Throwable t) {
                        Log.e(TAG, "    Error when calling onModuleLoaded of " + entry, t);
                    }
                } catch (Throwable e) {
                    Log.e(TAG, "    Failed to load class " + moduleClass, e);
                }
            }
            module.file.moduleLibraryNames.forEach(NativeAPI::recordNativeEntrypoint);
            Log.d(TAG, "Loaded module " + module.packageName + ": " + ctx);
        } catch (Throwable e) {
            Log.d(TAG, "Loading module " + module.packageName, e);
            return false;
        }
        return true;
    }

    @NonNull
    @Override
    public String getFrameworkName() {
        return BuildConfig.FRAMEWORK_NAME;
    }

    @NonNull
    @Override
    public String getFrameworkVersion() {
        return BuildConfig.VERSION_NAME;
    }

    @Override
    public long getFrameworkVersionCode() {
        return BuildConfig.VERSION_CODE;
    }

    @Override
    public long getFrameworkProperties() {
        return PROP_CAP_SYSTEM | PROP_CAP_REMOTE;
    }

    @NonNull
    @Override
    public HookBuilder hook(@NonNull Executable origin) {
        return LSPosedBridge.hook(mPackageName, origin);
    }

    @NonNull
    @Override
    public HookBuilder hookClassInitializer(@NonNull Class<?> origin) {
        throw new HookFailedError("Hooking class initializers is not supported by this framework");
    }

    private static boolean doDeoptimize(@NonNull Executable method) {
        if (Modifier.isAbstract(method.getModifiers())) {
            throw new IllegalArgumentException("Cannot deoptimize abstract methods: " + method);
        } else if (Proxy.isProxyClass(method.getDeclaringClass())) {
            throw new IllegalArgumentException("Cannot deoptimize methods from proxy class: " + method);
        }
        return HookBridge.deoptimizeMethod(method);
    }

    @Override
    public boolean deoptimize(@NonNull Executable executable) {
        return doDeoptimize(executable);
    }

    @NonNull
    @Override
    public Invoker<?, Method> getInvoker(@NonNull Method method) {
        return new MethodInvokerImpl(method);
    }

    @NonNull
    @Override
    public <T> CtorInvoker<T> getInvoker(@NonNull Constructor<T> constructor) {
        return new CtorInvokerImpl<>(constructor);
    }

    @Override
    public void log(int priority, @Nullable String tag, @NonNull String msg) {
        Log.println(priority, tag != null ? tag : TAG, mPackageName + ": " + msg);
    }

    @Override
    public void log(int priority, @Nullable String tag, @NonNull String msg, @Nullable Throwable tr) {
        Log.println(priority, tag != null ? tag : TAG, mPackageName + ": " + msg + '\n' + Log.getStackTraceString(tr));
    }

    @NonNull
    @Override
    public SharedPreferences getRemotePreferences(@NonNull String group) {
        if (group == null) throw new IllegalArgumentException("group must not be null");
        return mRemotePrefs.computeIfAbsent(group, n -> {
            try {
                return new LSPosedRemotePreferences(service, n);
            } catch (RemoteException e) {
                log(Log.ERROR, TAG, "Failed to get remote preferences", e);
                throw new XposedFrameworkError(e);
            }
        });
    }

    @NonNull
    @Override
    public String[] listRemoteFiles() {
        try {
            return service.getRemoteFileList();
        } catch (RemoteException e) {
            log(Log.ERROR, TAG, "Failed to list remote files", e);
            throw new XposedFrameworkError(e);
        }
    }

    @NonNull
    @Override
    public ParcelFileDescriptor openRemoteFile(@NonNull String name) throws FileNotFoundException {
        if (name == null) throw new IllegalArgumentException("name must not be null");
        try {
            return service.openRemoteFile(name);
        } catch (RemoteException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    private static final class MethodInvokerImpl implements Invoker<MethodInvokerImpl, Method> {
        @NonNull
        private final Method method;
        @NonNull
        private Type type = Type.Chain.FULL;

        MethodInvokerImpl(@NonNull Method method) {
            this.method = method;
        }

        @Override
        public MethodInvokerImpl setType(@NonNull Type type) {
            if (type == null) {
                throw new IllegalArgumentException("type should not be null!");
            }
            this.type = type;
            return this;
        }

        @Override
        public Object invoke(Object thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
            if (type instanceof Type.Origin) {
                try {
                    return HookBridge.invokeOriginalMethod(method, thisObject, args);
                } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                    throw e;
                } catch (Throwable t) {
                    throw new InvocationTargetException(t);
                }
            }
            var maxPriority = ((Type.Chain) type).maxPriority();
            return invokeChain(thisObject, args, maxPriority);
        }

        @Override
        public Object invokeSpecial(@NonNull Object thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
            if (Modifier.isStatic(method.getModifiers())) {
                throw new IllegalArgumentException("Cannot invoke special on static method: " + method);
            }
            try {
                return HookBridge.invokeSpecialMethod(method, getExecutableShorty(method), method.getDeclaringClass(), thisObject, args);
            } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                throw e;
            } catch (Throwable t) {
                throw new InvocationTargetException(t);
            }
        }

        private Object invokeChain(Object thisObject, Object[] args, int maxPriority) throws InvocationTargetException {
            try {
                var snapshots = HookBridge.callbackSnapshot(LSPosedBridge.HookRecord.class, method);
                var filtered = new ArrayList<>(snapshots[0].length);
                for (var record : snapshots[0]) {
                    if (((LSPosedBridge.HookRecord) record).priority <= maxPriority) {
                        filtered.add(record);
                    }
                }
                var isStatic = Modifier.isStatic(method.getModifiers());
                Class<?> returnType = method.getReturnType();
                var chain = new LSPosedBridge.ChainImpl(method, returnType, isStatic, thisObject, args,
                        filtered.toArray(), snapshots[1]);
                return chain.proceedInternal(thisObject, args);
            } catch (InvocationTargetException | IllegalArgumentException e) {
                throw e;
            } catch (Throwable t) {
                throw new InvocationTargetException(t);
            }
        }
    }

    private static final class CtorInvokerImpl<T> implements CtorInvoker<T> {
        @NonNull
        private final Constructor<T> constructor;
        @NonNull
        private Type type = Type.Chain.FULL;

        CtorInvokerImpl(@NonNull Constructor<T> constructor) {
            this.constructor = constructor;
        }

        @Override
        public CtorInvoker<T> setType(@NonNull Type type) {
            if (type == null) {
                throw new IllegalArgumentException("type should not be null!");
            }
            this.type = type;
            return this;
        }

        @Override
        public Object invoke(Object thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
            if (type instanceof Type.Origin) {
                try {
                    return HookBridge.invokeOriginalMethod(constructor, thisObject, args);
                } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                    throw e;
                } catch (Throwable t) {
                    throw new InvocationTargetException(t);
                }
            }
            var maxPriority = ((Type.Chain) type).maxPriority();
            try {
                var snapshots = HookBridge.callbackSnapshot(LSPosedBridge.HookRecord.class, constructor);
                var filtered = new ArrayList<>(snapshots[0].length);
                for (var record : snapshots[0]) {
                    if (((LSPosedBridge.HookRecord) record).priority <= maxPriority) {
                        filtered.add(record);
                    }
                }
                var chain = new LSPosedBridge.ChainImpl(constructor, null, false, thisObject, args,
                        filtered.toArray(), snapshots[1]);
                return chain.proceedInternal(thisObject, args);
            } catch (InvocationTargetException | IllegalArgumentException | IllegalAccessException e) {
                throw e;
            } catch (Throwable t) {
                throw new InvocationTargetException(t);
            }
        }

        @Override
        public Object invokeSpecial(@NonNull Object thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
            try {
                return HookBridge.invokeSpecialMethod(constructor, getExecutableShorty(constructor),
                        constructor.getDeclaringClass(), thisObject, args);
            } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                throw e;
            } catch (Throwable t) {
                throw new InvocationTargetException(t);
            }
        }

        @NonNull
        @Override
        public T newInstance(Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException, InstantiationException {
            var obj = HookBridge.allocateObject(constructor.getDeclaringClass());
            try {
                HookBridge.invokeOriginalMethod(constructor, obj, args);
            } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                throw e;
            } catch (Throwable t) {
                throw new InvocationTargetException(t);
            }
            return obj;
        }

        @NonNull
        @Override
        public <U> U newInstanceSpecial(@NonNull Class<U> subClass, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException, InstantiationException {
            var superClass = constructor.getDeclaringClass();
            if (!superClass.isAssignableFrom(subClass)) {
                throw new IllegalArgumentException(subClass + " is not inherited from " + superClass);
            }
            var obj = HookBridge.allocateObject(subClass);
            try {
                HookBridge.invokeSpecialMethod(constructor, getExecutableShorty(constructor), superClass, obj, args);
            } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                throw e;
            } catch (Throwable t) {
                throw new InvocationTargetException(t);
            }
            return obj;
        }
    }

    private static char getTypeShorty(Class<?> type) {
        if (type == int.class) {
            return 'I';
        } else if (type == long.class) {
            return 'J';
        } else if (type == float.class) {
            return 'F';
        } else if (type == double.class) {
            return 'D';
        } else if (type == boolean.class) {
            return 'Z';
        } else if (type == byte.class) {
            return 'B';
        } else if (type == char.class) {
            return 'C';
        } else if (type == short.class) {
            return 'S';
        } else if (type == void.class) {
            return 'V';
        } else {
            return 'L';
        }
    }

    private static char[] getExecutableShorty(Executable executable) {
        var parameterTypes = executable.getParameterTypes();
        var shorty = new char[parameterTypes.length + 1];
        shorty[0] = getTypeShorty(executable instanceof Method ? ((Method) executable).getReturnType() : void.class);
        for (int i = 1; i < shorty.length; i++) {
            shorty[i] = getTypeShorty(parameterTypes[i - 1]);
        }
        return shorty;
    }
}
