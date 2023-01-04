/*
 * Copyright (c) 2023 Auxio Project
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
 
package org.oxycblt.auxio.music.system

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.oxycblt.auxio.util.logD
import org.oxycblt.auxio.util.logI

class SDReIndex(val root: File, val context: Context) {
    private val toProcess: MutableSet<File> = TreeSet()
    private val pathNames: MutableList<String> = ArrayList()
    private var lastGoodProcessed: Int = -1
    private val handler = Handler(Looper.getMainLooper())

    private suspend fun preprocess() {
        logI("Starting")
        try {
            recursiveAddFiles(root)
        } catch (_: IOException) {}
        var dbSuccess = false
        var numRetries = 0
        while (!dbSuccess && (numRetries < 3)) {
            dbSuccess = true
            try {
                dbOneTry()
            } catch (_: Exception) {
                numRetries++
                dbSuccess = false
            }
        }
        pathNames.clear()
        toProcess.mapTo(pathNames) { it.path }
        lastGoodProcessed = -1
        startMediaScanner()
    }

    private fun dbOneTry() =
        context.contentResolver
            .query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DATE_MODIFIED),
                null,
                null,
                null)!!
            .use { cursor ->
                val dataColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val modifiedColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    try {
                        val file = File(cursor.getString(dataColumn)).canonicalFile
                        if ((!file.exists() ||
                            file.lastModified() / 1000 > cursor.getLong(modifiedColumn)) &&
                            shouldScan(file, true))
                            toProcess.add(file)
                        else toProcess.remove(file)
                    } catch (_: IOException) {}
                }
            }

    private fun recursiveAddFiles(f: File) {
        if (!shouldScan(f, false)) return
        if (!toProcess.add(f)) return
        if (f.isDirectory) {
            if (!File(f, ".nomedia").exists()) {
                f.listFiles()?.forEach { recursiveAddFiles(it) }
            }
        }
    }

    private fun shouldScan(f: File, fromDb: Boolean): Boolean {
        if (f.isDirectory && f.listFiles().run { this == null || isEmpty() }) return false
        if (fromDb) return true

        var file: File? = f
        while (file != null) {
            if (file == root) return true
            file = file.parentFile
        }

        // Outside of scan directory
        return false
    }

    private suspend fun startMediaScanner() {
        if (pathNames.isEmpty()) scannerEnded()
        else {
            suspendNop { continuation ->
                MediaScannerConnection.scanFile(
                    context,
                    pathNames.toTypedArray(),
                    null,
                ) { path, _ ->
                    logD("Scanning $path")
                    handler.post {
                        if (lastGoodProcessed + 1 < pathNames.size &&
                            pathNames[lastGoodProcessed + 1] == path) {
                            lastGoodProcessed++
                        } else {
                            val newIndex = pathNames.indexOf(path)
                            if (newIndex > -1) lastGoodProcessed = newIndex
                        }
                        if (lastGoodProcessed + 1 == pathNames.size) continuation()
                    }
                }
            }
            scannerEnded()
        }
    }

    private suspend fun scannerEnded() {
        logI("Finished")
    }

    companion object {
        suspend fun reindex(context: Context) {
            val f = File("/storage/emulated/0")
            if (f.exists()) SDReIndex(f, context).preprocess()
        }

        suspend inline fun suspendNop(crossinline block: (() -> Unit) -> Unit) {
            suspendCoroutine<Any?> { continuation -> block { continuation.resume(null) } }
        }
    }
}
