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

@file:OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)

package com.android.test.tracing.coroutines

import android.platform.test.annotations.EnableFlags
import com.android.app.tracing.coroutines.asyncTraced
import com.android.app.tracing.coroutines.flow.collectLatestTraced
import com.android.app.tracing.coroutines.flow.collectTraced
import com.android.app.tracing.coroutines.flow.filterTraced
import com.android.app.tracing.coroutines.flow.mapTraced
import com.android.app.tracing.coroutines.flow.transformTraced
import com.android.app.tracing.coroutines.launchTraced
import com.android.app.tracing.coroutines.withContextTraced
import com.android.systemui.Flags.FLAG_COROUTINE_TRACING
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests behavior of default names using reflection */
@EnableFlags(FLAG_COROUTINE_TRACING)
class DefaultNamingTest : TestBase() {

    @Test
    fun collectTraced1() {
        val coldFlow =
            flow {
                    expect(
                        2,
                        "1^main",
                        "collect:DefaultNamingTest\$collectTraced1$1$1",
                        "collect:mod2",
                        "collect:2x",
                    )
                    emit(21) // 21 * 2 = 42
                    expect(
                        6,
                        "1^main",
                        "collect:DefaultNamingTest\$collectTraced1$1$1",
                        "collect:mod2",
                        "collect:2x",
                    )
                }
                .mapTraced("2x") {
                    expect(
                        3,
                        "1^main",
                        "collect:DefaultNamingTest\$collectTraced1$1$1",
                        "collect:mod2",
                        "collect:2x",
                        "map:transform",
                    )
                    it * 2 // 42
                }
                .filterTraced("mod2") {
                    expect(
                        4,
                        "1^main",
                        "collect:DefaultNamingTest\$collectTraced1$1$1",
                        "collect:mod2",
                        "collect:2x",
                        "map:emit",
                        "filter:predicate",
                    )
                    it % 2 == 0 // true
                }
        runTest(finalEvent = 7) {
            expect(1, "1^main")
            coldFlow.collectTraced {
                assertEquals(42, it) // 21 * 2 = 42
                expect(
                    5,
                    "1^main",
                    "collect:DefaultNamingTest\$collectTraced1$1$1",
                    "collect:mod2",
                    "collect:2x",
                    "map:emit",
                    "filter:emit",
                    "emit",
                )
            }
            expect(7, "1^main")
        }
    }

    @Test
    fun collectTraced2() {
        val coldFlow =
            flow {
                    expect(
                        2,
                        "1^main:1^",
                        "collect:2x",
                        "collect:mod2",
                    ) // child scope used by `collectLatest {}`
                    emit(1) // should not get used by collectLatest {}
                    expect(6, "1^main:1^", "collect:2x", "collect:mod2")
                    emit(21) // 21 * 2 = 42
                    expect(10, "1^main:1^", "collect:2x", "collect:mod2")
                }
                .filterTraced("mod2") {
                    expect(
                        listOf(3, 7),
                        "1^main:1^",
                        "collect:2x",
                        "collect:mod2",
                        "filter:predicate",
                    )
                    it % 2 == 1 // true
                }
                .mapTraced("2x") {
                    expect(
                        listOf(4, 8),
                        "1^main:1^",
                        "collect:2x",
                        "collect:mod2",
                        "filter:emit",
                        "map:transform",
                    )
                    it * 2 // 42
                }
        runTest(finalEvent = 12) {
            expect(1, "1^main") // top-level scope
            coldFlow.collectLatestTraced {
                expectEvent(listOf(5, 9))
                delay(50)
                assertEquals(42, it) // 21 * 2 = 42
                expect(
                    11,
                    "1^main:1^:2^",
                    "collectLatest:DefaultNamingTest\$collectTraced2$1$1:action",
                )
            }
            expect(12, "1^main")
        }
    }

