package com.example.mypain2

import android.util.Log

object PacketBuilder {

    // возвращает готовый ответ для заблокированного домена (NXDOMAIN)
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

    // возвращает готовый ответ для нормального домена
    fun createProxyResponse(originalPacket: ByteArray, originalLength: Int, dnsResponseData: ByteArray, dnsResponseLength: Int
    ): ByteArray {

        val ipHeaderLength = (originalPacket[0].toInt() and 0x0F) * 4
        val udpOffset = ipHeaderLength
        val newTotalLength = ipHeaderLength + 8 + dnsResponseLength

        val response = ByteArray(newTotalLength)

        // копируем IP-заголовок
        System.arraycopy(originalPacket, 0, response, 0, ipHeaderLength)

        // меняем IP-адреса
        for (i in 0 until 4) {
            val temp = response[12 + i]
            response[12 + i] = response[16 + i]
            response[16 + i] = temp
        }

        // копируем UDP-заголовок
        System.arraycopy(originalPacket, udpOffset, response, udpOffset, 8)

        // меняем порты
        val tempPort1 = response[udpOffset]
        val tempPort2 = response[udpOffset + 1]
        response[udpOffset] = response[udpOffset + 2]
        response[udpOffset + 1] = response[udpOffset + 3]
        response[udpOffset + 2] = tempPort1
        response[udpOffset + 3] = tempPort2

        // обновляем длины IP
        response[2] = ((newTotalLength shr 8) and 0xFF).toByte()
        response[3] = (newTotalLength and 0xFF).toByte()

        // обновляем длины UDP
        val newUdpLength = 8 + dnsResponseLength
        response[udpOffset + 4] = ((newUdpLength shr 8) and 0xFF).toByte()
        response[udpOffset + 5] = (newUdpLength and 0xFF).toByte()

        // копируем данные ответа
        System.arraycopy(dnsResponseData, 0, response, udpOffset + 8, dnsResponseLength)

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