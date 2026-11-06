package com.cinemahall.core.library

import com.cinemahall.core.media.MediaSignature

object MovieCollection {

    fun siblings(
        snapshot: MovieLibrarySnapshot,
        current: MediaSignature?,
        progressBook: MovieProgressBook,
    ): List<MovieEntry> {
        if (current == null) return emptyList()
        val entry = snapshot.movies.firstOrNull { it.signature == current } ?: return emptyList()
        val arranged = MovieOrdering.arrange(snapshot.moviesIn(entry.categoryId), progressBook)
        val (unfinished, finished) = MovieWatchStatus.partition(arranged, progressBook)
        return unfinished + finished
    }

    fun next(
        snapshot: MovieLibrarySnapshot,
        current: MediaSignature?,
        progressBook: MovieProgressBook,
    ): MovieEntry? {
        val movies = siblings(snapshot, current, progressBook)
        val index = movies.indexOfFirst { it.signature == current }
        if (index < 0) return null

        val mark = MovieOrdering.parseEpisode(movies[index].displayName)
        val episode = mark?.episode
        if (mark != null && episode != null) {
            val following = movies.firstOrNull { candidate ->
                val parsed = MovieOrdering.parseEpisode(candidate.displayName)
                parsed != null && parsed.seriesKey == mark.seriesKey && parsed.episode == episode + 1
            }
            if (following != null) return following
        }

        return movies.getOrNull(index + 1)
    }
}
