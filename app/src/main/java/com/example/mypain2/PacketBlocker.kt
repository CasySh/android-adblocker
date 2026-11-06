package com.example.mypain2

object PacketBlocker {

    // возвращает готовый ответ NXDOMAIN
    fun createBlockResponse(packet: ByteArray, length: Int): ByteArray {

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

        return response
    }
}