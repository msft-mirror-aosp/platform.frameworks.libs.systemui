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
import com.android.app.tracing.coroutines.createCoroutineTracingContext
import com.android.app.tracing.coroutines.flow.collectTraced
import com.android.app.tracing.coroutines.flow.flowName
import com.android.app.tracing.coroutines.launchTraced
import com.android.systemui.Flags.FLAG_COROUTINE_TRACING
import java.util.concurrent.Executor
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.newSingleThreadContext
import org.junit.Test

data class ExampleInfo(val a: Int, val b: Boolean, val c: String)

interface ExampleStateTracker {
    val info: ExampleInfo

    fun addCallback(callback: Callback, executor: Executor)

    fun removeCallback(callback: Callback)

    interface Callback {
        fun onInfoChanged(newInfo: ExampleInfo)
    }
}

interface ExampleRepository {
    val currentInfo: Flow<ExampleInfo>
    val otherState: StateFlow<Boolean>
    val combinedState: StateFlow<Boolean> // true when otherState == true and current.b == true
}

class ExampleStateTrackerImpl : ExampleStateTracker {
    private var _info = ExampleInfo(0, false, "Initial")
    override val info: ExampleInfo
        get() = _info

    val callbacks = mutableListOf<Pair<ExampleStateTracker.Callback, Executor>>()

    override fun addCallback(callback: ExampleStateTracker.Callback, executor: Executor) {
        callbacks.add(Pair(callback, executor))
    }

    override fun removeCallback(callback: ExampleStateTracker.Callback) {
        callbacks.removeIf { it.first == callback }
    }

    fun forceUpdate(a: Int, b: Boolean, c: String) {
        _info = ExampleInfo(a, b, c)
        callbacks.forEach { it.second.execute { it.first.onInfoChanged(_info) } }
    }
}

private class ExampleRepositoryImpl(
    private val testBase: TestBase,
    private val bgScope: CoroutineScope,
    private val tracker: ExampleStateTrackerImpl,
) : ExampleRepository {
    @OptIn(ExperimentalStdlibApi::class)
    override val currentInfo: StateFlow<ExampleInfo> =
        callbackFlow {
                channel.trySend(tracker.info)
                val callback =
                    object : ExampleStateTracker.Callback {
                        override fun onInfoChanged(newInfo: ExampleInfo) {
                            channel.trySend(newInfo)
                        }
                    }
                tracker.addCallback(
                    callback,
                    bgScope.coroutineContext[CoroutineDispatcher]!!.asExecutor(),
                )
                awaitClose { tracker.removeCallback(callback) }
            }
            .onEach { testBase.expect("bg:1^currentInfo") }
            .flowName("currentInfo")
            .stateIn(bgScope, SharingStarted.Eagerly, initialValue = tracker.info)

    override val otherState = MutableStateFlow(false)

    /** flow that emits true only when currentInfo.b == true and otherState == true */
    override val combinedState: StateFlow<Boolean>
        get() =
            combine(currentInfo, otherState, ::Pair)
                .map { it.first.b && it.second }
                .distinctUntilChanged()
                .onEach { testBase.expect("bg:2^combinedState:1^:2^") }
                .onStart { emit(false) }
                .flowName("combinedState")
                .stateIn(
                    scope = bgScope,
                    started = SharingStarted.WhileSubscribed(),
                    initialValue = false,
                )
}

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
@EnableFlags(FLAG_COROUTINE_TRACING)
class CallbackFlowTracingTest : TestBase() {

    override val extraCoroutineContext: CoroutineContext
        get() = createCoroutineTracingContext("main", includeParentNames = true, strictMode = true)

    @Test
    fun callbackFlow1() {
        val exampleTracker = ExampleStateTrackerImpl()
        val bgScope =
            CoroutineScope(
                createCoroutineTracingContext("bg", includeParentNames = true, strictMode = true) +
                    newSingleThreadContext("bg-thread")
            )
        val repository = ExampleRepositoryImpl(this, bgScope, exampleTracker)

        expect(1)
        runTest {
            launchTraced("collectCombined") {
                repository.combinedState.collectTraced("combined-states") {
                    expect(
                        listOf(2, 4, 5, 6),
                        "main:1^:1^collectCombined",
                        "collect:combined-states",
                        "collect:combined-states:emit",
                    )
                }
            }
            delay(10)
            expect(3, "main:1^")
            delay(10)
            exampleTracker.forceUpdate(1, false, "A") // <-- no change
            delay(10)
            repository.otherState.value = true // <-- no change
            delay(10)
            exampleTracker.forceUpdate(2, true, "B") // <-- should update `combinedState`
            delay(10)
            repository.otherState.value = false // <-- should update `combinedState`
            delay(10)
            exampleTracker.forceUpdate(3, false, "C") // <-- no change
            delay(10)
            exampleTracker.forceUpdate(4, true, "D") // <-- no change
            delay(10)
            repository.otherState.value = true // <-- should update `combinedState`
            delay(10)
            finish(7, "main:1^")
            cancel("Cancelled normally for test")
        }
        bgScope.cancel("Cancelled normally for test")
    }
}