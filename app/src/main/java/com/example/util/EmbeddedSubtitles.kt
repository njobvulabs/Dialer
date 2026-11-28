package com.example.util

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ForwardingExtractorOutput
import androidx.media3.extractor.NoOpExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.CueDecoder
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleTranscodingExtractor
import java.io.ByteArrayOutputStream
import java.io.File

/** One parsed subtitle cue, in the same shape the player overlay already uses. */

data class EmbeddedCue(val startMs: Long, val endMs: Long, val text: String)

/** A subtitle track that was pulled out of the video container itself. */
data class EmbeddedSubtitleTrack(val label: String, val cues: List<EmbeddedCue>)

/**
 * Reads the subtitle track that is *inside* a video file and hands it back as plain cues.
 *
 * Why this exists: [android.media.MediaPlayer] can list a container's subtitle track
 * (`MEDIA_TRACK_TYPE_SUBTITLE`) but has no code path that ever draws cue text, so a
 * MKV/MP4 carrying an internal subtitle track simply plays with no captions on this
 * player. The player screen already knows how to render cues (it does that for sidecar
 * .srt/.vtt files), so the fix is to decode the embedded track into that same cue list
 * rather than replace the renderer or swap the player out.
 *
 * The container is walked with Media3's own text stack, the same one ExoPlayer's
 * TextRenderer uses: [SubtitleTranscodingExtractor] normalises whatever the file
 * carries (SubRip, WebVTT, TTML, SSA/ASS, SAMI, MicroDVD, QuickTime tx3g) into Media3's
 * own cue bundle, and [CueDecoder] turns each sample of that bundle back into cues.
 * Only the text track is read - video and audio samples are dropped, not decoded - and
 * the whole thing is bounded and best-effort: any failure returns null, which leaves
 * the player exactly where it was. Bitmap subtitles (PGS/VobSub/DVB) carry no text, so
 * they legitimately produce nothing.
 */
object EmbeddedSubtitles {

    /** Stop walking a container that will not give up its subtitle track. */
    private const val MAX_INPUT_BYTES = 32L * 1024 * 1024
    private const val MAX_MILLIS = 8_000L
    private const val MAX_ITERATIONS = 20_000
    private const val MAX_CUES = 20_000

    /**
     * Blocking. Call from a background dispatcher. Returns the first text track that
     * yields cues, or null when the file has no usable embedded subtitle.
     */
    fun load(file: File): EmbeddedSubtitleTrack? {
        if (!file.isFile || file.length() <= 0L) return null
        val started = System.currentTimeMillis()
        val dataSource = FileDataSource()
        val sinks = ArrayList<TextSink>()

        try {
            dataSource.open(
                DataSpec.Builder()
                    .setUri(Uri.fromFile(file))
                    .setPosition(0L)
                    .setLength(file.length())
                    .build()
            )
            val input = DefaultExtractorInput(FileDataReader(dataSource), 0L, file.length())
            val extractor = sniff(input) ?: return null
            val output = TextTrackOutput(sinks)
            val transcoding = SubtitleTranscodingExtractor(extractor, DefaultSubtitleParserFactory())
            transcoding.init(output)
            walk(transcoding, input, started)
        } catch (_: Throwable) {
            return null
        } finally {
            runCatching { dataSource.close() }
        }

        return sinks
            .asSequence()
            .mapNotNull { it.finish() }
            .firstOrNull { it.cues.isNotEmpty() }
    }

    /** Picks the extractor that recognises this file, exactly as ExoPlayer does. */
    private fun sniff(input: ExtractorInput): Extractor? {
        val candidates = DefaultExtractorsFactory().createExtractors()
        for (candidate in candidates) {
            val matched = try {
                candidate.sniff(input)
            } catch (_: Throwable) {
                false
            }
            if (matched) return candidate
        }
        return null
    }

    private fun walk(extractor: Extractor, input: ExtractorInput, started: Long) {
        val positionHolder = PositionHolder()
        var iterations = 0
        while (iterations++ < MAX_ITERATIONS) {
            if (input.position >= MAX_INPUT_BYTES) break
            if (System.currentTimeMillis() - started > MAX_MILLIS) break
            val result = extractor.read(input, positionHolder)
            if (result == Extractor.RESULT_END_OF_INPUT) break
            if (result == Extractor.RESULT_SEEK) {
                // Only expected right at the start (some extractors rewind once to
                // build their index). Anything later means we have what we need.
                if (input.position > 0L) break
                extractor.seek(0L, 0L)
            }
        }
    }

