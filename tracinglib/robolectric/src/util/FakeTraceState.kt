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

import kotlin.concurrent.Volatile
import kotlin.concurrent.getOrSet
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull

private class ThreadTraceState : ThreadLocal<MutableList<String>>()

private val ALL_THREAD_STATES = hashMapOf<Long, MutableList<String>>()

private val CURRENT_TRACE_STATE = ThreadTraceState()

private fun currentThreadTraceState(): MutableList<String> {
    return CURRENT_TRACE_STATE.getOrSet {
        synchronized(ALL_THREAD_STATES) {
            mutableListOf<String>().also { ALL_THREAD_STATES[currentThreadId()] = it }
        }
    }
}

object FakeTraceState {

    @Volatile var isTracingEnabled: Boolean = true

    fun clearAll() {
        synchronized(ALL_THREAD_STATES) { ALL_THREAD_STATES.entries.forEach { it.value.clear() } }
    }

    fun begin(sectionName: String) {
        currentThreadTraceState().add(sectionName)
    }

    fun end() {
        val threadId = currentThreadId()
        val traceSections = currentThreadTraceState()
        assertNotNull(
            "Attempting to close trace section on thread=$threadId, " +
                "but tracing never started on this thread",
            traceSections,
        )
        assertFalse(
            "Attempting to close trace section on thread=$threadId, " +
                "but there are no open sections",
            traceSections.isEmpty(),
        )
        traceSections.removeLast()
    }

    fun getOpenTraceSectionsOnCurrentThread(): Array<String> {
        return currentThreadTraceState().toTypedArray()
    }

    /**
     * Helper function for debugging; use as follows:
     * ```
     * println(FakeThreadStateLocal)
     * ```
     */
    override fun toString(): String {
        val sb = StringBuilder()
        synchronized(ALL_THREAD_STATES) {
            ALL_THREAD_STATES.entries.forEach { sb.appendLine("${it.key} -> ${it.value}") }
        }
        return sb.toString()
    }
}
