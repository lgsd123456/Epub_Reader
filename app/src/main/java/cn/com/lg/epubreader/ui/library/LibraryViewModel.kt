package cn.com.lg.epubreader.ui.library

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.com.lg.epubreader.data.model.Book
import cn.com.lg.epubreader.data.repository.BookRepository
import cn.com.lg.epubreader.epub.EpubParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class LibraryViewModel(
    private val repository: BookRepository,
    private val context: Context
) : ViewModel() {

    val books = repository.allBooks

    fun importBook(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. Copy file to internal storage
                val contentResolver = context.contentResolver
                val inputStream = contentResolver.openInputStream(uri) ?: return@launch
                
                // Temp file for parsing
                val tempFile = File(context.cacheDir, "temp.epub")
                val fos = FileOutputStream(tempFile)
                inputStream.copyTo(fos)
                inputStream.close()
                fos.close()

                // 2. Parse Metadata
                val parser = EpubParser()
                // Re-open stream from temp file for parsing
                val displayBook = parser.parseEpub(tempFile.inputStream())
                val metadata = parser.getMetadata(displayBook)
                
                // 3. Create Book Entity
                val bookId = UUID.randomUUID().toString()
                val internalFile = File(context.filesDir, "$bookId.epub")
                tempFile.copyTo(internalFile, overwrite = true)
                tempFile.delete()

                val newBook = Book(
                    id = bookId,
                    title = metadata.title,
                    author = metadata.author,
                    coverPath = null, // TODO: Save cover image
                    filePath = internalFile.absolutePath,
                    extractedPath = null, // Will extract on open
                    addedDate = System.currentTimeMillis(),
                    lastOpenedDate = null
                )

                // 4. Save to DB
                repository.addBook(newBook)

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    fun deleteBook(book: Book) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                File(book.filePath).delete()
                book.extractedPath?.let { File(it).deleteRecursively() }
                repository.deleteBook(book)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
