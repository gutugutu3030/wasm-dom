package app.konpeto.konpetomoide.webapp.js

import kotlinx.browser.window
import org.w3c.files.Blob
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.js.JsAny

/*
 * IndexedDB を wasm から叩く宣言（K1-3・`JsGlue.kt` の wasm↔JS 宣言の続き）。
 *
 * **`kotlinx-browser` 0.5.0 は IndexedDB を 1 個も宣言していない**（klib を確認: `package_org.w3c.dom.idb`
 * が無い）。なので必要な形だけをここに書く。置き場所は `js` にまとめる（散らすと W3 と同じ轍）。
 *
 * ## Kotlin 2.4.21 / wasmJs で測った制約（2026-11-21）
 *
 * - **`String` は `JsAny` のサブタイプではない**（`JsArray<String>` は型引数違反、
 *   `jsAny.unsafeCast<String>()` は receiver 不一致で通らない）。**JS の文字列は external 宣言の
 *   型として `String` と書いて境界で変換させる**のが唯一の通り道（`kotlinx-browser` の
 *   `createElement(tagName: String)` と同じ）
 * - 同じ理由で **`JsArray` の要素を 1 個ずつ `String` で読む方法が無い**。なので**一覧の受け取りは
 *   `JSON.stringify` して 1 本の文字列で受ける**（`jsJsonStringify`。`KonpeJson` で `List<String>` に割る）
 * - **Kotlin 側から JS のオブジェクトリテラルを書けない**（`@JsFun` はこの版本に無い）。
 *   ただし **`JSON.parse("{}")` で空オブジェクトを作ってプロパティを代入すれば作れる**（[jsJsonParse]。
 *   K1-5 で実測）。これで `{key, cachedAt, blob}` の行（TS 側と同じ形）が載せられるようになった。
 *   **数値は `Double` で読む**こと（wasm の `Long` は JS の number とinteropしない。epoch ms は
 *   i32 に収まらないので [PhotoRow.cachedAt] も `Double`）
 * - コールバックは `var onsuccess: ((JsAny) -> Unit)?` の形で宣言でき、**Kotlin の lambda がそのまま
 *   JS 側の関数として呼ばれる**（**2026-11-21 の実測で発火を確認**。K0-2 では「コンパイルは通るが
 *   実行時は未実測」だった所の答え合わせ）
 * - **`result` は `onsuccess` を待ってから読む**（待たずに読むと `undefined` で 0 行・実測で出た NG）。
 *   待つ口が [awaitResult] / [awaitDone] / [awaitRow] / [awaitDatabase]
 * - **同じ JS オブジェクトを別の型で読みたい**（`IDBRequest` → 行を読む口）ときは
 *   [IdbRowResult] のように**もう 1 個宣言して `unsafeCast` する**。`kotlinx-browser` 側の型
 *   （`RenderingContext` など）は `JsAny` を継いでいないので**そこから言い直すことはできない**
 *   （呼び出し元を宣言し直す手は `DomGlue.kt`）
 */

/** `window.indexedDB`（`kotlinx-browser` は未宣言）。 */
external interface IdbGlobal : JsAny {
    /** IDB が無いブラウザ・非 secure context では `undefined` → `null`。 */
    val indexedDB: IdbFactory?
}

/** `IDBFactory`。 */
external interface IdbFactory : JsAny {
    /**
     * DB を開く（`version` を上げると `onupgradeneeded` が走る）。
     *
     * @param name DB 名
     * @param version version（JS 側は `Double`）
     * @return 開くリクエスト
     */
    fun open(
        name: String,
        version: Double,
    ): IdbOpenRequest
}

/** `IDBOpenDBRequest`。 */
external interface IdbOpenRequest : JsAny {
    /** 開けたら呼ばれる。*/
    var onsuccess: ((JsAny) -> Unit)?

    /** 失敗（`VersionError` など）で呼ばれる。*/
    var onerror: ((JsAny) -> Unit)?

    /** 開けなかったタブがいて待たされているとき呼ばれる。*/
    var onblocked: ((JsAny) -> Unit)?

    /** 作成・更新の必要なときに呼ばれる。*/
    var onupgradeneeded: ((JsAny) -> Unit)?

    /** 開けた DB。 */
    val result: JsAny?

    /** 失敗理由。 */
    val error: JsAny?
}

/** `IDBVersionChangeEvent`（`onupgradeneeded` に載る）。 */
external interface IdbVersionEvent : JsAny {
    /** リクエスト（`result` に開いている DB が載る）。 */
    val target: JsAny?

    /** 入っていた version（新規は 0）。 */
    val oldVersion: Double?
}

