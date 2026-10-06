package com.alsekretary.app.localmodel;
/** Host-only JNI harness with the same entry-point signatures as the Android object. */
public final class NativeBridge {
    static {System.loadLibrary("secretary_llama");}
    public native long open(String path);
    public native byte[] generate(long id,byte[] system,byte[] user,byte[] grammar,int maximum);
    public native void cancel(long id);
    public native void close(long id);
}
