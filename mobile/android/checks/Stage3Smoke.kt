import com.alsekretary.app.domain.*
import java.time.*

fun main() {
    val now=Instant.parse("2026-10-05T12:00:00Z").toEpochMilli()
    fun task(id:String,mins:Int?,status:TaskStatus=TaskStatus.PLANNED)=Task(id,id,"p",status,null,now+86400000,mins,null,"done")
    val tasks=listOf(task("a",30),task("b",60),task("c",20),task("d",45))
    val edges=listOf(TaskDependency("b","a"),TaskDependency("c","a"),TaskDependency("d","b"),TaskDependency("d","c"))
    val signal=ProgressAnalytics.project("p",tasks,edges,emptyList(),now)
    check(signal.criticalMinutes==135 && signal.criticalPath==listOf("a","b","d"))
    val missing=ProgressAnalytics.project("p",listOf(task("x",null)),emptyList(),emptyList(),now)
    check(missing.unknownDurations==1)
    val g=Goal("g","Learn",LifeArea.MENTAL,"specific","count",10.0,5.0,"units",now+7*86400000,"reason","achievable",startedAt=now-14*86400000)
    check(ProgressAnalytics.goal(g,emptyList(),now).health=="بيانات غير كافية")
    val history=listOf(GoalObservation("g",now-7*86400000,0.2),GoalObservation("g",now,0.5))
    val gs=ProgressAnalytics.goal(g,history,now);check(gs.health=="معرض للتأخير" && gs.forecastAt!=null)
    check(ProgressAnalytics.recovery(emptyList())==null)
    check(ProgressAnalytics.recovery(listOf(DailyCheck("2026-10-05",8.0,5,"","","")))==100)
    val password="my-strong-backup-password".toCharArray();val plain="snapshot and attachments".toByteArray()
    val encrypted=BackupCrypto.encrypt(plain,password)
    check(BackupCrypto.decrypt(encrypted,password).contentEquals(plain))
    check(runCatching{BackupCrypto.decrypt(encrypted,"wrong-password".toCharArray())}.isFailure)
    val tampered=encrypted.copyOf().also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()}
    check(runCatching{BackupCrypto.decrypt(tampered,password)}.isFailure)
    println("PASS: critical path, missing durations, evidence-based goal forecast, recovery coverage, encrypted backup roundtrip/wrong password/tampering")
}
