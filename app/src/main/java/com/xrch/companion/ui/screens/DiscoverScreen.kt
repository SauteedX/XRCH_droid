package com.xrch.companion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xrch.companion.data.CompanionPOI
import com.xrch.companion.ui.theme.IndigoPrimary
import com.xrch.companion.ui.theme.IndigoSecondary
import com.xrch.companion.ui.viewmodel.CompanionViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    viewModel: CompanionViewModel,
    onNavigateToDeviceHub: () -> Unit = {}
) {
    var showDetailDialog by remember { mutableStateOf<CompanionPOI?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ARCH", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    Text(
                        text = "어디로 가볼까요?",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "스마트폰에서 찾고, 글라스에서 바로 확인하세요.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            }

            // Search Bar
            item {
                OutlinedTextField(
                    value = viewModel.query,
                    onValueChange = { viewModel.query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("장소, 카테고리, 분위기 검색") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "검색") },
                    trailingIcon = {
                        if (viewModel.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.query = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "지우기")
                            }
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }

            // Category Chips
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(viewModel.categories) { cat ->
                        val selected = viewModel.selectedCategory == cat
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.selectedCategory = cat },
                            label = { Text(cat, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
                            shape = CircleShape,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = IndigoPrimary,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
            }

            // Section Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("주변 추천", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("${viewModel.filteredPOIs.size}곳", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }

            // POI List
            items(viewModel.filteredPOIs) { poi ->
                POIRowCard(
                    poi = poi,
                    isFavorite = viewModel.favoriteIDs.contains(poi.id),
                    onFavoriteToggle = { viewModel.toggleFavorite(poi) },
                    onClick = { showDetailDialog = poi }
                )
            }

            // AI Search Section
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = IndigoPrimary.copy(alpha = 0.08f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = IndigoPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text("AI에게 조건으로 찾기", fontWeight = FontWeight.Bold, color = IndigoPrimary)
                        }
                        Text(
                            viewModel.aiAnswer,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = viewModel.aiPrompt,
                                onValueChange = { viewModel.aiPrompt = it },
                                placeholder = { Text("예: 조용하고 콘센트 있는 카페", fontSize = 13.sp) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true
                            )
                            Spacer(Modifier.width(8.dp))
                            IconButton(
                                onClick = { viewModel.askAI() },
                                colors = IconButtonDefaults.iconButtonColors(containerColor = IndigoPrimary)
                            ) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = "전송", tint = Color.White)
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    // POI Detail Dialog
    showDetailDialog?.let { poi ->
        AlertDialog(
            onDismissRequest = { showDetailDialog = null },
            title = {
                Text(poi.name, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(poi.summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Place, contentDescription = null, tint = IndigoPrimary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(poi.address, fontSize = 13.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null, tint = IndigoPrimary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("${poi.distanceMeters}m 거리", fontSize = 13.sp)
                    }
                    Text("★ ${poi.rating} · ${poi.category}", fontWeight = FontWeight.SemiBold, color = Color(0xFFF59E0B))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.selectedPOI = poi
                        showDetailDialog = null
                        onNavigateToDeviceHub()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary)
                ) {
                    Text("글라스에서 안내 시작")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDetailDialog = null }) {
                    Text("닫기")
                }
            }
        )
    }
}

@Composable
fun POIRowCard(
    poi: CompanionPOI,
    isFavorite: Boolean,
    onFavoriteToggle: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(IndigoPrimary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = getPoiIcon(poi.category),
                    contentDescription = poi.category,
                    tint = IndigoPrimary,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(poi.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("★ ${poi.rating}", fontSize = 12.sp, color = Color(0xFFF59E0B), fontWeight = FontWeight.SemiBold)
                }
                Text(poi.summary, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text("${poi.distanceMeters}m · ${poi.category}", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
            }

            IconButton(onClick = onFavoriteToggle) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = "즐겨찾기",
                    tint = if (isFavorite) Color(0xFFEC4899) else MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

fun getPoiIcon(category: String): ImageVector {
    return when (category) {
        "카페" -> Icons.Default.LocalCafe
        "음식점" -> Icons.Default.Restaurant
        "의료" -> Icons.Default.LocalHospital
        "쇼핑" -> Icons.Default.ShoppingBag
        "생활" -> Icons.Default.LocalConvenienceStore
        else -> Icons.Default.Place
    }
}
