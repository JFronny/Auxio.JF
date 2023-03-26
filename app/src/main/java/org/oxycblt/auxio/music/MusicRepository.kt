/*
 * Copyright (c) 2023 Auxio Project
 * MusicRepository.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.music

import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.*
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.oxycblt.auxio.music.cache.CacheRepository
import org.oxycblt.auxio.music.device.DeviceLibrary
import org.oxycblt.auxio.music.device.RawSong
import org.oxycblt.auxio.music.fs.MediaStoreExtractor
import org.oxycblt.auxio.music.metadata.TagExtractor
import org.oxycblt.auxio.music.system.SDReIndex
import org.oxycblt.auxio.music.user.UserLibrary
import org.oxycblt.auxio.util.logD
import org.oxycblt.auxio.util.logE
import org.oxycblt.auxio.util.logW

/**
 * Primary manager of music information and loading.
 *
 * Music information is loaded in-memory by this repository using an [IndexingWorker]. Changes in
 * music (loading) can be reacted to with [UpdateListener] and [IndexingListener].
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
interface MusicRepository {
    /** The current music information found on the device. */
    val deviceLibrary: DeviceLibrary?
    /** The current user-defined music information. */
    val userLibrary: UserLibrary?
    /** The current state of music loading. Null if no load has occurred yet. */
    val indexingState: IndexingState?

    /**
     * Add an [UpdateListener] to receive updates from this instance.
     *
     * @param listener The [UpdateListener] to add.
     */
    fun addUpdateListener(listener: UpdateListener)

    /**
     * Remove an [UpdateListener] such that it does not receive any further updates from this
     * instance.
     *
     * @param listener The [UpdateListener] to remove.
     */
    fun removeUpdateListener(listener: UpdateListener)

    /**
     * Add an [IndexingListener] to receive updates from this instance.
     *
     * @param listener The [UpdateListener] to add.
     */
    fun addIndexingListener(listener: IndexingListener)

    /**
     * Remove an [IndexingListener] such that it does not receive any further updates from this
     * instance.
     *
     * @param listener The [IndexingListener] to remove.
     */
    fun removeIndexingListener(listener: IndexingListener)

    /**
     * Register an [IndexingWorker] to handle loading operations. Will do nothing if one is already
     * registered.
     *
     * @param worker The [IndexingWorker] to register.
     */
    fun registerWorker(worker: IndexingWorker)

    /**
     * Unregister an [IndexingWorker] and drop any work currently being done by it. Does nothing if
     * given [IndexingWorker] is not the currently registered instance.
     *
     * @param worker The [IndexingWorker] to unregister.
     */
    fun unregisterWorker(worker: IndexingWorker)

    /**
     * Generically search for the [Music] associated with the given [Music.UID]. Note that this
     * method is much slower that type-specific find implementations, so this should only be used if
     * the type of music being searched for is entirely unknown.
     *
     * @param uid The [Music.UID] to search for.
     * @return The expected [Music] information, or null if it could not be found.
     */
    fun find(uid: Music.UID): Music?

    /**
     * Request that a music loading operation is started by the current [IndexingWorker]. Does
     * nothing if one is not available.
     *
     * @param withCache Whether to load with the music cache or not.
     */
    fun requestIndex(withCache: Boolean)

    /**
     * Load the music library. Any prior loads will be canceled.
     *
     * @param worker The [IndexingWorker] to perform the work with.
     * @param withCache Whether to load with the music cache or not.
     * @return The top-level music loading [Job] started.
     */
    fun index(worker: IndexingWorker, withCache: Boolean): Job

    /** A listener for changes to the stored music information. */
    interface UpdateListener {
        /**
         * Called when a change to the stored music information occurs.
         *
         * @param changes The [Changes] that have occurred.
         */
        fun onMusicChanges(changes: Changes)
    }

    /**
     * Flags indicating which kinds of music information changed.
     *
     * @param deviceLibrary Whether the current [DeviceLibrary] has changed.
     * @param userLibrary Whether the current [Playlist]s have changed.
     */
    data class Changes(val deviceLibrary: Boolean, val userLibrary: Boolean)

    /** A listener for events in the music loading process. */
    interface IndexingListener {
        /** Called when the music loading state changed. */
        fun onIndexingStateChanged()
    }

    /** A persistent worker that can load music in the background. */
    interface IndexingWorker {
        /** A [Context] required to read device storage */
        val context: Context

        /** The [CoroutineScope] to perform coroutine music loading work on. */
        val scope: CoroutineScope

        /**
         * Request that the music loading process ([index]) should be started. Any prior loads
         * should be canceled.
         *
         * @param withCache Whether to use the music cache when loading.
         */
        fun requestIndex(withCache: Boolean)

        suspend fun toast(msg: String) =
            withContext(Dispatchers.Main) { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
    }
}

