package com.codex.im.protocol

import java.util.zip.CRC32

object Crc32 {
    fun of(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Int {
        val crc = CRC32()
        crc.update(bytes, offset, length)
        return crc.value.toInt()
    }
}
