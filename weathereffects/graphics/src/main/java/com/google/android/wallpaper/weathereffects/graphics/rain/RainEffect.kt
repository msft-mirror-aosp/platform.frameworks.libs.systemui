/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.google.android.wallpaper.weathereffects.graphics.rain

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.util.SizeF
import com.google.android.wallpaper.weathereffects.graphics.FrameBuffer
import com.google.android.wallpaper.weathereffects.graphics.WeatherEffect
import com.google.android.wallpaper.weathereffects.graphics.WeatherEffect.Companion.DEFAULT_INTENSITY
import com.google.android.wallpaper.weathereffects.graphics.utils.GraphicsUtils
import com.google.android.wallpaper.weathereffects.graphics.utils.MatrixUtils.centerCropMatrix
import com.google.android.wallpaper.weathereffects.graphics.utils.MatrixUtils.postprocessParallaxMatrix
import com.google.android.wallpaper.weathereffects.graphics.utils.SolidColorShader
import com.google.android.wallpaper.weathereffects.graphics.utils.TimeUtils
import java.util.concurrent.Executor
import kotlin.random.Random

/** Defines and generates the rain weather effect animation. */
class RainEffect(
    /** The config of the rain effect. */
    private val rainConfig: RainEffectConfig,
    private var foreground: Bitmap,
    private var background: Bitmap,
    private var intensity: Float = DEFAULT_INTENSITY,
    /** The initial size of the surface where the effect will be shown. */
    private var surfaceSize: SizeF,
    private val mainExecutor: Executor,
) : WeatherEffect {

    private val rainPaint = Paint().also { it.shader = rainConfig.colorGradingShader }

    // Set blur effect to reduce the outline noise. No need to set blur effect every time we
    // re-generate the outline buffer.
    private var outlineBuffer =
        FrameBuffer(background.width, background.height).apply {
            setRenderEffect(RenderEffect.createBlurEffect(2f, 2f, Shader.TileMode.CLAMP))
        }
    private val outlineBufferPaint = Paint().also { it.shader = rainConfig.outlineShader }

    private var elapsedTime: Float = 0f

    private var matrix: Matrix? = null

    init {
        updateTextureUniforms()
        adjustCropping(surfaceSize)
        prepareColorGrading()
        updateRainGridSize(surfaceSize)
        setIntensity(intensity)
    }

    override fun resize(newSurfaceSize: SizeF) {
        adjustCropping(newSurfaceSize)
        updateRainGridSize(newSurfaceSize)
        surfaceSize = newSurfaceSize
    }

    override fun update(deltaMillis: Long, frameTimeNanos: Long) {
        elapsedTime += TimeUtils.millisToSeconds(deltaMillis)

        rainConfig.rainShowerShader.setFloatUniform("time", elapsedTime)
        rainConfig.glassRainShader.setFloatUniform("time", elapsedTime)

        rainConfig.glassRainShader.setInputShader("texture", rainConfig.rainShowerShader)
        rainConfig.colorGradingShader.setInputShader("texture", rainConfig.glassRainShader)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPaint(rainPaint)
    }

    override fun reset() {
        elapsedTime = Random.nextFloat() * 90f
    }

    override fun release() {
        rainConfig.lut?.recycle()
        outlineBuffer.close()
    }

    override fun setIntensity(intensity: Float) {
        rainConfig.rainShowerShader.setFloatUniform("intensity", intensity)
        rainConfig.glassRainShader.setFloatUniform("intensity", intensity)
        rainConfig.colorGradingShader.setFloatUniform(
            "intensity",
            rainConfig.colorGradingIntensity * intensity,
        )
        val thickness = 1f + intensity * 10f
        rainConfig.outlineShader.setFloatUniform("thickness", thickness)

        // Need to recreate the outline buffer as the uniform has changed.
        createOutlineBuffer()
    }

    override fun setBitmaps(foreground: Bitmap?, background: Bitmap) {
        if (this.foreground == foreground && this.background == background) {
            return
        }
        // Only when background changes, we can infer the bitmap set changes
        if (this.background != background) {
            this.background.recycle()
            this.foreground.recycle()
        }
        this.background = background
        this.foreground = foreground ?: background

        outlineBuffer =
            FrameBuffer(background.width, background.height).apply {
                setRenderEffect(RenderEffect.createBlurEffect(2f, 2f, Shader.TileMode.CLAMP))
            }
        adjustCropping(surfaceSize)
        updateTextureUniforms()

        // Need to recreate the outline buffer as the outlineBuffer has changed due to background
        createOutlineBuffer()
    }

    override fun setMatrix(matrix: Matrix) {
        this.matrix = matrix
        adjustCropping(surfaceSize)
    }

    private fun adjustCropping(surfaceSize: SizeF) {
        if (matrix == null) {
            matrix =
                centerCropMatrix(
                    surfaceSize,
                    SizeF(foreground.width.toFloat(), foreground.height.toFloat()),
                )
        }
        val postprocessedMatrix = postprocessParallaxMatrix(matrix!!)
        rainConfig.rainShowerShader.setFloatUniform("transformMatrixFgd", postprocessedMatrix)
        rainConfig.rainShowerShader.setFloatUniform("transformMatrixBgd", postprocessedMatrix)

        rainConfig.rainShowerShader.setFloatUniform(
            "screenSize",
            surfaceSize.width,
            surfaceSize.height,
        )
        rainConfig.glassRainShader.setFloatUniform(
            "screenSize",
            surfaceSize.width,
            surfaceSize.height,
        )

        val screenAspectRatio = GraphicsUtils.getAspectRatio(surfaceSize)
        rainConfig.rainShowerShader.setFloatUniform("screenAspectRatio", screenAspectRatio)
        rainConfig.glassRainShader.setFloatUniform("screenAspectRatio", screenAspectRatio)
    }

    private fun updateTextureUniforms() {
        val foregroundBuffer =
            BitmapShader(foreground, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
        rainConfig.rainShowerShader.setInputBuffer("foreground", foregroundBuffer)
        rainConfig.outlineShader.setInputBuffer("texture", foregroundBuffer)

        rainConfig.rainShowerShader.setInputBuffer(
            "background",
            BitmapShader(background, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR),
        )
    }

    private fun createOutlineBuffer() {
        val canvas = outlineBuffer.beginDrawing()
        canvas.drawPaint(outlineBufferPaint)
        outlineBuffer.endDrawing()

        outlineBuffer.tryObtainingImage(
            { buffer ->
                rainConfig.rainShowerShader.setInputBuffer(
                    "outlineBuffer",
                    BitmapShader(buffer, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR),
                )
            },
            mainExecutor,
        )
    }

    private fun prepareColorGrading() {
        // Initialize the buffer with black, so that we don't ever draw garbage buffer.
        rainConfig.glassRainShader.setInputShader("texture", SolidColorShader(Color.BLACK))
        rainConfig.colorGradingShader.setInputShader("texture", rainConfig.glassRainShader)
        rainConfig.lut?.let {
            rainConfig.colorGradingShader.setInputShader(
                "lut",
                BitmapShader(it, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR),
            )
        }
    }

    private fun updateRainGridSize(surfaceSize: SizeF) {
        val widthScreenScale =
            GraphicsUtils.computeDefaultGridSize(surfaceSize, rainConfig.pixelDensity)
        rainConfig.rainShowerShader.setFloatUniform("gridScale", widthScreenScale)
        rainConfig.glassRainShader.setFloatUniform("gridScale", widthScreenScale)
    }
}
