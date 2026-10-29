package com.example.mypain2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.mypain2.ui.theme.Mypain2Theme

import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.material3.Button
import android.net.VpnService
import android.content.Intent
import android.util.Log

class MainActivity : ComponentActivity() {
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult())
    { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val permissionLauncher = registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                if (!isGranted) {
                    Log.w("MainActivity", "Notification permission denied")
                }
            }
            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            Mypain2Theme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    VpnScreen(
                        modifier = Modifier.padding(innerPadding),
                        onStartVpn = { onStartVpnClicked() }
                    )
                }
            }
        }
    }

    private fun onStartVpnClicked() {
        // prepare() проверяет дал ли пользователь разрешение на VPN
        val intent = VpnService.prepare(this)
        if (intent != null) {
            // разрешения нет — показываем системный диалог
            vpnPermissionLauncher.launch(intent)
        } else {
            // разрешение есть — запускаем сервис
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, MyVpnService::class.java)
        startService(intent)
        Toast.makeText(this, "VPN starting...", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun VpnScreen(modifier: Modifier = Modifier, onStartVpn: () -> Unit) {
    Button(
        onClick = onStartVpn,
        modifier = modifier
    ) {
        Text("Start VPN")
    }
}
@Preview(showBackground = true)
@Composable
fun VpnScreenPreview() {
    Mypain2Theme {
        VpnScreen(onStartVpn = {})
    }
}