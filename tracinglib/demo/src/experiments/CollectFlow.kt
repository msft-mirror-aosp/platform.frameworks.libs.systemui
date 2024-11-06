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

import com.android.app.tracing.coroutines.flow.withTraceName
import com.android.app.tracing.coroutines.launch
import com.android.app.tracing.coroutines.traceCoroutine
import com.example.tracing.demo.FixedThreadA
import com.example.tracing.demo.FixedThreadB
import com.example.tracing.demo.FixedThreadC
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

@Singleton
class CollectFlow
@Inject
constructor(
    @FixedThreadA private var dispatcherA: CoroutineDispatcher,
    @FixedThreadB private var dispatcherB: CoroutineDispatcher,
    @FixedThreadC private val dispatcherC: CoroutineDispatcher,
) : Experiment {
    override val description: String = "Collect a cold flow with intermediate operators"

    private val coldFlow =
        flow {
                for (n in 0..4) {
                    traceCoroutine("delay-and-emit for $n") {
                        // use artificial delays to make the trace more readable for demo
                        forceSuspend("A", 250)
                        emit(n)
                    }
                }
            }
            .withTraceName("flowOf numbers")
            .filter {
                forceSuspend("B", 250)
                it % 2 == 0
            }
            .flowOn(dispatcherA)
            .withTraceName("filter for even")
            .map {
                forceSuspend("C", 250)
                it * 3
            }
            .withTraceName("map 3x")
            .flowOn(dispatcherB)
            .withTraceName("flowOn thread #2")

    override suspend fun start(): Unit = coroutineScope {
        launch("launch", dispatcherC) {
            coldFlow.collect { traceCoroutine("got: $it") { forceSuspend("C", 250) } }
        }
    }
}
