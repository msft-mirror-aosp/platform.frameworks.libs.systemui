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

import android.annotation.SuppressLint
import android.os.SystemProperties
import android.os.Trace
import android.util.Log
import com.android.systemui.Flags
import java.lang.StackWalker.StackFrame
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CopyableThreadContextElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** Use a final subclass to avoid virtual calls (b/316642146). */
@PublishedApi internal class TraceDataThreadLocal : ThreadLocal<TraceData?>()

/**
 * Thread-local storage for tracking open trace sections in the current coroutine context; it should
 * only be used when paired with a [TraceContextElement].
 *
 * [traceThreadLocal] will be `null` if the code being executed is either 1) not part of coroutine,
 * or 2) part of a coroutine that does not have a [TraceContextElement] in its context. In both
 * cases, writing to this thread-local will result in undefined behavior. However, it is safe to
 * check if [traceThreadLocal] is `null` to determine if coroutine tracing is enabled.
 *
 * @see traceCoroutine
 */
@PublishedApi internal val traceThreadLocal: TraceDataThreadLocal = TraceDataThreadLocal()

private val alwaysEnableStackWalker: Boolean by lazy {
    SystemProperties.getBoolean("debug.coroutine_tracing.walk_stack_override", false)
}

/**
 * Returns a new [TraceContextElement] (or [EmptyCoroutineContext] if `coroutine_tracing` feature is
 * flagged off). This context should only be installed on root coroutines (e.g. when constructing a
 * `CoroutineScope`). The context will be copied automatically to child scopes and thus should not
 * be passed to children explicitly.
 *
 * [TraceContextElement] should be installed on the root, and [CoroutineTraceName] on the children.
 *
 * For example, the following snippet will add trace sections to indicate that `C` is a child of
 * `B`, and `B` was started by `A`. Perfetto will post-process this information to show that: `A ->
 * B -> C`
 *
 * ```
 * val scope = CoroutineScope(createCoroutineTracingContext("A")
 * scope.launch(nameCoroutine("B")) {
 *     // ...
 *     launch(nameCoroutine("C")) {
 *         // ...
 *     }
 *     // ...
 * }
 * ```
 *
 * **NOTE:** The sysprop `debug.coroutine_tracing.walk_stack_override` can be used to override the
 * `walkStackForDefaultNames` parameter, forcing it to always be `true`. If the sysprop is `false`
 * (or does not exist), the value of `walkStackForDefaultNames` is used, whether `true` or `false`.
 *
 * @param name the name of the coroutine scope. Since this should only be installed on top-level
 *   coroutines, this should be the name of the root [CoroutineScope].
 * @param walkStackForDefaultNames whether to walk the stack and use the class name of the current
 *   suspending function if child does not have a name that was manually specified. Walking the
 *   stack is very expensive so this should not be used in production.
 * @param includeParentNames whether to concatenate parent names and sibling counts with the name of
 *   the child. This should only be used for testing because it can result in extremely long trace
 *   names.
 * @param strictMode whether to add additional checks to coroutine tracing machinery. These checks
 *   are expensive and should only be used for testing.
 * @param shouldIgnoreClassName lambda that takes binary class name (as returned from
 *   [StackFrame.getClassName] and returns true if it should be ignored (e.g. search for relevant
 *   class name should continue) or false otherwise
 */
public fun createCoroutineTracingContext(
    name: String = "UnnamedScope",
    walkStackForDefaultNames: Boolean = false,
    includeParentNames: Boolean = false,
    strictMode: Boolean = false,
    shouldIgnoreClassName: (String) -> Boolean = { false },
): CoroutineContext {
    return if (Flags.coroutineTracing()) {
        TraceContextElement(
            name = name,
            // Minor perf optimization: no need to create TraceData() for root scopes since all
            // launches require creation of child via [copyForChild] or [mergeForChild].
            contextTraceData = null,
            inheritedTracePrefix = "",
            coroutineDepth = 0,
            parentId = -1,
            TraceConfig(
                walkStackForDefaultNames = walkStackForDefaultNames || alwaysEnableStackWalker,
                includeParentNames = includeParentNames,
                strictMode = strictMode,
                shouldIgnoreClassName = shouldIgnoreClassName,
            ),
        )
    } else {
        EmptyCoroutineContext
    }
}

