package com.alsekretary.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alsekretary.app.domain.*
import java.util.UUID

@Composable
fun BrainScreen(state: MainUiState, onSave: (Note) -> Unit, onDelete: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val filtered = state.notes.filter { query.isBlank() || it.title.contains(query, true) || it.markdown.contains(query, true) }
    val selected = state.notes.firstOrNull { it.id == selectedId }

    if (selected == null && !creating) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row { Column(Modifier.weight(1f)) { Text("العقل", fontSize=28.sp,fontWeight=FontWeight.Bold); Text("ملاحظات مترابطة وMarkdown محلي",color=MaterialTheme.colorScheme.onSurfaceVariant) }; FilledTonalIconButton(onClick={creating=true}){Icon(Icons.Default.NoteAdd,null)} }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(query,{query=it},leadingIcon={Icon(Icons.Default.Search,null)},placeholder={Text("ابحث في المعرفة...")},modifier=Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                filtered.forEach { note -> Card(onClick={selectedId=note.id},shape=RoundedCornerShape(20.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)){Text(note.title,fontWeight=FontWeight.Bold);Text(note.markdown.lineSequence().firstOrNull{it.isNotBlank()}?.take(100).orEmpty(),color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=12.sp)} } }
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            if(selected!=null && !creating) {
                val outgoing=Regex("""\[\[([^\]\n]+)\]\]""").findAll(selected.markdown).map{it.groupValues[1].substringBefore('|')}.toSet()
                val backlinks=state.notes.filter{it.id!=selected.id && (it.markdown.contains("[[${selected.title}]]") || it.markdown.contains("[[${selected.id}]]"))}
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=10.dp)) {
                    outgoing.forEach { target -> val linked=state.notes.firstOrNull{it.title==target || it.id==target};AssistChip(onClick={if(linked!=null)selectedId=linked.id},label={Text(if(linked==null)"رابط مفقود: $target" else "↗ ${linked.title}")}) }
                    backlinks.forEach { note -> AssistChip(onClick={selectedId=note.id},label={Text("↙ ${note.title}")}) }
                }
            }
            Box(Modifier.weight(1f)) { MarkdownEditor(initial = if(creating) null else selected, onBack={creating=false;selectedId=null}, onSave={note->onSave(note);creating=false;selectedId=note.id}, onDelete=if(selected!=null){{onDelete(selected.id);selectedId=null}} else null) }
        }
    }
}

@Composable
private fun MarkdownEditor(initial: Note?, onBack: () -> Unit, onSave: (Note) -> Unit, onDelete: (() -> Unit)?) {
    var title by remember(initial?.id) { mutableStateOf(initial?.title ?: "ملاحظة جديدة") }
    var body by remember(initial?.id) { mutableStateOf(TextFieldValue(initial?.markdown.orEmpty())) }
    var previewOnly by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(8.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick=onBack){Icon(Icons.Default.ArrowForward,"رجوع")}
            OutlinedTextField(title,{title=it},singleLine=true,modifier=Modifier.weight(1f),placeholder={Text("عنوان الملاحظة")})
            IconButton(onClick={previewOnly=!previewOnly}){Icon(if(previewOnly)Icons.Default.Edit else Icons.Default.Visibility,null)}
            IconButton(onClick={onSave(Note(initial?.id?:UUID.randomUUID().toString(),title.trim().ifBlank{"بدون عنوان"},body.text,initial?.createdAt?:now,now))}){Icon(Icons.Default.Save,null,tint=MaterialTheme.colorScheme.primary)}
            if(onDelete!=null)IconButton(onClick=onDelete){Icon(Icons.Default.DeleteOutline,null)}
        }
        if(!previewOnly) MarkdownToolbar(body) { body = it }
        Row(Modifier.fillMaxSize().padding(horizontal=10.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(!previewOnly) {
                OutlinedTextField(body,{body=it},modifier=Modifier.weight(1f).fillMaxHeight(),textStyle=LocalTextStyle.current.copy(fontFamily=FontFamily.Monospace,fontSize=14.sp),placeholder={Text("اكتب Markdown...")})
            }
            Column(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)).padding(14.dp).verticalScroll(rememberScrollState())) {
                Text("معاينة مباشرة",fontSize=11.sp,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                MarkdownPreview(body.text)
            }
        }
    }
}

