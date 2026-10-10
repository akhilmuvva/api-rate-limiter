package com.cutm.nt14

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.cutm.nt14.data.remote.GatewayWebSocketClient
import com.cutm.nt14.data.repository.GatewayRepository
import com.cutm.nt14.ui.theme.NT14Theme
import com.cutm.nt14.ui.navigation.NT14NavHost
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

import androidx.activity.SystemBarStyle
import android.graphics.Color

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var wsClient: GatewayWebSocketClient

    @Inject
    lateinit var repository: GatewayRepository

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )

        askNotificationPermission()
        wsClient.connect()

        setContent {
            NT14Theme {
                NT14NavHost(repository = repository)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wsClient.disconnect()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
