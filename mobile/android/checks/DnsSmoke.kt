import com.alsekretary.app.domain.DnsFirewallCodec
fun main(){
 val name="www.youtube.com";val dns=java.io.ByteArrayOutputStream();dns.write(byteArrayOf(1,2,1,0,0,1,0,0,0,0,0,0));name.split('.').forEach{dns.write(it.length);dns.write(it.toByteArray())};dns.write(byteArrayOf(0,0,1,0,1))
 val wire=dns.toByteArray();val p=ByteArray(28+wire.size);p[0]=0x45;p[9]=17;p[20]=0x30;p[21]=0x39;p[23]=53;p[24]=((wire.size+8) ushr 8).toByte();p[25]=(wire.size+8).toByte();wire.copyInto(p,28)
 val q=DnsFirewallCodec.query(p)!!;check(q.host==name);check(DnsFirewallCodec.matches(q.host,setOf("youtube.com")));check(!DnsFirewallCodec.matches("notyoutube.com",setOf("youtube.com")))
 val reply=DnsFirewallCodec.failure(q);check(reply[9].toInt()==17 && (reply[31].toInt() and 15)==3);check(reply[20].toInt()==0 && reply[21].toInt()==53);check(reply[22]==p[20] && reply[23]==p[21])
 check(DnsFirewallCodec.query(byteArrayOf(0,1,2))==null)
 println("PASS: DNS parsing, exact/subdomain blocking, boundary bypass prevention, NXDOMAIN packet and invalid packet rejection")
}
