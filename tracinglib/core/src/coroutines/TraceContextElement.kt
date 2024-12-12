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

private val alwaysEnableContinuationCounting: Boolean by lazy {
    SystemProperties.getBoolean("debug.coroutine_tracing.count_continuations_override", false)
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
 * **NOTE:** The sysprops `debug.coroutine_tracing.walk_stack_override` and
 * `debug.coroutine_tracing.count_continuations_override` can be used to override the parameters
 * `walkStackForDefaultNames` and `countContinuations` respectively, forcing them to always be
 * `true`. If the sysprop is `false` (or does not exist), the value of the parameter is passed here
 * is used. If `true`, all calls to [createCoroutineTracingContext] will be overwritten with that
 * parameter set to `true`. Importantly, this means that the sysprops can be used to globally turn
 * ON `walkStackForDefaultNames` or `countContinuations`, but they cannot be used to globally turn
 * OFF either parameter.
 *
 * @param name the name of the coroutine scope. Since this should only be installed on top-level
 *   coroutines, this should be the name of the root [CoroutineScope].
 * @param walkStackForDefaultNames whether to walk the stack and use the class name of the current
 *   suspending function if child does not have a name that was manually specified. Walking the
 *   stack is very expensive so this should not be used in production.
 * @param countContinuations whether to include an extra trace section showing the total number of
 *   times a coroutine has suspended and resumed.
 * @param testMode changes behavior is several ways: 1) parent names and sibling counts are
 *   concatenated with the name of the child. This can result in extremely long trace names, which
 *   is why it is only for testing. 2) additional strict-mode checks are added to coroutine tracing
 *   machinery. These checks are expensive and should only be used for testing. 3) omits "coroutine
 *   execution" trace slices, and omits coroutine metadata slices
 * @param shouldIgnoreClassName lambda that takes binary class name (as returned from
 *   [StackFrame.getClassName] and returns true if it should be ignored (e.g. search for relevant
 *   class name should continue) or false otherwise.
 */
public fun createCoroutineTracingContext(
    name: String = "UnnamedScope",
    walkStackForDefaultNames: Boolean = false,
    countContinuations: Boolean = false,
    testMode: Boolean = false,
    shouldIgnoreClassName: (String) -> Boolean = { false },
): CoroutineContext {
    return if (Flags.coroutineTracing()) {
        TraceContextElement(
            name = name,
            // Minor perf optimization: no need to create TraceData() for root scopes since all
            // launches require creation of child via [copyForChild] or [mergeForChild].
            contextTraceData = null,
            config =
                TraceConfig(
                    walkStackForDefaultNames = walkStackForDefaultNames || alwaysEnableStackWalker,
                    testMode = testMode,
                    shouldIgnoreClassName = shouldIgnoreClassName,
                    countContinuations = countContinuations || alwaysEnableContinuationCounting,
                ),
            parentId = null,
            inheritedTracePrefix = "",
            coroutineDepth = 0,
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

internal class TraceConfig(
    val walkStackForDefaultNames: Boolean,
    val testMode: Boolean,
    val shouldIgnoreClassName: (String) -> Boolean,
    val countContinuations: Boolean,
)

/**
 * Used for tracking parent-child relationship of coroutines and persisting [TraceData] when
 * coroutines are suspended and resumed.
 *
 * This is internal machinery for [traceCoroutine] and should not be used directly.
 *
 * @param name The name of the current coroutine. Since this should only be installed on top-level
 *   coroutines, this should be the name of the root [CoroutineScope].
 * @property contextTraceData [TraceData] to be saved to thread-local storage.
 * @property config Configuration parameters
 * @param parentId The ID of the parent coroutine, as defined in [BaseTraceElement]
 * @param inheritedTracePrefix Prefix containing metadata for parent scopes. Each child is separated
 *   by a `:` and prefixed by a counter indicating the ordinal of this child relative to its
 *   siblings. Thus, the prefix such as `root-name:3^child-name` would indicate this is the 3rd
 *   child (of any name) to be started on `root-scope`. If the child has no name, an empty string
 *   would be used instead: `root-scope:3^`
 * @param coroutineDepth How deep the coroutine is relative to the top-level [CoroutineScope]
 *   containing the original [TraceContextElement] from which this [TraceContextElement] was copied.
 * @see createCoroutineTracingContext
 * @see nameCoroutine
 * @see traceCoroutine
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal class TraceContextElement(
    name: String,
    internal val contextTraceData: TraceData?,
    private val config: TraceConfig,
    parentId: Int?,
    inheritedTracePrefix: String,
    coroutineDepth: Int,
) : CopyableThreadContextElement<TraceData?>, CoroutineTraceName(name) {

    private val coroutineTraceName =
        if (config.testMode) "$inheritedTracePrefix$name"
        else "$name;d=$coroutineDepth;c=$currentId;p=${parentId ?: "none"}"

    init {
        debug { "#init: name=$name" }
        Trace.traceBegin(Trace.TRACE_TAG_APP, "TraceContextElement#init[$coroutineTraceName]")
    }

    private var continuationCount = 0
    private val childDepth = coroutineDepth + 1
    private var childCoroutineCount = if (config.testMode) AtomicInteger(0) else null

    private val copyForChildTraceMessage = "TraceContextElement#copyForChild[$coroutineTraceName]"
    private val mergeForChildTraceMessage = "TraceContextElement#mergeForChild[$coroutineTraceName]"

    init {
        Trace.traceEnd(Trace.TRACE_TAG_APP)
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
            if (!config.testMode) {
                Trace.traceBegin(Trace.TRACE_TAG_APP, "coroutine execution")
            }
            Trace.traceBegin(Trace.TRACE_TAG_APP, coroutineTraceName)
            if (config.countContinuations) {
                Trace.traceBegin(Trace.TRACE_TAG_APP, "continuation: #${continuationCount++}")
            }
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
            if (!config.testMode) {
                Trace.traceEnd(Trace.TRACE_TAG_APP) // end: "coroutine execution"
            }
            Trace.traceEnd(Trace.TRACE_TAG_APP) // end: contextMetadata
            if (config.countContinuations) {
                Trace.traceEnd(Trace.TRACE_TAG_APP) // end: continuation: #
            }
        }
    }

    public override fun copyForChild(): CopyableThreadContextElement<TraceData?> {
        debug { "#copyForChild" }
        try {
            Trace.traceBegin(Trace.TRACE_TAG_APP, copyForChildTraceMessage)
            return createChildContext()
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_APP) // end: copyForChildTraceMessage
        }
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
        try {
            Trace.traceBegin(Trace.TRACE_TAG_APP, mergeForChildTraceMessage)
            val nameForChild = (overwritingElement as CoroutineTraceName).name
            return createChildContext(nameForChild)
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_APP) // end: mergeForChildTraceMessage
        }
    }

    private fun createChildContext(name: String? = null): TraceContextElement {
        val childName =
            name
                ?: if (config.walkStackForDefaultNames)
                    walkStackForClassName(config.shouldIgnoreClassName)
                else ""
        debug { "#createChildContext: \"${this.name}\" has new child with name \"${childName}\"" }
        return TraceContextElement(
            name = childName,
            contextTraceData = TraceData(strictMode = config.testMode),
            config = config,
            parentId = currentId,
            inheritedTracePrefix =
                if (config.testMode) {
                    val childCount = childCoroutineCount?.incrementAndGet() ?: 0
                    "$coroutineTraceName:$childCount^"
                } else "",
            coroutineDepth = childDepth,
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
