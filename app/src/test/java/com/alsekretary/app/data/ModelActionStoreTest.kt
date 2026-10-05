package com.alsekretary.app.data

import com.alsekretary.app.domain.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ModelActionStoreTest {
    private lateinit var db: SecretaryDatabase
    private lateinit var store: AssistantStore
    private lateinit var repo: SecretaryRepository
    @Before fun setup(){val c=RuntimeEnvironment.getApplication();c.deleteDatabase("alsekretary.db");db=SecretaryDatabase(c);store=AssistantStore(db);repo=SecretaryRepository(db)}
    @After fun cleanup(){db.close()}
    @Test fun modelProposalWaitsForDurableExactlyOnceApproval(){val reply=ModelProposals.review(ModelAnswer("",listOf(ModelToolCall("create_task","{\"title\":\"قراءة\"}"))),emptyList());store.rememberReply("ذكرني بالقراءة",reply);assertTrue(repo.listTodayTasks().isEmpty());val id=store.proposals().single().id;store.confirm(id);store.confirm(id);assertEquals(1,repo.listTodayTasks().size)}
    @Test fun changedTaskCannotExecuteAnOldModelSnapshot(){val task=repo.addQuickTask("original");val reply=ModelProposals.review(ModelAnswer("",listOf(ModelToolCall("complete_task","{\"task_id\":\"${task.id}\"}"))),listOf(task));repo.saveTask(task.copy(title="changed"),emptySet());store.rememberReply("finish",reply);assertThrows(Exception::class.java){store.confirm(store.proposals().single().id)};assertNotEquals(TaskStatus.DONE,repo.taskById(task.id)!!.status)}
    @Test fun readOnlyRoutingDoesNotLogOrMutateBeforeCompletion(){val reply=store.evaluate("أضف مهمة قراءة");assertNotNull(reply.call);assertTrue(store.messages().isEmpty());assertTrue(store.proposals().isEmpty());assertTrue(repo.listTodayTasks().isEmpty())}
}
