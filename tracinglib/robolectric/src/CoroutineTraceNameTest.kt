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
import com.android.app.tracing.coroutines.createCoroutineTracingContext
import com.android.systemui.Flags.FLAG_COROUTINE_TRACING
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Test

@EnableFlags(FLAG_COROUTINE_TRACING)
class CoroutineTraceNameTest : TestBase() {

    // BAD: CoroutineTraceName should not be installed on the root like this:
    override val scope = CoroutineScope(CoroutineTraceName("MainName"))

    @Test
    fun nameMergedWithTraceContext() = runTest {
        expectD()
        val traceContext1 =
            createCoroutineTracingContext("trace1", testMode = true) as TraceContextElement
        // MainName is never used. It is overwritten by the CoroutineTracingContext:
        launch(traceContext1) { expectD("1^trace1") }
        expectD()
        launch(traceContext1) { expectD("2^trace1") }
        launch { expectD() }
    }
}