@Composable
private fun MarkdownToolbar(value: TextFieldValue, onChange: (TextFieldValue) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=10.dp),horizontalArrangement=Arrangement.spacedBy(5.dp)) {
        Tool("H1") { onChange(prefixAtLine(value,"# ")) }
        Tool("B") { onChange(wrapSelection(value,"**","**")) }
        Tool("✓") { onChange(prefixAtLine(value,"- [ ] ")) }
        Tool("•") { onChange(prefixAtLine(value,"- ")) }
        Tool("Table") { onChange(insert(value,"| العمود 1 | العمود 2 |\n| --- | --- |\n| قيمة | قيمة |")) }
        Tool("Code") { onChange(wrapSelection(value,"```text\n","\n```")) }
        Tool("Math") { onChange(wrapSelection(value,"$$\n","\n$$")) }
        Tool("[[Link]]") { onChange(wrapSelection(value,"[[","]]")) }
        Tool("Callout") { onChange(prefixAtLine(value,"> [!NOTE]\n> ")) }
    }
}

@Composable private fun Tool(label:String,onClick:()->Unit){AssistChip(onClick=onClick,label={Text(label,fontSize=11.sp)})}

@Composable
private fun MarkdownPreview(markdown: String) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    if(blocks.isEmpty()) Text("ستظهر المعاينة هنا.",color=MaterialTheme.colorScheme.onSurfaceVariant)
    blocks.forEach { block ->
        when(block) {
            is MarkdownBlock.Heading -> Text(block.text,fontSize=(28-block.level*2).coerceAtLeast(16).sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(vertical=4.dp))
            is MarkdownBlock.Paragraph -> Text(inlineMarkdown(block.text),modifier=Modifier.padding(vertical=3.dp))
            is MarkdownBlock.Bullet -> Row(Modifier.padding(vertical=2.dp)){Text(if(block.checked==true)"☑ " else if(block.checked==false)"☐ " else "• ");Text(inlineMarkdown(block.text))}
            is MarkdownBlock.Quote -> Text(block.text,modifier=Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary.copy(.08f),RoundedCornerShape(10.dp)).padding(10.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
            is MarkdownBlock.Code -> Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background,RoundedCornerShape(12.dp)).padding(10.dp)){block.language?.let{Text(it,fontSize=10.sp,color=MaterialTheme.colorScheme.primary)};Text(block.code,fontFamily=FontFamily.Monospace,fontSize=12.sp)}
            is MarkdownBlock.Math -> Text(block.expression,fontFamily=FontFamily.Serif,fontSize=18.sp,modifier=Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary.copy(.06f),RoundedCornerShape(12.dp)).padding(12.dp))
            is MarkdownBlock.Table -> Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background,RoundedCornerShape(12.dp)).padding(8.dp)){block.rows.forEachIndexed{idx,row->Row(Modifier.fillMaxWidth()){row.forEach{cell->Text(cell,Modifier.weight(1f).padding(5.dp),fontWeight=if(idx==0)FontWeight.Bold else FontWeight.Normal,fontSize=12.sp)}};if(idx==0)HorizontalDivider()}}
        }
    }
}

private fun stripInline(text:String)=text.replace("**","").replace("__","")
private fun insert(value:TextFieldValue,text:String):TextFieldValue { val s=value.selection.min;val n=value.text.substring(0,s)+text+value.text.substring(value.selection.max);return TextFieldValue(n,TextRange(s+text.length)) }
private fun wrapSelection(value:TextFieldValue,prefix:String,suffix:String):TextFieldValue { val a=value.selection.min;val b=value.selection.max;val selected=value.text.substring(a,b);val n=value.text.substring(0,a)+prefix+selected+suffix+value.text.substring(b);return TextFieldValue(n,TextRange(a+prefix.length,a+prefix.length+selected.length)) }
private fun prefixAtLine(value:TextFieldValue,prefix:String):TextFieldValue { val start=value.text.lastIndexOf('\n',(value.selection.start-1).coerceAtLeast(0)).let{if(it<0)0 else it+1};val n=value.text.substring(0,start)+prefix+value.text.substring(start);return TextFieldValue(n,TextRange(value.selection.start+prefix.length)) }

private fun inlineMarkdown(text: String) = buildAnnotatedString {
    val regex=Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|\\*(.+?)\\*")
    var cursor=0
    regex.findAll(text).forEach { m ->
        append(text.substring(cursor,m.range.first))
        val bold=m.groupValues[1].ifBlank{m.groupValues[2]}
        pushStyle(if(bold.isNotBlank()) SpanStyle(fontWeight=FontWeight.Bold) else SpanStyle(fontStyle=FontStyle.Italic))
        append(bold.ifBlank{m.groupValues[3]});pop();cursor=m.range.last+1
    }
    append(text.substring(cursor))
}
