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
import com.android.app.tracing.coroutines.CoroutineTraceName
import com.android.app.tracing.coroutines.createCoroutineTracingContext
import com.android.app.tracing.coroutines.flow.collectTraced
import com.android.app.tracing.coroutines.flow.filterTraced
import com.android.app.tracing.coroutines.flow.flowName
import com.android.app.tracing.coroutines.flow.mapTraced
import com.android.app.tracing.coroutines.flow.shareInTraced
import com.android.app.tracing.coroutines.flow.stateInTraced
import com.android.app.tracing.coroutines.launchInTraced
import com.android.app.tracing.coroutines.launchTraced
import com.android.systemui.Flags.FLAG_COROUTINE_TRACING
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.job
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.plus
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
@EnableFlags(FLAG_COROUTINE_TRACING)
class FlowTracingTest : TestBase() {

    override val scope = CoroutineScope(createCoroutineTracingContext("main", testMode = true))

    @Test
    fun collectFlow_simple() {
        val coldFlow = flow {
            expect("1^main")
            yield()
            expect("1^main")
            emit(42)
            expect("1^main")
            yield()
            expect("1^main")
        }

        runTest(totalEvents = 8) {
            expect("1^main")
            coldFlow.collect {
                assertEquals(42, it)
                expect("1^main")
                yield()
                expect("1^main")
            }
            yield()
            expect("1^main")
        }
    }

    /** @see [CoroutineTracingTest.withContext_incorrectUsage] */
    @Test
    fun collectFlow_incorrectNameUsage() =
        runTest(totalEvents = 8) {
            val coldFlow =
                flow {
                        expect(
                            "1^main"
                        ) // <-- Trace section from before withContext is open until the
                        //                      first suspension
                        yield()
                        expect()
                        emit(42)
                        expect("1^main") // <-- context changed due to context of collector
                        yield()
                        expect()
                    }
                    .flowOn(CoroutineTraceName("new-name")) // <-- BAD, DON'T DO THIS

            expect("1^main")
            coldFlow.collect {
                assertEquals(42, it)
                expect("1^main")
                yield()
                expect("1^main")
            }
            expect() // <-- trace sections erased due to context of emitter
        }

    @Test
    fun collectFlow_correctNameUsage() {
        val coldFlow =
            flow {
                    expect(2, "1^main", "collect:new-name")
                    yield()
                    expect(3, "1^main", "collect:new-name")
                    emit(42)
                    expect(6, "1^main", "collect:new-name")
                    yield()
                    expect(7, "1^main", "collect:new-name")
                }
                .flowName("new-name")
        runTest(totalEvents = 8) {
            expect(1, "1^main")
            coldFlow.collect {
                assertEquals(42, it)
                expect(4, "1^main", "collect:new-name", "emit")
                yield()
                expect(5, "1^main", "collect:new-name", "emit")
            }
            expect(8, "1^main")
        }
    }

    @Test
    fun collectFlow_shareIn() {
        val otherScope =
            CoroutineScope(
                createCoroutineTracingContext("other-scope", testMode = true) +
                    newSingleThreadContext("other-thread") +
                    scope.coroutineContext.job
            )
        val sharedFlow =
            flow {
                    expect("1^new-name")
                    yield()
                    expect("1^new-name")
                    emit(42)
                    expect("1^new-name")
                    yield()
                    expect("1^new-name")
                }
                .shareInTraced("new-name", otherScope, SharingStarted.Eagerly, 5)
        runTest(totalEvents = 7) {
            yield()
            expect("1^main")
            val job =
                launchTraced("launch-for-collect") {
                    expect("1^main:1^launch-for-collect")
                    sharedFlow.collect {
                        assertEquals(42, it)
                        expect("1^main:1^launch-for-collect")
                        yield()
                        expect("1^main:1^launch-for-collect")
                    }
                }
            yield()
            expect("1^main")
            yield()
            job.cancel()
        }
    }

    @Test
    fun collectFlow_launchIn() {
        val coldFlow = flow {
            expectAny(arrayOf("1^main:1^"), arrayOf("1^main:2^launchIn-for-cold"))
            yield()
            expectAny(arrayOf("1^main:1^"), arrayOf("1^main:2^launchIn-for-cold"))
            emit(42)
            expectAny(arrayOf("1^main:1^"), arrayOf("1^main:2^launchIn-for-cold"))
            yield()
            expectAny(arrayOf("1^main:1^"), arrayOf("1^main:2^launchIn-for-cold"))
        }

        runTest(totalEvents = 10) {
            val sharedFlow = coldFlow.shareIn(this, SharingStarted.Eagerly, 5)
            yield()
            expect("1^main")
            coldFlow.launchInTraced("launchIn-for-cold", this)
            val job = sharedFlow.launchIn(this)
            yield()
            expect("1^main")
            job.cancel()
        }
    }

