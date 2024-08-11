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

package com.android.app.tracing.coroutines

import com.android.app.tracing.coroutines.flow.collect
import com.android.app.tracing.coroutines.flow.collectTraced
import com.android.app.tracing.coroutines.flow.filter
import com.android.app.tracing.coroutines.flow.flowOn
import com.android.app.tracing.coroutines.flow.map
import com.android.app.tracing.coroutines.flow.withTraceName
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner

@RunWith(BlockJUnit4ClassRunner::class)
class FlowTracingTest : TestBase() {
    @Test
    fun stateFlowCollection() = runTestWithTraceContext {
        val state = MutableStateFlow(1)
        val bgThreadPool = newFixedThreadPoolContext(2, "bg-pool")

        // Inefficient fine-grained thread confinement
        val counterThread = newSingleThreadContext("counter-thread")
        var counter = 0
        val incrementCounter: suspend () -> Unit = {
            withContext("increment", counterThread) {
                expectEndsWith("increment")
                counter++
            }
        }

        val helper = ExampleClass(this@FlowTracingTest, incrementCounter)
        val collectJob =
            launch("launch-for-collect", bgThreadPool) {
                expect("launch-for-collect")
                launch {
                    state.collect("state-flow") {
                        expect("launch-for-collect", "state-flow:collect", "state-flow:emit")
                        incrementCounter()
                    }
                }
                launch {
                    state.collectTraced {
                        expect(
                            "launch-for-collect",
                            "com.android.app.tracing.coroutines.FlowTracingTest\$stateFlowCollection$1\$collectJob$1$2:collect",
                            "com.android.app.tracing.coroutines.FlowTracingTest\$stateFlowCollection$1\$collectJob$1$2:emit"
                        )
                        incrementCounter()
                    }
                }
                launch { state.collectTraced(helper::classMethod) }
            }
        val emitJob =
            launch(newSingleThreadContext("emitter thread")) {
                for (n in 2..5) {
                    delay(100)
                    state.value = n
                }
            }
        emitJob.join()
        delay(10)
        collectJob.cancel()
        withContext(counterThread) { assertEquals(15, counter) }
    }

    @Test
    fun flowOnWithTraceName() = runTestWithTraceContext {
        val state =
            flowOf(1, 2, 3, 4)
                .withTraceName("my-flow")
                .flowOn(
                    newSingleThreadContext("flow-thread") +
                        EmptyCoroutineContext +
                        CoroutineName("the-name")
                )
        val bgThreadPool = newFixedThreadPoolContext(2, "bg-pool")
        val collectJob =
            launch("launch-for-collect", bgThreadPool) {
                expect("launch-for-collect")
                launch {
                    state.collect("state-flow") {
                        expect(
                            "launch-for-collect",
                            "state-flow:collect",
                            "flowOn(the-name):collect",
                            "flowOn(the-name):emit",
                            "state-flow:emit"
                        )
                    }
                }
            }
        collectJob.join()
    }

    @Test
    fun mapAndFilter() = runTestWithTraceContext {
        val state =
            flowOf(1, 2, 3, 4)
                .withTraceName("my-flow")
                .map("multiply-by-3") { it * 2 }
                .filter("mod-2") { it % 2 == 0 }
        launch("launch-for-collect") {
                state.collect("my-collect-call") {
                    expect(
                        "launch-for-collect",
                        "my-collect-call:collect",
                        "mod-2:collect",
                        "multiply-by-3:collect",
                        "my-flow:collect",
                        "my-flow:emit",
                        "multiply-by-3:emit",
                        "mod-2:emit",
                        "my-collect-call:emit"
                    )
                }
            }
            .join()
    }
}
