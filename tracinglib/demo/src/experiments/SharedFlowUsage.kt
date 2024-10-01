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

import com.android.app.tracing.coroutines.launch
import com.android.app.tracing.coroutines.nameCoroutine
import com.android.app.tracing.coroutines.traceCoroutine
import com.example.tracing.demo.FixedThreadA
import com.example.tracing.demo.FixedThreadB
import com.example.tracing.demo.FixedThreadC
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Singleton
class SharedFlowUsage
@Inject
constructor(
    @FixedThreadA private var dispatcherA: CoroutineDispatcher,
    @FixedThreadB private var dispatcherB: CoroutineDispatcher,
    @FixedThreadC private var dispatcherC: CoroutineDispatcher,
) : Experiment {

    override val description: String = "Create a shared flow and collect from it"

    private val coldFlow =
        coldFibonacciFlow("shared")
            // this trace name is NOT used because the dispatcher did NOT change
            .flowOn(nameCoroutine("UNUSED_NAME"))
            .map {
                traceCoroutine("map") {
                    val rv = it * it
                    forceSuspend("map($it) -> $rv", 50)
                    rv
                }
            }
            // this trace name is used here because the dispatcher changed
            .flowOn(dispatcherC + nameCoroutine("new-name-for-flow"))
            .filter {
                traceCoroutine("filter") {
                    val rv = it % 4 == 0
                    forceSuspend("filter($it) -> $rv", 50)
                    rv
                }
            }
            // this trace name is used, because flowScope has a CoroutineTracingContext
            .flowOn(nameCoroutine("named-cold-flow"))

    override suspend fun start() {
        coroutineScope {
            val stateFlow = coldFlow.stateIn(this, SharingStarted.Eagerly, 10)
            launch("launchA", dispatcherA) {
                stateFlow.collect {
                    traceCoroutine("collectA-$it") { forceSuspend("collectA", 100) }
                }
            }
            launch("launchB", dispatcherB) {
                stateFlow.collect {
                    traceCoroutine("collectB-$it") { forceSuspend("collectB", 100) }
                }
            }
        }
    }
}
