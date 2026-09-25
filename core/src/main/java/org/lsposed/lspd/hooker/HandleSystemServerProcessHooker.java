package org.lsposed.lspd.hooker;

import android.annotation.SuppressLint;

import org.lsposed.lspd.deopt.PrebuiltMethodsDeopter;
import org.lsposed.lspd.impl.LSPosedHelper;
import org.lsposed.lspd.util.Hookers;

import io.github.libxposed.api.XposedInterface;

// system_server initialization
public class HandleSystemServerProcessHooker implements XposedInterface.Hooker {

    public static volatile ClassLoader systemServerCL;

    @SuppressLint("PrivateApi")
    @Override
    public Object intercept(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        Hookers.logD("ZygoteInit#handleSystemServerProcess() starts");
        try {
            // get system_server classLoader
            systemServerCL = Thread.currentThread().getContextClassLoader();
            // deopt methods in SYSTEMSERVERCLASSPATH
            PrebuiltMethodsDeopter.deoptSystemServerMethods(systemServerCL);
            var clazz = Class.forName("com.android.server.SystemServer", false, systemServerCL);
            LSPosedHelper.hookAllMethods(new StartBootstrapServicesHooker(), clazz, "startBootstrapServices");
        } catch (Throwable t) {
            Hookers.logE("error when hooking systemMain", t);
        }
        return result;
    }
}
