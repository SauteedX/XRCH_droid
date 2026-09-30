package com.xrch.companion

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.xrch.companion.bridge.QuestLocationBridge
import com.xrch.companion.ui.screens.MainScreen
import com.xrch.companion.ui.theme.XRCHCompanionTheme
import com.xrch.companion.ui.viewmodel.CompanionViewModel

class MainActivity : ComponentActivity() {

    private lateinit var bridge: QuestLocationBridge
    private val viewModel by viewModels<CompanionViewModel>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Permissions handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bridge = QuestLocationBridge(applicationContext)
        requestRequiredPermissions()

        setContent {
            XRCHCompanionTheme {
                MainScreen(
                    bridge = bridge,
                    viewModel = viewModel
                )
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val ungranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungranted.isNotEmpty()) {
            permissionLauncher.launch(ungranted.toTypedArray())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bridge.stop()
    }
}
