package com.futura.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.futura.ui.theme.AlarmGreen
import java.time.LocalTime
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Green analog clock face in the alarm style. Hands follow [time]. */
@Composable
fun AnalogClockFace(time: LocalTime, modifier: Modifier = Modifier) {
    val hour = (time.hour % 12).toFloat()
    val minute = time.minute.toFloat()
    val second = time.second.toFloat()

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = min(cx, cy) * 0.9f

        fun polar(degrees: Float, length: Float): Offset {
            val rad = Math.toRadians((degrees - 90f).toDouble())
            return Offset(cx + length * cos(rad).toFloat(), cy + length * sin(rad).toFloat())
        }

        drawCircle(
            color = AlarmGreen,
            radius = r,
            center = Offset(cx, cy),
            style = Stroke(width = 4f, cap = StrokeCap.Round)
        )
        for (i in 0..11) {
            drawLine(AlarmGreen, polar(i * 30f, r * 0.8f), polar(i * 30f, r * 0.9f), strokeWidth = 4f)
        }
        drawLine(AlarmGreen, Offset(cx, cy), polar(hour * 30f + minute * 0.5f, r * 0.5f), strokeWidth = 8f)
        drawLine(AlarmGreen, Offset(cx, cy), polar(minute * 6f, r * 0.7f), strokeWidth = 5f)
        drawLine(AlarmGreen, Offset(cx, cy), polar(second * 6f, r * 0.85f), strokeWidth = 2f)
        drawCircle(color = AlarmGreen, radius = 12f, center = Offset(cx, cy))
    }
}
