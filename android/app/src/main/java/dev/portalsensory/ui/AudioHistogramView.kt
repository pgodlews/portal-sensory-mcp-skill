package dev.portalsensory.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AudioHistogramView(
    frequencyBands: FloatArray,
    peakDb: Float,
    amplitude: Float,
    modifier: Modifier = Modifier
) {
    val bandLabels = listOf("60", "250", "500", "1k", "2k", "4k", "6k", "8k+")

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xCC0A0E17))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // Top row: Label and dB level
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "AUDIO SPECTRUM & SENSITIVITY",
                color = Color(0xFF00E5FF),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )

            val dbColor = when {
                peakDb > -3f -> Color(0xFFFF5252) // Red clipping
                peakDb > -12f -> Color(0xFFFFD740) // Amber hot
                else -> Color(0xFF00E676) // Green optimal
            }

            Text(
                text = String.format("%+5.1f dB", peakDb),
                color = dbColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Frequency Bars
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            for (i in frequencyBands.indices) {
                val bandValue = frequencyBands.getOrElse(i) { 0f }
                val animatedHeight by animateFloatAsState(
                    targetValue = bandValue.coerceIn(0.05f, 1f),
                    animationSpec = tween(durationMillis = 60),
                    label = "band_$i"
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(animatedHeight)
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0xFF00E5FF),
                                        Color(0xFF00B0FF),
                                        Color(0xFF1DE9B6)
                                    )
                                )
                            )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Band Hz labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (label in bandLabels) {
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    color = Color(0xFF90A4AE),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Peak Meter Bar
        val meterNorm = ((peakDb + 60f) / 60f).coerceIn(0.02f, 1f)
        val animatedMeter by animateFloatAsState(
            targetValue = meterNorm,
            animationSpec = tween(durationMillis = 50),
            label = "meter"
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFF1E293B))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedMeter)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF00E676),
                                Color(0xFF76FF03),
                                Color(0xFFFFD740),
                                Color(0xFFFF5252)
                            )
                        )
                    )
            )
        }
    }
}
