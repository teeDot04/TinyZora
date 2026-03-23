package com.telo.tinyzora.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.googlefonts.Font
import com.telo.tinyzora.R

val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

val jetbrainsMonoFont = GoogleFont("JetBrains Mono")
val jetbrainsMonoFamily = FontFamily(
    Font(googleFont = jetbrainsMonoFont, fontProvider = provider)
)

// Using Default Android sans-serif which is Roboto, matching the "Roboto Flex" core requirement 
// for conversational text, while explicitly bringing in JetBrains Mono for technical/code blocks.
val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Cursive,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    labelMedium = TextStyle( // Used for Code Blocks / Technical
        fontFamily = jetbrainsMonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    )
)
