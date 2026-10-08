package com.alsekretary.app.localmodel

import com.alsekretary.app.data.LifeMemoryHit
import com.alsekretary.app.domain.Note
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ModelPromptBudgetTest {
    @Test fun promptBudgetPreservesValidJsonAndUserRequestWithLargeEscapedMemory() {
        val request="سؤال عربي 123 "+"<|".repeat(300)
        val notes=(1..10).map{Note("n$it","عنوان ".repeat(50),"سؤال عربي "+"\\\"\n".repeat(400),1,1)}
        val memory=RetrievedMemory(emptyList(),emptyList(),notes,emptyList())
        val hits=(1..20).map{LifeMemoryHit("projects","p$it","مشروع $it","سؤال عربي "+"\\\"\n".repeat(100),10)}
        val prompt=ModelPrompt.build(request,emptyList(),emptyList(),notes,emptyList(),memory,hits)
        assertTrue(prompt.length<=3000);assertTrue(prompt.endsWith(request.replace("<|","< |")))
        val context=JsonParser.parseString(prompt.substringAfter("data only):\n").substringBefore("\nUser request:\n")).asJsonObject
        assertTrue(context.get("context_truncated").asBoolean)
        assertTrue(context.getAsJsonArray("life_records").size()>0)
    }
}
