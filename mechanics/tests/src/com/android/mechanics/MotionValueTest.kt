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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.mechanics

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.mechanics.spec.BreakpointKey
import com.android.mechanics.spec.DirectionalMotionSpec
import com.android.mechanics.spec.Guarantee
import com.android.mechanics.spec.InputDirection
import com.android.mechanics.spec.Mapping
import com.android.mechanics.spec.MotionSpec
import com.android.mechanics.spec.builder
import com.android.mechanics.spec.reverseBuilder
import com.android.mechanics.testing.DefaultSprings.matStandardDefault
import com.android.mechanics.testing.DefaultSprings.matStandardFast
import com.android.mechanics.testing.MotionValueToolkit
import com.android.mechanics.testing.MotionValueToolkit.Companion.input
import com.android.mechanics.testing.MotionValueToolkit.Companion.isStable
import com.android.mechanics.testing.MotionValueToolkit.Companion.output
import com.android.mechanics.testing.goldenTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.motion.MotionTestRule
import platform.test.motion.testing.createGoldenPathManager

@RunWith(AndroidJUnit4::class)
class MotionValueTest {
    private val goldenPathManager =
        createGoldenPathManager("frameworks/libs/systemui/mechanics/tests/goldens")

    @get:Rule(order = 0) val rule = createComposeRule()
    @get:Rule(order = 1) val motion = MotionTestRule(MotionValueToolkit(rule), goldenPathManager)

    @Test
    fun emptySpec_outputMatchesInput_withoutAnimation() =
        motion.goldenTest(
            spec = MotionSpec.Empty,
            verifyTimeSeries = {
                // Output always matches the input
                assertThat(output).containsExactlyElementsIn(input).inOrder()
                // There must never be an ongoing animation.
                assertThat(isStable).doesNotContain(false)
            },
        ) {
            animateValueTo(100f)
        }

    // TODO the tests should describe the expected values not only in terms of goldens, but
    // also explicitly in verifyTimeSeries

    @Test
    fun changingInput_addsAnimationToMapping_becomesStable() =
        motion.goldenTest(
            spec =
                specBuilder(Mapping.Zero)
                    .toBreakpoint(1f)
                    .completeWith(Mapping.Linear(factor = 0.5f))
        ) {
            animateValueTo(1.1f, changePerFrame = 0.5f)
            while (underTest.isStable) {
                updateValue(input + 0.5f)
                awaitFrames()
            }
        }

    @Test
    fun segmentChange_inMaxDirection_animatedWhenReachingBreakpoint() =
        motion.goldenTest(
            spec = specBuilder(Mapping.Zero).toBreakpoint(1f).completeWith(Mapping.One)
        ) {
            animateValueTo(1f, changePerFrame = 0.5f)
            awaitStable()
        }

    @Test
    fun segmentChange_inMinDirection_animatedWhenReachingBreakpoint() =
        motion.goldenTest(
            initialValue = 2f,
            initialDirection = InputDirection.Min,
            spec = specBuilder(Mapping.Zero).toBreakpoint(1f).completeWith(Mapping.One),
        ) {
            animateValueTo(1f, changePerFrame = 0.5f)
            awaitStable()
        }

    @Test
    fun segmentChange_inMaxDirection_springAnimationStartedRetroactively() =
        motion.goldenTest(
            spec = specBuilder(Mapping.Zero).toBreakpoint(.75f).completeWith(Mapping.One)
        ) {
            animateValueTo(1f, changePerFrame = 0.5f)
            awaitStable()
        }

    @Test
    fun segmentChange_inMinDirection_springAnimationStartedRetroactively() =
        motion.goldenTest(
            initialValue = 2f,
            initialDirection = InputDirection.Min,
            spec = specBuilder(Mapping.Zero).toBreakpoint(1.25f).completeWith(Mapping.One),
        ) {
            animateValueTo(1f, changePerFrame = 0.5f)
            awaitStable()
        }

    @Test
    fun segmentChange_guaranteeNone_springAnimatesIndependentOfInput() =
        motion.goldenTest(
            spec =
                specBuilder(Mapping.Zero)
                    .toBreakpoint(1f)
                    .completeWith(Mapping.One, guarantee = Guarantee.None)
        ) {
            animateValueTo(5f, changePerFrame = 0.5f)
            awaitStable()
        }

