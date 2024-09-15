/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.systemui.monet

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.systemui.monet.ColorScheme.GOOGLE_BLUE
import com.google.ux.material.libmonet.hct.Hct
import com.google.ux.material.libmonet.scheme.SchemeTonalSpot
import java.io.File
import java.io.FileWriter
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerException
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.math.abs
import org.junit.Test
import org.junit.runner.RunWith
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

private const val fileHeader =
    """
  ~ Copyright (C) 2022 The Android Open Source Project
  ~
  ~ Licensed under the Apache License, Version 2.0 (the "License");
  ~ you may not use this file except in compliance with the License.
  ~ You may obtain a copy of the License at
  ~
  ~      http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing, software
  ~ distributed under the License is distributed on an "AS IS" BASIS,
  ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  ~ See the License for the specific language governing permissions and
  ~ limitations under the License.
"""

private fun testName(name: String): String {
    return "Auto generated by: atest ColorSchemeTest#$name"
}

private const val commentRoles =
    "Colors used in Android system, from design system. These " +
        "values can be overlaid at runtime by OverlayManager RROs."

private const val commentOverlay = "This value can be overlaid at runtime by OverlayManager RROs."

private fun commentWhite(paletteName: String): String {
    return "Lightest shade of the $paletteName color used by the system. White. $commentOverlay"
}

private fun commentBlack(paletteName: String): String {
    return "Darkest shade of the $paletteName color used by the system. Black. $commentOverlay"
}

private fun commentShade(paletteName: String, tone: Int): String {
    return "Shade of the $paletteName system color at $tone% perceptual luminance (L* in L*a*b* " +
        "color space). $commentOverlay"
}

@SmallTest
@RunWith(AndroidJUnit4::class)
class ColorSchemeTest {
    @Test
    fun generateThemeStyles() {
        val document = buildDoc<Any>()

        val themes = document.createElement("themes")
        document.appendWithBreak(themes)

        var hue = 0.0
        while (hue < 360) {
            val sourceColor = Hct.from(hue, 50.0, 50.0)
            val sourceColorHex = sourceColor.toInt().toRGBHex()

            val theme = document.createElement("theme")
            theme.setAttribute("color", sourceColorHex)
            themes.appendChild(theme)

            for (styleValue in Style.entries) {
                if (
                    styleValue == Style.CLOCK ||
                        styleValue == Style.CLOCK_VIBRANT ||
                        styleValue == Style.CONTENT
                ) {
                    continue
                }

                val style = document.createElement(styleValue.name.lowercase())
                val colorScheme = ColorScheme(sourceColor.toInt(), false, styleValue)

                style.appendChild(
                    document.createTextNode(
                        listOf(
                                colorScheme.accent1,
                                colorScheme.accent2,
                                colorScheme.accent3,
                                colorScheme.neutral1,
                                colorScheme.neutral2
                            )
                            .flatMap { a -> listOf(*a.allShades.toTypedArray()) }
                            .joinToString(",", transform = Int::toRGBHex)
                    )
                )
                theme.appendChild(style)
            }

            hue += 60
        }

        saveFile(document, "current_themes.xml")
    }

    @Test
    fun generateDefaultValues() {
        val document = buildDoc<Any>()

        val resources = document.createElement("resources")
        document.appendWithBreak(resources)

        // shade colors
        val colorScheme = ColorScheme(GOOGLE_BLUE, false)
        arrayOf(
                Triple("accent1", "Primary", colorScheme.accent1),
                Triple("accent2", "Secondary", colorScheme.accent2),
                Triple("accent3", "Tertiary", colorScheme.accent3),
                Triple("neutral1", "Neutral", colorScheme.neutral1),
                Triple("neutral2", "Secondary Neutral", colorScheme.neutral2)
            )
            .forEach {
                val (paletteName, readable, palette) = it
                palette.allShadesMapped.entries.forEachIndexed { index, (shade, colorValue) ->
                    val comment =
                        when (index) {
                            0 -> commentWhite(readable)
                            palette.allShadesMapped.entries.size - 1 -> commentBlack(readable)
                            else -> commentShade(readable, abs(shade / 10 - 100))
                        }
                    resources.createColorEntry("system_${paletteName}_$shade", colorValue, comment)
                }
            }

        resources.appendWithBreak(document.createComment(commentRoles), 2)

        // dynamic colors
        arrayOf(false, true).forEach { isDark ->
            val suffix = if (isDark) "_dark" else "_light"
            val dynamicScheme = SchemeTonalSpot(Hct.fromInt(GOOGLE_BLUE), isDark, 0.5)
            DynamicColors.getAllDynamicColorsMapped(false).forEach {
                resources.createColorEntry(
                    "system_${it.first}$suffix",
                    it.second.getArgb(dynamicScheme)
                )
            }
        }

        // fixed colors
        val dynamicScheme = SchemeTonalSpot(Hct.fromInt(GOOGLE_BLUE), false, 0.5)
        DynamicColors.getFixedColorsMapped(false).forEach {
            resources.createColorEntry("system_${it.first}", it.second.getArgb(dynamicScheme))
        }

        saveFile(document, "role_values.xml")
    }

    // Helper Functions

    private inline fun <reified T> buildDoc(): Document {
        val functionName = T::class.simpleName + ""
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val document = builder.newDocument()

        document.appendWithBreak(document.createComment(fileHeader))
        document.appendWithBreak(document.createComment(testName(functionName)))

        return document
    }

    private fun documentToString(document: Document): String {
        try {
            val transformerFactory = TransformerFactory.newInstance()
            val transformer = transformerFactory.newTransformer()
            transformer.setOutputProperty(OutputKeys.MEDIA_TYPE, "application/xml")
            transformer.setOutputProperty(OutputKeys.METHOD, "xml")
            transformer.setOutputProperty(OutputKeys.INDENT, "yes")
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")

            val stringWriter = StringWriter()
            transformer.transform(DOMSource(document), StreamResult(stringWriter))
            return stringWriter.toString()
        } catch (e: TransformerException) {
            throw RuntimeException("Error transforming XML", e)
        }
    }

    private fun saveFile(document: Document, fileName: String) {
        val context = InstrumentationRegistry.getInstrumentation().context
        val outPath = context.filesDir.path + "/" + fileName
        Log.d("ColorSchemeXml", "Artifact $fileName created")
        val writer = FileWriter(File(outPath))
        writer.write(documentToString(document))
        writer.close()
    }
}

private fun Element.createColorEntry(name: String, value: Int, comment: String? = null) {
    val doc = this.ownerDocument

    if (comment != null) {
        this.appendChild(doc.createComment(comment))
    }

    val color = doc.createElement("color")
    this.appendChild(color)

    color.setAttribute("name", name)
    color.appendChild(doc.createTextNode("#" + value.toRGBHex()))
}

private fun Node.appendWithBreak(child: Node, lineBreaks: Int = 1): Node {
    val doc = if (this is Document) this else this.ownerDocument
    val node = doc.createTextNode("\n".repeat(lineBreaks))
    this.appendChild(node)
    return this.appendChild(child)
}

private fun Int.toRGBHex(): String {
    return "%06X".format(0xFFFFFF and this)
}
