package com.xrch.companion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xrch.companion.bridge.QuestLocationBridge
import com.xrch.companion.ui.theme.IndigoPrimary
import com.xrch.companion.ui.theme.StatusRed
import com.xrch.companion.ui.viewmodel.CompanionViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtherScreen(
    viewModel: CompanionViewModel,
    bridge: QuestLocationBridge,
    onNavigateToProfile: () -> Unit,
    onNavigateToHeading: () -> Unit,
    onNavigateToDeviceHub: () -> Unit,
    onNavigateToDiagnostics: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val isGuest by viewModel.auth.isGuest.collectAsState()
    val email by viewModel.auth.email.collectAsState()
    val status by viewModel.auth.status.collectAsState()
    val isWorking by viewModel.auth.isWorking.collectAsState()
    val usageStatus by viewModel.auth.usageStatus.collectAsState()

    var authEmail by remember { mutableStateOf("") }
    var authPassword by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var isSigningUp by remember { mutableStateOf(false) }
    var authMessage by remember { mutableStateOf<String?>(null) }

    val isAuthenticated = viewModel.auth.isAuthenticated
    val hasSession = viewModel.auth.hasSession

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("기타", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Account Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("계정", fontWeight = FontWeight.Bold, fontSize = 16.sp)

                    if (isGuest) {
                        LabeledRow("사용 모드", "게스트")
                        Text(
                            "로그인 없이 기본 기능을 사용할 수 있습니다. AI 추천은 서버의 일일 한도가 적용됩니다.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (isAuthenticated) {
                        LabeledRow("로그인", email ?: "-")
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    try {
                                        viewModel.auth.signOut()
                                        authMessage = null
                                    } catch (e: Exception) {
                                        authMessage = e.localizedMessage
                                    }
                                }
                            },
                            enabled = !isWorking,
                            colors = ButtonDefaults.buttonColors(containerColor = StatusRed),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("로그아웃")
                        }
                    } else {
                        OutlinedTextField(
                            value = authEmail,
                            onValueChange = { authEmail = it },
                            label = { Text("이메일") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            shape = RoundedCornerShape(12.dp)
                        )

                        OutlinedTextField(
                            value = authPassword,
                            onValueChange = { authPassword = it },
                            label = { Text("비밀번호") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            shape = RoundedCornerShape(12.dp)
                        )

                        if (isSigningUp) {
                            OutlinedTextField(
                                value = nickname,
                                onValueChange = { nickname = it },
                                label = { Text("닉네임") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                        }

                        Button(
                            onClick = {
                                val cleanEmail = authEmail.trim()
                                val cleanNick = nickname.trim()
                                coroutineScope.launch {
                                    try {
                                        if (isSigningUp) {
                                            viewModel.auth.signUp(cleanEmail, authPassword, cleanNick)
                                        } else {
                                            viewModel.auth.signIn(cleanEmail, authPassword)
                                        }
                                        authPassword = ""
                                        authMessage = viewModel.auth.status.value
                                    } catch (e: Exception) {
                                        authMessage = e.localizedMessage
                                    }
                                }
                            },
                            enabled = !isWorking && authEmail.isNotBlank() && authPassword.isNotBlank() && (!isSigningUp || nickname.isNotBlank()),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary)
                        ) {
                            if (isWorking) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (isSigningUp) "회원가입" else "로그인")
                        }

                        TextButton(
                            onClick = {
                                isSigningUp = !isSigningUp
                                authMessage = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isSigningUp) "기존 계정으로 로그인" else "계정 만들기", color = IndigoPrimary)
                        }
                    }

                    authMessage?.let {
                        Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    if (!hasSession) {
                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch { viewModel.auth.prepareGuestSession() }
                            },
                            enabled = !isWorking,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("게스트 연결 다시 시도")
                        }
                    }

                    if (hasSession) {
                        Text(usageStatus, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch { viewModel.auth.refreshUsage() }
                            },
                            enabled = !isWorking,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("남은 사용량 확인")
                        }
                    }
                }
            }

            // 2. App Tools Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        "앱 도구",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )

                    ToolRow(
                        title = "프로필 및 설정",
                        icon = Icons.Default.Person,
                        onClick = onNavigateToProfile
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    ToolRow(
                        title = "헤딩 보정",
                        icon = Icons.Default.Explore,
                        onClick = onNavigateToHeading
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    ToolRow(
                        title = "기기 연결",
                        icon = Icons.Default.Devices,
                        onClick = onNavigateToDeviceHub
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    ToolRow(
                        title = "개발자 진단",
                        icon = Icons.Default.BugReport,
                        onClick = onNavigateToDiagnostics
                    )
                }
            }

            // 3. Supabase Connection Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Supabase 연결", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    LabeledRow("프로젝트", "pdvemspkknlfapdzkwhz")
                    LabeledRow("함수", "chat")
                    LabeledRow("상태", viewModel.supabaseProbeStatus)

                    Button(
                        onClick = { viewModel.probeSupabase() },
                        enabled = !viewModel.isProbingSupabase,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary)
                    ) {
                        if (viewModel.isProbingSupabase) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("연결 확인")
                    }
                }
            }

            // 4. Recent AI Recommendation Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("최근 AI 추천 요청", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    LabeledRow("결과", viewModel.lastRecommendationStatus)
                    LabeledRow("응답 모델", viewModel.lastRecommendationModel)
                    LabeledRow("GPU", viewModel.lastRecommendationGPU)

                    val timeStr = viewModel.lastRecommendationAt?.let {
                        SimpleDateFormat("yyyy. MM. dd. a h:mm:ss", Locale.KOREA).format(it)
                    } ?: "-"
                    LabeledRow("요청 시각", timeStr)

                    val accStr = viewModel.lastRecommendationAccuracy?.let {
                        String.format(Locale.US, "%.1f m", it)
                    } ?: "-"
                    LabeledRow("GPS 정확도", accStr)
                    LabeledRow("표시된 장소", "${viewModel.allPois.size}곳")
                }
            }

            // Footnote
            Text(
                "연결 확인은 chat/plan 함수만 호출하며 추천 작업을 만들지 않습니다. GPU 사용 여부는 마지막 추천 응답에서 확인합니다.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun ToolRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = IndigoPrimary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun LabeledRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
