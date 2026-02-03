package cn.com.lg.epubreader.epub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import nl.siegmann.epublib.domain.Book
import nl.siegmann.epublib.epub.EpubReader
import java.io.InputStream

class EpubParser {

    fun parseEpub(inputStream: InputStream): Book {
        return EpubReader().readEpub(inputStream)
    }

    fun getCoverImage(book: Book): Bitmap? {
        val coverImage = book.coverImage
        return if (coverImage != null) {
            BitmapFactory.decodeStream(coverImage.inputStream)
        } else {
            null
        }
    }
    
    // Helper to get simple metadata
    data class EpubMetadata(
        val title: String,
        val author: String
    )
    
    fun getMetadata(book: Book): EpubMetadata {
        val title = book.title.ifEmpty { "Unknown Title" }
        val author = if (book.metadata.authors.isNotEmpty()) {
            book.metadata.authors.joinToString(", ") { "${it.firstname} ${it.lastname}".trim() }
        } else {
            "Unknown Author"
        }
        return EpubMetadata(title, author)
    }
}
