package com.xrch.companion.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.xrch.companion.bridge.QuestLocationBridge
import com.xrch.companion.ui.theme.IndigoPrimary
import com.xrch.companion.ui.viewmodel.CompanionViewModel

enum class NavigationTab(val label: String, val icon: ImageVector) {
    DISCOVER("탐색", Icons.Default.Search),
    MAP("지도", Icons.Default.Map),
    SAVED("저장", Icons.Default.Favorite),
    PROFILE("프로필", Icons.Default.Person),
    HEADING("헤딩 보정", Icons.Default.Explore),
    DEVICE("기기", Icons.Default.Devices)
}

@Composable
fun MainScreen(
    bridge: QuestLocationBridge,
    viewModel: CompanionViewModel
) {
    var selectedTab by remember { mutableStateOf(NavigationTab.DISCOVER) }
    var showDiagnostics by remember { mutableStateOf(false) }

    if (showDiagnostics) {
        DiagnosticsScreen(
            bridge = bridge,
            onNavigateBack = { showDiagnostics = false }
        )
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp
                ) {
                    NavigationTab.entries.forEach { tab ->
                        val selected = selectedTab == tab
                        NavigationBarItem(
                            selected = selected,
                            onClick = { selectedTab = tab },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = IndigoPrimary,
                                selectedTextColor = IndigoPrimary,
                                indicatorColor = IndigoPrimary.copy(alpha = 0.15f)
                            )
                        )
                    }
                }
            }
        ) { padding ->
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when (selectedTab) {
                    NavigationTab.DISCOVER -> DiscoverScreen(
                        viewModel = viewModel,
                        onNavigateToDeviceHub = { selectedTab = NavigationTab.DEVICE }
                    )
                    NavigationTab.MAP -> MapScreen(
                        bridge = bridge,
                        viewModel = viewModel
                    )
                    NavigationTab.SAVED -> SavedPlacesScreen(
                        viewModel = viewModel
                    )
                    NavigationTab.PROFILE -> ProfileScreen(
                        viewModel = viewModel
                    )
                    NavigationTab.HEADING -> HeadingCalibrationScreen(
                        bridge = bridge
                    )
                    NavigationTab.DEVICE -> DeviceHubScreen(
                        bridge = bridge,
                        viewModel = viewModel,
                        onNavigateToDiagnostics = { showDiagnostics = true }
                    )
                }
            }
        }
    }
}
