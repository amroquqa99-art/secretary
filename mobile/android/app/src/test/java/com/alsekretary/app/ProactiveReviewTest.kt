package com.alsekretary.app

import com.alsekretary.app.reminders.ProactiveReview
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ProactiveReviewTest {
    @Test fun stopCannotReturnBeforeAnAlreadyCommittedDeliveryFinishes() {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("proactive-review",0).edit().clear().commit()
        val review=ProactiveReview(context);review.setEnabled(true)
        val canceled=java.util.concurrent.atomic.AtomicBoolean()
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val attempted=java.util.concurrent.CountDownLatch(1)
        val executor=java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val delivery=executor.submit<Boolean>{review.deliver("2026-10-10",{canceled.get()}){entered.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS))}}
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            val stop=executor.submit { attempted.countDown();ProactiveReview.cancelDelivery(canceled) }
            assertTrue(attempted.await(5,java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(stop.isDone);assertFalse(canceled.get())
            release.countDown();assertTrue(delivery.get(5,java.util.concurrent.TimeUnit.SECONDS));stop.get(5,java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(canceled.get())
            assertFalse(review.deliver("2026-10-11",{canceled.get()}){fail("Delivered after stop returned")})
        } finally { release.countDown();executor.shutdownNow() }
    }

    @Test fun disableAndCancellationAcrossInstancesPreventLateOrDuplicateDelivery() {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("proactive-review",0).edit().clear().commit()
        val first=ProactiveReview(context);val second=ProactiveReview(context);var sent=0
        first.setEnabled(true);second.setEnabled(false)
        assertFalse(first.deliver("2026-10-09",{false}){sent++})
        first.setEnabled(true)
        assertFalse(first.deliver("2026-10-09",{true}){sent++})
        assertTrue(first.deliver("2026-10-09",{false}){sent++})
        assertFalse(second.deliver("2026-10-09",{false}){sent++})
        assertEquals(1,sent)
    }
}
