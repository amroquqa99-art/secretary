package com.alsekretary.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map{getJSONObject(it)}
val socialMetrics=listOf("LEARNING" to "التعلم","RELIABILITY" to "الالتزام","GOAL" to "الأهداف","CONSISTENCY" to "الاستمرار","PROJECTS" to "المشاريع","IMPROVEMENT" to "التحسن")
@Composable
fun PersonalHub(state: MainUiState,vm: MainViewModel) {
    var tab by remember {mutableStateOf(0)}
    Column(Modifier.fillMaxSize()) {
        Row { listOf("السكرتير","متابعة","التعاون","الإعدادات").forEachIndexed{i,label->TextButton(onClick={tab=i}){Text(if(tab==i)"• $label" else label)}} }
        Box(Modifier.weight(1f)) {when(tab){0->AssistantScreen(state,vm);1->LifeManagementScreen(state,vm);2->SocialScreen(state,vm);else->SettingsScreen(state,vm)}}
    }
}
@Composable
fun SocialScreen(state: MainUiState,vm: MainViewModel) {
    val social=state.social
    var url by remember {mutableStateOf("https://")};var username by remember{mutableStateOf("")};var password by remember{mutableStateOf("")};var name by remember{mutableStateOf("")};var register by remember{mutableStateOf(false)}
    var search by remember{mutableStateOf("")};var createChallenge by remember{mutableStateOf(false)};var share by remember{mutableStateOf(false)}
    var challengeAction by remember{mutableStateOf<Pair<String,String>?>(null)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item{Text("التعاون",style=MaterialTheme.typography.headlineMedium)}
        if(!social.connected) {
            item { GlassCard {
                Text("حساب مستقل؛ المشاركة اختيارية لكل عنصر")
                OutlinedTextField(url,{url=it},label={Text("عنوان خادمك HTTPS")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(username,{username=it},label={Text("اسم المستخدم")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(password,{password=it},label={Text("كلمة المرور")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                if(register)OutlinedTextField(name,{name=it},label={Text("الاسم الظاهر")})
                Row{Checkbox(register,{register=it});Text("إنشاء حساب جديد")}
                Button(onClick={vm.socialLogin(url,username,password,name,register);password=""},enabled=username.isNotBlank() && password.length>=8 && (!register || name.isNotBlank())){Text(if(register)"إنشاء الحساب" else "دخول")}
            } }
        } else {
            item { GlassCard {
                Text("@${social.username}")
                Text("آخر مزامنة: ${social.lastSync?.let{formatDate(it)+" "+formatTime(it)} ?: "لم تتم"}")
                Text("عمليات تنتظر الاتصال: ${social.pending}")
                Row{Button(onClick=vm::syncSocial){Text("مزامنة")};TextButton(onClick=vm::socialLogout){Text("خروج")}}
            } }
            items(social.failed,key={it.first}){failed->GlassCard{Text("لم تُقبل عملية: ${failed.second}",color=MaterialTheme.colorScheme.error);TextButton(onClick={vm.discardSocial(failed.first)}){Text("حذف العملية المرفوضة")}}}
            item{GlassCard{Text("ابحث باسم مستخدم محدد لإضافة صديق");OutlinedTextField(search,{search=it},label={Text("اسم المستخدم")});TextButton(onClick={vm.searchSocial(search)},enabled=search.length>=3){Text("بحث")}}}
            items(state.socialSearch,key={it.getString("id")}){person->GlassCard{Text(person.getString("name")+" @"+person.getString("username"));TextButton(onClick={vm.socialAction("/friends/request",JSONObject().put("user_id",person.getString("id")))}){Text("طلب صداقة")}}}
            item{SectionLabel("الأصدقاء والطلبات")}
            items(social.snapshot.optJSONArray("friends")?.objects().orEmpty(),key={"friend:"+it.getString("id")}){f->GlassCard{
                Text(f.getString("name")+" • "+humanLabel(f.getString("status")))
                if(f.getString("status")=="PENDING" && f.getBoolean("incoming"))Row{TextButton(onClick={vm.socialAction("/friends/respond",JSONObject().put("user_id",f.getString("id")).put("accept",true))}){Text("قبول")};TextButton(onClick={vm.socialAction("/friends/respond",JSONObject().put("user_id",f.getString("id")).put("accept",false))}){Text("رفض")}}
                if(f.getString("status")=="ACCEPTED")TextButton(onClick={vm.socialAction("/friends/remove",JSONObject().put("user_id",f.getString("id")))}){Text("إزالة الصداقة")}
            }}
            item{Row{Button(onClick={createChallenge=true}){Text("تحدٍ جديد")};TextButton(onClick={share=true}){Text("مشاركة تقدم")}}}
            item{Text("الترتيب حسب نسبة التقدم نحو هدف كل مشارك. الإنجازات مبلغ عنها ذاتياً؛ لا يمثل التصنيف حكماً على الأشخاص.",style=MaterialTheme.typography.bodySmall)}
            items(social.snapshot.optJSONArray("challenges")?.objects().orEmpty(),key={"challenge:"+it.getString("id")}){c->GlassCard{
                Text(c.getString("title"),style=MaterialTheme.typography.titleLarge)
                Text("${socialMetrics.firstOrNull{it.first==c.getString("metric")}?.second} • ${humanLabel(c.getString("visibility"))} • ${formatDate(c.getLong("deadline"))}")
                c.getJSONArray("leaderboard").objects().forEachIndexed{i,m->Text("${i+1}. ${m.getString("name")} — ${"%.1f".format(m.getDouble("score"))}% (${m.getDouble("progress")}/${m.getDouble("target")})")}
                Row {
                    if(!c.getBoolean("joined"))TextButton(onClick={challengeAction=c.getString("id") to "join"}){Text(if(c.optBoolean("invited"))"قبول الدعوة" else "انضمام")}
                    else {
                        TextButton(onClick={challengeAction=c.getString("id") to "progress"}){Text("سجل تقدمك")}
                        if(c.getString("owner_id")==social.snapshot.optJSONObject("me")?.optString("id"))TextButton(onClick={challengeAction=c.getString("id") to "invite"}){Text("دعوة صديق")}
                        else TextButton(onClick={vm.socialAction("/challenges/${c.getString("id")}/leave",JSONObject())}){Text("مغادرة")}
                    }
                }
            }}
            item{SectionLabel("المشاركات")}
            items(social.snapshot.optJSONArray("shares")?.objects().orEmpty(),key={"share:"+it.getString("id")}){post->GlassCard{
                Text(post.getString("title"),style=MaterialTheme.typography.titleMedium);Text(post.getString("summary"));Text("${post.getString("name")} • ${humanLabel(post.getString("visibility"))}")
                if(post.getString("owner_id")==social.snapshot.optJSONObject("me")?.optString("id"))TextButton(onClick={vm.socialAction("/shares/${post.getString("id")}",JSONObject(),"DELETE")}){Text("سحب المشاركة")}
            }}
        }
    }
    if(createChallenge)ChallengeDialog({createChallenge=false}){vm.socialAction("/challenges",it);createChallenge=false}
    if(share)ShareDialog(social.snapshot.optJSONArray("challenges")?.objects().orEmpty().filter{it.getBoolean("joined")},{share=false}){vm.socialAction("/shares",it);share=false}
    challengeAction?.let{(id,action)->ChallengeActionDialog(action,social.snapshot.optJSONArray("friends")?.objects().orEmpty().filter{it.getString("status")=="ACCEPTED"},{challengeAction=null}){vm.socialAction("/challenges/$id/$action",it);challengeAction=null}}
}
@Composable private fun Choice(label:String,value:String,options:List<String>,change:(String)->Unit){var open by remember{mutableStateOf(false)};Box{OutlinedButton(onClick={open=true}){Text("$label: ${humanLabel(value)}")};DropdownMenu(open,{open=false}){options.forEach{v->DropdownMenuItem(text={Text(humanLabel(v))},onClick={change(v);open=false})}}}}
@Composable private fun ChallengeDialog(dismiss:()->Unit,save:(JSONObject)->Unit){
    var title by remember{mutableStateOf("")};var metric by remember{mutableStateOf("LEARNING")};var target by remember{mutableStateOf("10")};var visibility by remember{mutableStateOf("FRIENDS")};var days by remember{mutableStateOf("7")}
    AlertDialog(onDismissRequest=dismiss,title={Text("تحدٍ جديد")},text={Column{
        OutlinedTextField(title,{title=it},label={Text("عنوان التحدي")});Choice("المقياس",metric,socialMetrics.map{it.first}){metric=it};OutlinedTextField(target,{target=it},label={Text("هدفك الرقمي")});Choice("الظهور",visibility,listOf("PRIVATE","FRIENDS","PUBLIC")){visibility=it};OutlinedTextField(days,{days=it},label={Text("عدد الأيام")})
    }},confirmButton={Button(onClick={save(JSONObject().put("id",UUID.randomUUID().toString()).put("title",title).put("metric",metric).put("target",target.toDouble()).put("visibility",visibility).put("deadline",System.currentTimeMillis()+days.toLong()*86400000))},enabled=title.isNotBlank() && target.toDoubleOrNull()?.let{it.isFinite() && it>0 && it<=1_000_000}==true && days.toIntOrNull() in 1..365){Text("إنشاء")}},dismissButton={TextButton(onClick=dismiss){Text("إلغاء")}})
}
@Composable private fun ShareDialog(groups:List<JSONObject>,dismiss:()->Unit,save:(JSONObject)->Unit){
    var title by remember{mutableStateOf("")};var summary by remember{mutableStateOf("")};var visibility by remember{mutableStateOf("PRIVATE")};var group by remember{mutableStateOf(groups.firstOrNull()?.getString("id"))}
    AlertDialog(onDismissRequest=dismiss,title={Text("مشاركة مختارة")},text={Column{
        Text("يُرسل هذا النص فقط؛ اختر صلاحية هذه المشاركة.");OutlinedTextField(title,{title=it},label={Text("العنوان")});OutlinedTextField(summary,{summary=it},label={Text("ما الذي تريد مشاركته؟")});Choice("الصلاحية",visibility,listOf("PRIVATE","FRIENDS","PUBLIC","GROUP")){visibility=it}
        if(visibility=="GROUP")Choice("المجموعة",groups.firstOrNull{it.getString("id")==group}?.getString("title") ?: "لا توجد",groups.map{it.getString("title")}){v->group=groups.first{it.getString("title")==v}.getString("id")}
    }},confirmButton={Button(onClick={save(JSONObject().put("id",UUID.randomUUID().toString()).put("title",title).put("summary",summary).put("visibility",visibility).put("group_id",group))},enabled=title.isNotBlank() && summary.isNotBlank() && (visibility!="GROUP" || group!=null)){Text("حفظ للإرسال")}},dismissButton={TextButton(onClick=dismiss){Text("إلغاء")}})
}
@Composable private fun ChallengeActionDialog(action:String,friends:List<JSONObject>,dismiss:()->Unit,save:(JSONObject)->Unit){
    var amount by remember{mutableStateOf("1")};var summary by remember{mutableStateOf("")};var friend by remember{mutableStateOf(friends.firstOrNull()?.getString("id"))}
    AlertDialog(onDismissRequest=dismiss,title={Text(when(action){"join"->"الانضمام";"invite"->"دعوة صديق";else->"تسجيل تقدم"})},text={Column{
        if(action=="invite")Choice("الصديق",friends.firstOrNull{it.getString("id")==friend}?.getString("name") ?: "لا يوجد",friends.map{it.getString("name")}){v->friend=friends.first{it.getString("name")==v}.getString("id")}
        else{OutlinedTextField(amount,{amount=it},label={Text(if(action=="join")"هدفك الشخصي" else "التقدم الإضافي")});if(action=="progress")OutlinedTextField(summary,{summary=it},label={Text("وصف الإنجاز")})}
    }},confirmButton={Button(onClick={val b=JSONObject();when(action){"invite"->b.put("user_id",friend);"join"->b.put("target",amount.toDouble());else->b.put("id",UUID.randomUUID().toString()).put("delta",amount.toDouble()).put("summary",summary)};save(b)},enabled=if(action=="invite")friend!=null else amount.toDoubleOrNull()?.let{it.isFinite() && it>0 && it<=1000000}==true && (action!="progress" || summary.isNotBlank())){Text("تأكيد")}},dismissButton={TextButton(onClick=dismiss){Text("إلغاء")}})
}
