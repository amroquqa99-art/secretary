package com.alsekretary.app.domain
import org.junit.Assert.*
import org.junit.Test
class BackupCryptoTest {
    @Test fun roundTripAndTamperProtection() {
        val password="correct-password".toCharArray();val plain="private backup".toByteArray();val packed=BackupCrypto.encrypt(plain,password)
        assertArrayEquals(plain,BackupCrypto.decrypt(packed,password))
        assertTrue(runCatching{BackupCrypto.decrypt(packed,"wrong-password".toCharArray())}.isFailure)
        packed[packed.lastIndex]=(packed.last().toInt() xor 1).toByte()
        assertTrue(runCatching{BackupCrypto.decrypt(packed,password)}.isFailure)
    }
}
