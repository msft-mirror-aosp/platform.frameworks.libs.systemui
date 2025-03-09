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
import com.android.mechanics.testing.DirectionalMotionSpecSubject.Companion.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FluentSpecBuilderTest {

    @Test
    fun directionalSpec_buildEmptySpec() {
        val result = DirectionalMotionSpec.builder(Spring).complete()

        assertThat(result).breakpoints().isEmpty()
        assertThat(result).mappings().containsExactly(Mapping.Identity)
    }

    @Test
    fun directionalSpec_buildEmptySpec_inReverse() {
        val result = DirectionalMotionSpec.reverseBuilder(Spring).complete()

        assertThat(result).breakpoints().isEmpty()
        assertThat(result).mappings().containsExactly(Mapping.Identity)
    }

    @Test
    fun motionSpec_sameSpecInBothDirections() {
        val result =
            MotionSpec.builder(Spring, Mapping.Zero)
                .toBreakpoint(0f, B1)
                .continueWith(Mapping.One)
                .toBreakpoint(10f, B2)
                .completeWith(Mapping.Two)

        assertThat(result.maxDirection).isSameInstanceAs(result.minDirection)

        assertThat(result.minDirection).breakpoints().keys().containsExactly(B1, B2).inOrder()
        assertThat(result.minDirection)
            .mappings()
            .containsExactly(Mapping.Zero, Mapping.One, Mapping.Two)
            .inOrder()
    }

    @Test
    fun directionalSpec_addBreakpointsAndMappings() {
        val result =
            DirectionalMotionSpec.builder(Spring, Mapping.Zero)
                .toBreakpoint(0f, B1)
                .continueWith(Mapping.One)
                .toBreakpoint(10f, B2)
                .completeWith(Mapping.Two)

        assertThat(result).breakpoints().keys().containsExactly(B1, B2).inOrder()
        assertThat(result).breakpoints().withKey(B1).isAt(0f)
        assertThat(result).breakpoints().withKey(B2).isAt(10f)
        assertThat(result)
            .mappings()
            .containsExactly(Mapping.Zero, Mapping.One, Mapping.Two)
            .inOrder()
    }

    @Test
    fun directionalSpec_addBreakpointsAndMappings_inReverse() {
        val result =
            DirectionalMotionSpec.reverseBuilder(Spring, Mapping.Two)
                .toBreakpoint(10f, B2)
                .continueWith(Mapping.One)
                .toBreakpoint(0f, B1)
                .completeWith(Mapping.Zero)

        assertThat(result).breakpoints().keys().containsExactly(B1, B2).inOrder()
        assertThat(result).breakpoints().withKey(B1).isAt(0f)
        assertThat(result).breakpoints().withKey(B2).isAt(10f)
        assertThat(result)
            .mappings()
            .containsExactly(Mapping.Zero, Mapping.One, Mapping.Two)
            .inOrder()
    }

    @Test
    fun directionalSpec_mappingBuilder_setsDefaultSpring() {
        val result =
            DirectionalMotionSpec.builder(Spring)
                .toBreakpoint(10f)
                .jumpTo(20f)
                .continueWithConstantValue()
                .complete()

        assertThat(result).breakpoints().atPosition(10f).spring().isEqualTo(Spring)
    }

    @Test
    fun directionalSpec_mappingBuilder_canOverrideDefaultSpring() {
        val otherSpring = SpringParameters(stiffness = 10f, dampingRatio = 0.1f)
        val result =
            DirectionalMotionSpec.builder(Spring)
                .toBreakpoint(10f)
                .jumpTo(20f, spring = otherSpring)
                .continueWithConstantValue()
                .complete()

        assertThat(result).breakpoints().atPosition(10f).spring().isEqualTo(otherSpring)
    }

    @Test
    fun directionalSpec_mappingBuilder_defaultsToNoGuarantee() {
        val result =
            DirectionalMotionSpec.builder(Spring)
                .toBreakpoint(10f)
                .jumpTo(20f)
                .continueWithConstantValue()
                .complete()

        assertThat(result).breakpoints().atPosition(10f).guarantee().isEqualTo(Guarantee.None)
    }

    @Test
    fun directionalSpec_mappingBuilder_canSetGuarantee() {
        val guarantee = Guarantee.InputDelta(10f)
        val result =
            DirectionalMotionSpec.builder(Spring)
                .toBreakpoint(10f)
                .jumpTo(20f, guarantee = guarantee)
                .continueWithConstantValue()
                .complete()

        assertThat(result).breakpoints().atPosition(10f).guarantee().isEqualTo(guarantee)
    }

    @Test
    fun directionalSpec_mappingBuilder_jumpTo_setsAbsoluteValue() {
        val result =
            DirectionalMotionSpec.builder(Spring, Mapping.Fixed(99f))
                .toBreakpoint(10f)
                .jumpTo(20f)
                .continueWithConstantValue()
                .complete()

        assertThat(result).breakpoints().positions().containsExactly(10f)
        assertThat(result).mappings().atOrAfter(10f).isConstantValue(20f)
    }

    @Test
    fun directionalSpec_mappingBuilder_jumpBy_setsRelativeValue() {
        val result =
            DirectionalMotionSpec.builder(Spring, Mapping.Linear(factor = 0.5f))
                .toBreakpoint(10f)
                .jumpBy(30f)
                .continueWithConstantValue()
                .complete()

        assertThat(result).breakpoints().positions().containsExactly(10f)
        assertThat(result).mappings().atOrAfter(10f).isConstantValue(35f)
    }

    @Test
    fun directionalSpec_mappingBuilder_continueWithConstantValue_usesSourceValue() {
        val result =
            DirectionalMotionSpec.builder(Spring, Mapping.Linear(factor = 0.5f))
                .toBreakpoint(5f)
                .jumpBy(0f)
                .continueWithConstantValue()
                .complete()

        assertThat(result).mappings().atOrAfter(5f).isConstantValue(2.5f)
    }

    @Test
    fun directionalSpec_mappingBuilder_continueWithFractionalInput_matchesLinearMapping() {
        val result =
            DirectionalMotionSpec.builder(Spring)
                .toBreakpoint(5f)
                .jumpTo(1f)
                .continueWithFractionalInput(fraction = .1f)
                .complete()

        assertThat(result)
            .mappings()
            .atOrAfter(5f)
            .matchesLinearMapping(in1 = 5f, out1 = 1f, in2 = 15f, out2 = 2f)
    }

    @Test
    fun directionalSpec_mappingBuilder_reverse_continueWithFractionalInput_matchesLinearMapping() {
        val result =
            DirectionalMotionSpec.reverseBuilder(Spring)
                .toBreakpoint(15f)
                .jumpTo(2f)
                .continueWithFractionalInput(fraction = .1f)
                .complete()

        assertThat(result)
            .mappings()
            .atOrAfter(5f)
            .matchesLinearMapping(in1 = 5f, out1 = 1f, in2 = 15f, out2 = 2f)
    }

    @Test
    fun directionalSpec_mappingBuilder_continueWithTargetValue_matchesLinearMapping() {
        val result =
            DirectionalMotionSpec.builder(Spring)
                .toBreakpoint(5f)
                .jumpTo(1f)
                .continueWithTargetValue(target = 20f)
                .toBreakpoint(30f)
                .completeWith(Mapping.Identity)

        assertThat(result)
            .mappings()
            .atOrAfter(5f)
            .matchesLinearMapping(in1 = 5f, out1 = 1f, in2 = 30f, out2 = 20f)
    }

    @Test
    fun directionalSpec_mappingBuilder_reverse_continueWithTargetValue_matchesLinearMapping() {
        val result =
            DirectionalMotionSpec.reverseBuilder(Spring)
                .toBreakpoint(30f)
                .jumpTo(20f)
                .continueWithTargetValue(target = 1f)
                .toBreakpoint(5f)
                .completeWith(Mapping.Identity)

        assertThat(result)
            .mappings()
            .atOrAfter(5f)
            .matchesLinearMapping(in1 = 5f, out1 = 1f, in2 = 30f, out2 = 20f)
    }

    companion object {
        val Spring = SpringParameters(stiffness = 100f, dampingRatio = 1f)
        val B1 = BreakpointKey("One")
        val B2 = BreakpointKey("Two")
    }
}