/**
 * Returns a new [CoroutineTraceName] (or [EmptyCoroutineContext] if `coroutine_tracing` feature is
 * flagged off). When the current [CoroutineScope] has a [TraceContextElement] installed,
 * [CoroutineTraceName] can be used to name the child scope under construction.
 *
 * [TraceContextElement] should be installed on the root, and [CoroutineTraceName] on the children.
 */
public fun nameCoroutine(name: String): CoroutineContext = nameCoroutine { name }

/**
 * Returns a new [CoroutineTraceName] (or [EmptyCoroutineContext] if `coroutine_tracing` feature is
 * flagged off). When the current [CoroutineScope] has a [TraceContextElement] installed,
 * [CoroutineTraceName] can be used to name the child scope under construction.
 *
 * [TraceContextElement] should be installed on the root, and [CoroutineTraceName] on the children.
 *
 * @param name lazy string to only be called if feature is enabled
 */
@OptIn(ExperimentalContracts::class)
public inline fun nameCoroutine(name: () -> String): CoroutineContext {
    contract { callsInPlace(name, InvocationKind.AT_MOST_ONCE) }
    return if (Flags.coroutineTracing()) CoroutineTraceName(name()) else EmptyCoroutineContext
}

/**
 * Common base class of [TraceContextElement] and [CoroutineTraceName]. For internal use only.
 *
 * [TraceContextElement] should be installed on the root, and [CoroutineTraceName] on the children.
 *
 * @property name the name of the current coroutine
 */
/**
 * A coroutine context element that can be used for naming the child coroutine under construction.
 *
 * @property name the name to be used for the child under construction
 * @see nameCoroutine
 */
@PublishedApi
internal open class CoroutineTraceName(internal val name: String) : CoroutineContext.Element {
    internal companion object Key : CoroutineContext.Key<CoroutineTraceName>

    public override val key: CoroutineContext.Key<*>
        get() = Key

    protected val currentId: Int = ThreadLocalRandom.current().nextInt(1, Int.MAX_VALUE)

    @Deprecated(
        message =
            """
         Operator `+` on two BaseTraceElement objects is meaningless. If used, the context element
         to the right of `+` would simply replace the element to the left. To properly use
         `BaseTraceElement`, `TraceContextElement` should be used when creating a top-level
         `CoroutineScope` and `CoroutineTraceName` should be passed to the child context that is
         under construction.
        """,
        level = DeprecationLevel.ERROR,
    )
    public operator fun plus(other: CoroutineTraceName): CoroutineTraceName {
        debug { "#plus(${other.currentId})" }
        return other
    }

    @OptIn(ExperimentalContracts::class)
    protected inline fun debug(message: () -> String) {
        contract { callsInPlace(message, InvocationKind.AT_MOST_ONCE) }
        if (DEBUG) Log.d(TAG, "${this::class.java.simpleName}@$currentId${message()}")
    }
}

internal data class TraceConfig(
    val walkStackForDefaultNames: Boolean,
    val includeParentNames: Boolean,
    val strictMode: Boolean,
    val shouldIgnoreClassName: (String) -> Boolean,
)

