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
import com.android.app.tracing.coroutines.flow.collectLatestTraced
import com.android.app.tracing.coroutines.flow.collectTraced
import com.android.app.tracing.coroutines.flow.filterTraced
import com.android.app.tracing.coroutines.flow.flowName
import com.android.app.tracing.coroutines.flow.mapTraced
import com.android.app.tracing.coroutines.flow.transformTraced
import com.android.app.tracing.coroutines.launchTraced
import com.android.systemui.Flags.FLAG_COROUTINE_TRACING
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests behavior of default names, whether that's via stack walking or reflection */
@EnableFlags(FLAG_COROUTINE_TRACING)
class DefaultNamingTest : TestBase() {

    override val extraCoroutineContext: CoroutineContext
        get() = createCoroutineTracingContext("main", includeParentNames = true, strictMode = true)

    fun namedCollectFun() {}

    @Test
    fun collectTraced1() = runTest {
        expect(1, "main:1^")
        flow {
                expect(2, "main:1^", "collect:DefaultNamingTest\$collectTraced1$1$4")
                emit(21) // 21 * 2 = 42
                expect(6, "main:1^", "collect:DefaultNamingTest\$collectTraced1$1$4")
            }
            .mapTraced("2x") {
                expect(
                    3,
                    "main:1^",
                    "collect:DefaultNamingTest\$collectTraced1$1$4",
                    "map:2x:transform",
                )
                it * 2 // 42
            }
            .flowName("UNUSED_NAME") // unused because scope is unchanged
            .filterTraced("mod2") {
                expect(
                    4,
                    "main:1^",
                    "collect:DefaultNamingTest\$collectTraced1$1$4",
                    "map:2x:emit",
                    "filter:mod2:predicate",
                )
                it % 2 == 0 // true
            }
            .collectTraced {
                assertEquals(42, it) // 21 * 2 = 42
                expect(
                    5,
                    "main:1^",
                    "collect:DefaultNamingTest\$collectTraced1$1$4",
                    "map:2x:emit",
                    "filter:mod2:emit",
                    "collect:DefaultNamingTest\$collectTraced1$1$4:emit",
                )
            }
        finish(7, "main:1^")
    }

    @Test
    fun collectTraced2() = runTest {
        expect(1, "main:1^") // top-level scope

        flow {
                expect(2, "main:1^:1^") // child scope used by `collectLatest {}`
                emit(1) // should not get used by collectLatest {}
                expect(6, "main:1^:1^")
                emit(21) // 21 * 2 = 42
                expect(10, "main:1^:1^")
            }
            .filterTraced("mod2") {
                expect(listOf(3, 7), "main:1^:1^", "filter:mod2:predicate")
                it % 2 == 1 // true
            }
            .mapTraced("2x") {
                expect(listOf(4, 8), "main:1^:1^", "filter:mod2:emit", "map:2x:transform")
                it * 2 // 42
            }
            // this name won't be used because it's not passed the scope used by mapLatest{}, which
            // is an internal implementation detail in kotlinx
            .flowName("UNUSED_NAME")
            .collectLatestTraced {
                expectEvent(listOf(5, 9))
                delay(10)
                assertEquals(42, it) // 21 * 2 = 42
                expect(
                    11,
                    "main:1^:1^:2^",
                    "collectLatest:DefaultNamingTest\$collectTraced2$1$4:action",
                )
            }
        finish(12, "main:1^")
    }

    @Test
    fun collectTraced3() = runTest {
        expect(1, "main:1^") // top-level scope

        val sharedFlow =
            flow {
                    expect(2, "main:1^:1^")
                    delay(1)
                    emit(22)
                    expect(3, "main:1^:1^")
                    delay(1)
                    emit(32)
                    expect(4, "main:1^:1^")
                    delay(1)
                    emit(42)
                    expect(5, "main:1^:1^")
                } // there is no API for passing a custom context to the new shared flow, so weg
                // can't pass our custom child name using `nameCoroutine()`
                .shareIn(this, SharingStarted.Eagerly, 4)

        launchTraced("AAAA") {
            sharedFlow.collectLatestTraced {
                delay(10)
                expect(
                    6,
                    "main:1^:2^AAAA:1^:3^",
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
                    "main:1^:3^BBBB:1^:3^",
                    "collectLatest:DefaultNamingTest\$collectTraced3$1$2$1:action",
                )
            }
        }

        delay(50)
        finish(8, "main:1^")
        cancel()
    }

    @Test
    fun collectTraced4() = runTest {
        expect(1, "main:1^")
        flow {
                expect(2, "main:1^", "collect:DefaultNamingTest\$collectTraced4$1$2")
                emit(42)
                expect(4, "main:1^", "collect:DefaultNamingTest\$collectTraced4$1$2")
            }
            .collectTraced {
                assertEquals(42, it)
                expect(
                    3,
                    "main:1^",
                    "collect:DefaultNamingTest\$collectTraced4$1$2",
                    "collect:DefaultNamingTest\$collectTraced4$1$2:emit",
                )
            }
        finish(5, "main:1^")
    }

    @Test
    fun collectTraced5_localFun() {
        fun localFun(value: Int) {
            assertEquals(42, value)
            expect(
                3,
                "main:1^",
                "collect:DefaultNamingTest\$collectTraced5_localFun$1$2",
                "collect:DefaultNamingTest\$collectTraced5_localFun$1$2:emit",
            )
        }
        return runTest {
            expect(1, "main:1^")
            flow {
                    expect(2, "main:1^", "collect:DefaultNamingTest\$collectTraced5_localFun$1$2")
                    emit(42)
                    expect(4, "main:1^", "collect:DefaultNamingTest\$collectTraced5_localFun$1$2")
                }
                .collectTraced(::localFun)
            finish(5, "main:1^")
        }
    }

