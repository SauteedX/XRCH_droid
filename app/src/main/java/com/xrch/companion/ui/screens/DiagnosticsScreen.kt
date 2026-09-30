package com.xrch.companion.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xrch.companion.bridge.QuestLocationBridge
import com.xrch.companion.ui.theme.IndigoPrimary
import com.xrch.companion.ui.theme.StatusGreen
import com.xrch.companion.ui.theme.StatusOrange
import com.xrch.companion.ui.theme.StatusRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    bridge: QuestLocationBridge,
    onNavigateBack: () -> Unit
) {
    val bridgeState by bridge.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("개발자 진단", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로가기")
                    }
                },
                actions = {
                    TextButton(onClick = { bridge.clearEvents() }) {
                        Text("로그 지우기", fontSize = 13.sp)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Connection Hero
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    val statusColor = when (bridgeState.connectionStateText) {
                        "준비됨" -> StatusGreen
                        "연결 중", "대기 중", "Quest 검색 중" -> StatusOrange
                        "실패" -> StatusRed
                        else -> MaterialTheme.colorScheme.outline
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(statusColor.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Sensors, contentDescription = null, tint = statusColor)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("QUEST LOCATION BRIDGE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.outline)
                            Text(bridgeState.connectionStateText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(bridgeState.statusText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetricPill(title = "전송", value = "${bridgeState.sentCount}", modifier = Modifier.weight(1f))
                        MetricPill(title = "마지막", value = bridgeState.lastSentAgoText, modifier = Modifier.weight(1f))
                        MetricPill(title = "패킷", value = bridgeState.lastPacketSizeText, modifier = Modifier.weight(1f))
                    }
                }
            }

            // 2. BLE Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Quest BLE", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    InfoRow(label = "기기", value = bridgeState.bleDeviceText)
                    InfoRow(label = "신호", value = bridgeState.bleRSSIText)
                    InfoRow(label = "서비스", value = "ARCH Location")

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { if (bridgeState.isRunning) bridge.stop() else bridge.startBLE() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary)
                        ) {
                            Text(if (bridgeState.isRunning) "중지" else "BLE 시작", fontSize = 13.sp)
                        }
                        OutlinedButton(
                            onClick = { bridge.sendCurrentOrTestLocation() },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("1회 전송", fontSize = 13.sp)
                        }
                        OutlinedButton(
                            onClick = { bridge.calibrateQuestHeading() },
                            enabled = bridgeState.isRunning,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("초기 보정", fontSize = 13.sp)
                        }
                    }
                }
            }

            // 3. Location Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("스마트폰 위치", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = IndigoPrimary.copy(alpha = 0.12f)
                        ) {
                            Text(
                                bridgeState.locationSourceText,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = IndigoPrimary
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(bridgeState.latitudeText, fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Text(bridgeState.longitudeText, fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    }

                    HorizontalDivider()

                    InfoRow(label = "수평 정확도", value = bridgeState.accuracyText)
                    InfoRow(label = "현재 방향", value = bridgeState.headingText)
                    InfoRow(label = "방향 정확도", value = bridgeState.headingAccuracyText)
                    InfoRow(label = "고도", value = bridgeState.altitudeText)
                    InfoRow(label = "위치 시각", value = bridgeState.locationTimestampText)
                    InfoRow(label = "위치 나이", value = bridgeState.locationAgeText)
                }
            }

            // 4. System Diagnostics
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("진단", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    InfoRow(label = "무선 상태", value = bridgeState.networkPathText)
                    InfoRow(label = "위치 권한", value = bridgeState.authorizationText)
                    InfoRow(label = "BLE 상태", value = bridgeState.connectionStateText)
                    InfoRow(label = "Endpoint", value = bridgeState.endpointText)
                    InfoRow(label = "메시지 계약", value = "arch.location.v1")
                    InfoRow(label = "앱 버전", value = "1.0.0 (1)")
                    InfoRow(label = "기기", value = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
                }
            }

            // 5. Recent Packet JSON
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("최근 송신 패킷", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0F172A), RoundedCornerShape(10.dp))
                            .padding(12.dp)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = bridgeState.lastPacketText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color(0xFF38BDF8)
                        )
                    }
                }
            }

            // 6. Event Logs
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("이벤트 로그", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    if (bridgeState.events.isEmpty()) {
                        Text("아직 이벤트가 없습니다.", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
                    } else {
                        bridgeState.events.forEachIndexed { index, event ->
                            Text(event, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (index < bridgeState.events.size - 1) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

@Composable
private fun MetricPill(title: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(title, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}
