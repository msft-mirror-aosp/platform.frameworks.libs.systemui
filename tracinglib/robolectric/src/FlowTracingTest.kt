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
import com.android.app.tracing.coroutines.createCoroutineTracingContext
import com.android.app.tracing.coroutines.flow.collectTraced
import com.android.app.tracing.coroutines.flow.filterTraced
import com.android.app.tracing.coroutines.flow.flowName
import com.android.app.tracing.coroutines.flow.mapTraced
import com.android.app.tracing.coroutines.launchTraced
import com.android.systemui.Flags.FLAG_COROUTINE_TRACING
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.newSingleThreadContext
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
@EnableFlags(FLAG_COROUTINE_TRACING)
class FlowTracingTest : TestBase() {

    override val extraCoroutineContext: CoroutineContext
        get() = createCoroutineTracingContext("main", includeParentNames = true, strictMode = true)

    @Test
    fun collectFlow1() {
        val coldFlow = flow {
            expect(1, "main:1^")
            delay(1)
            expect(2, "main:1^")
            emit(42)
            expect(4, "main:1^")
            delay(1)
            expect(5, "main:1^")
        }
        runTest {
            coldFlow.collect {
                assertEquals(42, it)
                expect(3, "main:1^")
            }
            delay(1)
            finish(6, "main:1^")
        }
    }

    @Test
    fun collectFlow2() {
        val coldFlow =
            flow {
                    expect(1, "main:1^")
                    delay(1)
                    expect(2)
                    emit(1)
                    expect(5, "main:1^")
                    delay(1)
                    finish(6)
                }
                .flowName("new-name")
        runTest {
            coldFlow.collect {
                expect(3, "main:1^")
                delay(1)
                expect(4, "main:1^")
            }
        }
    }

    @Test
    fun collectFlow3() {
        val thread1 = newSingleThreadContext("thread-#1")
        val coldFlow =
            flow {
                    expect("main:1^:1^new-name")
                    delay(1)
                    expect("main:1^:1^new-name")
                    emit(42)
                    expect("main:1^:1^new-name")
                    delay(1)
                    expect("main:1^:1^new-name")
                }
                .flowName("new-name")
                .flowOn(thread1)
        runTest {
            coldFlow.collect {
                assertEquals(42, it)
                expect("main:1^")
                delay(1)
                expect("main:1^")
            }
        }
    }

    @Test
    fun collectFlow4() {
        val thread1 = newSingleThreadContext("thread-#1")
        val coldFlow =
            flow {
                    expect("main:1^:1^new-name")
                    delay(1)
                    expect("main:1^:1^new-name")
                    emit(42)
                    expect("main:1^:1^new-name")
                    delay(1)
                    expect("main:1^:1^new-name")
                }
                .flowOn(thread1)
                .flowName("new-name")
        runTest {
            coldFlow.collect {
                assertEquals(42, it)
                expect("main:1^")
                delay(1)
                expect("main:1^")
            }
        }
    }

    @Test
    fun collectFlow5() {
        val thread1 = newSingleThreadContext("thread-#1")
        val coldFlow =
            flow {
                    expect("main:1^:1^new-name")
                    delay(1)
                    expect("main:1^:1^new-name")
                    emit(42)
                    expect("main:1^:1^new-name")
                    delay(1)
                    expect("main:1^:1^new-name")
                }
                .flowName("new-name")
                .flowOn(thread1)
                .flowName("UNUSED_NAME")

        runTest {
            coldFlow.collect {
                assertEquals(42, it)
                expect("main:1^")
            }
            delay(1)
            expect("main:1^")
        }
    }

    @Test
    fun collectFlow6() {
        val barrier1 = CompletableDeferred<Unit>()
        val barrier2 = CompletableDeferred<Unit>()
        val thread1 = newSingleThreadContext("thread-#1")
        val thread2 = newSingleThreadContext("thread-#2")
        val thread3 = newSingleThreadContext("thread-#3")
        val coldFlow =
            flow {
                    expect(2, "main:1^:1^name-for-filter:1^name-for-map:1^name-for-emit")
                    delay(1)
                    expect(3, "main:1^:1^name-for-filter:1^name-for-map:1^name-for-emit")
                    emit(42)
                    barrier1.await()
                    expect(9, "main:1^:1^name-for-filter:1^name-for-map:1^name-for-emit")
                    delay(1)
                    expect(10, "main:1^:1^name-for-filter:1^name-for-map:1^name-for-emit")
                    barrier2.complete(Unit)
                }
                .flowName("name-for-emit")
                .flowOn(thread3)
                .map {
                    expect(4, "main:1^:1^name-for-filter:1^name-for-map")
                    delay(1)
                    expect(5, "main:1^:1^name-for-filter:1^name-for-map")
                    it
                }
                .flowName("name-for-map")
                .flowOn(thread2)
                .filter {
                    expect(6, "main:1^:1^name-for-filter")
                    delay(1)
                    expect(7, "main:1^:1^name-for-filter")
                    true
                }
                .flowName("name-for-filter")
                .flowOn(thread1)

        runTest {
            expect(1, "main:1^")
            coldFlow.collect {
                assertEquals(42, it)
                expect(8, "main:1^")
                barrier1.complete(Unit)
            }
            barrier2.await()
            finish(11, "main:1^")
        }
    }

    @Test
    fun collectFlow7_withIntermediateOperatorNames() = runTest {
        expect(1, "main:1^")
        flow {
                expect(2, "main:1^", "collect:do-the-assert")
                emit(21) // 42 / 2 = 21
                expect(6, "main:1^", "collect:do-the-assert")
            }
            .flowName("UNUSED_NAME") // unused because scope is unchanged and operators are fused
            .mapTraced("multiply-by-3") {
                expect(3, "main:1^", "collect:do-the-assert", "map:multiply-by-3:transform")
                it * 2
            }
            .filterTraced("mod-2") {
                expect(
                    4,
                    "main:1^",
                    "collect:do-the-assert",
                    "map:multiply-by-3:emit",
                    "filter:mod-2:predicate",
                )
                it % 2 == 0
            }
            .collectTraced("do-the-assert") {
                assertEquals(42, it)
                expect(
                    5,
                    "main:1^",
                    "collect:do-the-assert",
                    "map:multiply-by-3:emit",
                    "filter:mod-2:emit",
                    "collect:do-the-assert:emit",
                )
            }
        finish(7, "main:1^")
    }

    @Test
    fun collectFlow8_separateJobs() = runTest {
        val flowThread = newSingleThreadContext("flow-thread")
        expect(1, "main:1^")
        val state =
            flowOf(1, 2, 3, 4)
                .transform {
                    expect("main:1^:1^:1^FLOW_NAME")
                    emit(it)
                }
                .flowName("unused-name")
                .transform {
                    expect("main:1^:1^:1^FLOW_NAME")
                    emit(it)
                }
                .flowName("FLOW_NAME")
                .flowOn(flowThread)
                .transform {
                    expect("main:1^:1^")
                    emit(it)
                }
                .stateIn(this)

        launchTraced("LAUNCH_CALL") {
            state.collectTraced("state-flow") {
                expect(2, "main:1^:2^LAUNCH_CALL", "collect:state-flow", "collect:state-flow:emit")
            }
        }

        delay(50)
        finish(3, "main:1^")
        cancel()
    }
}