/** `IDBObjectStoreNames`（`DOMStringList` 相当）。 */
external interface IdbStringList : JsAny {
    /**
     * その名前のストアが在るか。
     *
     * @param name ストア名
     * @return 在れば true
     */
    fun contains(name: String): Boolean
}

/** `IDBDatabase`。 */
external interface IdbDatabase : JsAny {
    /** 今この DB に在るストア名。 */
    val objectStoreNames: IdbStringList?

    /**
     * ストアを作る（`onupgradeneeded` の中でだけ呼べる）。
     *
     * @param name ストア名
     * @return 作ったストア（**使わずに捨てる**。後から取る必要が無い）
     */
    fun createObjectStore(name: String): JsAny?

    /**
     * トランザクションを開く。**ストア名は文字列 1 個で渡す**（JS は配列も取るが、
     * wasm 側から JS の配列を作らないので 1 個ずつ開く）。
     *
     * @param storeName ストア名
     * @param mode `readonly` / `readwrite`
     * @return トランザクション
     */
    fun transaction(
        storeName: String,
        mode: String,
    ): IdbTransaction?
}

/** `IDBTransaction`。 */
external interface IdbTransaction : JsAny {
    /**
     * ストアを引く。
     *
     * @param name ストア名
     * @return ストア（**リクエストを投げるだけ投げる**ので、ここで得たものは使い捨て）
     */
    fun objectStore(name: String): IdbObjectStore?
}

/** `IDBObjectStore`（**文字列を読む口**）。 */
external interface IdbObjectStore : JsAny {
    /**
     * 文字列を書く（同じ鍵なら上書き）。
     *
     * @param value 書く文字列（アルバム行は `AlbumCodec` の JSON）
     * @param key 鍵
     * @return 完了リクエスト
     */
    fun put(
        value: String,
        key: String,
    ): IdbRequest

    /**
     * 全行を読む。
     *
     * **要素を 1 個ずつ Kotlin の `String` で読む方法が wasm に無い**ので、まとめて
     * [jsJsonStringify] に通す（上の注）
     *
     * @return 完了リクエスト（`result` は JS の配列）
     */
    fun getAll(): IdbRequest

    /**
     * 1 行消す。
     *
     * @param key 鍵
     * @return 完了リクエスト
     */
    fun delete(key: String): IdbRequest
}

/** `IDBObjectStore`（**Blob を読む口**。同じ JS オブジェクトの別の読み直し）。 */
external interface IdbBlobStore : JsAny {
    /**
     * 値を書く（同じ鍵なら上書き）。
     *
     * **`JsAny` で受けている**のは、`photos` ストアに載せるのが **`Blob` 単体ではなく
     * [PhotoRow]（`Blob` を積んだ行）**だから（K1-5）。TS 側と同じ形にすると LRU の
     * 「いつ入れたか」が行と一緒に残る（`data/IdbPhotoCache.kt`）
     *
     * @param value 書く値（[PhotoRow] など。structured clone できるもの）
     * @param key 鍵
     * @return 完了リクエスト
     */
    fun put(
        value: JsAny,
        key: String,
    ): IdbRequest

    /**
     * Blob 1 行を読む。
     *
     * @param key 鍵
     * @return 完了リクエスト
     */
    fun get(key: String): IdbRequest

    /**
     * 全行を読む（**実体をデシリアライズする**ので掃除のときだけ）。
     *
     * @return 完了リクエスト（`result` は JS の配列 → [jsJsonStringify] で 1 本の文字列に受ける）
     */
    fun getAll(): IdbRequest

    /**
     * 全行の**鍵だけ**読む（実体はデシリアライズしない）。
     *
     * @return 完了リクエスト（`result` は JS の配列）
     */
    fun getAllKeys(): IdbRequest

    /**
     * 1 行消す。
     *
     * @param key 鍵
     * @return 完了リクエスト
     */
    fun delete(key: String): IdbRequest
}

/**
 * `photos` ストア 1 行の形（`{key, cachedAt, blob}`）。
 *
 * **作り方は [newPhotoRow]**（リテラルを書けないので `JSON.parse` を使う）。
 * [key] を行にも持たせるのは、掃除で `getAll()` したとき**鍵を知らない**と削除の指示を出せないから
 * （`getAllKeys()` だと `cachedAt` が読めない）
 *
 * 行に `Blob` を入れると `JSON.stringify` では `{}` になるが、**掃除は鍵と時刻しか見ないので**
 * 実体は写らない（`data/IdbPhotoCache.kt` の掃除）。TS 側は `photoId` / `variant` も行に載せていたが、
 * ここは鍵 `photoId:variant` から読む（余計な 2 個を持つと鍵と食い違う）
 */
