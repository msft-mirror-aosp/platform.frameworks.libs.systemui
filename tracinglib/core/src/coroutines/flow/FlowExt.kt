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

@file:OptIn(ExperimentalTypeInference::class)

package com.android.app.tracing.coroutines.flow

import com.android.app.tracing.coroutines.CoroutineTraceName
import com.android.app.tracing.coroutines.traceCoroutine
import com.android.app.tracing.coroutines.traceName
import kotlin.experimental.ExperimentalTypeInference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow as safeFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform

/** @see kotlinx.coroutines.flow.internal.unsafeFlow */
@PublishedApi
internal inline fun <T> unsafeFlow(
    name: String,
    crossinline block: suspend FlowCollector<T>.() -> Unit,
): Flow<T> {
    return object : Flow<T> {
        override suspend fun collect(collector: FlowCollector<T>) {
            traceCoroutine("collect:$name") { collector.block() }
        }
    }
}

/** @see kotlinx.coroutines.flow.unsafeTransform */
@PublishedApi
internal inline fun <T, R> Flow<T>.unsafeTransform(
    name: String,
    crossinline transform: suspend FlowCollector<R>.(value: T) -> Unit,
): Flow<R> = unsafeFlow(name) { collect { value -> transform(value) } }

/**
 * Helper for adding trace sections for when a trace is collected.
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
 *     .collect {
 *       // The open trace sections here would be "collect:a" and "a:emit"
 *     }
 *   }
 * ```
 */
public fun <T> Flow<T>.flowName(name: String): Flow<T> {
    return if (com.android.systemui.Flags.coroutineTracing()) {
        unsafeTransform(name) { traceCoroutine("emit") { emit(it) } }
    } else {
        this
    }
}

public fun <T> Flow<T>.onEachTraced(name: String, action: suspend (T) -> Unit): Flow<T> {
    return if (com.android.systemui.Flags.coroutineTracing()) {
        unsafeTransform(name) { value ->
            traceCoroutine("onEach:action") { action(value) }
            traceCoroutine("onEach:emit") { emit(value) }
        }
    } else {
        onEach(action)
    }
}

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
 *
 * @see kotlinx.coroutines.flow.collect
 */
public suspend fun <T> Flow<T>.collectTraced(name: String, collector: FlowCollector<T>) {
    if (com.android.systemui.Flags.coroutineTracing()) {
        flowName(name).collect(collector)
    } else {
        collect(collector)
    }
}

/** @see kotlinx.coroutines.flow.collect */
public suspend fun <T> Flow<T>.collectTraced(name: String) {
    if (com.android.systemui.Flags.coroutineTracing()) {
        flowName(name).collect()
    } else {
        collect()
    }
}

/** @see kotlinx.coroutines.flow.collect */
public suspend fun <T> Flow<T>.collectTraced(collector: FlowCollector<T>) {
    if (com.android.systemui.Flags.coroutineTracing()) {
        collectTraced(name = collector.traceName, collector = collector)
    } else {
        collect(collector)
    }
}

@ExperimentalCoroutinesApi
public fun <T, R> Flow<T>.mapLatestTraced(
    name: String,
    @BuilderInference transform: suspend (value: T) -> R,
): Flow<R> {
    return if (com.android.systemui.Flags.coroutineTracing()) {
        val collectName = "mapLatest:$name"
        val actionName = "$collectName:transform"
        traceCoroutine(collectName) { mapLatest { traceCoroutine(actionName) { transform(it) } } }
    } else {
        mapLatest(transform)
    }
}

@ExperimentalCoroutinesApi
public fun <T, R> Flow<T>.mapLatestTraced(
    @BuilderInference transform: suspend (value: T) -> R
): Flow<R> {
    return if (com.android.systemui.Flags.coroutineTracing()) {
        mapLatestTraced(transform.traceName, transform)
    } else {
        mapLatestTraced(transform)
    }
}

