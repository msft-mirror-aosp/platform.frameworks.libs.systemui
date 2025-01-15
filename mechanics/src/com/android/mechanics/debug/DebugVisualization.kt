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

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import com.android.mechanics.MotionValue
import com.android.mechanics.spec.DirectionalMotionSpec
import com.android.mechanics.spec.Guarantee
import com.android.mechanics.spec.MotionSpec
import kotlin.math.max
import kotlin.math.min

/**
 * A debug visualization of the [motionValue].
 *
 * Draws both the [MotionValue.spec], as well as the input and output.
 *
 * NOTE: This is a debug tool, do not enable in production.
 *
 * @param motionValue The [MotionValue] to inspect.
 * @param inputRange The relevant range of the input (x) axis, for which to draw the graph.
 * @param color Color for the dots indicating the value
 * @param historySize Number of past values to draw as a trail.
 */
@Composable
fun DebugMotionValueVisualization(
    motionValue: MotionValue,
    inputRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    color: Color = Color.DarkGray,
    historySize: Int = 100,
) {
    val spec = motionValue.spec
    val outputRange = remember(spec, inputRange) { spec.computeOutputValueRange(inputRange) }

    Spacer(
        modifier =
            modifier
                .debugMotionSpecGraph(spec, inputRange, outputRange)
                .debugMotionValueGraph(motionValue, color, inputRange, outputRange, historySize)
    )
}

/**
 * Draws a full-sized debug visualization of [spec].
 *
 * NOTE: This is a debug tool, do not enable in production.
 *
 * @param inputRange The range of the input (x) axis
 * @param outputRange The range of the output (y) axis.
 */
fun Modifier.debugMotionSpecGraph(
    spec: MotionSpec,
    inputRange: ClosedFloatingPointRange<Float>,
    outputRange: ClosedFloatingPointRange<Float>,
): Modifier = drawBehind {
    drawAxis(Color.Gray)
    if (spec.isUnidirectional) {
        drawDirectionalSpec(spec.maxDirection, inputRange, outputRange, Color.Red)
    } else {
        drawDirectionalSpec(spec.minDirection, inputRange, outputRange, Color.Red)
        drawDirectionalSpec(spec.maxDirection, inputRange, outputRange, Color.Blue)
    }
}

/**
 * Draws a full-sized debug visualization of the [motionValue] state.
 *
 * This can be combined with [debugMotionSpecGraph], when [inputRange] and [outputRange] are the
 * same.
 *
 * NOTE: This is a debug tool, do not enable in production.
 *
 * @param color Color for the dots indicating the value
 * @param inputRange The range of the input (x) axis
 * @param outputRange The range of the output (y) axis.
 * @param historySize Number of past values to draw as a trail.
 */
@Composable
fun Modifier.debugMotionValueGraph(
    motionValue: MotionValue,
    color: Color,
    inputRange: ClosedFloatingPointRange<Float>,
    outputRange: ClosedFloatingPointRange<Float>,
    historySize: Int = 100,
): Modifier =
    this then DebugMotionValueGraphElement(motionValue, color, inputRange, outputRange, historySize)

/**
 * Utility to compute the min/max output values of the spec for the given input.
 *
 * Note: this only samples at breakpoint locations. For segment mappings that produce smaller/larger
 * values in between two breakpoints, this method might might not produce a correct result.
 */
fun MotionSpec.computeOutputValueRange(
    inputRange: ClosedFloatingPointRange<Float>
): ClosedFloatingPointRange<Float> {
    return if (isUnidirectional) {
        maxDirection.computeOutputValueRange(inputRange)
    } else {
        val maxRange = maxDirection.computeOutputValueRange(inputRange)
        val minRange = minDirection.computeOutputValueRange(inputRange)

        val start = min(minRange.start, maxRange.start)
        val endInclusive = max(minRange.endInclusive, maxRange.endInclusive)

        start..endInclusive
    }
}

