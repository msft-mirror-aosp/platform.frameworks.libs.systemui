/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.app.tracing.coroutines

import android.os.Trace
import com.android.app.tracing.beginSlice
import com.android.app.tracing.endSlice
import java.util.ArrayDeque

/**
 * Represents a section of code executing in a coroutine. This may be split up into multiple slices
 * on different threads as the coroutine is suspended and resumed.
 *
 * @see traceCoroutine
 */
private typealias TraceSection = String

private class MutableInt(var value: Int)

private class ThreadLocalInt : ThreadLocal<MutableInt>() {
    override fun initialValue(): MutableInt {
        return MutableInt(0)
    }
}

/**
 * ThreadLocal counter for how many open trace sections there are. This is needed because it is
 * possible that on a multi-threaded dispatcher, one of the threads could be slow, and
 * [TraceContextElement.restoreThreadContext] might be invoked _after_ the coroutine has already
 * resumed and modified [TraceData] - either adding or removing trace sections and changing the
 * count. If we did not store this thread-locally, then we would incorrectly end too many or too few
 * trace sections.
 */
private val openSliceCount = ThreadLocalInt()

/**
 * Used for storing trace sections so that they can be added and removed from the currently running
 * thread when the coroutine is suspended and resumed.
 *
 * @property currentId ID of associated TraceContextElement
 * @property strictMode Whether to add additional checks to the coroutine machinery, throwing a
 *   `ConcurrentModificationException` if TraceData is modified from the wrong thread. This should
 *   only be set for testing.
 * @see traceCoroutine
 */
@PublishedApi
internal class TraceData(internal val currentId: Int, private val strictMode: Boolean) {

    internal lateinit var slices: ArrayDeque<TraceSection>

    /** Adds current trace slices back to the current thread. Called when coroutine is resumed. */
    internal fun beginAllOnThread() {
        if (Trace.isTagEnabled(Trace.TRACE_TAG_APP)) {
            strictModeCheck()
            if (::slices.isInitialized) {
                slices.descendingIterator().forEach { sectionName -> beginSlice(sectionName) }
                openSliceCount.get()!!.value = slices.size
            }
        }
    }

    /**
     * Removes all current trace slices from the current thread. Called when coroutine is suspended.
     */
    internal fun endAllOnThread() {
        if (Trace.isTagEnabled(Trace.TRACE_TAG_APP)) {
            strictModeCheck()
            val sliceCount = openSliceCount.get()!!
            repeat(sliceCount.value) { endSlice() }
            sliceCount.value = 0
        }
    }

    /**
     * Creates a new trace section with a unique ID and adds it to the current trace data. The slice
     * will also be added to the current thread immediately. This slice will not propagate to parent
     * coroutines, or to child coroutines that have already started. The unique ID is used to verify
     * that the [endSpan] is corresponds to a [beginSpan].
     */
    @PublishedApi
    internal fun beginSpan(name: String) {
        strictModeCheck()
        if (!::slices.isInitialized) {
            slices = ArrayDeque<TraceSection>(4)
        }
        slices.push(name)
        openSliceCount.get()!!.value = slices.size
        beginSlice(name)
    }

    /**
     * Ends the trace section and validates it corresponds with an earlier call to [beginSpan]. The
     * trace slice will immediately be removed from the current thread. This information will not
     * propagate to parent coroutines, or to child coroutines that have already started.
     */
    @PublishedApi
    internal fun endSpan() {
        strictModeCheck()
        // Should never happen, but we should be defensive rather than crash the whole application
        if (::slices.isInitialized && slices.size > 0) {
            slices.pop()
            openSliceCount.get()!!.value = slices.size
            endSlice()
        } else if (strictMode) {
            throw IllegalStateException(INVALID_SPAN_END_CALL_ERROR_MESSAGE)
        }
    }

    public override fun toString(): String =
        if (DEBUG) {
            if (::slices.isInitialized) {
                "{${slices.joinToString(separator = "\", \"", prefix = "\"", postfix = "\"")}}"
            } else {
                "{<uninitialized>}"
            }
        } else super.toString()

    private fun strictModeCheck() {
        if (strictMode && traceThreadLocal.get() !== this) {
            throw ConcurrentModificationException(STRICT_MODE_ERROR_MESSAGE)
        }
    }
}

private const val INVALID_SPAN_END_CALL_ERROR_MESSAGE =
    "TraceData#endSpan called when there were no active trace sections in its scope."

private const val STRICT_MODE_ERROR_MESSAGE =
    "TraceData should only be accessed using " +
        "the ThreadLocal: CURRENT_TRACE.get(). Accessing TraceData by other means, such as " +
        "through the TraceContextElement's property may lead to concurrent modification."
