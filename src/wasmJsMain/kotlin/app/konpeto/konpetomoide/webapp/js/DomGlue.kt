package app.konpeto.konpetomoide.webapp.js

import kotlinx.browser.window
import org.w3c.dom.HTMLCanvasElement
import org.w3c.files.Blob
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.js.JsAny

/*
 * `kotlinx-browser` の宣言が**無さ過ぎて読めない**ものを、必要な形だけ言い直す宣言（K1-3）。
 *
 * `unsafeCast` は `JsAny` 同士でしかできない（`js/IdbGlue.kt` の実測）ので、
 * `RenderingContext` のように `JsAny` を継いでいない型は**そこから言い直せない**。
 * そんなときは**呼び出し元側のメソッドを自分の見方で宣言し直す**（JS 側は同じ呼び出しになる）。
 * `HTMLCanvasElement.getContext("2d")` を `Canvas2d` を返す口として宣言するのがこれで、
 * `IdbGlue` の `IdbRowResult`（同じリクエストの別読み直し）と同じ手
 *
 * 写真の縮小版・縮略を作る口（`createImageBitmap` / `drawImage` / `getImageData`）もここに置く
 * （K1-5。`kotlinx-browser` は `createImageBitmap` を宣言していない）
 */

/** `HTMLCanvasElement.getContext` を、2d 文脈を返す口として言い直した宣言。 */
external interface Canvas2dProvider : JsAny {
    /**
     * 描画文脈を引く。
     *
     * @param contextId `"2d"`
     * @return 2d 文脈。使えないブラウザは `null`
     */
    fun getContext(contextId: String): Canvas2d?
}

/** `CanvasRenderingContext2D`（縮小版生成・PNG 書き出しで使う分だけ）。 */
external interface Canvas2d : JsAny {
    /** 塗り色（`#rrggbb` / `rgba(...)`）。 */
    var fillStyle: String

    /**
     * 長方形を塗る。
     *
     * @param x 左上 x（px）
     * @param y 左上 y（px）
     * @param width 幅（px）
     * @param height 高さ（px）
     */
    fun fillRect(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
    )

    /**
     * 画像を縮んで描く（**縮小版・縮略はここで生まれる**）。
     *
     * @param source 描く元（`ImageBitmap`）
     * @param dx 描き先の左上 x（px）
     * @param dy 描き先の左上 y（px）
     * @param dw 描き先の幅（px）
     * @param dh 描き先の高さ（px）
     */
    fun drawImage(
        source: JsAny,
        dx: Double,
        dy: Double,
        dw: Double,
        dh: Double,
    )

    /**
     * ピクセルを読む（**透過があるかを見るためだけ**）。
     *
     * @param sx 読み出しの左上 x
     * @param sy 読み出しの左上 y
     * @param sw 読み出しの幅
     * @param sh 読み出しの高さ
     * @return RGBA のバイト列。使えない環境は `null`
     */
    fun getImageData(
        sx: Int,
        sy: Int,
        sw: Int,
        sh: Int,
    ): JsImageData?
}

/** `ImageData`（[Canvas2d.getImageData] の返り値）。 */
external interface JsImageData : JsAny {
    /** RGBA 4 バイト × 画素数。 */
    val data: JsPixelView?
}

/**
 * `Uint8ClampedArray`。
 *
 * **添字で 1 バイトずつ読める**（`kotlin.js.JsArray` が同じ形で宣言できているので同じ手）。
 * 実寸で読むと 2048px で 16 MB 級の読み出しになるので、**必ず縮めた受け皿に対して**呼ぶ
 * （`image/CanvasPhotoVariants.kt`）
 */
external interface JsPixelView : JsAny {
    /** バイト数。 */
    val length: Int

    /**
     * 1 バイト読む。
     *
     * @param index 添字
     * @return 0〜255
     */
    operator fun get(index: Int): Int
}

