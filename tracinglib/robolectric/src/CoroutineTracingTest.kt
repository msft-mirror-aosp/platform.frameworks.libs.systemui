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
import com.android.app.tracing.coroutines.TraceContextElement
import com.android.app.tracing.coroutines.coroutineScopeTraced
import com.android.app.tracing.coroutines.createCoroutineTracingContext
import com.android.app.tracing.coroutines.launchTraced
import com.android.app.tracing.coroutines.nameCoroutine
import com.android.app.tracing.coroutines.traceCoroutine
import com.android.app.tracing.coroutines.withContextTraced
import com.android.systemui.Flags.FLAG_COROUTINE_TRACING
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@EnableFlags(FLAG_COROUTINE_TRACING)
class CoroutineTracingTest : TestBase() {

    override val scope = CoroutineScope(createCoroutineTracingContext("main", testMode = true))

    @Test
    fun simpleTraceSection() =
        runTest(finalEvent = 2) {
            expect(1, "1^main")
            delay(1)
            expect(2, "1^main")
        }

    @Test
    fun traceSectionFromScope() =
        runTest(finalEvent = 2) {
            traceCoroutine("hello") {
                expect(1, "1^main", "hello")
                delay(1)
                expect(2, "1^main", "hello")
            }
        }

    @Test
    fun testCoroutineScope() =
        runTest(finalEvent = 2) {
            coroutineScope { expect(1, "1^main") }
            expect(2, "1^main")
        }

    @Test
    fun simpleNestedTraceSection() =
        runTest(finalEvent = 10) {
            expect(1, "1^main")
            delay(1)
            expect(2, "1^main")
            traceCoroutine("hello") {
                expect(3, "1^main", "hello")
                delay(1)
                expect(4, "1^main", "hello")
                traceCoroutine("world") {
                    expect(5, "1^main", "hello", "world")
                    delay(1)
                    expect(6, "1^main", "hello", "world")
                }
                expect(7, "1^main", "hello")
                delay(1)
                expect(8, "1^main", "hello")
            }
            expect(9, "1^main")
            delay(1)
            expect(10, "1^main")
        }

    @Test
    fun simpleLaunch() =
        runTest(finalEvent = 4) {
            expectD(1, "1^main")
            traceCoroutine("hello") {
                expectD(2, "1^main", "hello")
                launch {
                    // "hello" is not passed to child scope
                    expect(4, "1^main:1^")
                }
            }
            expect(3, "1^main")
        }

    @Test
    fun launchWithSuspendingLambda() =
        runTest(finalEvent = 5) {
            val fetchData: suspend () -> String = {
                expect(3, "1^main:1^span-for-launch")
                delay(1L)
                traceCoroutine("span-for-fetchData") {
                    expect(4, "1^main:1^span-for-launch", "span-for-fetchData")
                }
                "stuff"
            }
            expect(1, "1^main")
            launchTraced("span-for-launch") {
                assertEquals("stuff", fetchData())
                expect(5, "1^main:1^span-for-launch")
            }
            expect(2, "1^main")
        }

    @Test
    fun withContext_incorrectUsage() =
        runTest(finalEvent = 4) {
            assertTrue(coroutineContext[CoroutineTraceName] is TraceContextElement)
            expect(1, "1^main")
            withContext(nameCoroutine("inside-withContext")) { // <-- BAD, DON'T DO THIS
                // This is why nameCoroutine() should not be used this way, it overwrites the
                // TraceContextElement. Because it is not a CopyableThreadContextElement, it is
                // not given opportunity to merge with the parent trace context.
                // While we could make CoroutineTraceName a CopyableThreadContextElement, it would
                // add too much overhead to tracing, especially for flows where operation fusion
                // is common.
                assertTrue(coroutineContext[CoroutineTraceName] is CoroutineTraceName)

                // The result of replacing the `TraceContextElement` with `CoroutineTraceName` is
                // that
                // tracing doesn't happen:
                expect(2, "1^main") // <-- Trace section from before withContext is open until the
                //                          first suspension
                delay(1)
                expect(3)
            }
            expect(4, "1^main")
        }

    @Test
    fun withContext_correctUsage() =
        runTest(finalEvent = 4) {
            expect(1, "1^main")
            withContextTraced("inside-withContext", EmptyCoroutineContext) {
                assertTrue(coroutineContext[CoroutineTraceName] is TraceContextElement)
                expect(2, "1^main", "inside-withContext")
                delay(1)
                expect(3, "1^main", "inside-withContext")
            }
            expect(4, "1^main")
        }

