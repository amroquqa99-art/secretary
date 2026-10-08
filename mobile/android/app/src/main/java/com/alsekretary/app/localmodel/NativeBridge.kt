package com.alsekretary.app.localmodel

/** Inference-only JNI: no database, HTTP, or action execution entry points. */
internal object NativeBridge {
    init { System.loadLibrary("secretary_llama") }
    external fun open(path: String): Long
    external fun openEmbedding(path: String): Long
    external fun generate(id: Long,system: ByteArray,user: ByteArray,grammar: ByteArray,maximum: Int): ByteArray
    external fun embed(id: Long,text: ByteArray): FloatArray
    external fun cancel(id: Long)
    external fun close(id: Long)
}