/** `Promise`。**`then` の 2 引数形だけ**使う（`catch` は Kotlin の予約語で宣言できない）。 */
external interface JsPromise : JsAny {
    /**
     * 成功・失敗をまとめて受け取る。
     *
     * **受け取りは `JsAny?`（nullable）**にする ―― `navigator.share()` のような `Promise<void>` は
     * 解決値が `undefined` で、Kotlin/Wasm は `undefined` を非 null `JsAny` の引数に渡すと内部で
     * 例外にしてコールバックを止める（＝待っている coroutine が永遠に復帰しない）。実測（dev・2026-11-25）
     *
     * @param onFulfilled 成功したときの値（`void` 解決は `null`）
     * @param onRejected 失敗したときの理由
     * @return 連結する新しい Promise（**使わない**）
     */
    fun then(
        onFulfilled: ((JsAny?) -> Unit)?,
        onRejected: ((JsAny?) -> Unit)?,
    ): JsAny?
}

/** `ImageBitmap`（`createImageBitmap` の結果）。 */
external interface ImageBitmap : JsAny {
    /** **EXIF を適用したあと**の幅（px）。 */
    val width: Int

    /** **EXIF を適用したあと**の高さ（px）。 */
    val height: Int

    /** 適用された回転（度）。非対応ブラウザは `null`。 */
    val rotation: Int?

    /** 裏のメモリを解放する（**描き終わったら必ず呼ぶ**。iOS の canvas メモリは厳しい）。 */
    fun close()
}

/** `window.createImageBitmap`（`kotlinx-browser` は未宣言）。 */
external interface ImageBitmapGlobal : JsAny {
    /**
     * 画像をデコードする。
     *
     * **`imageOrientation` の既定は `from-image`**（EXIF の向きを適用した向きで出てくる）なので、
     * オプションを渡さなくてよい。JS のオプションオブジェクトは wasm 側から作れないが、
     * 必要なら [jsJsonParse] で作れる（`IdbGlue`）
     *
     * @param source デコードする画像
     * @return `Promise<ImageBitmap>`。非対応ブラウザは `null`
     */
    fun createImageBitmap(source: Blob): JsPromise?
}

/**
 * canvas の 2d 文脈を引く。
 *
 * @receiver 寸法を決めた canvas
 * @return 2d 文脈。使えなければ `null`
 */
fun HTMLCanvasElement.context2d(): Canvas2d? =
    runCatching { unsafeCast<Canvas2dProvider>().getContext("2d") }.getOrNull()

/**
 * `HTMLCanvasElement.toBlob` を、**形式と品質を渡す口**として言い直した宣言。
 *
 * `kotlinx-browser` の宣言は wasm 側から渡しにくい型（コールバックの typealias と品質が `Number`）なので、
 * 必要な形だけ言い直す（上の注と同じ手）。JS 側は同じ呼び出しになる
 */
external interface CanvasBlobEncoder : JsAny {
    /**
     * 画像 Blob を非同期に作る。
     *
     * @param onComplete 出来た Blob（作れなければ `undefined`）を受ける口
     * @param type `"image/jpeg"` / `"image/png"` / `"image/webp"`
     * @param quality JPEG の品質（0..1）。PNG は無視される
     */
    fun toBlob(
        onComplete: ((JsAny?) -> Unit)?,
        type: String,
        quality: Double,
    )
}

/**
 * canvas を画像 Blob に書き出す。
 *
 * @receiver 描き終えた canvas
 * @param type 書き出す形式
 * @param quality JPEG の品質（0..1）
 * @return 出来た Blob。ブラウザが作れなければ `null`
 */
suspend fun HTMLCanvasElement.encodeBlob(
    type: String,
    quality: Double,
): Blob? =
    suspendCoroutine { continuation ->
        runCatching {
            unsafeCast<CanvasBlobEncoder>().toBlob({ value ->
                continuation.resume(value?.unsafeCast<Blob>())
            }, type, quality)
        }.onFailure { continuation.resume(null) }
    }

