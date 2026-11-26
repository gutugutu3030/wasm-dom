package app.konpeto.konpetomoide.webapp.js

import kotlinx.browser.window
import org.w3c.files.Blob
import kotlin.js.JsAny

/*
 * `fetch` の宣言（**probe 専用**・K1-5）。
 *
 * 読む側に**サーバが無い**ので、画面から fetch を発射することは原則無い。
 * ここにあるのは**同梱アセットを読むためだけ**（EXIF の実測に使う画像は、ブラウザに
 * デコードしてもらわないと作れない ―― 実測口は読み手側が持っている）
 */

/** `window.fetch`（`kotlinx-browser` は wasm 側で未宣言）。 */
external interface FetchGlobal : JsAny {
    /**
     * URL を取りに行く。
     *
     * @param input URL
     * @return `Promise<Response>`。**ネットワーク失敗も resolve される**ので `ok` を見る
     */
    fun fetch(input: String): JsPromise?
}

/** `Response`（`ok` と `blob()` だけ）。 */
external interface FetchResponse : JsAny {
    /** 2xx か。 */
    val ok: Boolean

    /** ステータス（行に出す）。 */
    val status: Int

    /**
     * 本文を Blob で読む。
     *
     * @return `Promise<Blob>`
     */
    fun blob(): JsPromise?
}

/**
 * `fetch` 1 回の結果。
 *
 * @param ok HTTP 2xx か
 * @param status ステータス。**`fetch` 自体が呼べなければ `null`**
 * @param blob 本文。読めなければ `null`
 */
class Fetched(
    val ok: Boolean,
    val status: Int?,
    val blob: Blob?,
)

/**
 * 同梱アセットを Blob で読む。
 *
 * **`fetch` はネットワーク失敗でも throw しない**（resolve される）ので `ok` を見る。
 * 404 とデコード失敗を切り分けられるように、ステータスも返す
 *
 * @param url 読む URL
 * @return 読めた実体と、そのようす
 */
suspend fun fetchBlob(url: String): Fetched {
    val promise = runCatching { window.unsafeCast<FetchGlobal>().fetch(url) }.getOrNull()
        ?: return Fetched(ok = false, status = null, blob = null)
    val response = promise.awaitJs()?.unsafeCast<FetchResponse>()
        ?: return Fetched(ok = false, status = null, blob = null)
    val ok = runCatching { response.ok }.getOrDefault(false)
    val status = runCatching { response.status }.getOrNull()
    val blob = response.blob()?.awaitJs()?.unsafeCast<Blob>()
    return Fetched(ok = ok, status = status, blob = blob)
}
