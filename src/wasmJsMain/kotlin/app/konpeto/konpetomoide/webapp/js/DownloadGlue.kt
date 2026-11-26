package app.konpeto.konpetomoide.webapp.js

import kotlinx.browser.document
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import kotlin.js.JsAny

/*
 * ファイルとして**書き出す**口（K2-8・S-File シーム）。
 *
 * ブラウザで実測済みの 5 手:
 * `createObjectURL` → `a[download]` → append → `click()` → 即 `remove()` → **10 秒後に revoke**。
 * 宣言は kotlinx-browser に揃っているが、**手順を書く場所が無い**と画面ごとに 5 手が duplicated になる
 * （書き出し経路が複数増えても 1 本で足ります）。
 *
 * **revoke のタイマは画面の後始末で消さない**: 消すと URL が永遠に残る（リークのほうが悪い）。
 * 10 秒後に自分が消すだけの自己完結したタイマなので、置いておく
 */

/** ブラウザが書き出しを終えるのを待ってから object URL を返す時間（ms）。 */
private const val REVOKE_DELAY_MS = 10_000

/**
 * このブラウザが `a[download]` を持つか（**実在だけ**を見る）。
 *
 * 書き出しの案内を「ダウンロード」にするか「共有シート」にするかの判定に使う。無い環境でも
 * `a.href` を踏めば開けるので、**false でも書き出し自体は試みる**（開いたファイルを「ファイルに保存」
 * する導線になる）
 *
 * @return `download` 属性が付きそうか
 */
fun supportsDownload(): Boolean {
    val probe = runCatching { document.createElement("a").unsafeCast<MaybeDownload>() }.getOrNull() ?: return false
    return runCatching { probe.download }.getOrNull() != null
}

/** `HTMLAnchorElement` を、`download` の実在だけ見る読み直し（kotlinx-browser は属性の存在を言わない）。 */
private external interface MaybeDownload : JsAny {
    /** `a.download`。 */
    val download: JsAny?
}

/**
 * Blob をユーザーの端末に書き出す（**1 操作 = 1 ファイル**）。
 *
 * iOS Safari は `a[download]` を連打しても 1 枚しか保存できない（実測）。
 * **複数個をまとめて出したいときは共有シート**（`js/ShareGlue.kt`）を使ってここは呼ばない
 *
 * @param name 保存されるファイル名（呼び出し側が決めたもの）
 * @param blob 実体
 * @return 書き出しを開始したか。`URL` が作れない環境は false（**失敗行は呼び出し側が出す**）
 */
fun downloadFile(
    name: String,
    blob: Blob,
): Boolean {
    val url = runCatching { URL.createObjectURL(blob) }.getOrNull() ?: return false
    val body = document.body ?: return false
    val link = document.createElement("a").unsafeCast<HTMLAnchorElement>()
    link.href = url
    link.download = name
    link.rel = "noopener"
    body.appendChild(link)
    link.click()
    link.remove()
    afterMillis(REVOKE_DELAY_MS) { runCatching { URL.revokeObjectURL(url) } }
    return true
}