/**
 * Promise を待って結果を 1 本で受ける（**`then` の 2 引数形を使う共通の待ち口**）。
 *
 * `catch` は Kotlin の予約語なので宣言できない（`JsPromise` の注）
 *
 * @receiver 待つ Promise
 * @return 成功した値。失敗・例外は `null`
 */
suspend fun JsPromise.awaitJs(): JsAny? =
    suspendCoroutine { continuation ->
        var done = false
        val onOk: (JsAny?) -> Unit = { value ->
            if (!done) {
                done = true
                continuation.resume(value)
            }
        }
        val onErr: (JsAny?) -> Unit = {
            if (!done) {
                done = true
                continuation.resume(null)
            }
        }
        // 失敗しても `then` 自体が throw することがあるので握り潰す（`onErr` が来ないまま止まるよりマシ）
        runCatching { then(onOk, onErr) }.onFailure {
            if (!done) {
                done = true
                continuation.resume(null)
            }
        }
    }

/**
 * 画像をデコードする（**EXIF の向きは適用された形で出る**）。
 *
 * @param source デコードする Blob
 * @return bitmap。デコードできない・非対応は `null`
 */
suspend fun decodeImage(source: Blob): ImageBitmap? {
    val promise = runCatching { window.unsafeCast<ImageBitmapGlobal>().createImageBitmap(source) }.getOrNull()
        ?: return null
    return promise.awaitJs()?.let { value -> runCatching { value.unsafeCast<ImageBitmap>() }.getOrNull() }
}

/**
 * `addEventListener` の **options 形**（`kotlinx-browser` は boolean ＝ `useCapture` 形しか持たない）。
 *
 * `wheel` / `touchstart` は `window` / `document` に貼ると**既定が `passive: true`** で、
 * `preventDefault()` が黙られる（Chrome / Safari とも）。ブラウザのピンチを抑止する
 * [app.konpeto.konpetomoide.webapp.dom.blockBrowserPinch] は `{ passive: false }` が無いと効かない
 * （JS 実装はオプションオブジェクトを渡していた）。
 */
external interface EventListenerOptions : JsAny {
    /** `false` にすると `preventDefault()` が効くようになる。 */
    var passive: Boolean
}

/** `window` / `document` に options 付きでリスナを貼る口（[EventListenerOptions] を添えて呼ぶ）。 */
external interface OptionsEventTarget : JsAny {
    /**
     * リスナを貼る。
     *
     * @param type イベント名（`"wheel"` 等）
     * @param callback 受ける口（**Kotlin の lambda が JS の関数として呼ばれる**のは K1-3 で実測済）
     * @param options `passive` 等の入った物件（[passiveFalseOptions] で作る）
     */
    fun addEventListener(
        type: String,
        callback: (JsAny) -> Unit,
        options: EventListenerOptions,
    )
}

/** `wheel` で必要なのは `ctrlKey`（トラックパッドのピンチ）と `preventDefault` だけ。 */
external interface CtrlKeyWheelEvent : JsAny {
    /** `ctrl` が押されていたか（**トラックパッドのピンチはこれと一緒に届く**）。 */
    val ctrlKey: Boolean

    /** ブラウザ自身の動作（ここではページズーム）を取り消す。 */
    fun preventDefault()
}

/**
 * `{ passive: false }` を作る（**JS のオブジェクトリテラルは書けない**ので `JSON.parse` が唯一の口・
 * [jsJsonParse] の実測）。
 *
 * @return `passive = false` の入った物件
 * @throws IllegalStateException `JSON.parse` が使えない環境（あり得ないが、黙って `passive: true` で
 * 抑止が効かないより、起きた場所が分かる方がマシ）
 */
fun passiveFalseOptions(): EventListenerOptions {
    val options =
        jsJsonParse("{}")
            ?.let { runCatching { it.unsafeCast<EventListenerOptions>() }.getOrNull() }
            ?: error("JSON.parse が使えません（`{ passive: false }` を作れません）")
    options.passive = false
    return options
}
