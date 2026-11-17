package me.rerere.rikkahub.data.ai.tools

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import java.net.URI

private const val DEFAULT_OPEN_CHARS = 12_000
private const val DEFAULT_READ_CHARS = 8_000
private const val MAX_READ_CHARS = 40_000
private const val DEFAULT_OPEN_TIMEOUT_MS = 30_000L
private const val MAX_OPEN_TIMEOUT_MS = 60_000L
private const val SCRIPT_TIMEOUT_MS = 15_000L

data class BrowserPage(
    val url: String,
    val title: String,
    val text: String,
)

/**
 * 离屏 WebView 浏览器, 作为网络搜索的补充: 需要执行 JavaScript 才显示的页面也能读出正文。
 *
 * 进程内复用一个 WebView (cookie 登录态得以保留), 同一时刻只服务一个页面:
 * 工具调用串行执行, [mutex] 保证并发调用不会互相污染页面状态。
 */
class AgentBrowser(context: Context) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var webView: WebView? = null
    private var currentPage: BrowserPage? = null

    /** 打开 [url] 并读出正文; 超时或加载失败时抛出带原因的异常 */
    suspend fun open(url: String, timeoutMillis: Long = DEFAULT_OPEN_TIMEOUT_MS): BrowserPage =
        mutex.withLock {
            val target = normalizeBrowserUrl(url)
            val page = withContext(Dispatchers.Main.immediate) {
                loadPage(target, timeoutMillis.coerceIn(5_000L, MAX_OPEN_TIMEOUT_MS))
            }
            currentPage = page
            page
        }

    /** 从最近打开的页面里截取一段正文 */
    fun read(offset: Int, maxChars: Int): PageSlice {
        val page = currentPage ?: error("No page has been opened yet. Call browser_open first.")
        val chars = maxChars.coerceIn(256, MAX_READ_CHARS)
        val start = offset.coerceIn(0, page.text.length)
        val end = (start + chars).coerceAtMost(page.text.length)
        return PageSlice(
            url = page.url,
            title = page.title,
            offset = start,
            text = page.text.substring(start, end),
            totalChars = page.text.length,
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun loadPage(target: String, timeoutMillis: Long): BrowserPage {
        val view = ensureWebView()
        val finished = CompletableDeferred<LoadOutcome>()
        var httpStatus: Int? = null
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                finished.complete(LoadOutcome.Done)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    finished.complete(LoadOutcome.Failed(error?.description?.toString() ?: "page load failed"))
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                if (request?.isForMainFrame == true) {
                    httpStatus = errorResponse?.statusCode
                }
            }

            // 页面只允许留在内置浏览器里, intent://、market:// 等外部 scheme 一律拦截
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val scheme = request?.url?.scheme?.lowercase()
                return scheme != "http" && scheme != "https"
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                if (webView === view) webView = null
                view?.destroy()
                finished.complete(LoadOutcome.Failed("The browser process crashed; retry the request"))
                return true
            }
        }
        try {
            view.loadUrl(target)
            val outcome = withTimeoutOrNull(timeoutMillis) { finished.await() }
                ?: error("Page load timed out after ${timeoutMillis / 1000}s: $target")
            if (outcome is LoadOutcome.Failed) error("${outcome.message}: $target")
        } finally {
            // 取消或超时后停止加载, 避免旧页面在新请求开始前还在改写状态
            if (!finished.isCompleted) runCatching { view.stopLoading() }
        }
        httpStatus?.takeIf { it >= 400 }?.let { error("The page returned HTTP $it: $target") }
        return extractPage()
    }

    private suspend fun extractPage(): BrowserPage {
        val view = webView ?: error("Browser is not available")
        val raw = CompletableDeferred<String?>()
        view.evaluateJavascript(PAGE_TEXT_SCRIPT) { raw.complete(it) }
        val result = withTimeoutOrNull(SCRIPT_TIMEOUT_MS) { raw.await() }
            ?: error("Reading the page timed out")
        return parseBrowserPage(result)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView {
        webView?.let { return it }
        val wrapper = MutableContextWrapper(appContext)
        val view = WebView(wrapper).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowContentAccess = false
            settings.allowFileAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.mediaPlaybackRequiresUserGesture = true
            settings.setSupportZoom(false)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        layoutOffscreen(view)
        webView = view
        return view
    }

    /** WebView 不挂在窗口上时不会自动布局; innerText 依赖布局, 所以先按屏幕尺寸离屏测量一次 */
    private fun layoutOffscreen(view: WebView) {
        if (view.width > 0 && view.height > 0) return
        val metrics = appContext.resources.displayMetrics
        val width = metrics.widthPixels.coerceIn(720, 1_440)
        val height = metrics.heightPixels.coerceIn(1_280, 2_560)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    private sealed interface LoadOutcome {
        data object Done : LoadOutcome
        data class Failed(val message: String) : LoadOutcome
    }

    private companion object {
        val PAGE_TEXT_SCRIPT = """
            (function () {
              try {
                var root = document.querySelector('article') || document.querySelector('main') || document.body;
                if (!root) {
                  return JSON.stringify({ url: location.href || '', title: document.title || '', text: '' });
                }
                var clone = root.cloneNode(true);
                var dropped = clone.querySelectorAll('script,style,noscript,template,svg,canvas,iframe');
                for (var i = 0; i < dropped.length; i++) { dropped[i].remove(); }
                var blocks = clone.querySelectorAll(
                  'p,div,li,h1,h2,h3,h4,h5,h6,tr,section,article,header,footer,pre,blockquote,br'
                );
                for (var j = 0; j < blocks.length; j++) {
                  blocks[j].appendChild(document.createTextNode('\n'));
                }
                var text = (clone.textContent || '')
                  .replace(/[ \t ]+/g, ' ')
                  .replace(/\n[ \t]+/g, '\n')
                  .replace(/[ \t]+\n/g, '\n')
                  .replace(/\n{3,}/g, '\n\n')
                  .trim();
                return JSON.stringify({
                  url: location.href || '',
                  title: document.title || '',
                  text: text.slice(0, 500000)
                });
              } catch (e) {
                return JSON.stringify({ error: String(e) });
              }
            })()
        """.trimIndent()
    }
}

data class PageSlice(
    val url: String,
    val title: String,
    val offset: Int,
    val text: String,
    val totalChars: Int,
)

internal fun createBrowserTools(browser: AgentBrowser): List<Tool> = listOf(
    createBrowserOpenTool(browser),
    createBrowserReadTool(browser),
)

private fun createBrowserOpenTool(browser: AgentBrowser) = Tool(
    name = "browser_open",
    description = """
        Open a web page in the built-in off-screen browser and return its readable text.
        Use this as a supplement to web search when a page needs JavaScript to render, or when search
        results are only snippets and you need the full content of a specific result.
        Only http and https URLs are supported; the page runs in the app, not in an external browser.
        Long pages are truncated here; call browser_read with an offset to continue.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "Page URL. A missing scheme is treated as https.")
                })
                put("max_chars", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Characters of body text to return now. Default $DEFAULT_OPEN_CHARS, max $MAX_READ_CHARS."
                    )
                })
                put("timeout_seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "Load timeout in seconds. Default 30, max 60.")
                })
            },
            required = listOf("url"),
        )
    },
    execute = {
        val params = it.jsonObject
        val url = params.string("url")?.takeIf { it.isNotBlank() } ?: error("url is required")
        val maxChars = params.intValue("max_chars", DEFAULT_OPEN_CHARS, 256, MAX_READ_CHARS)
        val timeoutSeconds = params.intValue("timeout_seconds", 30, 5, 60)
        val page = browser.open(url, timeoutSeconds * 1_000L)
        val text = page.text.take(maxChars)
        val payload = buildJsonObject {
            put("url", page.url)
            put("title", page.title)
            put("total_chars", page.text.length)
            put("returned_chars", text.length)
            put("truncated", text.length < page.text.length)
            put("text", text)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

private fun createBrowserReadTool(browser: AgentBrowser) = Tool(
    name = "browser_read",
    description = """
        Read another slice of the page most recently opened by browser_open.
        Use offset with the previous total_chars to continue through a long page.
        Opening a new URL replaces the cached page.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("offset", buildJsonObject {
                    put("type", "integer")
                    put("description", "Start character offset in the cached page text. Default 0.")
                })
                put("max_chars", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Characters to return. Default $DEFAULT_READ_CHARS, max $MAX_READ_CHARS."
                    )
                })
            },
        )
    },
    execute = {
        val params = it.jsonObject
        val offset = params.intValue("offset", 0, 0, 500_000)
        val maxChars = params.intValue("max_chars", DEFAULT_READ_CHARS, 256, MAX_READ_CHARS)
        val slice = browser.read(offset, maxChars)
        val payload = buildJsonObject {
            put("url", slice.url)
            put("title", slice.title)
            put("offset", slice.offset)
            put("total_chars", slice.totalChars)
            put("returned_chars", slice.text.length)
            put("truncated", slice.offset + slice.text.length < slice.totalChars)
            put("text", slice.text)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

private fun kotlinx.serialization.json.JsonObject.intValue(
    name: String,
    default: Int,
    min: Int,
    max: Int,
): Int = this[name]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(min, max) ?: default

/** 只允许 http/https; 没写 scheme 时按 https 处理 */
internal fun normalizeBrowserUrl(raw: String): String {
    val trimmed = raw.trim()
    require(trimmed.isNotEmpty()) { "url is required" }
    val candidate = if ("://" in trimmed) trimmed else "https://$trimmed"
    val uri = try {
        URI(candidate)
    } catch (e: Exception) {
        throw IllegalArgumentException("Invalid URL: $raw", e)
    }
    require(uri.scheme?.lowercase() in setOf("http", "https")) {
        "Only http and https URLs are supported: $raw"
    }
    require(!uri.host.isNullOrBlank()) { "URL must include a host: $raw" }
    return uri.toString()
}

/** evaluateJavascript 的返回值可能是 JSON 对象本身, 也可能是被 JSON 编码过的字符串 */
internal fun parseBrowserPage(raw: String?): BrowserPage {
    if (raw.isNullOrBlank() || raw == "null") error("The page did not return readable text")
    fun JsonElement.asPageObject(): JsonObject? = when (this) {
        is JsonObject -> this
        is JsonPrimitive -> contentOrNull
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?.let { text -> runCatching { JsonInstant.parseToJsonElement(text) as? JsonObject }.getOrNull() }
        else -> null
    }
    val obj = runCatching { JsonInstant.parseToJsonElement(raw).asPageObject() }.getOrNull()
        ?: error("The page did not return readable text")
    obj["error"]?.jsonPrimitive?.contentOrNull?.let { error("Page script failed: $it") }
    return BrowserPage(
        url = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    )
}
