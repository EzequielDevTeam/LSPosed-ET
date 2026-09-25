package org.lsposed.lspd.impl;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.errors.HookFailedError;

public class LSPosedHelper {

    @SuppressWarnings("UnusedReturnValue")
    public static XposedInterface.HookHandle
    hookMethod(XposedInterface.Hooker hooker, Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        try {
            var method = clazz.getDeclaredMethod(methodName, parameterTypes);
            return LSPosedBridge.hook(null, method).intercept(hooker);
        } catch (NoSuchMethodException e) {
            throw new HookFailedError(e);
        }
    }

    @SuppressWarnings("UnusedReturnValue")
    public static Set<XposedInterface.HookHandle>
    hookAllMethods(XposedInterface.Hooker hooker, Class<?> clazz, String methodName) {
        var handles = new HashSet<XposedInterface.HookHandle>();
        for (var method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                handles.add(LSPosedBridge.hook(null, method).intercept(hooker));
            }
        }
        return handles;
    }

    @SuppressWarnings("UnusedReturnValue")
    public static <T> XposedInterface.HookHandle
    hookConstructor(XposedInterface.Hooker hooker, Class<T> clazz, Class<?>... parameterTypes) {
        try {
            var constructor = clazz.getDeclaredConstructor(parameterTypes);
            return LSPosedBridge.hook(null, constructor).intercept(hooker);
        } catch (NoSuchMethodException e) {
            throw new HookFailedError(e);
        }
    }

    @SuppressWarnings("UnusedReturnValue")
    public static <T> XposedInterface.HookHandle
    hookMethod(XposedInterface.Hooker hooker, Method method) {
        return LSPosedBridge.hook(null, method).intercept(hooker);
    }
}