    @Test
    fun segmentChange_guaranteeInputDelta_springCompletesWithinDistance() =
        motion.goldenTest(
            spec =
                specBuilder(Mapping.Zero)
                    .toBreakpoint(1f)
                    .completeWith(Mapping.One, guarantee = Guarantee.InputDelta(3f))
        ) {
            animateValueTo(4f, changePerFrame = 0.5f)
        }

    @Test
    fun segmentChange_guaranteeGestureDistance_springCompletesWithinDistance() =
        motion.goldenTest(
            spec =
                specBuilder(Mapping.Zero)
                    .toBreakpoint(1f)
                    .completeWith(Mapping.One, guarantee = Guarantee.GestureDistance(3f))
        ) {
            animateValueTo(1f, changePerFrame = 0.5f)
            while (!underTest.isStable) {
                gestureContext.distance += 0.5f
                awaitFrames()
            }
        }

    @Test
    fun specChange_shiftSegmentBackwards_doesNotAnimateWithinSegment_animatesSegmentChange() {
        fun generateSpec(offset: Float) =
            specBuilder(Mapping.Zero)
                .toBreakpoint(offset, B1)
                .jumpTo(1f)
                .continueWithTargetValue(2f)
                .toBreakpoint(offset + 1f, B2)
                .completeWith(Mapping.Zero)

        motion.goldenTest(spec = generateSpec(0f), initialValue = .5f) {
            var offset = 0f
            repeat(4) {
                offset -= .2f
                underTest.spec = generateSpec(offset)
                awaitFrames()
            }
            awaitStable()
        }
    }

    @Test
    fun specChange_shiftSegmentForward_doesNotAnimateWithinSegment_animatesSegmentChange() {
        fun generateSpec(offset: Float) =
            specBuilder(Mapping.Zero)
                .toBreakpoint(offset, B1)
                .jumpTo(1f)
                .continueWithTargetValue(2f)
                .toBreakpoint(offset + 1f, B2)
                .completeWith(Mapping.Zero)

        motion.goldenTest(spec = generateSpec(0f), initialValue = .5f) {
            var offset = 0f
            repeat(4) {
                offset += .2f
                underTest.spec = generateSpec(offset)
                awaitFrames()
            }
            awaitStable()
        }
    }

    @Test
    fun directionChange_maxToMin_changesSegmentWithDirectionChange() =
        motion.goldenTest(
            spec = specBuilder(Mapping.Zero).toBreakpoint(1f).completeWith(Mapping.One),
            initialValue = 2f,
            initialDirection = InputDirection.Max,
            directionChangeSlop = 3f,
        ) {
            animateValueTo(-2f, changePerFrame = 0.5f)
            awaitStable()
        }

    @Test
    fun directionChange_minToMax_changesSegmentWithDirectionChange() =
        motion.goldenTest(
            spec = specBuilder(Mapping.Zero).toBreakpoint(1f).completeWith(Mapping.One),
            initialValue = 0f,
            initialDirection = InputDirection.Min,
            directionChangeSlop = 3f,
        ) {
            animateValueTo(4f, changePerFrame = 0.5f)
            awaitStable()
        }

    @Test
    fun directionChange_maxToMin_appliesGuarantee_afterDirectionChange() =
        motion.goldenTest(
            spec =
                specBuilder(Mapping.Zero)
                    .toBreakpoint(1f)
                    .completeWith(Mapping.One, guarantee = Guarantee.InputDelta(1f)),
            initialValue = 2f,
            initialDirection = InputDirection.Max,
            directionChangeSlop = 3f,
        ) {
            animateValueTo(-2f, changePerFrame = 0.5f)
            awaitStable()
        }

    @Test
    fun traverseSegments_maxDirection_noGuarantee_addsDiscontinuityToOngoingAnimation() =
        motion.goldenTest(
            spec =
                specBuilder(Mapping.Zero)
                    .toBreakpoint(1f)
                    .continueWith(Mapping.One)
                    .toBreakpoint(2f)
                    .completeWith(Mapping.Two)
        ) {
            animateValueTo(3f, changePerFrame = 0.2f)
            awaitStable()
        }

