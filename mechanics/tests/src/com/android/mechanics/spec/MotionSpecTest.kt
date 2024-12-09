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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.mechanics.spring.SpringParameters
import com.android.mechanics.testing.BreakpointSubject.Companion.assertThat
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MotionSpecTest {

    @Test
    fun containsSegment_unknownSegment_returnsFalse() {
        val underTest = MotionSpec.builder(Spring).complete()
        assertThat(underTest.containsSegment(SegmentKey(B1, B2, InputDirection.Max))).isFalse()
    }

    @Test
    fun containsSegment_symmetricSpec_knownSegment_returnsTrue() {
        val underTest =
            MotionSpec.builder(Spring)
                .toBreakpoint(10f, key = B1)
                .continueWith(Mapping.One)
                .toBreakpoint(20f, key = B2)
                .completeWith(Mapping.Identity)

        assertThat(underTest.containsSegment(SegmentKey(B1, B2, InputDirection.Max))).isTrue()
        assertThat(underTest.containsSegment(SegmentKey(B1, B2, InputDirection.Min))).isTrue()
    }

    @Test
    fun containsSegment_asymmetricSpec_knownMaxDirectionSegment_trueOnlyInMaxDirection() {
        val forward =
            DirectionalMotionSpec.builder(Spring)
                .toBreakpoint(10f, key = B1)
                .continueWith(Mapping.One)
                .toBreakpoint(20f, key = B2)
                .completeWith(Mapping.Identity)
        val reverse = DirectionalMotionSpec.builder(Spring).complete()

        val underTest = MotionSpec(forward, reverse)

        assertThat(underTest.containsSegment(SegmentKey(B1, B2, InputDirection.Max))).isTrue()
        assertThat(underTest.containsSegment(SegmentKey(B1, B2, InputDirection.Min))).isFalse()
    }

    @Test
    fun containsSegment_asymmetricSpec_knownMinDirectionSegment_trueOnlyInMinDirection() {
        val forward = DirectionalMotionSpec.builder(Spring).complete()
        val reverse =
            DirectionalMotionSpec.builder(Spring)
                .toBreakpoint(10f, key = B1)
                .continueWith(Mapping.One)
                .toBreakpoint(20f, key = B2)
                .completeWith(Mapping.Identity)

        val underTest = MotionSpec(forward, reverse)

        assertThat(underTest.containsSegment(SegmentKey(B1, B2, InputDirection.Max))).isFalse()
        assertThat(underTest.containsSegment(SegmentKey(B1, B2, InputDirection.Min))).isTrue()
    }

    @Test
    fun segmentAtInput_emptySpec_maxDirection_segmentDataIsCorrect() {
        val underTest = MotionSpec.builder(Spring).complete()

        val segmentAtInput = underTest.segmentAtInput(0f, InputDirection.Max)

        assertThat(segmentAtInput.spec).isSameInstanceAs(underTest)
        assertThat(segmentAtInput.minBreakpoint).isSameInstanceAs(Breakpoint.minLimit)
        assertThat(segmentAtInput.maxBreakpoint).isSameInstanceAs(Breakpoint.maxLimit)
        assertThat(segmentAtInput.direction).isEqualTo(InputDirection.Max)
        assertThat(segmentAtInput.mapping).isEqualTo(Mapping.Identity)
    }

    @Test
    fun segmentAtInput_emptySpec_minDirection_segmentDataIsCorrect() {
        val underTest = MotionSpec.builder(Spring).complete()

        val segmentAtInput = underTest.segmentAtInput(0f, InputDirection.Min)

        assertThat(segmentAtInput.spec).isSameInstanceAs(underTest)
        assertThat(segmentAtInput.minBreakpoint).isSameInstanceAs(Breakpoint.minLimit)
        assertThat(segmentAtInput.maxBreakpoint).isSameInstanceAs(Breakpoint.maxLimit)
        assertThat(segmentAtInput.direction).isEqualTo(InputDirection.Min)
        assertThat(segmentAtInput.mapping).isEqualTo(Mapping.Identity)
    }

    @Test
    fun segmentAtInput_atBreakpointPosition() {
        val underTest =
            MotionSpec.builder(Spring)
                .toBreakpoint(10f, key = B1)
                .continueWith(Mapping.One)
                .toBreakpoint(20f, key = B2)
                .completeWith(Mapping.Identity)

        val segmentAtInput = underTest.segmentAtInput(10f, InputDirection.Max)

        assertThat(segmentAtInput.key).isEqualTo(SegmentKey(B1, B2, InputDirection.Max))
        assertThat(segmentAtInput.minBreakpoint).isAt(10f)
        assertThat(segmentAtInput.maxBreakpoint).isAt(20f)
        assertThat(segmentAtInput.mapping).isEqualTo(Mapping.One)
    }

    @Test
    fun segmentAtInput_reverse_atBreakpointPosition() {
        val underTest =
            MotionSpec.builder(Spring)
                .toBreakpoint(10f, key = B1)
                .continueWith(Mapping.One)
                .toBreakpoint(20f, key = B2)
                .completeWith(Mapping.Identity)

        val segmentAtInput = underTest.segmentAtInput(20f, InputDirection.Min)

        assertThat(segmentAtInput.key).isEqualTo(SegmentKey(B1, B2, InputDirection.Min))
        assertThat(segmentAtInput.minBreakpoint).isAt(10f)
        assertThat(segmentAtInput.maxBreakpoint).isAt(20f)
        assertThat(segmentAtInput.mapping).isEqualTo(Mapping.One)
    }

    @Test
    fun containsSegment_asymmetricSpec_readsFromIndicatedDirection() {
        val forward =
            DirectionalMotionSpec.builder(Spring)
                .toBreakpoint(10f, key = B1)
                .continueWith(Mapping.One)
                .toBreakpoint(20f, key = B2)
                .completeWith(Mapping.Identity)
        val reverse =
            DirectionalMotionSpec.builder(Spring)
                .toBreakpoint(5f, key = B1)
                .continueWith(Mapping.Two)
                .toBreakpoint(25f, key = B2)
                .completeWith(Mapping.Identity)

        val underTest = MotionSpec(forward, reverse)

        val segmentAtInputMax = underTest.segmentAtInput(15f, InputDirection.Max)
        assertThat(segmentAtInputMax.key).isEqualTo(SegmentKey(B1, B2, InputDirection.Max))
        assertThat(segmentAtInputMax.minBreakpoint).isAt(10f)
        assertThat(segmentAtInputMax.maxBreakpoint).isAt(20f)
        assertThat(segmentAtInputMax.mapping).isEqualTo(Mapping.One)

        val segmentAtInputMin = underTest.segmentAtInput(15f, InputDirection.Min)
        assertThat(segmentAtInputMin.key).isEqualTo(SegmentKey(B1, B2, InputDirection.Min))
        assertThat(segmentAtInputMin.minBreakpoint).isAt(5f)
        assertThat(segmentAtInputMin.maxBreakpoint).isAt(25f)
        assertThat(segmentAtInputMin.mapping).isEqualTo(Mapping.Two)
    }

    @Test
    fun onSegmentChanged_noHandler_returnsEqualSegmentForSameInput() {
        val underTest =
            MotionSpec.builder(Spring)
                .toBreakpoint(10f, key = B1)
                .continueWith(Mapping.One)
                .toBreakpoint(20f, key = B2)
                .completeWith(Mapping.Identity)

        val segmentAtInput = underTest.segmentAtInput(15f, InputDirection.Max)
        val onChangedResult = underTest.onChangeSegment(segmentAtInput, 15f, InputDirection.Max)
        assertThat(segmentAtInput).isEqualTo(onChangedResult)
    }

    @Test
    fun onSegmentChanged_noHandler_returnsNewSegmentForNewInput() {
        val underTest =
            MotionSpec.builder(Spring)
                .toBreakpoint(10f, key = B1)
                .continueWith(Mapping.One)
                .toBreakpoint(20f, key = B2)
                .completeWith(Mapping.Identity)

        val segmentAtInput = underTest.segmentAtInput(15f, InputDirection.Max)
        val onChangedResult = underTest.onChangeSegment(segmentAtInput, 15f, InputDirection.Min)
        assertThat(segmentAtInput).isNotEqualTo(onChangedResult)

        assertThat(onChangedResult.key).isEqualTo(SegmentKey(B1, B2, InputDirection.Min))
    }

    @Test
    fun onSegmentChanged_withHandlerReturningNull_returnsSegmentAtInput() {
        val underTest =
            MotionSpec.builder(Spring)
                .toBreakpoint(10f, key = B1)
                .continueWith(Mapping.One)
                .toBreakpoint(20f, key = B2)
                .completeWith(Mapping.Identity)
                .copy(
                    segmentHandlers =
                        mapOf(SegmentKey(B1, B2, InputDirection.Max) to { _, _, _ -> null })
                )

        val segmentAtInput = underTest.segmentAtInput(15f, InputDirection.Max)
        val onChangedResult = underTest.onChangeSegment(segmentAtInput, 15f, InputDirection.Min)

        assertThat(segmentAtInput).isNotEqualTo(onChangedResult)
        assertThat(onChangedResult.key).isEqualTo(SegmentKey(B1, B2, InputDirection.Min))
    }

    @Test
    fun onSegmentChanged_withHandlerReturningSegment_returnsHandlerResult() {
        val underTest =
            MotionSpec.builder(Spring)
                .toBreakpoint(10f, key = B1)
                .continueWith(Mapping.One)
                .toBreakpoint(20f, key = B2)
                .completeWith(Mapping.Identity)
                .copy(
                    segmentHandlers =
                        mapOf(
                            SegmentKey(B1, B2, InputDirection.Max) to
                                { _, _, _ ->
                                    segmentAtInput(0f, InputDirection.Min)
                                }
                        )
                )

        val segmentAtInput = underTest.segmentAtInput(15f, InputDirection.Max)
        val onChangedResult = underTest.onChangeSegment(segmentAtInput, 15f, InputDirection.Min)

        assertThat(segmentAtInput).isNotEqualTo(onChangedResult)
        assertThat(onChangedResult.key)
            .isEqualTo(SegmentKey(Breakpoint.minLimit.key, B1, InputDirection.Min))
    }

    companion object {
        val B1 = BreakpointKey("one")
        val B2 = BreakpointKey("two")
        val Spring = SpringParameters(stiffness = 100f, dampingRatio = 1f)
    }
}
