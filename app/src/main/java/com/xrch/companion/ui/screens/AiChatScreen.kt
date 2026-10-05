package com.xrch.companion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xrch.companion.bridge.QuestLocationBridge
import com.xrch.companion.data.CompanionPOI
import com.xrch.companion.data.MessageRole
import com.xrch.companion.data.RecommendationMessage
import com.xrch.companion.ui.theme.IndigoPrimary
import com.xrch.companion.ui.viewmodel.CompanionViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatScreen(
    viewModel: CompanionViewModel,
    bridge: QuestLocationBridge
) {
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val isGuest by viewModel.auth.isGuest.collectAsState()
    val usage by viewModel.auth.usage.collectAsState()
    val usageStatus by viewModel.auth.usageStatus.collectAsState()

    var showDetailDialog by remember { mutableStateOf<CompanionPOI?>(null) }

    LaunchedEffect(Unit) {
        viewModel.auth.refreshUsage()
    }

    LaunchedEffect(viewModel.recommendationMessages.size, viewModel.isRecommending) {
        if (viewModel.recommendationMessages.isNotEmpty()) {
            listState.animateScrollToItem(viewModel.recommendationMessages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AI 추천", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        if (isGuest) {
                            Text(
                                usageStatus,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Guest Quota Info Banner
            if (isGuest && usage?.resetDateFormatted != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "${usage?.resetDateFormatted}에 초기화",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }

            // Message List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                items(viewModel.recommendationMessages, key = { it.id }) { msg ->
                    ChatBubble(
                        message = msg,
                        onPlaceClick = { showDetailDialog = it }
                    )
                }

                if (viewModel.isRecommending) {
                    item {
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = IndigoPrimary
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "현재 위치에서 장소를 찾는 중…",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Input Bar
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = viewModel.aiPrompt,
                        onValueChange = { viewModel.aiPrompt = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("근처에서 찾고 싶은 장소를 물어보세요", fontSize = 13.sp) },
                        shape = RoundedCornerShape(20.dp),
                        maxLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.background,
                            unfocusedContainerColor = MaterialTheme.colorScheme.background
                        )
                    )

                    Spacer(Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            val prompt = viewModel.aiPrompt.trim()
                            if (prompt.isNotEmpty()) {
                                viewModel.askAI(prompt, bridge.getLatestLocation())
                            }
                        },
                        enabled = viewModel.aiPrompt.isNotBlank() && !viewModel.isRecommending,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                if (viewModel.aiPrompt.isNotBlank() && !viewModel.isRecommending)
                                    IndigoPrimary
                                else
                                    MaterialTheme.colorScheme.surfaceVariant
                            )
                    ) {
                        Icon(
                            Icons.Default.ArrowUpward,
                            contentDescription = "질문 보내기",
                            tint = if (viewModel.aiPrompt.isNotBlank() && !viewModel.isRecommending)
                                Color.White
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    showDetailDialog?.let { poi: CompanionPOI ->
        POIDetailDialog(
            poi = poi,
            onDismiss = { showDetailDialog = null }
        )
    }
}

@Composable
private fun ChatBubble(
    message: RecommendationMessage,
    onPlaceClick: (CompanionPOI) -> Unit
) {
    val isUser = message.role == MessageRole.USER

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (isUser) Spacer(Modifier.width(48.dp))

        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(if (isUser) IndigoPrimary else MaterialTheme.colorScheme.surface)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = message.text,
                fontSize = 14.sp,
                color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface
            )

            if (message.places.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                message.places.forEach { poi ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlaceClick(poi) },
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Place,
                                contentDescription = null,
                                tint = IndigoPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(poi.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(
                                    "${poi.distanceMeters}m · ${poi.category}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        if (!isUser) Spacer(Modifier.width(48.dp))
    }
}