    @Test
    fun collectFlow_launchIn_and_shareIn() {
        val coldFlow = flow {
            expectAny(arrayOf("1^main:1^shareIn-name"), arrayOf("1^main:2^launchIn-for-cold"))
            yield()
            expectAny(arrayOf("1^main:1^shareIn-name"), arrayOf("1^main:2^launchIn-for-cold"))
            emit(42)
            expectAny(arrayOf("1^main:1^shareIn-name"), arrayOf("1^main:2^launchIn-for-cold"))
            yield()
            expectAny(arrayOf("1^main:1^shareIn-name"), arrayOf("1^main:2^launchIn-for-cold"))
        }

        runTest(totalEvents = 12) {
            val sharedFlow = coldFlow.shareInTraced("shareIn-name", this, SharingStarted.Eagerly, 5)
            yield()
            expect("1^main")
            coldFlow
                .onEach { expectAny(arrayOf("1^main:2^launchIn-for-cold")) }
                .launchInTraced("launchIn-for-cold", this)
            val job =
                sharedFlow
                    .onEach { expectAny(arrayOf("1^main:3^launchIn-for-hot")) }
                    .launchInTraced("launchIn-for-hot", this)
            yield()
            expect("1^main")
            job.cancel()
        }
    }

    @Test
    fun collectFlow_badUsageOfCoroutineTraceName_coldFlowOnDifferentThread() {
        val thread1 = newSingleThreadContext("thread-#1")
        // Example of bad usage of CoroutineTraceName. CoroutineTraceName is an internal API.
        // It should only be used during collection, or whenever a coroutine is launched.
        // It should not be used as an intermediate operator.
        val coldFlow =
            flow {
                    expect("1^main:1^fused-name")
                    yield()
                    expect() // <-- empty due to CoroutineTraceName overwriting TraceContextElement
                    emit(21)
                    expect() // <-- empty due to CoroutineTraceName overwriting TraceContextElement
                    yield()
                    expect() // <-- empty due to CoroutineTraceName overwriting TraceContextElement
                }
                // "UNUSED_MIDDLE_NAME" is overwritten during operator fusion because the thread
                // of the flow did not change, meaning no new coroutine needed to be created.
                // However, using CoroutineTraceName("UNUSED_MIDDLE_NAME") is bad because it will
                // replace CoroutineTracingContext on the resumed thread
                .flowOn(CoroutineTraceName("UNUSED_MIDDLE_NAME") + thread1)
                .map {
                    expect("1^main:1^fused-name")
                    it * 2
                }
                .flowOn(CoroutineTraceName("fused-name") + thread1)

        runTest(totalEvents = 9) {
            expect("1^main")
            coldFlow.collect {
                assertEquals(42, it)
                expect("1^main")
                yield()
                expect("1^main")
            }
            expect("1^main")
        }
    }

    @Test
    fun collectFlow_flowOnTraced() {
        val thread1 = newSingleThreadContext("thread-#1")
        val thread2 = newSingleThreadContext("thread-#2")
        // Example of bad usage of CoroutineTraceName. CoroutineTraceName is an internal API.
        // It should only be used during collection, or whenever a coroutine is launched.
        // It should not be used as an intermediate operator.
        val op1 = flow {
            expect("1^main:1^outer-name:1^inner-name")
            yield()
            expect()
            emit(42)
            expect()
            yield()
            expect()
        }
        val op2 = op1.flowOn(CoroutineTraceName("UNUSED_NAME") + thread2)
        val op3 = op2.onEach { expect("1^main:1^outer-name:1^inner-name") }
        val op4 = op3.flowOn(CoroutineTraceName("inner-name") + thread2)
        val op5 = op4.onEach { expect("1^main:1^outer-name") }
        val op6 = op5.flowOn(CoroutineTraceName("outer-name") + thread1)

        runTest(totalEvents = 10) {
            expect("1^main")
            op6.collect {
                assertEquals(42, it)
                expect("1^main")
                yield()
                expect("1^main")
            }
            expect("1^main")
        }
    }

