package com.neurotube.app.account

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import com.neurotube.app.R
import com.neurotube.app.data.engagement.FeedInvalidationBus
import com.neurotube.app.data.local.LikedVideosRepository
import com.neurotube.app.data.local.PlaylistRepository
import com.neurotube.app.data.local.SubscriptionRepository
import com.neurotube.app.data.local.ViewHistory
import com.neurotube.app.data.model.Channel
import com.neurotube.app.data.model.Video
import com.neurotube.app.data.music.YouTubeMusicService
import com.neurotube.app.data.music.model.PlaylistDetails
import com.neurotube.app.data.recommendation.FlowNeuroEngine
import com.neurotube.app.innertube.YouTube
import com.neurotube.app.innertube.models.ArtistItem
import com.neurotube.app.innertube.models.YTItem
import com.neurotube.app.innertube.pages.AccountVideoCollection
import com.neurotube.app.innertube.utils.completed
import com.neurotube.app.ui.screens.music.saveMusicCollection
import com.neurotube.app.utils.ImportDiagnostics
import com.neurotube.app.utils.ImportStepDiagnostic
import com.neurotube.app.utils.describeFailure
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AccountLibraryImport"

/** The music library's browse ids, one per kind of thing a viewer keeps there. */
private const val LIBRARY_LIKED_SONGS = "FEmusic_liked_videos"
private const val LIBRARY_ARTISTS = "FEmusic_library_corpus_artists"
private const val LIBRARY_PLAYLISTS = "FEmusic_liked_playlists"
private const val LIBRARY_ALBUMS = "FEmusic_liked_albums"

/** How many channel pages to read before giving up, so a loop can never run forever. */
private const val MAX_CHANNEL_PAGES = 40

/** How many lookups to run at once. Each collection is a full playlist, so this stays small. */
private const val PARALLELISM = 3

/** The parts of a library, in the order they are brought in. */
enum class AccountImportStep {
    SUBSCRIPTIONS,
    ARTISTS,
    LIKED_SONGS,
    PLAYLISTS,
    ALBUMS,
    VIDEO_PLAYLISTS,
    WATCH_LATER,
    HISTORY,
}

/** The string that names one part of an import, shared by the page and the progress notification. */
@StringRes
internal fun AccountImportStep.titleRes(): Int =
    when (this) {
        AccountImportStep.SUBSCRIPTIONS -> R.string.youtube_account_import_step_subscriptions
        AccountImportStep.ARTISTS -> R.string.youtube_account_import_step_artists
        AccountImportStep.LIKED_SONGS -> R.string.youtube_account_import_step_liked_songs
        AccountImportStep.PLAYLISTS -> R.string.youtube_account_import_step_playlists
        AccountImportStep.ALBUMS -> R.string.youtube_account_import_step_albums
        AccountImportStep.VIDEO_PLAYLISTS -> R.string.youtube_account_import_step_video_playlists
        AccountImportStep.WATCH_LATER -> R.string.youtube_account_import_step_watch_later
        AccountImportStep.HISTORY -> R.string.youtube_account_import_step_history
    }

/** Progress through an import, so the screen can say what it is doing. */
sealed interface AccountImportState {
    data object Idle : AccountImportState

    /**
     * A part is running. [current]/[total] count the items of the parts that save one by one
     * (playlists, albums); they are 0/0 for the parts that are a single bulk write, which keeps the
     * bar indeterminate rather than inventing a denominator.
     */
    data class Running(
        val step: AccountImportStep,
        val current: Int = 0,
        val total: Int = 0,
    ) : AccountImportState

    data class Done(
        val report: AccountImportReport,
    ) : AccountImportState

    data object Failed : AccountImportState
}

/**
 * Brings a signed-in YouTube account's library into the app: the channels and artists it follows,
 * the songs it liked, and the playlists and albums it saved.
 *
 * Everything lands in the same stores the rest of the app reads, so an import needs no screen of
 * its own — the subscriptions list, Liked music and the music library show the result. Each part is
 * attempted on its own, so one failing leaves the others in place, and the report counts only what
 * was actually written.
 */
