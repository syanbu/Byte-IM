package com.buyansong.im.chat

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidAlbumImageRepository(
    private val contentResolver: ContentResolver
) : AlbumImageRepository {
    override suspend fun loadImages(limit: Int): List<AlbumImageItem> {
        return withContext(Dispatchers.IO) {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT
            )
            val images = mutableListOf<AlbumImageItem>()
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateTakenIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val dateAddedIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val widthIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                while (cursor.moveToNext() && images.size < limit) {
                    val id = cursor.getLong(idIndex)
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val dateTaken = cursor.getLong(dateTakenIndex).takeIf { it > 0L }
                    val dateAdded = cursor.getLong(dateAddedIndex).takeIf { it > 0L }?.times(1_000L)
                    images += AlbumImageItem(
                        id = id,
                        uriString = uri.toString(),
                        dateTakenMillis = dateTaken ?: dateAdded ?: 0L,
                        width = cursor.getInt(widthIndex).takeIf { it > 0 },
                        height = cursor.getInt(heightIndex).takeIf { it > 0 }
                    )
                }
            }
            images
        }
    }
}
