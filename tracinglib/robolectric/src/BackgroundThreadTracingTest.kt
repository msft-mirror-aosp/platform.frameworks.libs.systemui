/*
 * Copyright (C) 2025 The Android Open Source Project
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

@file:OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)

package com.android.test.tracing.coroutines

import android.platform.test.annotations.EnableFlags
import com.android.app.tracing.coroutines.createCoroutineTracingContext
import com.android.app.tracing.coroutines.launchTraced
import com.android.app.tracing.coroutines.withContextTraced
import com.android.systemui.Flags.FLAG_COROUTINE_TRACING
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.plus
import org.junit.Test

@EnableFlags(FLAG_COROUTINE_TRACING)
class BackgroundThreadTracingTest : TestBase() {

    override val scope = CoroutineScope(createCoroutineTracingContext("main", testMode = true))

    @Test
    fun withContext_reentrant() =
        runTest(totalEvents = 11) {
            expect("1^main")
            val thread1 = newSingleThreadContext("thread-#1").asExecutor().asCoroutineDispatcher()
            val bgScope = scope.plus(thread1)
            val otherJob =
                bgScope.launchTraced("AAA") {
                    expect("2^AAA")
                    delay(1)
                    expect("2^AAA")
                    withContextTraced("BBB", Dispatchers.Main.immediate) {
                        expect("2^AAA", "BBB")
                        delay(1)
                        expect("2^AAA", "BBB")
                        withContextTraced("CCC", thread1) {
                            expect("2^AAA", "BBB", "CCC")
                            delay(1)
                            expect("2^AAA", "BBB", "CCC")
                        }
                        expect("2^AAA", "BBB")
                    }
                    expect("2^AAA")
                    delay(1)
                    expect("2^AAA")
                }
            delay(195)
            otherJob.cancel()
            expect("1^main")
        }
}