@Singleton
class AccountLibraryImport
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val subscriptions: SubscriptionRepository,
        private val likedVideos: LikedVideosRepository,
        private val musicPlaylists: PlaylistRepository,
        private val viewHistory: ViewHistory,
    ) {
        /**
         * Reads the account's library and stores it. [onProgress] is called as each part begins and,
         * for the parts that save items one by one, again as each item is written; the report counts
         * what each part added.
         */
        suspend fun run(onProgress: (AccountImportState.Running) -> Unit = {}): AccountImportReport {
            var report = AccountImportReport()
            val steps = mutableListOf<ImportStepDiagnostic>()
            ImportDiagnostics.begin()
            try {
                onProgress(AccountImportState.Running(AccountImportStep.SUBSCRIPTIONS))
                val channels = fetchPart(AccountImportStep.SUBSCRIPTIONS, steps) { fetchSubscriptions() }
                if (channels.isNotEmpty()) {
                    runCatching { subscriptions.subscribeAll(channels.toSubscribedChannels()) }
                        .onSuccess { report = report.copy(channels = channels.size) }
                        .onFailure { Log.w(TAG, "Importing channels failed", it) }
                }

                onProgress(AccountImportState.Running(AccountImportStep.ARTISTS))
                val artists = fetchPart(AccountImportStep.ARTISTS, steps) { fetchLibrary(LIBRARY_ARTISTS) }
                if (artists.isNotEmpty()) {
                    runCatching {
                        val music = artists.toSubscribedArtists(resolveArtistChannels(artists))
                        subscriptions.subscribeAll(music)
                        music.size
                    }.onSuccess { report = report.copy(artists = it) }
                        .onFailure { Log.w(TAG, "Importing artists failed", it) }
                }

                onProgress(AccountImportState.Running(AccountImportStep.LIKED_SONGS))
                val songs = fetchPart(AccountImportStep.LIKED_SONGS, steps) { fetchLibrary(LIBRARY_LIKED_SONGS) }
                if (songs.isNotEmpty()) {
                    runCatching { likedVideos.importLikes(songs.toLikedSongs(System.currentTimeMillis())) }
                        .onSuccess { report = report.copy(likedSongs = it.size) }
                        .onFailure { Log.w(TAG, "Importing liked songs failed", it) }
                }

                onProgress(AccountImportState.Running(AccountImportStep.PLAYLISTS))
                val collections = fetchPart(AccountImportStep.PLAYLISTS, steps) { fetchLibrary(LIBRARY_PLAYLISTS) }
                if (collections.isNotEmpty()) {
                    val saved =
                        saveCollections(collections.toSavedMusicCollections(), AccountImportStep.PLAYLISTS, onProgress)
                    report = report.copy(playlists = saved)
                }

                onProgress(AccountImportState.Running(AccountImportStep.ALBUMS))
                val albums = fetchPart(AccountImportStep.ALBUMS, steps) { fetchLibrary(LIBRARY_ALBUMS) }
                if (albums.isNotEmpty()) {
                    val saved = saveCollections(albums.toSavedMusicCollections(), AccountImportStep.ALBUMS, onProgress)
                    report = report.copy(albums = saved)
                }

                onProgress(AccountImportState.Running(AccountImportStep.VIDEO_PLAYLISTS))
                val videoPlaylists =
                    fetchPart(AccountImportStep.VIDEO_PLAYLISTS, steps) { fetchVideoPlaylists() }
                if (videoPlaylists.isNotEmpty()) {
                    runCatching { musicPlaylists.saveVideoPlaylists(videoPlaylists) }
                        .onSuccess { report = report.copy(videoPlaylists = it) }
                        .onFailure { Log.w(TAG, "Importing video playlists failed", it) }
                }

                onProgress(AccountImportState.Running(AccountImportStep.WATCH_LATER))
                val watchLater = fetchPart(AccountImportStep.WATCH_LATER, steps) { fetchWatchLater() }
                if (watchLater.isNotEmpty()) {
                    runCatching { watchLater.forEach { video -> musicPlaylists.addToWatchLater(video) } }
                        .onSuccess { report = report.copy(watchLater = watchLater.size) }
                        .onFailure { Log.w(TAG, "Importing Watch Later failed", it) }
                }

                onProgress(AccountImportState.Running(AccountImportStep.HISTORY))
                val watched = fetchPart(AccountImportStep.HISTORY, steps) { fetchWatchHistory() }
                if (watched.isNotEmpty()) {
                    runCatching {
                        viewHistory.bulkSaveHistoryEntries(watched.toHistoryEntries(System.currentTimeMillis()))
                    }.onSuccess { report = report.copy(history = watched.size) }
                        .onFailure { Log.w(TAG, "Importing watch history failed", it) }
                }

                seedFeedFromImport(report)
            } finally {
                ImportDiagnostics.end()
            }

            return report.copy(steps = steps)
        }

        /**
         * Runs one part, recording what it found and, on failure, the error rather than swallowing it.
         *
         * The diagnostics log already carries the shape of every response, so a part that returns
         * nothing is distinguishable from one that threw: the former records `found=0`, the latter
         * records the exception's class and message.
         */
        private suspend fun <T> fetchPart(
            step: AccountImportStep,
            steps: MutableList<ImportStepDiagnostic>,
            fetch: suspend () -> List<T>,
        ): List<T> {
            ImportDiagnostics.record("step ${step.name}: begin")
            return runCatching { fetch() }
                .onSuccess { ImportDiagnostics.record("step ${step.name}: found=${it.size}") }
                .onFailure {
                    Log.w(TAG, "Importing ${step.name} failed", it)
                    ImportDiagnostics.record("step ${step.name}: FAILED ${describeFailure(it)}")
                }.fold(
                    onSuccess = { items ->
                        steps += ImportStepDiagnostic(step.name, items.size)
                        items
                    },
                    onFailure = { error ->
                        steps += ImportStepDiagnostic(step.name, 0, describeFailure(error))
                        emptyList()
                    },
                )
        }

        /**
         * The playlists the account keeps on youtube.com, paged to the end.
         *
         * This is a different list from the music library's saved playlists: it holds the ordinary
         * video playlists, and it is what a viewer means by "my playlists".
         */
        private suspend fun fetchVideoPlaylists(): List<AccountVideoCollection> =
            withContext(Dispatchers.IO) {
                val all = mutableListOf<AccountVideoCollection>()
                var continuation: String? = null
                var pages = 0
                do {
                    val result = YouTube.accountPlaylists(continuation)
                    if (result.isFailure) {
                        ImportDiagnostics.record(
                            "videoPlaylists page ${pages + 1}: FAILED ${describeFailure(result.exceptionOrNull()!!)}",
                        )
                        if (continuation == null) throw result.exceptionOrNull()!!
                        break
                    }
                    val page = result.getOrThrow()
                    ImportDiagnostics.record(
                        "videoPlaylists page ${pages + 1}: playlists=${page.playlists.size} " +
                            "next=${page.continuation != null}",
                    )
                    all += page.playlists
                    continuation = page.continuation
                    pages++
                } while (continuation != null && pages < MAX_CHANNEL_PAGES)

                all.toSavedVideoPlaylists()
            }

        /** The account's Watch Later list, paged to the end. */
        private suspend fun fetchWatchLater(): List<Video> =
            withContext(Dispatchers.IO) {
                val all = mutableListOf<Video>()
                var continuation: String? = null
                var pages = 0
                do {
                    val result = YouTube.watchLater(continuation)
                    if (result.isFailure) {
                        ImportDiagnostics.record(
                            "watchLater page ${pages + 1}: FAILED ${describeFailure(result.exceptionOrNull()!!)}",
                        )
                        if (continuation == null) throw result.exceptionOrNull()!!
                        break
                    }
                    val page = result.getOrThrow()
                    ImportDiagnostics.record(
                        "watchLater page ${pages + 1}: videos=${page.videos.size} next=${page.continuation != null}",
                    )
                    all += page.videos
                    continuation = page.continuation
                    pages++
                } while (continuation != null && pages < MAX_CHANNEL_PAGES)

                all.distinctBy { it.id }
            }

        /** The account's watch history, paged to the end. */
        private suspend fun fetchWatchHistory(): List<Video> =
            withContext(Dispatchers.IO) {
                val all = mutableListOf<Video>()
                var continuation: String? = null
                var pages = 0
                do {
                    val result = YouTube.accountWatchHistory(continuation)
                    if (result.isFailure) {
                        ImportDiagnostics.record(
                            "history page ${pages + 1}: FAILED ${describeFailure(result.exceptionOrNull()!!)}",
                        )
                        if (continuation == null) throw result.exceptionOrNull()!!
                        break
                    }
                    val page = result.getOrThrow()
                    ImportDiagnostics.record(
                        "history page ${pages + 1}: videos=${page.videos.size} next=${page.continuation != null}",
                    )
                    all += page.videos
                    continuation = page.continuation
                    pages++
                } while (continuation != null && pages < MAX_CHANNEL_PAGES)

                all.distinctBy { it.id }
            }

        /**
         * Teaches the recommendation engine the channels just imported and drops the feed caches, so
         * the next load is ranked for this account rather than the previous identity. Mirrors what the
         * backup and Takeout importers do; without it an import left the subscriptions list correct
         * but the home feed ignorant of every new channel.
         */
        private suspend fun seedFeedFromImport(report: AccountImportReport) {
            if (report.isEmpty) return

            val channelNames =
                runCatching { subscriptions.getAllSubscriptions().first() }
                    .getOrDefault(emptyList())
                    .map { it.channelName }
                    .filter { it.isNotBlank() }
            if (channelNames.isNotEmpty()) {
                runCatching { FlowNeuroEngine.bootstrapFromSubscriptions(context, channelNames) }
                    .onFailure { Log.w(TAG, "Seeding the engine from subscriptions failed", it) }
            }

            FeedInvalidationBus.emit(FeedInvalidationBus.Event.AccountChanged)
        }

        /**
         * Every subscription the account holds: the guide's short list, which is always populated,
         * merged with `FEchannels`, which is the full one and may be empty on its own.
         */
        private suspend fun fetchSubscriptions(): List<Channel> =
            withContext(Dispatchers.IO) {
                val all = mutableListOf<Channel>()
                val guide = YouTube.guideSubscriptions().getOrDefault(emptyList())
                ImportDiagnostics.record("subscriptions guide: channels=${guide.size}")
                all += guide

                var continuation: String? = null
                var pages = 0
                do {
                    val result = YouTube.subscribedChannels(continuation)
                    if (result.isFailure) {
                        ImportDiagnostics.record(
                            "subscriptions FEchannels page ${pages + 1}: FAILED " +
                                describeFailure(result.exceptionOrNull()!!),
                        )
                        break
                    }
                    val page = result.getOrThrow()
                    ImportDiagnostics.record(
                        "subscriptions FEchannels page ${pages + 1}: channels=${page.channels.size} " +
                            "next=${page.continuation != null}",
                    )
                    all += page.channels
                    continuation = page.continuation
                    pages++
                } while (continuation != null && pages < MAX_CHANNEL_PAGES)

                all.distinctBy { it.id }
            }

        private suspend fun fetchLibrary(browseId: String): List<YTItem> =
            YouTube
                .library(browseId)
                .completed()
                .getOrThrow()
                .items
                .also { ImportDiagnostics.record("library($browseId): items=${it.size}") }

        /**
         * Saves each collection with its songs, so the library can play it offline. Collections are
         * fetched a few at a time, one failing does not stop the rest, and [onProgress] is called as
         * each one lands so the bar moves while the work is happening rather than at the end.
         */
        private suspend fun saveCollections(
            collections: List<AccountMusicCollection>,
            step: AccountImportStep,
            onProgress: (AccountImportState.Running) -> Unit,
        ): Int {
            val gate = Semaphore(PARALLELISM)
            val total = collections.size
            val done = AtomicInteger(0)
            val saved =
                coroutineScope {
                    collections
                        .map { collection ->
                            async {
                                gate.withPermit {
                                    val ok =
                                        runCatching { saveCollection(collection) }
                                            .onFailure { Log.w(TAG, "Saving ${collection.id} failed", it) }
                                            .getOrDefault(false)
                                    onProgress(AccountImportState.Running(step, done.incrementAndGet(), total))
                                    ok
                                }
                            }
                        }.awaitAll()
                }
            return saved.count { it }
        }

        private suspend fun saveCollection(collection: AccountMusicCollection): Boolean {
            val details = fetchCollection(collection) ?: return false
            musicPlaylists.saveMusicCollection(details)
            return true
        }

        /**
         * An album's browse id is not its playlist id, so an album that yields no songs as a
         * playlist is read as an album instead.
         */
        private suspend fun fetchCollection(collection: AccountMusicCollection): PlaylistDetails? {
            val asPlaylist = YouTubeMusicService.fetchPlaylistDetails(collection.id)
            if (asPlaylist != null && asPlaylist.tracks.isNotEmpty()) return asPlaylist
            return if (collection.isAlbum) YouTubeMusicService.fetchAlbumDetails(collection.id) else asPlaylist
        }

        /**
         * The channel behind each artist page. Most artists already carry one; the rest are looked
         * up one at a time, which is only needed for the few whose id is a radio or a shelf.
         */
        private suspend fun resolveArtistChannels(artists: List<YTItem>): Map<String, String> {
            val unresolved =
                artists
                    .filterIsInstance<ArtistItem>()
                    .filter { it.channelId == null && !it.id.startsWith("UC") }
                    .map { it.id }
                    .distinct()
            if (unresolved.isEmpty()) return emptyMap()

            return coroutineScope {
                unresolved
                    .map { id -> async { id to YouTube.getChannelId(id) } }
                    .awaitAll()
                    .filter { (_, channelId) -> channelId.isNotBlank() }
                    .toMap()
            }
        }
    }
