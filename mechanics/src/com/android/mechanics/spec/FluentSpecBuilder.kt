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

package com.android.mechanics.spec

import com.android.mechanics.spring.SpringParameters

/**
 * Fluent builder for [DirectionalMotionSpec].
 *
 * This builder ensures correctness at compile-time, and simplifies the expression of the
 * input-to-output mapping.
 *
 * The [MotionSpec] is defined by specify interleaved [Mapping]s and [Breakpoint]s. [Breakpoint]s
 * must be specified in ascending order.
 *
 * NOTE: The returned fluent interfaces must only be used for chaining calls to build exactly one
 * [DirectionalMotionSpec], otherwise resulting behavior is undefined, since the builder is
 * internally mutated.
 *
 * @param defaultSpring spring to use for all breakpoints by default.
 * @param initialMapping the [Mapping] from [Breakpoint.minLimit] to the next [Breakpoint].
 * @see reverseBuilder to specify [Breakpoint]s in descending order.
 */
fun DirectionalMotionSpec.Companion.builder(
    defaultSpring: SpringParameters,
    initialMapping: Mapping = Mapping.Identity,
): FluentSpecEndSegmentWithNextBreakpoint<DirectionalMotionSpec> {
    return FluentSpecBuilder(defaultSpring, InputDirection.Max) { it }
        .apply { mappings.add(initialMapping) }
}

/**
 * Fluent builder for [DirectionalMotionSpec], specifying breakpoints and mappings in reverse order.
 *
 * Variant of [DirectionalMotionSpec.Companion.builder], where [Breakpoint]s must be specified in
 * *descending* order. The resulting [DirectionalMotionSpec] will contain the breakpoints in
 * ascending order.
 *
 * @param defaultSpring spring to use for all breakpoints by default.
 * @param initialMapping the [Mapping] from [Breakpoint.maxLimit] to the next [Breakpoint].
 * @see DirectionalMotionSpec.Companion.builder for more documentation.
 */
fun DirectionalMotionSpec.Companion.reverseBuilder(
    defaultSpring: SpringParameters,
    initialMapping: Mapping = Mapping.Identity,
): FluentSpecEndSegmentWithNextBreakpoint<DirectionalMotionSpec> {
    return FluentSpecBuilder(defaultSpring, InputDirection.Min) { it }
        .apply { mappings.add(initialMapping) }
}

/**
 * Fluent builder for a [MotionSpec], which uses the same spec in both directions.
 *
 * @param defaultSpring spring to use for all breakpoints by default.
 * @param initialMapping [Mapping] for the first segment
 * @param resetSpring the [MotionSpec.resetSpring].
 */
fun MotionSpec.Companion.builder(
    defaultSpring: SpringParameters,
    initialMapping: Mapping = Mapping.Identity,
    resetSpring: SpringParameters = defaultSpring,
): FluentSpecEndSegmentWithNextBreakpoint<MotionSpec> {
    return FluentSpecBuilder(defaultSpring, InputDirection.Max) {
            MotionSpec(it, resetSpring = resetSpring)
        }
        .apply { mappings.add(initialMapping) }
}

/** Fluent-interface to end the current segment, by placing the next [Breakpoint]. */
interface FluentSpecEndSegmentWithNextBreakpoint<R> {
    /**
     * Adds a new [Breakpoint] at the specified position.
     *
     * @param atPosition The position of the breakpoint, in the input domain of the [MotionValue].
     * @param key identifies the breakpoint in the [DirectionalMotionSpec]. Must be specified to
     *   reference the breakpoint or segment.
     */
    fun toBreakpoint(
        atPosition: Float,
        key: BreakpointKey = BreakpointKey(),
    ): FluentSpecDefineBreakpointAndStartNextSegment<R>

    /** Completes the spec by placing the last, implicit [Breakpoint]. */
    fun complete(): R
}

/** Fluent-interface to define the [Breakpoint]'s properties and start to start the next segment. */
interface FluentSpecDefineBreakpointAndStartNextSegment<R> {
    /**
     * Default spring parameters for breakpoint, as specified at creation time of the builder.
     *
     * Used as the default `spring` parameters.
     */
    val defaultSpring: SpringParameters

