package io.github.umislat.aimesimulator.nfc

internal object FelicaCodec {
    const val READ_COMMAND: Byte = 0x06
    const val READ_RESPONSE: Byte = 0x07
    const val WRITE_COMMAND: Byte = 0x08
    const val WRITE_RESPONSE: Byte = 0x09
    const val READ_ONLY_SERVICE = 0x000B
    const val MAX_READ_BLOCKS = 15

    data class BlockAddress(val serviceIndex: Int, val blockNumber: Int)

    data class Request(
        val command: Byte,
        val nfcid2: ByteArray,
        val services: IntArray = IntArray(0),
        val blocks: List<BlockAddress> = emptyList()
    )

    data class ReadResult(
        val nfcid2: ByteArray,
        val status1: Int,
        val status2: Int,
        val blocks: List<ByteArray>
    ) {
        val succeeded: Boolean get() = status1 == 0 && status2 == 0
    }

    fun decodeRequest(frame: ByteArray): Request? {
        if (frame.size < 10 || (frame[0].toInt() and 0xFF) != frame.size) return null
        val command = frame[1]
        val nfcid2 = frame.copyOfRange(2, 10)
        if (command != READ_COMMAND && command != WRITE_COMMAND) return Request(command, nfcid2)
        if (frame.size < 12) return null

        val serviceCount = frame[10].toInt() and 0xFF
        if (serviceCount == 0) return null
        val servicesEnd = 11 + serviceCount * 2
        if (servicesEnd >= frame.size) return null
        val services = IntArray(serviceCount) { index ->
            val offset = 11 + index * 2
            (frame[offset].toInt() and 0xFF) or ((frame[offset + 1].toInt() and 0xFF) shl 8)
        }

        val count = frame[servicesEnd].toInt() and 0xFF
        if (count == 0) return null
        var cursor = servicesEnd + 1
        val blocks = ArrayList<BlockAddress>(count)
        repeat(count) {
            if (cursor >= frame.size) return null
            val descriptor = frame[cursor].toInt() and 0xFF
            val serviceIndex = descriptor and 0x0F
            if (serviceIndex >= serviceCount) return null
            val twoByteDescriptor = descriptor and 0x80 != 0
            val required = if (twoByteDescriptor) 2 else 3
            if (cursor + required > frame.size) return null
            val number = if (twoByteDescriptor) {
                frame[cursor + 1].toInt() and 0xFF
            } else {
                (frame[cursor + 1].toInt() and 0xFF) or
                    ((frame[cursor + 2].toInt() and 0xFF) shl 8)
            }
            blocks += BlockAddress(serviceIndex, number)
            cursor += required
        }
        val expectedEnd = if (command == WRITE_COMMAND) cursor + count * 16 else cursor
        if (expectedEnd != frame.size) return null
        return Request(command, nfcid2, services, blocks)
    }

    fun readResponse(
        nfcid2: ByteArray,
        blocks: List<ByteArray>,
        status1: Int = 0,
        status2: Int = 0
    ): ByteArray {
        require(nfcid2.size == 8)
        require(blocks.size <= MAX_READ_BLOCKS)
        val body = ByteArray(3 + blocks.size * 16)
        body[0] = status1.toByte()
        body[1] = status2.toByte()
        body[2] = blocks.size.toByte()
        blocks.forEachIndexed { index, block ->
            block.copyInto(body, 3 + index * 16, 0, minOf(16, block.size))
        }
        return response(READ_RESPONSE, nfcid2, body)
    }

    fun writeResponse(nfcid2: ByteArray, status1: Int = 0, status2: Int = 0): ByteArray =
        response(WRITE_RESPONSE, nfcid2, byteArrayOf(status1.toByte(), status2.toByte()))

    private fun response(command: Byte, nfcid2: ByteArray, body: ByteArray): ByteArray {
        require(nfcid2.size == 8)
        return ByteArray(10 + body.size).also { frame ->
            frame[0] = frame.size.toByte()
            frame[1] = command
            nfcid2.copyInto(frame, 2)
            body.copyInto(frame, 10)
        }
    }

    fun readRequest(nfcid2: ByteArray, blockNumbers: IntArray): ByteArray {
        require(nfcid2.size == 8)
        require(
            blockNumbers.isNotEmpty() &&
                blockNumbers.size <= MAX_READ_BLOCKS &&
                blockNumbers.all { it in 0..0xFF }
        )
        return ByteArray(14 + blockNumbers.size * 2).also { frame ->
            frame[0] = frame.size.toByte()
            frame[1] = READ_COMMAND
            nfcid2.copyInto(frame, 2)
            frame[10] = 1
            frame[11] = READ_ONLY_SERVICE.toByte()
            frame[12] = (READ_ONLY_SERVICE ushr 8).toByte()
            frame[13] = blockNumbers.size.toByte()
            blockNumbers.forEachIndexed { index, number ->
                frame[14 + index * 2] = 0x80.toByte()
                frame[15 + index * 2] = number.toByte()
            }
        }
    }

    fun decodeReadResponse(frame: ByteArray): ReadResult? {
        if (frame.size < 13 || (frame[0].toInt() and 0xFF) != frame.size || frame[1] != READ_RESPONSE) {
            return null
        }
        val blockCount = frame[12].toInt() and 0xFF
        if (frame.size != 13 + blockCount * 16) return null
        return ReadResult(
            nfcid2 = frame.copyOfRange(2, 10),
            status1 = frame[10].toInt() and 0xFF,
            status2 = frame[11].toInt() and 0xFF,
            blocks = List(blockCount) { index ->
                val offset = 13 + index * 16
                frame.copyOfRange(offset, offset + 16)
            }
        )
    }
}
