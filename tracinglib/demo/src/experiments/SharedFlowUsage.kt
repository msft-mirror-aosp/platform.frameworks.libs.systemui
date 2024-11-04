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

import com.android.app.tracing.coroutines.flow.collectTraced as collect
import com.android.app.tracing.coroutines.flow.collectTraced
import com.android.app.tracing.coroutines.flow.filterTraced as filter
import com.android.app.tracing.coroutines.flow.flowName
import com.android.app.tracing.coroutines.flow.mapTraced as map
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.app.tracing.coroutines.nameCoroutine
import com.android.app.tracing.coroutines.traceCoroutine
import com.example.tracing.demo.FixedThreadA
import com.example.tracing.demo.FixedThreadB
import com.example.tracing.demo.FixedThreadC
import com.example.tracing.demo.FixedThreadD
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

@Singleton
class SharedFlowUsage
@Inject
constructor(
    @FixedThreadA private var dispatcherA: CoroutineDispatcher,
    @FixedThreadB private var dispatcherB: CoroutineDispatcher,
    @FixedThreadC private var dispatcherC: CoroutineDispatcher,
    @FixedThreadD private var dispatcherD: CoroutineDispatcher,
) : Experiment {

    override val description: String = "Create a shared flow and collect from it"

    private val coldFlow =
        coldCounterFlow("shared", 10)
            // this trace name is NOT used because the dispatcher did NOT change
            .flowName("UNUSED_NAME")
            .map("pow2") {
                val rv = it * it
                forceSuspend("map($it) -> $rv", 50)
                rv
            }
            // this trace name is used here because the dispatcher changed
            .flowOn(dispatcherC + nameCoroutine("NEW_COLD_FLOW_NAME"))
            .filter("mod4") {
                val rv = it % 4 == 0
                forceSuspend("filter($it) -> $rv", 50)
                rv
            }
            // this trace name is used, because the scope it is collected in has a
            // CoroutineTracingContext
            .flowName("COLD_FLOW")

    override suspend fun start() {
        coroutineScope {
            val stateFlow = coldFlow.stateIn(this, SharingStarted.Eagerly, 10)
            launch("launchAAAA", dispatcherA) {
                stateFlow.collect("collectAAAA") {
                    traceCoroutine("AAAA collected: $it") { forceSuspend("AAAA", 15) }
                }
            }
            launch("launchBBBB", dispatcherB) {
                // Don't pass a string. Instead, rely on default behavior to walk the stack for the
                // name. This results in trace sections like:
                // `collect:SharedFlowUsage$start$1$2:emit`
                // NOTE: `Flow.collect` is a member function and takes precedence, so we need
                // to invoke `collectTraced` using its original name instead of its `collect` alias
                stateFlow.collectTraced {
                    traceCoroutine("BBBB collected: $it") { forceSuspend("BBBB", 30) }
                }
            }
            launch("launchCCCC", dispatcherC) {
                stateFlow.collect("collectCCCC") {
                    traceCoroutine("CCCC collected: $it") { forceSuspend("CCCC", 60) }
                }
            }
            launch("launchDDDD", dispatcherD) {
                // Uses Flow.collect member function instead of collectTraced:
                stateFlow.collect {
                    traceCoroutine("DDDD collected: $it") { forceSuspend("DDDD", 90) }
                }
            }
        }
    }
}
