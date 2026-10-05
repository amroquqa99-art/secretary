package com.alsekretary.app.domain
import org.junit.Test
import org.junit.Assert.*
class VoiceConfirmationTest {
    private val proposal=AssistantProposal("id",AssistantCall(AssistantTool.CREATE_TASK,title="قراءة"),"PENDING",null)
    @Test fun exactArabicAndEnglishRequireArming(){val gate=VoiceConfirmation();assertFalse(gate.consume("أكد التنفيذ",proposal,1));gate.arm(proposal,10);assertTrue(gate.consume("أكّد التنفيذ",proposal,11));gate.arm(proposal,20);assertTrue(gate.consume("CONFIRM EXECUTION",proposal,21))}
    @Test fun consumedApprovalCannotReplay(){val gate=VoiceConfirmation();gate.arm(proposal,1);assertTrue(gate.consume("أكد التنفيذ",proposal,2));assertFalse(gate.consume("أكد التنفيذ",proposal,3))}
    @Test fun vagueAffirmationsAndNegationsNeverExecute(){for(text in listOf("نعم","لا تؤكد التنفيذ","yes","do not confirm execution","أكد التنفيذ ثم احذف","")){val gate=VoiceConfirmation();gate.arm(proposal,1);assertFalse(text,gate.consume(text,proposal,2));assertFalse(gate.consume("أكد التنفيذ",proposal,3))}}
    @Test fun changedCancelledMissingAndDifferentActionsReject(){for(p in listOf(proposal.copy(call=proposal.call.copy(title="تغيرت")),proposal.copy(status="CANCELLED"),proposal.copy(id="other"),null)){val gate=VoiceConfirmation();gate.arm(proposal,1);assertFalse(gate.consume("أكد التنفيذ",p,2))}}
    @Test fun expiryAndLifecycleClearInvalidate(){val gate=VoiceConfirmation();gate.arm(proposal,100);assertFalse(gate.consume("أكد التنفيذ",proposal,60100));gate.arm(proposal,200);gate.clear();assertFalse(gate.consume("أكد التنفيذ",proposal,201))}
}
