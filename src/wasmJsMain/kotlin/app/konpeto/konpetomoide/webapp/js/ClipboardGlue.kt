package app.konpeto.konpetomoide.webapp.js

import kotlinx.browser.window
import org.w3c.files.Blob
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.js.JsAny
import kotlin.js.JsArray

/*
 * `navigator.clipboard.read()`（K2-7・S-Clip シームの口）。
 *
 * `kotlinx-browser` 0.5.0 は `Clipboard.read()` を **`Promise<DataTransfer>` と宣言している**が、
 * 実物は `Promise<ClipboardItem[]>`（型が違う）。`ClipboardItem` は未宣言。なので
 * **呼び出し元側を言い直す**（`js/DomGlue.kt` の `Canvas2dProvider` と同じ手・仕様 §8.2 J6〜J9）。
 *
 * 非安全文脈（LAN `http://192.168.x.x`）では `navigator.clipboard` **自体が `undefined`** なので、
 * 実在は `JsAny?` で宣言して `null` として落ちるのを見る（`js/JsGlue.kt` の実測）。
 * **メソッドも `JsAny?` で見てから呼ぶ**（無いものに `read()` を呼ぶと `TypeError`）。
 *
 * **JSON を割らない**（K5-1・D-95）。型の列は生 JSON のまま返して `KonpeJson` での decode はアプリ側
 * （`editor/Clipboard.kt`）に置く。このモジュールが `shared` と `kotlinx-serialization` を知らないまま
 * 単体で立つようにするため（`doc/plan-k5-1-wasm-dom-module.md`）
 */

/** `navigator` を、クリップボードが**有るか無いかを見る**口。 */
external interface MaybeClipboard : JsAny {
    /** `navigator.clipboard`。非安全文脈では `undefined` → `null`。 */
    val clipboard: JsAny?
}

/** `Clipboard` を、**読み取りが有るかを見る**口。 */
external interface ClipboardReadCapability : JsAny {
    /** `clipboard.read`。無いブラウザ（`write` だけ等）は `undefined` → `null`。 */
    val read: JsAny?
}

/** `Clipboard` を、**読み取りを呼ぶ**口。 */
external interface ClipboardReader : JsAny {
    /**
     * クリップボードの項目を読む（**押すまでは呼ばない** ―― iOS / macOS Safari の
     * アクセス確認を連発する）。
     *
     * @return `Promise<ClipboardItem[]>`。**許可されないと `NotAllowedError`**
     */
    fun read(): JsPromise?
}

/** `ClipboardItem`（`kotlinx-browser` は未宣言）。 */
external interface ClipboardItemLike : JsAny {
    /** この項目が持てる MIME 型の列（JS の配列。[clipboardTypesJson] で生 JSON にまとめて割る）。 */
    val types: JsArray<JsAny?>?

    /**
     * 1 型ぶんの実体を引く。
     *
     * @param type 引く MIME 型（`"image/png"` 等）
     * @return `Promise<Blob>`
     */
    fun getType(type: String): JsPromise?
}

/** 読み取り使える `Clipboard`。無ければ `null`。 */
private fun clipboardReader(): ClipboardReader? {
    val clipboard = runCatching { window.navigator.unsafeCast<MaybeClipboard>() }.getOrNull()?.clipboard
        ?: return null
    val hasRead = runCatching { clipboard.unsafeCast<ClipboardReadCapability>().read }.getOrNull() != null
    if (!hasRead) return null
    return runCatching { clipboard.unsafeCast<ClipboardReader>() }.getOrNull()
}

/**
 * この環境でクリップボードを読めるか（**押す前に判断しない**が、文言の切り分けには使う）。
 *
 * @return 読み取りが使えるか
 */
fun canReadClipboard(): Boolean =
    clipboardReader() != null

/**
 * クリップボードの項目を読む（**画像を優先**するのは呼び出し側）。
 *
 * @return 項目の列（空なら「何も無い」）。**許可されないと例外**（`JsReject("NotAllowedError")`）
 * @throws JsReject アクセスが許可されなかった・読めない
 */