/**
 * Utility to compute the min/max output values of the spec for the given input.
 *
 * Note: this only samples at breakpoint locations. For segment mappings that produce smaller/larger
 * values in between two breakpoints, this method might might not produce a correct result.
 */
fun DirectionalMotionSpec.computeOutputValueRange(
    inputRange: ClosedFloatingPointRange<Float>
): ClosedFloatingPointRange<Float> {

    val start = findBreakpointIndex(inputRange.start)
    val end = findBreakpointIndex(inputRange.endInclusive)

    val samples = buildList {
        add(mappings[start].map(inputRange.start))

        for (breakpointIndex in (start + 1)..end) {

            val position = breakpoints[breakpointIndex].position

            add(mappings[breakpointIndex - 1].map(position))
            add(mappings[breakpointIndex].map(position))
        }

        add(mappings[end].map(inputRange.endInclusive))
    }

    return samples.min()..samples.max()
}

private data class DebugMotionValueGraphElement(
    val motionValue: MotionValue,
    val color: Color,
    val inputRange: ClosedFloatingPointRange<Float>,
    val outputRange: ClosedFloatingPointRange<Float>,
    val historySize: Int,
) : ModifierNodeElement<DebugMotionValueGraphNode>() {

    init {
        require(historySize > 0)
    }

    override fun create() =
        DebugMotionValueGraphNode(motionValue, color, inputRange, outputRange, historySize)

    override fun update(node: DebugMotionValueGraphNode) {
        node.motionValue = motionValue
        node.color = color
        node.inputRange = inputRange
        node.outputRange = outputRange
        node.historySize = historySize
    }

    override fun InspectorInfo.inspectableProperties() {
        // intentionally empty
    }
}

private class DebugMotionValueGraphNode(
    motionValue: MotionValue,
    var color: Color,
    var inputRange: ClosedFloatingPointRange<Float>,
    var outputRange: ClosedFloatingPointRange<Float>,
    historySize: Int,
) : DrawModifierNode, ObserverModifierNode, Modifier.Node() {

    private var debugInspector by mutableStateOf<DebugInspector?>(null)
    private val history = mutableStateListOf<FrameData>()

    var historySize = historySize
        set(value) {
            field = value

            if (history.size > value) {
                history.removeRange(0, value - historySize)
            }
        }

    var motionValue = motionValue
        set(value) {
            if (value != field) {
                disposeDebugInspector()
                field = value

                if (isAttached) {
                    acquireDebugInspector()
                }
            }
        }

    override fun onAttach() {
        acquireDebugInspector()
    }

    override fun onDetach() {
        disposeDebugInspector()
    }

    private fun acquireDebugInspector() {
        debugInspector = motionValue.debugInspector()
        observeFrameAndAddToHistory()
    }

    private fun disposeDebugInspector() {
        debugInspector?.dispose()
        debugInspector = null
        history.clear()
    }

    override fun ContentDrawScope.draw() {
        drawInputOutputTrail(history, inputRange, outputRange, color)
        drawContent()
    }

    private fun observeFrameAndAddToHistory() {
        var lastFrame: FrameData? = null

        observeReads { lastFrame = debugInspector?.frame }

        lastFrame?.also {
            history.add(it)
            if (history.size > historySize) {
                history.removeFirst()
            }
        }
    }

    override fun onObservedReadsChanged() {
        observeFrameAndAddToHistory()
    }
}

private val MotionSpec.isUnidirectional: Boolean
    get() = maxDirection == minDirection

private fun DrawScope.mapPointInInputToX(
    input: Float,
    inputRange: ClosedFloatingPointRange<Float>,
): Float {
    val inputExtent = (inputRange.endInclusive - inputRange.start)
    return ((input - inputRange.start) / (inputExtent)) * size.width
}

