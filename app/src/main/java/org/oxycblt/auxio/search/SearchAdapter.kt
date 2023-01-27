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
 
package org.oxycblt.auxio.search

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.list.*
import org.oxycblt.auxio.list.adapter.BasicListInstructions
import org.oxycblt.auxio.list.adapter.ListDiffer
import org.oxycblt.auxio.list.adapter.SelectionIndicatorAdapter
import org.oxycblt.auxio.list.adapter.SimpleDiffCallback
import org.oxycblt.auxio.list.recycler.*
import org.oxycblt.auxio.music.*
import org.oxycblt.auxio.util.logD

/**
 * An adapter that displays search results.
 * @param listener An [SelectableListListener] to bind interactions to.
 * @author Alexander Capehart (OxygenCobalt)
 */
class SearchAdapter(private val listener: SelectableListListener<Music>) :
    SelectionIndicatorAdapter<Item, BasicListInstructions, RecyclerView.ViewHolder>(
        ListDiffer.Async(DIFF_CALLBACK)),
    AuxioRecyclerView.SpanSizeLookup {

    override fun getItemViewType(position: Int) =
        when (getItem(position)) {
            is Song -> SongViewHolder.VIEW_TYPE
            is Album -> AlbumViewHolder.VIEW_TYPE
            is Artist -> ArtistViewHolder.VIEW_TYPE
            is Genre -> GenreViewHolder.VIEW_TYPE
            is Header -> HeaderViewHolder.VIEW_TYPE
            else -> super.getItemViewType(position)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        when (viewType) {
            SongViewHolder.VIEW_TYPE -> SongViewHolder.from(parent)
            AlbumViewHolder.VIEW_TYPE -> AlbumViewHolder.from(parent)
            ArtistViewHolder.VIEW_TYPE -> ArtistViewHolder.from(parent)
            GenreViewHolder.VIEW_TYPE -> GenreViewHolder.from(parent)
            HeaderViewHolder.VIEW_TYPE -> HeaderViewHolder.from(parent)
            else -> error("Invalid item type $viewType")
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        logD(position)
        when (val item = getItem(position)) {
            is Song -> (holder as SongViewHolder).bind(item, listener)
            is Album -> (holder as AlbumViewHolder).bind(item, listener)
            is Artist -> (holder as ArtistViewHolder).bind(item, listener)
            is Genre -> (holder as GenreViewHolder).bind(item, listener)
            is Header -> (holder as HeaderViewHolder).bind(item)
        }
    }

    override fun isItemFullWidth(position: Int) = getItem(position) is Header

    /**
     * Make sure that the top header has a correctly configured divider visibility. This would
     * normally be automatically done by the differ, but that results in a strange animation.
     */
    fun pokeDividers() {
        notifyItemChanged(0, PAYLOAD_UPDATE_DIVIDER)
    }

    private companion object {
        val PAYLOAD_UPDATE_DIVIDER = 102249124
        /** A comparator that can be used with DiffUtil. */
        val DIFF_CALLBACK =
            object : SimpleDiffCallback<Item>() {
                override fun areContentsTheSame(oldItem: Item, newItem: Item) =
                    when {
                        oldItem is Song && newItem is Song ->
                            SongViewHolder.DIFF_CALLBACK.areContentsTheSame(oldItem, newItem)
                        oldItem is Album && newItem is Album ->
                            AlbumViewHolder.DIFF_CALLBACK.areContentsTheSame(oldItem, newItem)
                        oldItem is Artist && newItem is Artist ->
                            ArtistViewHolder.DIFF_CALLBACK.areContentsTheSame(oldItem, newItem)
                        oldItem is Genre && newItem is Genre ->
                            GenreViewHolder.DIFF_CALLBACK.areContentsTheSame(oldItem, newItem)
                        oldItem is Header && newItem is Header ->
                            HeaderViewHolder.DIFF_CALLBACK.areContentsTheSame(oldItem, newItem)
                        else -> false
                    }
            }
    }
}
