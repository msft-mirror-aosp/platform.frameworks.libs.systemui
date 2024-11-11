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

package com.google.android.wallpaper.weathereffects.graphics

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.SizeF
import com.google.android.wallpaper.weathereffects.graphics.utils.GraphicsUtils
import com.google.android.wallpaper.weathereffects.graphics.utils.MatrixUtils.calculateTransformDifference
import com.google.android.wallpaper.weathereffects.graphics.utils.MatrixUtils.centerCropMatrix
import com.google.android.wallpaper.weathereffects.graphics.utils.MatrixUtils.invertAndTransposeMatrix
import kotlin.random.Random

/** Give default implementation of some functions in WeatherEffect */
abstract class WeatherEffectBase(
    protected var foreground: Bitmap,
    protected var background: Bitmap,
    /** The initial size of the surface where the effect will be shown. */
    private var surfaceSize: SizeF,
) : WeatherEffect {
    private var centerCropMatrix: Matrix =
        centerCropMatrix(
            surfaceSize,
            SizeF(background.width.toFloat(), background.height.toFloat()),
        )
    protected var parallaxMatrix = Matrix(centerCropMatrix)
    // Currently, we use same transform for both foreground and background
    protected open val transformMatrixBitmap: FloatArray = FloatArray(9)
    // Apply to weather components not rely on image textures
    // Should be identity matrix in editor, and only change when parallax applied in homescreen
    private val transformMatrixWeather: FloatArray = FloatArray(9)
    protected var elapsedTime: Float = 0f

    abstract val shader: RuntimeShader
    abstract val colorGradingShader: RuntimeShader
    abstract val lut: Bitmap?
    abstract val colorGradingIntensity: Float

    override fun setMatrix(matrix: Matrix) {
        this.parallaxMatrix.set(matrix)
        adjustCropping(surfaceSize)
    }

    open fun adjustCropping(newSurfaceSize: SizeF) {
        invertAndTransposeMatrix(parallaxMatrix, transformMatrixBitmap)
        calculateTransformDifference(centerCropMatrix, parallaxMatrix, transformMatrixWeather)
        shader.setFloatUniform("transformMatrixBitmap", transformMatrixBitmap)
        shader.setFloatUniform("transformMatrixWeather", transformMatrixWeather)
        shader.setFloatUniform("screenSize", newSurfaceSize.width, newSurfaceSize.height)
        shader.setFloatUniform("screenAspectRatio", GraphicsUtils.getAspectRatio(newSurfaceSize))
    }

    open fun updateGridSize(newSurfaceSize: SizeF) {}

    override fun resize(newSurfaceSize: SizeF) {
        surfaceSize = newSurfaceSize
        adjustCropping(newSurfaceSize)
        updateGridSize(newSurfaceSize)
    }

    abstract override fun update(deltaMillis: Long, frameTimeNanos: Long)

    abstract override fun draw(canvas: Canvas)

    override fun reset() {
        elapsedTime = Random.nextFloat() * 90f
    }

    override fun release() {
        lut?.recycle()
    }

    override fun setIntensity(intensity: Float) {
        shader.setFloatUniform("intensity", intensity)
        colorGradingShader.setFloatUniform("intensity", colorGradingIntensity * intensity)
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
        this.foreground = foreground ?: background
        this.background = background

        centerCropMatrix =
            centerCropMatrix(
                surfaceSize,
                SizeF(background.width.toFloat(), background.height.toFloat()),
            )
        parallaxMatrix.set(centerCropMatrix)
        shader.setInputBuffer(
            "background",
            BitmapShader(this.background, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR),
        )
        shader.setInputBuffer(
            "foreground",
            BitmapShader(this.foreground, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR),
        )
        adjustCropping(surfaceSize)
    }

    open fun updateTextureUniforms() {
        shader.setInputBuffer(
            "foreground",
            BitmapShader(foreground, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR),
        )

        shader.setInputBuffer(
            "background",
            BitmapShader(background, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR),
        )
    }
}
