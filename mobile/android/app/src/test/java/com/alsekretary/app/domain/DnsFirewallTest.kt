package com.alsekretary.app.domain
import org.junit.Assert.*
import org.junit.Test
class DnsFirewallTest {
    @Test fun matchesSubdomainsWithoutSuffixBypass() {
        assertTrue(DnsFirewallCodec.matches("www.youtube.com",setOf("youtube.com")))
        assertTrue(DnsFirewallCodec.matches("youtube.com",setOf("youtube.com")))
        assertFalse(DnsFirewallCodec.matches("notyoutube.com",setOf("youtube.com")))
        assertFalse(DnsFirewallCodec.matches("youtube.com.example.org",setOf("youtube.com")))
    }
    @Test fun rejectsInvalidPackets() {assertNull(DnsFirewallCodec.query(byteArrayOf(1,2,3)))}
}
