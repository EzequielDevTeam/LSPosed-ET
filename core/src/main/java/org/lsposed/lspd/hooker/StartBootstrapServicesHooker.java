package org.lsposed.lspd.hooker;

import static org.lsposed.lspd.util.Utils.logD;

import androidx.annotation.NonNull;

import org.lsposed.lspd.impl.LSPosedContext;
import org.lsposed.lspd.util.Hookers;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

public class StartBootstrapServicesHooker implements XposedInterface.Hooker {

    @Override
    public Object intercept(XposedInterface.Chain chain) throws Throwable {
        logD("SystemServer#startBootstrapServices() starts");

        try {
            XposedInit.loadedPackagesInProcess.add("android");

            XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam(XposedBridge.sLoadedPackageCallbacks);
            lpparam.packageName = "android";
            lpparam.processName = "android"; // it's actually system_server, but other functions return this as well
            lpparam.classLoader = HandleSystemServerProcessHooker.systemServerCL;
            lpparam.appInfo = null;
            lpparam.isFirstApplication = true;
            XC_LoadPackage.callAll(lpparam);

            LSPosedContext.callOnSystemServerStarting(new XposedModuleInterface.SystemServerStartingParam() {
                @Override
                @NonNull
                public ClassLoader getClassLoader() {
                    return HandleSystemServerProcessHooker.systemServerCL;
                }
            });
        } catch (Throwable t) {
            Hookers.logE("error when hooking startBootstrapServices", t);
        }
        return chain.proceed();
    }
}
