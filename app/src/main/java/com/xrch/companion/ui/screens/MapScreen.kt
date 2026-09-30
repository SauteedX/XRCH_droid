package com.xrch.companion.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xrch.companion.bridge.QuestLocationBridge
import com.xrch.companion.ui.theme.IndigoPrimary
import com.xrch.companion.ui.theme.StatusGreen
import com.xrch.companion.ui.viewmodel.CompanionViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    bridge: QuestLocationBridge,
    viewModel: CompanionViewModel
) {
    val bridgeState by bridge.state.collectAsState()

    var zoomLevel by remember { mutableFloatStateOf(1.0f) }
    var panOffsetX by remember { mutableFloatStateOf(0f) }
    var panOffsetY by remember { mutableFloatStateOf(0f) }

    // Reference center: Hanyang ERICA
    val refLat = 37.3017362
    val refLon = 126.8385608

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("주변 지도", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = {
                        zoomLevel = 1.0f
                        panOffsetX = 0f
                        panOffsetY = 0f
                    }) {
                        Icon(Icons.Default.GpsFixed, contentDescription = "중앙 정렬")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Interactive Map Canvas
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFF1F5F9))
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            zoomLevel = (zoomLevel * zoom).coerceIn(0.5f, 4.0f)
                            panOffsetX += pan.x
                            panOffsetY += pan.y
                        }
                    }
            ) {
                val centerX = size.width / 2 + panOffsetX
                val centerY = size.height / 2 + panOffsetY

                // Draw Radar Grid Circles
                val radii = listOf(80f, 160f, 260f, 380f).map { it * zoomLevel }
                radii.forEachIndexed { index, r ->
                    drawCircle(
                        color = Color(0xFFCBD5E1),
                        radius = r,
                        center = Offset(centerX, centerY),
                        style = Stroke(width = 1.5f)
                    )
                }

                // Grid lines
                drawLine(
                    color = Color(0xFFE2E8F0),
                    start = Offset(centerX, 0f),
                    end = Offset(centerX, size.height),
                    strokeWidth = 1f
                )
                drawLine(
                    color = Color(0xFFE2E8F0),
                    start = Offset(0f, centerY),
                    end = Offset(size.width, centerY),
                    strokeWidth = 1f
                )

                // Scale factor: 1 meter roughly translates to pixels
                // lat diff 0.001 ~ 111 meters
                val metersPerDegLat = 111000.0
                val metersPerDegLon = 88000.0
                val pxPerMeter = 1.2f * zoomLevel

                // Draw POI markers
                viewModel.allPois.forEach { poi ->
                    val dLat = poi.latitude - refLat
                    val dLon = poi.longitude - refLon

                    val dx = (dLon * metersPerDegLon * pxPerMeter).toFloat()
                    val dy = (-dLat * metersPerDegLat * pxPerMeter).toFloat()

                    val poiX = centerX + dx
                    val poiY = centerY + dy

                    val isSelected = viewModel.selectedPOI?.id == poi.id

                    // Marker outer glow
                    drawCircle(
                        color = if (isSelected) Color(0xFFEF4444).copy(alpha = 0.3f) else IndigoPrimary.copy(alpha = 0.25f),
                        radius = (if (isSelected) 18f else 12f) * zoomLevel,
                        center = Offset(poiX, poiY)
                    )
                    // Marker center
                    drawCircle(
                        color = if (isSelected) Color(0xFFEF4444) else IndigoPrimary,
                        radius = (if (isSelected) 10f else 7f) * zoomLevel,
                        center = Offset(poiX, poiY)
                    )
                }

                // Draw User / Device Location
                val userLat = bridgeState.latitudeText.toDoubleOrNull() ?: refLat
                val userLon = bridgeState.longitudeText.toDoubleOrNull() ?: refLon
                val userDLat = userLat - refLat
                val userDLon = userLon - refLon
                val userX = centerX + (userDLon * metersPerDegLon * pxPerMeter).toFloat()
                val userY = centerY + (-userDLat * metersPerDegLat * pxPerMeter).toFloat()

                // User Location Pulse
                drawCircle(
                    color = StatusGreen.copy(alpha = 0.25f),
                    radius = 24f * zoomLevel,
                    center = Offset(userX, userY)
                )
                drawCircle(
                    color = Color.White,
                    radius = 10f * zoomLevel,
                    center = Offset(userX, userY)
                )
                drawCircle(
                    color = StatusGreen,
                    radius = 7f * zoomLevel,
                    center = Offset(userX, userY)
                )
            }

            // Controls on top right
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FloatingActionButton(
                    onClick = { zoomLevel = (zoomLevel * 1.25f).coerceAtMost(4f) },
                    modifier = Modifier.size(42.dp),
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Icon(Icons.Default.ZoomIn, contentDescription = "확대")
                }
                FloatingActionButton(
                    onClick = { zoomLevel = (zoomLevel * 0.8f).coerceAtLeast(0.5f) },
                    modifier = Modifier.size(42.dp),
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Icon(Icons.Default.ZoomOut, contentDescription = "축소")
                }
            }

            // Bottom Overlay: Selected POI
            val selected = viewModel.selectedPOI
            if (selected != null) {
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(selected.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("${selected.distanceMeters}m · ${selected.category}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(selected.address, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                        }
                        Button(
                            onClick = { /* Glass guide */ },
                            colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("안내")
                        }
                    }
                }
            }
        }
    }
}
