/*
 * Copyright (c) 2021 Auxio Project
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
 
package org.oxycblt.auxio.detail

import android.app.Application
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.oxycblt.auxio.R
import org.oxycblt.auxio.list.Header
import org.oxycblt.auxio.list.Item
import org.oxycblt.auxio.music.*
import org.oxycblt.auxio.music.MusicStore
import org.oxycblt.auxio.music.library.Library
import org.oxycblt.auxio.music.library.Sort
import org.oxycblt.auxio.music.storage.MimeType
import org.oxycblt.auxio.music.tags.ReleaseType
import org.oxycblt.auxio.playback.PlaybackSettings
import org.oxycblt.auxio.util.*

/**
 * [AndroidViewModel] that manages the Song, Album, Artist, and Genre detail views. Keeps track of
 * the current item they are showing, sub-data to display, and configuration. Since this ViewModel
 * requires a context, it must be instantiated [AndroidViewModel]'s Factory.
 * @param application [Application] context required to initialize certain information.
 * @author Alexander Capehart (OxygenCobalt)
 */
class DetailViewModel(application: Application) :
    AndroidViewModel(application), MusicStore.Listener {
    private val musicStore = MusicStore.getInstance()
    private val musicSettings = MusicSettings.from(application)
    private val playbackSettings = PlaybackSettings.from(application)

    private var currentSongJob: Job? = null

    // --- SONG ---

    private val _currentSong = MutableStateFlow<Song?>(null)
    /** The current [Song] to display. Null if there is nothing to show. */
    val currentSong: StateFlow<Song?>
        get() = _currentSong

    private val _songProperties = MutableStateFlow<SongProperties?>(null)
    /** The [SongProperties] of the currently shown [Song]. Null if not loaded yet. */
    val songProperties: StateFlow<SongProperties?> = _songProperties

    // --- ALBUM ---

    private val _currentAlbum = MutableStateFlow<Album?>(null)
    /** The current [Album] to display. Null if there is nothing to show. */
    val currentAlbum: StateFlow<Album?>
        get() = _currentAlbum

    private val _albumList = MutableStateFlow(listOf<Item>())
    /** The current list data derived from [currentAlbum]. */
    val albumList: StateFlow<List<Item>>
        get() = _albumList

    /** The current [Sort] used for [Song]s in [albumList]. */
    var albumSongSort: Sort
        get() = musicSettings.albumSongSort
        set(value) {
            musicSettings.albumSongSort = value
            // Refresh the album list to reflect the new sort.
            currentAlbum.value?.let(::refreshAlbumList)
        }

    // --- ARTIST ---

    private val _currentArtist = MutableStateFlow<Artist?>(null)
    /** The current [Artist] to display. Null if there is nothing to show. */
    val currentArtist: StateFlow<Artist?>
        get() = _currentArtist

    private val _artistList = MutableStateFlow(listOf<Item>())
    /** The current list derived from [currentArtist]. */
    val artistList: StateFlow<List<Item>> = _artistList

    /** The current [Sort] used for [Song]s in [artistList]. */
    var artistSongSort: Sort
        get() = musicSettings.artistSongSort
        set(value) {
            musicSettings.artistSongSort = value
            // Refresh the artist list to reflect the new sort.
            currentArtist.value?.let(::refreshArtistList)
        }

    // --- GENRE ---

    private val _currentGenre = MutableStateFlow<Genre?>(null)
    /** The current [Genre] to display. Null if there is nothing to show. */
    val currentGenre: StateFlow<Genre?>
        get() = _currentGenre

    private val _genreList = MutableStateFlow(listOf<Item>())
    /** The current list data derived from [currentGenre]. */
    val genreList: StateFlow<List<Item>> = _genreList

    /** The current [Sort] used for [Song]s in [genreList]. */
    var genreSongSort: Sort
        get() = musicSettings.genreSongSort
        set(value) {
            musicSettings.genreSongSort = value
            // Refresh the genre list to reflect the new sort.
            currentGenre.value?.let(::refreshGenreList)
        }

    /**
     * The [MusicMode] to use when playing a [Song] from the UI, or null to play from the currently
     * shown item.
     */
    val playbackMode: MusicMode?
        get() = playbackSettings.inParentPlaybackMode

    init {
        musicStore.addListener(this)
    }

    override fun onCleared() {
        musicStore.removeListener(this)
    }

    override fun onLibraryChanged(library: Library?) {
        if (library == null) {
            // Nothing to do.
            return
        }

        // If we are showing any item right now, we will need to refresh it (and any information
        // related to it) with the new library in order to prevent stale items from showing up
        // in the UI.

        val song = currentSong.value
        if (song != null) {
            _currentSong.value = library.sanitize(song)?.also(::loadProperties)
            logD("Updated song to ${currentSong.value}")
        }

        val album = currentAlbum.value
        if (album != null) {
            _currentAlbum.value = library.sanitize(album)?.also(::refreshAlbumList)
            logD("Updated genre to ${currentAlbum.value}")
        }

        val artist = currentArtist.value
        if (artist != null) {
            _currentArtist.value = library.sanitize(artist)?.also(::refreshArtistList)
            logD("Updated genre to ${currentArtist.value}")
        }

        val genre = currentGenre.value
        if (genre != null) {
            _currentGenre.value = library.sanitize(genre)?.also(::refreshGenreList)
            logD("Updated genre to ${currentGenre.value}")
        }
    }

    /**
     * Set a new [currentSong] from it's [Music.UID]. If the [Music.UID] differs, [currentSong] and
     * [songProperties] will be updated to align with the new [Song].
     * @param uid The UID of the [Song] to load. Must be valid.
     */
    fun setSongUid(uid: Music.UID) {
        if (_currentSong.value?.uid == uid) {
            // Nothing to do.
            return
        }
        logD("Opening Song [uid: $uid]")
        _currentSong.value = requireMusic<Song>(uid)?.also(::loadProperties)
    }

    /**
     * Set a new [currentAlbum] from it's [Music.UID]. If the [Music.UID] differs, [currentAlbum]
     * and [albumList] will be updated to align with the new [Album].
     * @param uid The [Music.UID] of the [Album] to update [currentAlbum] to. Must be valid.
     */
    fun setAlbumUid(uid: Music.UID) {
        if (_currentAlbum.value?.uid == uid) {
            // Nothing to do.
            return
        }
        logD("Opening Album [uid: $uid]")
        _currentAlbum.value = requireMusic<Album>(uid)?.also(::refreshAlbumList)
    }

    /**
     * Set a new [currentArtist] from it's [Music.UID]. If the [Music.UID] differs, [currentArtist]
     * and [artistList] will be updated to align with the new [Artist].
     * @param uid The [Music.UID] of the [Artist] to update [currentArtist] to. Must be valid.
     */
    fun setArtistUid(uid: Music.UID) {
        if (_currentArtist.value?.uid == uid) {
            // Nothing to do.
            return
        }
        logD("Opening Artist [uid: $uid]")
        _currentArtist.value = requireMusic<Artist>(uid)?.also(::refreshArtistList)
    }

    /**
     * Set a new [currentGenre] from it's [Music.UID]. If the [Music.UID] differs, [currentGenre]
     * and [genreList] will be updated to align with the new album.
     * @param uid The [Music.UID] of the [Genre] to update [currentGenre] to. Must be valid.
     */
    fun setGenreUid(uid: Music.UID) {
        if (_currentGenre.value?.uid == uid) {
            // Nothing to do.
            return
        }
        logD("Opening Genre [uid: $uid]")
        _currentGenre.value = requireMusic<Genre>(uid)?.also(::refreshGenreList)
    }

    private fun <T : Music> requireMusic(uid: Music.UID) = musicStore.library?.find<T>(uid)

    /**
     * Start a new job to load a given [Song]'s [SongProperties]. Result is pushed to
     * [songProperties].
     * @param song The song to load.
     */
    private fun loadProperties(song: Song) {
        // Clear any previous job in order to avoid stale data from appearing in the UI.
        currentSongJob?.cancel()
        _songProperties.value = null
        currentSongJob =
            viewModelScope.launch(Dispatchers.IO) {
                val properties = this@DetailViewModel.loadPropertiesImpl(song)
                yield()
                _songProperties.value = properties
            }
    }

    private fun loadPropertiesImpl(song: Song): SongProperties {
        // While we would use ExoPlayer to extract this information, it doesn't support
        // common data like bit rate in progressive data sources due to there being no
        // demand. Thus, we are stuck with the inferior OS-provided MediaExtractor.
        val extractor = MediaExtractor()

        try {
            extractor.setDataSource(context, song.uri, emptyMap())
        } catch (e: Exception) {
            // Can feasibly fail with invalid file formats. Note that this isn't considered
            // an error condition in the UI, as there is still plenty of other song information
            // that we can show.
            logW("Unable to extract song attributes.")
            logW(e.stackTraceToString())
            return SongProperties(null, null, song.mimeType)
        }

        // Get the first track from the extractor (This is basically always the only
        // track we need to analyze).
        val format = extractor.getTrackFormat(0)

        // Accessing fields can throw an exception if the fields are not present, and
        // the new method for using default values is not available on lower API levels.
        // So, we are forced to handle the exception and map it to a saner null value.
        val bitrate =
            try {
                // Convert bytes-per-second to kilobytes-per-second.
                format.getInteger(MediaFormat.KEY_BIT_RATE) / 1000
            } catch (e: NullPointerException) {
                logD("Unable to extract bit rate field")
                null
            }

        val sampleRate =
            try {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } catch (e: NullPointerException) {
                logE("Unable to extract sample rate field")
                null
            }

        val resolvedMimeType =
            if (song.mimeType.fromFormat != null) {
                // ExoPlayer was already able to populate the format.
                song.mimeType
            } else {
                // ExoPlayer couldn't populate the format somehow, populate it here.
                val formatMimeType =
                    try {
                        format.getString(MediaFormat.KEY_MIME)
                    } catch (e: NullPointerException) {
                        logE("Unable to extract mime type field")
                        null
                    }

                MimeType(song.mimeType.fromExtension, formatMimeType)
            }

        return SongProperties(bitrate, sampleRate, resolvedMimeType)
    }

    private fun refreshAlbumList(album: Album) {
        logD("Refreshing album data")
        val data = mutableListOf<Item>(album)
        data.add(SortHeader(R.string.lbl_songs))

        // To create a good user experience regarding disc numbers, we group the album's
        // songs up by disc and then delimit the groups by a disc header.
        val songs = albumSongSort.songs(album.songs)
        // Songs without disc tags become part of Disc 1.
        val byDisc = songs.groupBy { it.disc ?: 1 }
        if (byDisc.size > 1) {
            logD("Album has more than one disc, interspersing headers")
            for (entry in byDisc.entries) {
                data.add(DiscHeader(entry.key))
                data.addAll(entry.value)
            }
        } else {
            // Album only has one disc, don't add any redundant headers
            data.addAll(songs)
        }

        _albumList.value = data
    }

    private fun refreshArtistList(artist: Artist) {
        logD("Refreshing artist data")
        val data = mutableListOf<Item>(artist)
        val albums = Sort(Sort.Mode.ByDate, false).albums(artist.albums)

        val byReleaseGroup =
            albums.groupBy {
                // Remap the complicated ReleaseType data structure into an easier
                // "AlbumGrouping" enum that will automatically group and sort
                // the artist's albums.
                when (it.releaseType.refinement) {
                    ReleaseType.Refinement.LIVE -> AlbumGrouping.LIVE
                    ReleaseType.Refinement.REMIX -> AlbumGrouping.REMIXES
                    null ->
                        when (it.releaseType) {
                            is ReleaseType.Album -> AlbumGrouping.ALBUMS
                            is ReleaseType.EP -> AlbumGrouping.EPS
                            is ReleaseType.Single -> AlbumGrouping.SINGLES
                            is ReleaseType.Compilation -> AlbumGrouping.COMPILATIONS
                            is ReleaseType.Soundtrack -> AlbumGrouping.SOUNDTRACKS
                            is ReleaseType.Mix -> AlbumGrouping.MIXES
                            is ReleaseType.Mixtape -> AlbumGrouping.MIXTAPES
                        }
                }
            }

        logD("Release groups for this artist: ${byReleaseGroup.keys}")

        for (entry in byReleaseGroup.entries.sortedBy { it.key }) {
            data.add(Header(entry.key.headerTitleRes))
            data.addAll(entry.value)
        }

        // Artists may not be linked to any songs, only include a header entry if we have any.
        if (artist.songs.isNotEmpty()) {
            logD("Songs present in this artist, adding header")
            data.add(SortHeader(R.string.lbl_songs))
            data.addAll(artistSongSort.songs(artist.songs))
        }

        _artistList.value = data.toList()
    }

    private fun refreshGenreList(genre: Genre) {
        logD("Refreshing genre data")
        val data = mutableListOf<Item>(genre)
        // Genre is guaranteed to always have artists and songs.
        data.add(Header(R.string.lbl_artists))
        data.addAll(genre.artists)
        data.add(SortHeader(R.string.lbl_songs))
        data.addAll(genreSongSort.songs(genre.songs))
        _genreList.value = data
    }

    /**
     * A simpler mapping of [ReleaseType] used for grouping and sorting songs.
     * @param headerTitleRes The title string resource to use for a header created out of an
     * instance of this enum.
     */
    private enum class AlbumGrouping(@StringRes val headerTitleRes: Int) {
        ALBUMS(R.string.lbl_albums),
        EPS(R.string.lbl_eps),
        SINGLES(R.string.lbl_singles),
        COMPILATIONS(R.string.lbl_compilations),
        SOUNDTRACKS(R.string.lbl_soundtracks),
        MIXES(R.string.lbl_mixes),
        MIXTAPES(R.string.lbl_mixtapes),
        LIVE(R.string.lbl_live_group),
        REMIXES(R.string.lbl_remix_group),
    }
}