external interface PhotoRow : JsAny {
    /** 鍵（[app.konpeto.konpetomoide.shared.storage.StorageSchema.photoCacheKey] と同じ `photoId:variant`）。 */
    var key: String

    /** 手元に入れた時刻（epoch ms）。**JS の number なので `Double`**（`Long` は境界を越えない）。LRU 順に使う */
    var cachedAt: Double

    /** 実体。 */
    var blob: Blob?
}

/** `IDBRequest`（完了・結果を待つだけ）。 */
external interface IdbRequest : JsAny {
    /** 完了したら呼ばれる。*/
    var onsuccess: ((JsAny) -> Unit)?

    /** 失敗したら呼ばれる。*/
    var onerror: ((JsAny) -> Unit)?

    /** 結果。 */
    val result: JsAny?

    /** 失敗理由。 */
    val error: JsAny?
}

/** `IDBRequest` の**保存行（[PhotoRow]）を読む読み直し**。 */
external interface IdbRowResult : JsAny {
    /** 完了したら呼ばれる。*/
    var onsuccess: ((JsAny) -> Unit)?

    /** 失敗したら呼ばれる。*/
    var onerror: ((JsAny) -> Unit)?

    /** 読めた行。無い・`Blob` 単体の旧行なら `null`。 */
    val result: PhotoRow?

    /** 失敗理由。 */
    val error: JsAny?
}

/** `window.JSON`（一覧を受け取る唯一の口。上の注）。 */
external interface JsonGlobal : JsAny {
    /**
     * `JSON` オブジェクト。
     *
     * **JS 側のグローバル名そのまま**なので小文字化できない（小文字にすると別物を見て `null` になる）
     */
    @Suppress("ktlint:standard:property-naming")
    val JSON: JsonBridge?
}

/** `JSON` オブジェクト。 */
external interface JsonBridge : JsAny {
    /**
     * JS の値を JSON 文字列に落とす。
     *
     * @param value 落とす値（配列でも何でも）
     * @return JSON 文字列。**循環参照などでは `undefined` → `null`**
     */
    fun stringify(value: JsAny?): String?

    /**
     * JSON 文字列を JS の値に直す（**オブジェクトを作る唯一の口**）。
     *
     * @param text JSON 文字列
     * @return 直した値。壊れた文字列は例外になるので呼び出し元で `runCatching`
     */
    fun parse(text: String): JsAny?
}

/** `window.Date`（**JS のグローバルは `window` 経由でしか引けない**。`indexedDB` と同じ手）。 */
external interface DateGlobal : JsAny {
    /**
     * `Date` コンストラクタ。
     *
     * **JS 側の名前そのまま**なので小文字化できない
     */
    @Suppress("ktlint:standard:property-naming")
    val Date: DateBridge?
}

/** `Date` コンストラクタ（`now()` だけ）。 */
external interface DateBridge : JsAny {
    /**
     * 今の時刻を epoch ms で返す。
     *
     * @return epoch ms。**`Double`**（wasm の `Long` は JS の number とinteropしない・実測で落ちる）
     */
    fun now(): Double
}

/**
 * このブラウザの `IDBFactory`。
 *
 * @return 無いブラウザ（と非 secure context）は `null`
 */
fun idbFactory(): IdbFactory? =
    runCatching { window.unsafeCast<IdbGlobal>().indexedDB }.getOrNull()

/**
 * リクエストの完了を待って結果を返す。
 *
 * **失敗は `null` に丸める**（`doc/kb-local-first.md` §S2-5）。IDB が開けない・書けない環境で
 * 画面に通信エラーを出さないための約束で、ここが例外を投げると「保存が失敗したアプリ」になる
 *
 * @receiver 待つリクエスト
 * @return 結果。失敗・結果無しは `null`
 */
suspend fun IdbRequest.awaitResult(): JsAny? =
    suspendCoroutine { continuation ->
        onsuccess = { continuation.resume(runCatching { result }.getOrNull()) }
        onerror = { continuation.resume(null) }
    }

/**
 * リクエストの完了を待つ（結果を使わない書き込み・削除）。
 *
 * @receiver 待つリクエスト
 * @return 成功なら true
 */
suspend fun IdbRequest.awaitDone(): Boolean =
    suspendCoroutine { continuation ->
        onsuccess = { continuation.resume(true) }
        onerror = { continuation.resume(false) }
    }

/**
 * リクエストの完了を待って保存行（[PhotoRow]）を読む。
 *
 * @receiver 行を読む読み直しにしたリクエスト
 * @return 読めた行。行が無い・失敗は `null`
 */