    fun memberFun(value: Int) {
        assertEquals(42, value)
        expect(
            3,
            "main:1^",
            "collect:DefaultNamingTest\$collectTraced6_memberFun$1$2",
            "collect:DefaultNamingTest\$collectTraced6_memberFun$1$2:emit",
        )
    }

    @Test
    fun collectTraced6_memberFun() = runTest {
        expect(1, "main:1^")
        flow {
                expect(2, "main:1^", "collect:DefaultNamingTest\$collectTraced6_memberFun$1$2")
                emit(42)
                expect(4, "main:1^", "collect:DefaultNamingTest\$collectTraced6_memberFun$1$2")
            }
            .collectTraced(::memberFun)
        finish(5, "main:1^")
    }

    @Test
    fun collectTraced7_topLevelFun() = runTest {
        expect(1, "main:1^")
        flow {
                expect(2, "main:1^", "collect:DefaultNamingTest\$collectTraced7_topLevelFun$1$2")
                emit(42)
                expect(3, "main:1^", "collect:DefaultNamingTest\$collectTraced7_topLevelFun$1$2")
            }
            .collectTraced(::topLevelFun)
        finish(4, "main:1^")
    }

    @Test
    fun collectTraced8_localFlowObject() = runTest {
        expect(1, "main:1^")
        val flowObj =
            object : Flow<Int> {
                override suspend fun collect(collector: FlowCollector<Int>) {
                    expect(
                        2,
                        "main:1^",
                        "collect:DefaultNamingTest\$collectTraced8_localFlowObject$1$1",
                    )
                    collector.emit(42)
                    expect(
                        4,
                        "main:1^",
                        "collect:DefaultNamingTest\$collectTraced8_localFlowObject$1$1",
                    )
                }
            }
        flowObj.collectTraced {
            assertEquals(42, it)
            expect(
                3,
                "main:1^",
                "collect:DefaultNamingTest\$collectTraced8_localFlowObject$1$1",
                "collect:DefaultNamingTest\$collectTraced8_localFlowObject$1$1:emit",
            )
        }
        finish(5, "main:1^")
    }

    @Test
    fun collectTraced9_flowObjectWithClassName() = runTest {
        expect(1, "main:1^")
        FlowWithName(this@DefaultNamingTest).collectTraced {
            assertEquals(42, it)
            expect(
                3,
                "main:1^",
                "collect:DefaultNamingTest\$collectTraced9_flowObjectWithClassName$1$1",
                "collect:DefaultNamingTest\$collectTraced9_flowObjectWithClassName$1$1:emit",
            )
        }
        finish(5, "main:1^")
    }

    @Test
    fun collectTraced10_flowCollectorObjectWithClassName() = runTest {
        expect(1, "main:1^")
        flow {
                expect(2, "main:1^", "collect:FlowCollectorWithName")
                emit(42)
                expect(4, "main:1^", "collect:FlowCollectorWithName")
            }
            .collectTraced(FlowCollectorWithName(this@DefaultNamingTest))
        finish(5, "main:1^")
    }

    @Test
    fun collectTraced11_transform() = runTest {
        expect(1, "main:1^")
        flow {
                expect(2, "main:1^", "collect:COLLECT")
                emit(42)
                expect(7, "main:1^", "collect:COLLECT")
            }
            .transformTraced("TRANSFORM") {
                expect(3, "main:1^", "collect:COLLECT", "TRANSFORM:emit")
                emit(it)
                emit(it * 2)
                emit(it * 4)
            }
            .collectTraced("COLLECT") {
                expect(
                    listOf(4, 5, 6),
                    "main:1^",
                    "collect:COLLECT",
                    "TRANSFORM:emit",
                    "collect:COLLECT:emit",
                )
            }
        finish(8, "main:1^")
    }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    @Test
    fun collectTraced12_badTransform() =
        runTest(
            expectedException = { e ->
                return@runTest e is java.lang.IllegalStateException &&
                    (e.message?.startsWith("Flow invariant is violated") ?: false)
            }
        ) {
            val thread1 = newSingleThreadContext("thread-#1")
            expect(1, "main:1^")
            flow {
                    expect(2, "main:1^", "collect:COLLECT")
                    emit(42)
                    expect(4, "main:1^", "collect:COLLECT")
                }
                .transformTraced("TRANSFORM") {
                    // SHOULD THROW AN EXCEPTION:
                    withContext(thread1) { emit(it * 2) }
                }
                .collectTraced("COLLECT") {}
            finish(5, "main:1^")
        }
}

fun topLevelFun(value: Int) {
    assertEquals(42, value)
}

class FlowWithName(private val test: TestBase) : Flow<Int> {
    override suspend fun collect(collector: FlowCollector<Int>) {
        test.expect(
            2,
            "main:1^",
            "collect:DefaultNamingTest\$collectTraced9_flowObjectWithClassName$1$1",
        )
        collector.emit(42)
        test.expect(
            4,
            "main:1^",
            "collect:DefaultNamingTest\$collectTraced9_flowObjectWithClassName$1$1",
        )
    }
}

class FlowCollectorWithName(private val test: TestBase) : FlowCollector<Int> {
    override suspend fun emit(value: Int) {
        assertEquals(42, value)
        test.expect(
            3,
            "main:1^",
            "collect:FlowCollectorWithName",
            "collect:FlowCollectorWithName:emit",
        )
    }
}
