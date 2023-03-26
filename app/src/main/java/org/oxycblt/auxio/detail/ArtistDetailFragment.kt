/*
 * Copyright (c) 2021 Auxio Project
 * ArtistDetailFragment.kt is part of Auxio.
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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.ConcatAdapter
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentDetailBinding
import org.oxycblt.auxio.detail.header.ArtistDetailHeaderAdapter
import org.oxycblt.auxio.detail.header.DetailHeaderAdapter
import org.oxycblt.auxio.detail.list.ArtistDetailListAdapter
import org.oxycblt.auxio.detail.list.DetailListAdapter
import org.oxycblt.auxio.list.Item
import org.oxycblt.auxio.list.ListFragment
import org.oxycblt.auxio.list.Sort
import org.oxycblt.auxio.list.selection.SelectionViewModel
import org.oxycblt.auxio.music.Album
import org.oxycblt.auxio.music.Artist
import org.oxycblt.auxio.music.Music
import org.oxycblt.auxio.music.MusicParent
import org.oxycblt.auxio.music.Song
import org.oxycblt.auxio.navigation.NavigationViewModel
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.util.*

/**
 * A [ListFragment] that shows information about an [Artist].
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class ArtistDetailFragment :
    ListFragment<Music, FragmentDetailBinding>(),
    DetailHeaderAdapter.Listener,
    DetailListAdapter.Listener<Music> {
    private val detailModel: DetailViewModel by activityViewModels()
    override val navModel: NavigationViewModel by activityViewModels()
    override val playbackModel: PlaybackViewModel by activityViewModels()
    override val selectionModel: SelectionViewModel by activityViewModels()
    // Information about what artist to display is initially within the navigation arguments
    // as a UID, as that is the only safe way to parcel an artist.
    private val args: ArtistDetailFragmentArgs by navArgs()
    private val artistHeaderAdapter = ArtistDetailHeaderAdapter(this)
    private val artistListAdapter = ArtistDetailListAdapter(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Detail transitions are always on the X axis. Shared element transitions are more
        // semantically correct, but are also too buggy to be sensible.
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    override fun onCreateBinding(inflater: LayoutInflater) = FragmentDetailBinding.inflate(inflater)

    override fun getSelectionToolbar(binding: FragmentDetailBinding) =
        binding.detailSelectionToolbar

    override fun onBindingCreated(binding: FragmentDetailBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        // --- UI SETUP ---
        binding.detailToolbar.apply {
            inflateMenu(R.menu.menu_parent_detail)
            setNavigationOnClickListener { findNavController().navigateUp() }
            setOnMenuItemClickListener(this@ArtistDetailFragment)
        }

        binding.detailRecycler.adapter = ConcatAdapter(artistHeaderAdapter, artistListAdapter)

        // --- VIEWMODEL SETUP ---
        // DetailViewModel handles most initialization from the navigation argument.
        detailModel.setArtistUid(args.artistUid)
        collectImmediately(detailModel.currentArtist, ::updateArtist)
        collectImmediately(detailModel.artistList, ::updateList)
        collectImmediately(
            playbackModel.song, playbackModel.parent, playbackModel.isPlaying, ::updatePlayback)
        collect(navModel.exploreNavigationItem.flow, ::handleNavigation)
        collectImmediately(selectionModel.selected, ::updateSelection)
    }

    override fun onDestroyBinding(binding: FragmentDetailBinding) {
        super.onDestroyBinding(binding)
        binding.detailToolbar.setOnMenuItemClickListener(null)
        binding.detailRecycler.adapter = null
        // Avoid possible race conditions that could cause a bad replace instruction to be consumed
        // during list initialization and crash the app. Could happen if the user is fast enough.
        detailModel.artistInstructions.consume()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        if (super.onMenuItemClick(item)) {
            return true
        }

        val currentArtist = unlikelyToBeNull(detailModel.currentArtist.value)
        return when (item.itemId) {
            R.id.action_play_next -> {
                playbackModel.playNext(currentArtist)
                requireContext().showToast(R.string.lng_queue_added)
                true
            }
            R.id.action_queue_add -> {
                playbackModel.addToQueue(currentArtist)
                requireContext().showToast(R.string.lng_queue_added)
                true
            }
            else -> false
        }
    }

    override fun onRealClick(item: Music) {
        when (item) {
            is Album -> navModel.exploreNavigateTo(item)
            is Song -> {
                val playbackMode = detailModel.playbackMode
                if (playbackMode != null) {
                    playbackModel.playFrom(item, playbackMode)
                } else {
                    // When configured to play from the selected item, we already have an Artist
                    // to play from.
                    playbackModel.playFromArtist(
                        item, unlikelyToBeNull(detailModel.currentArtist.value))
                }
            }
            else -> error("Unexpected datatype: ${item::class.simpleName}")
        }
    }

    override fun onOpenMenu(item: Music, anchor: View) {
        when (item) {
            is Song -> openMusicMenu(anchor, R.menu.menu_artist_song_actions, item)
            is Album -> openMusicMenu(anchor, R.menu.menu_artist_album_actions, item)
            else -> error("Unexpected datatype: ${item::class.simpleName}")
        }
    }

    override fun onPlay() {
        playbackModel.play(unlikelyToBeNull(detailModel.currentArtist.value))
    }

    override fun onShuffle() {
        playbackModel.shuffle(unlikelyToBeNull(detailModel.currentArtist.value))
    }

    override fun onOpenSortMenu(anchor: View) {
        openMenu(anchor, R.menu.menu_artist_sort) {
            // Select the corresponding sort mode option
            val sort = detailModel.artistSongSort
            unlikelyToBeNull(menu.findItem(sort.mode.itemId)).isChecked = true
            // Select the corresponding sort direction option
            val directionItemId =
                when (sort.direction) {
                    Sort.Direction.ASCENDING -> R.id.option_sort_asc
                    Sort.Direction.DESCENDING -> R.id.option_sort_dec
                }
            unlikelyToBeNull(menu.findItem(directionItemId)).isChecked = true
            setOnMenuItemClickListener { item ->
                item.isChecked = !item.isChecked

                detailModel.artistSongSort =
                    when (item.itemId) {
                        // Sort direction options
                        R.id.option_sort_asc -> sort.withDirection(Sort.Direction.ASCENDING)
                        R.id.option_sort_dec -> sort.withDirection(Sort.Direction.DESCENDING)
                        // Any other option is a sort mode
                        else -> sort.withMode(unlikelyToBeNull(Sort.Mode.fromItemId(item.itemId)))
                    }

                true
            }
        }
    }

    private fun updateArtist(artist: Artist?) {
        if (artist == null) {
            // Artist we were showing no longer exists.
            findNavController().navigateUp()
            return
        }
        requireBinding().detailToolbar.title = artist.resolveName(requireContext())
        artistHeaderAdapter.setParent(artist)
    }

    private fun updatePlayback(song: Song?, parent: MusicParent?, isPlaying: Boolean) {
        val currentArtist = unlikelyToBeNull(detailModel.currentArtist.value)
        val playingItem =
            when (parent) {
                // Always highlight a playing album if it's from this artist.
                is Album -> parent
                // If the parent is the artist itself, use the currently playing song.
                currentArtist -> song
                // Nothing is playing from this artist.
                else -> null
            }

        artistListAdapter.setPlaying(playingItem, isPlaying)
    }

    private fun handleNavigation(item: Music?) {
        val binding = requireBinding()

        when (item) {
            // Songs should be shown in their album, not in their artist.
            is Song -> {
                logD("Navigating to another album")
                findNavController()
                    .navigateSafe(ArtistDetailFragmentDirections.actionShowAlbum(item.album.uid))
            }
            // Launch a new detail view for an album, even if it is part of
            // this artist.
            is Album -> {
                logD("Navigating to another album")
                findNavController()
                    .navigateSafe(ArtistDetailFragmentDirections.actionShowAlbum(item.uid))
            }
            // If the artist that should be navigated to is this artist, then
            // scroll back to the top. Otherwise launch a new detail view.
            is Artist -> {
                if (item.uid == detailModel.currentArtist.value?.uid) {
                    logD("Navigating to the top of this artist")
                    binding.detailRecycler.scrollToPosition(0)
                    navModel.exploreNavigationItem.consume()
                } else {
                    logD("Navigating to another artist")
                    findNavController()
                        .navigateSafe(ArtistDetailFragmentDirections.actionShowArtist(item.uid))
                }
            }
            null -> {}
            else -> error("Unexpected datatype: ${item::class.java}")
        }
    }

    private fun updateList(list: List<Item>) {
        artistListAdapter.update(list, detailModel.artistInstructions.consume())
    }

    private fun updateSelection(selected: List<Music>) {
        artistListAdapter.setSelected(selected.toSet())
        requireBinding().detailSelectionToolbar.updateSelectionAmount(selected.size)
    }
}
