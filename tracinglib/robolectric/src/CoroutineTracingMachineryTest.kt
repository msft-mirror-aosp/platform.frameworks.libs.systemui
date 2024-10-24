/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.test.tracing.coroutines

import android.platform.test.annotations.EnableFlags
import com.android.app.tracing.coroutines.TraceContextElement
import com.android.app.tracing.coroutines.TraceData
import com.android.app.tracing.coroutines.createCoroutineTracingContext
import com.android.app.tracing.coroutines.launchTraced
import com.android.app.tracing.coroutines.traceCoroutine
import com.android.app.tracing.coroutines.traceThreadLocal
import com.android.systemui.Flags.FLAG_COROUTINE_TRACING
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
@EnableFlags(FLAG_COROUTINE_TRACING)
class CoroutineTracingMachineryTest : TestBase() {

    @Test
    fun missingTraceContextObjects() = runTest {
        val channel = Channel<Int>()
        val context1 = newSingleThreadContext("thread-#1")
        val context2 =
            newSingleThreadContext("thread-#2") +
                createCoroutineTracingContext("main", includeParentNames = true, strictMode = true)

        launchTraced("launch#1", context1) {
            expect()
            channel.receive()
            traceCoroutine("span-1") { expect() }
            expect()
            launchTraced("launch#2", context2) {
                // "launch#2" is not traced because TraceContextElement was installed too
                // late; it is not part of the scope that was launched (i.e., the `this` in
                // `this.launch {}`)
                expect("main:1^")
                channel.receive()
                traceCoroutine("span-2") { expect("main:1^", "span-2") }
                expect("main:1^")
                launch {
                    // ...it won't appear in the child scope either because in
                    // launchTraced("string"), it
                    // adds: `CoroutineTraceName` + `TraceContextElement`. This demonstrates why
                    // it is important to only use `TraceContextElement` in the root scope. In this
                    // case, the `TraceContextElement`  overwrites the name, so the name is dropped.
                    // Tracing still works with a default, empty name, however.
                    expect("main:1^:1^")
                }
            }
            expect()
        }
        expect()

        channel.send(1)
        channel.send(2)

        launch(context1) { expect() }
        launch(context2) { expect("main:2^") }
    }

