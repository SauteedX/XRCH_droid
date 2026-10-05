package com.xrch.companion.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
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
    AI_CHAT("AI 추천", Icons.Default.AutoAwesome),
    MAP("지도", Icons.Default.Map),
    SAVED("저장", Icons.Default.Favorite),
    OTHER("기타", Icons.Default.MoreHoriz)
}

enum class SubScreen {
    NONE,
    PROFILE,
    HEADING,
    DEVICE,
    DIAGNOSTICS
}

@Composable
fun MainScreen(
    bridge: QuestLocationBridge,
    viewModel: CompanionViewModel
) {
    var selectedTab by remember { mutableStateOf(NavigationTab.DISCOVER) }
    var currentSubScreen by remember { mutableStateOf(SubScreen.NONE) }

    if (currentSubScreen != SubScreen.NONE) {
        BackHandler {
            currentSubScreen = SubScreen.NONE
        }

        when (currentSubScreen) {
            SubScreen.PROFILE -> ProfileScreen(
                viewModel = viewModel,
                onNavigateBack = { currentSubScreen = SubScreen.NONE }
            )
            SubScreen.HEADING -> HeadingCalibrationScreen(
                bridge = bridge,
                onNavigateBack = { currentSubScreen = SubScreen.NONE }
            )
            SubScreen.DEVICE -> DeviceHubScreen(
                bridge = bridge,
                viewModel = viewModel,
                onNavigateToDiagnostics = { currentSubScreen = SubScreen.DIAGNOSTICS },
                onNavigateBack = { currentSubScreen = SubScreen.NONE }
            )
            SubScreen.DIAGNOSTICS -> DiagnosticsScreen(
                bridge = bridge,
                onNavigateBack = { currentSubScreen = SubScreen.NONE }
            )
            SubScreen.NONE -> {}
        }
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when (selectedTab) {
                    NavigationTab.DISCOVER -> DiscoverScreen(
                        viewModel = viewModel,
                        onNavigateToDeviceHub = { currentSubScreen = SubScreen.DEVICE },
                        onNavigateToAiChat = { selectedTab = NavigationTab.AI_CHAT }
                    )
                    NavigationTab.AI_CHAT -> AiChatScreen(
                        viewModel = viewModel,
                        bridge = bridge
                    )
                    NavigationTab.MAP -> MapScreen(
                        bridge = bridge,
                        viewModel = viewModel
                    )
                    NavigationTab.SAVED -> SavedPlacesScreen(
                        viewModel = viewModel
                    )
                    NavigationTab.OTHER -> OtherScreen(
                        viewModel = viewModel,
                        bridge = bridge,
                        onNavigateToProfile = { currentSubScreen = SubScreen.PROFILE },
                        onNavigateToHeading = { currentSubScreen = SubScreen.HEADING },
                        onNavigateToDeviceHub = { currentSubScreen = SubScreen.DEVICE },
                        onNavigateToDiagnostics = { currentSubScreen = SubScreen.DIAGNOSTICS }
                    )
                }
            }
        }
    }
}
