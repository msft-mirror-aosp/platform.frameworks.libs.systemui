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

@file:OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)

package com.android.mechanics.testing

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.android.mechanics.DistanceGestureContext
import com.android.mechanics.MotionValue
import com.android.mechanics.debug.FrameData
import com.android.mechanics.spec.InputDirection
import com.android.mechanics.spec.MotionSpec
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.test.motion.MotionTestRule
import platform.test.motion.RecordedMotion.Companion.create
import platform.test.motion.golden.Feature
import platform.test.motion.golden.FrameId
import platform.test.motion.golden.TimeSeries
import platform.test.motion.golden.TimestampFrameId
import platform.test.motion.golden.ValueDataPoint
import platform.test.motion.golden.asDataPoint

/** Toolkit to support [MotionValue] motion tests. */
class MotionValueToolkit(val composeTestRule: ComposeContentTestRule) {
    companion object {

        val TimeSeries.input: List<Float>
            get() = dataPoints("input")

        val TimeSeries.output: List<Float>
            get() = dataPoints("output")

        val TimeSeries.outputTarget: List<Float>
            get() = dataPoints("outputTarget")

        val TimeSeries.isStable: List<Boolean>
            get() = dataPoints("isStable")

        internal const val TAG = "MotionValueToolkit"

        private fun <T> TimeSeries.dataPoints(featureName: String): List<T> {
            @Suppress("UNCHECKED_CAST")
            return (features[featureName] as Feature<T>).dataPoints.map {
                require(it is ValueDataPoint)
                it.value
            }
        }
    }
}

interface InputScope {
    val input: Float
    val gestureContext: DistanceGestureContext
    val underTest: MotionValue

    suspend fun awaitStable()

    suspend fun awaitFrames(frames: Int = 1)

    var directionChangeSlop: Float

    fun updateValue(position: Float)

    suspend fun animateValueTo(
        targetValue: Float,
        changePerFrame: Float = abs(input - targetValue) / 5f,
    )

    fun reset(position: Float, direction: InputDirection)
}

fun MotionTestRule<MotionValueToolkit>.goldenTest(
    spec: MotionSpec,
    initialValue: Float = 0f,
    initialDirection: InputDirection = InputDirection.Max,
    directionChangeSlop: Float = 5f,
    stableThreshold: Float = 0.01f,
    verifyTimeSeries: TimeSeries.() -> Unit = {},
    testInput: suspend InputScope.() -> Unit,
) = runTest {
    with(toolkit.composeTestRule) {
        val frameEmitter = MutableStateFlow<Long>(0)

        val testHarness =
            MotionValueTestHarness(
                initialValue,
                initialDirection,
                spec,
                stableThreshold,
                directionChangeSlop,
                frameEmitter.asStateFlow(),
            )
        val underTest = testHarness.underTest
        val inspector = underTest.debugInspector()

        setContent { LaunchedEffect(Unit) { underTest.keepRunning() } }

        val recordingJob = launch { testInput.invoke(testHarness) }

        waitForIdle()
        mainClock.autoAdvance = false

        val frameIds = mutableListOf<FrameId>()
        val frameData = mutableListOf<FrameData>()

        fun recordFrame(frameId: TimestampFrameId) {
            frameIds.add(frameId)
            frameData.add(inspector.frame)
        }

        val startFrameTime = mainClock.currentTime
        recordFrame(TimestampFrameId(mainClock.currentTime - startFrameTime))
        while (!recordingJob.isCompleted) {
            frameEmitter.tryEmit(mainClock.currentTime + 16)
            runCurrent()
            mainClock.advanceTimeByFrame()
            recordFrame(TimestampFrameId(mainClock.currentTime - startFrameTime))
        }

        val timeSeries =
            TimeSeries(
                frameIds.toList(),
                listOf(
                    Feature("input", frameData.map { it.input.asDataPoint() }),
                    Feature(
                        "gestureDirection",
                        frameData.map { it.gestureDirection.name.asDataPoint() },
                    ),
                    Feature("output", frameData.map { it.output.asDataPoint() }),
                    Feature("outputTarget", frameData.map { it.outputTarget.asDataPoint() }),
                    Feature("outputSpring", frameData.map { it.springParameters.asDataPoint() }),
                    Feature("isStable", frameData.map { it.isStable.asDataPoint() }),
                ),
            )

        inspector.dispose()

        val recordedMotion = create(timeSeries, screenshots = null)
        verifyTimeSeries.invoke(recordedMotion.timeSeries)
        assertThat(recordedMotion).timeSeriesMatchesGolden()
    }
}

private class MotionValueTestHarness(
    initialInput: Float,
    initialDirection: InputDirection,
    spec: MotionSpec,
    stableThreshold: Float,
    directionChangeSlop: Float,
    val onFrame: StateFlow<Long>,
) : InputScope {

    override var input by mutableFloatStateOf(initialInput)
    override val gestureContext: DistanceGestureContext =
        DistanceGestureContext(initialInput, initialDirection, directionChangeSlop)

    override val underTest =
        MotionValue(
            { input },
            gestureContext,
            stableThreshold = stableThreshold,
            initialSpec = spec,
        )

    override fun updateValue(position: Float) {
        input = position
        gestureContext.distance = position
    }

    override var directionChangeSlop: Float
        get() = gestureContext.directionChangeSlop
        set(value) {
            gestureContext.directionChangeSlop = value
        }

    override suspend fun awaitStable() {
        val debugInspector = underTest.debugInspector()
        try {

            onFrame
                // Since this is a state-flow, the current frame is counted too.
                .drop(1)
                .takeWhile { !debugInspector.frame.isStable }
                .collect {}
        } finally {
            debugInspector.dispose()
        }
    }

    override suspend fun awaitFrames(frames: Int) {
        onFrame
            // Since this is a state-flow, the current frame is counted too.
            .drop(1)
            .take(frames)
            .collect {}
    }

    override suspend fun animateValueTo(targetValue: Float, changePerFrame: Float) {
        require(changePerFrame > 0f)
        var currentValue = input
        val delta = targetValue - currentValue
        val step = changePerFrame * delta.sign

        val stepCount = floor((abs(delta) / changePerFrame) - 1).toInt()
        repeat(stepCount) {
            currentValue += step
            updateValue(currentValue)
            awaitFrames()
        }

        updateValue(targetValue)
        awaitFrames()
    }

    override fun reset(position: Float, direction: InputDirection) {
        input = position
        gestureContext.reset(position, direction)
    }
}
