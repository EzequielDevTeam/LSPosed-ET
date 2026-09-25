package org.lsposed.lspd.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Executable;
import java.lang.reflect.Member;

public class LSPosedHookCallback<T extends Executable> {

    public Member method;

    public Object thisObject;

    public Object[] args;

    public Object result;

    public Throwable throwable;

    public boolean isSkipped;

    public LSPosedHookCallback() {
    }

    @NonNull
    public Member getMember() {
        return this.method;
    }

    @Nullable
    public Object getThisObject() {
        return this.thisObject;
    }

    @NonNull
    public Object[] getArgs() {
        return this.args;
    }

    @Nullable
    public Object getResult() {
        return this.result;
    }

    @Nullable
    public Throwable getThrowable() {
        return this.throwable;
    }

    public boolean isSkipped() {
        return this.isSkipped;
    }

    public void setResult(@Nullable Object result) {
        this.result = result;
        this.throwable = null;
    }

    public void setThrowable(@Nullable Throwable throwable) {
        this.result = null;
        this.throwable = throwable;
    }
}