    @Test
    fun collectFlow_coldFlowOnDifferentThread() {
        val thread1 = newSingleThreadContext("thread-#1")
        val coldFlow =
            flow {
                    expect("1^main:1^fused-name")
                    yield()
                    expect("1^main:1^fused-name")
                    emit(21)
                    expect("1^main:1^fused-name")
                    yield()
                    expect("1^main:1^fused-name")
                }
                .map {
                    expect("1^main:1^fused-name")
                    it * 2
                }
                .flowOn(CoroutineTraceName("fused-name") + thread1)

        runTest(totalEvents = 9) {
            expect("1^main")
            coldFlow.collect {
                assertEquals(42, it)
                expect("1^main")
                yield()
                expect("1^main")
            }
            expect("1^main")
        }
    }

    @Test
    fun collectTraced_coldFlowOnDifferentThread() {
        val thread1 = newSingleThreadContext("thread-#1")
        val coldFlow =
            flow {
                    expect("1^main:1^fused-name")
                    yield()
                    expect()
                    emit(21)
                    expect()
                    yield()
                    expect()
                }
                // "UNUSED_MIDDLE_NAME" is overwritten during operator fusion because the thread
                // of the flow did not change, meaning no new coroutine needed to be created.
                .flowOn(CoroutineTraceName("UNUSED_MIDDLE_NAME") + thread1)
                .map {
                    expect("1^main:1^fused-name")
                    it * 2
                }
                .flowOn(CoroutineTraceName("fused-name") + thread1)

        runTest(totalEvents = 9) {
            expect("1^main")
            coldFlow.collectTraced("coldFlow") {
                assertEquals(42, it)
                expect("1^main", "collect:coldFlow", "emit")
                yield()
                expect("1^main", "collect:coldFlow", "emit")
            }
            expect("1^main")
        }
    }

    @Test
    fun collectFlow_nameBeforeDispatcherChange() {
        val thread1 = newSingleThreadContext("thread-#1")
        val coldFlow =
            flow {
                    expect("1^main:1^new-name")
                    yield()
                    expect("1^main:1^new-name")
                    emit(42)
                    expect("1^main:1^new-name")
                    yield()
                    expect("1^main:1^new-name")
                }
                .flowOn(CoroutineTraceName("new-name"))
                .flowOn(thread1)
        runTest(totalEvents = 6) {
            coldFlow.collect {
                assertEquals(42, it)
                expect("1^main")
                yield()
                expect("1^main")
            }
        }
    }

    @Test
    fun collectFlow_nameAfterDispatcherChange() {
        val thread1 = newSingleThreadContext("thread-#1")
        val coldFlow =
            flow {
                    expect("1^main:1^new-name")
                    yield()
                    expect("1^main:1^new-name")
                    emit(42)
                    expect("1^main:1^new-name")
                    yield()
                    expect("1^main:1^new-name")
                }
                .flowOn(thread1)
                .flowOn(CoroutineTraceName("new-name"))
        runTest(totalEvents = 6) {
            coldFlow.collect {
                assertEquals(42, it)
                expect("1^main")
                yield()
                expect("1^main")
            }
        }
    }

    @Test
    fun collectFlow_nameBeforeAndAfterDispatcherChange() {
        val thread1 = newSingleThreadContext("thread-#1")
        val coldFlow =
            flow {
                    expect("1^main:1^new-name")
                    yield()
                    expect("1^main:1^new-name")
                    emit(42)
                    expect("1^main:1^new-name")
                    yield()
                    expect("1^main:1^new-name")
                }
                .flowOn(CoroutineTraceName("new-name"))
                .flowOn(thread1)
                // Unused because, when fused, the previous upstream context takes precedence
                .flowOn(CoroutineTraceName("UNUSED_NAME"))

        runTest {
            coldFlow.collect {
                assertEquals(42, it)
                expect("1^main")
            }
            yield()
            expect("1^main")
        }
    }

