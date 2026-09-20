package dev.wfy.app

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

val InterfaceFont = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold)
)
val CodeFont = FontFamily(Font(R.font.jetbrains_mono_regular))
private val baseTypography = Typography()
val OutpostTypography = with(baseTypography) { copy(
    displayLarge = displayLarge.copy(fontFamily = InterfaceFont), displayMedium = displayMedium.copy(fontFamily = InterfaceFont), displaySmall = displaySmall.copy(fontFamily = InterfaceFont),
    headlineLarge = headlineLarge.copy(fontFamily = InterfaceFont), headlineMedium = headlineMedium.copy(fontFamily = InterfaceFont), headlineSmall = headlineSmall.copy(fontFamily = InterfaceFont),
    titleLarge = titleLarge.copy(fontFamily = InterfaceFont), titleMedium = titleMedium.copy(fontFamily = InterfaceFont), titleSmall = titleSmall.copy(fontFamily = InterfaceFont),
    bodyLarge = bodyLarge.copy(fontFamily = InterfaceFont), bodyMedium = bodyMedium.copy(fontFamily = InterfaceFont), bodySmall = bodySmall.copy(fontFamily = InterfaceFont),
    labelLarge = labelLarge.copy(fontFamily = InterfaceFont), labelMedium = labelMedium.copy(fontFamily = InterfaceFont), labelSmall = labelSmall.copy(fontFamily = InterfaceFont)
) }
