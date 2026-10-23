package com.example.mypain2

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream

class MyVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null

    private var vpnThread: Thread? = null
    @Volatile
    private var isRunning = false

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
    private fun readPackets() {
        val vpnInput = FileInputStream(vpnInterface!!.fileDescriptor)
        val packet = ByteArray(32767)

        while (isRunning) {
            try {
                val length = vpnInput.read(packet)

                if (length > 0) {
                    val version = (packet[0].toInt() shr 4) and 0x0F

                    if (version == 4) { // IPv4
                        val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
                        val protocol = packet[9].toInt() and 0xFF // 6 = TCP, 17 = UDP

                        // Извлечение IP назначения (байты 16..19)
                        val destIp =
                            "${packet[16].toUByte()}.${packet[17].toUByte()}.${packet[18].toUByte()}.${packet[19].toUByte()}"

                        if (protocol == 17) { // UDP
                            val srcPort = ((packet[ipHeaderLength].toInt() and 0xFF) shl 8) or
                                    (packet[ipHeaderLength + 1].toInt() and 0xFF)
                            val destPort = ((packet[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or
                                    (packet[ipHeaderLength + 3].toInt() and 0xFF)

                            // Если destPort == 53, перед нами DNS-запрос
                            if (destPort == 53) {
                                // пока просто проверяем что успешно поймали пакет
                                Log.d("MyVpnService", "Пойман DNS-пакет. Размер: $length байт")
                            }
                        }
                    }
                }
            }
            catch (e: Exception) {
                Log.e("MyVpnService", "Ошибка при чтении пакета", e)
                break
            }
        }
    }
}
