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

import com.android.app.tracing.coroutines.nameCoroutine
import com.android.app.tracing.coroutines.traceCoroutine
import com.android.app.tracing.traceSection
import com.example.tracing.demo.FixedThreadA
import com.example.tracing.demo.FixedThreadB
import com.example.tracing.demo.FixedThreadC
import com.example.tracing.demo.Unconfined
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart.LAZY
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Singleton
class CombineDeferred
@Inject
constructor(
    @FixedThreadA private var dispatcherA: CoroutineDispatcher,
    @FixedThreadB private var dispatcherB: CoroutineDispatcher,
    @FixedThreadC private val dispatcherC: CoroutineDispatcher,
    @Unconfined private var unconfinedContext: CoroutineDispatcher,
) : Experiment {
    override val description: String = "async{} then start()"

    override suspend fun start(): Unit = coroutineScope {
        // deferred10 -> deferred20 -> deferred30
        val deferred30 =
            async(start = LAZY, context = dispatcherB) {
                traceCoroutine("async#30") { forceSuspend("deferred30", 250) }
            }
        val deferred20 =
            async(start = LAZY, context = unconfinedContext) {
                traceCoroutine("async#20") { forceSuspend("deferred20", 250) }
                traceSection("start30") { deferred30.start() }
            }
        val deferred10 =
            async(start = LAZY, context = dispatcherC) {
                traceCoroutine("async#10") { forceSuspend("deferred10", 250) }
                traceSection("start20") { deferred20.start() }
            }

        // deferredA -> deferredB -> deferredC
        val deferredC =
            async(start = LAZY, context = dispatcherB) {
                traceCoroutine("async#C") { forceSuspend("deferredC", 250) }
            }
        val deferredB =
            async(start = LAZY, context = unconfinedContext) {
                traceCoroutine("async#B") { forceSuspend("deferredB", 250) }
                traceSection("startC") { deferredC.start() }
            }
        val deferredA =
            async(start = LAZY, context = dispatcherC) {
                traceCoroutine("async#A") { forceSuspend("deferredA", 250) }
                traceSection("startB") { deferredB.start() }
            }

        // no dispatcher specified, so will inherit dispatcher from whoever called
        // run(), meaning the main thread
        val deferredE =
            async(nameCoroutine("overridden-scope-name-for-deferredE")) {
                traceCoroutine("async#E") { forceSuspend("deferredE", 250) }
            }

        launch(dispatcherA) {
            traceSection("start10") { deferred10.start() }
            traceSection("startA") { deferredA.start() }
            traceSection("startE") { deferredE.start() }
        }
    }
}
