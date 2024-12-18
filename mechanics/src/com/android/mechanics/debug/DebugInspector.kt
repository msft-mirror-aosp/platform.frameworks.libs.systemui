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

package com.android.mechanics.debug

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.mechanics.DiscontinuityAnimation
import com.android.mechanics.MotionValue
import com.android.mechanics.spec.InputDirection
import com.android.mechanics.spec.SegmentData
import com.android.mechanics.spec.SegmentKey
import com.android.mechanics.spring.SpringParameters
import com.android.mechanics.spring.SpringState
import kotlinx.coroutines.DisposableHandle

/** Utility to gain inspection access to internal [MotionValue] state. */
class DebugInspector
internal constructor(
    initialFrameData: FrameData,
    initialIsActive: Boolean,
    initialIsAnimating: Boolean,
    disposableHandle: DisposableHandle,
) : DisposableHandle by disposableHandle {

    /** The last completed frame's data. */
    var frame: FrameData by mutableStateOf(initialFrameData)
        internal set

    /** Whether a [MotionValue.keepRunning] coroutine is active currently. */
    var isActive: Boolean by mutableStateOf(initialIsActive)
        internal set

    /**
     * `false` whenever the [MotionValue.keepRunning] coroutine internally is suspended while no
     * animation is running and the input is not changing.
     */
    var isAnimating: Boolean by mutableStateOf(initialIsAnimating)
        internal set
}

/** The input, output and internal state of a [MotionValue] for the frame. */
data class FrameData
internal constructor(
    val input: Float,
    val gestureDirection: InputDirection,
    val gestureDistance: Float,
    val frameTimeNanos: Long,
    val springState: SpringState,
    private val segment: SegmentData,
    private val animation: DiscontinuityAnimation,
) {
    val isStable: Boolean
        get() = springState == SpringState.AtRest

    val springParameters: SpringParameters
        get() = animation.springParameters

    val segmentKey: SegmentKey
        get() = segment.key

    val output: Float
        get() = currentDirectMapped + (animation.targetValue + springState.displacement)

    val outputTarget: Float
        get() = currentDirectMapped + animation.targetValue

    private val currentDirectMapped: Float
        get() = segment.mapping.map(input) - animation.targetValue
}
