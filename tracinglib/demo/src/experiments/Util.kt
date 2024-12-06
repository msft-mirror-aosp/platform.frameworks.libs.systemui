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
import android.os.Trace
import com.android.app.tracing.coroutines.traceCoroutine
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

private val delayHandler by lazy { startThreadWithLooper("Thread:forceSuspend").threadHandler }

/** Like [delay], but naively implemented so that it always suspends. */
suspend fun forceSuspend(traceName: String, timeMillis: Long) {
    val traceMessage = "forceSuspend:$traceName"
    return traceCoroutine(traceMessage) {
        val cookie = Random.nextInt()
        suspendCancellableCoroutine { continuation ->
            Trace.asyncTraceForTrackBegin(Trace.TRACE_TAG_APP, TRACK_NAME, traceMessage, cookie)
            Trace.instant(Trace.TRACE_TAG_APP, "will resume in ${timeMillis}ms")
            continuation.invokeOnCancellation { cause ->
                Trace.instant(
                    Trace.TRACE_TAG_APP,
                    "forceSuspend:$traceName, cancelled due to ${cause?.javaClass}",
                )
                Trace.asyncTraceForTrackEnd(Trace.TRACE_TAG_APP, TRACK_NAME, cookie)
            }
            delayHandler.postDelayed(
                {
                    Trace.asyncTraceForTrackEnd(Trace.TRACE_TAG_APP, TRACK_NAME, cookie)
                    Trace.traceBegin(Trace.TRACE_TAG_APP, "resume")
                    try {
                        continuation.resume(Unit)
                    } finally {
                        Trace.traceEnd(Trace.TRACE_TAG_APP)
                    }
                },
                timeMillis,
            )
        }
    }
}

fun startThreadWithLooper(name: String): HandlerThread {
    val thread = HandlerThread(name)
    thread.start()
    val looper = thread.looper
    looper.setTraceTag(Trace.TRACE_TAG_APP)
    return thread
}

const val TRACK_NAME = "Async events"
