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

package com.android.mechanics.testing

import com.android.mechanics.spec.Breakpoint
import com.android.mechanics.spec.BreakpointKey
import com.android.mechanics.spec.DirectionalMotionSpec
import com.android.mechanics.spec.Mapping
import com.android.mechanics.testing.BreakpointSubject.Companion.BreakpointKeys
import com.android.mechanics.testing.BreakpointSubject.Companion.BreakpointPositions
import com.google.common.truth.Correspondence
import com.google.common.truth.FailureMetadata
import com.google.common.truth.FloatSubject
import com.google.common.truth.IterableSubject
import com.google.common.truth.Subject
import com.google.common.truth.Subject.Factory
import com.google.common.truth.Truth

/** Subject to verify the definition of a [DirectionalMotionSpec]. */
class DirectionalMotionSpecSubject
internal constructor(failureMetadata: FailureMetadata, private val actual: DirectionalMotionSpec?) :
    Subject(failureMetadata, actual) {

    /** Assert on breakpoints, excluding the implicit start and end breakpoints. */
    fun breakpoints(): BreakpointsSubject {
        isNotNull()

        return check("breakpoints").about(BreakpointsSubject.subjectFactory).that(actual)
    }

    /** Assert on the mappings. */
    fun mappings(): MappingsSubject {
        isNotNull()

        return check("mappings").about(MappingsSubject.subjectFactory).that(actual)
    }

    companion object {

        /** Returns a factory to be used with [Truth.assertAbout]. */
        fun directionalMotionSpec(): Factory<DirectionalMotionSpecSubject, DirectionalMotionSpec> {
            return Factory { failureMetadata: FailureMetadata, subject: DirectionalMotionSpec? ->
                DirectionalMotionSpecSubject(failureMetadata, subject)
            }
        }

        /** Shortcut for `Truth.assertAbout(directionalMotionSpec()).that(spec)`. */
        fun assertThat(spec: DirectionalMotionSpec): DirectionalMotionSpecSubject =
            Truth.assertAbout(directionalMotionSpec()).that(spec)
    }
}

/** Subject to assert on the list of breakpoints of a [DirectionalMotionSpec]. */
class BreakpointsSubject(
    failureMetadata: FailureMetadata,
    private val actual: DirectionalMotionSpec?,
) : IterableSubject(failureMetadata, actual?.breakpoints?.let { it.slice(1 until it.size - 1) }) {

    fun keys() = comparingElementsUsing(BreakpointKeys)

    fun positions() = comparingElementsUsing(BreakpointPositions)

    fun atPosition(position: Float): BreakpointSubject {
        return check("breakpoint @ $position")
            .about(BreakpointSubject.subjectFactory)
            .that(actual?.breakpoints?.find { it.position == position })
    }

    fun withKey(key: BreakpointKey): BreakpointSubject {
        return check("breakpoint with $key]")
            .about(BreakpointSubject.subjectFactory)
            .that(actual?.breakpoints?.find { it.key == key })
    }

    companion object {

        /** Returns a factory to be used with [Truth.assertAbout]. */
        val subjectFactory =
            Factory<BreakpointsSubject, DirectionalMotionSpec> { failureMetadata, subject ->
                BreakpointsSubject(failureMetadata, subject)
            }
    }
}

/** Subject to assert on a [Breakpoint] definition. */
class BreakpointSubject
internal constructor(failureMetadata: FailureMetadata, private val actual: Breakpoint?) :
    Subject(failureMetadata, actual) {

    fun exists() {
        isNotNull()
    }

    fun key(): Subject {
        return check("key").that(actual?.key)
    }

    fun position(): FloatSubject {
        return check("position").that(actual?.position)
    }

    fun guarantee(): Subject {
        return check("guarantee").that(actual?.guarantee)
    }

    fun spring(): Subject {
        return check("spring").that(actual?.spring)
    }

    fun isAt(position: Float) = position().isEqualTo(position)

    fun hasKey(key: BreakpointKey) = key().isEqualTo(key)

    companion object {
        val BreakpointKeys =
            Correspondence.transforming<Breakpoint, BreakpointKey>({ it?.key }, "key")
        val BreakpointPositions =
            Correspondence.transforming<Breakpoint, Float>({ it?.position }, "position")

        /** Returns a factory to be used with [Truth.assertAbout]. */
        val subjectFactory =
            Factory<BreakpointSubject, Breakpoint> { failureMetadata, subject ->
                BreakpointSubject(failureMetadata, subject)
            }
    }
}

/** Subject to assert on the list of mappings of a [DirectionalMotionSpec]. */
class MappingsSubject(
    failureMetadata: FailureMetadata,
    private val actual: DirectionalMotionSpec?,
) : IterableSubject(failureMetadata, actual?.mappings) {

    /** Assert on the mapping at or after the specified position. */
    fun atOrAfter(position: Float): MappingSubject {
        return check("mapping @ $position")
            .about(MappingSubject.subjectFactory)
            .that(actual?.run { mappings[findBreakpointIndex(position)] })
    }

    companion object {
        /** Returns a factory to be used with [Truth.assertAbout]. */
        val subjectFactory =
            Factory<MappingsSubject, DirectionalMotionSpec> { failureMetadata, subject ->
                MappingsSubject(failureMetadata, subject)
            }
    }
}

/** Subject to assert on a [Mapping] function. */
class MappingSubject
internal constructor(failureMetadata: FailureMetadata, private val actual: Mapping?) :
    Subject(failureMetadata, actual) {

    fun matchesLinearMapping(in1: Float, out1: Float, in2: Float, out2: Float) {
        isNotNull()

        check("input @ $in1").that(actual?.map(in1)).isEqualTo(out1)
        check("input @ $in2").that(actual?.map(in2)).isEqualTo(out2)
    }

    fun isConstantValue(value: Float) {
        when (actual) {
            is Mapping.Fixed -> check("fixed value").that(actual.value).isEqualTo(value)
            is Mapping.Linear -> {
                check("linear factor").that(actual.factor).isZero()
                check("linear offset").that(actual.offset).isEqualTo(value)
            }

            else -> failWithActual("Unexpected mapping type", actual)
        }
    }

    companion object {
        /** Returns a factory to be used with [Truth.assertAbout]. */
        val subjectFactory =
            Factory<MappingSubject, Mapping> { failureMetadata, subject ->
                MappingSubject(failureMetadata, subject)
            }
    }
}