    @Test
    fun launchInCoroutineScope() = runTest {
        launchTraced("launch#0") {
            expect("1^main:1^launch#0")
            delay(1)
            expect("1^main:1^launch#0")
        }
        coroutineScopeTraced("span-for-coroutineScope-1") {
            launchTraced("launch#1") {
                expect("1^main:2^launch#1")
                delay(1)
                expect("1^main:2^launch#1")
            }
            launchTraced("launch#2") {
                expect("1^main:3^launch#2")
                delay(1)
                expect("1^main:3^launch#2")
            }
            coroutineScopeTraced("span-for-coroutineScope-2") {
                launchTraced("launch#3") {
                    expect("1^main:4^launch#3")
                    delay(1)
                    expect("1^main:4^launch#3")
                }
                launchTraced("launch#4") {
                    expect("1^main:5^launch#4")
                    delay(1)
                    expect("1^main:5^launch#4")
                }
            }
        }
        launchTraced("launch#5") {
            expect("1^main:6^launch#5")
            delay(1)
            expect("1^main:6^launch#5")
        }
    }

    @Test
    fun namedScopeMerging() = runTest {
        // to avoid race conditions in the test leading to flakes, avoid calling expectD() or
        // delaying before launching (e.g. only call expectD() in leaf blocks)
        expect("1^main")
        launchTraced("A") {
            expect("1^main:1^A")
            traceCoroutine("span") { expectD("1^main:1^A", "span") }
            launchTraced("B") { expectD("1^main:1^A:1^B") }
            launchTraced("C") {
                expect("1^main:1^A:2^C")
                launch { expectD("1^main:1^A:2^C:1^") }
                launchTraced("D") { expectD("1^main:1^A:2^C:2^D") }
                launchTraced("E") {
                    expect("1^main:1^A:2^C:3^E")
                    launchTraced("F") { expectD("1^main:1^A:2^C:3^E:1^F") }
                    expect("1^main:1^A:2^C:3^E")
                }
            }
            launchTraced("G") { expectD("1^main:1^A:3^G") }
        }
        launch { launch { launch { expectD("1^main:2^:1^:1^") } } }
        delay(2)
        launchTraced("H") { launch { launch { expectD("1^main:3^H:1^:1^") } } }
        delay(2)
        launch {
            launch {
                launch {
                    launch { launch { launchTraced("I") { expectD("1^main:4^:1^:1^:1^:1^:1^I") } } }
                }
            }
        }
        delay(2)
        launchTraced("J") {
            launchTraced("K") { launch { launch { expectD("1^main:5^J:1^K:1^:1^") } } }
        }
        delay(2)
        launchTraced("L") {
            launchTraced("M") { launch { launch { expectD("1^main:6^L:1^M:1^:1^") } } }
        }
        delay(2)
        launchTraced("N") {
            launchTraced("O") { launch { launchTraced("D") { expectD("1^main:7^N:1^O:1^:1^D") } } }
        }
        delay(2)
        launchTraced("P") {
            launchTraced("Q") { launch { launchTraced("R") { expectD("1^main:8^P:1^Q:1^:1^R") } } }
        }
        delay(2)
        launchTraced("S") { launchTraced("T") { launch { expectD("1^main:9^S:1^T:1^") } } }
        delay(2)
        launchTraced("U") { launchTraced("V") { launch { expectD("1^main:10^U:1^V:1^") } } }
        delay(2)
        expectD("1^main")
    }

    @Test
    fun launchIntoSelf() =
        runTest(finalEvent = 11) {
            expect(1, "1^main")
            delay(1)
            expect(2, "1^main")
            val reusedNameContext = nameCoroutine("my-coroutine")
            launch(reusedNameContext) {
                expect(3, "1^main:1^my-coroutine")
                delay(1)
                expect(4, "1^main:1^my-coroutine")
                launch(reusedNameContext) {
                    expect(5, "1^main:1^my-coroutine:1^my-coroutine")
                    delay(5)
                    expect(8, "1^main:1^my-coroutine:1^my-coroutine")
                }
                delay(1)
                expect(6, "1^main:1^my-coroutine")
                launch(reusedNameContext) {
                    expect(7, "1^main:1^my-coroutine:2^my-coroutine")
                    delay(7)
                    expect(9, "1^main:1^my-coroutine:2^my-coroutine")
                }
                delay(10)
                expect(10, "1^main:1^my-coroutine")
            }
            launch(reusedNameContext) {
                delay(20)
                expect(11, "1^main:2^my-coroutine")
            }
        }
}
