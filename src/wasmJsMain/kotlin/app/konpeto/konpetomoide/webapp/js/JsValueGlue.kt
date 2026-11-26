package app.konpeto.konpetomoide.webapp.js

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.JsAny
import kotlin.js.JsArray

/*
 * JS の**値そのもの**を扱う口（K2-7）。
 *
 * `js/JsGlue.kt` がジェスチャ・待ち時間、`js/DomGlue.kt` が canvas・画像、`js/IdbGlue.kt` が
 * 保存層の言い直しを持っている。ここはそれらに共通して要る 3 本:
 *
 * 1. **JS 配列を作る**（`@JsFun` が無いのでリテラルは書けないが、`JSON.parse("[]")` は通る・
 *    [jsJsonParse] の実測）。共有に出す `File` の並びと `new File([blob], …)` の `fileBits` がこれ
 * 2. **失敗の中身を見る待ち**（[awaitJs] は失敗を `null` に丸めるので、**キャンセルと失敗を分ける**
 *    場所では使えない。仕様 §8.3）
 * 3. **`DOMException.name` を引く**（wasm 側は `DOMException` を型として受けられない）
 */

/** `Error` / `DOMException` を**名前だけ**で引く口（型として受けられないので言い直す）。 */
external interface JsErrorName : JsAny {
    /** `DOMException.name`（`"AbortError"` 等）。`Error` なら `"Error"`。取れなければ `null`。 */
    val name: String?
}

/**
 * 失敗した理由から `DOMException` 名を引く。
 *
 * @param reason `then` の失敗段で受けた値
 * @return 名前。取れなければ `null`（＝その他の失敗に倒す）
 */
fun jsErrorName(reason: JsAny?): String? =
    runCatching { reason?.unsafeCast<JsErrorName>()?.name }.getOrNull()

/**
 * Promise が失敗したことを表す（**失敗の名前を落とさない**ための薄い箱）。
 *
 * @param errorName `DOMException.name`（`"AbortError"` 等）。取れなければ `null`
 */
class JsReject(
    val errorName: String?,
) : Throwable("Promise が失敗しました（${errorName ?: "名前が取れない"}）")

/**
 * Promise を待って、**失敗は例外として上げる**。
 *
 * [awaitJs] は失敗を `null` に丸めるので、**共有のキャンセル（`AbortError`）・クリップボードの
 * `NotAllowedError`・画像の `error`** のように「失敗の中身で挙動が変わる」場所ではこちらを使う
 * （仕様 §8.3）。`then` の 2 引数形だけ使う（`catch` は Kotlin の予約語）
 *
 * @receiver 待つ Promise
 * @return 成功した値
 * @throws JsReject 失敗した（[JsReject.errorName] に `DOMException.name`）
 * @throws IllegalStateException `then` を呼べない（Promise でない・型が壊れている）
 */
suspend fun JsPromise.awaitOrThrow(): JsAny? =
    suspendCoroutine { continuation ->
        var done = false
        // 受け取りは `JsAny?`（`Promise<void` の `undefined` 解決で止まらないため・`JsPromise.then` 参照）
        val onOk: (JsAny?) -> Unit = { value ->
            if (!done) {
                done = true
                continuation.resume(value)
            }
        }
        val onErr: (JsAny?) -> Unit = { reason ->
            if (!done) {
                done = true
                continuation.resumeWithException(JsReject(jsErrorName(reason)))
            }
        }
        runCatching { then(onOk, onErr) }.onFailure { reason ->
            if (!done) {
                done = true
                continuation.resumeWithException(reason)
            }
        }
    }

/** JS の配列を「何かを push できるもの」として言い直した宣言。 */
external interface JsPushableArray : JsAny {
    /**
     * 1 個足す。
     *
     * @param item 足す値（`undefined` 以外なら何でも）
     */
    fun push(item: JsAny?)
}

/**
 * 空の JS 配列を作る（**リテラルが書けないので `JSON.parse("[]")` が唯一の口**）。
 *
 * @return 空の配列。`JSON.parse` が使えない環境は `null`
 */
fun newJsArray(): JsArray<JsAny?>? =
    jsJsonParse("[]")?.let { value -> runCatching { value.unsafeCast<JsArray<JsAny?>>() }.getOrNull() }

/**
 * JS 配列に 1 個足す。
 *
 * wasm 側は `JsArray` の**要素を読む**ほうはできない（`js/IdbGlue.kt` の実測）が、
 * `push` は呼べる（同じ呼び出しになるだけ）
 *
 * @receiver 足す配列（[newJsArray] で作ったもの）
 * @param item 足す値
 */
fun JsArray<JsAny?>.pushJs(item: JsAny?) {
    runCatching { unsafeCast<JsPushableArray>().push(item) }
}
