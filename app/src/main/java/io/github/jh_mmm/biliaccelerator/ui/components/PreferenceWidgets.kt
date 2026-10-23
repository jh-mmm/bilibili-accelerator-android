package io.github.jh_mmm.biliaccelerator.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import io.github.jh_mmm.biliaccelerator.ui.theme.LocalUiStyle
import io.github.jh_mmm.biliaccelerator.ui.theme.UiStyle
import io.github.jh_mmm.biliaccelerator.ui.theme.isAcceleratorDarkTheme
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun PreferenceGroupTitle(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp)
    )
}

@Composable
fun PreferenceGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val uiStyle = LocalUiStyle.current
    val cornerRadius = if (uiStyle == UiStyle.MIUIX) 18.dp else 12.dp

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        content = content
    )
}

@Composable
fun PreferenceSwitchItem(
    title: String,
    summary: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(24.dp)
                    .padding(end = 4.dp)
            )
            Spacer(Modifier.width(12.dp))
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
            if (!summary.isNullOrEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.8f else 0.4f),
                    lineHeight = 16.sp
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
fun PreferenceDropdownItem(
    title: String,
    selectedValue: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
            if (!summary.isNullOrEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    fontSize = 12.sp,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    lineHeight = 16.sp
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        Text(
            text = selectedValue,
            fontSize = 13.sp,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
            fontWeight = FontWeight.SemiBold
        )

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun PreferenceActionItem(
    title: String,
    summary: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tintColor: Color? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tintColor ?: MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = tintColor ?: MaterialTheme.colorScheme.onSurface
            )
            if (!summary.isNullOrEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun MiuixSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    height: Dp = 28.dp,
    showKeyPoints: Boolean = false,
    keyPoints: List<Float>? = null,
    magnetThreshold: Float = 0.035f,
) {
    require(steps >= 0) { "steps should be >= 0" }
    require(valueRange.start < valueRange.endInclusive) { "valueRange start should be less than end" }

    val view = LocalView.current
    val isDark = isAcceleratorDarkTheme()
    val onValueChangeState by rememberUpdatedState(onValueChange)
    val onValueChangeFinishedState by rememberUpdatedState(onValueChangeFinished)

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    var isDragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var layoutWidth by remember { mutableIntStateOf(0) }
    var layoutHeight by remember { mutableIntStateOf(0) }

    val coercedValue = value.coerceIn(valueRange.start, valueRange.endInclusive)

    // Spring physics: dampening 0.90f / stiffness 1755f during drag for 120Hz tracking, dampening 0.96f / stiffness 322f on release
    val progressSpring = remember(isDragging) {
        if (isDragging) {
            spring(dampingRatio = 0.90f, stiffness = 1755f)
        } else {
            spring(dampingRatio = 0.96f, stiffness = 322f)
        }
    }
    val animatedProgressValue by animateFloatAsState(
        targetValue = coercedValue,
        animationSpec = progressSpring,
        label = "MiuixSliderProgress"
    )

    // Thumb bounce scale animation (HyperOS / MIUI elastic touch feedback)
    val isActive = isPressed || isDragging
    val thumbScale by animateFloatAsState(
        targetValue = if (isActive) 1.15f else 1.0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 987f),
        label = "MiuixSliderThumbScale"
    )

    // Subtle track background darkening on press/drag
    val backgroundOverlayAlpha by animateFloatAsState(
        targetValue = if (isActive) 0.045f else 0f,
        animationSpec = tween(150),
        label = "MiuixSliderOverlayAlpha"
    )

    // Discrete step points and custom key points
    val stepFractions = remember(steps) {
        if (steps == 0) floatArrayOf() else FloatArray(steps + 2) { it.toFloat() / (steps + 1) }
    }

    val keyPointFractions = remember(keyPoints, stepFractions, valueRange, showKeyPoints) {
        when {
            keyPoints != null -> keyPoints.map {
                ((it - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
            }.toFloatArray()
            showKeyPoints -> stepFractions
            else -> floatArrayOf()
        }
    }

    // Function to calculate and magnetically snap values
    val resolveValue: (Float) -> Float = remember(valueRange, steps, keyPoints, magnetThreshold) {
        { fraction: Float ->
            val clampedFraction = fraction.coerceIn(0f, 1f)
            when {
                steps > 0 -> {
                    val stepCount = steps + 1
                    val stepIndex = (clampedFraction * stepCount).roundToInt().coerceIn(0, stepCount)
                    valueRange.start + (valueRange.endInclusive - valueRange.start) * stepIndex / stepCount
                }
                keyPoints != null && keyPoints.isNotEmpty() -> {
                    val rangeSpan = valueRange.endInclusive - valueRange.start
                    var closestPoint = keyPoints[0]
                    var minDistance = Float.MAX_VALUE
                    for (kp in keyPoints) {
                        val kpFraction = (kp - valueRange.start) / rangeSpan
                        val dist = abs(kpFraction - clampedFraction)
                        if (dist < minDistance) {
                            minDistance = dist
                            closestPoint = kp
                        }
                    }
                    if (minDistance <= magnetThreshold) {
                        closestPoint
                    } else {
                        lerp(valueRange.start, valueRange.endInclusive, clampedFraction)
                    }
                }
                else -> {
                    lerp(valueRange.start, valueRange.endInclusive, clampedFraction)
                }
            }
        }
    }

    // Native HyperOS / Android linear motor haptics
    var lastHapticValue by remember { mutableFloatStateOf(coercedValue) }
    var edgeFeedbackFired by remember { mutableStateOf(false) }

    fun triggerHapticFeedback(newValue: Float) {
        val isAtStart = newValue <= valueRange.start
        val isAtEnd = newValue >= valueRange.endInclusive
        val isAtBoundary = isAtStart || isAtEnd

        if (isAtBoundary) {
            if (!edgeFeedbackFired) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    view.performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
                } else {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
                edgeFeedbackFired = true
            }
        } else {
            edgeFeedbackFired = false
            if (newValue != lastHapticValue) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
        }
        lastHapticValue = newValue
    }

    // Theming colors
    val activeColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
    val trackBgColor = if (isDark) {
        if (enabled) Color(0xFF2C2C2E) else Color(0xFF222224)
    } else {
        if (enabled) Color(0xFFE5E5EA) else Color(0xFFEBEBEF)
    }
    val thumbColor = if (enabled) Color.White else Color.White.copy(alpha = 0.6f)
    val keyPointColor = if (isDark) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.15f)
    val keyPointForegroundColor = Color.White.copy(alpha = 0.65f)

    Box(
        modifier = modifier
            .height(height)
            .fillMaxWidth()
            .onSizeChanged {
                layoutWidth = it.width
                layoutHeight = it.height
            }
            .then(
                if (enabled) {
                    Modifier
                        .pointerInput(valueRange, steps, keyPoints) {
                            detectTapGestures { offset ->
                                val thumbRadius = layoutHeight / 2f
                                val availableWidth = (layoutWidth - 2f * thumbRadius).coerceAtLeast(1f)
                                val fraction = ((offset.x - thumbRadius) / availableWidth).coerceIn(0f, 1f)
                                val newValue = resolveValue(fraction)
                                onValueChangeState(newValue)
                                triggerHapticFeedback(newValue)
                                onValueChangeFinishedState?.invoke()
                            }
                        }
                        .draggable(
                            orientation = Orientation.Horizontal,
                            interactionSource = interactionSource,
                            state = rememberDraggableState { delta ->
                                dragOffset += delta
                                val thumbRadius = layoutHeight / 2f
                                val availableWidth = (layoutWidth - 2f * thumbRadius).coerceAtLeast(1f)
                                val fraction = ((dragOffset - thumbRadius) / availableWidth).coerceIn(0f, 1f)
                                val newValue = resolveValue(fraction)
                                onValueChangeState(newValue)
                                triggerHapticFeedback(newValue)
                            },
                            onDragStarted = { offset ->
                                isDragging = true
                                dragOffset = offset.x
                                val thumbRadius = layoutHeight / 2f
                                val availableWidth = (layoutWidth - 2f * thumbRadius).coerceAtLeast(1f)
                                val fraction = ((offset.x - thumbRadius) / availableWidth).coerceIn(0f, 1f)
                                val newValue = resolveValue(fraction)
                                onValueChangeState(newValue)
                                triggerHapticFeedback(newValue)
                            },
                            onDragStopped = {
                                isDragging = false
                                onValueChangeFinishedState?.invoke()
                            }
                        )
                } else {
                    Modifier
                }
            )
            .clip(CircleShape)
            .drawBehind {
                drawRect(trackBgColor)
                if (backgroundOverlayAlpha > 0f) {
                    drawRect(Color.Black, alpha = backgroundOverlayAlpha)
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            val barWidth = size.width
            val barHeight = size.height
            val thumbRadius = barHeight / 2f
            val availableWidth = (barWidth - 2f * thumbRadius).coerceAtLeast(0f)
            val rangeSpan = valueRange.endInclusive - valueRange.start
            val fraction = if (rangeSpan > 0f) {
                ((animatedProgressValue - valueRange.start) / rangeSpan).coerceIn(0f, 1f)
            } else 0f

            val centerX = thumbRadius + fraction * availableWidth
            val centerY = barHeight / 2f
            val knobRadius = thumbRadius * 0.72f * thumbScale

            // 1. Draw Active Progress Fill
            drawLine(
                color = activeColor,
                start = Offset(0f, centerY),
                end = Offset(centerX, centerY),
                strokeWidth = barHeight,
                cap = StrokeCap.Round
            )

            // 2. Draw Key Point Indicator Dots
            if (showKeyPoints && keyPointFractions.isNotEmpty()) {
                val keyPointRadius = barHeight / 7.5f
                for (stepFraction in keyPointFractions) {
                    val kpX = thumbRadius + stepFraction * availableWidth
                    val isSelected = kpX <= centerX
                    val kpColor = if (isSelected) keyPointForegroundColor else keyPointColor
                    drawCircle(
                        color = kpColor,
                        radius = keyPointRadius,
                        center = Offset(kpX, centerY)
                    )
                }
            }

            // 3. Draw Inner Thumb Knob with Ambient Drop Shadow
            if (enabled) {
                drawCircle(
                    color = Color.Black.copy(alpha = 0.16f),
                    radius = knobRadius + 1.2.dp.toPx(),
                    center = Offset(centerX, centerY + 0.8.dp.toPx())
                )
                drawCircle(
                    color = Color.Black.copy(alpha = 0.05f),
                    radius = knobRadius + 0.5.dp.toPx(),
                    center = Offset(centerX, centerY)
                )
            }
            drawCircle(
                color = thumbColor,
                radius = knobRadius,
                center = Offset(centerX, centerY)
            )
        }
    }
}

@Composable
fun PreferenceSliderItem(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    valueLabel: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    showKeyPoints: Boolean = false,
    keyPoints: List<Float>? = null,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
                if (!summary.isNullOrEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = summary,
                        fontSize = 12.sp,
                        color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            Text(
                text = valueLabel,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            )
        }

        Spacer(Modifier.height(10.dp))

        MiuixSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            showKeyPoints = showKeyPoints,
            keyPoints = keyPoints,
            onValueChangeFinished = onValueChangeFinished,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun PreferenceDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 0.6.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    )
}
