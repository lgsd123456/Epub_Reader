package cn.com.lg.epubreader.data.repository

import cn.com.lg.epubreader.data.database.BookDao
import cn.com.lg.epubreader.data.model.Book
import kotlinx.coroutines.flow.Flow

class BookRepository(private val bookDao: BookDao) {
    val allBooks: Flow<List<Book>> = bookDao.getAllBooks()

    suspend fun getBook(id: String): Book? {
        return bookDao.getBookById(id)
    }

    suspend fun addBook(book: Book) {
        bookDao.insertBook(book)
    }

    suspend fun updateBook(book: Book) {
        bookDao.updateBook(book)
    }
    
    suspend fun updateProgress(bookId: String, chapterIndex: Int, scrollOffset: Int) {
        val book = getBook(bookId)
        if (book != null) {
            val updatedBook = book.copy(
                lastReadChapter = chapterIndex,
                scrollOffset = scrollOffset,
                lastOpenedDate = System.currentTimeMillis()
            )
            updateBook(updatedBook)
        }
    }

    suspend fun deleteBook(book: Book) {
        bookDao.deleteBook(book)
    }
}
