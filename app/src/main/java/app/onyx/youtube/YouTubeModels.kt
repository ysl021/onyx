package app.onyx.youtube

/** A single search hit on YouTube. */
data class YtSearchResult(
    val id: String,
    val url: String,
    val title: String,
    val channel: String,
    val durationMs: Long,
    val thumbnailUrl: String?,
)

data class YtSearchPage(
    val items: List<YtSearchResult>,
    /** innerTube continuation token for the "More" button, if YouTube sent one. */
    val next: String?,
)

/** Fully resolved video plus the audio stream that will be downloaded. */
data class YtVideo(
    val id: String,
    val url: String,
    val title: String,
    val channel: String,
    val durationMs: Long,
    val thumbnailUrl: String?,
    val description: String,
    val audioUrl: String,
    val audioExt: String,
    val audioBitrate: Int,
    /** Chapters YouTube itself exposes (also used when the description has none). */
    val chapters: List<YtSection> = emptyList(),
)

/**
 * One chapter of a long video. [endMs] always points at the next chapter or at
 * the end of the video, so a section is a ready-to-cut slice.
 */
data class YtSection(
    val title: String,
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/** One file that actually landed in the user's folder. */
data class SavedTrack(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String,
    val name: String,
    val savedAt: Long = System.currentTimeMillis(),
)

enum class JobStatus { Queued, Downloading, Splitting, Tagging, Saving, Done, Error }data class DownloadJob(
    val id: String,
    val videoId: String,
    val videoTitle: String,
    val channel: String,
    val sectionTitle: String,
    val index: Int,
    val total: Int,
    val startMs: Long,
    val endMs: Long,
    val status: JobStatus = JobStatus.Queued,
    val progress: Float = 0f,
    val message: String? = null,
    val outputName: String? = null,
)

data class YouTubeState(
    val jobs: List<DownloadJob> = emptyList(),
    val busy: Boolean = false,
) {
    val finished: Int get() = jobs.count { it.status == JobStatus.Done }
    val failed: Int get() = jobs.count { it.status == JobStatus.Error }
    val overallProgress: Float
        get() {
            if (jobs.isEmpty()) return 0f
            return jobs.sumOf { if (it.status == JobStatus.Done) 1.0 else it.progress.toDouble() }
                .div(jobs.size)
                .toFloat()
        }
}
