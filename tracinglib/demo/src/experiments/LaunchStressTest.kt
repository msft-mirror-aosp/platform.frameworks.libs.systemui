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
package com.example.tracing.demo.experiments

import com.android.app.tracing.coroutines.createCoroutineTracingContext
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.app.tracing.coroutines.traceCoroutine
import com.example.tracing.demo.FixedPool
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

@Singleton
class LaunchStressTest
@Inject
constructor(@FixedPool private var fixedPoolDispatcher: CoroutineDispatcher) : BlockingExperiment {

    override val description: String = "Simultaneous launch{} calls on different threads"

    override fun start() {
        val scope =
            CoroutineScope(
                fixedPoolDispatcher +
                    createCoroutineTracingContext(
                        name = "ParentScope",
                        walkStackForDefaultNames = false,
                    )
            )

        repeat(1000) { i ->
            scope.launch("launch-A") { traceCoroutine("A:$i") { delay(1) } }
            scope.launch("launch-B") { traceCoroutine("B:$i") { delay(1) } }
            scope.launch("launch-C") { traceCoroutine("C:$i") { delay(1) } }
        }
    }
}