    /**
     * Starts the next segment, using the specified mapping.
     *
     * @param mapping the mapping to use for the next segment.
     * @param spring the spring to animate this breakpoints discontinuity.
     * @param guarantee a guarantee by when the animation must be complete
     */
    fun continueWith(
        mapping: Mapping,
        spring: SpringParameters = defaultSpring,
        guarantee: Guarantee = Guarantee.None,
    ): FluentSpecEndSegmentWithNextBreakpoint<R>

    /**
     * Starts the next linear-mapped segment, by specifying the output [value] this breakpoint.
     *
     * @param value the output value the new mapping will produce at this breakpoints position.
     * @param spring the spring to animate this breakpoints discontinuity.
     * @param guarantee a guarantee by when the animation must be complete
     */
    fun jumpTo(
        value: Float,
        spring: SpringParameters = defaultSpring,
        guarantee: Guarantee = Guarantee.None,
    ): FluentSpecDefineLinearSegmentMapping<R>

    /**
     * Starts the next linear-mapped segment, by offsetting the output by [delta] from the incoming
     * mapping.
     *
     * @param delta the delta in output from the previous mapping's output.
     * @param spring the spring to animate this breakpoints discontinuity.
     * @param guarantee a guarantee by when the animation must be complete
     */
    fun jumpBy(
        delta: Float,
        spring: SpringParameters = defaultSpring,
        guarantee: Guarantee = Guarantee.None,
    ): FluentSpecDefineLinearSegmentMapping<R>

    /**
     * Completes the spec by using [mapping] between the this and the implicit sentinel breakpoint
     * at infinity.
     *
     * @param mapping the mapping to use for the final segment.
     * @param spring the spring to animate this breakpoints discontinuity.
     * @param guarantee a guarantee by when the animation must be complete
     */
    fun completeWith(
        mapping: Mapping,
        spring: SpringParameters = defaultSpring,
        guarantee: Guarantee = Guarantee.None,
    ): R
}

/** Fluent-interface to define a linear mapping between two breakpoints. */
interface FluentSpecDefineLinearSegmentMapping<R> {
    /**
     * The linear-mapping will produce the specified [target] output at the next breakpoint
     * position.
     *
     * @param target the output value the new mapping will produce at the next breakpoint position.
     */
    fun continueWithTargetValue(target: Float): FluentSpecEndSegmentWithNextBreakpoint<R>

    /**
     * Defines the slope for the linear mapping, as a fraction of the input value.
     *
     * @param fraction the multiplier applied to the input value..
     */
    fun continueWithFractionalInput(fraction: Float): FluentSpecEndSegmentWithNextBreakpoint<R>

    /**
     * The linear-mapping will produce a constant value, as defined at the source breakpoint of this
     * segment.
     */
    fun continueWithConstantValue(): FluentSpecEndSegmentWithNextBreakpoint<R>
}

