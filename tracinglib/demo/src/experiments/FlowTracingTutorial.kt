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
import com.android.app.tracing.TraceUtils.traceAsync
import com.android.app.tracing.coroutines.createCoroutineTracingContext
import com.android.app.tracing.coroutines.launchTraced
import com.android.app.tracing.coroutines.traceCoroutine
import com.example.tracing.demo.FixedThread1
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

@Singleton
class FlowTracingTutorial
@Inject
constructor(
    @FixedThread1 private var dispatcherA: CoroutineDispatcher,
    @FixedThread1 private var dispatcherB: CoroutineDispatcher,
) : Experiment() {

    override val description: String = "Flow tracing tutorial"

    @OptIn(ExperimentalContracts::class)
    private suspend inline fun runStep(stepNumber: Int = 0, crossinline block: (Job) -> Unit) {
        contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
        traceAsync(TRACK_NAME, "Step #$stepNumber") {
            coroutineScope { block(coroutineContext.job) }
        }
        traceAsync(TRACK_NAME, "cooldown") { delay(10) }
    }

    private fun createTracingContext(name: String): CoroutineContext {
        return createCoroutineTracingContext(
            name = name,
            walkStackForDefaultNames = true,
            countContinuations = true,
        )
    }

    /** 1: */
    private fun step1(job: Job) {
        val scope = CoroutineScope(job + dispatcherA + createTracingContext("scope1"))
        val coldFlow = flow {
            forceSuspend("e1", 2)
            traceCoroutine("emit(1)") { emit(0) }
            forceSuspend("e3", 2)
            traceCoroutine("emit(2)") { emit(1) }
            forceSuspend("e5", 2)
        }
        scope.launchTraced("launch1") {
            coldFlow.collect {
                Trace.instant(Trace.TRACE_TAG_APP, "received:$it")
                when (it) {
                    0 -> {
                        forceSuspend("e2", 1)
                    }
                    1 -> {
                        forceSuspend("e4", 1)
                    }
                }
            }
        }
    }

    //    /** 2: */
    //    private fun step2(job: Job) {
    //        val scope = CoroutineScope(job + dispatcherA + createTracingContext("scope2"))
    //        scope.launchTraced("launch2") { coldFlow.collectTraced { forceSuspend("got:$it", 1) }
    // }
    //    }
    //
    //    /** 3: */
    //    private fun step3(job: Job) {
    //        val scope = CoroutineScope(job + dispatcherA + createTracingContext("scope3"))
    //        scope.launchTraced("launch3") {
    //            coldFlow.collectTraced("collect3") { forceSuspend("got:$it", 1) }
    //        }
    //    }
    //
    //    /** 4: */
    //    private fun step4(job: Job) {
    //        val scope = CoroutineScope(job + dispatcherA + createTracingContext("scope3"))
    //        scope.launchTraced("launch3") {
    //            coldFlow.map { it * 2 }.collectTraced("collect4") { forceSuspend("got:$it", 1) }
    //        }
    //    }

    //    /** 5: */
    //    private fun step5(job: Job) {
    //        scope.launchTraced("my-launch") {
    //            coldFlow
    //                .flowOn(dispatcherA)
    //                .map { it * 2 }
    //                .collectTraced("my-collector") { forceSuspend("A:$it", 1) }
    //        }
    //    }
    //
    //    /** 6: */
    //    private fun step6(job: Job) {
    //        scope.launchTraced("my-launch") {
    //            coldFlow
    //                // Alternatively, call `.flowName("hello-cold")` before or after `flowOn`
    // changes
    //                // the dispatcher.
    //                .flowOn(CoroutineTraceName("hello-cold") + dispatcherA)
    //                .map { it * 2 }
    //                .collectTraced("my-collector") { forceSuspend("A:$it", 1) }
    //        }
    //    }
    //
    //    /** 7: */
    //    private fun step7(job: Job) {
    //        // Important: flowName() must be called BEFORE shareIn(). Otherwise it will have no
    // effect.
    //        val sharedFlow = coldFlow.flowName("my-shared-flow").shareIn(bgScope,
    // SharingStarted.Lazily)
    //
    //        scope.launchTraced("my-launch") {
    //            sharedFlow.collectTraced("my-collector") { forceSuspend("A:$it", 1) }
    //        }
    //
    //        bgScope.cancel()
    //    }
    //
    //    /** 8: */
    //    private fun step8(job: Job) {
    //        val state = MutableStateFlow(1)
    //        // `shareIn` launches on the given scope using the context of the flow as a receiver,
    // but
    //        // only if the Flow is a ChannelFlow. MutableStateFlow is not, so it uses a
    //        // EmptyCoroutineContext.
    //        //
    //        // To get the name of the shared flow into the trace context, we will need to walk the
    // stack
    //        // for a name, or modify behavior of `TraceContextElement` to better support this
    // use-case.
    //        val sharedFlow = state.shareIn(bgScope, SharingStarted.Lazily)
    //
    //        scope.launchTraced("my-launch") {
    //            sharedFlow.collectTraced("my-collector") { forceSuspend("A:$it", 1) }
    //        }
    //
    //        bgScope.cancel()
    //    }

    override suspend fun runExperiment(): Unit = coroutineScope {
        launch {
            runStep(1, ::step1)
            //            runStep(2, ::step2)
            //            runStep(3, ::step3)
        }
        //        runStep(4, ::step4)
        //        runStep(5, ::step5)
        //        runStep(6, ::step6)
        //        runStep(7, ::step7)
        //        runStep(8, ::step8)
    }
}