private fun DrawScope.mapPointInOutputToY(
    output: Float,
    outputRange: ClosedFloatingPointRange<Float>,
): Float {
    val outputExtent = (outputRange.endInclusive - outputRange.start)
    return (1 - (output - outputRange.start) / (outputExtent)) * size.height
}

private fun DrawScope.drawDirectionalSpec(
    spec: DirectionalMotionSpec,
    inputRange: ClosedFloatingPointRange<Float>,
    outputRange: ClosedFloatingPointRange<Float>,
    color: Color,
) {

    val startSegment = spec.findBreakpointIndex(inputRange.start)
    val endSegment = spec.findBreakpointIndex(inputRange.endInclusive)

    for (segmentIndex in startSegment..endSegment) {
        val mapping = spec.mappings[segmentIndex]
        val startBreakpoint = spec.breakpoints[segmentIndex]
        val segmentStart = startBreakpoint.position
        val fromInput = segmentStart.coerceAtLeast(inputRange.start)
        val endBreakpoint = spec.breakpoints[segmentIndex + 1]
        val segmentEnd = endBreakpoint.position
        val toInput = segmentEnd.coerceAtMost(inputRange.endInclusive)

        // TODO add support for functions that are not linear
        val fromY = mapPointInOutputToY(mapping.map(fromInput), outputRange)
        val toY = mapPointInOutputToY(mapping.map(toInput), outputRange)

        val start = Offset(mapPointInInputToX(fromInput, inputRange), fromY)
        val end = Offset(mapPointInInputToX(toInput, inputRange), toY)
        drawLine(color, start, end)

        if (segmentStart == fromInput) {
            drawCircle(color, 2.dp.toPx(), start)
        }

        if (segmentEnd == toInput) {
            drawCircle(color, 2.dp.toPx(), end)
        }

        val guarantee = startBreakpoint.guarantee
        if (guarantee is Guarantee.InputDelta) {
            val guaranteePos = segmentStart + guarantee.delta
            if (guaranteePos > inputRange.start) {

                val guaranteeOffset =
                    Offset(
                        mapPointInInputToX(guaranteePos, inputRange),
                        mapPointInOutputToY(mapping.map(guaranteePos), outputRange),
                    )

                val arrowSize = 4.dp.toPx()

                drawLine(
                    color,
                    guaranteeOffset,
                    guaranteeOffset.plus(Offset(arrowSize, -arrowSize)),
                )
                drawLine(color, guaranteeOffset, guaranteeOffset.plus(Offset(arrowSize, arrowSize)))
            }
        }
    }
}

private fun DrawScope.drawInputOutputTrail(
    history: List<FrameData>,
    inputRange: ClosedFloatingPointRange<Float>,
    outputRange: ClosedFloatingPointRange<Float>,
    color: Color,
) {
    history.fastForEachIndexed { index, frame ->
        val x = mapPointInInputToX(frame.input, inputRange)
        val y = mapPointInOutputToY(frame.output, outputRange)

        drawCircle(color, 2.dp.toPx(), Offset(x, y), alpha = index / history.size.toFloat())
    }
}

private fun DrawScope.drawAxis(color: Color) {

    drawXAxis(color)
    drawYAxis(color)
}

private fun DrawScope.drawYAxis(color: Color, atX: Float = 0f) {

    val arrowSize = 4.dp.toPx()

    drawLine(color, Offset(atX, size.height), Offset(atX, 0f))
    drawLine(color, Offset(atX, 0f), Offset(atX + arrowSize, arrowSize))
    drawLine(color, Offset(atX, 0f), Offset(atX - arrowSize, arrowSize))
}

private fun DrawScope.drawXAxis(color: Color, atY: Float = size.height) {

    val arrowSize = 4.dp.toPx()

    drawLine(color, Offset(0f, atY), Offset(size.width, atY))
    drawLine(color, Offset(size.width, atY), Offset(size.width - arrowSize, atY + arrowSize))
    drawLine(color, Offset(size.width, atY), Offset(size.width - arrowSize, atY - arrowSize))
}
