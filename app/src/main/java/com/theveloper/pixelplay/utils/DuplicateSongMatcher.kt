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
 * Artists are compared credit by credit ("A feat. B" -> {A, B}); two artist fields are
 * compatible when they share at least one whole credit. Substrings never count, so "Queen"
 * does not match "Queens of the Stone Age".
 *
 * Songs without tags usually get their file name as title (e.g. "Track.mp3"). Their artist
 * is meaningless, so the audio extension is ignored for the title comparison and the
 * artist check is skipped for them.
 */
class DuplicateSongMatcher {

    private class Entry(
        val credits: Set<String>,
        val bracketSegments: Set<String>,
        val artistReliable: Boolean,
        val durationMs: Long
    )

    private val entriesByTitle = HashMap<String, MutableList<Entry>>()

    fun add(title: String, artist: String, durationMs: Long) {
        val entry = buildEntry(title, artist, durationMs) ?: return
        entriesByTitle.getOrPut(titleKey(title)) { mutableListOf() }.add(entry)
    }

    fun isDuplicate(title: String, artist: String, durationMs: Long): Boolean {
        val candidates = entriesByTitle[titleKey(title)] ?: return false
        val probe = buildEntry(title, artist, durationMs) ?: return false
        return candidates.any { candidate ->
            durationsMatch(candidate.durationMs, probe.durationMs) &&
                (!candidate.artistReliable || !probe.artistReliable ||
                    artistsCompatible(candidate, probe))
        }
    }

    private fun buildEntry(title: String, artist: String, durationMs: Long): Entry? {
        if (titleKey(title).isEmpty()) return null
        return Entry(
            credits = splitCredits(artist),
            bracketSegments = bracketSegments(title),
            artistReliable = !hasAudioExtension(title),
            durationMs = durationMs
        )
    }

    companion object {
        const val DURATION_TOLERANCE_MS = 2_000L

        private val UNKNOWN_ARTIST_MARKERS = setOf("unknown", "unknownartist", "نامشخص", "ناشناس")

        private val AUDIO_EXTENSION_REGEX = Regex(
            "\\.(mp3|m4a|m4b|aac|flac|ogg|oga|opus|wav|wma|aif|aiff|alac|ape|wv|mka)\\s*$",
            RegexOption.IGNORE_CASE
        )

        private val CREDIT_SEPARATOR_REGEX = Regex(
            "[,;&/|+×،]|\\s+(?:x|and|with|feat\\.?|ft\\.?|featuring)\\s+",
            RegexOption.IGNORE_CASE
        )

        private val BRACKET_SEGMENT_REGEX = Regex("[(\\[{]([^)\\]}]*)[)\\]}]")

        // Words that may follow an artist name inside a bracketed title segment,
        // e.g. "(BLH Remix)" credits the artist "BLH".
        private val SEGMENT_SUFFIXES = listOf("remix", "mix", "edit", "version", "cover")

        internal fun hasAudioExtension(title: String): Boolean =
            AUDIO_EXTENSION_REGEX.containsMatchIn(title)

        private fun titleKey(title: String): String =
            normalizeForMatching(title.replace(AUDIO_EXTENSION_REGEX, ""))

        internal fun durationsMatch(a: Long, b: Long): Boolean {
            // A missing duration (0) can't be used to prove two songs differ.
            if (a <= 0L || b <= 0L) return true
            return abs(a - b) <= DURATION_TOLERANCE_MS
        }

        private fun splitCredits(artist: String): Set<String> =
            artist.split(CREDIT_SEPARATOR_REGEX)
                .map { normalizeForMatching(it) }
                .filter { it.isNotEmpty() }
                .toSet()

        private fun bracketSegments(title: String): Set<String> {
            val segments = LinkedHashSet<String>()
            BRACKET_SEGMENT_REGEX.findAll(title.replace(AUDIO_EXTENSION_REGEX, "")).forEach { match ->
                val segment = normalizeForMatching(match.groupValues[1])
                if (segment.isEmpty()) return@forEach
                segments.add(segment)
                SEGMENT_SUFFIXES.forEach { suffix ->
                    if (segment.endsWith(suffix) && segment.length > suffix.length) {
                        segments.add(segment.removeSuffix(suffix))
                    }
                }
            }
            return segments
        }

        private fun isUnknown(credits: Set<String>): Boolean =
            credits.isEmpty() || credits.all { it in UNKNOWN_ARTIST_MARKERS }

        private fun artistsCompatible(a: Entry, b: Entry): Boolean {
            if (isUnknown(a.credits) || isUnknown(b.credits)) return true
            if (a.credits.any { it in b.credits }) return true
            // The artist tag of one song may only appear as a credit in the other's title,
            // e.g. title "Alaki (BLH Remix)" with artist tag "BLH Remix".
            return b.credits.any { it in a.bracketSegments } ||
                a.credits.any { it in b.bracketSegments }
        }

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
