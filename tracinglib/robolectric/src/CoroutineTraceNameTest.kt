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
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.launch
import org.junit.Test

@EnableFlags(FLAG_COROUTINE_TRACING)
class CoroutineTraceNameTest : TestBase() {

    // BAD: CoroutineTraceName should not be installed on the root like this:
    override val extraContext: CoroutineContext by lazy { CoroutineTraceName("MainName") }

    @Test
    fun nameMergedWithTraceContext() = runTest {
        expectD()
        val otherTraceContext =
            createCoroutineTracingContext("TraceContextName", testMode = true)
                as TraceContextElement
        // MainName is never used. It is overwritten by the CoroutineTracingContext:
        launch(otherTraceContext) { expectD("1^TraceContextName") }
        expectD()
        launch(otherTraceContext) { expectD("2^TraceContextName") }
        launch { expectD() }
    }
}
