package com.example.mypain2

object PacketBlocker {

    // возвращает готовый ответ NXDOMAIN
    fun createBlockResponse(packet: ByteArray, length: Int): ByteArray {
        val response = packet.copyOf(length)

        // меняем получателя и отправителя местами
        for (i in 0 until 4) {
            val temp = response[12 + i]
            response[12 + i] = response[16 + i]
            response[16 + i] = temp
        }

        // меняем UDP-порты местами
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

        // пересчитываем контрольную сумму
        calculateIpChecksum(response, ipHeaderLength)

        // для UDP в IPv4 контрольную сумму можно поставить 0
        response[udpOffset + 6] = 0
        response[udpOffset + 7] = 0

        return response
    }

    // пересчет контрольной суммы IP-заголовка
    fun calculateIpChecksum(packet: ByteArray, ipHeaderLength: Int) {
        // обнуляем старое значение
        packet[10] = 0
        packet[11] = 0

        var sum = 0L
        var i = 0
        while (i < ipHeaderLength) {
            if (i == 10) { // пропускаем байты checksum
                i += 2
                continue
            }
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }

        // складываем переносы
        while ((sum shr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        // инвертируем и записываем обратно
        val checksum = (sum.inv() and 0xFFFF).toInt()
        packet[10] = (checksum shr 8).toByte()
        packet[11] = checksum.toByte()
    }
}