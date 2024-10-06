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
import kotlin.coroutines.resume
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine

fun coldFibonacciFlow(name: String) = flow {
    emit(1)
    var cur = 1
    var next = 1
    while (true) {
        emit(next)
        forceSuspend("$name-fib: $next", 25)
        val tmp = cur + next
        cur = next
        next = tmp
    }
}

private val delayHandler by lazy { startThreadWithLooper("Thread D").threadHandler }

/** Like [delay], but naively implemented so that it always suspends. */
suspend fun forceSuspend(traceName: String, timeMillis: Long) {
    Trace.instant(
        Trace.TRACE_TAG_APP,
        "forceSuspend:$traceName suspended, will resume in ${timeMillis}ms",
    )
    val cookie = Random.nextInt()
    Trace.asyncTraceForTrackBegin(
        Trace.TRACE_TAG_APP,
        TRACK_NAME,
        "forceSuspend:$traceName",
        cookie,
    )
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cause ->
            Trace.instant(
                Trace.TRACE_TAG_APP,
                "forceSuspend:$traceName cancelled due to ${cause?.javaClass}",
            )
            Trace.asyncTraceForTrackEnd(Trace.TRACE_TAG_APP, TRACK_NAME, cookie)
        }
        delayHandler.postDelayed(
            {
                Trace.asyncTraceForTrackEnd(Trace.TRACE_TAG_APP, TRACK_NAME, cookie)
                continuation.resume(Unit)
            },
            timeMillis,
        )
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
