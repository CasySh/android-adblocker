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

    val blacklist = setOf("doubleclick.net", "googleads.g.doubleclick.net", "adservice.google.com")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("MyVpnService", "VPN service started")

        val builder = Builder()
        builder.setSession("My AdBlocker VPN")
        builder.addAddress("10.0.0.2", 24)
        builder.addRoute("0.0.0.0", 0)
        builder.addDnsServer("8.8.8.8")

        vpnInterface = builder.establish()

        if (vpnInterface == null) {
            Log.e("MyVpnService", "Failed to establish VPN")
        } else {
            Log.d("MyVpnService", "VPN established successfully")
            startForeground(1, createNotification())
        }

        // Запускаем чтение в отдельном потоке чтобы не блокировать сервис
        isRunning = true
        vpnThread = Thread {
            readPackets()
        }.apply { start() }

        return START_STICKY
    }
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        vpnThread?.interrupt()
        vpnInterface?.close()
        Log.d("MyVpnService", "VPN service destroyed")
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

                            // копируем входящий пакет
                            val response = packet.copyOf(length)

                            // меняем получателя и отправителя местами
                            for (i in 0 until 4) {
                                val temp = response[12 + i]
                                response[12 + i] = response[16 + i]
                                response[16 + i] = temp
                            }

                            // меняем UDP-порты местами
                            // ipHeaderLength нужен нам только здесь, для генерации ответа
                            val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
                            val udpOffset = ipHeaderLength
                            val tempPort1 = response[udpOffset]
                            val tempPort2 = response[udpOffset + 1]
                            response[udpOffset] = response[udpOffset + 2]
                            response[udpOffset + 1] = response[udpOffset + 3]
                            response[udpOffset + 2] = tempPort1
                            response[udpOffset + 3] = tempPort2

                            // меняем флаги DNS на NXDOMAIN
                            val dnsFlagsOffset = udpOffset + 8 + 2
                            response[dnsFlagsOffset] = 0x81.toByte()
                            response[dnsFlagsOffset + 1] = 0x83.toByte()

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
            } catch (e: Exception) {
                Log.e("MyVpnService", "Ошибка при чтении пакета", e)
                break
            }
        }
    }
}
