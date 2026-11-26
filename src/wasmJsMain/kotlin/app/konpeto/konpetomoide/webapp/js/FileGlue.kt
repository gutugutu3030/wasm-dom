package app.konpeto.konpetomoide.webapp.js

import kotlinx.browser.window
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.toUint8Array
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import org.w3c.files.File
import org.w3c.files.FileReader
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.JsAny

/*
 * **`ByteArray ↔ Blob / File` の橋**。
 *
 * 使う側が扱うのは **`ByteArray`**（zip のバイト列）、手元の実体は IndexedDB の **`Blob`**。
 * **この 1 段が wasm に無い** ―― `kotlinx-browser` 0.5.0 は `Blob.arrayBuffer()` / `Blob.text()` /
 * `File.arrayBuffer()` を 1 個も宣言していない（sources jar 実測）。通れるのは `FileReader` と `fetch` と
 * `Base64` と、**外部クラスのコンストラクタが呼べるか**（未実測）だけ。
 *
 * ## 読み（[readBlobBytes]）
 *
 * `FileReader.readAsDataURL` → 先頭の `data:…;base64,` を落とす → `Base64.decode`。
 * `result` は宣言上 `JsAny?` で、**`JsAny` から `String` に `unsafeCast` は通らない**
 * （`docs/kb-wasm-bridge.md` §B-1）ので、
 * 同じ JS オブジェクトを**文字列を読む口** [FileReaderText] として見直す（`IdbGlue` の `IdbRowResult` と同じ手）。
 * `FileReaderSync` も宣言はあるが**メインスレッドでは使えない**（Worker 限定）
 *
 * ## 書き（[blobFromBytes]）
 *
 * **型付き配列で `Blob` を直接作る手を先に試し、落ちたら data URL を `fetch` して取り戻す**。
 * 先に試す理由はメモリ: data URL は元の 5.3 倍の文字列を挟むのに対し、型付き配列は 1 倍で済む（実測）。
 * **効かない手の `external` は増やさない** ―― `Reflect.construct` は宣言も stdlib も無い（実測）
 */

/** data URL の先頭。 */
private const val DATA_URL_PREFIX = "data:"

/** data URL のヘッダと本体の区切り（**本体だけ Base64 で割る**）。 */
private const val BASE64_MARK = ";base64,"

/** 書き出しの mime（`.konpe`）。**mime を載せない**と iOS がその場で開こうとする。 */
const val ZIP_MIME: String = "application/zip"

/**
 * `FileReader` を、**結果を文字列で読む**読み直し。
 *
 * kotlinx-browser の `FileReader.result` は `JsAny?`（`String | ArrayBuffer`）で、`JsAny` からは
 * `String` に言い直せない。なので**同じオブジェクトの別読み直し**を宣言する（`IdbRowResult` と同じ手）
 */
external interface FileReaderText : JsAny {
    /** 読み終わった結果（data URL なら `data:{mime};base64,{…}`）。まだなら `null`。 */
    val result: String?
}

/**
 * `Blob` を、**長さを `Double` で読む**読み直し。
 *
 * kotlinx-browser の `Blob.size` は `JsNumber` 宣言で、`JsNumber` から数値を読めない
 * （`PhotoRow.cachedAt` を `Double` にしたのと同じ言い直し）。**開く前の上限チェック**に要る
 */
external interface BlobSize : JsAny {
    /** 実体の長さ（バイト）。 */
    val size: Double
}

/** `window` を、`fetch` が在るかだけ見る口。 */
private external interface MaybeFetch : JsAny {
    /** `window.fetch`。 */
    val fetch: JsAny?
}

/**
 * `File` / `Blob` を `ByteArray` で読む（**`File` は `Blob` のサブクラス**なので読み方は同じ）。
 *
 * @param blob 読む実体
 * @return 中身。**読めなかった・失敗した実は空列**（呼び出し側が「読めなかった」で断る）
 */
@OptIn(ExperimentalEncodingApi::class)
suspend fun readBlobBytes(blob: Blob): ByteArray {
    val dataUrl = readAsDataUrl(blob) ?: return ByteArray(0)
    // `data:image/jpeg;base64,` までを落とす。**本体のカンマに引っかからないよう最後のカンマ**で割る
    val body = dataUrl.substringAfterLast(BASE64_MARK, "")
    if (body.isEmpty()) return ByteArray(0)
    return runCatching { Base64.decode(body) }.getOrNull() ?: ByteArray(0)
}

/**
 * 選ばれたファイルを読む（画面はこれだけ呼ぶ）。
 *
 * @param file 選んだファイル
 * @return 中身。読めなければ空列
 */
