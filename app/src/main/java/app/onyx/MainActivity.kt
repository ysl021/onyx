package app.onyx

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.onyx.ui.theme.OnyxTheme

class MainActivity : ComponentActivity() {

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        AppController.get(this).startBudsIfAllowed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
        )
        AppController.get(this).start()
        setContent {
            OnyxTheme {
                OnyxApp()
            }
        }
        requestStartupPermissions()
    }

    private fun requestStartupPermissions() {
        val wanted = buildList {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (wanted.isNotEmpty()) {
            permissions.launch(wanted.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        val controller = AppController.get(this)
        controller.media.refresh()
        controller.startBudsIfAllowed()
    }

    override fun onDestroy() {
        if (isFinishing) {
            // Keep the process-level controller for the foreground buds service;
            // close only the transient coroutines that belong to the UI.
        }
        super.onDestroy()
    }
}
