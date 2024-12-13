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

package com.android.mechanics

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.mechanics.spec.InputDirection
import kotlin.math.max
import kotlin.math.min

/**
 * Gesture-specific context to augment [MotionValue.currentInput].
 *
 * This context helps to capture the user's intent, and should be provided to [MotionValue]s that
 * respond to a user gesture.
 */
@Stable
interface GestureContext {

    /**
     * The intrinsic direction of the [MotionValue.currentInput].
     *
     * This property determines which of the [DirectionalMotionSpec] from the [MotionSpec] is used,
     * and also prevents flip-flopping of the output value on tiny input-changes around a
     * breakpoint.
     *
     * If the [MotionValue.currentInput] is driven - directly or indirectly - by a user gesture,
     * this property should only change direction after the gesture travelled a significant distance
     * in the opposite direction.
     *
     * @see DistanceGestureContext for a default implementation.
     */
    val direction: InputDirection

    /**
     * The gesture distance of the current gesture, in pixels.
     *
     * Used solely for the [GestureDistance] [Guarantee]. Can be hard-coded to a static value if
     * this type of [Guarantee] is not used.
     */
    val distance: Float
}

/** [GestureContext] implementation for manually set values. */
class ProvidedGestureContext(direction: InputDirection, distance: Float) : GestureContext {
    override var direction by mutableStateOf(direction)
    override var distance by mutableFloatStateOf(distance)
}

/**
 * [GestureContext] driven by a gesture distance.
 *
 * The direction is determined from the gesture input, where going further than
 * [directionChangeSlop] in the opposite direction toggles the direction.
 *
 * @param initialDistance The initial [distance] of the [GestureContext]
 * @param initialDirection The initial [direction] of the [GestureContext]
 * @param directionChangeSlop the amount [distance] must be moved in the opposite direction for the
 *   [direction] to flip.
 */
class DistanceGestureContext(
    initialDistance: Float,
    initialDirection: InputDirection,
    directionChangeSlop: Float,
) : GestureContext {
    init {
        require(directionChangeSlop > 0) {
            "directionChangeSlop must be greater than 0, was $directionChangeSlop"
        }
    }

    override var direction by mutableStateOf(initialDirection)
        private set

    private var furthestDistance by mutableFloatStateOf(initialDistance)
    private var _distance by mutableFloatStateOf(initialDistance)

    override var distance: Float
        get() = _distance
        /**
         * Updates the [distance].
         *
         * This flips the [direction], if the [value] is further than [directionChangeSlop] away
         * from the furthest recorded value regarding to the current [direction].
         */
        set(value) {
            _distance = value
            this.direction =
                when (direction) {
                    InputDirection.Max -> {
                        if (furthestDistance - value > directionChangeSlop) {
                            furthestDistance = value
                            InputDirection.Min
                        } else {
                            furthestDistance = max(value, furthestDistance)
                            InputDirection.Max
                        }
                    }

                    InputDirection.Min -> {
                        if (value - furthestDistance > directionChangeSlop) {
                            furthestDistance = value
                            InputDirection.Max
                        } else {
                            furthestDistance = min(value, furthestDistance)
                            InputDirection.Min
                        }
                    }
                }
        }

    private var _directionChangeSlop by mutableFloatStateOf(directionChangeSlop)

    var directionChangeSlop: Float
        get() = _directionChangeSlop

        /**
         * This flips the [direction], if the current [direction] is further than the new
         * directionChangeSlop [value] away from the furthest recorded value regarding to the
         * current [direction].
         */
        set(value) {
            require(value > 0) { "directionChangeSlop must be greater than 0, was $value" }

            _directionChangeSlop = value

            when (direction) {
                InputDirection.Max -> {
                    if (furthestDistance - distance > directionChangeSlop) {
                        furthestDistance = distance
                        direction = InputDirection.Min
                    }
                }
                InputDirection.Min -> {
                    if (distance - furthestDistance > directionChangeSlop) {
                        furthestDistance = value
                        direction = InputDirection.Max
                    }
                }
            }
        }

    /**
     * Sets [distance] and [direction] to the specified values.
     *
     * This also resets memoized [furthestDistance], which is used to determine the direction
     * change.
     */
    fun reset(distance: Float, direction: InputDirection) {
        this.distance = distance
        this.direction = direction
        this.furthestDistance = distance
    }
}