/**
 * Used for tracking parent-child relationship of coroutines and persisting [TraceData] when
 * coroutines are suspended and resumed.
 *
 * This is internal machinery for [traceCoroutine] and should not be used directly.
 *
 * @param name the name of the current coroutine. Since this should only be installed on top-level
 *   coroutines, this should be the name of the root [CoroutineScope].
 * @property contextTraceData [TraceData] to be saved to thread-local storage.
 * @param inheritedTracePrefix prefix containing metadata for parent scopes. Each child is separated
 *   by a `:` and prefixed by a counter indicating the ordinal of this child relative to its
 *   siblings. Thus, the prefix such as `root-name:3^child-name` would indicate this is the 3rd
 *   child (of any name) to be started on `root-scope`. If the child has no name, an empty string
 *   would be used instead: `root-scope:3^`
 * @property coroutineDepth how deep the coroutine is relative to the top-level [CoroutineScope]
 *   containing the original [TraceContextElement] from which this [TraceContextElement] was copied.
 * @param parentId the ID of the parent coroutine, as defined in [BaseTraceElement]
 * @param walkStackForDefaultNames whether to walk the stack and use the class name of the current
 *   suspending function if child does not have a name that was manually specified. Walking the
 *   stack is very expensive so this should not be used in production.
 * @param includeParentNames whether to concatenate parent names and sibling counts with the name of
 *   the child. This should only be used for testing because it can result in extremely long trace
 *   names.
 * @param strictMode whether to add additional checks to coroutine machinery. These checks are
 *   expensive and should only be used for testing.
 * @param shouldIgnoreClassName lambda that takes binary class name (as returned from
 *   [StackFrame.getClassName] and returns true if it should be ignored (e.g. search for relevant
 *   class name should continue) or false otherwise
 * @see createCoroutineTracingContext
 * @see nameCoroutine
 * @see traceCoroutine
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal class TraceContextElement(
    name: String,
    internal val contextTraceData: TraceData?,
    inheritedTracePrefix: String,
    private val coroutineDepth: Int,
    parentId: Int,
    private val config: TraceConfig,
) : CopyableThreadContextElement<TraceData?>, CoroutineTraceName(name) {

    private var childCoroutineCount = AtomicInteger(0)

    private val fullCoroutineTraceName =
        if (config.includeParentNames) "$inheritedTracePrefix$name" else ""
    private val continuationTraceMessage =
        "$fullCoroutineTraceName;$name;d=$coroutineDepth;c=$currentId;p=$parentId"

    init {
        debug { "#init: name=$name" }
        Trace.instant(Trace.TRACE_TAG_APP, continuationTraceMessage)
    }

    /**
     * This function is invoked before the coroutine is resumed on the current thread. When a
     * multi-threaded dispatcher is used, calls to `updateThreadContext` may happen in parallel to
     * the prior `restoreThreadContext` in the same context. However, calls to `updateThreadContext`
     * will not run in parallel on the same context.
     *
     * ```
     * Thread #1 | [updateThreadContext]....^              [restoreThreadContext]
     * --------------------------------------------------------------------------------------------
     * Thread #2 |                           [updateThreadContext]...........^[restoreThreadContext]
     * ```
     *
     * (`...` indicate coroutine body is running; whitespace indicates the thread is not scheduled;
     * `^` is a suspension point)
     */
    @SuppressLint("UnclosedTrace")
    public override fun updateThreadContext(context: CoroutineContext): TraceData? {
        val oldState = traceThreadLocal.get()
        debug { "#updateThreadContext oldState=$oldState" }
        if (oldState !== contextTraceData) {
            Trace.traceBegin(Trace.TRACE_TAG_APP, continuationTraceMessage)
            traceThreadLocal.set(contextTraceData)
            // Calls to `updateThreadContext` will not happen in parallel on the same context, and
            // they cannot happen before the prior suspension point. Additionally,
            // `restoreThreadContext` does not modify `traceData`, so it is safe to iterate over the
            // collection here:
            contextTraceData?.beginAllOnThread()
        }
        return oldState
    }

    /**
     * This function is invoked after the coroutine has suspended on the current thread. When a
     * multi-threaded dispatcher is used, calls to `restoreThreadContext` may happen in parallel to
     * the subsequent `updateThreadContext` and `restoreThreadContext` operations. The coroutine
     * body itself will not run in parallel, but `TraceData` could be modified by a coroutine body
     * after the suspension point in parallel to `restoreThreadContext` associated with the
     * coroutine body _prior_ to the suspension point.
     *
     * ```
     * Thread #1 | [updateThreadContext].x..^              [restoreThreadContext]
     * --------------------------------------------------------------------------------------------
     * Thread #2 |                           [updateThreadContext]..x..x.....^[restoreThreadContext]
     * ```
     *
     * OR
     *
     * ```
     * Thread #1 |                                 [restoreThreadContext]
     * --------------------------------------------------------------------------------------------
     * Thread #2 |     [updateThreadContext]...x....x..^[restoreThreadContext]
     * ```
     *
     * (`...` indicate coroutine body is running; whitespace indicates the thread is not scheduled;
     * `^` is a suspension point; `x` are calls to modify the thread-local trace data)
     *
     * ```
     */
    public override fun restoreThreadContext(context: CoroutineContext, oldState: TraceData?) {
        debug { "#restoreThreadContext restoring=$oldState" }
        // We not use the `TraceData` object here because it may have been modified on another
        // thread after the last suspension point. This is why we use a [TraceStateHolder]:
        // so we can end the correct number of trace sections, restoring the thread to its state
        // prior to the last call to [updateThreadContext].
        if (oldState !== traceThreadLocal.get()) {
            contextTraceData?.endAllOnThread()
            traceThreadLocal.set(oldState)
            Trace.traceEnd(Trace.TRACE_TAG_APP) // end: currentScopeTraceMessage
        }
    }

    public override fun copyForChild(): CopyableThreadContextElement<TraceData?> {
        debug { "#copyForChild" }
        return createChildContext()
    }

    public override fun mergeForChild(
        overwritingElement: CoroutineContext.Element
    ): CoroutineContext {
        debug { "#mergeForChild" }
        if (DEBUG) {
            (overwritingElement as? TraceContextElement)?.let {
                Log.e(
                    TAG,
                    "${this::class.java.simpleName}@$currentId#mergeForChild(@${it.currentId}): " +
                        "current name=\"$name\", overwritingElement name=\"${it.name}\". " +
                        UNEXPECTED_TRACE_DATA_ERROR_MESSAGE,
                )
            }
        }
        val nameForChild = (overwritingElement as CoroutineTraceName).name
        return createChildContext(nameForChild)
    }

    private fun createChildContext(
        name: String =
            if (config.walkStackForDefaultNames) walkStackForClassName(config.shouldIgnoreClassName)
            else ""
    ): TraceContextElement {
        debug { "#createChildContext: \"$name\" has new child with name \"${name}\"" }
        val childCount = childCoroutineCount.incrementAndGet()
        return TraceContextElement(
            name = name,
            contextTraceData = TraceData(config.strictMode),
            inheritedTracePrefix =
                if (config.includeParentNames) "$fullCoroutineTraceName:$childCount^" else "",
            coroutineDepth = coroutineDepth + 1,
            parentId = currentId,
            config = config,
        )
    }
}

