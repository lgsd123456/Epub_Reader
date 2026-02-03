# Epub_Reader

一个基于 Kotlin + Jetpack Compose 的本地 EPUB 电子书阅读器，包含书架管理、WebView 阅读渲染、目录跳转、阅读进度保存，以及 TextToSpeech 朗读（支持从当前位置开始、朗读高亮、自动续读下一章）等能力。

## 功能

- 书架
  - 通过系统文件选择器导入本地文件（OpenDocument）
  - 书籍列表展示与删除
  - 本地保存书籍元信息与阅读进度
- 阅读器
  - WebView 渲染 EPUB 内容（自定义 `epub://` 虚拟地址加载）
  - 目录（TOC）侧边栏，支持章节跳转
  - 两种阅读模式：
    - 连续滚动（将多章拼接为一页，滚动阅读）
    - 单章模式（按章切换）
  - 字体大小调整、主题切换（浅色/深色/护眼）
- 朗读（TTS）
  - 点击朗读后从“当前屏幕位置附近的段落”开始朗读
  - 朗读时对当前正在朗读的段落进行背景高亮，便于定位
  - 自动续读：当前章节朗读完自动进入下一章继续朗读
  - 文本清理：对抽取到的段落文本做 trim/空白过滤，避免无效播报
- 沉浸式全屏阅读
  - 阅读页不显示应用顶部标题栏
  - 隐藏系统状态栏/导航栏（时间、电量等），支持手势临时唤出
  - 支持“刘海/挖孔屏”区域显示（Android 9+）

## 关键实现

### 1) EPUB 解析与资源加载

- EPUB 文件按 ZIP 读取，通过解析 spine/TOC 获取章节顺序与目录数据。
- 阅读器对 WebView 使用 `epub://{bookId}/{href}` 形式的虚拟 URL，并在 `WebViewClient` 中拦截请求，将对应章节 HTML / 图片 / CSS 等资源从 EPUB ZIP 中读取后返回给 WebView。
- 相关代码：
  - 解析：`cn.com.lg.epubreader.epub.FastEpubParser`
  - 资源读取/章节加载：`cn.com.lg.epubreader.ui.reader.ReaderViewModel`
  - WebView 拦截与渲染：`cn.com.lg.epubreader.ui.reader.ReaderScreen`

### 2) 阅读 UI（Compose + Navigation）

- 应用入口通过 `navigation-compose` 在书架与阅读页之间切换。
- 阅读页使用 `ModalNavigationDrawer` 展示目录侧栏；内容区域为 WebView（AndroidView）。
- 相关代码：
  - 导航：`cn.com.lg.epubreader.MainActivity`
  - 书架：`cn.com.lg.epubreader.ui.library.LibraryScreen`
  - 阅读：`cn.com.lg.epubreader.ui.reader.ReaderScreen`

### 3) 阅读进度保存

- 使用 Room 持久化书籍信息（文件路径、标题/作者、最后阅读章节、滚动偏移等）。
- 连续滚动模式下，会根据 WebView 回传的位置更新“当前章节索引 + 章节内偏移 + 进度”；单章模式则按章节保存。
- 相关代码：
  - 数据库：`cn.com.lg.epubreader.data.database.AppDatabase`
  - 实体/DAO：`cn.com.lg.epubreader.data.model.Book`、`cn.com.lg.epubreader.data.database.BookDao`
  - 进度更新：`cn.com.lg.epubreader.ui.reader.ReaderViewModel`

### 4) 朗读（TextToSpeech）与 WebView 高亮联动

- `TtsManager` 封装 Android `TextToSpeech`：
  - 对文本按句号/问号/叹号等做拆分，并限制单段长度，避免一次 speak 过长导致失败
  - 通过 `UtteranceProgressListener` 在最后一段播报完成时触发回调，用于自动续读
- 阅读页通过 `evaluateJavascript` 从 DOM 中抽取段落（带 elementId + 文本），并计算“当前可视位置附近”的起始段落索引，实现“从当前位置开始朗读”。
- 朗读过程中，ViewModel 维护当前正在朗读的段落 elementId；阅读页监听该状态并对 WebView 注入 JS 更新样式，实现朗读高亮。
- 相关代码：
  - TTS 封装：`cn.com.lg.epubreader.tts.TtsManager`
  - 朗读链路/状态：`cn.com.lg.epubreader.ui.reader.ReaderViewModel`
  - DOM 抽取/高亮注入：`cn.com.lg.epubreader.ui.reader.ReaderScreen`

### 5) 沉浸式全屏与挖孔屏显示

- 阅读页进入时设置 `WindowCompat.setDecorFitsSystemWindows(false)` 并隐藏 `systemBars()`。
- Android 9+ 额外设置 `layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`，允许内容延伸到挖孔/刘海区域。
- 阅读页离开时恢复系统栏与 cutout 配置，避免影响全局页面。

## 构建与运行

- Android Studio 打开项目后直接运行 `app` 模块即可。
- 建议使用 JDK 17（Android Gradle Plugin 8.x 通常要求较高的 Java 版本）。

