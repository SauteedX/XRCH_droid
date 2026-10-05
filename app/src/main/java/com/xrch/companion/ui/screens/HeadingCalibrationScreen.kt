package com.xrch.companion.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CompassCalibration
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xrch.companion.bridge.QuestLocationBridge
import com.xrch.companion.ui.theme.IndigoPrimary
import com.xrch.companion.ui.theme.StatusGreen
import com.xrch.companion.ui.theme.StatusRed

import androidx.compose.material.icons.automirrored.filled.ArrowBack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeadingCalibrationScreen(
    bridge: QuestLocationBridge,
    onNavigateBack: (() -> Unit)? = null
) {
    val bridgeState by bridge.state.collectAsState()

    val animatedRotation by animateFloatAsState(
        targetValue = if (bridgeState.headingDegrees >= 0) -bridgeState.headingDegrees.toFloat() else 0f,
        label = "compassRotation"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("헤딩 보정", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로가기")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(Modifier.height(10.dp))

            // Compass Visualizer
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .background(IndigoPrimary.copy(alpha = 0.05f), shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                // Compass Rose Canvas
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .rotate(animatedRotation)
                ) {
                    val center = Offset(size.width / 2, size.height / 2)
                    val radius = size.minDimension / 2 - 12.dp.toPx()

                    // Outer dial ring
                    drawCircle(
                        color = Color(0xFFCBD5E1),
                        radius = radius,
                        center = center,
                        style = Stroke(width = 3.dp.toPx())
                    )

                    // Dial ticks
                    for (i in 0 until 360 step 30) {
                        val angleRad = Math.toRadians(i.toDouble() - 90)
                        val innerR = if (i % 90 == 0) radius - 16.dp.toPx() else radius - 8.dp.toPx()
                        val start = Offset(
                            (center.x + innerR * Math.cos(angleRad)).toFloat(),
                            (center.y + innerR * Math.sin(angleRad)).toFloat()
                        )
                        val end = Offset(
                            (center.x + radius * Math.cos(angleRad)).toFloat(),
                            (center.y + radius * Math.sin(angleRad)).toFloat()
                        )
                        drawLine(
                            color = if (i == 0) StatusRed else Color(0xFF94A3B8),
                            start = start,
                            end = end,
                            strokeWidth = if (i % 90 == 0) 3.dp.toPx() else 1.5.dp.toPx()
                        )
                    }

                    // North Needle
                    val needlePathNorth = Path().apply {
                        moveTo(center.x, center.y - radius + 10.dp.toPx())
                        lineTo(center.x - 12.dp.toPx(), center.y)
                        lineTo(center.x + 12.dp.toPx(), center.y)
                        close()
                    }
                    drawPath(needlePathNorth, color = StatusRed)

                    // South Needle
                    val needlePathSouth = Path().apply {
                        moveTo(center.x, center.y + radius - 10.dp.toPx())
                        lineTo(center.x - 12.dp.toPx(), center.y)
                        lineTo(center.x + 12.dp.toPx(), center.y)
                        close()
                    }
                    drawPath(needlePathSouth, color = Color(0xFF94A3B8))

                    // Center pivot
                    drawCircle(color = Color.White, radius = 8.dp.toPx(), center = center)
                    drawCircle(color = Color(0xFF1E293B), radius = 5.dp.toPx(), center = center)
                }

                // Center Compass Icon Overlay if no heading
                if (bridgeState.headingDegrees < 0) {
                    Icon(
                        Icons.Default.Explore,
                        contentDescription = null,
                        tint = IndigoPrimary,
                        modifier = Modifier.size(54.dp)
                    )
                }
            }

            // Heading Details
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = bridgeState.headingText,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "정확도  ${bridgeState.headingAccuracyText}",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Description
            Text(
                text = if (bridgeState.headingCalibrationModeActive) {
                    "스마트폰을 천천히 좌우로 돌리면 POI 방향이 Quest에서 실시간으로 따라갑니다."
                } else {
                    "Quest GPS 연결을 시작한 뒤 보정 모드를 켜고, 스마트폰을 천천히 좌우로 돌려주세요."
                },
                textAlign = TextAlign.Center,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            // Live Calibration Toggle Button
            Button(
                onClick = {
                    if (bridgeState.headingCalibrationModeActive) {
                        bridge.stopHeadingCalibrationMode()
                    } else {
                        bridge.startHeadingCalibrationMode()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (bridgeState.headingCalibrationModeActive) StatusRed else IndigoPrimary
                ),
                enabled = bridgeState.isRunning || bridgeState.headingCalibrationModeActive
            ) {
                Icon(
                    imageVector = if (bridgeState.headingCalibrationModeActive) Icons.Default.Stop else Icons.Default.CompassCalibration,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (bridgeState.headingCalibrationModeActive) "실시간 보정 중지" else "실시간 보정 시작",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Connection Status Pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = if (bridgeState.isRunning) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (bridgeState.isRunning) StatusGreen else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (bridgeState.isRunning) "Quest 연결됨 (${bridgeState.connectionStateText})" else "Quest 연결 필요",
                    fontSize = 13.sp,
                    color = if (bridgeState.isRunning) StatusGreen else MaterialTheme.colorScheme.outline
                )
            }

            Spacer(Modifier.height(10.dp))
        }
    }
}
