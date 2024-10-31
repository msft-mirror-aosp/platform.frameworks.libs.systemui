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

package com.android.test.tracing.coroutines.util

import android.os.Trace
import android.util.Log
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@OptIn(ExperimentalStdlibApi::class)
@Suppress("unused_parameter")
@Implements(Trace::class)
object ShadowTrace {

    @Implementation
    @JvmStatic
    fun isEnabled(): Boolean {
        return FakeTraceState.isTracingEnabled
    }

    @Implementation
    @JvmStatic
    fun traceBegin(traceTag: Long, methodName: String) {
        debug { "traceBegin: name=$methodName" }
        FakeTraceState.begin(methodName)
    }

    @Implementation
    @JvmStatic
    fun traceEnd(traceTag: Long) {
        debug { "traceEnd" }
        FakeTraceState.end()
    }

    @Implementation
    @JvmStatic
    fun asyncTraceBegin(traceTag: Long, methodName: String, cookie: Int) {
        debug { "asyncTraceBegin: name=$methodName cookie=${cookie.toHexString()}" }
    }

    @Implementation
    @JvmStatic
    fun asyncTraceEnd(traceTag: Long, methodName: String, cookie: Int) {
        debug { "asyncTraceEnd: name=$methodName cookie=${cookie.toHexString()}" }
    }

    @Implementation
    @JvmStatic
    fun asyncTraceForTrackBegin(
        traceTag: Long,
        trackName: String,
        methodName: String,
        cookie: Int,
    ) {
        debug {
            "asyncTraceForTrackBegin: track=$trackName name=$methodName cookie=${cookie.toHexString()}"
        }
    }

    @Implementation
    @JvmStatic
    fun asyncTraceForTrackEnd(traceTag: Long, trackName: String, methodName: String, cookie: Int) {
        debug {
            "asyncTraceForTrackEnd: track=$trackName name=$methodName cookie=${cookie.toHexString()}"
        }
    }

    @Implementation
    @JvmStatic
    fun instant(traceTag: Long, eventName: String) {
        debug { "instant: name=$eventName" }
    }

    @Implementation
    @JvmStatic
    fun instantForTrack(traceTag: Long, trackName: String, eventName: String) {
        debug { "instantForTrack: track=$trackName name=$eventName" }
    }
}

private const val DEBUG = false

/** Log a message with a tag indicating the current thread ID */
private fun debug(message: () -> String) {
    if (DEBUG) Log.d("ShadowTrace", "Thread #${currentThreadId()}: $message")
}
