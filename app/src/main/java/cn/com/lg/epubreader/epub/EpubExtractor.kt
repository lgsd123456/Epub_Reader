package cn.com.lg.epubreader.epub

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class EpubExtractor(private val context: Context) {

    fun extractEpub(inputStream: InputStream, bookId: String): String {
        val outputDir = File(context.filesDir, "books/$bookId")
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        
        // If already extracted, might want to skip or overwrite. 
        // For now, simple logic: if directory has content, assume extracted.
        if (outputDir.list()?.isNotEmpty() == true) {
            return outputDir.absolutePath
        }

        try {
            val zipInputStream = ZipInputStream(inputStream)
            var entry: ZipEntry? = zipInputStream.nextEntry
            val buffer = ByteArray(1024)

            while (entry != null) {
                val newFile = File(outputDir, entry.name)
                
                // Security check for Zip Slip vulnerability
                if (!newFile.canonicalPath.startsWith(outputDir.canonicalPath)) {
                     throw SecurityException("Zip Path Traversal Vulnerability detected")
                }

                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    File(newFile.parent ?: "").mkdirs()
                    val fos = FileOutputStream(newFile)
                    var len: Int
                    while (zipInputStream.read(buffer).also { len = it } > 0) {
                        fos.write(buffer, 0, len)
                    }
                    fos.close()
                }
                entry = zipInputStream.nextEntry
            }
            zipInputStream.closeEntry()
            zipInputStream.close()
        } catch (e: Exception) {
            e.printStackTrace()
            // Cleanup on failure might be good
        }
        
        return outputDir.absolutePath
    }
}