suspend fun readFile(file: File): ByteArray =
    readBlobBytes(file)

/**
 * 実体の長さ（バイト）。
 *
 * @param blob 測る実体
 * @return 長さ。読めない環境は 0
 */
fun blobByteSize(blob: Blob): Long =
    runCatching { blob.unsafeCast<BlobSize>().size }.getOrNull()?.toLong() ?: 0L

/**
 * `ByteArray` を `Blob` にする（**書き出しの最後の 1 段**。`URL.createObjectURL` は `Blob` を要求する）。
 *
 * @param bytes 載せるバイト列
 * @param mimeType 実体の mime（[ZIP_MIME] 等）
 * @return 実体。どちらの手も落ちた実は `null`
 */
suspend fun blobFromBytes(
    bytes: ByteArray,
    mimeType: String = ZIP_MIME,
): Blob? =
    blobFromTypedArray(bytes, mimeType) ?: blobFromDataUrl(bytes, mimeType)

/**
 * 断片の列を `Blob` にする（**1 本に繋がない**）。
 *
 * `Blob` のコンストラクタは part 配列を受け取る（[blobFromTypedArray] が 1 part でやっていること
 * そのもの）。断片で出せる書き出しなら、ここで繋がないと **ピークが出力 1 個ぶん減る**
 * （120 MB 書き出しで 120 MB）
 *
 * **型付き配列の手が落ちたときだけ 1 本に繋いで** [blobFromBytes] に落とす ―― data URL の手は
 * 1 本でしか渡せないのでピークは元に戻るが、**出せないよりマシ**
 *
 * @param parts 先頭から順に並べれば 1 本になる断片
 * @param mimeType 実体の mime
 * @return 実体。どちらの手も落ちた実は `null`
 */
@OptIn(ExperimentalUnsignedTypes::class)
suspend fun blobFromParts(
    parts: List<ByteArray>,
    mimeType: String = ZIP_MIME,
): Blob? {
    val total = parts.sumOf { part -> part.size.toLong() }
    val array = newJsArray() ?: return blobFromBytes(parts.joined(), mimeType)
    parts.forEach { part ->
        // `ByteArray` → `UByteArray` → `Uint8Array`（kotlinx-browser 側の写し）
        val view: Uint8Array = runCatching { part.toUByteArray().toUint8Array() }.getOrNull()
            ?: return blobFromBytes(parts.joined(), mimeType)
        array.pushJs(view)
    }
    val blob = runCatching { Blob(array, BlobPropertyBag(mimeType)) }.getOrNull()
        ?: return blobFromBytes(parts.joined(), mimeType)
    // **長さが食い違ったら壊れた zip を出す**ので止める（[blobFromTypedArray] と同じ約束）
    return blob.takeIf { candidate -> blobByteSize(candidate) == total }
        ?: blobFromBytes(parts.joined(), mimeType)
}

/**
 * 断片を 1 本に繋ぐ（**fallback 専用** ―― ここで繋いだ瞬間にピークが 1 個ぶん増える）。
 *
 * stdlib の `concatenate()` は `Array<T>` 用で **`ByteArray` には無い**（wasm で実測）
 *
 * @param receiver 断片の列
 * @return 繋いだバイト列
 */
private fun List<ByteArray>.joined(): ByteArray {
    val out = ByteArray(sumOf { part -> part.size })
    var at = 0
    forEach { part ->
        part.copyInto(out, at)
        at += part.size
    }
    return out
}

/**
 * 型付き配列で `Blob` を直接作る（**メモリ 1 倍の速い手**）。
 *
 * `Uint8Array` も `Blob` のコンストラクタも kotlinx-browser に宣言があるが、
 * **外部クラスのコンストラクタが Kotlin/Wasm から呼べるかは未実測**（`js()` も `@JsFun` も無く、
 * `new` に相当する口が分からない）。呼べなければ例外、**`size` が食い違っても失敗**として落とす
 * （壊れた zip を出さない）
 *
 * **呼び出し側の実測画面が「どちらの手で通ったか」を出すために明かしている**。使う側は
 * [blobFromBytes] だけを呼べばよい（**`public` にしているのはこのためだけ**）
 *
 * @param bytes 載せるバイト列
 * @param mimeType 実体の mime
 * @return 実体。作れない環境は `null`
 */
