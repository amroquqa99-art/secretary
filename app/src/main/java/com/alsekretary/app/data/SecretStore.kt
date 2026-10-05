package com.alsekretary.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretStore(context: Context) {
    private val prefs=context.getSharedPreferences("secretary_secrets",Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (ks.getKey("secretary-secrets",null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("secretary-secrets",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    @Synchronized fun set(name: String,value: String?) {
        if(value==null){prefs.edit().remove(name).commit();return}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        val packed=cipher.iv+cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name,Base64.encodeToString(packed,Base64.NO_WRAP)).commit()
    }
    @Synchronized fun get(name: String): String? {
        val text=prefs.getString(name,null) ?: return null
        return runCatching {
            val packed=Base64.decode(text,Base64.NO_WRAP);val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,packed.copyOfRange(0,12)))
            String(cipher.doFinal(packed.copyOfRange(12,packed.size)),Charsets.UTF_8)
        }.getOrNull()
    }
}
