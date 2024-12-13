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

import androidx.compose.runtime.FloatState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.util.lerp
import androidx.compose.ui.util.packFloats
import androidx.compose.ui.util.unpackFloat1
import androidx.compose.ui.util.unpackFloat2
import com.android.mechanics.debug.DebugInspector
import com.android.mechanics.debug.FrameData
import com.android.mechanics.spec.Breakpoint
import com.android.mechanics.spec.Guarantee
import com.android.mechanics.spec.InputDirection
import com.android.mechanics.spec.Mapping
import com.android.mechanics.spec.MotionSpec
import com.android.mechanics.spec.SegmentData
import com.android.mechanics.spring.SpringParameters
import com.android.mechanics.spring.SpringState
import com.android.mechanics.spring.calculateUpdatedState
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Computes an animated [output] value, by mapping the [currentInput] according to the [spec].
 *
 * A [MotionValue] represents a single animated value within a larger animation. It takes a
 * numerical [currentInput] value, typically a spatial value like width, height, or gesture length,
 * and transforms it into an [output] value using a [MotionSpec].
 *
 * ## Mapping Input to Output
 *
 * The [MotionSpec] defines the relationship between the input and output values. It does this by
 * specifying a series of [Mapping] functions and [Breakpoint]s. Breakpoints divide the input domain
 * into segments. Each segment has an associated [Mapping] function, which determines how input
 * values within that segment are transformed into output values.
 *
 * These [Mapping] functions can be arbitrary, as long as they are
 * 1. deterministic: When invoked repeatedly for the same input, they must produce the same output.
 * 2. continuous: meaning infinitesimally small changes in input result in infinitesimally small
 *    changes in output
 *
 * A valid [Mapping] function is one whose graph could be drawn without lifting your pen from the
 * paper, meaning there are no abrupt jumps or breaks.
 *
 * ## Animating Discontinuities
 *
 * When the input value crosses a breakpoint, there might be a discontinuity in the output value due
 * to the switch between mapping functions. `MotionValue` automatically animates these
 * discontinuities using a spring animation. The spring parameters are defined for each
 * [Breakpoint].
 *
 * ## Guarantees for Choreography
 *
 * Breakpoints can also define [Guarantee]s. These guarantees can make the spring animation finish
 * faster, in response to quick input value changes. Thus, [Guarantee]s allows to maintain a
 * predictable choreography, even as the input is unpredictably changed by a user's gesture.
 *
 * ## Updating the MotionSpec
 *
 * The [spec] property can be changed at any time. If the new spec produces a different output for
 * the current input, the difference will be animated using the spring parameters defined in
 * [MotionSpec.resetSpring].
 *
 * ## Gesture Context
 *
 * The [GestureContext] augments the [currentInput] value with the user's intent. The
 * [GestureContext] is created wherever gesture input is handled. If the motion value is not driven
 * by a gesture, it is OK for the [GestureContext] to return static values.
 *
 * ## Usage
 *
 * The [MotionValue] does animate the [output] implicitly, whenever a change in [currentInput],
 * [spec], or [gestureContext] requires it. The animated value is computed whenever the [output]
 * property is read, or the latest once the animation frame is complete.
 * 1. Create an instance, providing the input value, gesture context, and an initial spec.
 * 2. Call [keepRunning] in a coroutine scope, and keep the coroutine running while the
 *    `MotionValue` is in use.
 * 3. Access the animated output value through the [output] property.
 *
 * Internally, the [keepRunning] coroutine is automatically suspended if there is nothing to
 * animate.
 *
 * @param currentInput Provides the current input value.
 * @param gestureContext The [GestureContext] augmenting the [currentInput].
 * @param label An optional label to aid in debugging.
 * @param stableThreshold A threshold value (in output units) that determines when the
 *   [MotionValue]'s internal spring animation is considered stable.
 */
