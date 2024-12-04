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
 * Handler to allow for custom segment-change logic.
 *
 * This handler is called whenever the new input (position or direction) does not match
 * [currentSegment] anymore (see [SegmentData.isValidForInput]).
 *
 * This is intended to implement custom effects on direction-change.
 *
 * Implementations can return:
 * 1. [currentSegment] to delay/suppress segment change.
 * 2. `null` to use the default segment lookup based on [newPosition] and [newDirection]
 * 3. manually looking up segments on this [MotionSpec]
 * 4. create a [SegmentData] that is not in the spec.
 */
typealias OnChangeSegmentHandler =
    MotionSpec.(
        currentSegment: SegmentData, newPosition: Float, newDirection: InputDirection,
    ) -> SegmentData?

/**
 * Specification for the mapping of input values to output values.
 *
 * The spec consists of two independent directional spec's, while only one the one matching
 * `MotionInput`'s `direction` is used at any given time.
 *
 * @param maxDirection spec used when the MotionInput's direction is [InputDirection.Max]
 * @param minDirection spec used when the MotionInput's direction is [InputDirection.Min]
 * @param resetSpring spring parameters to animate a difference in output, if the difference is
 *   caused by setting this new spec.
 * @param segmentHandlers allow for custom segment-change logic, when the `MotionValue` runtime
 *   would leave the [SegmentKey].
 */
data class MotionSpec(
    val maxDirection: DirectionalMotionSpec,
    val minDirection: DirectionalMotionSpec = maxDirection,
    val resetSpring: SpringParameters = DefaultResetSpring,
    val segmentHandlers: Map<SegmentKey, OnChangeSegmentHandler> = emptyMap(),
) {
    companion object {
        /**
         * Default spring parameters for the reset spring. Matches the Fast Spatial spring of the
         * standard motion scheme.
         */
        private val DefaultResetSpring = SpringParameters(stiffness = 1400f, dampingRatio = 1f)

        /* Empty motion spec, the output is the same as the input. */
        val Empty = MotionSpec(DirectionalMotionSpec.Empty)
    }
}

/**
 * Defines the [breakpoints], as well as the [mappings] in-between adjacent [Breakpoint] pairs.
 *
 * This [DirectionalMotionSpec] is applied in the direction defined by the containing [MotionSpec]:
 * especially the direction in which the `breakpoint` [Guarantee] are applied depend on how this is
 * used; this type does not have an inherit direction.
 *
 * All [breakpoints] are sorted in ascending order by their `position`, with the first and last
 * breakpoints are guaranteed to be sentinel values for negative and positive infinity respectively.
 *
 * @param breakpoints All breakpoints in the spec, must contain [Breakpoint.minLimit] as the first
 *   element, and [Breakpoint.maxLimit] as the last element.
 * @param mappings All mappings in between the breakpoints, thus must always contain
 *   `breakpoints.size - 1` elements.
 */
data class DirectionalMotionSpec(val breakpoints: List<Breakpoint>, val mappings: List<Mapping>) {
    init {
        require(breakpoints.size >= 2)
        require(breakpoints.first() == Breakpoint.minLimit)
        require(breakpoints.last() == Breakpoint.maxLimit)
        require(breakpoints.zipWithNext { a, b -> a <= b }.all { it }) {
            "Breakpoints are not sorted ascending ${breakpoints.map { "${it.key}@${it.position}" }}"
        }
        require(mappings.size == breakpoints.size - 1)
    }

    /**
     * Returns the index of the closest breakpoint where `Breakpoint.position <= position`.
     *
     * Guaranteed to be a valid index into [breakpoints], and guaranteed not to be the last element.
     *
     * @param position the position in the input domain.
     * @return Index into [breakpoints], guaranteed to be in range `0..breakpoints.size - 2`
     */
    fun findBreakpointIndex(position: Float): Int {
        require(position.isFinite())
        val breakpointPosition = breakpoints.binarySearchBy(position) { it.position }

        val result =
            when {
                // position is between two anchors, return the min one.
                breakpointPosition < 0 -> -breakpointPosition - 2
                else -> breakpointPosition
            }

        check(result >= 0)
        check(result < breakpoints.size - 1)

        return result
    }

    companion object {
        /* Empty spec, the full input domain is mapped to output using [Mapping.identity]. */
        val Empty =
            DirectionalMotionSpec(
                listOf(Breakpoint.minLimit, Breakpoint.maxLimit),
                listOf(Mapping.Identity),
            )
    }
}