class MusicRepositoryImpl
@Inject
constructor(
    private val cacheRepository: CacheRepository,
    private val mediaStoreExtractor: MediaStoreExtractor,
    private val tagExtractor: TagExtractor,
    private val deviceLibraryFactory: DeviceLibrary.Factory,
    private val userLibraryFactory: UserLibrary.Factory
) : MusicRepository {
    private val updateListeners = mutableListOf<MusicRepository.UpdateListener>()
    private val indexingListeners = mutableListOf<MusicRepository.IndexingListener>()
    private var indexingWorker: MusicRepository.IndexingWorker? = null

    override var deviceLibrary: DeviceLibrary? = null
    override var userLibrary: UserLibrary? = null
    private var previousCompletedState: IndexingState.Completed? = null
    private var currentIndexingState: IndexingState? = null
    override val indexingState: IndexingState?
        get() = currentIndexingState ?: previousCompletedState

    @Synchronized
    override fun addUpdateListener(listener: MusicRepository.UpdateListener) {
        updateListeners.add(listener)
        listener.onMusicChanges(MusicRepository.Changes(deviceLibrary = true, userLibrary = true))
    }

    @Synchronized
    override fun removeUpdateListener(listener: MusicRepository.UpdateListener) {
        updateListeners.remove(listener)
    }

    @Synchronized
    override fun addIndexingListener(listener: MusicRepository.IndexingListener) {
        indexingListeners.add(listener)
        listener.onIndexingStateChanged()
    }

    @Synchronized
    override fun removeIndexingListener(listener: MusicRepository.IndexingListener) {
        indexingListeners.remove(listener)
    }

    @Synchronized
    override fun registerWorker(worker: MusicRepository.IndexingWorker) {
        if (indexingWorker != null) {
            logW("Worker is already registered")
            return
        }
        indexingWorker = worker
        if (indexingState == null) {
            worker.requestIndex(true)
        }
    }

    @Synchronized
    override fun unregisterWorker(worker: MusicRepository.IndexingWorker) {
        if (indexingWorker !== worker) {
            logW("Given worker did not match current worker")
            return
        }
        indexingWorker = null
        currentIndexingState = null
    }

    override fun find(uid: Music.UID) =
        (deviceLibrary?.run { findSong(uid) ?: findAlbum(uid) ?: findArtist(uid) ?: findGenre(uid) }
            ?: userLibrary?.findPlaylist(uid))

    override fun requestIndex(withCache: Boolean) {
        indexingWorker?.requestIndex(withCache)
    }

    override fun index(worker: MusicRepository.IndexingWorker, withCache: Boolean) =
        worker.scope.launch {
            try {
                if (!withCache) worker.toast("Reindexing")
                val start = System.currentTimeMillis()
                indexImpl(worker, withCache)
                logD(
                    "Music indexing completed successfully in " +
                        "${System.currentTimeMillis() - start}ms")
                if (!withCache) worker.toast("Finished Indexing")
            } catch (e: CancellationException) {
                // Got cancelled, propagate upwards to top-level co-routine.
                logD("Loading routine was cancelled")
                throw e
            } catch (e: Exception) {
                // Music loading process failed due to something we have not handled.
                logE("Music indexing failed")
                logE(e.stackTraceToString())
                emitComplete(e)
            }
        }

    private suspend fun indexImpl(worker: MusicRepository.IndexingWorker, withCache: Boolean) {
        if (ContextCompat.checkSelfPermission(worker.context, PERMISSION_READ_AUDIO) ==
            PackageManager.PERMISSION_DENIED) {
            logE("Permission check failed")
            // No permissions, signal that we can't do anything.
            throw NoAudioPermissionException()
        }

        // Start initializing the extractors. Use an indeterminate state, as there is no ETA on
        // how long a media database query will take.
        emitLoading(IndexingProgress.Indeterminate)

        if (!withCache) {
            logD("Reindexing SD")
            SDReIndex.reindex(worker.context)
        }

        // Do the initial query of the cache and media databases in parallel.
        logD("Starting queries")
        val mediaStoreQueryJob = worker.scope.async { mediaStoreExtractor.query() }
        val cache =
            if (withCache) {
                cacheRepository.readCache()
            } else {
                null
            }
        val query = mediaStoreQueryJob.await()

        // Now start processing the queried song information in parallel. Songs that can't be
        // received from the cache are consisted incomplete and pushed to a separate channel
        // that will eventually be processed into completed raw songs.
        logD("Starting song discovery")
        val completeSongs = Channel<RawSong>(Channel.UNLIMITED)
        val incompleteSongs = Channel<RawSong>(Channel.UNLIMITED)
        val mediaStoreJob =
            worker.scope.async {
                mediaStoreExtractor.consume(query, cache, incompleteSongs, completeSongs)
            }
        val metadataJob =
            worker.scope.async { tagExtractor.consume(incompleteSongs, completeSongs) }

        // Await completed raw songs as they are processed.
        val rawSongs = LinkedList<RawSong>()
        for (rawSong in completeSongs) {
            rawSongs.add(rawSong)
            emitLoading(IndexingProgress.Songs(rawSongs.size, query.projectedTotal))
        }
        // These should be no-ops
        mediaStoreJob.await()
        metadataJob.await()

        if (rawSongs.isEmpty()) {
            logE("Music library was empty")
            throw NoMusicException()
        }

        // Successfully loaded the library, now save the cache and create the library in
        // parallel.
        logD("Discovered ${rawSongs.size} songs, starting finalization")
        emitLoading(IndexingProgress.Indeterminate)
        val deviceLibraryChannel = Channel<DeviceLibrary>()
        val deviceLibraryJob =
            worker.scope.async(Dispatchers.Main) {
                deviceLibraryFactory.create(rawSongs).also { deviceLibraryChannel.send(it) }
            }
        val userLibraryJob = worker.scope.async { userLibraryFactory.read(deviceLibraryChannel) }
        if (cache == null || cache.invalidated) {
            cacheRepository.writeCache(rawSongs)
        }
        val deviceLibrary = deviceLibraryJob.await()
        val userLibrary = userLibraryJob.await()
        withContext(Dispatchers.Main) {
            emitComplete(null)
            emitData(deviceLibrary, userLibrary)
        }
    }

    private suspend fun emitLoading(progress: IndexingProgress) {
        yield()
        synchronized(this) {
            currentIndexingState = IndexingState.Indexing(progress)
            for (listener in indexingListeners) {
                listener.onIndexingStateChanged()
            }
        }
    }

    private suspend fun emitComplete(error: Exception?) {
        yield()
        synchronized(this) {
            previousCompletedState = IndexingState.Completed(error)
            currentIndexingState = null
            for (listener in indexingListeners) {
                listener.onIndexingStateChanged()
            }
        }
    }

    @Synchronized
    private fun emitData(deviceLibrary: DeviceLibrary, userLibrary: UserLibrary) {
        val deviceLibraryChanged = this.deviceLibrary != deviceLibrary
        val userLibraryChanged = this.userLibrary != userLibrary
        if (!deviceLibraryChanged && !userLibraryChanged) return

        this.deviceLibrary = deviceLibrary
        this.userLibrary = userLibrary
        val changes = MusicRepository.Changes(deviceLibraryChanged, userLibraryChanged)
        for (listener in updateListeners) {
            listener.onMusicChanges(changes)
        }
    }
}