    /**
     * Tests interleaving:
     * ```
     * Thread #1 | [updateThreadContext]....^              [restoreThreadContext]
     * --------------------------------------------------------------------------------------------
     * Thread #2 |                           [updateThreadContext]...........^[restoreThreadContext]
     * ```
     *
     * This test checks for issues with concurrent modification of the trace state. For example, the
     * test should fail if [TraceData.endAllOnThread] uses the size of the slices array as follows
     * instead of using the ThreadLocal count:
     * ```
     * class TraceData {
     *   ...
     *   fun endAllOnThread() {
     *     repeat(slices.size) {
     *       // THIS WOULD BE AN ERROR. If the thread is slow, the TraceData object could have been
     *       // modified by another thread
     *       endSlice()
     *     }
     *   ...
     *   }
     * }
     * ```
     */
    @Test
    fun coroutineMachinery() {
        assertNull(traceThreadLocal.get())

        val thread1ResumptionPoint = CyclicBarrier(2)
        val thread1SuspensionPoint = CyclicBarrier(2)

        val thread1 = Executors.newSingleThreadExecutor()
        val thread2 = Executors.newSingleThreadExecutor()
        val slicesForThread1 = listOf("a", "c", "e", "g")
        val slicesForThread2 = listOf("b", "d", "f", "h")
        var failureOnThread1: Error? = null
        var failureOnThread2: Error? = null

        val expectedTraceForThread1 = arrayOf("1:a", "2:b", "1:c", "2:d", "1:e", "2:f", "1:g")

        val traceContext =
            createCoroutineTracingContext("main", includeParentNames = true, strictMode = true)
                as TraceContextElement
        thread1.execute {
            try {
                slicesForThread1.forEachIndexed { index, sliceName ->
                    assertNull(traceThreadLocal.get())
                    val oldTrace = traceContext.updateThreadContext(EmptyCoroutineContext)
                    // await() AFTER updateThreadContext, thus thread #1 always resumes the
                    // coroutine before thread #2
                    assertSame(traceThreadLocal.get(), traceContext.contextTraceData)

                    // coroutine body start {
                    (traceThreadLocal.get() as TraceData).beginSpan("1:$sliceName")

                    // At the end, verify the interleaved trace sections look correct:
                    if (index == slicesForThread1.size - 1) {
                        expect(*expectedTraceForThread1)
                    }

                    // simulate a slow thread, wait to call restoreThreadContext until after thread
                    // A
                    // has resumed
                    thread1SuspensionPoint.await(3, TimeUnit.SECONDS)
                    Thread.sleep(500)
                    // } coroutine body end

                    traceContext.restoreThreadContext(EmptyCoroutineContext, oldTrace)
                    thread1ResumptionPoint.await(3, TimeUnit.SECONDS)
                    assertNull(traceThreadLocal.get())
                }
            } catch (e: Error) {
                failureOnThread1 = e
            }
        }

        val expectedTraceForThread2 =
            arrayOf("1:a", "2:b", "1:c", "2:d", "1:e", "2:f", "1:g", "2:h")
        thread2.execute {
            try {
                slicesForThread2.forEachIndexed { i, n ->
                    assertNull(traceThreadLocal.get())
                    thread1SuspensionPoint.await(3, TimeUnit.SECONDS)

                    val oldTrace = traceContext.updateThreadContext(EmptyCoroutineContext)

                    // coroutine body start {
                    (traceThreadLocal.get() as TraceData).beginSpan("2:$n")

                    // At the end, verify the interleaved trace sections look correct:
                    if (i == slicesForThread2.size - 1) {
                        expect(*expectedTraceForThread2)
                    }
                    // } coroutine body end

                    traceContext.restoreThreadContext(EmptyCoroutineContext, oldTrace)
                    thread1ResumptionPoint.await(3, TimeUnit.SECONDS)
                    assertNull(traceThreadLocal.get())
                }
            } catch (e: Error) {
                failureOnThread2 = e
            }
        }

        thread1.shutdown()
        thread1.awaitTermination(5, TimeUnit.SECONDS)
        thread2.shutdown()
        thread2.awaitTermination(5, TimeUnit.SECONDS)

        assertNull("Failure executing coroutine on thread-#1.", failureOnThread1)
        assertNull("Failure executing coroutine on thread-#2.", failureOnThread2)
    }

    @Test
    fun traceContextIsCopied() = runTest {
        expect()
        val traceContext =
            createCoroutineTracingContext("main", includeParentNames = true, strictMode = true)
                as TraceContextElement
        withContext(traceContext) {
            // Not the same object because it should be copied into the current context
            assertNotSame(traceThreadLocal.get(), traceContext.contextTraceData)
            // slices is lazily created, so it should be null:
            assertNull((traceThreadLocal.get() as TraceData).slices)
            assertNull(traceContext.contextTraceData?.slices)
            expect("main:1^")
            traceCoroutine("hello") {
                assertNotSame(traceThreadLocal.get(), traceContext.contextTraceData)
                assertArrayEquals(
                    arrayOf("hello"),
                    (traceThreadLocal.get() as TraceData).slices?.toArray(),
                )
                assertNull(traceContext.contextTraceData?.slices)
            }
            assertNotSame(traceThreadLocal.get(), traceContext.contextTraceData)
            // Because slices is lazily created, it will no longer be null after it was used to
            // trace "hello", but this time it will be empty
            assertArrayEquals(arrayOf(), (traceThreadLocal.get() as TraceData).slices?.toArray())
            assertNull(traceContext.contextTraceData?.slices)
            expect("main:1^")
        }
        expect()
    }
}
