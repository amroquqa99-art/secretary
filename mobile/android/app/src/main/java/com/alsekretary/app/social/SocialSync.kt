package com.alsekretary.app.social

import android.content.ContentValues
import android.content.Context
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.job.JobParameters
import android.content.ComponentName
import com.alsekretary.app.data.SecretStore
import com.alsekretary.app.data.SecretaryDatabase
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

class ApiException(val status: Int,message: String): Exception(message)
data class SocialState(val connected: Boolean=false,val username: String="",val url: String="",val snapshot: JSONObject=JSONObject(),val pending: Int=0,val failed: List<Pair<String,String>> = emptyList(),val lastSync: Long?=null)

class SocialSync(private val context: Context,private val db: SecretaryDatabase) {
    private val secrets=SecretStore(context)
    private fun session()=secrets.get("social-session")?.let{JSONObject(it)}
    private fun request(base: String,path: String,method: String,body: JSONObject,token: String?=null,id: String?=null): JSONObject {
        val url=URL(base.trimEnd('/')+path)
        require(url.protocol=="https" || (url.protocol=="http" && url.host in setOf("127.0.0.1","localhost","10.0.2.2"))) { "استخدم HTTPS؛ HTTP متاح لاتصال التطوير المحلي فقط" }
        require(url.userInfo==null) { "عنوان خادم غير صالح" }
        val conn=url.openConnection() as HttpURLConnection
        conn.connectTimeout=10000;conn.readTimeout=15000;conn.instanceFollowRedirects=false;conn.requestMethod=method
        conn.setRequestProperty("Accept","application/json")
        token?.let{conn.setRequestProperty("Authorization","Bearer $it")};id?.let{conn.setRequestProperty("Idempotency-Key",it)}
        try {
            if(method!="GET") {conn.doOutput=true;conn.setRequestProperty("Content-Type","application/json");conn.outputStream.use{it.write(body.toString().toByteArray(Charsets.UTF_8))}}
            val code=conn.responseCode;val stream=if(code in 200..299) conn.inputStream else conn.errorStream
            val text=stream?.bufferedReader()?.use{it.readText()} ?: "{}"
            require(text.length<=2_000_000){"استجابة خادم أكبر من الحد"}
            val result=JSONObject(text)
            if(code !in 200..299) throw ApiException(code,result.optString("error","خطأ اتصال $code"))
            return result
        } finally {conn.disconnect()}
    }
    fun login(base: String,username: String,password: String,name: String?,register: Boolean) {
        val body=JSONObject().put("username",username).put("password",password).put("name",name.orEmpty())
        val result=request(base,if(register)"/register" else "/login","POST",body)
        result.put("url",base.trimEnd('/'));secrets.set("social-session",result.toString())
        schedule();sync()
    }
    fun logout() {
        session()?.let{s->runCatching{request(s.getString("url"),"/logout","POST",JSONObject(),s.getString("token"),UUID.randomUUID().toString())}}
        secrets.set("social-session",null)
        context.getSystemService(JobScheduler::class.java).cancel(7003)
    }
    fun searchUser(name: String): JSONArray {
        val s=session() ?: error("سجل الدخول أولاً")
        return request(s.getString("url"),"/users?q="+java.net.URLEncoder.encode(name,"UTF-8"),"GET",JSONObject(),s.getString("token")).getJSONArray("users")
    }
    fun enqueue(path: String,body: JSONObject,method: String="POST") {
        val s=session() ?: error("سجل الدخول أولاً")
        val actor=s.getJSONObject("user").getString("id")
        val payload=JSONObject().put("actor",actor).put("data",body)
        db.writableDatabase.insertOrThrow("social_outbox",null,ContentValues().apply{put("id",UUID.randomUUID().toString());put("method",method);put("path",path);put("body",payload.toString());put("created_at",System.currentTimeMillis());put("status","PENDING")})
    }
    @Synchronized fun sync(): Boolean {
        val s=session() ?: return true
        val actor=s.getJSONObject("user").getString("id")
        val rows=db.readableDatabase.rawQuery("SELECT id,method,path,body FROM social_outbox WHERE status='PENDING' ORDER BY created_at",null).use{c->buildList{while(c.moveToNext())add(listOf(c.getString(0),c.getString(1),c.getString(2),c.getString(3)))}}
        for(row in rows) {
            val payload=JSONObject(row[3]);if(payload.getString("actor")!=actor)continue
            try {
                request(s.getString("url"),row[2],row[1],payload.getJSONObject("data"),s.getString("token"),row[0])
                db.writableDatabase.delete("social_outbox","id=?",arrayOf(row[0]))
            } catch(e: ApiException) {
                if(e.status==401 || e.status==429 || e.status>=500) throw e
                db.writableDatabase.update("social_outbox",ContentValues().apply{put("status","ERROR");put("error",e.message)},"id=?",arrayOf(row[0]))
            }
        }
        val snapshot=request(s.getString("url"),"/snapshot","GET",JSONObject(),s.getString("token"))
        db.writableDatabase.insertWithOnConflict("social_cache",null,ContentValues().apply{put("key",actor);put("json",snapshot.toString());put("updated_at",System.currentTimeMillis())},android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        return true
    }
    fun discard(id: String) {db.writableDatabase.delete("social_outbox","id=?",arrayOf(id))}
    fun state(): SocialState {
        val s=session() ?: return SocialState()
        val actor=s.getJSONObject("user").getString("id")
        val cache=db.readableDatabase.rawQuery("SELECT json,updated_at FROM social_cache WHERE key=?",arrayOf(actor)).use{if(it.moveToFirst())JSONObject(it.getString(0)) to it.getLong(1) else JSONObject() to null}
        var pending=0
        val failed=db.readableDatabase.rawQuery("SELECT id,status,error,body FROM social_outbox",null).use{c->buildList{while(c.moveToNext())if(JSONObject(c.getString(3)).getString("actor")==actor){if(c.getString(1)=="PENDING")pending++ else add(c.getString(0) to c.getString(2))}}}
        return SocialState(true,s.getJSONObject("user").getString("username"),s.getString("url"),cache.first,pending,failed,cache.second)
    }
    fun schedule() {
        if(session()==null)return
        context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(7003,ComponentName(context,SocialSyncService::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(900000).build())
    }
}
class SocialSyncService: JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        executor.execute {
            val db=SecretaryDatabase(applicationContext)
            val success=runCatching{SocialSync(applicationContext,db).sync()}.isSuccess
            db.close();jobFinished(params,!success)
        };return true
    }
    override fun onStopJob(params: JobParameters)=true
    companion object {private val executor=Executors.newSingleThreadExecutor()}
}
