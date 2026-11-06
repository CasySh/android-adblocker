package com.example.mypain2

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream

class MyVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    @Volatile
    private var isRunning = false
    private val blacklist = HashSet<String>()
    companion object {
        const val ACTION_STOP = "com.example.mypain2.STOP_VPN"
    }

    private fun loadBlocklist() {
        if (blacklist.isNotEmpty()) return

        try {
            val inputStream = assets.open("blocklist.txt")
            val reader = inputStream.bufferedReader()

            reader.forEachLine { line ->
                // убираем пробелы по краям и пропускаем пустые строки или комментарии
                val trimmedLine = line.trim()
                if (trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#")) {
                    blacklist.add(trimmedLine)
                }
            }
            reader.close()
            Log.d("MyVpnService", "Загружено ${blacklist.size} доменов из файла")

        } catch (e: Exception) {
            Log.e("MyVpnService", "Ошибка загрузки blacklist", e)
            // запасной вариант
            blacklist.addAll(listOf("doubleclick.net", "googleads.g.doubleclick.net", "adservice.google.com"))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        Log.d("MyVpnService", "VPN service started")
        loadBlocklist()
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        // если старый поток жив - останавливаем
        if (vpnThread?.isAlive == true) {
            isRunning = false
            vpnInterface?.close()
            vpnThread?.interrupt()
        }

        val builder = Builder()
        builder.setSession("My AdBlocker VPN")
        builder.addAddress("10.0.0.2", 24)
        builder.addRoute("0.0.0.0", 0)
        builder.addDnsServer("8.8.8.8")

        vpnInterface = builder.establish()

        if (vpnInterface == null) {
            Log.e("MyVpnService", "Failed to establish VPN")
            return
        }

        Log.d("MyVpnService", "VPN established successfully")
        startForeground(1, createNotification())

        isRunning = true
        vpnThread = Thread { readPackets() }.apply { start() }
    }

    private fun stopVpn() {
        Log.d("MyVpnService", "Получена команда STOP")
        isRunning = false
        vpnInterface?.close()
        vpnInterface = null
        vpnThread?.interrupt()
        vpnThread = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        Log.d("MyVpnService", "VPN service destroying...")
        isRunning = false
        vpnInterface?.close()
        vpnThread?.interrupt()
        super.onDestroy()
        Log.d("MyVpnService", "VPN service destroyed")
    }

    // если включили другой VPN
    override fun onRevoke() {
        Log.w("MyVpnService", "VPN был отозван системой! Останавливаем сервис.")
        stopVpn()
        super.onRevoke()
    }

    // если пользователь выключил приложение
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d("MyVpnService", "Приложение закрыто пользователем. Останавливаем сервис.")
        stopVpn()
        super.onTaskRemoved(rootIntent)
    }

    private fun createNotification(): android.app.Notification {
        val channelId = "vpn_channel"

        // канал уведомлений для Android 8+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "VPN Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("AdBlocker VPN")
            .setContentText("Фильтрация рекламы активна")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
    }


    private fun readPackets() {
        val vpnInput = FileInputStream(vpnInterface!!.fileDescriptor)
        val vpnOutput = FileOutputStream(vpnInterface!!.fileDescriptor)
        val packet = ByteArray(32767)

        val parser = DnsParser()

        while (isRunning) {
            try {
                val length = vpnInput.read(packet)

                if (length > 0) {
                    // DNS-запрос - вернется домен
                    // если нет - null
                    val domain = parser.parsePacket(packet, length)

                    if (domain != null) {
                        var isBlocked = false
                        for (blockedDomain in blacklist) {
                            if (domain == blockedDomain || domain.endsWith(".$blockedDomain")) {
                                isBlocked = true
                                break
                            }
                        }
                        if (isBlocked) {
                            Log.d("MyVpnService", "БЛОКИРОВКА: $domain")

                            val response = PacketBlocker.createBlockResponse(packet, length)

                            // отправляем ответ обратно в TUN
                            try {
                                vpnOutput.write(response, 0, length)
                                Log.d("MyVpnService", "Отправлен NXDOMAIN для $domain")
                            } catch (e: Exception) {
                                Log.e("MyVpnService", "Ошибка записи ответа", e)
                            }
                        } else {
                            Log.d("MyVpnService", "DNS запрос: $domain")
                        }
                    }
                }
            } catch (e: java.io.IOException) {
                // если TUN закрыт мы просто выходим из цикла
                if (isRunning) Log.e("MyVpnService", "Ошибка чтения (TUN закрыт)", e)
                break
            } catch (e: Exception) {
                Log.e("MyVpnService", "Ошибка при чтении пакета", e)
                break
            }
        }
    }
}
