package cn.com.lg.epubreader.ui.reader

import android.content.Context
import android.webkit.WebView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.com.lg.epubreader.data.model.Book
import cn.com.lg.epubreader.data.repository.BookRepository
import cn.com.lg.epubreader.epub.FastEpubParser
import cn.com.lg.epubreader.tts.TtsManager
import cn.com.lg.epubreader.ui.theme.ThemeManager
import cn.com.lg.epubreader.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

class ReaderViewModel(
    private val bookId: String,
    private val repository: BookRepository,
    private val context: Context
) : ViewModel() {

    enum class ReadingMode {
        CHAPTER,
        CONTINUOUS_SCROLL
    }

    private val _book = MutableStateFlow<Book?>(null)
    val book = _book.asStateFlow()

    private val _htmlContent = MutableStateFlow<String?>(null)
    val htmlContent = _htmlContent.asStateFlow()

    private val _readingMode = MutableStateFlow(ReadingMode.CONTINUOUS_SCROLL)
    val readingMode = _readingMode.asStateFlow()

    private val _currentChapterIndex = MutableStateFlow(0)
    val currentChapterIndex = _currentChapterIndex.asStateFlow()

    private val _scrollPosition = MutableStateFlow(0)
    val scrollPosition = _scrollPosition.asStateFlow()

    private val _readingProgress = MutableStateFlow(0f)
    val readingProgress = _readingProgress.asStateFlow()

    private val _isPageLoading = MutableStateFlow(false)
    val isPageLoading = _isPageLoading.asStateFlow()

    private val _fontSize = MutableStateFlow(100) // Percentage
    val fontSize = _fontSize.asStateFlow()

    data class TocItem(
        val title: String,
        val href: String,
        val chapterIndex: Int
    )

    private val _toc = MutableStateFlow<List<TocItem>>(emptyList())
    val toc = _toc.asStateFlow()
    
    // List of hrefs for spine items relative to root
    private var spineHrefs: List<String> = emptyList()
    private var zipFile: ZipFile? = null
    private var fastParser: FastEpubParser? = null
    private var continuousStartIndex: Int = 0

    data class ScrollRequest(
        val chapterIndex: Int,
        val offset: Int
    )

    private val _scrollRequest = MutableStateFlow<ScrollRequest?>(null)
    val scrollRequest = _scrollRequest.asStateFlow()

    private var ttsManager: TtsManager? = null
    private val _isTtsReady = MutableStateFlow(false)
    val isTtsReady = _isTtsReady.asStateFlow()
    private val _isTtsPlaying = MutableStateFlow(false)
    val isTtsPlaying = _isTtsPlaying.asStateFlow()
    private val _ttsChapterIndex = MutableStateFlow<Int?>(null)
    val ttsChapterIndex = _ttsChapterIndex.asStateFlow()
    private val _ttsProgress = MutableStateFlow(0f)
    val ttsProgress = _ttsProgress.asStateFlow()
    private val _ttsHighlightElementId = MutableStateFlow<String?>(null)
    val ttsHighlightElementId = _ttsHighlightElementId.asStateFlow()
    private val _ttsHighlightChapterIndex = MutableStateFlow<Int?>(null)
    val ttsHighlightChapterIndex = _ttsHighlightChapterIndex.asStateFlow()

    data class DomTtsItem(
        val elementId: String,
        val text: String
    )

    data class DomTtsRestartRequest(
        val chapterIndex: Int
    )

    private val _domTtsRestartRequest = MutableStateFlow<DomTtsRestartRequest?>(null)
    val domTtsRestartRequest = _domTtsRestartRequest.asStateFlow()
    private var ttsChainId: Long = 0L

    init {
        viewModelScope.launch {
            val fetchedBook = repository.getBook(bookId) ?: return@launch
            _book.value = fetchedBook
            _currentChapterIndex.value = fetchedBook.lastReadChapter
            _scrollPosition.value = fetchedBook.scrollOffset
            
            withContext(Dispatchers.IO) {
                prepareBookContent(fetchedBook)
            }
        }

        ttsManager = TtsManager(context) { success ->
            _isTtsReady.value = success
        }
    }

    private suspend fun prepareBookContent(book: Book) {
        try {
            val file = File(book.filePath)
            if (!file.exists()) return

            withContext(Dispatchers.IO) {
                zipFile = ZipFile(file)
                val parser = FastEpubParser(file)
                fastParser = parser
                spineHrefs = parser.spine

                val tocItems = if (parser.toc.isNotEmpty()) {
                    parser.toc.mapNotNull { navItem ->
                        val index = parser.spine.indexOf(navItem.href)
                        if (index != -1) {
                            TocItem(navItem.title, navItem.href, index)
                        } else {
                            null
                        }
                    }
                } else {
                    parser.spine.mapIndexed { index, href ->
                        TocItem("Chapter ${index + 1}", href, index)
                    }
                }
                _toc.value = tocItems

                withContext(Dispatchers.Main) {
                    if (_readingMode.value == ReadingMode.CONTINUOUS_SCROLL) {
                        loadContinuous(_currentChapterIndex.value)
                    } else {
                        loadChapter(_currentChapterIndex.value)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ReaderViewModel", "Error preparing book", e)
        }
    }

    fun loadChapter(index: Int) {
        if (index < 0 || index >= spineHrefs.size) return
        stopTts()
        
        _isPageLoading.value = true
        _currentChapterIndex.value = index
        _readingProgress.value = 0f
        
        if (index != _book.value?.lastReadChapter) {
            _scrollPosition.value = 0
        }
        
        val href = spineHrefs[index]
        // Use a custom scheme that we will intercept in WebView
        val virtualUrl = "epub://${_book.value?.id}/$href"
        
        android.util.Log.d("ReaderViewModel", "Loading Virtual URL: $virtualUrl")
        
        _htmlContent.value = virtualUrl
        _isPageLoading.value = false
        saveProgress(index)
    }

    fun loadContinuous(index: Int) {
        if (index < 0 || index >= spineHrefs.size) return
        stopTts()

        _isPageLoading.value = true
        continuousStartIndex = index
        _currentChapterIndex.value = index
        _readingProgress.value = 0f
        val virtualUrl = "epub://${_book.value?.id}/__continuous__/index.html"
        _htmlContent.value = virtualUrl
        _isPageLoading.value = false
        saveProgress(index, _scrollPosition.value)
    }

    fun getResourceStream(href: String): java.io.InputStream? {
        val parser = fastParser ?: return null
        val fullPath = parser.getZipPath(href)
        return zipFile?.getEntry(fullPath)?.let { zipFile?.getInputStream(it) }
    }

    fun nextChapter() {
        goToChapter(_currentChapterIndex.value + 1, 0)
    }

    fun prevChapter() {
        goToChapter(_currentChapterIndex.value - 1, 0)
    }

    fun jumpToTocItem(item: TocItem) {
        goToChapter(item.chapterIndex, 0)
    }

    fun updateScrollPosition(scrollY: Int) {
        if (_isPageLoading.value) return // Don't save while loading/restoring
        _scrollPosition.value = scrollY
        saveProgress(_currentChapterIndex.value, scrollY)
    }

    fun updateContinuousPosition(chapterIndex: Int, offset: Int, progress: Float) {
        if (_isPageLoading.value) return
        if (chapterIndex < 0 || chapterIndex >= spineHrefs.size) return
        _currentChapterIndex.value = chapterIndex
        _scrollPosition.value = offset
        _readingProgress.value = progress.coerceIn(0f, 1f)
        saveProgress(chapterIndex, offset)
    }

    fun updateReadingProgress(progress: Float) {
        if (_isPageLoading.value) return
        _readingProgress.value = progress.coerceIn(0f, 1f)
    }

    fun setReadingMode(mode: ReadingMode) {
        if (_readingMode.value == mode) return
        _readingMode.value = mode
        if (mode == ReadingMode.CONTINUOUS_SCROLL) {
            loadContinuous(_currentChapterIndex.value)
        } else {
            loadChapter(_currentChapterIndex.value)
        }
    }

    fun goToChapter(index: Int, offset: Int) {
        if (index < 0 || index >= spineHrefs.size) return
        if (_readingMode.value == ReadingMode.CONTINUOUS_SCROLL) {
            val isContinuousPage = _htmlContent.value?.contains("/__continuous__/index.html") == true
            _currentChapterIndex.value = index
            _scrollPosition.value = offset
            _readingProgress.value = 0f
            if (isContinuousPage) {
                _scrollRequest.value = ScrollRequest(index, offset)
            } else {
                loadContinuous(index)
            }
            saveProgress(index, offset)
        } else {
            _scrollPosition.value = offset
            loadChapter(index)
        }
    }

    fun consumeScrollRequest() {
        _scrollRequest.value = null
    }

    fun getContinuousIndexHtml(): String {
        val bookHost = _book.value?.id ?: bookId
        val startIndex = continuousStartIndex.coerceIn(0, (spineHrefs.size - 1).coerceAtLeast(0))
        val spineJson = spineHrefs.joinToString(
            separator = ",",
            prefix = "[",
            postfix = "]"
        ) { href ->
            "\"" + href
                .replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\""
        }

        return """
            <!doctype html>
            <html>
              <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=5.0" />
                <style>
                  html, body { margin: 0; padding: 0; }
                  body { -webkit-text-size-adjust: 100%; }
                  #container { width: 100%; }
                  iframe.reader-chapter {
                    width: 100%;
                    border: 0;
                    display: block;
                    overflow: hidden;
                  }
                </style>
              </head>
              <body>
                <div id="container"></div>
                <script>
                  (function() {
                    const BOOK_ID = ${bookHost.toJsString()};
                    const SPINE = $spineJson;
                    const START = ${startIndex};
                    const container = document.getElementById('container');
                    let nextIndex = START + 1;
                    let prevIndex = START - 1;

                    function safeGetHeight(doc) {
                      if (!doc) return 0;
                      const body = doc.body;
                      const root = doc.documentElement;
                      return Math.max(
                        body ? body.scrollHeight : 0,
                        root ? root.scrollHeight : 0
                      );
                    }

                    function setIframeHeight(iframe) {
                      try {
                        const doc = iframe.contentDocument;
                        const height = safeGetHeight(doc);
                        if (height > 0) iframe.style.height = height + 'px';
                      } catch (e) {}
                    }

                    window.__epubReaderResizeIframes = function() {
                      const frames = document.querySelectorAll('iframe.reader-chapter');
                      frames.forEach(function(fr) {
                        setIframeHeight(fr);
                        requestAnimationFrame(function() { setIframeHeight(fr); });
                      });
                    };

                    function markReady(iframe) {
                      setIframeHeight(iframe);
                      iframe.dataset.ready = '1';
                      requestAnimationFrame(function() { setIframeHeight(iframe); });
                      setTimeout(function() { setIframeHeight(iframe); }, 200);
                      setTimeout(function() { setIframeHeight(iframe); }, 800);
                    }

                    function createIframe(idx, insertAtTop) {
                      const iframe = document.createElement('iframe');
                      iframe.className = 'reader-chapter';
                      iframe.dataset.index = String(idx);
                      iframe.loading = 'lazy';
                      iframe.src = 'epub://' + BOOK_ID + '/' + SPINE[idx];
                      iframe.addEventListener('load', function() { markReady(iframe); }, { once: true });
                      if (insertAtTop) {
                        container.insertBefore(iframe, container.firstChild);
                      } else {
                        container.appendChild(iframe);
                      }
                      return iframe;
                    }

                    function ensureNext(count) {
                      const toLoad = count || 1;
                      for (let i = 0; i < toLoad; i++) {
                        if (nextIndex >= SPINE.length) return;
                        createIframe(nextIndex, false);
                        nextIndex++;
                      }
                    }

                    function ensurePrev() {
                      if (prevIndex < 0) return;
                      const oldScroll = window.scrollY;
                      const iframe = createIframe(prevIndex, true);
                      prevIndex--;
                      iframe.addEventListener('load', function() {
                        const addedHeight = iframe.getBoundingClientRect().height || 0;
                        window.scrollTo(0, oldScroll + addedHeight);
                      }, { once: true });
                    }

                    let ticking = false;
                    window.addEventListener('scroll', function() {
                      if (ticking) return;
                      ticking = true;
                      requestAnimationFrame(function() {
                        ticking = false;
                        const nearBottom = (window.innerHeight + window.scrollY) > (document.body.scrollHeight - 1200);
                        if (nearBottom) ensureNext(2);
                        const nearTop = window.scrollY < 200;
                        if (nearTop) ensurePrev();
                      });
                    }, { passive: true });

                    window.__epubReaderGetLocation = function() {
                      const frames = Array.prototype.slice.call(document.querySelectorAll('iframe.reader-chapter'));
                      if (frames.length === 0) return { chapterIndex: START, offset: 0, progress: 0 };

                      let active = null;
                      for (let i = 0; i < frames.length; i++) {
                        const rect = frames[i].getBoundingClientRect();
                        if (rect.bottom <= 0) continue;
                        if (rect.top <= 120 && rect.bottom > 120) { active = frames[i]; break; }
                      }
                      if (!active) active = frames[0];
                      const idx = parseInt(active.dataset.index || '0', 10);
                      const offset = Math.max(0, window.scrollY - active.offsetTop);
                      const frameHeight = Math.max(1, active.getBoundingClientRect().height || 1);
                      const maxOffset = Math.max(1, frameHeight - window.innerHeight);
                      const progress = Math.max(0, Math.min(1, offset / maxOffset));
                      return { chapterIndex: idx, offset: offset, progress: progress };
                    };

                    window.__epubReaderScrollTo = function(chapterIndex, offset) {
                      const targetIndex = Math.max(0, Math.min(SPINE.length - 1, (chapterIndex | 0)));
                      const targetOffset = Math.max(0, (offset | 0));

                      function findFrame(idx) {
                        return document.querySelector('iframe.reader-chapter[data-index="' + idx + '"]');
                      }

                      function scrollNow() {
                        const frame = findFrame(targetIndex);
                        if (!frame) return false;
                        window.scrollTo(0, frame.offsetTop + targetOffset);
                        return true;
                      }

                      if (!findFrame(targetIndex)) {
                        container.innerHTML = '';
                        nextIndex = targetIndex + 1;
                        prevIndex = targetIndex - 1;
                        createIframe(targetIndex, false);
                        ensureNext(1);
                      }

                      const frame = findFrame(targetIndex);
                      if (!frame) return;
                      if (frame.dataset.ready === '1') {
                        scrollNow();
                        return;
                      }
                      frame.addEventListener('load', function() {
                        markReady(frame);
                        scrollNow();
                      }, { once: true });
                    };

                    createIframe(START, false);
                    ensureNext(1);
                  })();
                </script>
              </body>
            </html>
        """.trimIndent()
    }

    fun changeFontSize(increase: Boolean) {
        val current = _fontSize.value
        _fontSize.value = if (increase) (current + 10).coerceAtMost(250) else (current - 10).coerceAtLeast(50)
    }

    private fun saveProgress(chapterIndex: Int, scrollY: Int = 0) {
        val currentBook = _book.value ?: return
        viewModelScope.launch {
            repository.updateProgress(currentBook.id, chapterIndex, scrollY)
        }
    }

    fun toggleTts(textToRead: String?) {
        if (_isTtsPlaying.value) {
            stopTts()
            return
        }
        if (textToRead != null) {
            startTtsText(textToRead, autoContinue = false)
        } else {
            startTtsAt(_currentChapterIndex.value, progress = 0f, autoContinue = true)
        }
    }

    fun stopTts() {
        ttsChainId++
        ttsManager?.stop()
        _isTtsPlaying.value = false
        _ttsChapterIndex.value = null
        _ttsProgress.value = 0f
        _ttsHighlightElementId.value = null
        _ttsHighlightChapterIndex.value = null
        _domTtsRestartRequest.value = null
    }

    fun startDomTts(
        chapterIndex: Int,
        items: List<DomTtsItem>,
        startIndex: Int,
        autoContinue: Boolean
    ) {
        stopTts()
        val chainId = ++ttsChainId
        if (!_isTtsReady.value || ttsManager?.isReady() != true) return
        if (chapterIndex < 0 || chapterIndex >= spineHrefs.size) return
        if (items.isEmpty()) return

        val start = startIndex.coerceIn(0, items.size - 1)
        val queue = items.subList(start, items.size)
        speakDomItem(chainId, chapterIndex, queue, 0, autoContinue)
    }

    fun consumeDomTtsRestartRequest() {
        _domTtsRestartRequest.value = null
    }

    private fun speakDomItem(
        chainId: Long,
        chapterIndex: Int,
        items: List<DomTtsItem>,
        index: Int,
        autoContinue: Boolean
    ) {
        if (ttsChainId != chainId) return
        if (index >= items.size) {
            _isTtsPlaying.value = false
            _ttsHighlightElementId.value = null
            _ttsHighlightChapterIndex.value = null
            if (autoContinue && chapterIndex + 1 < spineHrefs.size) {
                val next = chapterIndex + 1
                _domTtsRestartRequest.value = DomTtsRestartRequest(next)
                goToChapter(next, 0)
            }
            return
        }

        val item = items[index]
        _currentChapterIndex.value = chapterIndex
        _ttsChapterIndex.value = chapterIndex
        _ttsProgress.value = 0f
        _ttsHighlightChapterIndex.value = chapterIndex
        _ttsHighlightElementId.value = item.elementId

        val started = ttsManager?.speak(
            item.text,
            flush = true
        ) {
            viewModelScope.launch {
                if (ttsChainId != chainId) return@launch
                speakDomItem(chainId, chapterIndex, items, index + 1, autoContinue)
            }
        } ?: false

        _isTtsPlaying.value = started
        if (!started) {
            _ttsHighlightElementId.value = null
            _ttsHighlightChapterIndex.value = null
        }
    }

    fun startTtsAt(chapterIndex: Int, progress: Float, autoContinue: Boolean) {
        val chainId = ++ttsChainId
        startTtsAtInternal(chainId, chapterIndex, progress.coerceIn(0f, 1f), autoContinue)
    }

    private fun startTtsAtInternal(chainId: Long, chapterIndex: Int, progress: Float, autoContinue: Boolean) {
        if (chapterIndex < 0 || chapterIndex >= spineHrefs.size) return
        viewModelScope.launch {
            if (ttsChainId != chainId) return@launch
            if (!_isTtsReady.value || ttsManager?.isReady() != true) return@launch
            _ttsHighlightElementId.value = null
            _ttsHighlightChapterIndex.value = null

            val rawText = withContext(Dispatchers.IO) {
                loadChapterPlainText(chapterIndex)
            } ?: run {
                if (ttsChainId == chainId) _isTtsPlaying.value = false
                return@launch
            }

            val speakText = sliceTextByProgress(rawText, progress)
            if (speakText.isBlank()) {
                if (ttsChainId == chainId) _isTtsPlaying.value = false
                return@launch
            }

            _currentChapterIndex.value = chapterIndex
            saveProgress(chapterIndex, _scrollPosition.value)
            _ttsChapterIndex.value = chapterIndex
            _ttsProgress.value = progress

            val started = ttsManager?.speak(
                speakText,
                flush = true
            ) {
                viewModelScope.launch {
                    if (ttsChainId != chainId) return@launch
                    if (autoContinue && chapterIndex + 1 < spineHrefs.size) {
                        _scrollPosition.value = 0
                        saveProgress(chapterIndex + 1, 0)
                        _ttsChapterIndex.value = chapterIndex + 1
                        _ttsProgress.value = 0f
                        startTtsAtInternal(chainId, chapterIndex + 1, 0f, autoContinue)
                    } else {
                        _isTtsPlaying.value = false
                        _ttsChapterIndex.value = null
                        _ttsProgress.value = 0f
                    }
                }
            } ?: false

            if (ttsChainId == chainId) {
                _isTtsPlaying.value = started
            }
        }
    }

    fun startTtsText(text: String, autoContinue: Boolean = false) {
        val chainId = ++ttsChainId
        viewModelScope.launch {
            if (ttsChainId != chainId) return@launch
            if (!_isTtsReady.value || ttsManager?.isReady() != true) return@launch
            _ttsHighlightElementId.value = null
            _ttsHighlightChapterIndex.value = null
            val started = ttsManager?.speak(
                text,
                flush = true
            ) {
                viewModelScope.launch {
                    if (ttsChainId != chainId) return@launch
                    _isTtsPlaying.value = false
                    _ttsChapterIndex.value = null
                    _ttsProgress.value = 0f
                }
            } ?: false
            if (ttsChainId == chainId) {
                _isTtsPlaying.value = started
                if (started) {
                    _ttsChapterIndex.value = _currentChapterIndex.value
                    _ttsProgress.value = _readingProgress.value
                } else {
                    _ttsChapterIndex.value = null
                    _ttsProgress.value = 0f
                }
            }
        }
    }

    private fun loadChapterPlainText(chapterIndex: Int): String? {
        if (chapterIndex < 0 || chapterIndex >= spineHrefs.size) return null
        val href = spineHrefs[chapterIndex]
        val stream = getResourceStream(href) ?: return null
        val html = stream.bufferedReader().use { it.readText() }
        return htmlToPlainText(html)
    }

    private fun sliceTextByProgress(text: String, progress: Float): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val p = progress.coerceIn(0f, 1f)
        val start = (trimmed.length * p).toInt().coerceIn(0, trimmed.length - 1)
        val boundaryChars = charArrayOf('。', '！', '？', '.', '!', '?', '\n')
        val boundary = trimmed.lastIndexOfAny(boundaryChars, startIndex = start)
        val from = if (boundary >= 0) (boundary + 1).coerceAtMost(trimmed.length) else start
        return trimmed.substring(from).trim()
    }

    private fun htmlToPlainText(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<head[^>]*>.*?</head>"), " ")
        s = s.replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
        s = s.replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
        s = s.replace(Regex("(?is)<nav[^>]*>.*?</nav>"), " ")
        s = s.replace(Regex("(?is)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?is)</p\\s*>"), "\n")
        s = s.replace(Regex("(?is)</div\\s*>"), "\n")
        s = s.replace(Regex("(?is)<[^>]+>"), " ")
        s = decodeHtmlEntities(s)
        s = s.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        s = s.replace(Regex("\\n\\s+"), "\n")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }

    private fun decodeHtmlEntities(input: String): String {
        var s = input
        s = s.replace("&nbsp;", " ")
        s = s.replace("&amp;", "&")
        s = s.replace("&lt;", "<")
        s = s.replace("&gt;", ">")
        s = s.replace("&quot;", "\"")
        s = s.replace("&#39;", "'")

        s = s.replace(Regex("&#(\\d+);")) { m ->
            val code = m.groupValues[1].toIntOrNull() ?: return@replace m.value
            runCatching { String(Character.toChars(code)) }.getOrElse { m.value }
        }
        s = s.replace(Regex("&#x([0-9a-fA-F]+);")) { m ->
            val code = m.groupValues[1].toIntOrNull(16) ?: return@replace m.value
            runCatching { String(Character.toChars(code)) }.getOrElse { m.value }
        }
        return s
    }
    
    fun toggleTheme() {
        val current = ThemeManager.themeMode.value
        val next = when (current) {
            ThemeMode.LIGHT -> ThemeMode.DARK
            ThemeMode.DARK -> ThemeMode.EYE_CARE
            ThemeMode.EYE_CARE -> ThemeMode.LIGHT
            ThemeMode.SYSTEM -> ThemeMode.LIGHT
        }
        ThemeManager.setThemeMode(next)
    }
    
    override fun onCleared() {
        super.onCleared()
        try {
            zipFile?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        ttsManager?.shutdown()
    }
}

private fun String.toJsString(): String {
    return "\"" + this
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r") + "\""
}
