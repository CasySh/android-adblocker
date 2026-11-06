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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        onStartVpn = { onStartVpnClicked() },
                        onStopVpn = { stopVpnService() }
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
    private fun stopVpnService() {
        Log.d("MainActivity", "Отправка команды STOP в сервис...")
        val intent = Intent(this, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_STOP
        }
        // Используем startService, чтобы передать команду в onStartCommand
        startService(intent)
        Toast.makeText(this, "VPN stopped", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    onStartVpn: () -> Unit,
    onStopVpn: () -> Unit
) {
    var isVpnActive by remember { mutableStateOf(false) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Button(
            onClick = {
                if (isVpnActive) {
                    onStopVpn()
                    isVpnActive = false
                } else {
                    onStartVpn()
                    isVpnActive = true
                }
            },
            modifier = Modifier
                .size(180.dp)
                .clip(CircleShape),
            shape = CircleShape,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = if (isVpnActive) Color(0xFFA24444) else Color(0xFF43A047) //зеленый выключен, красный включен
            )
        ) {
            Text(
                text = if (isVpnActive) "Stop\nVPN" else "Start\nVPN",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = Color.White
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    Mypain2Theme {
        MainScreen(onStartVpn = {}, onStopVpn = {})
    }
}