suspend fun IdbRowResult.awaitRow(): PhotoRow? =
    suspendCoroutine { continuation ->
        onsuccess = { continuation.resume(runCatching { result }.getOrNull()) }
        onerror = { continuation.resume(null) }
    }

/**
 * 開くリクエストを待って DB を返す。
 *
 * @receiver 開くリクエスト
 * @return 開けた DB。失敗・他タブにブロックされた場合は `null`
 */
suspend fun IdbOpenRequest.awaitDatabase(): JsAny? =
    suspendCoroutine { continuation ->
        onsuccess = { continuation.resume(runCatching { result }.getOrNull()) }
        onerror = { continuation.resume(null) }
        // 古い version を開いたままのタブがあるとここで止まる（§S2-9）。「キャッシュが無い」で進める
        onblocked = { continuation.resume(null) }
    }

/**
 * `onupgradeneeded` のイベントから開いている DB を引く。
 *
 * @param event `onupgradeneeded` に載ったイベント
 * @return DB。取れなければ `null`
 */
fun IdbVersionEvent.database(): JsAny? =
    runCatching {
        target?.unsafeCast<IdbOpenRequest>()?.result
    }.getOrNull()

/**
 * `IDBDatabase` として言い直す（`JsAny` で受け取った DB を使う唯一の口）。
 *
 * @receiver `IdbOpenRequest.result`
 * @return 言い直した DB
 */
fun JsAny.asIdbDatabase(): IdbDatabase =
    unsafeCast<IdbDatabase>()

/**
 * リクエストを**保存行を読む読み直し**に言い直す。
 *
 * @receiver 読むリクエスト
 * @return 行を見るリクエスト
 */
fun IdbRequest.asRowResult(): IdbRowResult =
    unsafeCast<IdbRowResult>()

/**
 * JS の値（たいてい配列）を JSON 文字列 1 本で受け取る。
 *
 * wasm 側は JS 配列の要素を Kotlin の型で 1 個ずつ読めない（上の注）。`KonpeJson` の
 * `List<String>` で割って使う
 *
 * @param value 渡す JS の値
 * @return JSON 文字列。取れなければ `null`
 */
fun jsJsonStringify(value: JsAny?): String? =
    runCatching { window.unsafeCast<JsonGlobal>().JSON?.stringify(value) }.getOrNull()

/**
 * JSON 文字列を JS の値に直す（**Kotlin 側から JS オブジェクトを作る唯一の口**）。
 *
 * `@JsFun` が無いのでリテラルは書けないが、`JSON.parse("{}")` は通る（K1-5 実測）。
 * 作ったオブジェクトには external 宣言の `var` でプロパティを足せる → [newPhotoRow]
 *
 * @param text JSON 文字列
 * @return 直した値。壊れていれば `null`
 */
fun jsJsonParse(text: String): JsAny? =
    runCatching { window.unsafeCast<JsonGlobal>().JSON?.parse(text) }.getOrNull()

/**
 * 今の時刻（epoch ms）。
 *
 * **LRU の順はリロードを跨がないと意味が無い**ので、`TimeSource.Monotonic` ではダメで
 * JS の壁時計を見る（`kotlinx-datetime` は入れていない・依存を増やさない方針 D-8）
 *
 * @return epoch ms。`Date` が取れない環境は 0（＝一番古い扱い。掃除は「捨てる側」に倒れる）
 */
fun epochMillis(): Double =
    runCatching { window.unsafeCast<DateGlobal>().Date?.now() }.getOrNull() ?: 0.0

/** 空オブジェクトの JSON（[newPhotoRow] の作り皿）。 */
private const val EMPTY_OBJECT_JSON = "{}"

/**
 * 写真キャッシュの 1 行（`{key, cachedAt, blob}`）を作る。
 *
 * **作り置きした JSON を `JSON.parse` して、プロパティを 3 個代入する**（リテラルが書けないので）。
 * 鍵を JSON に埋めないのは、写真 id に `"` が混入したときに JSON を壊すから（代入なら安全）
 *
 * @param key 鍵（`photoId:variant`）
 * @param cachedAtMillis 手元に入れた時刻（epoch ms・[epochMillis]）
 * @param blob 実体
 * @return 作った行。`JSON` が使えない環境は `null`
 */
fun newPhotoRow(
    key: String,
    cachedAtMillis: Double,
    blob: Blob,
): PhotoRow? =
    runCatching {
        jsJsonParse(EMPTY_OBJECT_JSON)?.unsafeCast<PhotoRow>()?.apply {
            this.key = key
            this.cachedAt = cachedAtMillis
            this.blob = blob
        }
    }.getOrNull()
