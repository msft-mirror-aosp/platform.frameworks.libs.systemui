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

import android.os.Trace
import com.android.app.tracing.coroutines.flow.collectTraced
import com.android.app.tracing.coroutines.flow.filterTraced as filter
import com.android.app.tracing.coroutines.flow.flowName
import com.android.app.tracing.coroutines.flow.mapTraced as map
import com.android.app.tracing.coroutines.launchTraced as launch
import com.example.tracing.demo.FixedThreadA
import com.example.tracing.demo.FixedThreadB
import com.example.tracing.demo.FixedThreadC
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flowOn

@Singleton
class CollectFlow
@Inject
constructor(
    @FixedThreadA private var dispatcherA: CoroutineDispatcher,
    @FixedThreadB private var dispatcherB: CoroutineDispatcher,
    @FixedThreadC private val dispatcherC: CoroutineDispatcher,
) : AsyncExperiment {
    override val description: String = "Collect a cold flow with intermediate operators"

    private val coldFlow =
        coldCounterFlow("count", 4)
            .flowName("original-cold-flow-scope")
            .flowOn(dispatcherA)
            .filter("evens") {
                forceSuspend("B", 20)
                it % 2 == 0
            }
            .flowOn(dispatcherB)
            .flowName("even-filter-scope")
            .map("3x") {
                forceSuspend("C", 15)
                it * 3
            }
            .flowOn(dispatcherC)

    override suspend fun start(): Unit = coroutineScope {
        launch(context = dispatcherA) {
            coldFlow.collectTraced {
                Trace.instant(Trace.TRACE_TAG_APP, "got: $it")
                forceSuspend("A2", 60)
            }
        }
    }
}
