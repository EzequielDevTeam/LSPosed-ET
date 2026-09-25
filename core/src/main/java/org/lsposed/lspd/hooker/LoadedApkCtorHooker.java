package org.lsposed.lspd.hooker;

import android.app.LoadedApk;
import android.content.res.XResources;
import android.util.Log;

import org.lsposed.lspd.util.Hookers;

import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XposedInit;
import io.github.libxposed.api.XposedInterface;

// when a package is loaded for an existing process, trigger the callbacks as well
public class LoadedApkCtorHooker implements XposedInterface.Hooker {

    @Override
    public Object intercept(XposedInterface.Chain chain) throws Throwable {
        chain.proceed();
        Hookers.logD("LoadedApk#<init> starts");

        try {
            LoadedApk loadedApk = (LoadedApk) chain.getThisObject();
            assert loadedApk != null;
            String packageName = loadedApk.getPackageName();
            Object mAppDir = XposedHelpers.getObjectField(loadedApk, "mAppDir");
            Hookers.logD("LoadedApk#<init> ends: " + mAppDir);

            if (!XposedInit.disableResources) {
                XResources.setPackageNameForResDir(packageName, loadedApk.getResDir());
            }

            if (packageName.equals("android")) {
                if (XposedInit.startsSystemServer) {
                    Hookers.logD("LoadedApk#<init> is android, skip: " + mAppDir);
                    return null;
                } else {
                    packageName = "system";
                }
            }

            if (!XposedInit.loadedPackagesInProcess.add(packageName)) {
                Hookers.logD("LoadedApk#<init> has been loaded before, skip: " + mAppDir);
                return null;
            }

            // OnePlus magic...
            if (Log.getStackTraceString(new Throwable()).
                    contains("android.app.ActivityThread$ApplicationThread.schedulePreload")) {
                Hookers.logD("LoadedApk#<init> maybe oneplus's custom opt, skip");
                return null;
            }

            LoadedApkCreateCLHooker.addLoadedApk(loadedApk);
        } catch (Throwable t) {
            Hookers.logE("error when hooking LoadedApk.<init>", t);
        }
        return null;
    }
}