class MotionValue(
    private val currentInput: () -> Float,
    private val gestureContext: GestureContext,
    initialSpec: MotionSpec = MotionSpec.Empty,
    val label: String? = null,
    private val stableThreshold: Float = 0.01f,
) : FloatState {

    /** The [MotionSpec] describing the mapping of this [MotionValue]'s input to the output. */
    var spec by mutableStateOf(initialSpec)

    /** Animated [output] value. */
    val output: Float
        get() = currentDirectMapped + currentAnimatedDelta

    /**
     * [output] value, but without animations.
     *
     * This value always reports the target value, even before a animation is finished.
     *
     * While [isStable], [outputTarget] and [output] are the same value.
     */
    val outputTarget: Float
        get() = currentDirectMapped + currentAnimation.targetValue

    /** The [output] exposed as [FloatState]. */
    override val floatValue: Float
        get() = output

    /** Whether an animation is currently running. */
    val isStable: Boolean
        get() = currentSpringState == SpringState.AtRest

    /**
     * Keeps the [MotionValue]'s animated output running.
     *
     * Clients must call [keepRunning], and keep the coroutine running while the [MotionValue] is in
     * use. When disposing this [MotionValue], cancel the coroutine.
     *
     * Internally, this method does suspend, unless there are animations ongoing.
     */
    suspend fun keepRunning(): Nothing = coroutineScope {
        check(!isActive) { "keepRunning() invoked while already running" }
        isActive = true
        try {
            // The purpose of this implementation is to run an animation frame (via withFrameNanos)
            // whenever the input changes, or the spring is still settling, but otherwise just
            // suspend.

            // Used to suspend when no animations are running, and to wait for a wakeup signal.
            val wakeupChannel = Channel<Unit>(capacity = Channel.CONFLATED)

            // `true` while the spring is settling.
            var runAnimationFrames = !isStable
            launch {
                // TODO(b/383979536) use a SnapshotStateObserver instead
                snapshotFlow {
                        // observe all input values
                        var result = spec.hashCode()
                        result = result * 31 + currentInput().hashCode()
                        result = result * 31 + currentDirection.hashCode()
                        result = result * 31 + currentGestureDistance.hashCode()

                        // Track whether the spring needs animation frames to finish
                        // In fact, whether the spring is settling is the only relevant bit to
                        // export from here. For everything else, just cause the flow to emit a
                        // different value (hence the hashing)
                        (result shl 1) + if (isStable) 0 else 1
                    }
                    .collect { hashedState ->
                        // while the 'runAnimationFrames' bit was set on the result
                        runAnimationFrames = (hashedState and 1) != 0
                        // nudge the animation runner in case its sleeping.
                        wakeupChannel.send(Unit)
                    }
            }

            while (true) {
                if (!runAnimationFrames) {
                    // While the spring does not need animation frames (its stable), wait until
                    // woken up - this can be for a single frame after an input change.
                    debugIsAnimating = false
                    wakeupChannel.receive()
                }

                debugIsAnimating = true
                withFrameNanos { frameTimeNanos -> currentAnimationTimeNanos = frameTimeNanos }

                // At this point, the complete frame is done (including layout, drawing and
                // everything else). What follows next is similar what one would do in a
                // `SideEffect`, were this composable code:
                // If during the last frame, a new animation was started, or a new segment entered,
                // this state is copied over. If nothing changed, the computed `current*` state will
                // be the same, it won't have a side effect.

                // Capturing the state here is required since crossing a breakpoint is an event -
                // the code has to record that this happened.

                // Important - capture all values first, and only afterwards update the state.
                // Interleaving read and update might trigger immediate re-computations.
                val newSegment = currentSegment
                val newGuaranteeState = currentGuaranteeState
                val newAnimation = currentAnimation
                val newSpringState = currentSpringState

                // Capture the last frames input.
                lastFrameTimeNanos = currentAnimationTimeNanos
                lastInput = currentInput()
                lastGestureDistance = currentGestureDistance
                // Not capturing currentDirection and spec explicitly, they are included in
                // lastSegment

                // Update the state to the computed `current*` values
                lastSegment = newSegment
                lastGuaranteeState = newGuaranteeState
                lastAnimation = newAnimation
                lastSpringState = newSpringState
                debugInspector?.run {
                    frame =
                        FrameData(
                            lastInput,
                            currentDirection,
                            lastGestureDistance,
                            lastFrameTimeNanos,
                            lastSpringState,
                            lastSegment,
                            lastAnimation,
                        )
                }
            }

            // Keep the compiler happy - the while (true) {} above will not complete, yet the
            // compiler wants a return value.
            @Suppress("UNREACHABLE_CODE") awaitCancellation()
        } finally {
            isActive = false
        }
    }

    companion object {
        /** Creates a [MotionValue] whose [currentInput] is the animated [output] of [source]. */
        fun createDerived(
            source: MotionValue,
            initialSpec: MotionSpec = MotionSpec.Empty,
            label: String? = null,
            stableThreshold: Float = 0.01f,
        ): MotionValue {
            return MotionValue(
                currentInput = source::output,
                gestureContext = source.gestureContext,
                initialSpec = initialSpec,
                label = label,
                stableThreshold = stableThreshold,
            )
        }

        internal const val TAG = "MotionValue"
    }

    // ---- Last frame's state ---------------------------------------------------------------------

    // The following state values prefixed with `last*` contain the state of the last completed
    // frame. These values are updated in [keepRunning], once [withFrameNanos] returns.
    // The `last*` state is what the `current*` computations further down are based on.

    /**
     * The segment in use, defined by the min/max [Breakpoint]s and the [Mapping] in between. This
     * implicitly also captures the [InputDirection] and [MotionSpec].
     */
    private var lastSegment: SegmentData by
        mutableStateOf(
            spec.segmentAtInput(currentInput(), currentDirection),
            referentialEqualityPolicy(),
        )

    /**
     * State of the [Guarantee]. Its interpretation is defined by the [lastSegment]'s
     * [SegmentData.entryBreakpoint]'s [Breakpoint.guarantee]. If that breakpoint has no guarantee,
     * this value will be [GuaranteeState.Inactive].
     *
     * This is the maximal guarantee value seen so far, as well as the guarantee's start value, and
     * is used to compute the spring-tightening fraction.
     */
    private inline var lastGuaranteeState: GuaranteeState
        get() = GuaranteeState(_lastGuaranteeStatePacked)
        set(value) {
            _lastGuaranteeStatePacked = value.packedValue
        }

    /** Backing field for [lastGuaranteeState], to avoid auto-boxing. */
    private var _lastGuaranteeStatePacked: Long by
        mutableLongStateOf(GuaranteeState.Inactive.packedValue)

    /**
     * The state of an ongoing animation of a discontinuity.
     *
     * The spring animation is described by the [DiscontinuityAnimation.springStartState], which
     * tracks the oscillation of the spring until the displacement is guaranteed not to exceed
     * [stableThreshold] anymore. The spring animation started at
     * [DiscontinuityAnimation.springStartTimeNanos], and uses the
     * [DiscontinuityAnimation.springParameters]. The displacement's origin is at
     * [DiscontinuityAnimation.targetValue].
     *
     * This state does not have to be updated every frame, even as an animation is ongoing: the
     * spring animation can be computed with the same start parameters, and as time progresses, the
     * [SpringState.calculateUpdatedState] is passed an ever larger `elapsedNanos` on each frame.
     *
     * The [DiscontinuityAnimation.targetValue] is a delta to the direct mapped output value from
     * the [SegmentData.mapping]. It might accumulate the target value - it is not required to reset
     * when the animation ends.
     */
    private var lastAnimation: DiscontinuityAnimation by
        mutableStateOf(DiscontinuityAnimation.None, referentialEqualityPolicy())

    // ---- Last frame's input and output ----------------------------------------------------------

    // The state below captures relevant input values (including frame time) and the computed spring
    // state, thus are updated on every frame. To avoid excessive invalidations, these must only be
    // read from [currentAnimation] and [currentGuaranteeState], when starting a new animation.

    /**
     * Last frame's spring state, based on initial origin values in [lastAnimation], carried-forward
     * to [lastFrameTimeNanos].
     */
    private inline var lastSpringState: SpringState
        get() = SpringState(_lastSpringStatePacked)
        set(value) {
            _lastSpringStatePacked = value.packedValue
        }

    /** Backing field for [lastSpringState], to avoid auto-boxing. */
    private var _lastSpringStatePacked: Long by
        mutableLongStateOf(lastAnimation.springStartState.packedValue)

    /** The time of the last frame, in nanoseconds. */
    private var lastFrameTimeNanos by mutableLongStateOf(-1L)

    /** The [currentInput] of the last frame */
    private var lastInput by mutableFloatStateOf(currentInput())

    /** The [currentGestureDistance] input of the last frame. */
    private var lastGestureDistance by mutableFloatStateOf(currentGestureDistance)

    // ---- Declarative Update ---------------------------------------------------------------------

    // All the below contains the magic to compute the updated [MotionValue] state.
    // The code is strictly ordered by dependencies - code is only ever allowed to access a value
    // that is placed above in this file, to avoid cyclic dependencies.

    /**
     * The current frame's animation time, updated by [keepRunning] while an animation is running or
     * the input changed.
     */
    private var currentAnimationTimeNanos by mutableLongStateOf(-1L)

    /** [gestureContext]'s [GestureContext.direction], exists solely for consistent naming. */
    private inline val currentDirection: InputDirection
        get() = gestureContext.direction

    /** [gestureContext]'s [GestureContext.distance], exists solely for consistent naming. */
    private inline val currentGestureDistance: Float
        get() = gestureContext.distance

    /**
     * The current segment, which defines the [Mapping] function used to transform the input to the
     * output.
     *
     * While both [spec] and [currentDirection] remain the same, and [currentInput] is within the
     * segment (see [SegmentData.isValidForInput]), this is [lastSegment].
     *
     * Otherwise, [MotionSpec.onChangeSegment] is queried for an up-dated segment.
     */
    private val currentSegment: SegmentData by derivedStateOf {
        val lastSegment = lastSegment
        val input = currentInput()
        val direction = currentDirection

        val specChanged = lastSegment.spec != spec
        if (specChanged || !lastSegment.isValidForInput(input, direction)) {
            spec.onChangeSegment(lastSegment, input, direction)
        } else {
            lastSegment
        }
    }

    /**
     * Describes how the [currentSegment] is different from last frame's [lastSegment].
     *
     * This affects how the discontinuities are animated and [Guarantee]s applied.
     */
    private enum class SegmentChangeType {
        /**
         * The segment has the same key, this is considered equivalent.
         *
         * Only the [GuaranteeState] needs to be kept updated.
         */
        Same,

        /**
         * The segment's direction changed, however the min / max breakpoints remain the same: This
         * is a direction change within a segment.
         *
         * The delta between the mapping must be animated with the reset spring, and there is no
         * guarantee associated with the change.
         */
        SameOppositeDirection,

        /**
         * The segment and its direction change. This is a direction change that happened over a
         * segment boundary.
         *
         * The direction change might have happened outside the [lastSegment] already, since a
         * segment can't be exited at the entry side.
         */
        Direction,

        /**
         * The segment changed, due to the [currentInput] advancing in the [currentDirection],
         * crossing one or more breakpoints.
         *
         * The guarantees of all crossed breakpoints have to be applied. The [GuaranteeState] must
         * be reset, and a new [DiscontinuityAnimation] is started.
         */
        Traverse,

        /**
         * The spec was changed and added or removed the previous and/or current segment.
         *
         * The [MotionValue] does not have a semantic understanding of this change, hence the
         * difference output produced by the previous and current mapping are animated with the
         * [MotionSpec.resetSpring]
         */
        Spec,
    }

    /** Computes the [SegmentChangeType] between [lastSegment] and [currentSegment]. */
    private val segmentChangeType: SegmentChangeType
        get() {
            val currentSegment = currentSegment
            val lastSegment = lastSegment

            if (currentSegment.key == lastSegment.key) {
                return SegmentChangeType.Same
            }

            if (
                currentSegment.key.minBreakpoint == lastSegment.key.minBreakpoint &&
                    currentSegment.key.maxBreakpoint == lastSegment.key.maxBreakpoint
            ) {
                return SegmentChangeType.SameOppositeDirection
            }

            val currentSpec = currentSegment.spec
            val lastSpec = lastSegment.spec
            if (currentSpec !== lastSpec) {
                // Determine/guess whether the segment change was due to the changed spec, or
                // whether lastSpec would return the same segment key for the update input.
                val lastSpecSegmentForSameInput =
                    lastSpec.segmentAtInput(currentInput(), gestureContext.direction).key
                if (currentSegment.key != lastSpecSegmentForSameInput) {
                    // Note: this might not be correct if the new [MotionSpec.segmentHandlers] were
                    // involved.
                    return SegmentChangeType.Spec
                }
            }

            return if (currentSegment.direction == lastSegment.direction) {
                SegmentChangeType.Traverse
            } else {
                SegmentChangeType.Direction
            }
        }

    /**
     * Computes the fraction of [position] between [lastInput] and [currentInput].
     *
     * Essentially, this determines fractionally when [position] was crossed, between the current
     * frame and the last frame.
     *
     * Since frames are updated periodically, not continuously, crossing a breakpoint happened
     * sometime between the last frame's start and this frame's start.
     *
     * This fraction is used to estimate the time when a breakpoint was crossed since last frame,
     * and simplifies the logic of crossing multiple breakpoints in one frame, as it offers the
     * springs and guarantees time to be updated correctly.
     *
     * Of course, this is a simplification that assumes the input velocity was uniform during the
     * last frame, but that is likely good enough.
     */
    private fun lastFrameFractionOfPosition(position: Float): Float {
        return ((position - lastInput) / (currentInput() - lastInput)).coerceIn(0f, 1f)
    }

    /**
     * The [GuaranteeState] for [currentSegment].
     *
     * Without a segment change, this carries forward [lastGuaranteeState], adjusted to the new
     * input if needed.
     *
     * If a segment change happened, this is a new [GuaranteeState] for the [currentSegment]. Any
     * remaining [lastGuaranteeState] will be consumed in [currentAnimation].
     */
    private val currentGuaranteeState: GuaranteeState by derivedStateOf {
        val currentSegment = currentSegment
        val entryBreakpoint = currentSegment.entryBreakpoint

        // First, determine the origin of the guarantee computations
        val guaranteeOriginState =
            when (segmentChangeType) {
                // Still in the segment, the origin is carried over from the last frame
                SegmentChangeType.Same -> lastGuaranteeState
                // The direction changed within the same segment, no guarantee to enforce.
                SegmentChangeType.SameOppositeDirection ->
                    return@derivedStateOf GuaranteeState.Inactive
                // The spec changes, there is no guarantee associated with the animation.
                SegmentChangeType.Spec -> return@derivedStateOf GuaranteeState.Inactive
                SegmentChangeType.Direction -> {
                    // Direction changed over a segment boundary. To make up for the
                    // directionChangeSlop, the guarantee starts at the current input.
                    GuaranteeState.withStartValue(
                        when (entryBreakpoint.guarantee) {
                            is Guarantee.InputDelta -> currentInput()
                            is Guarantee.GestureDistance -> gestureContext.distance
                            is Guarantee.None -> return@derivedStateOf GuaranteeState.Inactive
                        }
                    )
                }

                SegmentChangeType.Traverse -> {
                    // Traversed over a segment boundary, the guarantee going forward is determined
                    // by the [entryBreakpoint].
                    GuaranteeState.withStartValue(
                        when (entryBreakpoint.guarantee) {
                            is Guarantee.InputDelta -> entryBreakpoint.position
                            is Guarantee.GestureDistance -> {
                                // Guess the [GestureDistance] origin - since the gesture distance
                                // is sampled, interpolate it according to when the breakpoint was
                                // crossed in the last frame.
                                val fractionalBreakpointPos =
                                    lastFrameFractionOfPosition(entryBreakpoint.position)

                                lerp(
                                    lastGestureDistance,
                                    gestureContext.distance,
                                    fractionalBreakpointPos,
                                )
                            }

                            // No guarantee to enforce.
                            is Guarantee.None -> return@derivedStateOf GuaranteeState.Inactive
                        }
                    )
                }
            }

        // Finally, update the origin state with the current guarantee value.
        guaranteeOriginState.withCurrentValue(
            when (entryBreakpoint.guarantee) {
                is Guarantee.InputDelta -> currentInput()
                is Guarantee.GestureDistance -> gestureContext.distance
                is Guarantee.None -> return@derivedStateOf GuaranteeState.Inactive
            },
            currentSegment.direction,
        )
    }

    /**
     * The [DiscontinuityAnimation] in effect for the current frame.
     *
     * This describes the starting condition of the spring animation, and is only updated if the
     * spring animation must restarted: that is, if yet another discontinuity must be animated as a
     * result of a segment change, or if the [currentGuaranteeState] requires the spring to be
     * tightened.
     *
     * See [currentSpringState] for the continuously updated, animated spring values.
     */
    private val currentAnimation: DiscontinuityAnimation by derivedStateOf {
        val currentSegment = currentSegment
        val lastSegment = lastSegment
        val currentSpec = spec
        val currentInput = currentInput()
        val lastAnimation = lastAnimation

        when (segmentChangeType) {
            SegmentChangeType.Same -> {
                if (lastAnimation.isAtRest) {
                    // Nothing to update if no animation is ongoing
                    lastAnimation
                } else if (lastGuaranteeState == currentGuaranteeState) {
                    // Nothing to update if the spring must not be tightened.
                    lastAnimation
                } else {
                    // Compute the updated spring parameters
                    val tightenedSpringParameters =
                        currentGuaranteeState.updatedSpringParameters(
                            currentSegment.entryBreakpoint
                        )

                    lastAnimation.copy(
                        springStartState = lastSpringState,
                        springParameters = tightenedSpringParameters,
                        springStartTimeNanos = lastFrameTimeNanos,
                    )
                }
            }

            SegmentChangeType.SameOppositeDirection,
            SegmentChangeType.Direction,
            SegmentChangeType.Spec -> {
                // Determine the delta in the output, as produced by the old and new mapping.
                val delta =
                    currentSegment.mapping.map(currentInput) - lastSegment.mapping.map(currentInput)

                if (delta == 0f) {
                    // Nothing new to animate.
                    lastAnimation
                } else {
                    val springParameters =
                        if (segmentChangeType == SegmentChangeType.Direction) {
                            currentSegment.entryBreakpoint.spring
                        } else {
                            currentSpec.resetSpring
                        }

                    val newTarget = delta - lastSpringState.displacement
                    DiscontinuityAnimation(
                        newTarget,
                        SpringState(-newTarget, lastSpringState.velocity),
                        springParameters,
                        lastFrameTimeNanos,
                    )
                }
            }

            SegmentChangeType.Traverse -> {
                // Process all breakpoints traversed, in order.
                // This is involved due to the guarantees - they have to be applied, one after the
                // other, before crossing the next breakpoint.
                val currentDirection = currentSegment.direction

                with(currentSpec[currentDirection]) {
                    val targetIndex = findSegmentIndex(currentSegment.key)
                    val sourceIndex = findSegmentIndex(lastSegment.key)
                    check(targetIndex != sourceIndex)

                    val directionOffset = if (targetIndex > sourceIndex) 1 else -1

                    var lastBreakpoint = lastSegment.entryBreakpoint
                    var lastAnimationTime = lastFrameTimeNanos
                    var guaranteeState = lastGuaranteeState
                    var springState = lastSpringState
                    var springTarget = lastAnimation.targetValue
                    var springParameters = lastAnimation.springParameters

                    var segmentIndex = sourceIndex
                    while (segmentIndex != targetIndex) {
                        val nextBreakpoint =
                            breakpoints[segmentIndex + directionOffset.coerceAtLeast(0)]

                        val nextBreakpointFrameFraction =
                            lastFrameFractionOfPosition(nextBreakpoint.position)

                        val nextBreakpointCrossTime =
                            lerp(
                                lastFrameTimeNanos,
                                currentAnimationTimeNanos,
                                nextBreakpointFrameFraction,
                            )
                        if (
                            guaranteeState != GuaranteeState.Inactive &&
                                springState != SpringState.AtRest
                        ) {
                            val guaranteeValueAtNextBreakpoint =
                                when (lastBreakpoint.guarantee) {
                                    is Guarantee.InputDelta -> nextBreakpoint.position
                                    is Guarantee.GestureDistance ->
                                        lerp(
                                            lastGestureDistance,
                                            gestureContext.distance,
                                            nextBreakpointFrameFraction,
                                        )

                                    is Guarantee.None ->
                                        error(
                                            "guaranteeState ($guaranteeState) is not Inactive, guarantee is missing"
                                        )
                                }

                            guaranteeState =
                                guaranteeState.withCurrentValue(
                                    guaranteeValueAtNextBreakpoint,
                                    currentDirection,
                                )

                            springParameters =
                                guaranteeState.updatedSpringParameters(lastBreakpoint)
                        }

                        springState =
                            springState.calculateUpdatedState(
                                nextBreakpointCrossTime - lastAnimationTime,
                                springParameters,
                            )
                        lastAnimationTime = nextBreakpointCrossTime

                        val beforeBreakpoint = mappings[segmentIndex].map(nextBreakpoint.position)
                        val afterBreakpoint =
                            mappings[segmentIndex + directionOffset].map(nextBreakpoint.position)

                        val delta = afterBreakpoint - beforeBreakpoint
                        springTarget += delta
                        springState = springState.addDisplacement(-delta)

                        segmentIndex += directionOffset
                        lastBreakpoint = nextBreakpoint
                        guaranteeState =
                            when (nextBreakpoint.guarantee) {
                                is Guarantee.InputDelta ->
                                    GuaranteeState.withStartValue(nextBreakpoint.position)

                                is Guarantee.GestureDistance ->
                                    GuaranteeState.withStartValue(
                                        lerp(
                                            lastGestureDistance,
                                            gestureContext.distance,
                                            nextBreakpointFrameFraction,
                                        )
                                    )

                                is Guarantee.None -> GuaranteeState.Inactive
                            }
                    }

                    val tightened =
                        currentGuaranteeState.updatedSpringParameters(
                            currentSegment.entryBreakpoint
                        )

                    DiscontinuityAnimation(springTarget, springState, tightened, lastAnimationTime)
                }
            }
        }
    }

    /**
     * Up to date and animated spring state, based on initial origin values in [currentAnimation],
     * carried-forward to [currentAnimationTimeNanos].
     *
     * Is guaranteed to be [SpringState.AtRest] if the spring is not animating (the oscillation is
     * less than [stableThreshold]).
     */
    private val currentSpringState: SpringState by derivedStateOf {
        with(currentAnimation) {
            if (isAtRest) return@derivedStateOf SpringState.AtRest

            val nanosSinceAnimationStart = currentAnimationTimeNanos - springStartTimeNanos
            val updatedSpringState =
                springStartState.calculateUpdatedState(nanosSinceAnimationStart, springParameters)

            if (updatedSpringState.isStable(springParameters, stableThreshold)) {
                SpringState.AtRest
            } else {
                updatedSpringState
            }
        }
    }

    private val currentDirectMapped: Float
        get() = currentSegment.mapping.map(currentInput()) - currentAnimation.targetValue

    private val currentAnimatedDelta: Float
        get() = currentAnimation.targetValue + currentSpringState.displacement

    // ---- Accessor to internals, for inspection and tests ----------------------------------------

    /** Whether a [keepRunning] coroutine is active currently. */
    private var isActive = false
        set(value) {
            field = value
            debugInspector?.isActive = value
        }

    /**
     * `false` whenever the [keepRunning] coroutine is suspended while no animation is running and
     * the input is not changing.
     */
    private var debugIsAnimating = false
        set(value) {
            field = value
            debugInspector?.isAnimating = value
        }

    private var debugInspector: DebugInspector? = null
    private var debugInspectorRefCount = AtomicInteger(0)

    private fun onDisposeDebugInspector() {
        if (debugInspectorRefCount.decrementAndGet() == 0) {
            debugInspector = null
        }
    }

    /**
     * Provides access to internal state for debug tooling and tests.
     *
     * The returned [DebugInspector] must be [DebugInspector.dispose]d when no longer needed.
     */
    fun debugInspector(): DebugInspector {
        if (debugInspectorRefCount.getAndIncrement() == 0) {
            debugInspector =
                DebugInspector(
                    FrameData(
                        lastInput,
                        lastSegment.direction,
                        lastGestureDistance,
                        lastFrameTimeNanos,
                        lastSpringState,
                        lastSegment,
                        lastAnimation,
                    ),
                    isActive,
                    debugIsAnimating,
                    ::onDisposeDebugInspector,
                )
        }

        return checkNotNull(debugInspector)
    }
}

