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

/**
 * Identifies a segment in a [MotionSpec].
 *
 * A segment only exists between two adjacent [Breakpoint]s; it cannot span multiple breakpoints.
 * The [direction] indicates to the relevant [DirectionalMotionSpec] of the [MotionSpec].
 *
 * The position of the [minBreakpoint] must be less or equal to the position of the [maxBreakpoint].
 */
data class SegmentKey(
    val minBreakpoint: BreakpointKey,
    val maxBreakpoint: BreakpointKey,
    val direction: InputDirection,
)

/**
 * Captures denormalized segment data from a [MotionSpec].
 *
 * Instances are created by the [MotionSpec] and used by the [MotionValue] runtime to compute the
 * output value. By default, the [SegmentData] is cached while [isValidForInput] returns true.
 *
 * The [SegmentData] has an intrinsic direction, thus the segment has an entry and exit side, at the
 * respective breakpoint.
 */
data class SegmentData(
    val spec: MotionSpec,
    val minBreakpoint: Breakpoint,
    val maxBreakpoint: Breakpoint,
    val direction: InputDirection,
    val mapping: Mapping,
) {
    val key = SegmentKey(minBreakpoint.key, maxBreakpoint.key, direction)

    /**
     * Whether the given [inputPosition] and [inputDirection] should be handled by this segment.
     *
     * The input is considered invalid only if the direction changes or the input is *at or outside*
     * the segment on the exit-side. The input remains intentionally valid outside the segment on
     * the entry-side, to avoid flip-flopping.
     */
    fun isValidForInput(inputPosition: Float, inputDirection: InputDirection): Boolean {
        if (inputDirection != direction) return false

        return when (inputDirection) {
            InputDirection.Max -> inputPosition < maxBreakpoint.position
            InputDirection.Min -> inputPosition > minBreakpoint.position
        }
    }

    /**
     * The breakpoint at the side of the segment's start.
     *
     * The [entryBreakpoint]'s [Guarantee] is the relevant guarantee for this segment.
     */
    val entryBreakpoint: Breakpoint
        get() =
            when (direction) {
                InputDirection.Max -> minBreakpoint
                InputDirection.Min -> maxBreakpoint
            }
}

/**
 * Maps the `input` of a [MotionValue] to the desired output value.
 *
 * The mapping implementation can be arbitrary, but must not produce discontinuities.
 */
fun interface Mapping {
    /** Computes the [MotionValue]'s target output, given the input. */
    fun map(input: Float): Float

    /** `f(x) = x` */
    object Identity : Mapping {
        override fun map(input: Float): Float {
            return input
        }
    }

    /** `f(x) = value` */
    data class Fixed(val value: Float) : Mapping {
        override fun map(input: Float): Float {
            return value
        }
    }

    /** `f(x) = factor*x + offset` */
    data class Linear(val factor: Float, val offset: Float = 0f) : Mapping {
        override fun map(input: Float): Float {
            return input * factor + offset
        }
    }

    data class Tanh(val scaling: Float, val tilt: Float, val offset: Float = 0f) : Mapping {

        override fun map(input: Float): Float {
            return scaling * kotlin.math.tanh((input + offset) / (scaling * tilt))
        }
    }

    companion object {
        val Zero = Fixed(0f)
        val One = Fixed(1f)
        val Two = Fixed(2f)
    }
}
