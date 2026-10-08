package com.alsekretary.app.domain

import java.security.SecureRandom
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.GCMParameterSpec

object BackupCrypto {
    private val magic=byteArrayOf(83,75,82,51,49)
    private const val iterations=310000
    private fun key(password: CharArray,salt: ByteArray): SecretKeySpec {
        require(password.size>=8){"كلمة مرور النسخة 8 أحرف على الأقل"}
        val spec=PBEKeySpec(password,salt,iterations,256)
        return try{SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,"AES")}finally{spec.clearPassword()}
    }
    fun encrypt(plain: ByteArray,password: CharArray): ByteArray {
        val random=SecureRandom();val salt=ByteArray(16).also{random.nextBytes(it)};val iv=ByteArray(12).also{random.nextBytes(it)}
        val header=magic+ByteBuffer.allocate(4).putInt(iterations).array()+salt+iv
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(password,salt),GCMParameterSpec(128,iv));cipher.updateAAD(header)
        return header+cipher.doFinal(plain)
    }
    fun decrypt(packed: ByteArray,password: CharArray): ByteArray {
        require(packed.size in 53..134217728 && packed.copyOfRange(0,5).contentEquals(magic)){"ملف نسخة غير صالح"}
        require(ByteBuffer.wrap(packed,5,4).int==iterations){"صيغة تشفير غير مدعومة"}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(password,packed.copyOfRange(9,25)),GCMParameterSpec(128,packed.copyOfRange(25,37)));cipher.updateAAD(packed.copyOfRange(0,37))
        return cipher.doFinal(packed.copyOfRange(37,packed.size))
    }
}
