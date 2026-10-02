package com.theveloper.pixelplay.utils

import java.text.Normalizer
import kotlin.math.abs

/**
 * Detects songs that are the same track even though they come from different sources
 * (e.g. a file downloaded from a Telegram channel into local storage and the very same
 * audio message streamed from that channel).
 *
 * Two songs are considered duplicates when their normalized titles are identical, their
 * durations are within [DURATION_TOLERANCE_MS] and their artists are compatible.
 *
 * Songs without tags usually get their file name as title (e.g. "Track.mp3"). Their artist
 * is meaningless, so the audio extension is ignored for the title comparison and the
 * artist check is skipped for them.
 */
class DuplicateSongMatcher {

    private data class Entry(
        val artist: String,
        val artistReliable: Boolean,
        val durationMs: Long
    )

    private val entriesByTitle = HashMap<String, MutableList<Entry>>()

    fun add(title: String, artist: String, durationMs: Long) {
        val key = titleKey(title)
        if (key.isEmpty()) return
        entriesByTitle.getOrPut(key) { mutableListOf() }.add(
            Entry(
                artist = normalizeForMatching(artist),
                artistReliable = !hasAudioExtension(title),
                durationMs = durationMs
            )
        )
    }

    fun isDuplicate(title: String, artist: String, durationMs: Long): Boolean {
        val key = titleKey(title)
        if (key.isEmpty()) return false
        val candidates = entriesByTitle[key] ?: return false
        val normalizedArtist = normalizeForMatching(artist)
        val artistReliable = !hasAudioExtension(title)
        return candidates.any { candidate ->
            durationsMatch(candidate.durationMs, durationMs) &&
                (!candidate.artistReliable || !artistReliable ||
                    artistsCompatible(candidate.artist, normalizedArtist, key))
        }
    }

    companion object {
        const val DURATION_TOLERANCE_MS = 2_000L

        private val UNKNOWN_ARTIST_MARKERS = setOf("unknown", "unknownartist", "نامشخص", "ناشناس")

        private val AUDIO_EXTENSION_REGEX = Regex(
            "\\.(mp3|m4a|m4b|aac|flac|ogg|oga|opus|wav|wma|aif|aiff|alac|ape|wv|mka)\\s*$",
            RegexOption.IGNORE_CASE
        )

        internal fun hasAudioExtension(title: String): Boolean =
            AUDIO_EXTENSION_REGEX.containsMatchIn(title)

        private fun titleKey(title: String): String =
            normalizeForMatching(title.replace(AUDIO_EXTENSION_REGEX, ""))

        internal fun durationsMatch(a: Long, b: Long): Boolean {
            // A missing duration (0) can't be used to prove two songs differ.
            if (a <= 0L || b <= 0L) return true
            return abs(a - b) <= DURATION_TOLERANCE_MS
        }

        internal fun artistsCompatible(a: String, b: String, titleKey: String = ""): Boolean {
            if (isUnknownArtist(a) || isUnknownArtist(b)) return true
            if (a == b || a.contains(b) || b.contains(a)) return true
            // e.g. title "Alaki (BLH Remix)" with artist tag "BLH Remix".
            return titleKey.isNotEmpty() &&
                (titleKey.contains(a) || titleKey.contains(b))
        }

        private fun isUnknownArtist(normalized: String): Boolean =
            normalized.isEmpty() || normalized in UNKNOWN_ARTIST_MARKERS

        /**
         * Lowercases, strips diacritics/punctuation/whitespace and unifies Arabic/Persian letter
         * variants so tag differences such as "Song - Name" vs "song name" still match.
         */
        internal fun normalizeForMatching(value: String): String {
            if (value.isBlank()) return ""
            val decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD)
            val sb = StringBuilder(decomposed.length)
            for (ch in decomposed) {
                val mapped = when (ch) {
                    'ي', 'ى' -> 'ی'
                    'ك' -> 'ک'
                    'ة' -> 'ه'
                    else -> ch
                }
                if (Character.isLetterOrDigit(mapped)) {
                    sb.append(mapped.lowercaseChar())
                }
            }
            return sb.toString()
        }
    }
}
