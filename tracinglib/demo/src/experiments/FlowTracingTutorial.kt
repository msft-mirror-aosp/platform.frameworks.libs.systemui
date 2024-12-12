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

import com.android.app.tracing.TraceUtils.traceAsync
import com.android.app.tracing.coroutines.createCoroutineTracingContext
import com.android.app.tracing.coroutines.flow.collectTraced
import com.android.app.tracing.coroutines.flow.flowName
import com.android.app.tracing.coroutines.launchTraced
import com.android.app.tracing.coroutines.nameCoroutine
import com.android.app.tracing.coroutines.traceCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

@Singleton
class FlowTracingTutorial @Inject constructor() : BlockingExperiment {

    override val description: String = "Flow tracing tutorial"

    @OptIn(ExperimentalContracts::class)
    private inline fun runStep(stepNumber: Int = 0, block: () -> Unit) {
        contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
        traceAsync(TRACK_NAME, "Step #$stepNumber") {
            block()
            // To space out events, and clarity when reading the Perfetto trace, add a sleep:
            traceAsync(TRACK_NAME, "sleep") { Thread.sleep(10) }
        }
    }

    private fun createHandlerDispatcher(name: String): CoroutineDispatcher {
        val thread = startThreadWithLooper(name)
        return thread.threadHandler.asCoroutineDispatcher()
    }

    private fun createScope(dispatcher: CoroutineDispatcher): CoroutineScope {
        return CoroutineScope(
            dispatcher +
                createCoroutineTracingContext(
                    walkStackForDefaultNames = true,
                    countContinuations = true,
                )
        )
    }

    private val handlerDispatcher: CoroutineDispatcher by lazy {
        createHandlerDispatcher("demo-main-thread")
    }

    private val backgroundDispatcher: CoroutineDispatcher by lazy {
        createHandlerDispatcher("demo-bg-thread")
    }

    private val scope = createScope(handlerDispatcher)
    private val bgScope = createScope(backgroundDispatcher)

    private val coldFlow = flow {
        traceCoroutine("emit(1)") { emit(1) }
        forceSuspend("A", 2)
        emit(2)
        traceCoroutine("emit(2)") { emit(2) }
        forceSuspend("B", 2)
    }

    /** 1: */
    private fun step1() {
        scope.launchTraced("my-launch") { coldFlow.collect { forceSuspend("A:$it", 1) } }
    }

    /** 2: */
    private fun step2() {
        scope.launchTraced("my-launch") { coldFlow.collectTraced { forceSuspend("A:$it", 1) } }
    }

    /** 3: */
    private fun step3() {
        scope.launchTraced("my-launch") {
            coldFlow.collectTraced("my-collector") { forceSuspend("A:$it", 1) }
        }
    }

    /** 4: */
    private fun step4() {
        scope.launchTraced("my-launch") {
            coldFlow.map { it * 2 }.collectTraced("my-collector") { forceSuspend("A:$it", 1) }
        }
    }

    /** 5: */
    private fun step5() {
        scope.launchTraced("my-launch") {
            coldFlow
                .flowOn(backgroundDispatcher)
                .map { it * 2 }
                .collectTraced("my-collector") { forceSuspend("A:$it", 1) }
        }
    }

    /** 6: */
    private fun step6() {
        scope.launchTraced("my-launch") {
            coldFlow
                // Alternatively, call `.flowName("hello-cold")` before or after `flowOn` changes
                // the dispatcher.
                .flowOn(nameCoroutine("hello-cold") + backgroundDispatcher)
                .map { it * 2 }
                .collectTraced("my-collector") { forceSuspend("A:$it", 1) }
        }
    }

    /** 7: */
    private fun step7() {
        // Important: flowName() must be called BEFORE shareIn(). Otherwise it will have no effect.
        val sharedFlow = coldFlow.flowName("my-shared-flow").shareIn(bgScope, SharingStarted.Lazily)

        scope.launchTraced("my-launch") {
            sharedFlow.collectTraced("my-collector") { forceSuspend("A:$it", 1) }
        }

        bgScope.cancel()
    }

    /** 8: */
    private fun step8() {
        val state = MutableStateFlow(1)
        // `shareIn` launches on the given scope using the context of the flow as a receiver, but
        // only if the Flow is a ChannelFlow. MutableStateFlow is not, so it uses a
        // EmptyCoroutineContext.
        //
        // To get the name of the shared flow into the trace context, we will need to walk the stack
        // for a name, or modify behavior of `TraceContextElement` to better support this use-case.
        val sharedFlow = state.shareIn(bgScope, SharingStarted.Lazily)

        scope.launchTraced("my-launch") {
            sharedFlow.collectTraced("my-collector") { forceSuspend("A:$it", 1) }
        }

        bgScope.cancel()
    }

    override fun start() {
        runStep(1, ::step1)
        runStep(2, ::step2)
        runStep(3, ::step3)
        runStep(4, ::step4)
        runStep(5, ::step5)
        runStep(6, ::step6)
        runStep(7, ::step7)
        runStep(8, ::step8)
    }
}
