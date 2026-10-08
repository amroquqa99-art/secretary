package com.alsekretary.app.domain

import org.junit.Test
import org.junit.Assert.*

class ModelProposalsTest {
    private val task=Task("id","قراءة",null,TaskStatus.PLANNED,null,null,30,null,null)
    private fun review(name: String,args: String)=ModelProposals.review(ModelAnswer("تم التنفيذ",listOf(ModelToolCall(name,args))),listOf(task))
    @Test fun createsOnlyTypedProposalsAndReplacesClaimsOfExecution(){val r=review("create_task","{\"title\":\" قراءة كتاب \"}");assertEquals("قراءة كتاب",r.call!!.title);assertEquals(AssistantTool.CREATE_TASK,r.call!!.tool);assertFalse(r.text.contains("تم التنفيذ"));assertTrue(r.text.contains("لم ينفّذ"))}
    @Test fun fingerprintComesFromTrustedSnapshot(){val r=review("complete_task","{\"task_id\":\"id\"}");assertEquals(task.toString(),r.call!!.expected);assertEquals(task.title,r.call!!.title)}
    @Test fun rejectsInventedClosedOrAmbiguousTargets(){assertThrows(Exception::class.java){review("complete_task","{\"task_id\":\"missing\"}")};for(tasks in listOf(listOf(task.copy(status=TaskStatus.DONE)),listOf(task,task))){assertThrows(Exception::class.java){ModelProposals.review(ModelAnswer("",listOf(ModelToolCall("start_focus","{\"task_id\":\"id\"}"))),tasks)}}}
    @Test fun rejectsExtraFieldsDuplicateKeysAndNonStringValues(){for(args in listOf("{\"title\":\"a\",\"expected\":\"fake\"}","{\"title\":\"a\",\"title\":\"b\"}","{\"title\":12}","{\"title\":null}","{\"title\":[]}","{\"title\":\"a\"} {}","{'title':'a'}","{\"title\":\"\"}")){assertThrows(args,Exception::class.java){review("create_task",args)}}}
    @Test fun rejectsUnsupportedToolsAndMultipleActions(){assertThrows(Exception::class.java){review("delete_all","{}")};assertThrows(Exception::class.java){ModelProposals.review(ModelAnswer("",List(2){ModelToolCall("create_task","{\"title\":\"a\"}")}),emptyList())}}
    @Test fun notesRequireBoundedTitleAndBody(){val r=review("create_note","{\"title\":\"note\",\"body\":\"text\"}");assertEquals("text",r.call!!.body);assertThrows(Exception::class.java){review("create_note","{\"title\":\"note\",\"body\":\"\"}")};assertThrows(Exception::class.java){review("create_task","{\"title\":\"${"a".repeat(301)}\"}")}}
    @Test fun freeTextIsClearlyLabeledAndDoesNotContainAnAction(){val r=ModelProposals.review(ModelAnswer("اقتراح دراسة"),emptyList());assertNull(r.call);assertTrue(r.text.contains("اقتراح دراسة"))}
    @Test fun readOnlyRequestRejectsUnexpectedToolCall(){assertThrows(Exception::class.java){ModelProposals.review(ModelAnswer("",listOf(ModelToolCall("create_task","{\"title\":\"a\"}"))),emptyList(),false)}}
    @Test fun unknownRequestsAllowModelButRecognizedCommandsKeepValidators(){assertFalse(LocalAssistant.respond("اقترح طرقاً للدراسة",emptyList(),emptyList()).handled);assertTrue(LocalAssistant.respond("احفظ ملاحظة عنوان",emptyList(),emptyList()).handled)}
}
