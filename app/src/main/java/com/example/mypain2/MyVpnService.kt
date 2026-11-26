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
import java.net.InetAddress
import androidx.core.content.edit

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
            assets.open("blocklist.txt").bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    // убираем пробелы по краям и пропускаем пустые строки или комментарии
                    val trimmedLine = line.trim().lowercase()
                    if (trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#")) {
                        blacklist.add(trimmedLine)
                    }
                }
            }
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
        //адрес VPN-интерфейса
        builder.addAddress("10.0.0.2", 24)
        //DNS-сервер находится по локальному адресу 10.0.0.1
        builder.addDnsServer("10.0.0.1")
        // маршрутизируем в VPN только трафик к DNS-серверу
        // весь остальной трафик идут напрямую через обычный интернет
        builder.addRoute("10.0.0.1", 32)

        val establishedInterface = builder.establish() ?: run {
            Log.e("MyVpnService", "Failed to establish VPN")
            stopSelf()
            return
        }
        vpnInterface = establishedInterface

        Log.d("MyVpnService", "VPN established successfully")
        startForeground(1, createNotification())

        getSharedPreferences("vpn_prefs", MODE_PRIVATE).edit {
            putBoolean("is_vpn_active", true) }

        isRunning = true
        vpnThread = Thread { readPackets(establishedInterface) }.apply { start() }
    }

    private fun stopVpn() {
        Log.d("MyVpnService", "Получена команда STOP")
        isRunning = false
        vpnInterface?.close()
        vpnInterface = null
        vpnThread?.interrupt()
        vpnThread = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSharedPreferences("vpn_prefs", MODE_PRIVATE).edit {
            putBoolean("is_vpn_active", false) }
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


    private fun readPackets(pfd: ParcelFileDescriptor) {
        val vpnInput = FileInputStream(pfd.fileDescriptor)
        val vpnOutput = FileOutputStream(pfd.fileDescriptor)
        val packet = ByteArray(32767)

        val parser = DnsParser()
        val dnsServer = InetAddress.getByName("8.8.8.8")

        while (isRunning) {
            try {
                val length = vpnInput.read(packet)

                if (length > 0) {
                    // DNS-запрос - вернется домен
                    // если нет - null
                    val domain = parser.parsePacket(packet, length)

                    if (domain != null) {
                        val isBlocked = blacklist.contains(domain) ||
                                blacklist.any { blockedDomain -> domain.endsWith(".$blockedDomain") }

                        if (isBlocked) {
                            Log.d("Block", "БЛОКИРОВКА: $domain")

                            val response = PacketBuilder.createBlockResponse(packet, length)

                            // отправляем ответ обратно в TUN
                            if (response != null) {
                                try {
                                    vpnOutput.write(response, 0, response.size)
                                    Log.d("NXDOMAIN", "Отправлен NXDOMAIN для $domain")
                                } catch (e: Exception) {
                                    Log.e("NX_err", "Ошибка записи ответа", e)
                                }
                            } else {
                                Log.w("NX_err", "Не удалось создать NXDOMAIN для $domain")
                            }

                        } else {
                            Log.d("DNS", "DNS запрос: $domain")

                            var channel: java.nio.channels.DatagramChannel? = null
                            var socket: java.net.DatagramSocket? = null

                            try {
                                channel = java.nio.channels.DatagramChannel.open()
                                socket = channel.socket()

                                if (!protect(socket)) {
                                    Log.e("Socet_err", "Не удалось защитить сокет")
                                    socket.close()
                                    channel.close()
                                    continue
                                }

                                val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
                                val udpOffset = ipHeaderLength
                                val dnsPayloadLength = length - (udpOffset + 8)

                                // отправляем запрос
                                val dnsPacket = java.net.DatagramPacket(
                                    packet, udpOffset + 8, dnsPayloadLength, dnsServer, 53
                                )
                                socket.send(dnsPacket)

                                // ждем ответ
                                socket.soTimeout = 2000
                                val responseBuffer = ByteArray(4096)
                                val responsePacket = java.net.DatagramPacket(responseBuffer, responseBuffer.size)

                                try {
                                    socket.receive(responsePacket)
                                } catch (e: java.net.SocketTimeoutException) {
                                    Log.w("VPN_error", "Таймаут DNS для $domain")
                                    socket.close()
                                    channel.close()
                                    continue
                                }

                                // создаем ответ для приложения
                                val response = PacketBuilder.createProxyResponse(
                                    packet, length, responsePacket.data, responsePacket.length
                                )

                                // отправляем обратно в TUN
                                if (response != null) {
                                    vpnOutput.write(response, 0, response.size)
                                    Log.d("MyVpnService", "DNS ответ получен и отправлен, размер: ${response.size}")
                                } else {
                                    Log.w("MyVpnService", "Не удалось собрать ответ для $domain")
                                }

                            } catch (e: Exception) {
                                Log.e("MyVpnService", "Ошибка проксирования DNS", e)
                            } finally {
                                try { socket?.close() } catch (e: Exception) {}
                                try { channel?.close() } catch (e: Exception) {}
                            }
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
