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

package com.google.android.wallpaper.weathereffects.graphics.utils

import android.graphics.Matrix
import android.util.SizeF

/** Helper functions for matrix operations. */
object MatrixUtils {
    private val inverseMatrix: Matrix = Matrix()
    private val matrixValues = FloatArray(9)
    private val transposedValues = FloatArray(9)
    private val translationOnlyMatrixValues = FloatArray(9)

    /** Returns a [Matrix] that crops the image and centers to the screen. */
    fun centerCropMatrix(surfaceSize: SizeF, imageSize: SizeF): Matrix {
        val widthScale = surfaceSize.width / imageSize.width
        val heightScale = surfaceSize.height / imageSize.height
        val scale = maxOf(widthScale, heightScale)

        return Matrix(Matrix.IDENTITY_MATRIX).apply {
            // Move the origin of the image to its center.
            postTranslate(-imageSize.width / 2f, -imageSize.height / 2f)
            // Apply scale.
            postScale(scale, scale)
            // Translate back to the center of the screen.
            postTranslate(surfaceSize.width / 2f, surfaceSize.height / 2f)
        }
    }

    // To apply parallax matrix to fragCoord, we need to invert and transpose the matrix
    fun postprocessParallaxMatrix(matrix: Matrix): FloatArray {
        matrix.invert(inverseMatrix)
        inverseMatrix.getValues(matrixValues)
        return transposeMatrixArray(matrixValues)
    }

    // Transpose 3x3 matrix values as a FloatArray[9]
    private fun transposeMatrixArray(matrix: FloatArray): FloatArray {
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                transposedValues[j * 3 + i] = matrix[i * 3 + j]
            }
        }
        return transposedValues
    }

    // Extract translation elements from the transposed matrix returned by transposeMatrixArray
    fun extractTranslationMatrix(matrix: FloatArray): FloatArray {
        Matrix.IDENTITY_MATRIX.getValues(translationOnlyMatrixValues)
        return translationOnlyMatrixValues.apply {
            set(6, matrix[6])
            set(7, matrix[7])
        }
    }
}
