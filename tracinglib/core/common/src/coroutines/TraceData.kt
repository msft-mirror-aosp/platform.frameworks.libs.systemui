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

import com.android.app.tracing.beginSlice
import com.android.app.tracing.endSlice
import java.util.ArrayDeque

/**
 * Represents a section of code executing in a coroutine. This may be split up into multiple slices
 * on different threads as the coroutine is suspended and resumed.
 *
 * @see traceCoroutine
 */
typealias TraceSection = String

@PublishedApi
internal class TraceCountThreadLocal : ThreadLocal<Int>() {
    override fun initialValue(): Int {
        return 0
    }
}

/**
 * Used for storing trace sections so that they can be added and removed from the currently running
 * thread when the coroutine is suspended and resumed.
 *
 * @see traceCoroutine
 */
@PublishedApi
internal class TraceData(
    internal val slices: ArrayDeque<TraceSection> = ArrayDeque(),
) : Cloneable {

    /**
     * ThreadLocal counter for how many open trace sections there are. This is needed because it is
     * possible that on a multi-threaded dispatcher, one of the threads could be slow, and
     * `restoreThreadContext` might be invoked _after_ the coroutine has already resumed and
     * modified TraceData - either adding or removing trace sections and changing the count. If we
     * did not store this thread-locally, then we would incorrectly end too many or too few trace
     * sections.
     */
    private val openSliceCount = TraceCountThreadLocal()

    /** Adds current trace slices back to the current thread. Called when coroutine is resumed. */
    internal fun beginAllOnThread() {
        strictModeCheck()
        slices.descendingIterator().forEach { beginSlice(it) }
        openSliceCount.set(slices.size)
    }

    /**
     * Removes all current trace slices from the current thread. Called when coroutine is suspended.
     */
    internal fun endAllOnThread() {
        strictModeCheck()
        repeat(openSliceCount.get()) { endSlice() }
        openSliceCount.set(0)
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
        slices.push(name)
        openSliceCount.set(slices.size)
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
        if (slices.size > 0) {
            slices.pop()
            openSliceCount.set(slices.size)
            endSlice()
        } else if (strictModeForTesting) {
            throw IllegalStateException(INVALID_SPAN_END_CALL_ERROR_MESSAGE)
        }
    }

    /**
     * Used by [TraceContextElement] when launching a child coroutine so that the child coroutine's
     * state is isolated from the parent.
     */
    public override fun clone(): TraceData {
        return TraceData(slices.clone())
    }

    override fun toString(): String {
        return "TraceData@${hashCode().toHexString()}-size=${slices.size}"
    }

    private fun strictModeCheck() {
        if (strictModeForTesting && traceThreadLocal.get() !== this) {
            throw ConcurrentModificationException(STRICT_MODE_ERROR_MESSAGE)
        }
    }

    companion object {
        /**
         * Whether to add additional checks to the coroutine machinery, throwing a
         * `ConcurrentModificationException` if TraceData is modified from the wrong thread. This
         * should only be set for testing.
         */
        internal var strictModeForTesting: Boolean = false
    }
}

private const val INVALID_SPAN_END_CALL_ERROR_MESSAGE =
    "TraceData#endSpan called when there were no active trace sections."

private const val STRICT_MODE_ERROR_MESSAGE =
    "TraceData should only be accessed using " +
        "the ThreadLocal: CURRENT_TRACE.get(). Accessing TraceData by other means, such as " +
        "through the TraceContextElement's property may lead to concurrent modification."
