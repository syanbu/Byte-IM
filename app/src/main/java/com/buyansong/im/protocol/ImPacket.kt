package com.buyansong.im.protocol

data class ImPacket(
    val cmd: Int,
    val body: ByteArray,
    val version: Byte = ImPacketCodec.VERSION
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImPacket) return false
        return cmd == other.cmd && version == other.version && body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        var result = cmd
        result = 31 * result + body.contentHashCode()
        result = 31 * result + version
        return result
    }
}