/**
 * Captures the start-state of a spring-animation to smooth over a discontinuity.
 *
 * Discontinuities are caused by segment changes, where the new and old segment produce different
 * output values for the same input.
 */
internal data class DiscontinuityAnimation(
    val targetValue: Float,
    val springStartState: SpringState,
    val springParameters: SpringParameters,
    val springStartTimeNanos: Long,
) {
    val isAtRest: Boolean
        get() = springStartState == SpringState.AtRest

    companion object {
        val None =
            DiscontinuityAnimation(
                targetValue = 0f,
                springStartState = SpringState.AtRest,
                springParameters = SpringParameters.Snap,
                springStartTimeNanos = 0L,
            )
    }
}

/**
 * Captures the origin of a guarantee, and the maximal distance the input has been away from the
 * origin at most.
 */
@JvmInline
internal value class GuaranteeState(val packedValue: Long) {
    private val start: Float
        get() = unpackFloat1(packedValue)

    private val maxDelta: Float
        get() = unpackFloat2(packedValue)

    private val isInactive: Boolean
        get() = this == Inactive

    fun withCurrentValue(value: Float, direction: InputDirection): GuaranteeState {
        if (isInactive) return Inactive

        val delta = ((value - start) * direction.sign).coerceAtLeast(0f)
        return GuaranteeState(start, max(delta, maxDelta))
    }

    fun updatedSpringParameters(breakpoint: Breakpoint): SpringParameters {
        if (isInactive) return breakpoint.spring

        val denominator =
            when (val guarantee = breakpoint.guarantee) {
                is Guarantee.None -> return breakpoint.spring
                is Guarantee.InputDelta -> guarantee.delta
                is Guarantee.GestureDistance -> guarantee.distance
            }

        val springTighteningFraction = maxDelta / denominator
        return com.android.mechanics.spring.lerp(
            breakpoint.spring,
            SpringParameters.Snap,
            springTighteningFraction,
        )
    }

    companion object {
        val Inactive = GuaranteeState(packFloats(Float.NaN, Float.NaN))

        fun withStartValue(start: Float) = GuaranteeState(packFloats(start, 0f))
    }
}

internal fun GuaranteeState(start: Float, maxDelta: Float) =
    GuaranteeState(packFloats(start, maxDelta))