    @Test
    fun collectFlow_badNameUsage() {
        val barrier1 = CompletableDeferred<Unit>()
        val barrier2 = CompletableDeferred<Unit>()
        val thread1 = newSingleThreadContext("thread-#1")
        val thread2 = newSingleThreadContext("thread-#2")
        val thread3 = newSingleThreadContext("thread-#3")
        val coldFlow =
            flow {
                    expect("1^main:1^name-for-filter:1^name-for-map:1^name-for-emit")
                    yield()
                    expect("1^main:1^name-for-filter:1^name-for-map:1^name-for-emit")
                    emit(42)
                    barrier1.await()
                    expect("1^main:1^name-for-filter:1^name-for-map:1^name-for-emit")
                    yield()
                    expect("1^main:1^name-for-filter:1^name-for-map:1^name-for-emit")
                    barrier2.complete(Unit)
                }
                .flowOn(CoroutineTraceName("name-for-emit"))
                .flowOn(thread3)
                .map {
                    expect("1^main:1^name-for-filter:1^name-for-map")
                    yield()
                    expect("1^main:1^name-for-filter:1^name-for-map")
                    it
                }
                .flowOn(CoroutineTraceName("name-for-map")) // <-- This only works because the
                //                   dispatcher changes; this behavior should not be relied on.
                .flowOn(thread2)
                .flowOn(CoroutineTraceName("UNUSED_NAME")) // <-- Unused because, when fused, the
                //                                     previous upstream context takes precedence
                .filter {
                    expect("1^main:1^name-for-filter")
                    yield()
                    expect("1^main:1^name-for-filter")
                    true
                }
                .flowOn(CoroutineTraceName("name-for-filter"))
                .flowOn(thread1)

        runTest(totalEvents = 11) {
            expect("1^main")
            coldFlow.collect {
                assertEquals(42, it)
                expect("1^main")
                barrier1.complete(Unit)
            }
            barrier2.await()
            expect("1^main")
        }
    }

    @Test
    fun collectFlow_withIntermediateOperatorNames() {
        val coldFlow =
            flow {
                    expect(
                        2,
                        "1^main",
                        "collect:do-the-assert",
                        "collect:mod-2",
                        "collect:multiply-by-3",
                    )
                    emit(21) // 42 / 2 = 21
                    expect(
                        6,
                        "1^main",
                        "collect:do-the-assert",
                        "collect:mod-2",
                        "collect:multiply-by-3",
                    )
                }
                .mapTraced("multiply-by-3") {
                    expect(
                        3,
                        "1^main",
                        "collect:do-the-assert",
                        "collect:mod-2",
                        "collect:multiply-by-3",
                        "map:transform",
                    )
                    it * 2
                }
                .filterTraced("mod-2") {
                    expect(
                        4,
                        "1^main",
                        "collect:do-the-assert",
                        "collect:mod-2",
                        "collect:multiply-by-3",
                        "map:emit",
                        "filter:predicate",
                    )
                    it % 2 == 0
                }
        runTest(totalEvents = 7) {
            expect(1, "1^main")

            coldFlow.collectTraced("do-the-assert") {
                assertEquals(42, it)
                expect(
                    5,
                    "1^main",
                    "collect:do-the-assert",
                    "collect:mod-2",
                    "collect:multiply-by-3",
                    "map:emit",
                    "filter:emit",
                    "emit",
                )
            }
            expect(7, "1^main")
        }
    }

    @Test
    fun collectFlow_stateIn() {
        val flowThread = newSingleThreadContext("flow-thread")
        val otherScope =
            CoroutineScope(
                createCoroutineTracingContext("other-scope", testMode = true) +
                    newSingleThreadContext("other-thread") +
                    scope.coroutineContext.job
            )
        val coldFlow =
            flowOf(1, 2, 3, 4)
                .onEach { expectAny(arrayOf("1^STATE_1"), arrayOf("2^STATE_2:1^")) }
                .flowOn(flowThread)
                .onEach { expectAny(arrayOf("1^STATE_1"), arrayOf("2^STATE_2")) }

        runTest(totalEvents = 24) {
            expect("1^main")
            val state1 = coldFlow.stateInTraced("STATE_1", otherScope.plus(flowThread))
            val state2 = coldFlow.stateInTraced("STATE_2", otherScope, SharingStarted.Lazily, 42)

            val job1 =
                state1.onEach { expect("1^main:1^LAUNCH_1") }.launchInTraced("LAUNCH_1", this)
            assertEquals(42, state2.value)
            val job2 =
                state2.onEach { expect("1^main:2^LAUNCH_2") }.launchInTraced("LAUNCH_2", this)

            yield()
            delay(100)
            expect("1^main")
            job1.cancel()
            job2.cancel()
        }
    }
}
