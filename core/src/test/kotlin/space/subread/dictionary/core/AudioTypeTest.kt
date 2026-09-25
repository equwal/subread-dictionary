package space.subread.dictionary.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioTypeTest {

    private fun bytes(vararg parts: Any): ByteArray = parts.flatMap { part ->
        when (part) {
            is String -> part.toByteArray(Charsets.ISO_8859_1).toList()
            is Int -> listOf(part.toByte())
            else -> error("a string or a byte")
        }
    }.toByteArray()

    @Test
    fun theFirstBytesNameTheFormat() {
        assertEquals("ogg", AudioType.extension(bytes("OggS", 0, 2, 0)))
        assertEquals("wav", AudioType.extension(bytes("RIFF", 1, 2, 3, 4, "WAVEfmt ")))
        assertEquals("flac", AudioType.extension(bytes("fLaC", 0)))
        assertEquals("m4a", AudioType.extension(bytes(0, 0, 0, 24, "ftypM4A ")))
        assertEquals("mp3", AudioType.extension(bytes("ID3", 3, 0)))
        assertEquals("mp3", AudioType.extension(bytes(0xFF, 0xFB, 0x90)))
    }

    @Test
    fun bytesThatSayNothingCountAsMp3() {
        assertEquals("mp3", AudioType.extension(ByteArray(0)))
        assertEquals("mp3", AudioType.extension(bytes("RIFF", 1, 2, 3, 4, "AVI ")))
    }

    @Test
    fun eachExtensionHasItsMime() {
        assertEquals("audio/mpeg", AudioType.mime("mp3"))
        assertEquals("audio/ogg", AudioType.mime("ogg"))
        assertEquals("audio/wav", AudioType.mime("wav"))
        assertEquals("audio/flac", AudioType.mime("flac"))
        assertEquals("audio/mp4", AudioType.mime("m4a"))
        assertEquals("application/octet-stream", AudioType.mime("bin"))
    }
}
