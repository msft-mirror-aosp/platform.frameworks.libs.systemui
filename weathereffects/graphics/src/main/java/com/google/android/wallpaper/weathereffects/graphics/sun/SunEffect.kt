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

package com.google.android.wallpaper.weathereffects.graphics.sun

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.SizeF
import com.google.android.wallpaper.weathereffects.graphics.WeatherEffect
import com.google.android.wallpaper.weathereffects.graphics.utils.GraphicsUtils
import com.google.android.wallpaper.weathereffects.graphics.utils.MatrixUtils.centerCropMatrix
import com.google.android.wallpaper.weathereffects.graphics.utils.MatrixUtils.postprocessParallaxMatrix
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Defines and generates the sunny weather animation. */
class SunEffect(
    /** The config of the sunny effect. */
    private val sunConfig: SunEffectConfig,
    private var foreground: Bitmap,
    private var background: Bitmap,
    private var intensity: Float = WeatherEffect.DEFAULT_INTENSITY,
    /** The initial size of the surface where the effect will be shown. */
    var surfaceSize: SizeF,
) : WeatherEffect {

    private val sunnyPaint = Paint().also { it.shader = sunConfig.colorGradingShader }
    private var elapsedTime: Float = 0f

    private var matrix: Matrix? = null

    init {
        updateTextureUniforms()
        adjustCropping(surfaceSize)
        prepareColorGrading()
        setIntensity(intensity)
    }

    override fun resize(newSurfaceSize: SizeF) {
        adjustCropping(newSurfaceSize)
        surfaceSize = newSurfaceSize
    }

    override fun update(deltaMillis: Long, frameTimeNanos: Long) {
        elapsedTime += TimeUnit.MILLISECONDS.toSeconds(deltaMillis)
        sunConfig.shader.setFloatUniform("time", elapsedTime)
        sunConfig.colorGradingShader.setInputShader("texture", sunConfig.shader)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPaint(sunnyPaint)
    }

    override fun reset() {
        elapsedTime = Random.nextFloat() * 90f
    }

    override fun release() {
        sunConfig.lut?.recycle()
    }

    override fun setIntensity(intensity: Float) {
        sunConfig.shader.setFloatUniform("intensity", intensity)
        sunConfig.colorGradingShader.setFloatUniform(
            "intensity",
            sunConfig.colorGradingIntensity * intensity,
        )
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

        sunConfig.shader.setInputBuffer(
            "background",
            BitmapShader(this.background, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR),
        )
        sunConfig.shader.setInputBuffer(
            "foreground",
            BitmapShader(this.foreground, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR),
        )
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
        sunConfig.shader.setFloatUniform("transformMatrixFgd", postprocessedMatrix)
        sunConfig.shader.setFloatUniform("transformMatrixBgd", postprocessedMatrix)
        sunConfig.shader.setFloatUniform("screenSize", surfaceSize.width, surfaceSize.height)
        sunConfig.shader.setFloatUniform(
            "screenAspectRatio",
            GraphicsUtils.getAspectRatio(surfaceSize),
        )
    }

    override fun setMatrix(matrix: Matrix) {
        this.matrix = matrix
        adjustCropping(surfaceSize)
    }

    private fun updateTextureUniforms() {
        sunConfig.shader.setInputBuffer(
            "foreground",
            BitmapShader(foreground, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR),
        )

        sunConfig.shader.setInputBuffer(
            "background",
            BitmapShader(background, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR),
        )
    }

    private fun prepareColorGrading() {
        sunConfig.colorGradingShader.setInputShader("texture", sunConfig.shader)
        sunConfig.lut?.let {
            sunConfig.colorGradingShader.setInputShader(
                "lut",
                BitmapShader(it, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR),
            )
        }
        sunConfig.colorGradingShader.setFloatUniform("intensity", sunConfig.colorGradingIntensity)
    }

    private fun isForegroundLoaded(): Boolean {
        return foreground != background
    }
}
