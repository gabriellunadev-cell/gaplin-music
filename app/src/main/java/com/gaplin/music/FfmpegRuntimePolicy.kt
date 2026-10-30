package com.gaplin.music

/**
 * Parses the feature table printed by `ffmpeg -muxers`.
 * A muxer is available only when its flags include E (encoding).
 */
internal object FfmpegRuntimePolicy {
    fun supportsComponent(output: String, componentName: String): Boolean {
        val expected = componentName.trim()
        if (expected.isEmpty()) return false
        return output.lineSequence().any { line ->
            val columns = line.trim().split(Regex("\\s+"))
            columns.size >= 2 && columns[1].equals(expected, ignoreCase = true)
        }
    }

    fun supportsMuxer(output: String, muxerName: String): Boolean {
        val expected = muxerName.trim()
        if (expected.isEmpty()) return false

        return output.lineSequence().any { line ->
            val columns = line.trim().split(Regex("\\s+"))
            columns.size >= 2 &&
                columns[0].contains('E') &&
                columns[1].equals(expected, ignoreCase = true)
        }
    }
}
