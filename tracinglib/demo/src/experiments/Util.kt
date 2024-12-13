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

import android.os.HandlerThread
import android.os.Process
import android.os.Trace
import com.android.app.tracing.coroutines.traceCoroutine
import com.android.app.tracing.traceSection
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine

fun coldCounterFlow(name: String, maxCount: Int = Int.MAX_VALUE) = flow {
    for (n in 0..maxCount) {
        emit(n)
        forceSuspend("coldCounterFlow:$name:$n", 25)
    }
}

private val delayHandler by lazy { startThreadWithLooper("delay-thread").threadHandler }

private class DelayedContinuationRunner(
    private val continuation: Continuation<Unit>,
    private val traceName: String,
    private val cookie: Int,
) : Runnable {
    override fun run() {
        Trace.asyncTraceForTrackEnd(Trace.TRACE_TAG_APP, TRACK_NAME, cookie)
        Trace.traceBegin(Trace.TRACE_TAG_APP, "resume after $traceName")
        try {
            continuation.resume(Unit)
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_APP)
        }
    }
}

/** Like [delay], but naively implemented so that it always suspends. */
suspend fun forceSuspend(traceName: String? = null, timeMillis: Long) {
    val traceMessage = "delay($timeMillis)${traceName?.let { " [$it]" } ?: ""}"
    val cookie = Random.nextInt()
    Trace.asyncTraceForTrackBegin(Trace.TRACE_TAG_APP, TRACK_NAME, traceMessage, cookie)
    traceCoroutine(traceMessage) {
        suspendCancellableCoroutine { continuation ->
            traceSection("scheduling DelayedContinuationRunner") {
                val delayedRunnable = DelayedContinuationRunner(continuation, traceMessage, cookie)
                if (delayHandler.postDelayed(delayedRunnable, timeMillis)) {
                    continuation.invokeOnCancellation { cause ->
                        Trace.instant(
                            Trace.TRACE_TAG_APP,
                            "$traceMessage, cancelled due to ${cause?.javaClass}",
                        )
                        delayHandler.removeCallbacks(delayedRunnable)
                        Trace.asyncTraceForTrackEnd(Trace.TRACE_TAG_APP, TRACK_NAME, cookie)
                    }
                }
            }
        }
    }
}

fun startThreadWithLooper(name: String): HandlerThread {
    val thread = HandlerThread(name, Process.THREAD_PRIORITY_FOREGROUND)
    thread.start()
    val looper = thread.looper
    looper.setTraceTag(Trace.TRACE_TAG_APP)
    return thread
}

const val TRACK_NAME = "Demo app events"
