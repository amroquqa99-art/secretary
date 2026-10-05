package com.alsekretary.app.strict

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.content.Intent
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.alsekretary.app.data.StrictModeStore
import com.alsekretary.app.domain.DnsFirewallCodec
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.util.concurrent.Executors

class FocusDnsVpnService: VpnService() {
    @Volatile private var tunnel: ParcelFileDescriptor?=null
    @Volatile private var running=false
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(running)return START_NOT_STICKY
        val store=StrictModeStore(this)
        if(!store.active || store.blockedDomains.isEmpty()){stopSelf();return START_NOT_STICKY}
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("focus-dns","حماية التركيز",NotificationManager.IMPORTANCE_LOW))
        startForeground(884,NotificationCompat.Builder(this,"focus-dns").setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("حجب المواقع أثناء التركيز").setContentText("مرشح DNS محلي نشط").build())
        tunnel=Builder().setSession("Secretary focus DNS").addAddress("10.111.0.2",32).addDnsServer("10.111.0.1").addRoute("10.111.0.1",32).setMtu(1500).setBlocking(true).addDisallowedApplication(packageName).establish()
        val fd=tunnel ?: run{stopSelf();return START_NOT_STICKY};running=true
        executor.execute {
            try {
                val input=FileInputStream(fd.fileDescriptor);val output=FileOutputStream(fd.fileDescriptor);val buffer=ByteArray(32767)
                while(running && store.active){val n=input.read(buffer);if(n<=0)continue
                    val q=DnsFirewallCodec.query(buffer.copyOf(n)) ?: continue
                    val answer=if(DnsFirewallCodec.matches(q.host,store.blockedDomains))DnsFirewallCodec.failure(q) else runCatching {
                        // IP endpoint avoids DNS recursion; only system DNS is routed through this VPN.
                        val conn=URL("https://1.1.1.1/dns-query").openConnection() as HttpsURLConnection
                        conn.connectTimeout=5000;conn.readTimeout=5000;conn.requestMethod="POST";conn.doOutput=true
                        conn.setRequestProperty("Content-Type","application/dns-message");conn.setRequestProperty("Accept","application/dns-message")
                        try{conn.outputStream.use{it.write(q.dns)};require(conn.responseCode==200);val bytes=conn.inputStream.use{it.readBytes()};DnsFirewallCodec.response(q,bytes)}finally{conn.disconnect()}
                    }.getOrElse{DnsFirewallCodec.failure(q,2)}
                    output.write(answer)
                }
            } finally {running=false;runCatching{fd.close()};tunnel=null;stopSelf()}
        }
        return START_NOT_STICKY
    }
    override fun onRevoke(){stopTunnel();super.onRevoke()}
    override fun onDestroy(){stopTunnel();super.onDestroy()}
    private fun stopTunnel(){running=false;runCatching{tunnel?.close()};tunnel=null}
    companion object {private val executor=Executors.newSingleThreadExecutor()}
}
