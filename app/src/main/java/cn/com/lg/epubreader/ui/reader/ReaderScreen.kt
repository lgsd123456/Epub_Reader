package cn.com.lg.epubreader.ui.reader

import android.app.Activity
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.MimeTypeMap
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import cn.com.lg.epubreader.EpubReaderApp
import cn.com.lg.epubreader.data.repository.BookRepository
import cn.com.lg.epubreader.ui.theme.ThemeManager
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val app = context.applicationContext as EpubReaderApp
    val repository = remember { BookRepository(app.database.bookDao()) }
    val viewModel = remember { ReaderViewModel(bookId, repository, context) }
    
    val htmlContent by viewModel.htmlContent.collectAsState()
    val isTtsPlaying by viewModel.isTtsPlaying.collectAsState()
    val isPageLoading by viewModel.isPageLoading.collectAsState()
    val toc by viewModel.toc.collectAsState()
    val book by viewModel.book.collectAsState()
    val readingMode by viewModel.readingMode.collectAsState()
    val scrollRequest by viewModel.scrollRequest.collectAsState()
    val ttsHighlightElementId by viewModel.ttsHighlightElementId.collectAsState()
    val ttsHighlightChapterIndex by viewModel.ttsHighlightChapterIndex.collectAsState()
    
    // UI state for full screen
    var showUIBars by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val activity = context as? Activity
        val window = activity?.window
        if (window != null) {
            val oldLayoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode
            } else {
                null
            }

            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val attrs = window.attributes
                attrs.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                window.attributes = attrs
            }
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())

            onDispose {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.show(WindowInsetsCompat.Type.systemBars())
                WindowCompat.setDecorFitsSystemWindows(window, true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && oldLayoutInDisplayCutoutMode != null) {
                    val attrs = window.attributes
                    attrs.layoutInDisplayCutoutMode = oldLayoutInDisplayCutoutMode
                    window.attributes = attrs
                }
            }
        } else {
            onDispose {}
        }
    }
    
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    
    // Theme and Font application for WebView
    val themeMode by ThemeManager.themeMode.collectAsState()
    val fontSize by viewModel.fontSize.collectAsState()
    
    val themeScript = remember(themeMode, fontSize) {
        val bg = when(themeMode) {
            cn.com.lg.epubreader.ui.theme.ThemeMode.DARK -> "#121212"
            cn.com.lg.epubreader.ui.theme.ThemeMode.EYE_CARE -> "#F5F0E6"
            else -> "#FFFFFF"
        }
        val text = when(themeMode) {
            cn.com.lg.epubreader.ui.theme.ThemeMode.DARK -> "#E0E0E0"
            cn.com.lg.epubreader.ui.theme.ThemeMode.EYE_CARE -> "#3C3C3C"
            else -> "#1A1A1A"
        }
        """
        (function() {
          function applyToDoc(doc) {
            if (!doc || !doc.body) return;
            doc.body.style.backgroundColor = '$bg';
            doc.body.style.color = '$text';
            doc.body.style.padding = '20px 16px';
            doc.body.style.fontSize = '$fontSize%';
            doc.body.style.lineHeight = '1.6';
          }
          applyToDoc(document);
          var frames = document.querySelectorAll('iframe.reader-chapter');
          for (var i = 0; i < frames.length; i++) {
            try { applyToDoc(frames[i].contentDocument); } catch (e) {}
          }
          try { if (window.__epubReaderResizeIframes) window.__epubReaderResizeIframes(); } catch (e) {}
        })();
        """.trimIndent()
    }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    fun clearTtsHighlight(webView: WebView) {
        webView.evaluateJavascript(
            """
            (function() {
              function clearDoc(doc) {
                if (!doc) return;
                try {
                  var highlighted = doc.querySelectorAll('.tts-highlight');
                  for (var i = 0; i < highlighted.length; i++) {
                    highlighted[i].classList.remove('tts-highlight');
                  }
                } catch (e) {}
              }
              clearDoc(document);
              try {
                var frames = document.querySelectorAll('iframe.reader-chapter');
                for (var i = 0; i < frames.length; i++) {
                  clearDoc(frames[i].contentDocument);
                }
              } catch (e) {}
            })();
            """.trimIndent(),
            null
        )
    }

    fun applyTtsHighlight(webView: WebView, chapterIndex: Int?, elementId: String) {
        val targetIndex = chapterIndex ?: -1
        val safeId = elementId
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        webView.evaluateJavascript(
            """
            (function() {
              var targetIndex = $targetIndex;
              var elementId = "$safeId";
              function ensureStyle(doc) {
                if (!doc) return;
                var style = doc.getElementById('tts_highlight_style');
                if (style) return;
                style = doc.createElement('style');
                style.id = 'tts_highlight_style';
                style.textContent = '.tts-highlight{background:rgba(255,235,59,0.35);border-radius:6px;padding:2px 0;}';
                doc.head && doc.head.appendChild(style);
              }
              function clearAndApply(doc) {
                if (!doc) return;
                ensureStyle(doc);
                try {
                  var highlighted = doc.querySelectorAll('.tts-highlight');
                  for (var i = 0; i < highlighted.length; i++) {
                    highlighted[i].classList.remove('tts-highlight');
                  }
                } catch (e) {}
                var el = doc.getElementById(elementId);
                if (!el) return;
                el.classList.add('tts-highlight');
                try { el.scrollIntoView({block:'center', behavior:'smooth'}); } catch (e) { try { el.scrollIntoView(true); } catch (e2) {} }
              }
              if (targetIndex >= 0) {
                var frame = document.querySelector('iframe.reader-chapter[data-index="' + targetIndex + '"]');
                if (frame && frame.contentDocument) {
                  clearAndApply(frame.contentDocument);
                } else {
                  clearAndApply(document);
                }
              } else {
                clearAndApply(document);
              }
            })();
            """.trimIndent(),
            null
        )
    }

    fun startDomTtsFromCurrentPosition(webView: WebView, autoContinue: Boolean, fallback: () -> Unit) {
        val currentIndex = viewModel.currentChapterIndex.value
        val script = """
            (function() {
              function pickActiveFrame() {
                var frames = Array.prototype.slice.call(document.querySelectorAll('iframe.reader-chapter'));
                if (frames.length === 0) return null;
                for (var i = 0; i < frames.length; i++) {
                  var rect = frames[i].getBoundingClientRect();
                  if (rect.bottom <= 0) continue;
                  if (rect.top <= 120 && rect.bottom > 120) return frames[i];
                }
                return frames[0];
              }

              function ensureStyle(doc) {
                if (!doc) return;
                var style = doc.getElementById('tts_highlight_style');
                if (style) return;
                style = doc.createElement('style');
                style.id = 'tts_highlight_style';
                style.textContent = '.tts-highlight{background:rgba(255,235,59,0.35);border-radius:6px;padding:2px 0;}';
                doc.head && doc.head.appendChild(style);
              }

              function buildFromDoc(doc) {
                if (!doc || !doc.body) return null;
                ensureStyle(doc);
                var selector = 'p, h1, h2, h3, h4, h5, h6, li, blockquote';
                var nodes = Array.prototype.slice.call(doc.querySelectorAll(selector));
                var items = [];
                var elements = [];
                var base = Date.now().toString(36);
                for (var i = 0; i < nodes.length; i++) {
                  var el = nodes[i];
                  var text = '';
                  try { text = (el.innerText || '').trim(); } catch (e) { text = ''; }
                  if (!text) continue;
                  if (!el.id) el.id = 'tts_' + base + '_' + i;
                  items.push({id: el.id, text: text});
                  elements.push(el);
                }
                var startIndex = 0;
                for (var j = 0; j < elements.length; j++) {
                  var n = elements[j];
                  var r = n.getBoundingClientRect();
                  if (r.top <= 120 && r.bottom > 120) { startIndex = j; break; }
                  if (r.bottom > 0) { startIndex = j; break; }
                }
                return {startIndex: startIndex, items: items};
              }

              var chapterIndex = $currentIndex;
              var doc = document;
              var frame = pickActiveFrame();
              if (frame && frame.contentDocument && frame.dataset && frame.dataset.index) {
                doc = frame.contentDocument;
                chapterIndex = parseInt(frame.dataset.index || '$currentIndex', 10);
              }
              var payload = buildFromDoc(doc);
              if (!payload) return null;
              return JSON.stringify({chapterIndex: chapterIndex, startIndex: payload.startIndex, items: payload.items});
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            val jsonText = normalizeEvaluateJavascriptResult(result)
            if (jsonText.isBlank() || jsonText == "null") {
                fallback()
                return@evaluateJavascript
            }
            runCatching {
                val obj = JSONObject(jsonText)
                val chapterIndex = obj.optInt("chapterIndex", currentIndex)
                val startIndex = obj.optInt("startIndex", 0)
                val itemsArray = obj.optJSONArray("items") ?: JSONArray()
                val items = ArrayList<ReaderViewModel.DomTtsItem>(itemsArray.length())
                for (i in 0 until itemsArray.length()) {
                    val it = itemsArray.optJSONObject(i) ?: continue
                    val id = it.optString("id", "")
                    val text = it.optString("text", "")
                    if (id.isNotBlank() && text.isNotBlank()) {
                        items.add(ReaderViewModel.DomTtsItem(id, text))
                    }
                }
                if (items.isEmpty()) {
                    fallback()
                    return@evaluateJavascript
                }
                viewModel.startDomTts(chapterIndex, items, startIndex, autoContinue)
            }.onFailure {
                fallback()
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(12.dp))
                Text(
                    book?.title ?: "Table of Contents",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Divider()
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(toc) { _, item ->
                        NavigationDrawerItem(
                            label = { Text(item.title) },
                            selected = false,
                            onClick = {
                                scope.launch {
                                    drawerState.close()
                                }
                                viewModel.jumpToTocItem(item)
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                    }
                }
            }
        },
        gesturesEnabled = drawerState.isOpen // Only allow gestures to close when already open
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {},
                bottomBar = {
                    if (showUIBars) {
                        BottomAppBar {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = onBack) {
                                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                                    }
                                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                                    }
                                }

                                Spacer(modifier = Modifier.weight(1f))

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { viewModel.prevChapter() }) {
                                        Icon(Icons.Default.SkipPrevious, contentDescription = "Prev Chapter")
                                    }
                                    IconButton(
                                        onClick = {
                                            if (isTtsPlaying) {
                                                viewModel.stopTts()
                                                return@IconButton
                                            }

                                            val webView = webViewRef
                                            if (webView == null) {
                                                viewModel.startTtsAt(
                                                    viewModel.currentChapterIndex.value,
                                                    progress = 0f,
                                                    autoContinue = true
                                                )
                                                return@IconButton
                                            }

                                            startDomTtsFromCurrentPosition(
                                                webView = webView,
                                                autoContinue = true,
                                                fallback = {
                                                    if (readingMode == ReaderViewModel.ReadingMode.CONTINUOUS_SCROLL) {
                                                        webView.evaluateJavascript(
                                                            "window.__epubReaderGetLocation && __epubReaderGetLocation();"
                                                        ) { result ->
                                                            val jsonText = normalizeEvaluateJavascriptResult(result)
                                                            if (jsonText.isBlank() || jsonText == "null") {
                                                                viewModel.startTtsAt(
                                                                    viewModel.currentChapterIndex.value,
                                                                    progress = 0f,
                                                                    autoContinue = true
                                                                )
                                                                return@evaluateJavascript
                                                            }
                                                            runCatching {
                                                                val obj = JSONObject(jsonText)
                                                                val chapterIndex = obj.optInt("chapterIndex", viewModel.currentChapterIndex.value)
                                                                val progress = obj.optDouble("progress", 0.0).toFloat()
                                                                viewModel.startTtsAt(
                                                                    chapterIndex,
                                                                    progress = progress,
                                                                    autoContinue = true
                                                                )
                                                            }.onFailure {
                                                                viewModel.startTtsAt(
                                                                    viewModel.currentChapterIndex.value,
                                                                    progress = 0f,
                                                                    autoContinue = true
                                                                )
                                                            }
                                                        }
                                                    } else {
                                                        webView.evaluateJavascript(
                                                            "(function(){ var h=Math.max(document.body.scrollHeight||0, document.documentElement.scrollHeight||0); var y=window.scrollY||0; var max=Math.max(1, h-window.innerHeight); return JSON.stringify({progress: y/max}); })();"
                                                        ) { result ->
                                                            val jsonText = normalizeEvaluateJavascriptResult(result)
                                                            if (jsonText.isBlank() || jsonText == "null") {
                                                                viewModel.startTtsAt(
                                                                    viewModel.currentChapterIndex.value,
                                                                    progress = 0f,
                                                                    autoContinue = true
                                                                )
                                                                return@evaluateJavascript
                                                            }
                                                            runCatching {
                                                                val obj = JSONObject(jsonText)
                                                                val progress = obj.optDouble("progress", 0.0).toFloat()
                                                                viewModel.startTtsAt(
                                                                    viewModel.currentChapterIndex.value,
                                                                    progress = progress,
                                                                    autoContinue = true
                                                                )
                                                            }.onFailure {
                                                                viewModel.startTtsAt(
                                                                    viewModel.currentChapterIndex.value,
                                                                    progress = 0f,
                                                                    autoContinue = true
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    ) {
                                        Icon(
                                            if (isTtsPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                            contentDescription = if (isTtsPlaying) "Stop" else "Play"
                                        )
                                    }
                                    IconButton(onClick = { viewModel.nextChapter() }) {
                                        Icon(Icons.Default.SkipNext, contentDescription = "Next Chapter")
                                    }
                                }

                                Spacer(modifier = Modifier.weight(1f))

                                IconButton(onClick = { showSettings = true }) {
                                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                                }
                            }
                        }
                    }
                }
            ) { innerPadding ->
                if (showSettings) {
                     Dialog(onDismissRequest = { showSettings = false }) {
                         Surface(
                             shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                             color = MaterialTheme.colorScheme.surface,
                             modifier = Modifier.padding(16.dp)
                         ) {
                             Column(modifier = Modifier.padding(24.dp)) {
                                 Text("Settings", style = MaterialTheme.typography.titleLarge)
                                 Spacer(modifier = Modifier.height(16.dp))

                                 Row(
                                     verticalAlignment = Alignment.CenterVertically,
                                     modifier = Modifier.fillMaxWidth()
                                 ) {
                                     Text("连续滚动", modifier = Modifier.weight(1f))
                                     Switch(
                                         checked = readingMode == ReaderViewModel.ReadingMode.CONTINUOUS_SCROLL,
                                         onCheckedChange = { enabled ->
                                             viewModel.setReadingMode(
                                                 if (enabled) ReaderViewModel.ReadingMode.CONTINUOUS_SCROLL else ReaderViewModel.ReadingMode.CHAPTER
                                             )
                                         }
                                     )
                                 }
                                 Spacer(modifier = Modifier.height(16.dp))
                                 
                                 Text("Font Size: $fontSize%")
                                 Row(verticalAlignment = Alignment.CenterVertically) {
                                     IconButton(onClick = { viewModel.changeFontSize(false) }) {
                                         Icon(Icons.Default.Remove, contentDescription = "Decrease")
                                     }
                                     Spacer(modifier = Modifier.weight(1.0f))
                                     IconButton(onClick = { viewModel.changeFontSize(true) }) {
                                         Icon(Icons.Default.Add, contentDescription = "Increase")
                                     }
                                 }
                                 
                                 Spacer(modifier = Modifier.height(16.dp))
                                 Text("Theme")
                                 Row {
                                     IconButton(onClick = { viewModel.toggleTheme() }) {
                                         Icon(Icons.Default.Refresh, contentDescription = "Toggle Theme")
                                     }
                                 }
                             }
                         }
                     }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(if (showUIBars) innerPadding else PaddingValues(0.dp))
                ) {
                    val initialScroll by viewModel.scrollPosition.collectAsState()
                    var isInitialScrollDone by remember(htmlContent) { mutableStateOf(false) }

                    if (htmlContent != null) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.apply {
                                        javaScriptEnabled = true
                                        allowFileAccess = true
                                        allowContentAccess = true
                                        allowFileAccessFromFileURLs = true
                                        allowUniversalAccessFromFileURLs = true
                                        domStorageEnabled = true
                                        useWideViewPort = false
                                        loadWithOverviewMode = true
                                    }
                                    
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            view?.evaluateJavascript(themeScript, null)
                                            if (!isInitialScrollDone) {
                                                if (viewModel.readingMode.value == ReaderViewModel.ReadingMode.CONTINUOUS_SCROLL) {
                                                    val chapterIndex = viewModel.currentChapterIndex.value
                                                    val offset = viewModel.scrollPosition.value
                                                    view?.evaluateJavascript(
                                                        "window.__epubReaderScrollTo && __epubReaderScrollTo($chapterIndex, $offset);",
                                                        null
                                                    )
                                                } else {
                                                    view?.scrollTo(0, initialScroll)
                                                }
                                                isInitialScrollDone = true
                                                if (viewModel.readingMode.value == ReaderViewModel.ReadingMode.CONTINUOUS_SCROLL) {
                                                    view?.evaluateJavascript(
                                                        "window.__epubReaderGetLocation && __epubReaderGetLocation();"
                                                    ) { result ->
                                                        val jsonText = normalizeEvaluateJavascriptResult(result)
                                                        if (jsonText.isBlank() || jsonText == "null") return@evaluateJavascript
                                                        runCatching {
                                                            val obj = JSONObject(jsonText)
                                                            val idx = obj.optInt("chapterIndex", viewModel.currentChapterIndex.value)
                                                            val off = obj.optInt("offset", viewModel.scrollPosition.value)
                                                            val prog = obj.optDouble("progress", 0.0).toFloat()
                                                            viewModel.updateContinuousPosition(idx, off, prog)
                                                        }
                                                    }
                                                } else {
                                                    view?.evaluateJavascript(
                                                        "(function(){ var h=Math.max(document.body.scrollHeight||0, document.documentElement.scrollHeight||0); var y=window.scrollY||0; var max=Math.max(1, h-window.innerHeight); return JSON.stringify({progress: y/max}); })();"
                                                    ) { result ->
                                                        val jsonText = normalizeEvaluateJavascriptResult(result)
                                                        if (jsonText.isBlank() || jsonText == "null") return@evaluateJavascript
                                                        runCatching {
                                                            val obj = JSONObject(jsonText)
                                                            val prog = obj.optDouble("progress", 0.0).toFloat()
                                                            viewModel.updateReadingProgress(prog)
                                                        }
                                                    }
                                                }

                                                val domTtsRequest = viewModel.domTtsRestartRequest.value
                                                if (domTtsRequest != null && view != null) {
                                                    viewModel.consumeDomTtsRestartRequest()
                                                    startDomTtsFromCurrentPosition(
                                                        webView = view,
                                                        autoContinue = true,
                                                        fallback = { }
                                                    )
                                                }
                                            }
                                        }

                                        override fun shouldInterceptRequest(
                                            view: WebView?,
                                            request: WebResourceRequest?
                                        ): WebResourceResponse? {
                                            val url = request?.url ?: return null
                                            if (url.scheme == "epub") {
                                                val rawPath = url.path ?: return null
                                                if (rawPath == "/__continuous__/index.html") {
                                                    val html = viewModel.getContinuousIndexHtml()
                                                    return WebResourceResponse(
                                                        "text/html",
                                                        "UTF-8",
                                                        ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
                                                    )
                                                }
                                                // Format: epub://bookId/path/to/resource
                                                val path = rawPath.substring(1)
                                                val stream = viewModel.getResourceStream(path)
                                                if (stream != null) {
                                                    val extension = MimeTypeMap.getFileExtensionFromUrl(path)
                                                    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "text/html"
                                                    return WebResourceResponse(mimeType, "UTF-8", stream)
                                                }
                                            }
                                            return super.shouldInterceptRequest(view, request)
                                        }
                                    }

                                    // Track scrolling
                                    var lastSampleTime = 0L
                                    var lastSampleScrollY = 0
                                    setOnScrollChangeListener { v, scrollX, scrollY, oldScrollX, oldScrollY ->
                                        if (viewModel.readingMode.value == ReaderViewModel.ReadingMode.CONTINUOUS_SCROLL) {
                                            val now = SystemClock.uptimeMillis()
                                            if (now - lastSampleTime >= 500 || abs(scrollY - lastSampleScrollY) >= 300) {
                                                lastSampleTime = now
                                                lastSampleScrollY = scrollY
                                                (v as? WebView)?.evaluateJavascript(
                                                    "window.__epubReaderGetLocation && __epubReaderGetLocation();"
                                                ) { result ->
                                                    val jsonText = normalizeEvaluateJavascriptResult(result)
                                                    if (jsonText.isBlank() || jsonText == "null") return@evaluateJavascript
                                                    runCatching {
                                                        val obj = JSONObject(jsonText)
                                                        val chapterIndex = obj.optInt("chapterIndex", viewModel.currentChapterIndex.value)
                                                        val offset = obj.optInt("offset", 0)
                                                        val progress = obj.optDouble("progress", 0.0).toFloat()
                                                        viewModel.updateContinuousPosition(chapterIndex, offset, progress)
                                                    }
                                                }
                                            }
                                        } else {
                                            viewModel.updateScrollPosition(scrollY)
                                            val now = SystemClock.uptimeMillis()
                                            if (now - lastSampleTime >= 500 || abs(scrollY - lastSampleScrollY) >= 300) {
                                                lastSampleTime = now
                                                lastSampleScrollY = scrollY
                                                (v as? WebView)?.evaluateJavascript(
                                                    "(function(){ var h=Math.max(document.body.scrollHeight||0, document.documentElement.scrollHeight||0); var y=window.scrollY||0; var max=Math.max(1, h-window.innerHeight); return JSON.stringify({progress: y/max}); })();"
                                                ) { result ->
                                                    val jsonText = normalizeEvaluateJavascriptResult(result)
                                                    if (jsonText.isBlank() || jsonText == "null") return@evaluateJavascript
                                                    runCatching {
                                                        val obj = JSONObject(jsonText)
                                                        val progress = obj.optDouble("progress", 0.0).toFloat()
                                                        viewModel.updateReadingProgress(progress)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // Handle clicks to toggle UI
                                    setOnTouchListener { v, event ->
                                        if (event.action == android.view.MotionEvent.ACTION_UP) {
                                            // Detect a single tap in the center area
                                            val width = v.width
                                            val height = v.height
                                            val x = event.x
                                            val y = event.y
                                            
                                            if (x > width * 0.2 && x < width * 0.8 && y > height * 0.2 && y < height * 0.8) {
                                                showUIBars = !showUIBars
                                            }
                                        }
                                        false // Allow subsequent processing
                                    }
                                }
                            },
                            update = { webView ->
                                webViewRef = webView
                                if (webView.url != htmlContent) {
                                    htmlContent?.let { 
                                        isInitialScrollDone = false
                                        webView.loadUrl(it) 
                                    }
                                }
                                webView.evaluateJavascript(themeScript, null)
                            }
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }

            // Custom Edge Detector to open drawer
            if (!drawerState.isOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(20.dp) // Only response to swipe from the far left 20dp
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures { change, dragAmount ->
                                if (dragAmount > 50) { // Require a significant right swipe
                                    scope.launch { drawerState.open() }
                                }
                            }
                        }
                )
            }
        }
    }

    LaunchedEffect(scrollRequest, webViewRef) {
        val req = scrollRequest ?: return@LaunchedEffect
        webViewRef?.evaluateJavascript(
            "window.__epubReaderScrollTo && __epubReaderScrollTo(${req.chapterIndex}, ${req.offset});",
            null
        )
        viewModel.consumeScrollRequest()
    }

    LaunchedEffect(ttsHighlightElementId, ttsHighlightChapterIndex, webViewRef) {
        val webView = webViewRef ?: return@LaunchedEffect
        val elementId = ttsHighlightElementId
        if (elementId.isNullOrBlank()) {
            clearTtsHighlight(webView)
        } else {
            applyTtsHighlight(webView, ttsHighlightChapterIndex, elementId)
        }
    }
}

private fun normalizeEvaluateJavascriptResult(value: String?): String {
    val raw = value?.trim() ?: return ""
    if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"') {
        return raw.substring(1, raw.length - 1)
            .replace("\\\\", "\\")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
    }
    return raw
}
