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
import com.android.app.tracing.coroutines.flow.flowName
import com.example.tracing.demo.FixedThreadA
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn

@Singleton
class LeakySharedFlow
@Inject
constructor(@FixedThreadA private var dispatcherA: CoroutineDispatcher) : AsyncExperiment {

    override val description: String = "Create a shared flow that cannot be cancelled by the caller"

    private val leakedScope =
        CoroutineScope(
            dispatcherA +
                createCoroutineTracingContext("flow-scope", walkStackForDefaultNames = true)
        )

    override suspend fun start() {
        // BAD - does not follow structured concurrency. This creates a new job each time it is
        // called. There is no way to cancel the shared flow because the parent does not know about
        // it
        coldCounterFlow("leaky1")
            .flowName("leakySharedFlow1")
            .shareIn(leakedScope, SharingStarted.Eagerly, replay = 10)

        // BAD - this also leaks
        coroutineScope {
            coldCounterFlow("leaky2")
                .flowName("leakySharedFlow2")
                .shareIn(leakedScope, SharingStarted.Eagerly, replay = 10)
        }
    }
}
