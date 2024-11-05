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

package com.google.android.wallpaper.weathereffects.graphics.snow

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.util.SizeF
import com.google.android.wallpaper.weathereffects.graphics.FrameBuffer
import com.google.android.wallpaper.weathereffects.graphics.WeatherEffect
import com.google.android.wallpaper.weathereffects.graphics.WeatherEffect.Companion.DEFAULT_INTENSITY
import com.google.android.wallpaper.weathereffects.graphics.utils.GraphicsUtils
import com.google.android.wallpaper.weathereffects.graphics.utils.MathUtils
import com.google.android.wallpaper.weathereffects.graphics.utils.MatrixUtils.centerCropMatrix
import com.google.android.wallpaper.weathereffects.graphics.utils.MatrixUtils.extractTranslationMatrix
import com.google.android.wallpaper.weathereffects.graphics.utils.MatrixUtils.getScale
import com.google.android.wallpaper.weathereffects.graphics.utils.MatrixUtils.postprocessParallaxMatrix
import com.google.android.wallpaper.weathereffects.graphics.utils.TimeUtils
import java.util.concurrent.Executor
import kotlin.random.Random

/** Defines and generates the rain weather effect animation. */
class SnowEffect(
    /** The config of the snow effect. */
    private val snowConfig: SnowEffectConfig,
    private var foreground: Bitmap,
    private var background: Bitmap,
    private var intensity: Float = DEFAULT_INTENSITY,
    /** The initial size of the surface where the effect will be shown. */
    private var surfaceSize: SizeF,
    /** App main executor. */
    private val mainExecutor: Executor,
) : WeatherEffect {

    private var snowSpeed: Float = 0.8f
    private val snowPaint = Paint().also { it.shader = snowConfig.colorGradingShader }
    private var elapsedTime: Float = 0f

    private var frameBuffer = FrameBuffer(background.width, background.height)
    private val frameBufferPaint = Paint().also { it.shader = snowConfig.accumulatedSnowShader }

    private var matrix: Matrix =
        centerCropMatrix(
            surfaceSize,
            SizeF(this.foreground.width.toFloat(), this.foreground.height.toFloat()),
        )

    private var scale = getScale(matrix)

    init {
        frameBuffer.setRenderEffect(
            RenderEffect.createBlurEffect(4f / scale, 4f / scale, Shader.TileMode.CLAMP)
        )
        updateTextureUniforms()
        adjustCropping(surfaceSize)
        prepareColorGrading()
        updateSnowGridSize(surfaceSize)
        setIntensity(intensity)

        // Generate accumulated snow at the end after we updated all the uniforms.
        generateAccumulatedSnow()
    }

    override fun resize(newSurfaceSize: SizeF) {
        surfaceSize = newSurfaceSize
        adjustCropping(newSurfaceSize)
        updateSnowGridSize(newSurfaceSize)
    }

    override fun update(deltaMillis: Long, frameTimeNanos: Long) {
        elapsedTime += snowSpeed * TimeUtils.millisToSeconds(deltaMillis)

        snowConfig.shader.setFloatUniform("time", elapsedTime)
        snowConfig.colorGradingShader.setInputShader("texture", snowConfig.shader)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPaint(snowPaint)
    }

    override fun reset() {
        elapsedTime = Random.nextFloat() * 90f
    }

    override fun release() {
        snowConfig.lut?.recycle()
        frameBuffer.close()
    }

    override fun setIntensity(intensity: Float) {
        /**
         * Increase effect speed as weather intensity decreases. This compensates for the floaty
         * appearance when there are fewer particles at the original speed.
         */
        snowSpeed = MathUtils.map(intensity, 0f, 1f, 2.5f, 1.7f)

        snowConfig.shader.setFloatUniform("intensity", intensity)
        snowConfig.colorGradingShader.setFloatUniform(
            "intensity",
            snowConfig.colorGradingIntensity * intensity,
        )
        this.intensity = intensity
        // Regenerate accumulated snow since the uniform changed.
        generateAccumulatedSnow()
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

        matrix =
            centerCropMatrix(
                surfaceSize,
                SizeF(this.foreground.width.toFloat(), this.foreground.height.toFloat()),
            )

        scale = getScale(matrix)
        frameBuffer =
            FrameBuffer(background.width, background.height).apply {
                setRenderEffect(
                    RenderEffect.createBlurEffect(4f / scale, 4f / scale, Shader.TileMode.CLAMP)
                )
            }
        snowConfig.shader.setInputBuffer(
            "background",
            BitmapShader(this.background, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR),
        )
        snowConfig.shader.setInputBuffer(
            "foreground",
            BitmapShader(this.foreground, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR),
        )

        adjustCropping(surfaceSize)
        // generateAccumulatedSnow needs foreground for accumulatedSnowShader, and needs frameBuffer
        // which is also changed with background
        generateAccumulatedSnow()
    }

    override fun setMatrix(matrix: Matrix) {
        this.matrix = matrix
        adjustCropping(surfaceSize)
        generateAccumulatedSnow()
    }

    private fun adjustCropping(surfaceSize: SizeF) {
        val postprocessedMatrix = postprocessParallaxMatrix(matrix)
        val weatherMatrix = extractTranslationMatrix(postprocessedMatrix)
        snowConfig.shader.setFloatUniform("transformMatrixFgd", postprocessedMatrix)
        snowConfig.shader.setFloatUniform("transformMatrixBgd", postprocessedMatrix)
        snowConfig.shader.setFloatUniform("transformMatrixWeather", weatherMatrix)
        snowConfig.shader.setFloatUniform("screenSize", surfaceSize.width, surfaceSize.height)
        snowConfig.shader.setFloatUniform(
            "screenAspectRatio",
            GraphicsUtils.getAspectRatio(surfaceSize),
        )
    }

    private fun updateTextureUniforms() {
        snowConfig.shader.setInputBuffer(
            "foreground",
            BitmapShader(foreground, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR),
        )

        snowConfig.shader.setInputBuffer(
            "background",
            BitmapShader(background, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR),
        )

        snowConfig.shader.setInputBuffer(
            "noise",
            BitmapShader(snowConfig.noiseTexture, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT),
        )
    }

    private fun prepareColorGrading() {
        snowConfig.colorGradingShader.setInputShader("texture", snowConfig.shader)
        snowConfig.lut?.let {
            snowConfig.colorGradingShader.setInputShader(
                "lut",
                BitmapShader(it, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR),
            )
        }
    }

    private fun generateAccumulatedSnow() {
        val renderingCanvas = frameBuffer.beginDrawing()
        snowConfig.accumulatedSnowShader.setFloatUniform("scale", scale)
        snowConfig.accumulatedSnowShader.setFloatUniform(
            "snowThickness",
            snowConfig.maxAccumulatedSnowThickness * intensity / scale,
        )
        snowConfig.accumulatedSnowShader.setFloatUniform("screenWidth", surfaceSize.width)
        snowConfig.accumulatedSnowShader.setInputBuffer(
            "foreground",
            BitmapShader(foreground, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR),
        )
        renderingCanvas.drawPaint(frameBufferPaint)
        frameBuffer.endDrawing()

        frameBuffer.tryObtainingImage(
            { image ->
                snowConfig.shader.setInputBuffer(
                    "accumulatedSnow",
                    BitmapShader(image, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR),
                )
            },
            mainExecutor,
        )
    }

    private fun updateSnowGridSize(surfaceSize: SizeF) {
        val gridSize = GraphicsUtils.computeDefaultGridSize(surfaceSize, snowConfig.pixelDensity)
        snowConfig.shader.setFloatUniform("gridSize", 7 * gridSize, 2f * gridSize)
    }
}