/** Implements the fluent spec builder logic. */
private class FluentSpecBuilder<R>(
    override val defaultSpring: SpringParameters,
    buildDirection: InputDirection = InputDirection.Max,
    private val toResult: (DirectionalMotionSpec) -> R,
) :
    FluentSpecDefineLinearSegmentMapping<R>,
    FluentSpecDefineBreakpointAndStartNextSegment<R>,
    FluentSpecEndSegmentWithNextBreakpoint<R> {
    private val buildForward = buildDirection == InputDirection.Max

    val breakpoints = mutableListOf<Breakpoint>()
    val mappings = mutableListOf<Mapping>()

    var sourceValue: Float = Float.NaN
    var targetValue: Float = Float.NaN
    var fractionalMapping: Float = Float.NaN
    var breakpointPosition: Float = Float.NaN
    var breakpointKey: BreakpointKey? = null

    init {
        val initialBreakpoint = if (buildForward) Breakpoint.minLimit else Breakpoint.maxLimit
        breakpoints.add(initialBreakpoint)
    }

    //  FluentSpecDefineLinearSegmentMapping

    override fun continueWithTargetValue(target: Float): FluentSpecEndSegmentWithNextBreakpoint<R> {
        check(sourceValue.isFinite())

        // memoize for FluentSpecEndSegmentWithNextBreakpoint
        targetValue = target

        return this
    }

    override fun continueWithFractionalInput(
        fraction: Float
    ): FluentSpecEndSegmentWithNextBreakpoint<R> {
        check(sourceValue.isFinite())

        // memoize for FluentSpecEndSegmentWithNextBreakpoint
        fractionalMapping = fraction

        return this
    }

    override fun continueWithConstantValue(): FluentSpecEndSegmentWithNextBreakpoint<R> {
        check(sourceValue.isFinite())

        mappings.add(Mapping.Fixed(sourceValue))

        sourceValue = Float.NaN
        return this
    }

    // FluentSpecDefineBreakpointAndStartNextSegment implementation

    override fun jumpTo(
        value: Float,
        spring: SpringParameters,
        guarantee: Guarantee,
    ): FluentSpecDefineLinearSegmentMapping<R> {
        check(sourceValue.isNaN())

        doAddBreakpoint(spring, guarantee)
        sourceValue = value

        return this
    }

    override fun jumpBy(
        delta: Float,
        spring: SpringParameters,
        guarantee: Guarantee,
    ): FluentSpecDefineLinearSegmentMapping<R> {
        check(sourceValue.isNaN())

        val breakpoint = doAddBreakpoint(spring, guarantee)
        sourceValue = mappings.last().map(breakpoint.position) + delta

        return this
    }

    override fun continueWith(
        mapping: Mapping,
        spring: SpringParameters,
        guarantee: Guarantee,
    ): FluentSpecEndSegmentWithNextBreakpoint<R> {
        check(sourceValue.isNaN())

        doAddBreakpoint(spring, guarantee)
        mappings.add(mapping)

        return this
    }

    override fun completeWith(mapping: Mapping, spring: SpringParameters, guarantee: Guarantee): R {
        check(sourceValue.isNaN())

        doAddBreakpoint(spring, guarantee)
        mappings.add(mapping)

        return complete()
    }

    // FluentSpecEndSegmentWithNextBreakpoint implementation

    override fun toBreakpoint(
        atPosition: Float,
        key: BreakpointKey,
    ): FluentSpecDefineBreakpointAndStartNextSegment<R> {
        check(breakpointPosition.isNaN())
        check(breakpointKey == null)

        if (!targetValue.isNaN() || !fractionalMapping.isNaN()) {
            check(!sourceValue.isNaN())

            val sourcePosition = breakpoints.last().position

            if (fractionalMapping.isNaN()) {
                val delta = targetValue - sourceValue
                fractionalMapping = delta / (atPosition - sourcePosition)
            } else {
                val delta = (atPosition - sourcePosition) * fractionalMapping
                targetValue = sourceValue + delta
            }

            val offset =
                if (buildForward) sourceValue - (sourcePosition * fractionalMapping)
                else targetValue - (atPosition * fractionalMapping)

            mappings.add(Mapping.Linear(fractionalMapping, offset))
            targetValue = Float.NaN
            sourceValue = Float.NaN
            fractionalMapping = Float.NaN
        }

        breakpointPosition = atPosition
        breakpointKey = key

        return this
    }

    override fun complete(): R {
        check(targetValue.isNaN()) { "cant specify target value for last segment" }

        if (!fractionalMapping.isNaN()) {
            check(!sourceValue.isNaN())

            val sourcePosition = breakpoints.last().position

            mappings.add(
                Mapping.Linear(
                    fractionalMapping,
                    sourceValue - (sourcePosition * fractionalMapping),
                )
            )
        }

        if (buildForward) {
            breakpoints.add(Breakpoint.maxLimit)
        } else {
            breakpoints.add(Breakpoint.minLimit)
            breakpoints.reverse()
            mappings.reverse()
        }

        return toResult(DirectionalMotionSpec(breakpoints.toList(), mappings.toList()))
    }

    private fun doAddBreakpoint(springSpec: SpringParameters, guarantee: Guarantee): Breakpoint {
        check(breakpointPosition.isFinite())
        return Breakpoint(checkNotNull(breakpointKey), breakpointPosition, springSpec, guarantee)
            .also {
                breakpoints.add(it)
                breakpointPosition = Float.NaN
                breakpointKey = null
            }
    }
}
