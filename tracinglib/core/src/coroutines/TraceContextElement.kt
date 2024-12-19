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
import kotlin.coroutines.AbstractCoroutineContextKey
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.getPolymorphicElement
import kotlin.coroutines.minusPolymorphicKey
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

private val alwaysEnableStackWalker =
    SystemProperties.getBoolean("debug.coroutine_tracing.walk_stack_override", false)

private val alwaysEnableContinuationCounting =
    SystemProperties.getBoolean("debug.coroutine_tracing.count_continuations_override", false)

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
 * @param countContinuations whether to include extra info in the trace section indicating the total
 *   number of times a coroutine has suspended and resumed (e.g. ";n=#")
 * @param countDepth whether to include extra info in the trace section indicating the how far from
 *   the root trace context this coroutine is (e.g. ";d=#")
 * @param testMode changes behavior is several ways: 1) parent names and sibling counts are
 *   concatenated with the name of the child. This can result in extremely long trace names, which
 *   is why it is only for testing. 2) additional strict-mode checks are added to coroutine tracing
 *   machinery. These checks are expensive and should only be used for testing. 3) omits "coroutine
 *   execution" trace slices, and omits coroutine metadata slices. If [testMode] is enabled,
 *   [countContinuations] and [countDepth] are ignored.
 * @param shouldIgnoreClassName lambda that takes binary class name (as returned from
 *   [StackFrame.getClassName] and returns true if it should be ignored (e.g. search for relevant
 *   class name should continue) or false otherwise.
 */
public fun createCoroutineTracingContext(
    name: String = "UnnamedScope",
    countContinuations: Boolean = false,
    countDepth: Boolean = false,
    testMode: Boolean = false,
    walkStackForDefaultNames: Boolean = false,
    shouldIgnoreClassName: (String) -> Boolean = { false },
): CoroutineContext {
    return if (Flags.coroutineTracing()) {
        TraceContextElement(
            name = name,
            isRoot = true,
            countContinuations =
                !testMode && (countContinuations || alwaysEnableContinuationCounting),
            walkStackForDefaultNames = walkStackForDefaultNames || alwaysEnableStackWalker,
            shouldIgnoreClassName = shouldIgnoreClassName,
            parentId = null,
            inheritedTracePrefix = if (testMode) "" else null,
            coroutineDepth = if (!testMode && countDepth) 0 else -1,
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
 * [TraceContextElement] should be installed on the root, and [CoroutineTraceName] should ONLY be
 * used when launching children to give them names.
 *
 * @property name the name of the current coroutine
 */
/**
 * A coroutine context element that can be used for naming the child coroutine under construction.
 *
 * @property name the name to be used for the child under construction
 * @see nameCoroutine
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
@PublishedApi
internal open class CoroutineTraceName(internal val name: String) :
    CopyableThreadContextElement<TraceData?>, CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<CoroutineTraceName>

    override val key: CoroutineContext.Key<*>
        get() = Key

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
        debug { "CTN#plus;c=$name other=${other.name}" }
        return other
    }

    @OptIn(ExperimentalStdlibApi::class)
    override fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? =
        getPolymorphicElement(key)

    @OptIn(ExperimentalStdlibApi::class)
    override fun minusKey(key: CoroutineContext.Key<*>): CoroutineContext = minusPolymorphicKey(key)

    override fun copyForChild(): CopyableThreadContextElement<TraceData?> {
        // Reusing this object is okay because we aren't persisting to thread local store.
        // This object only exists for the purpose of storing a String for future use
        // by TraceContextElement.
        return this
    }

    override fun mergeForChild(overwritingElement: CoroutineContext.Element): CoroutineContext {
        return if (overwritingElement is TraceContextElement) {
            overwritingElement.createChildContext(name)
        } else if (overwritingElement is CoroutineTraceName) {
            overwritingElement
        } else {
            EmptyCoroutineContext
        }
    }

    override fun updateThreadContext(context: CoroutineContext): TraceData? {
        return null
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: TraceData?) {}
}

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
 *   If -1, counting depth is disabled
 * @see createCoroutineTracingContext
 * @see nameCoroutine
 * @see traceCoroutine
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal class TraceContextElement(
    name: String,
    private val isRoot: Boolean,
    private val countContinuations: Boolean,
    private val walkStackForDefaultNames: Boolean,
    private val shouldIgnoreClassName: (String) -> Boolean,
    parentId: Int?,
    inheritedTracePrefix: String?,
    coroutineDepth: Int,
) : CoroutineTraceName(name) {

    protected val currentId: Int = ThreadLocalRandom.current().nextInt(1, Int.MAX_VALUE)

    @OptIn(ExperimentalStdlibApi::class)
    companion object Key :
        AbstractCoroutineContextKey<CoroutineTraceName, TraceContextElement>(
            CoroutineTraceName,
            { it as? TraceContextElement },
        )

    init {
        val traceSection = "TCE#init name=$name"
        debug { traceSection }
        Trace.traceBegin(Trace.TRACE_TAG_APP, traceSection)
    }

    // Minor perf optimization: no need to create TraceData() for root scopes since all launches
    // require creation of child via [copyForChild] or [mergeForChild].
    internal val contextTraceData: TraceData? =
        if (isRoot) null else TraceData(currentId, strictMode = inheritedTracePrefix != null)

    private val coroutineTraceName =
        if (inheritedTracePrefix == null) {
            "coroutine execution;$name;c=$currentId;p=${parentId ?: "none"}${if (coroutineDepth == -1) "" else ";d=$coroutineDepth"}"
        } else {
            "$inheritedTracePrefix$name"
        }

    private var continuationCount = 0
    private val childDepth =
        if (inheritedTracePrefix != null || coroutineDepth == -1) -1 else coroutineDepth + 1
    private val childCoroutineCount = if (inheritedTracePrefix != null) AtomicInteger(0) else null

    private val copyForChildTraceMessage = "TCE#copy;c=$currentId]"
    private val mergeForChildTraceMessage = "TCE#merge;c=$currentId]"

    init {
        Trace.traceEnd(Trace.TRACE_TAG_APP) // end: "TCE#init"
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
        debug { "TCE#update;c=$currentId oldState=${oldState?.currentId}" }
        if (oldState !== contextTraceData) {
            traceThreadLocal.set(contextTraceData)
            Trace.traceBegin(
                Trace.TRACE_TAG_APP,
                if (countContinuations) "$coroutineTraceName;n=${continuationCount++}"
                else coroutineTraceName,
            )
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
        debug { "TCE#restore;c=$currentId restoring=${oldState?.currentId}" }
        // We not use the `TraceData` object here because it may have been modified on another
        // thread after the last suspension point. This is why we use a [TraceStateHolder]:
        // so we can end the correct number of trace sections, restoring the thread to its state
        // prior to the last call to [updateThreadContext].
        if (oldState !== traceThreadLocal.get()) {
            contextTraceData?.endAllOnThread()
            traceThreadLocal.set(oldState)
            Trace.traceEnd(Trace.TRACE_TAG_APP) // end: coroutineTraceName
        }
    }

    public override fun copyForChild(): CopyableThreadContextElement<TraceData?> {
        debug { copyForChildTraceMessage }
        try {
            Trace.traceBegin(Trace.TRACE_TAG_APP, copyForChildTraceMessage)
            return createChildContext(null)
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_APP) // end: copyForChildTraceMessage
        }
    }

    public override fun mergeForChild(
        overwritingElement: CoroutineContext.Element
    ): CoroutineContext {
        debug {
            (overwritingElement as? TraceContextElement)?.let {
                val msg =
                    "${this::class.java.simpleName}@$currentId#mergeForChild(@${it.currentId}): " +
                        "current name=\"$name\", overwritingElement name=\"${it.name}\". " +
                        UNEXPECTED_TRACE_DATA_ERROR_MESSAGE
                Trace.instant(Trace.TRACE_TAG_APP, msg)
                Log.e(TAG, msg)
            }
            return@debug mergeForChildTraceMessage
        }
        try {
            Trace.traceBegin(Trace.TRACE_TAG_APP, mergeForChildTraceMessage)
            val nameForChild = (overwritingElement as CoroutineTraceName).name
            return createChildContext(nameForChild)
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_APP) // end: mergeForChildTraceMessage
        }
    }

    internal fun createChildContext(childName: String?): TraceContextElement {
        return TraceContextElement(
            name =
                childName
                    ?: if (walkStackForDefaultNames) {
                        walkStackForClassName(shouldIgnoreClassName)
                    } else {
                        ""
                    },
            isRoot = false,
            countContinuations = countContinuations,
            walkStackForDefaultNames = walkStackForDefaultNames,
            shouldIgnoreClassName = shouldIgnoreClassName,
            parentId = currentId,
            inheritedTracePrefix =
                if (childCoroutineCount != null) {
                    val childCount = childCoroutineCount.incrementAndGet()
                    "$coroutineTraceName:$childCount^"
                } else null,
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

@OptIn(ExperimentalContracts::class)
private inline fun debug(message: () -> String) {
    contract { callsInPlace(message, InvocationKind.AT_MOST_ONCE) }
    if (DEBUG) {
        val msg = message()
        Trace.instant(Trace.TRACE_TAG_APP, msg)
        Log.d(TAG, msg)
    }
}
