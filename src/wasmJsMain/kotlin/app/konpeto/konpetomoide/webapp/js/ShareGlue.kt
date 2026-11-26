package app.konpeto.konpetomoide.webapp.js

import kotlinx.browser.window
import org.w3c.files.File
import kotlin.js.JsAny
import kotlin.js.JsArray

/*
 * `navigator.share` / `navigator.canShare`（K2-7・S-Share シームの口）。
 *
 * `kotlinx-browser` 0.5.0 は **`share` / `canShare` / `ShareData` を 1 つも宣言していない**
 * （sources jar を全文検索して 0 件）。`external interface` + `unsafeCast` で
 * 必要な形だけ言い直す。
 *
 * 注意していること:
 *
 * - **`share` が在るだけでは足りない**（`canShare({files})` が偽のブラウザがある）。両方真のときだけ
 *   共有する
 * - **実在確認は `JsAny?` で宣言する**（`js/JsGlue.kt` の実測: `undefined` は `null` として落ちる）。
 *   非対応ブラウザで呼ぶと `TypeError` になるので、呼ぶ前に見る
 * - **共有シートを閉じただけ（`AbortError`）は失敗ではない**。`awaitJs` は失敗を `null` に丸めるので、
 *   名前を見る [awaitOrThrow] を使う（`docs/kb-wasm-bridge.md` §B-2）
 * - data の皿は `jsJsonParse("{}")`（リテラルは書けない）。**触っていない `text` / `url` は
 *   `undefined` のまま**（`null` を入れると Safari が弾く）
 */

/** `navigator` を、共有が**有るか無いかを見る**口。 */
external interface MaybeShare : JsAny {
    /** `navigator.share`。無ければ `undefined` → `null`。 */
    val share: JsAny?

    /** `navigator.canShare`。無ければ `undefined` → `null`。 */
    val canShare: JsAny?
}

/** `navigator` を、共有を**呼ぶ**口。 */
external interface NavigatorShare : JsAny {
    /**
     * 共有シートを出す。
     *
     * @param data `{ files, title }` の皿（[newSharePayload] で作る）
     * @return `Promise<void>`。**ユーザーが閉じると `AbortError` で失敗する**
     */
    fun share(data: JsAny): JsPromise?

    /**
     * 共有できるか。
     *
     * @param data 共有しようとする皿
     * @return 共有できるか。取れないブラウザは `null`
     */
    fun canShare(data: JsAny): Boolean?
}

/** `navigator.share(data)` の `data`。 */
external interface SharePayload : JsAny {
    /** 共有する `File` の並び（JS 配列）。 */
    var files: JsAny?

    /** 共有シートに出す題。 */
    var title: String?
}

/**
 * 共有が失敗した（**`DOMException.name` を落とさない**）。
 *
 * @param errorName `DOMException.name`（`"NotAllowedError"` 等）
 */
class ShareException(
    val errorName: String?,
) : Throwable("共有に失敗しました（${errorName ?: "名前が取れない"}）")

/** 共有を呼べる `navigator`。無いブラウザは `null`。 */
private fun shareNavigator(): NavigatorShare? {
    val maybe = runCatching { window.navigator.unsafeCast<MaybeShare>() }.getOrNull() ?: return null
    val hasBoth = runCatching { maybe.share != null && maybe.canShare != null }.getOrDefault(false)
    if (!hasBoth) return null
    return runCatching { window.navigator.unsafeCast<NavigatorShare>() }.getOrNull()
}

/**
 * 共有の皿を作る（`{ files }`。`title` は後から足す）。
 *
 * @param files 共有する `File` の JS 配列
 * @return 皿。`JSON.parse` が使えない環境は `null`
 */
private fun newSharePayload(files: JsArray<JsAny?>): SharePayload? =
    jsJsonParse("{}")
        ?.let { value -> runCatching { value.unsafeCast<SharePayload>() }.getOrNull() }
        ?.apply { this.files = files }

/**
 * このブラウザが共有シートを持つか（**実在だけ**を見る。中身は問わない）。
 *
 * 設定画面・probe はこれで「共有 / ダウンロード」のどちらの導線かを決める。
 * **実際に共有できるかは [canShareFiles]**がファイル列を渡して確かめる
 *
 * @return `navigator.share` と `navigator.canShare` が両方関数として在るか
 */
fun hasShare(): Boolean =
    shareNavigator() != null

/**
 * この並びを共有できるか（**`share` の存在だけでは不十分**）。
 *
 * @param files 共有しようとする `File` の JS 配列
 * @return 共有できるか。非対応ブラウザは `false`（→ ダウンロードへ倒す）
 */
fun canShareFiles(files: JsArray<JsAny?>): Boolean {
    val share = shareNavigator() ?: return false
    val payload = newSharePayload(files) ?: return false
    return runCatching { share.canShare(payload) == true }.getOrDefault(false)
}

/**
 * ファイルを共有シートに出す（**複数枚を 1 回で出せる唯一のまともな口**・iOS の「画像を保存」はここ）。
 *
 * @param files 共有するファイル
 * @param title 共有シートに出す題（冊名）
 * @return 共有した枚数。**ユーザーがシートを閉じただけ（`AbortError`）は 0**
 *   （失敗ではない・`docs/kb-wasm-bridge.md` §B-2）
 * @throws ShareException 共有が失敗した（`NotAllowedError` 等）
 * @throws IllegalStateException 共有が使えない・皿を作れない（呼び出し側はダウンロードに倒す）
 */
suspend fun shareFiles(
    files: List<File>,
    title: String,
): Int {
    val array = newJsArray() ?: error("JS 配列を作れません（共有に出せません）")
    files.forEach { file -> array.pushJs(file) }
    val share = shareNavigator() ?: error("このブラウザは共有を使えません")
    val payload =
        newSharePayload(array)?.apply { this.title = title }
            ?: error("共有の皿を作れません（`JSON.parse` が使えません）")
    val promise = runCatching { share.share(payload) }.getOrNull() ?: error("共有を呼べません")
    return runCatching { promise.awaitOrThrow() }.fold(
        onSuccess = { files.size },
        onFailure = { reason ->
            val name = (reason as? JsReject)?.errorName
            if (name == "AbortError") 0 else throw ShareException(name)
        },
    )
}
