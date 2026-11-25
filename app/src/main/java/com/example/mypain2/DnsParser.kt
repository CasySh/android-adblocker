package com.example.mypain2


class DnsParser {
    companion object {
        private const val IPV4 = 4
        private const val IPV6 = 6
        private const val PROTOCOL_UDP = 17
        private const val PROTOCOL_TCP = 6
        private const val PORT_DNS = 53
        private const val MIN_IPV4_HEADER_LENGTH = 20
        private const val UDP_HEADER_LENGTH = 8
        private const val DNS_HEADER_LENGTH = 12
        private const val MAX_POINTER_JUMPS = 10
    }

    // Возвращает домен или null, если пакет нам не подходит
    fun parsePacket(packet: ByteArray, length: Int): String? {
        if (length < MIN_IPV4_HEADER_LENGTH || length > packet.size) return null

        val firstByte = packet[0].toInt() and 0xFF
        val version = firstByte ushr 4

        if (version == IPV4) {

            val ipHeaderLength = (firstByte and 0x0F) * 4
            if (ipHeaderLength < MIN_IPV4_HEADER_LENGTH || ipHeaderLength >= length) return null

            val protocol = packet[9].toInt() and 0xFF

            if (protocol == PROTOCOL_UDP) {
                return readUdp(packet, length, ipHeaderLength)
            }
        }
        //выключен на уровне vpn и игнорируется
        if(version == IPV6){
            return null
        }
        return null
    }

    private fun readUdp(packet: ByteArray, packetLength: Int, ipHeaderLength: Int): String? {
        val indexDns = ipHeaderLength + UDP_HEADER_LENGTH
        if (indexDns + DNS_HEADER_LENGTH > packetLength) return null

        val destinationPort = ((packet[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or
                (packet[ipHeaderLength + 3].toInt() and 0xFF)

        if (destinationPort != PORT_DNS) return null

        return readDns(packet, packetLength, indexDns)
    }

    private fun readDns(packet: ByteArray, packetLength: Int, indexDns: Int): String? {
        // проверяем что это DNS-запрос (бит QR == 0)
        val flagsHigh = packet[indexDns + 2].toInt() and 0xFF
        val isResponse = (flagsHigh and 0x80) != 0
        if (isResponse) return null

        // проверяем что в пакете есть хотя бы один вопрос (QDCOUNT > 0)
        val qdCount = ((packet[indexDns + 4].toInt() and 0xFF) shl 8) or
                (packet[indexDns + 5].toInt() and 0xFF)
        if (qdCount == 0) return null

        var parseOffset = indexDns + DNS_HEADER_LENGTH
        val domainBuilder = StringBuilder()
        var jumps = 0

        while (true) {
            if (jumps > MAX_POINTER_JUMPS || parseOffset >= packetLength) return null

            val lengthOrPointer = packet[parseOffset].toInt() and 0xFF
            if (lengthOrPointer == 0) break

            val labelType = lengthOrPointer and 0xC0
            if (labelType == 0xC0) {
                if (parseOffset + 1 >= packetLength) return null
                val nextByte = packet[parseOffset + 1].toInt() and 0xFF
                val pointerOffset = ((lengthOrPointer and 0x3F) shl 8) or nextByte
                parseOffset = indexDns + pointerOffset
                jumps++
                continue
            }
            else if (labelType != 0) {
                return null
            }

            val labelLength = lengthOrPointer and 0x3F
            parseOffset++
            if (parseOffset + labelLength > packetLength) return null

            if (domainBuilder.isNotEmpty()) domainBuilder.append('.')
            domainBuilder.append(String(packet, parseOffset, labelLength, Charsets.US_ASCII))
            parseOffset += labelLength
        }
        val domain = domainBuilder.toString().lowercase()

        if (domain.isEmpty()) return null
        return domain
    }
}