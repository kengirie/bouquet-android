package io.github.kengirie.bouquet.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val BouquetColorScheme = darkColorScheme(
    background = BouquetBackground,
    surface = BouquetSurface,
    surfaceVariant = BouquetSurfaceVariant,
    surfaceContainer = BouquetSurfaceContainer,
    surfaceContainerLow = BouquetSurfaceContainerLow,
    surfaceContainerLowest = BouquetSurfaceContainerLowest,
    primary = BouquetPrimary,
    onPrimary = BouquetOnPrimary,
    primaryContainer = BouquetPrimaryContainer,
    onPrimaryContainer = BouquetOnPrimaryContainer,
    secondary = BouquetSecondary,
    tertiary = BouquetTertiary,
    error = BouquetError,
    errorContainer = BouquetErrorContainer,
    onErrorContainer = BouquetOnErrorContainer,
    onBackground = BouquetOnBackground,
    onSurface = BouquetOnSurface,
    onSurfaceVariant = BouquetOnSurfaceVariant,
    outline = BouquetOutline,
    outlineVariant = BouquetOutlineVariant,
)

@Composable
fun BouquetTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = BouquetColorScheme,
        typography = BouquetTypography,
        content = content,
    )
}
