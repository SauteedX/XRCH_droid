package com.xrch.companion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xrch.companion.bridge.QuestLocationBridge
import com.xrch.companion.data.CompanionLocationMode
import com.xrch.companion.ui.theme.IndigoPrimary
import com.xrch.companion.ui.theme.StatusGreen
import com.xrch.companion.ui.theme.StatusOrange
import com.xrch.companion.ui.theme.StatusRed
import com.xrch.companion.ui.viewmodel.CompanionViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceHubScreen(
    bridge: QuestLocationBridge,
    viewModel: CompanionViewModel,
    onNavigateToDiagnostics: () -> Unit
) {
    val bridgeState by bridge.state.collectAsState()
    var showUdpDialog by remember { mutableStateOf(false) }
    var tempUdpHost by remember { mutableStateOf(bridgeState.questHost) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("연결 기기", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Connection Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val statusColor = when (bridgeState.connectionStateText) {
                            "준비됨" -> StatusGreen
                            "연결 중", "Quest 검색 중", "서비스 확인 중" -> StatusOrange
                            "실패" -> StatusRed
                            else -> MaterialTheme.colorScheme.outline
                        }

                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(statusColor.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Sensors,
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Spacer(Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "QUEST LOCATION BRIDGE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Text(
                                bridgeState.connectionStateText,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                bridgeState.statusText,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                if (bridgeState.isRunning) bridge.stop() else bridge.startBLE()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (bridgeState.isRunning) StatusRed else IndigoPrimary
                            )
                        ) {
                            Icon(
                                if (bridgeState.isRunning) Icons.Default.Stop else Icons.AutoMirrored.Filled.BluetoothSearching,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (bridgeState.isRunning) "연결 중지" else "Quest BLE 연결")
                        }

                        OutlinedButton(
                            onClick = {
                                tempUdpHost = bridgeState.questHost
                                showUdpDialog = true
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("UDP")
                        }
                    }
                }
            }

            // 2. GPS Mode Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("GPS 모드", fontWeight = FontWeight.Bold, fontSize = 16.sp)

                    // Mode Switcher Chips
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CompanionLocationMode.entries.forEach { mode ->
                            val isSelected = bridgeState.locationMode == mode
                            FilterChip(
                                selected = isSelected,
                                onClick = { bridge.setLocationMode(mode) },
                                label = { Text(mode.label) },
                                shape = RoundedCornerShape(12.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = IndigoPrimary,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }

                    // Virtual GPS Coordinate Inputs
                    if (bridgeState.locationMode == CompanionLocationMode.VIRTUAL) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "가상 GPS 좌표를 설정하면 1초마다 Quest로 자동 전송됩니다.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            var editLat by remember(bridgeState.virtualLatitude) { mutableStateOf(bridgeState.virtualLatitude) }
                            var editLon by remember(bridgeState.virtualLongitude) { mutableStateOf(bridgeState.virtualLongitude) }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = editLat,
                                    onValueChange = { editLat = it },
                                    label = { Text("위도 (Lat)") },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = editLon,
                                    onValueChange = { editLon = it },
                                    label = { Text("경도 (Lon)") },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Button(
                                    onClick = {
                                        val lat = editLat.toDoubleOrNull() ?: 37.3017362
                                        val lon = editLon.toDoubleOrNull() ?: 126.8385608
                                        bridge.setVirtualLocation(lat, lon)
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary)
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("가상 GPS 적용", fontSize = 13.sp)
                                }

                                TextButton(
                                    onClick = {
                                        bridge.setVirtualLocation(37.3017362, 126.8385608)
                                    }
                                ) {
                                    Text("ERICA 기준 위치", fontSize = 13.sp)
                                }
                            }
                        }
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("전송 소스", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(bridgeState.locationSourceText, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // 3. Navigation Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("현재 안내", fontWeight = FontWeight.Bold, fontSize = 16.sp)

                    val poi = viewModel.selectedPOI
                    if (poi != null) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("목적지", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(poi.name, fontWeight = FontWeight.SemiBold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("거리", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${poi.distanceMeters}m", fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Text(
                            "탐색 또는 지도에서 안내할 장소를 선택하세요.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }

                    HorizontalDivider()

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("GPS 전송 누적", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${bridgeState.sentCount}회", fontWeight = FontWeight.Bold, color = IndigoPrimary)
                    }
                }
            }

            // 4. Initial Heading Calibration Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Quest 초기 방향 보정", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "Quest 정면과 스마트폰 상단을 같은 방향으로 맞춘 뒤 1회 전송하세요.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("현재 방향", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(bridgeState.headingText, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("방향 정확도", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(bridgeState.headingAccuracyText, fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = { bridge.calibrateQuestHeading() },
                        enabled = bridgeState.isRunning,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary)
                    ) {
                        Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("현재 방향으로 Quest 보정")
                    }
                }
            }

            // 5. Diagnostics Screen Link
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    TextButton(
                        onClick = onNavigateToDiagnostics,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Build, contentDescription = null, tint = IndigoPrimary)
                                Spacer(Modifier.width(10.dp))
                                Text("개발자 진단 화면", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    // UDP Host Input Dialog
    if (showUdpDialog) {
        AlertDialog(
            onDismissRequest = { showUdpDialog = false },
            title = { Text("Quest UDP 연결 설정") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Quest의 로컬 Wi-Fi IP 주소를 입력하세요 (기본 포트: 47777).", fontSize = 13.sp)
                    OutlinedTextField(
                        value = tempUdpHost,
                        onValueChange = { tempUdpHost = it },
                        label = { Text("Quest IP (예: 192.168.1.100)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        bridge.setQuestHost(tempUdpHost)
                        bridge.startUDP(tempUdpHost)
                        showUdpDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary)
                ) {
                    Text("UDP 연결 시작")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUdpDialog = false }) {
                    Text("취소")
                }
            }
        )
    }
}
