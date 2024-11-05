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

package com.android.app.tracing.coroutines.flow

import com.android.app.tracing.coroutines.nameCoroutine
import com.android.app.tracing.coroutines.traceCoroutine
import com.android.systemui.Flags
import kotlin.experimental.ExperimentalTypeInference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow as safeFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform

/** @see kotlinx.coroutines.flow.internal.unsafeFlow */
@PublishedApi
internal inline fun <T> unsafeFlow(
    crossinline block: suspend FlowCollector<T>.() -> Unit
): Flow<T> {
    return object : Flow<T> {
        override suspend fun collect(collector: FlowCollector<T>) {
            collector.block()
        }
    }
}

/** @see kotlinx.coroutines.flow.unsafeTransform */
@PublishedApi
internal inline fun <T, R> Flow<T>.unsafeTransform(
    crossinline transform: suspend FlowCollector<R>.(value: T) -> Unit
): Flow<R> = unsafeFlow { collect { value -> transform(value) } }

/**
 * Helper for naming the coroutine a flow is collected in. This only has an effect if the flow
 * changes contexts (e.g. `flowOn()` is used to change the dispatcher), meaning a new coroutine is
 * created during collection.
 *
 * For example, the following would `emit(1)` from a trace section named "a" and collect in section
 * named "b".
 *
 * ```
 *   launch(nameCoroutine("b") {
 *     val flow {
 *       emit(1)
 *     }
 *     .flowName("a")
 *     .flowOn(Dispatchers.Default)
 *     .collect {
 *     }
 *   }
 * ```
 */
public fun <T> Flow<T>.flowName(name: String): Flow<T> = flowOn(nameCoroutine(name))

/**
 * Applying [flowName][Flow.flowName] to [SharedFlow] has no effect. See the [SharedFlow]
 * documentation on Operator Fusion.
 *
 * @see SharedFlow.flowOn
 */
@Deprecated(
    level = DeprecationLevel.ERROR,
    message =
        "Applying 'flowName' to SharedFlow has no effect. See the SharedFlow documentation on Operator Fusion.",
    replaceWith = ReplaceWith("this"),
)
@Suppress("UnusedReceiverParameter")
public fun <T> SharedFlow<T>.flowName(@Suppress("UNUSED_PARAMETER") name: String): Flow<T> =
    throw UnsupportedOperationException("Not implemented, should not be called")

/**
 * NOTE: [Flow.collect] is a member function and takes precedence if this function is imported as
 * `collect` and the default parameter is used. (In Kotlin, when an extension function has the same
 * receiver type, name, and applicable arguments as a class member function, the member takes
 * precedence).
 *
 * For example,
 * ```
 * import com.android.app.tracing.coroutines.flow.collectTraced as collect
 * ...
 * flowOf(1).collect { ... } // this will call `Flow.collect`
 * flowOf(1).collect(null) { ... } // this will call `collectTraced`
 * ```
 */
public suspend fun <T> Flow<T>.collectTraced(name: String, collector: FlowCollector<T>) {
    if (Flags.coroutineTracing()) {
        val collectName = "collect:$name"
        val emitName = "$collectName:emit"
        traceCoroutine(collectName) { collect { traceCoroutine(emitName) { collector.emit(it) } } }
    } else {
        collect(collector)
    }
}

/** @see Flow.collectTraced */
public suspend fun <T> Flow<T>.collectTraced(collector: FlowCollector<T>) {
    if (Flags.coroutineTracing()) {
        collectTraced(
            name = collector::class.java.name.substringAfterLast("."),
            collector = collector,
        )
    } else {
        collect(collector)
    }
}

internal suspend fun <T> Flow<T>.collectLatestTraced(
    name: String,
    action: suspend (value: T) -> Unit,
) {
    if (Flags.coroutineTracing()) {
        val collectName = "collectLatest:$name"
        val actionName = "$collectName:action"
        return traceCoroutine(collectName) {
            collectLatest { traceCoroutine(actionName) { action(it) } }
        }
    } else {
        collectLatest(action)
    }
}

public suspend fun <T> Flow<T>.collectLatestTraced(action: suspend (value: T) -> Unit) {
    if (Flags.coroutineTracing()) {
        collectLatestTraced(action::class.java.name.substringAfterLast("."), action)
    } else {
        collectLatest(action)
    }
}

/** @see kotlinx.coroutines.flow.transform */
@OptIn(ExperimentalTypeInference::class)
public inline fun <T, R> Flow<T>.transformTraced(
    name: String,
    @BuilderInference crossinline transform: suspend FlowCollector<R>.(value: T) -> Unit,
): Flow<R> =
    if (Flags.coroutineTracing()) {
        val emitName = "$name:emit"
        safeFlow { collect { value -> traceCoroutine(emitName) { transform(value) } } }
    } else {
        transform(transform)
    }

public inline fun <T> Flow<T>.filterTraced(
    name: String,
    crossinline predicate: suspend (T) -> Boolean,
): Flow<T> {
    if (Flags.coroutineTracing()) {
        val predicateName = "filter:$name:predicate"
        val emitName = "filter:$name:emit"
        return unsafeTransform { value ->
            if (traceCoroutine(predicateName) { predicate(value) }) {
                traceCoroutine(emitName) {
                    return@unsafeTransform emit(value)
                }
            }
        }
    } else {
        return filter(predicate)
    }
}

public inline fun <T, R> Flow<T>.mapTraced(
    name: String,
    crossinline transform: suspend (value: T) -> R,
): Flow<R> {
    if (Flags.coroutineTracing()) {
        val transformName = "map:$name:transform"
        val emitName = "map:$name:emit"
        return unsafeTransform { value ->
            val transformedValue = traceCoroutine(transformName) { transform(value) }
            traceCoroutine(emitName) {
                return@unsafeTransform emit(transformedValue)
            }
        }
    } else {
        return map(transform)
    }
}