    @Test
    fun collectTraced3() =
        runTest(finalEvent = 8) {
            expect(1, "1^main") // top-level scope

            val sharedFlow =
                flow {
                        expect(2, "1^main:1^")
                        delay(1)
                        emit(22)
                        expect(3, "1^main:1^")
                        delay(1)
                        emit(32)
                        expect(4, "1^main:1^")
                        delay(1)
                        emit(42)
                        expect(5, "1^main:1^")
                    } // there is no API for passing a custom context to the new shared flow, so weg
                    // can't pass our custom child name using `nameCoroutine()`
                    .shareIn(this, SharingStarted.Eagerly, 4)

            launchTraced("AAAA") {
                sharedFlow.collectLatestTraced {
                    delay(10)
                    expect(
                        6,
                        "1^main:2^AAAA:1^:3^",
                        "collectLatest:DefaultNamingTest\$collectTraced3$1$1$1:action",
                    )
                }
            }
            launchTraced("BBBB") {
                sharedFlow.collectLatestTraced {
                    delay(40)
                    assertEquals(42, it)
                    expect(
                        7,
                        "1^main:3^BBBB:1^:3^",
                        "collectLatest:DefaultNamingTest\$collectTraced3$1$2$1:action",
                    )
                }
            }

            delay(70)
            expect(8, "1^main")
            coroutineContext.job.cancelChildren()
        }

    @Test
    fun collectTraced4() =
        runTest(finalEvent = 5) {
            expect(1, "1^main")
            flow {
                    expect(2, "1^main", "collect:DefaultNamingTest\$collectTraced4$1$2")
                    emit(42)
                    expect(4, "1^main", "collect:DefaultNamingTest\$collectTraced4$1$2")
                }
                .collectTraced {
                    assertEquals(42, it)
                    expect(3, "1^main", "collect:DefaultNamingTest\$collectTraced4$1$2", "emit")
                }
            expect(5, "1^main")
        }

    @Test
    fun collectTraced5_localFun() {
        fun localFun(value: Int) {
            assertEquals(42, value)
            expect(3, "1^main", "collect:DefaultNamingTest\$collectTraced5_localFun$1$2", "emit")
        }
        return runTest(finalEvent = 5) {
            expect(1, "1^main")
            flow {
                    expect(2, "1^main", "collect:DefaultNamingTest\$collectTraced5_localFun$1$2")
                    emit(42)
                    expect(4, "1^main", "collect:DefaultNamingTest\$collectTraced5_localFun$1$2")
                }
                .collectTraced(::localFun)
            expect(5, "1^main")
        }
    }

    fun memberFun(value: Int) {
        assertEquals(42, value)
        expect(3, "1^main", "collect:DefaultNamingTest\$collectTraced6_memberFun$1$2", "emit")
    }

    @Test
    fun collectTraced6_memberFun() =
        runTest(finalEvent = 5) {
            expect(1, "1^main")
            flow {
                    expect(2, "1^main", "collect:DefaultNamingTest\$collectTraced6_memberFun$1$2")
                    emit(42)
                    expect(4, "1^main", "collect:DefaultNamingTest\$collectTraced6_memberFun$1$2")
                }
                .collectTraced(::memberFun)
            expect(5, "1^main")
        }

    @Test
    fun collectTraced7_topLevelFun() =
        runTest(finalEvent = 4) {
            expect(1, "1^main")
            flow {
                    expect(2, "1^main", "collect:DefaultNamingTest\$collectTraced7_topLevelFun$1$2")
                    emit(42)
                    expect(3, "1^main", "collect:DefaultNamingTest\$collectTraced7_topLevelFun$1$2")
                }
                .collectTraced(::topLevelFun)
            expect(4, "1^main")
        }

    @Test
    fun collectTraced8_localFlowObject() =
        runTest(finalEvent = 5) {
            expect(1, "1^main")
            val flowObj =
                object : Flow<Int> {
                    override suspend fun collect(collector: FlowCollector<Int>) {
                        expect(
                            2,
                            "1^main",
                            "collect:DefaultNamingTest\$collectTraced8_localFlowObject$1$1",
                        )
                        collector.emit(42)
                        expect(
                            4,
                            "1^main",
                            "collect:DefaultNamingTest\$collectTraced8_localFlowObject$1$1",
                        )
                    }
                }
            flowObj.collectTraced {
                assertEquals(42, it)
                expect(
                    3,
                    "1^main",
                    "collect:DefaultNamingTest\$collectTraced8_localFlowObject$1$1",
                    "emit",
                )
            }
            expect(5, "1^main")
        }

