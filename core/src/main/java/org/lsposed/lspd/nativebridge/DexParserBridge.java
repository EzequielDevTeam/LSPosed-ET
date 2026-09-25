package org.lsposed.lspd.nativebridge;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * JNI bridge for the native dex parser.
 *
 * <p>Kept as a stub so that the native registration in {@code dex_parser.cpp}
 * ({@code RegisterDexParserBridge}) keeps succeeding. The DexParser API was
 * removed from libxposed API 102, so nothing calls these methods anymore.
 */
public class DexParserBridge {

    public static native Object openDex(ByteBuffer buffer, long[] args);

    public static native void closeDex(long cookie);

    public static native void visitClass(long cookie, Object visitor, Class<?> fieldVisitorClass,
                                         Class<?> methodVisitorClass, Method classVisitMethod,
                                         Method fieldVisitMethod, Method methodVisitMethod,
                                         Method methodBodyVisitMethod, Method stopMethod);
}
