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

import com.android.systemui.Flags
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

@OptIn(ExperimentalContracts::class)
public suspend inline fun <R> coroutineScopeTraced(
    traceName: String,
    crossinline block: suspend CoroutineScope.() -> R,
): R {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    return coroutineScope {
        traceCoroutine(traceName) {
            return@coroutineScope block()
        }
    }
}

/**
 * Convenience function for calling [CoroutineScope.launch] with [traceCoroutine] to enable tracing.
 *
 * @see traceCoroutine
 */
public inline fun CoroutineScope.launchTraced(
    crossinline spanName: () -> String,
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    noinline block: suspend CoroutineScope.() -> Unit,
): Job {
    return launch(nameCoroutine(spanName) + context, start, block)
}

/**
 * Convenience function for calling [CoroutineScope.launch] with [traceCoroutine] to enable tracing.
 *
 * @see traceCoroutine
 */
public fun CoroutineScope.launchTraced(
    spanName: String? = null,
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend CoroutineScope.() -> Unit,
): Job = launchTraced({ spanName ?: block::class.simpleName ?: "launch" }, context, start, block)

/**
 * Convenience function for calling [CoroutineScope.async] with [traceCoroutine] enable tracing
 *
 * @see traceCoroutine
 */
public inline fun <T> CoroutineScope.asyncTraced(
    spanName: () -> String,
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    noinline block: suspend CoroutineScope.() -> T,
): Deferred<T> = async(nameCoroutine(spanName) + context, start, block)

/**
 * Convenience function for calling [CoroutineScope.async] with [traceCoroutine] enable tracing.
 *
 * @see traceCoroutine
 */
public fun <T> CoroutineScope.asyncTraced(
    spanName: String? = null,
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend CoroutineScope.() -> T,
): Deferred<T> =
    asyncTraced({ spanName ?: block::class.simpleName ?: "async" }, context, start, block)

/**
 * Convenience function for calling [runBlocking] with [traceCoroutine] to enable tracing.
 *
 * @see traceCoroutine
 */
public inline fun <T> runBlockingTraced(
    spanName: () -> String,
    context: CoroutineContext,
    noinline block: suspend CoroutineScope.() -> T,
): T = runBlocking(nameCoroutine(spanName) + context, block)

/**
 * Convenience function for calling [runBlocking] with [traceCoroutine] to enable tracing.
 *
 * @see traceCoroutine
 */
public fun <T> runBlockingTraced(
    spanName: String? = null,
    context: CoroutineContext,
    block: suspend CoroutineScope.() -> T,
): T = runBlockingTraced({ spanName ?: block::class.simpleName ?: "runBlocking" }, context, block)

/**
 * Convenience function for calling [withContext] with [traceCoroutine] to enable tracing.
 *
 * @see traceCoroutine
 */
public suspend fun <T> withContextTraced(
    spanName: String? = null,
    context: CoroutineContext,
    block: suspend CoroutineScope.() -> T,
): T = withContextTraced({ spanName ?: block::class.simpleName ?: "withContext" }, context, block)

/**
 * Convenience function for calling [withContext] with [traceCoroutine] to enable tracing.
 *
 * @see traceCoroutine
 */
public suspend inline fun <T> withContextTraced(
    spanName: () -> String,
    context: CoroutineContext,
    noinline block: suspend CoroutineScope.() -> T,
): T = withContext(nameCoroutine(spanName) + context, block)

/**
 * Traces a section of work of a `suspend` [block]. The trace sections will appear on the thread
 * that is currently executing the [block] of work. If the [block] is suspended, all trace sections
 * added using this API will end until the [block] is resumed, which could happen either on this
 * thread or on another thread. If a child coroutine is started, it will *NOT* inherit the trace
 * sections of its parent; however, it will include metadata in the trace section pointing to the
 * parent.
 *
 * The current [CoroutineContext] must have a [TraceContextElement] for this API to work. Otherwise,
 * the trace sections will be dropped.
 *
 * For example, in the following trace, Thread #1 starts a coroutine, suspends, and continues the
 * coroutine on Thread #2. Next, Thread #2 start a child coroutine in an unconfined manner. Then,
 * still on Thread #2, the original coroutine suspends, the child resumes, and the child suspends.
 * Then, the original coroutine resumes on Thread#1.
 *
 * ```
 * -----------------------------------------------------------------------------------------------|
 * Thread #1 | [== Slice A ==]                                            [==== Slice A ====]
 *           |       [== B ==]                                            [=== B ===]
 * -----------------------------------------------------------------------------------------------|
 * Thread #2 |                     [==== Slice A ====]    [=== C ====]
 *           |                     [======= B =======]
 *           |                         [=== C ====]
 * -----------------------------------------------------------------------------------------------|
 * ```
 *
 * @param spanName The name of the code section to appear in the trace
 * @see traceCoroutine
 */
@OptIn(ExperimentalContracts::class)
public inline fun <T> traceCoroutine(spanName: () -> String, block: () -> T): T {
    contract {
        callsInPlace(spanName, InvocationKind.AT_MOST_ONCE)
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }

    // For coroutine tracing to work, trace spans must be added and removed even when
    // tracing is not active (i.e. when TRACE_TAG_APP is disabled). Otherwise, when the
    // coroutine resumes when tracing is active, we won't know its name.
    val traceData = if (Flags.coroutineTracing()) traceThreadLocal.get() else null
    traceData?.beginSpan(spanName())
    try {
        return block()
    } finally {
        traceData?.endSpan()
    }
}

/** @see traceCoroutine */
public inline fun <T> traceCoroutine(spanName: String, block: () -> T): T =
    traceCoroutine({ spanName }, block)