    @Test
    fun traverseSegmentsInOneFrame_noGuarantee_combinesDiscontinuity() =
        motion.goldenTest(
            spec =
                specBuilder(Mapping.Zero)
                    .toBreakpoint(1f)
                    .continueWith(Mapping.One)
                    .toBreakpoint(2f)
                    .completeWith(Mapping.Two)
        ) {
            updateValue(2.5f)
            awaitStable()
        }

    @Test
    fun traverseSegmentsInOneFrame_withGuarantee_appliesGuarantees() =
        motion.goldenTest(
            spec =
                specBuilder(Mapping.Zero)
                    .toBreakpoint(1f)
                    .jumpBy(5f, guarantee = Guarantee.InputDelta(.9f))
                    .continueWithConstantValue()
                    .toBreakpoint(2f)
                    .jumpBy(1f, guarantee = Guarantee.InputDelta(.9f))
                    .continueWithConstantValue()
                    .complete()
        ) {
            updateValue(2.1f)
            awaitStable()
        }

    @Test
    fun traverseSegmentsInOneFrame_withDirectionChange_appliesGuarantees() =
        motion.goldenTest(
            spec =
                specBuilder(Mapping.Zero)
                    .toBreakpoint(1f)
                    .continueWith(Mapping.One, guarantee = Guarantee.InputDelta(1f))
                    .toBreakpoint(2f)
                    .completeWith(Mapping.Two),
            initialValue = 2.5f,
            initialDirection = InputDirection.Max,
            directionChangeSlop = 1f,
        ) {
            updateValue(.5f)
            animateValueTo(0f)
            awaitStable()
        }

    @Test
    fun changeDirection_flipsBetweenDirectionalSegments() {
        val spec =
            MotionSpec(
                maxDirection = forwardSpecBuilder(Mapping.Zero).complete(),
                minDirection = reverseSpecBuilder(Mapping.One).complete(),
            )

        motion.goldenTest(
            spec = spec,
            initialValue = 2f,
            initialDirection = InputDirection.Max,
            directionChangeSlop = 1f,
        ) {
            animateValueTo(0f)
            awaitStable()
        }
    }

    @Test
    fun keepRunning_concurrentInvocationThrows() = runTest {
        val underTest = MotionValue({ 1f }, FakeGestureContext)

        rule.setContent {
            LaunchedEffect(underTest) {
                val firstJob = launch { underTest.keepRunning() }

                val result = kotlin.runCatching { underTest.keepRunning() }

                assertThat(result.isFailure).isTrue()
                assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)

                assertThat(firstJob.isActive).isTrue()
                firstJob.cancel()
            }
        }
    }

    @Test
    fun debugInspector_sameInstance_whileInUse() {
        val underTest = MotionValue({ 1f }, FakeGestureContext)

        val originalInspector = underTest.debugInspector()
        assertThat(underTest.debugInspector()).isSameInstanceAs(originalInspector)
    }

    @Test
    fun debugInspector_newInstance_afterUnused() {
        val underTest = MotionValue({ 1f }, FakeGestureContext)

        val originalInspector = underTest.debugInspector()
        originalInspector.dispose()
        assertThat(underTest.debugInspector()).isNotSameInstanceAs(originalInspector)
    }

    companion object {
        val B1 = BreakpointKey("breakpoint1")
        val B2 = BreakpointKey("breakpoint2")
        val FakeGestureContext =
            object : GestureContext {
                override val direction: InputDirection
                    get() = InputDirection.Max

                override val distance: Float
                    get() = 0f
            }

        fun specBuilder(firstSegment: Mapping = Mapping.Identity) =
            MotionSpec.builder(
                defaultSpring = matStandardDefault,
                resetSpring = matStandardFast,
                initialMapping = firstSegment,
            )

        fun forwardSpecBuilder(firstSegment: Mapping = Mapping.Identity) =
            DirectionalMotionSpec.builder(
                defaultSpring = matStandardDefault,
                initialMapping = firstSegment,
            )

        fun reverseSpecBuilder(firstSegment: Mapping = Mapping.Identity) =
            DirectionalMotionSpec.reverseBuilder(
                defaultSpring = matStandardDefault,
                initialMapping = firstSegment,
            )
    }
}
