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

package com.android.systemui.monet;

import android.util.Pair;

import com.google.ux.material.libmonet.dynamiccolor.DynamicColor;
import com.google.ux.material.libmonet.dynamiccolor.MaterialDynamicColors;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

public class DynamicColors {

    /**
     * List of all public Dynamic Color (Light and Dark) resources
     *
     * @param isExtendedFidelity boolean indicating if Fidelity is active
     * @return List of pairs of Resource Names / DynamicColor
     */
    public static List<Pair<String, DynamicColor>> getAllDynamicColorsMapped(
            boolean isExtendedFidelity) {
        MaterialDynamicColors mdc = new MaterialDynamicColors(isExtendedFidelity);
        final Supplier<DynamicColor>[] allColors = new Supplier[]{
                mdc::primaryPaletteKeyColor,
                mdc::secondaryPaletteKeyColor,
                mdc::tertiaryPaletteKeyColor,
                mdc::neutralPaletteKeyColor,
                mdc::neutralVariantPaletteKeyColor,
                mdc::background,
                mdc::onBackground,
                mdc::surface,
                mdc::surfaceDim,
                mdc::surfaceBright,
                mdc::surfaceContainerLowest,
                mdc::surfaceContainerLow,
                mdc::surfaceContainer,
                mdc::surfaceContainerHigh,
                mdc::surfaceContainerHighest,
                mdc::onSurface,
                mdc::surfaceVariant,
                mdc::onSurfaceVariant,
                mdc::inverseSurface,
                mdc::inverseOnSurface,
                mdc::outline,
                mdc::outlineVariant,
                mdc::shadow,
                mdc::scrim,
                mdc::surfaceTint,
                mdc::primary,
                mdc::onPrimary,
                mdc::primaryContainer,
                mdc::onPrimaryContainer,
                mdc::inversePrimary,
                mdc::secondary,
                mdc::onSecondary,
                mdc::secondaryContainer,
                mdc::onSecondaryContainer,
                mdc::tertiary,
                mdc::onTertiary,
                mdc::tertiaryContainer,
                mdc::onTertiaryContainer,
                mdc::error,
                mdc::onError,
                mdc::errorContainer,
                mdc::onErrorContainer,
                mdc::controlActivated,
                mdc::controlNormal,
                mdc::controlHighlight,
                mdc::textPrimaryInverse,
                mdc::textSecondaryAndTertiaryInverse,
                mdc::textPrimaryInverseDisableOnly,
                mdc::textSecondaryAndTertiaryInverseDisabled,
                mdc::textHintInverse
        };

        List<Pair<String, DynamicColor>> list = generateSysUINames(allColors);
        return list;
    }

    /**
     * List of all public Static Color resources
     *
     * @param isExtendedFidelity boolean indicating if Fidelity is active
     * @return List of pairs of Resource Names / DynamicColor @return
     */
    public static List<Pair<String, DynamicColor>> getFixedColorsMapped(
            boolean isExtendedFidelity) {
        MaterialDynamicColors mdc = new MaterialDynamicColors(isExtendedFidelity);

        final Supplier<DynamicColor>[] allColors = new Supplier[]{
                mdc::primaryFixed,
                mdc::primaryFixedDim,
                mdc::onPrimaryFixed,
                mdc::onPrimaryFixedVariant,
                mdc::secondaryFixed,
                mdc::secondaryFixedDim,
                mdc::onSecondaryFixed,
                mdc::onSecondaryFixedVariant,
                mdc::tertiaryFixed,
                mdc::tertiaryFixedDim,
                mdc::onTertiaryFixed,
                mdc::onTertiaryFixedVariant
        };

        List<Pair<String, DynamicColor>> list = generateSysUINames(allColors);
        return list;
    }


    /**
     * List of all private SystemUI Color resources
     *
     * @param isExtendedFidelity boolean indicating if Fidelity is active
     * @return List of pairs of Resource Names / DynamicColor
     */
    public static List<Pair<String, DynamicColor>> getCustomColorsMapped(
            boolean isExtendedFidelity) {
        CustomDynamicColors customMdc = new CustomDynamicColors(isExtendedFidelity);
        List<Pair<String, DynamicColor>> list = generateSysUINames(customMdc.allColors);
        return list;
    }

    private static List<Pair<String, DynamicColor>> generateSysUINames(
            Supplier<DynamicColor>[] allColors) {
        List<Pair<String, DynamicColor>> list = new ArrayList<>();

        for (Supplier<DynamicColor> supplier : allColors) {
            DynamicColor dynamicColor = supplier.get();
            String name = dynamicColor.name;

            // Fix tokens containing `palette_key_color` for SysUI requirements:
            // In SysUI palette_key_color should come first in the token name;
            String paletteMark = "palette_key_color";
            if (name.contains("_" + paletteMark)) {
                name = paletteMark + "_" + name.replace("_" + paletteMark, "");
            }

            list.add(new Pair(name, dynamicColor));
        }

        list.sort(Comparator.comparing(pair -> pair.first));
        return list;
    }
}

