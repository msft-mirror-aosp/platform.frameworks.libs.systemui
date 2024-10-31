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

import com.android.app.tracing.coroutines.launchTraced as launch
import com.example.tracing.demo.Default
import com.example.tracing.demo.FixedThreadA
import com.example.tracing.demo.FixedThreadB
import com.example.tracing.demo.FixedThreadC
import com.example.tracing.demo.IO
import com.example.tracing.demo.Unconfined
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope

@Singleton
class LaunchSequentially
@Inject
constructor(
    @FixedThreadA private var dispatcherA: CoroutineDispatcher,
    @FixedThreadB private var dispatcherB: CoroutineDispatcher,
    @FixedThreadC private val dispatcherC: CoroutineDispatcher,
    @Default private var defaultContext: CoroutineDispatcher,
    @IO private var ioContext: CoroutineDispatcher,
    @Unconfined private var unconfinedContext: CoroutineDispatcher,
) : Experiment {
    override val description: String = "launch{};launch{};launch{};launch{}"

    override suspend fun start(): Unit = coroutineScope {
        launch("launch(threadA)", dispatcherA) { forceSuspend("A", 250) }
        launch("launch(threadB)", dispatcherB) { forceSuspend("B", 250) }
        launch("launch(threadC)", dispatcherC) { forceSuspend("C", 250) }
        launch("launch(Dispatchers.Default)", defaultContext) { forceSuspend("D", 250) }
        launch("launch(EmptyCoroutineContext)") { forceSuspend("E", 250) }
        launch("launch(Dispatchers.IO)", ioContext) { forceSuspend("F", 250) }
        launch("launch(Dispatchers.Unconfined)", unconfinedContext) { forceSuspend("G", 250) }
    }
}