/** @see kotlinx.coroutines.flow.collectLatest */
internal suspend fun <T> Flow<T>.collectLatestTraced(
    name: String,
    action: suspend (value: T) -> Unit,
) {
    if (com.android.systemui.Flags.coroutineTracing()) {
        val collectName = "collectLatest:$name"
        val actionName = "$collectName:action"
        return traceCoroutine(collectName) {
            collectLatest { traceCoroutine(actionName) { action(it) } }
        }
    } else {
        collectLatest(action)
    }
}

/** @see kotlinx.coroutines.flow.collectLatest */
public suspend fun <T> Flow<T>.collectLatestTraced(action: suspend (value: T) -> Unit) {
    if (com.android.systemui.Flags.coroutineTracing()) {
        collectLatestTraced(action.traceName, action)
    } else {
        collectLatest(action)
    }
}

/** @see kotlinx.coroutines.flow.transform */
@OptIn(ExperimentalTypeInference::class)
public inline fun <T, R> Flow<T>.transformTraced(
    name: String,
    @BuilderInference crossinline transform: suspend FlowCollector<R>.(value: T) -> Unit,
): Flow<R> {
    return if (com.android.systemui.Flags.coroutineTracing()) {
        // Safe flow must be used because collector is exposed to the caller
        safeFlow { collect { value -> traceCoroutine("$name:transform") { transform(value) } } }
    } else {
        transform(transform)
    }
}

/** @see kotlinx.coroutines.flow.filter */
public inline fun <T> Flow<T>.filterTraced(
    name: String,
    crossinline predicate: suspend (T) -> Boolean,
): Flow<T> {
    return if (com.android.systemui.Flags.coroutineTracing()) {
        unsafeTransform(name) { value ->
            if (traceCoroutine("filter:predicate") { predicate(value) }) {
                traceCoroutine("filter:emit") { emit(value) }
            }
        }
    } else {
        filter(predicate)
    }
}

/** @see kotlinx.coroutines.flow.map */
public inline fun <T, R> Flow<T>.mapTraced(
    name: String,
    crossinline transform: suspend (value: T) -> R,
): Flow<R> {
    return if (com.android.systemui.Flags.coroutineTracing()) {
        unsafeTransform(name) { value ->
            val transformedValue = traceCoroutine("map:transform") { transform(value) }
            traceCoroutine("map:emit") { emit(transformedValue) }
        }
    } else {
        map(transform)
    }
}

/** @see kotlinx.coroutines.flow.shareIn */
public fun <T> Flow<T>.shareInTraced(
    name: String,
    scope: CoroutineScope,
    started: SharingStarted,
    replay: Int = 0,
): SharedFlow<T> {
    // .shareIn calls this.launch(context), where this === scope, and the previous upstream flow's
    // context is passed to launch
    return if (com.android.systemui.Flags.coroutineTracing()) {
            flowOn(CoroutineTraceName(name))
        } else {
            this
        }
        .shareIn(scope, started, replay)
}

/** @see kotlinx.coroutines.flow.stateIn */
public fun <T> Flow<T>.stateInTraced(
    name: String,
    scope: CoroutineScope,
    started: SharingStarted,
    initialValue: T,
): StateFlow<T> {
    // .stateIn calls this.launch(context), where this === scope, and the previous upstream flow's
    // context is passed to launch
    return if (com.android.systemui.Flags.coroutineTracing()) {
            flowOn(CoroutineTraceName(name))
        } else {
            this
        }
        .stateIn(scope, started, initialValue)
}

/** @see kotlinx.coroutines.flow.stateIn */
public suspend fun <T> Flow<T>.stateInTraced(name: String, scope: CoroutineScope): StateFlow<T> {
    // .stateIn calls this.launch(context), where this === scope, and the previous upstream flow's
    return if (com.android.systemui.Flags.coroutineTracing()) {
            flowOn(CoroutineTraceName(name))
        } else {
            this
        }
        .stateIn(scope)
}
