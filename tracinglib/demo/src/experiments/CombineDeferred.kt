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

import com.android.app.tracing.coroutines.asyncTraced
import com.android.app.tracing.coroutines.traceCoroutine
import com.android.app.tracing.traceSection
import com.example.tracing.demo.FixedThread1
import com.example.tracing.demo.FixedThread2
import com.example.tracing.demo.FixedThread3
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart.LAZY
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Singleton
class CombineDeferred
@Inject
constructor(
    @FixedThread1 private var dispatcher1: CoroutineDispatcher,
    @FixedThread2 private var dispatcher2: CoroutineDispatcher,
    @FixedThread3 private val dispatcher3: CoroutineDispatcher,
) : Experiment() {
    override val description: String = "async{} then start()"

    override suspend fun runExperiment(): Unit = coroutineScope {
        // deferred10 -> deferred20 -> deferred30
        val deferred30 =
            async(start = LAZY, context = dispatcher2) {
                traceCoroutine("async#30") { forceSuspend("deferred30", 25) }
            }
        val deferred20 =
            async(start = LAZY, context = Dispatchers.Unconfined) {
                traceCoroutine("async#20") { forceSuspend("deferred20", 25) }
                traceSection("start30") { deferred30.start() }
            }
        val deferred10 =
            async(start = LAZY, context = dispatcher3) {
                traceCoroutine("async#10") { forceSuspend("deferred10", 25) }
                traceSection("start20") { deferred20.start() }
            }

        // deferredA -> deferredB -> deferredC
        val deferredC =
            async(start = LAZY, context = dispatcher2) {
                traceCoroutine("async#C") { forceSuspend("deferredC", 25) }
            }
        val deferredB =
            async(start = LAZY, context = Dispatchers.Unconfined) {
                traceCoroutine("async#B") { forceSuspend("deferredB", 25) }
                traceSection("startC") { deferredC.start() }
            }
        val deferredA =
            async(start = LAZY, context = dispatcher3) {
                traceCoroutine("async#A") { forceSuspend("deferredA", 25) }
                traceSection("startB") { deferredB.start() }
            }

        // no dispatcher specified, so will inherit dispatcher from whoever called
        // run(), meaning the main thread
        val deferredE =
            asyncTraced("overridden-scope-name-for-deferredE") {
                traceCoroutine("async#E") { forceSuspend("deferredE", 25) }
            }

        launch(dispatcher1) {
            traceSection("start10") { deferred10.start() }
            traceSection("startA") { deferredA.start() }
            traceSection("startE") { deferredE.start() }
        }
    }
}
