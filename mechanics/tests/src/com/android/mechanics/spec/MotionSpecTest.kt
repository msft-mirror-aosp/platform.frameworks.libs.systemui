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
import kotlin.math.nextDown
import kotlin.math.nextUp
import kotlin.test.assertFailsWith
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MotionSpecTest {

    @Test
    fun directionalMotionSpec_noBreakpoints_throws() {
        assertFailsWith<IllegalArgumentException> {
            DirectionalMotionSpec(emptyList(), emptyList())
        }
    }

    @Test
    fun directionalMotionSpec_wrongSentinelBreakpoints_throws() {
        val breakpoint1 = Breakpoint(b1, position = 10f, spring, Guarantee.None)
        val breakpoint2 = Breakpoint(b2, position = 20f, spring, Guarantee.None)

        assertFailsWith<IllegalArgumentException> {
            DirectionalMotionSpec(listOf(breakpoint1, breakpoint2), listOf(Mapping.Identity))
        }
    }

    @Test
    fun directionalMotionSpec_tooFewMappings_throws() {
        assertFailsWith<IllegalArgumentException> {
            DirectionalMotionSpec(listOf(Breakpoint.minLimit, Breakpoint.maxLimit), emptyList())
        }
    }

    @Test
    fun directionalMotionSpec_tooManyMappings_throws() {
        assertFailsWith<IllegalArgumentException> {
            DirectionalMotionSpec(
                listOf(Breakpoint.minLimit, Breakpoint.maxLimit),
                listOf(Mapping.One, Mapping.Two),
            )
        }
    }

    @Test
    fun directionalMotionSpec_breakpointsOutOfOrder_throws() {
        val breakpoint1 = Breakpoint(b1, position = 10f, spring, Guarantee.None)
        val breakpoint2 = Breakpoint(b2, position = 20f, spring, Guarantee.None)
        assertFailsWith<IllegalArgumentException> {
            DirectionalMotionSpec(
                listOf(Breakpoint.minLimit, breakpoint2, breakpoint1, Breakpoint.maxLimit),
                listOf(Mapping.Zero, Mapping.One, Mapping.Two),
            )
        }
    }

    @Test
    fun directionalMotionSpec_findBreakpointIndex_returnsMinForEmptySpec() {
        val underTest = DirectionalMotionSpec.builder(spring).complete()

        assertThat(underTest.findBreakpointIndex(0f)).isEqualTo(0)
        assertThat(underTest.findBreakpointIndex(Float.MAX_VALUE)).isEqualTo(0)
        assertThat(underTest.findBreakpointIndex(-Float.MAX_VALUE)).isEqualTo(0)
    }

    @Test
    fun directionalMotionSpec_findBreakpointIndex_throwsForNonFiniteInput() {
        val underTest = DirectionalMotionSpec.builder(spring).complete()

        assertFailsWith<IllegalArgumentException> { underTest.findBreakpointIndex(Float.NaN) }
        assertFailsWith<IllegalArgumentException> {
            underTest.findBreakpointIndex(Float.NEGATIVE_INFINITY)
        }
        assertFailsWith<IllegalArgumentException> {
            underTest.findBreakpointIndex(Float.POSITIVE_INFINITY)
        }
    }

    @Test
    fun directionalMotionSpec_findBreakpointIndex_atBreakpoint_returnsIndex() {
        val underTest =
            DirectionalMotionSpec.builder(spring).toBreakpoint(10f).completeWith(Mapping.Identity)

        assertThat(underTest.findBreakpointIndex(10f)).isEqualTo(1)
    }

    @Test
    fun directionalMotionSpec_findBreakpointIndex_afterBreakpoint_returnsPreviousIndex() {
        val underTest =
            DirectionalMotionSpec.builder(spring).toBreakpoint(10f).completeWith(Mapping.Identity)

        assertThat(underTest.findBreakpointIndex(10f.nextUp())).isEqualTo(1)
    }

    @Test
    fun directionalMotionSpec_findBreakpointIndex_beforeBreakpoint_returnsIndex() {
        val underTest =
            DirectionalMotionSpec.builder(spring).toBreakpoint(10f).completeWith(Mapping.Identity)

        assertThat(underTest.findBreakpointIndex(10f.nextDown())).isEqualTo(0)
    }

    companion object {
        val b1 = BreakpointKey("one")
        val b2 = BreakpointKey("two")
        val spring = SpringParameters(stiffness = 100f, dampingRatio = 1f)
    }
}