/**
 * Get a name for the trace section include the name of the call site.
 *
 * @param additionalDropPredicate additional checks for whether class should be ignored
 */
private fun walkStackForClassName(
    additionalDropPredicate: (String) -> Boolean = { false }
): String {
    Trace.traceBegin(Trace.TRACE_TAG_APP, "walkStackForClassName")
    try {
        var frame = ""
        StackWalker.getInstance().walk { s: Stream<StackFrame> ->
            s.dropWhile { f: StackFrame ->
                    val className = f.className
                    className.startsWith("kotlin") ||
                        className.startsWith("com.android.app.tracing.") ||
                        additionalDropPredicate(className)
                }
                .findFirst()
                .ifPresent { frame = it.className.substringAfterLast(".") + "." + it.methodName }
        }
        return frame
    } catch (e: Exception) {
        if (DEBUG) Log.e(TAG, "Error walking stack to infer a trace name", e)
        return ""
    } finally {
        Trace.traceEnd(Trace.TRACE_TAG_APP)
    }
}

private const val UNEXPECTED_TRACE_DATA_ERROR_MESSAGE =
    "Overwriting context element with non-empty trace data. There should only be one " +
        "TraceContextElement per coroutine, and it should be installed in the root scope. "

@PublishedApi internal const val TAG: String = "CoroutineTracing"

@PublishedApi internal const val DEBUG: Boolean = false