    private fun labelOf(format: Format): String {
        val language = format.language
        val name = format.label
        return when {
            !name.isNullOrBlank() && !language.isNullOrBlank() -> "$name ($language)"
            !name.isNullOrBlank() -> name
            !language.isNullOrBlank() -> language
            else -> "Embedded track"
        }
    }

    /** SubRip/SSA text arrives with the source's own line breaks and style blocks. */
    private fun cleanText(raw: CharSequence?): String {
        var text = raw?.toString().orEmpty()
        if (text.contains("{\\") || text.contains("{\\an8}")) {
            text = text.replace(Regex("""\{[^}]*}"""), "")
        }
        text = text.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
        return text.trim()
    }

    private class FileDataReader(private val dataSource: FileDataSource) : DataReader {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            dataSource.read(buffer, offset, length)
    }

    /** Text tracks only; everything else is handed to Media3's no-op output. */
    private class TextTrackOutput(private val sinks: MutableList<TextSink>) :
        ForwardingExtractorOutput(NoOpExtractorOutput()) {

        private val noOp = NoOpExtractorOutput()
        private val byTrack = HashMap<Int, TextSink>()

        override fun track(track: Int, trackType: Int): TrackOutput {
            if (trackType != C.TRACK_TYPE_TEXT) return noOp.track(track, trackType)
            return byTrack.getOrPut(track) {
                TextSink().also(sinks::add)
            }
        }
    }

    /**
     * Collects one track's samples. Because the transcoding extractor is in the chain,
     * what arrives is already Media3 cue-bundle data, so each sample is decoded with
     * [CueDecoder] against the sample time reported by [sampleMetadata].
     */
    private class TextSink : TrackOutput {

        private var format: Format? = null
        private var sampleTimeUs = 0L
        private val pending = ByteArrayOutputStream()
        private val collected = ArrayList<EmbeddedCue>()
        private val decoder = CueDecoder()
        private val readBuffer = ByteArray(8 * 1024)

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleMetadata(
            sampleTimeUs: Long,
            flags: Int,
            dataOffset: Int,
            dataSize: Int,
            cryptoData: TrackOutput.CryptoData?
        ) {
            flush()
            this.sampleTimeUs = sampleTimeUs
        }

        override fun sampleData(input: DataReader, size: Int, allowPartialReads: Boolean, partFlags: Int): Int {
            var left = size
            while (left > 0) {
                val chunk = minOf(left, readBuffer.size)
                val read = input.read(readBuffer, 0, chunk)
                if (read <= 0) return size - left
                pending.write(readBuffer, 0, read)
                left -= read
            }
            return size
        }

        override fun sampleData(input: ParsableByteArray, offset: Int, size: Int) {
            pending.write(input.data, offset, size)
        }

        private fun flush() {
            if (pending.size() == 0) return
            val bytes = pending.toByteArray()
            pending.reset()
            // Only transcoded (text) tracks are decodable; a raw bitmap subtitle track
            // is forwarded untouched and is skipped here.
            if (format?.sampleMimeType != MimeTypes.APPLICATION_MEDIA3_CUES) return
            if (collected.size >= MAX_CUES) return
            val timed = runCatching { decoder.decode(sampleTimeUs, bytes, 0, bytes.size) }
                .getOrNull() ?: return
            val startUs = timed.startTimeUs
            val endUs = if (timed.endTimeUs > startUs) timed.endTimeUs else startUs + 1_000_000L
            for (cue in timed.cues) {
                val text = cleanText(cue.text)
                if (text.isEmpty()) continue
                collected.add(EmbeddedCue(startUs / 1000L, endUs / 1000L, text))
            }
        }

        fun finish(): EmbeddedSubtitleTrack? {
            flush()
            if (collected.isEmpty()) return null
            collected.sortBy { it.startMs }
            return EmbeddedSubtitleTrack(format?.let { labelOf(it) } ?: "Embedded track", collected.toList())
        }
    }
}
