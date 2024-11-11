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
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.SizeF
import com.google.android.wallpaper.weathereffects.graphics.FrameBuffer
import com.google.android.wallpaper.weathereffects.graphics.WeatherEffect.Companion.DEFAULT_INTENSITY
import com.google.android.wallpaper.weathereffects.graphics.WeatherEffectBase
import com.google.android.wallpaper.weathereffects.graphics.utils.GraphicsUtils
import com.google.android.wallpaper.weathereffects.graphics.utils.MathUtils
import com.google.android.wallpaper.weathereffects.graphics.utils.MatrixUtils.getScale
import com.google.android.wallpaper.weathereffects.graphics.utils.TimeUtils
import java.util.concurrent.Executor

/** Defines and generates the rain weather effect animation. */
class SnowEffect(
    /** The config of the snow effect. */
    private val snowConfig: SnowEffectConfig,
    foreground: Bitmap,
    background: Bitmap,
    private var intensity: Float = DEFAULT_INTENSITY,
    /** The initial size of the surface where the effect will be shown. */
    private var surfaceSize: SizeF,
    /** App main executor. */
    private val mainExecutor: Executor,
) : WeatherEffectBase(foreground, background, surfaceSize) {

    private var snowSpeed: Float = 0.8f
    private val snowPaint = Paint().also { it.shader = snowConfig.colorGradingShader }

    private var frameBuffer = FrameBuffer(background.width, background.height)
    private val frameBufferPaint = Paint().also { it.shader = snowConfig.accumulatedSnowShader }

    private var scale = getScale(parallaxMatrix)

    init {
        frameBuffer.setRenderEffect(
            RenderEffect.createBlurEffect(4f / scale, 4f / scale, Shader.TileMode.CLAMP)
        )
        updateTextureUniforms()
        adjustCropping(surfaceSize)
        prepareColorGrading()
        updateGridSize(surfaceSize)
        setIntensity(intensity)

        // Generate accumulated snow at the end after we updated all the uniforms.
        generateAccumulatedSnow()
    }

    override fun update(deltaMillis: Long, frameTimeNanos: Long) {
        elapsedTime += snowSpeed * TimeUtils.millisToSeconds(deltaMillis)

        snowConfig.shader.setFloatUniform("time", elapsedTime)
        snowConfig.colorGradingShader.setInputShader("texture", snowConfig.shader)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPaint(snowPaint)
    }

    override fun release() {
        super.release()
        frameBuffer.close()
    }

    override fun setIntensity(intensity: Float) {
        super.setIntensity(intensity)
        /**
         * Increase effect speed as weather intensity decreases. This compensates for the floaty
         * appearance when there are fewer particles at the original speed.
         */
        snowSpeed = MathUtils.map(intensity, 0f, 1f, 2.5f, 1.7f)
        this.intensity = intensity
        // Regenerate accumulated snow since the uniform changed.
        generateAccumulatedSnow()
    }

    override fun setBitmaps(foreground: Bitmap?, background: Bitmap) {
        super.setBitmaps(foreground, background)
        scale = getScale(parallaxMatrix)
        frameBuffer =
            FrameBuffer(background.width, background.height).apply {
                setRenderEffect(
                    RenderEffect.createBlurEffect(4f / scale, 4f / scale, Shader.TileMode.CLAMP)
                )
            }
        // GenerateAccumulatedSnow needs foreground for accumulatedSnowShader, and needs frameBuffer
        // which is also changed with background
        generateAccumulatedSnow()
    }

    override val shader: RuntimeShader
        get() = snowConfig.shader

    override val colorGradingShader: RuntimeShader
        get() = snowConfig.colorGradingShader

    override val lut: Bitmap?
        get() = snowConfig.lut

    override val colorGradingIntensity: Float
        get() = snowConfig.colorGradingIntensity

    override fun setMatrix(matrix: Matrix) {
        super.setMatrix(matrix)
        generateAccumulatedSnow()
    }

    override fun updateTextureUniforms() {
        super.updateTextureUniforms()
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

    override fun updateGridSize(newSurfaceSize: SizeF) {
        val gridSize = GraphicsUtils.computeDefaultGridSize(newSurfaceSize, snowConfig.pixelDensity)
        snowConfig.shader.setFloatUniform("gridSize", 7 * gridSize, 2f * gridSize)
    }
}
