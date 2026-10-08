package com.alsekretary.app.domain

/** Volatile, single-use permission bound to the exact action read aloud. */
class VoiceConfirmation {
    private data class Challenge(val id: String,val call: AssistantCall,val expires: Long)
    private var challenge: Challenge?=null
    fun arm(proposal: AssistantProposal,now: Long) {
        require(proposal.status=="PENDING")
        challenge=Challenge(proposal.id,proposal.call,now+60_000)
    }
    fun clear() { challenge=null }
    fun consume(text: String,proposal: AssistantProposal?,now: Long): Boolean {
        val c=challenge;challenge=null
        return c!=null && now<c.expires && proposal?.status=="PENDING" && proposal.id==c.id && proposal.call==c.call &&
            LocalAssistant.normalize(text).trimEnd('.','!','؟','?','،',' ') in setOf("اكد التنفيذ","confirm execution")
    }
}
