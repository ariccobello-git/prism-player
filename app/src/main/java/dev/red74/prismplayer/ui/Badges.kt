package dev.red74.prismplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.red74.prismplayer.data.VariantTags

/** Language badges are blue, picture and sound badges amber, the rest (file type, group) grey. */
private val LanguageColor = Color(0xFFB9C8FF)
private val QualityColor = Color(0xFFFFD27A)

/**
 * The tags that tell copies of the same content apart ("ITA", "SUB ITA", "4K", "HEVC"): plain text on a dark
 * rounded box, nothing animated or blurred, so a grid full of them scrolls as smoothly as before.
 * [compact] is for posters, the larger size for rows and pages.
 */
@Composable
fun Badges(badges: List<String>, modifier: Modifier = Modifier, compact: Boolean = false, max: Int = 5) {
    if (badges.isEmpty()) return
    val shape = RoundedCornerShape(if (compact) 4.dp else 6.dp)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp)) {
        for (badge in badges.take(max)) {
            Box(
                Modifier
                    .background(Color(0xD9080C18), shape)
                    .padding(horizontal = if (compact) 4.dp else 7.dp, vertical = if (compact) 1.dp else 2.dp),
            ) {
                PrismText(
                    text = badge,
                    size = if (compact) 11.sp else 14.sp,
                    weight = FontWeight.Bold,
                    color = when {
                        VariantTags.isLanguage(badge) -> LanguageColor
                        VariantTags.isQuality(badge) -> QualityColor
                        else -> PrismColors.TextSecondary
                    },
                    maxLines = 1,
                )
            }
        }
    }
}
