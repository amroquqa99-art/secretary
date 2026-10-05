package com.alsekretary.app.domain

object DnsFirewallCodec {
    data class Query(val packet: ByteArray,val dns: ByteArray,val host: String)
    private fun u16(b:ByteArray,i:Int)=((b[i].toInt() and 255) shl 8) or (b[i+1].toInt() and 255)
    private fun put(b:ByteArray,i:Int,n:Int){b[i]=(n ushr 8).toByte();b[i+1]=n.toByte()}
    fun query(packet: ByteArray): Query? {
        if(packet.size<40 || (packet[0].toInt() ushr 4 and 15)!=4 || packet[9].toInt()!=17)return null
        val ihl=(packet[0].toInt() and 15)*4
        if(ihl<20 || packet.size<ihl+20 || u16(packet,ihl+2)!=53 || (u16(packet,6) and 0x3fff)!=0)return null
        val udpLength=u16(packet,ihl+4);if(udpLength<20 || ihl+udpLength>packet.size)return null
        val dns=packet.copyOfRange(ihl+8,ihl+udpLength)
        if((dns[2].toInt() and 0x80)!=0 || u16(dns,4)!=1)return null
        var at=12;val labels=mutableListOf<String>()
        while(at<dns.size){val n=dns[at++].toInt() and 255;if(n==0)break;if(n>63 || at+n>dns.size)return null
            labels.add(String(dns,at,n,Charsets.US_ASCII));at+=n}
        if(at+4>dns.size || labels.isEmpty())return null
        return Query(packet,dns,labels.joinToString(".").lowercase())
    }
    fun matches(host: String,blocked: Set<String>)=blocked.any{host==it || host.endsWith(".$it")}
    fun failure(query: Query,code: Int=3): ByteArray {
        val dns=query.dns.copyOf();dns[2]=(0x80 or (dns[2].toInt() and 1)).toByte();dns[3]=(0x80 or code).toByte()
        for(i in 6..11)dns[i]=0
        // Only include the question; remove EDNS additional data.
        var end=12;while(end<dns.size){val n=dns[end++].toInt() and 255;if(n==0)break;end+=n};end+=4
        return response(query,dns.copyOf(end))
    }
    fun response(q: Query,dns: ByteArray): ByteArray {
        require(dns.size<=1400)
        val p=q.packet;val ihl=(p[0].toInt() and 15)*4;val result=ByteArray(28+dns.size)
        result[0]=0x45;put(result,2,result.size);result[8]=64;result[9]=17
        p.copyInto(result,12,16,20);p.copyInto(result,16,12,16)
        put(result,20,53);put(result,22,u16(p,ihl));put(result,24,8+dns.size)
        dns.copyInto(result,28)
        var checksum=0;for(i in 0 until 20 step 2)checksum+=u16(result,i)
        while(checksum ushr 16!=0)checksum=(checksum and 65535)+(checksum ushr 16)
        put(result,10,checksum.inv() and 65535)
        return result
    }
}
