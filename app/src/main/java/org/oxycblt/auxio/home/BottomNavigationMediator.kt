/*
 * Copyright (c) 2023 Auxio Project
 * BottomNavigationMediator.kt is part of Auxio.
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
 
package org.oxycblt.auxio.home

import android.view.Menu
import android.view.MenuItem
import androidx.core.view.size
import androidx.recyclerview.widget.RecyclerView.Adapter
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver
import androidx.viewpager2.widget.ViewPager2
import androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.navigation.NavigationBarView.OnItemSelectedListener
import kotlin.math.min
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.MusicMode

class BottomNavigationMediator(
    private val navigation: NavigationBarView,
    private val viewPager: ViewPager2,
    private val tabs: List<MusicMode>
) {
    private val adapter: Adapter<*> = viewPager.adapter!!
    private val menu: Menu = navigation.menu

    fun attach() {
        viewPager.registerOnPageChangeCallback(PagerPageChangeCallback())

        navigation.setOnItemSelectedListener(NavigationPageChangeCallback())

        val pagerAdapterObserver = PagerAdapterObserver()
        adapter.registerAdapterDataObserver(pagerAdapterObserver)

        populateTabsFromPagerAdapter()
    }

    private fun populateTabsFromPagerAdapter() {
        menu.clear()
        for (i in 0 until adapter.itemCount) {
            val icon: Int
            val label: Int

            when (tabs[i]) {
                MusicMode.SONGS -> {
                    icon = R.drawable.ic_song_24
                    label = R.string.lbl_songs
                }
                MusicMode.ALBUMS -> {
                    icon = R.drawable.ic_album_24
                    label = R.string.lbl_albums
                }
                MusicMode.ARTISTS -> {
                    icon = R.drawable.ic_artist_24
                    label = R.string.lbl_artists
                }
                MusicMode.GENRES -> {
                    icon = R.drawable.ic_genre_24
                    label = R.string.lbl_genres
                }
                MusicMode.PLAYLISTS -> {
                    icon = R.drawable.ic_playlist_24
                    label = R.string.lbl_playlists
                }
            }

            menu.add(0, i, 0, label).setIcon(icon)
        }

        if (adapter.itemCount > 0) {
            val lastItem = menu.size - 1
            val currItem = min(viewPager.currentItem, lastItem)
            if (currItem != navigation.selectedItemId) {
                navigation.selectedItemId = currItem
            }
        }
    }

    private inner class PagerPageChangeCallback : OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            if (navigation.selectedItemId != position && position < menu.size) {
                navigation.selectedItemId = position
            }
        }
    }

    private inner class NavigationPageChangeCallback : OnItemSelectedListener {
        override fun onNavigationItemSelected(item: MenuItem): Boolean {
            viewPager.setCurrentItem(item.itemId, true)
            return true
        }
    }

    private inner class PagerAdapterObserver : AdapterDataObserver() {
        override fun onChanged() = populateTabsFromPagerAdapter()
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) =
            populateTabsFromPagerAdapter()
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) =
            populateTabsFromPagerAdapter()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) =
            populateTabsFromPagerAdapter()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) =
            populateTabsFromPagerAdapter()
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) =
            populateTabsFromPagerAdapter()
    }
}
