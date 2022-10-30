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
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.MusicMode
import kotlin.math.min

class BottomNavigationMediator(private val navigation: NavigationBarView,
                               private val viewPager: ViewPager2,
                               private val homeModel: HomeViewModel) {
    private val adapter: Adapter<*> = viewPager.adapter!!
    private val menu: Menu = navigation.menu

    fun attach() {
        viewPager.registerOnPageChangeCallback(PagerPageChangeCallback())

        navigation.setOnItemSelectedListener(NavigationPageChangeCallback())

        val pagerAdapterObserver = PagerAdapterObserver()
        adapter.registerAdapterDataObserver(pagerAdapterObserver)

        populateTabsFromPagerAdapter();
    }

    private fun populateTabsFromPagerAdapter() {
        menu.clear()
        for (i in 0 until adapter.itemCount) {
            val icon: Int
            val label: Int

            when (homeModel.tabs[i]) {
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
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = populateTabsFromPagerAdapter()
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) = populateTabsFromPagerAdapter()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = populateTabsFromPagerAdapter()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = populateTabsFromPagerAdapter()
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = populateTabsFromPagerAdapter()
    }
}