    @Test
    fun collectTraced9_flowObjectWithClassName() =
        runTest(finalEvent = 5) {
            expect(1, "1^main")
            FlowWithName(this@DefaultNamingTest).collectTraced {
                assertEquals(42, it)
                expect(
                    3,
                    "1^main",
                    "collect:DefaultNamingTest\$collectTraced9_flowObjectWithClassName$1$1",
                    "emit",
                )
            }
            expect(5, "1^main")
        }

    @Test
    fun collectTraced10_flowCollectorObjectWithClassName() =
        runTest(finalEvent = 5) {
            expect(1, "1^main")
            flow {
                    expect(2, "1^main", "collect:FlowCollectorWithName")
                    emit(42)
                    expect(4, "1^main", "collect:FlowCollectorWithName")
                }
                .collectTraced(FlowCollectorWithName(this@DefaultNamingTest))
            expect(5, "1^main")
        }

    @Test
    fun collectTraced11_transform() =
        runTest(finalEvent = 8) {
            expect(1, "1^main")
            flow {
                    expect(2, "1^main", "collect:COLLECT")
                    emit(42)
                    expect(7, "1^main", "collect:COLLECT")
                }
                .transformTraced("TRANSFORM") {
                    expect(3, "1^main", "collect:COLLECT", "TRANSFORM:transform")
                    emit(it)
                    emit(it * 2)
                    emit(it * 4)
                }
                .collectTraced("COLLECT") {
                    expect(
                        listOf(4, 5, 6),
                        "1^main",
                        "collect:COLLECT",
                        "TRANSFORM:transform",
                        "emit",
                    )
                }
            expect(8, "1^main")
        }

    @Test
    fun collectTraced12_badTransform() =
        runTest(
            finalEvent = 2,
            isExpectedException = { e ->
                e is java.lang.IllegalStateException &&
                    (e.message?.startsWith("Flow invariant is violated") ?: false)
            },
            block = {
                val thread1 = bgThread1
                expect(1, "1^main")
                flow {
                        expect(2, "1^main", "collect:COLLECT")
                        emit(42)
                    }
                    .transformTraced("TRANSFORM") {
                        // throws IllegalStateException:
                        withContext(thread1) { emit(it * 2) } // <-- Flow invariant is violated
                    }
                    .collectTraced("COLLECT") {}
            },
        )

    @Test
    fun coroutineBuilder_defaultNames() {
        val localFun: suspend CoroutineScope.() -> Unit = {
            expectAny(
                arrayOf("1^main:4^DefaultNamingTest\$coroutineBuilder_defaultNames\$localFun$1"),
                arrayOf("1^main", "DefaultNamingTest\$coroutineBuilder_defaultNames\$localFun$1"),
                arrayOf("1^main:2^DefaultNamingTest\$coroutineBuilder_defaultNames\$localFun$1"),
            )
        }
        runTest(totalEvents = 6) {
            launchTraced { expect("1^main:1^DefaultNamingTest\$coroutineBuilder_defaultNames$1$1") }
                .join()
            launchTraced(block = localFun).join()
            asyncTraced { expect("1^main:3^DefaultNamingTest\$coroutineBuilder_defaultNames$1$2") }
                .await()
            asyncTraced(block = localFun).await()
            withContextTraced(context = EmptyCoroutineContext) {
                expect("1^main", "DefaultNamingTest\$coroutineBuilder_defaultNames$1$3")
            }
            withContextTraced(context = EmptyCoroutineContext, block = localFun)
        }
    }
}

fun topLevelFun(value: Int) {
    assertEquals(42, value)
}

class FlowWithName(private val test: TestBase) : Flow<Int> {
    override suspend fun collect(collector: FlowCollector<Int>) {
        test.expect(
            2,
            "1^main",
            "collect:DefaultNamingTest\$collectTraced9_flowObjectWithClassName$1$1",
        )
        collector.emit(42)
        test.expect(
            4,
            "1^main",
            "collect:DefaultNamingTest\$collectTraced9_flowObjectWithClassName$1$1",
        )
    }
}

class FlowCollectorWithName(private val test: TestBase) : FlowCollector<Int> {
    override suspend fun emit(value: Int) {
        assertEquals(42, value)
        test.expect(3, "1^main", "collect:FlowCollectorWithName", "emit")
    }
}