suspend fun readClipboardItems(): List<ClipboardItemLike> {
    val reader = clipboardReader() ?: error("この環境ではクリップボードを読めません")
    val promise = runCatching { reader.read() }.getOrNull() ?: error("クリップボードを読めません")
    val items = promise.awaitOrThrow()?.let { value -> runCatching { value.unsafeCast<JsArray<JsAny?>>() }.getOrNull() }
        ?: return emptyList()
    return (0 until items.length).mapNotNull { index ->
        runCatching { items[index]?.unsafeCast<ClipboardItemLike>() }.getOrNull()
    }
}

/**
 * 項目が持てる MIME 型の列を**生 JSON のまま**返す（`'["image/png","text/plain"]'` の形）。
 *
 * wasm 側は JS 配列の要素を 1 個ずつ読めない（`js/IdbGlue.kt` の実測）ので `JSON.stringify` して
 * 1 本の文字列で受ける。**ここで割らない**のが要点で、`KonpeJson` を引くとこのモジュールが
 * `shared` を知るになってしまう（K5-1・D-95）。decode は `editor/Clipboard.kt`
 *
 * @param item 引く項目
 * @return 型の JSON 文字列。取れなければ `null`（呼び出し側は空の列として扱う）
 */
fun clipboardTypesJson(item: ClipboardItemLike): String? =
    runCatching { jsJsonStringify(item.types) }.getOrNull()

/**
 * 項目から 1 型の**実体**を引く。
 *
 * @param item 引く項目
 * @param type 引く MIME 型（[clipboardTypesJson] をアプリ側で割って `pickImageType` で選んだもの）
 * @return 実体。引けなければ `null`（その型を持たない・読み取り失敗）
 */
suspend fun clipboardBlob(
    item: ClipboardItemLike,
    type: String,
): Blob? {
    val promise = runCatching { item.getType(type) }.getOrNull() ?: return null
    return runCatching { promise.awaitOrThrow() }.getOrNull()
        ?.let { value -> runCatching { value.unsafeCast<Blob>() }.getOrNull() }
}

/** `Blob` を、**文字列として読める**口（kotlinx-browser は `org.w3c.files.Blob` に `text()` を無い）。 */
external interface BlobTextReader : JsAny {
    /**
     * 実体を文字列で読む。
     *
     * @return `Promise<USVString>`（解決値が**文字列**の Promise）
     */
    fun text(): BlobTextPromise?
}

/**
 * 解決値が**文字列**の Promise。
 *
 * `js/JsValueGlue.kt` の `JsPromise` は成功値が `JsAny` なので、そこから `String` に言い直せない
 * （`unsafeCast` の型引数は `JsAny` のサブタイプでなければならない・**`String` はそうではない**）。
 * なので**受け取る側を `String` で宣言する**（kotlinx-browser が external 宣言に `String` を使えるのと同じ）
 */
external interface BlobTextPromise : JsAny {
    /**
     * 成功・失敗を受け取る。
     *
     * @param onOk 解決した文字列
     * @param onErr 失敗した理由
     * @return 連結する新しい Promise（**使わない**）
     */
    fun then(
        onOk: (String) -> JsAny?,
        onErr: (JsAny) -> JsAny?,
    ): JsAny?
}

/**
 * 項目から 1 型の**文字列**を引く（`text/plain` 用）。
 *
 * @param item 引く項目
 * @param type 引く MIME 型（`text/plain`）
 * @return 文字列。引けなければ `null`
 */
suspend fun clipboardText(
    item: ClipboardItemLike,
    type: String,
): String? {
    val blob = clipboardBlob(item, type) ?: return null
    val promise = runCatching { blob.unsafeCast<BlobTextReader>().text() }.getOrNull() ?: return null
    return suspendCoroutine { continuation ->
        var done = false
        val onOk: (String) -> JsAny? = { text ->
            if (!done) {
                done = true
                continuation.resume(text)
            }
            null
        }
        val onErr: (JsAny) -> JsAny? = {
            if (!done) {
                done = true
                continuation.resume(null)
            }
            null
        }
        // 読めないときは `null`（**ペーストを失敗で止めない** ―― 項目が空のときと同じ見え方にする）
        runCatching { promise.then(onOk, onErr) }.onFailure {
            if (!done) {
                done = true
                continuation.resume(null)
            }
        }
    }
}
