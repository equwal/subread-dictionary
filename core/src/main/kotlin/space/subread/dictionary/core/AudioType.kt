package space.subread.dictionary.core

/** The format of an audio file, from its first bytes: the audio sources give bytes and no name. */
object AudioType {

    /** The file extension: mp3, ogg, wav, flac or m4a. Bytes that say nothing count as mp3. */
    fun extension(bytes: ByteArray): String {
        fun startsWith(prefix: String, at: Int = 0) = bytes.size >= at + prefix.length &&
            prefix.indices.all { bytes[at + it] == prefix[it].code.toByte() }
        return when {
            startsWith("OggS") -> "ogg"
            startsWith("RIFF") && startsWith("WAVE", 8) -> "wav"
            startsWith("fLaC") -> "flac"
            startsWith("ftyp", 4) -> "m4a"
            else -> "mp3"
        }
    }

    fun mime(extension: String): String = when (extension) {
        "mp3" -> "audio/mpeg"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "m4a" -> "audio/mp4"
        else -> "application/octet-stream"
    }
}
