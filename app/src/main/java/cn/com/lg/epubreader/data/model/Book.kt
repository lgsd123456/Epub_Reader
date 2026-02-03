package cn.com.lg.epubreader.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class Book(
    @PrimaryKey val id: String, // Usually md5 of file or unique path hash
    val title: String,
    val author: String,
    val coverPath: String?, // Path to local storage of cover image
    val filePath: String, // Original URI or path
    val extractedPath: String?, // Path where we unzipped/processed it
    val lastReadPosition: Int = 0, // Could be chapter index or scroll offset
    val lastReadChapter: Int = 0, // Chapter index in spine
    val scrollOffset: Int = 0, // Pixel offset in WebView
    val addedDate: Long,
    val lastOpenedDate: Long?
)
