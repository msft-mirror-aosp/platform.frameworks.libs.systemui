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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull

object FakeTraceState {

    @Volatile var isTracingEnabled: Boolean = true

    private val allThreadStates = hashMapOf<Long, MutableList<String>>()

    private val threadLocalTraceState =
        ThreadLocal.withInitial {
            synchronized(allThreadStates) {
                val threadId = currentThreadId()
                allThreadStates[threadId] = mutableListOf()
            }
            mutableListOf<String>()
        }

    fun begin(sectionName: String) {
        threadLocalTraceState.get()!!.add(sectionName)
    }

    fun end() {
        val threadId = currentThreadId()
        val traceSections = threadLocalTraceState.get()
        assertNotNull(
            "Attempting to close trace section on thread=$threadId, " +
                "but tracing never started on this thread",
            traceSections,
        )
        assertFalse(
            "Attempting to close trace section on thread=$threadId, " +
                "but there are no open sections",
            traceSections!!.isEmpty(),
        )
        traceSections.removeLast()
    }

    fun getOpenTraceSectionsOnCurrentThread(): Array<String> {
        return threadLocalTraceState.get()?.toTypedArray() ?: emptyArray()
    }

    /**
     * Helper function for debugging; use as follows:
     * ```
     * println(FakeThreadStateLocal)
     * ```
     */
    override fun toString(): String {
        val sb = StringBuilder()
        synchronized(allThreadStates) {
            allThreadStates.entries.forEach { sb.appendLine("${it.key} -> ${it.value}") }
        }
        return sb.toString()
    }
}