@OptIn(ExperimentalUnsignedTypes::class)
fun blobFromTypedArray(
    bytes: ByteArray,
    mimeType: String,
): Blob? {
    val parts = newJsArray() ?: return null
    // `ByteArray` → `UByteArray` → `Uint8Array`（kotlinx-browser 側の写し。1 バイトずつ JS 境界を越えない）
    val view: Uint8Array = runCatching { bytes.toUByteArray().toUint8Array() }.getOrNull() ?: return null
    parts.pushJs(view)
    val blob = runCatching { Blob(parts, BlobPropertyBag(mimeType)) }.getOrNull() ?: return null
    return blob.takeIf { candidate -> blobByteSize(candidate) == bytes.size.toLong() }
}

/**
 * data URL を `fetch` して `Blob` を取り戻す（**`new` が呼べない環境の本命**）。
 *
 * Base64 で 4/3 に膨らんだ文字列をブラウザにデコードさせるので、**ピークは元の約 5.3 倍**。
 * 読み書きの上限（`MAX_KONPE_BYTES` = 120 MB）はこの係数で決めている
 *
 * @param bytes 載せるバイト列
 * @param mimeType 実体の mime
 * @return 実体。`fetch` が落ちた実は `null`
 */
@OptIn(ExperimentalEncodingApi::class)
suspend fun blobFromDataUrl(
    bytes: ByteArray,
    mimeType: String,
): Blob? {
    // **`encodeToString` は wasm の stdlib に無い**（2.4.21 の klib を直接引いて
    // `encodeToByteArray` のみ実測）。Base64 の Alphabet は ASCII なので `ByteArray` → `String` の変換は可逆
    val encoded = runCatching { Base64.encodeToByteArray(bytes).decodeToString() }.getOrNull() ?: return null
    return fetchBlob("$DATA_URL_PREFIX$mimeType$BASE64_MARK$encoded").takeIf { fetched -> fetched.ok }?.blob
}

/**
 * `FileReader.readAsDataURL` の完了を 1 回だけ待つ。
 *
 * [readBlobBytes] の中にしまいこむと **Base64 の係数**（data URL 経路のメモリピークの根拠）が
 * 測れないので、**呼び出し側の実測画面から見える形**にしている
 * （`public` の理由は [blobFromTypedArray] と同じ）
 *
 * @param blob 読む実体
 * @return data URL。失敗・呼べない環境は `null`
 */
suspend fun readAsDataUrl(blob: Blob): String? {
    val reader = runCatching { FileReader() }.getOrNull() ?: return null
    return suspendCoroutine { continuation ->
        // `onload` / `onerror` のどちらが来ても 1 回だけ再開する（`settled` 印・
        // `docs/kb-wasm-bridge.md` §B-4 の同じ手）
        var settled = false
        reader.onload = { _ ->
            if (!settled) {
                settled = true
                continuation.resume(runCatching { reader.unsafeCast<FileReaderText>().result }.getOrNull())
            }
        }
        // `onerror` は失敗。**空の実体として通すと壊れた `.konpe` が読めてしまう**
        reader.onerror = { _ ->
            if (!settled) {
                settled = true
                continuation.resume(null)
            }
        }
        runCatching { reader.readAsDataURL(blob) }.onFailure {
            if (!settled) {
                settled = true
                continuation.resume(null)
            }
        }
    }
}

/**
 * この環境に `fetch` が在るか（**data URL を読む手が使えるか**の事前確認・probe に出す）。
 *
 * @return 関数として在るか
 */
fun hasFetch(): Boolean =
    runCatching { window.unsafeCast<MaybeFetch>().fetch }.getOrNull() != null

/**
 * data URL の文字数から**Base64 の係数**を出す（probe 用・想定 1.33）。
 *
 * @param dataUrl `readAsDataURL` の結果
 * @param byteSize 実体の長さ
 * @return 係数。測れなければ 0
 */
fun base64Ratio(
    dataUrl: String,
    byteSize: Long,
): Double {
    if (byteSize <= 0L) return 0.0
    val body = dataUrl.substringAfterLast(BASE64_MARK, "")
    return body.length.toDouble() / byteSize.toDouble()
}

/**
 * `new File([blob], name)` を作る（**共有シートは `File` でないと名前が乗らない**）。
 *
 * `kotlinx-browser` は `org.w3c.files.File` を構築子付きで宣言しているが、`fileBits` が
 * `JsArray<JsAny?>` なので **リテラルは [newJsArray] でしか作れない**。実物が作れるかはブラウザ実測
 * （作れなければ `null` → 呼び出し側はダウンロードへ倒す）
 *
 * @param blob 実体
 * @param name ファイル名（呼び出し側が決める）
 * @return 作ったファイル。作れない環境は `null`
 */
fun newFile(
    blob: Blob,
    name: String,
): File? {
    val bits = newJsArray() ?: return null
    bits.pushJs(blob.unsafeCast<JsAny>())
    return runCatching { File(bits, name) }.getOrNull()
}
