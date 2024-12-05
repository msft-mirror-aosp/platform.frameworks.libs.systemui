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
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SegmentTest {

    @Test
    fun segmentData_isValidForInput_betweenBreakpointsSameDirection_isTrue() {
        val breakpoint1 = Breakpoint(b1, position = 10f, spring, Guarantee.None)
        val breakpoint2 = Breakpoint(b2, position = 20f, spring, Guarantee.None)
        val underTest = SegmentData(breakpoint1, breakpoint2, InputDirection.Max, Mapping.Identity)

        assertThat(underTest.isValidForInput(15f, InputDirection.Max)).isTrue()
    }

    @Test
    fun segmentData_isValidForInput_betweenBreakpointsOppositeDirection_isFalse() {
        val breakpoint1 = Breakpoint(b1, position = 10f, spring, Guarantee.None)
        val breakpoint2 = Breakpoint(b2, position = 20f, spring, Guarantee.None)
        val underTest = SegmentData(breakpoint1, breakpoint2, InputDirection.Max, Mapping.Identity)

        assertThat(underTest.isValidForInput(15f, InputDirection.Min)).isFalse()
    }

    @Test
    fun segmentData_isValidForInput_inMaxDirection_sampledAtVariousPositions_matchesExpectation() {
        val breakpoint1 = Breakpoint(b1, position = 10f, spring, Guarantee.None)
        val breakpoint2 = Breakpoint(b2, position = 20f, spring, Guarantee.None)
        val underTest = SegmentData(breakpoint1, breakpoint2, InputDirection.Max, Mapping.Identity)

        for ((samplePosition, expectedResult) in
            listOf(5f to true, 10f to true, 15f to true, 20f to false, 25f to false)) {
            assertWithMessage("at $samplePosition")
                .that(underTest.isValidForInput(samplePosition, InputDirection.Max))
                .isEqualTo(expectedResult)
        }
    }

    @Test
    fun segmentData_isValidForInput_inMinDirection_sampledAtVariousPositions_matchesExpectation() {
        val breakpoint1 = Breakpoint(b1, position = 10f, spring, Guarantee.None)
        val breakpoint2 = Breakpoint(b2, position = 20f, spring, Guarantee.None)
        val underTest = SegmentData(breakpoint1, breakpoint2, InputDirection.Min, Mapping.Identity)

        for ((samplePosition, expectedResult) in
            listOf(5f to false, 10f to false, 15f to true, 20f to true, 25f to true)) {
            assertWithMessage("at $samplePosition")
                .that(underTest.isValidForInput(samplePosition, InputDirection.Min))
                .isEqualTo(expectedResult)
        }
    }

    companion object {
        val b1 = BreakpointKey("one")
        val b2 = BreakpointKey("two")
        val spring = SpringParameters(stiffness = 100f, dampingRatio = 1f)
    }
}
