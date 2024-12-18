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
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.SizeF
import com.google.android.wallpaper.weathereffects.graphics.FrameBuffer
import com.google.android.wallpaper.weathereffects.graphics.WeatherEffect.Companion.DEFAULT_INTENSITY
import com.google.android.wallpaper.weathereffects.graphics.WeatherEffectBase
import com.google.android.wallpaper.weathereffects.graphics.utils.GraphicsUtils
import com.google.android.wallpaper.weathereffects.graphics.utils.SolidColorShader
import com.google.android.wallpaper.weathereffects.graphics.utils.TimeUtils
import java.util.concurrent.Executor

/** Defines and generates the rain weather effect animation. */
class RainEffect(
    /** The config of the rain effect. */
    private val rainConfig: RainEffectConfig,
    foreground: Bitmap,
    background: Bitmap,
    intensity: Float = DEFAULT_INTENSITY,
    /** The initial size of the surface where the effect will be shown. */
    surfaceSize: SizeF,
    private val mainExecutor: Executor,
) : WeatherEffectBase(foreground, background, surfaceSize) {

    private val rainPaint = Paint().also { it.shader = rainConfig.colorGradingShader }

    // Set blur effect to reduce the outline noise. No need to set blur effect every time we
    // re-generate the outline buffer.
    private var outlineBuffer =
        FrameBuffer(background.width, background.height).apply {
            setRenderEffect(RenderEffect.createBlurEffect(2f, 2f, Shader.TileMode.CLAMP))
        }
    private val outlineBufferPaint = Paint().also { it.shader = rainConfig.outlineShader }

    init {
        updateTextureUniforms()
        adjustCropping(surfaceSize)
        prepareColorGrading()
        updateGridSize(surfaceSize)
        setIntensity(intensity)
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

    override fun release() {
        super.release()
        outlineBuffer.close()
    }

    override fun setIntensity(intensity: Float) {
        super.setIntensity(intensity)
        rainConfig.glassRainShader.setFloatUniform("intensity", intensity)
        val thickness = 1f + intensity * 10f
        rainConfig.outlineShader.setFloatUniform("thickness", thickness)

        // Need to recreate the outline buffer as the uniform has changed.
        createOutlineBuffer()
    }

    override fun setBitmaps(foreground: Bitmap?, background: Bitmap) {
        super.setBitmaps(foreground, background)
        outlineBuffer =
            FrameBuffer(background.width, background.height).apply {
                setRenderEffect(RenderEffect.createBlurEffect(2f, 2f, Shader.TileMode.CLAMP))
            }
        updateTextureUniforms()

        // Need to recreate the outline buffer as the outlineBuffer has changed due to background
        createOutlineBuffer()
    }

    override val shader: RuntimeShader
        get() = rainConfig.rainShowerShader

    override val colorGradingShader: RuntimeShader
        get() = rainConfig.colorGradingShader

    override val lut: Bitmap?
        get() = rainConfig.lut

    override val colorGradingIntensity: Float
        get() = rainConfig.colorGradingIntensity

    override fun adjustCropping(newSurfaceSize: SizeF) {
        super.adjustCropping(newSurfaceSize)
        rainConfig.glassRainShader.setFloatUniform(
            "screenSize",
            newSurfaceSize.width,
            newSurfaceSize.height,
        )

        val screenAspectRatio = GraphicsUtils.getAspectRatio(newSurfaceSize)
        rainConfig.glassRainShader.setFloatUniform("screenAspectRatio", screenAspectRatio)
    }

    override fun updateTextureUniforms() {
        val foregroundBuffer =
            BitmapShader(super.foreground, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
        rainConfig.rainShowerShader.setInputBuffer("foreground", foregroundBuffer)
        rainConfig.outlineShader.setInputBuffer("texture", foregroundBuffer)

        rainConfig.rainShowerShader.setInputBuffer(
            "background",
            BitmapShader(super.background, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR),
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

    override fun updateGridSize(newSurfaceSize: SizeF) {
        val widthScreenScale =
            GraphicsUtils.computeDefaultGridSize(newSurfaceSize, rainConfig.pixelDensity)
        rainConfig.rainShowerShader.setFloatUniform("gridScale", widthScreenScale)
        rainConfig.glassRainShader.setFloatUniform("gridScale", widthScreenScale)
    }
}
