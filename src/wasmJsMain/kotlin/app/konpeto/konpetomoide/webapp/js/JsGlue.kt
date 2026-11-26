package app.konpeto.konpetomoide.webapp.js

import kotlinx.browser.window
import org.w3c.dom.events.Event
import org.w3c.dom.events.MouseEvent
import kotlin.js.JsAny
import kotlin.js.JsArray

/*
 * wasm から JS を直接叩く**唯一**の場所（K0-2・`doc/plan-kmp-local-rewrite.md`）。
 *
 * 置くものは `kotlinx-browser` が宣言していないものだけ。散らすと W3 と同じ轍を踏む
 * （グルーと成果物の版本ズレ・`doc/kb-wasm-interop.md`）。
 *
 * ## Kotlin 2.4.21 / wasmJs の実測（2026-11-21）
 *
 * - **`kotlin.js.JsFun` は使えない**（`Unresolved reference 'JsFun'`）。`@JsFun("...")` で
 *   JS の関数本体を文字列で渡す手は、この版本では通らない
 * - **通るのは `external interface` + `unsafeCast`**。メソッドはそのまま呼べるし、
 *   **有るか分からないプロパティは `JsAny?` で宣言すれば `undefined` が `null` として落ちる**
 *   ＝「`x in prototype` かどうか」の実在確認がこれで作れる
 * - `kotlin.js.JsArray` は使える。ただし**添字の結果は `JsAny?`**（null 混じりで返る）
 * - `kotlinx-browser` 0.5.0 は `PointerEvent` / `setPointerCapture` / `releasePointerCapture` /
 *   `hasPointerCapture` を**宣言済み**。未宣言だったのは `getCoalescedEvents` だけだった
 * - `MouseEvent.clientX` / `clientY` は **`Int`**（`Double` ではない。座標計算はここで広げる）
 */

/** `getCoalescedEvents` を持つ `PointerEvent`（`kotlinx-browser` は未宣言）。 */
external interface CoalescedPointerEvent : JsAny {
    /**
     * 1 フレームにまとまって届いた中間点（JS の `Array<PointerEvent>`）。
     *
     * @return 中間点の並び。要素は `PointerEvent`
     */
    fun getCoalescedEvents(): JsArray<JsAny>
}

/** `window` に `PointerEvent` が生えているかを見る宣言（有るかもしれないものとして扱う）。 */
external interface MaybePointerEventGlobal : JsAny {
    /**
     * `PointerEvent` クラス。無ければ `undefined` → `null`。
     *
     * **JS 側のグローバル名そのまま**なので小文字化できない（小文字にすると別物を見て
     * いつも `null` になる）。ktlint の命名規則だけ止める。
     */
    @Suppress("ktlint:standard:property-naming")
    val PointerEvent: JsAny?
}

/** 要素が `setPointerCapture` を持つかを見る宣言。 */
external interface MaybeSetPointerCapture : JsAny {
    /** メソッド。プロトタイプを辿って見付からなければ `null`。 */
    val setPointerCapture: JsAny?
}

/** イベントが `getCoalescedEvents` を持つかを見る宣言。 */
external interface MaybeGetCoalescedEvents : JsAny {
    /** メソッド。無ければ `null`。 */
    val getCoalescedEvents: JsAny?
}

/** `window.isSecureContext`（`kotlinx-browser` 0.5.0 は未宣言）。 */
external interface MaybeSecureContext : JsAny {
    /** https / localhost / file などであれば true。*/
    val isSecureContext: Boolean?
}

/**
 * devtools へ 1 行出す（`kotlinx-browser` は `console` を宣言していない）。
 *
 * **画面を止めないための記録**に使う。保存層は失敗を `null` に丸めるので、切り分けの手がかりが
 * 画面に出ない。だからといって例外を投げると「保存の失敗でアプリが止まる」になる（§S2-5）
 *
 * @param message 出す文字
 */
fun consoleLog(message: String) {
    runCatching { window.unsafeCast<ConsoleGlobal>().console?.log(message) }
}

/**
 * `window.console`（`kotlinx-browser` は未宣言）。
 *
 * `log` だけあれば足りるので、`error` / `warn` は必要になるまで足さない。
 * IDB など**宣言のかたまりは `js` パッケージ内に別ファイル**に置く
 * （`IdbGlue.kt` / `DomGlue.kt`。1 ファイルに全部入れると読めなくなる）
 */
external interface ConsoleGlobal : JsAny {
    /** 無い環境では `undefined` → `null`。 */
    val console: ConsoleBridge?
}

/** `console` オブジェクト。 */
external interface ConsoleBridge : JsAny {
    /**
     * 1 行書く。
     *
     * @param message 書く文字
     */
    fun log(message: String)
}

/**
 * 今このページが secure context か。
 *
 * **`getCoalescedEvents` / `navigator.clipboard` / `crypto.randomUUID` は secure context 限定**
 * （Chrome は 2024 に非 secure context から `getCoalescedEvents` を削除した）。Tailscale の IP 直
 * `http://100.x:8080` は secure context でないので、**「wasm が使えない」と「文脈が https でない」を
 * ここで切り分ける**（K0-2 の実測で踏んだ）。
 *
 * @return secure context なら true
 */
fun isSecureContext(): Boolean =
    window.unsafeCast<MaybeSecureContext>().isSecureContext == true

/**
 * ブラウザに `PointerEvent` が実在するか。
 *
 * @return 実在なら true
 */
fun hasPointerEventClass(): Boolean =
    window.unsafeCast<MaybePointerEventGlobal>().PointerEvent != null

/**
 * 要素が `setPointerCapture` を持つか（宣言はあっても実装が無いブラウザを見る）。
 *
 * @param element 見る要素
 * @return 持てるなら true
 */
fun hasSetPointerCapture(element: JsAny): Boolean =
    element.unsafeCast<MaybeSetPointerCapture>().setPointerCapture != null

/**
 * イベントが `getCoalescedEvents` を持つか。
 *
 * @param event 見るイベント
 * @return 持つなら true
 */
fun hasGetCoalescedEvents(event: JsAny): Boolean =
    event.unsafeCast<MaybeGetCoalescedEvents>().getCoalescedEvents != null

/**
 * 1 フレームにまとまって届いた中間点の個数（`web/src/editor/gestures.ts` が使っているものと同じ）。
 *
 * @param event `pointermove` の `PointerEvent`
 * @return 個数。**非対応・例外は -1**（0 と区別する）
 */
fun coalescedCount(event: JsAny): Int =
    runCatching { event.unsafeCast<CoalescedPointerEvent>().getCoalescedEvents().length }.getOrDefault(-1)

/**
 * 中間点の 1 本目の `clientX`。**wasm 側まで数値が正しく渡っているか**の実測に使う。
 *
 * @param event `pointermove` の `PointerEvent`
 * @return 取れなければ `null`（非対応・空・例外）
 */
fun coalescedFirstClientX(event: JsAny): Int? =
    runCatching {
        event.unsafeCast<CoalescedPointerEvent>().getCoalescedEvents().firstOrNull()
            ?.unsafeCast<MouseEvent>()?.clientX
    }.getOrNull()

/**
 * 1 フレームにまとまって届いた中間点の client 座標（**落書きのなめらかさ**・E-7）。
 *
 * iOS Safari は `pointermove` 1 回に数点を詰めることがあり、これを読まないとなめらかさが落ちる。
 * `web/src/editor/gestures.ts` の `coalescedPagePoints` と同じ口を Kotlin 側に置く
 *
 * @param event `pointermove` の `PointerEvent`
 * @return client 座標の点列（時系列順）。**非対応・空・例外は空列表**（呼び出し側がイベント本体 1 点に代用する）
 */
fun coalescedClientPoints(event: Event): List<Pair<Double, Double>> =
    runCatching {
        val points = event.unsafeCast<CoalescedPointerEvent>().getCoalescedEvents()
        // `JsArray` は Kotlin の collection ではないので添字で回る。添字は `JsAny?` で返る
        // （要素が `undefined` になり得る）ので安全呼びで受ける
        buildList {
            for (index in 0 until points.length) {
                val point = points[index]?.unsafeCast<MouseEvent>() ?: continue
                add(point.clientX.toDouble() to point.clientY.toDouble())
            }
        }
    }.getOrDefault(emptyList())

/**
 * `JsArray` の 1 件目（空なら `null`）。添字は `JsAny?` で返るので 1 本だけ包む。
 *
 * @receiver 対象の並び
 * @return 1 件目
 */
private fun JsArray<JsAny>.firstOrNull(): JsAny? =
    if (length == 0) null else this[0]

/*
 * ---------------------------------------------------------------------------
 * ジェスチャが待つ口・数える口（K2-4・`web/src/editor/viewerSwipe.ts` / `stripReorder.ts` の移植）
 * ---------------------------------------------------------------------------
 *
 * `kotlinx-browser` 0.5.0 を sources jar で引いて**実在を確認**してから足している（2026-11-21）:
 *
 * - `PointerEvent` は **`org.w3c.dom.pointerevents` に在る**。ただし `clientX` / `clientY` は
 *   `MouseEvent` 由来で **`Int`**。**ブラウザズーム 150% だと JS 側は小数で渡す**（`234.66667…`。
 *   kb-kmp-webview.md §12-1 の「ズームで px が拡大される」の実測と同じ状況）ので、**座標は `Double`
 *   で読む言い直しを 1 個置く**（`setPointerCapture` / `hasPointerCapture` は宣言済みなので使わない）
 * - `window.performance` は**宣言が無い**（`kotlinx.browser.performance` は unresolved・実測）。
 *   `performance.now()` は**単調増加**なので、速度計算に壁時計（`Date`）を使うとタイムゾーンや
 *   時計合わせでジェスチャが飛ぶ。ここだけを言い直す
 * - `window.setTimeout` のハンドラは **`() -> JsAny?` 形**（`Unit` を渡すと型エラー・実測）。
 *   戻り値は JS 側が見ないので `null` を返す 1 行をここに閉じる
 * - `requestAnimationFrame` / `cancelAnimationFrame` は宣言済み（`() -> Unit` をそのまま渡せる）
 * - `TransitionEvent` は**宣言が無い**（めくりの滑りが終わった合図だけ言い直す）
 */

/** `PointerEvent` の**座標と身元だけ**言い直した宣言（上の注のとおりの理由）。 */
external interface PointerLike : JsAny {
    /** 追っている指・マウス 1 本分の id（2 本目は無視する）。 */
    val pointerId: Int

    /** `"mouse"` / `"touch"` / `"pen"`。マウスは左ボタンだけ受けたい。 */
    val pointerType: String

    /** 押したボタンの番号（マウス左は 0）。 */
    val button: Int

    /** ビューポート基準の x（px。**ズーム時は小数**）。 */
    val clientX: Double

    /** ビューポート基準の y（px。**ズーム時は小数**）。 */
    val clientY: Double
}

/** `transitionend`（`kotlinx-browser` は未宣言。めくりの滑りが終わった合図に使う分だけ）。 */
external interface TransitionLike : JsAny {
    /** 滑り終えた CSS プロパティ名（`"transform"` だけ受けたい）。 */
    val propertyName: String
}

/** `window.performance` を言い直した宣言。 */
external interface PerformanceOwner : JsAny {
    /** 単調増加のミリ秒時計。 */
    val performance: MonotonicClock
}

/** `Performance`（`now()` だけ）。 */
external interface MonotonicClock : JsAny {
    /**
     * ページを開けてからの経過時間（ms）。**単調増加**（壁時計と違い巻き戻らない）。
     *
     * @return 経過時間
     */
    fun now(): Double
}

/**
 * ポインタイベントを言い直す（**`clientX` / `clientY` を小数で読む**ための 1 本）。
 *
 * @receiver `pointerdown` 等で受けたイベント
 * @return 座標と身元
 */
fun Event.asPointer(): PointerLike =
    unsafeCast<PointerLike>()

/**
 * 遷移イベントのプロパティ名（`"transform"` だけを見たい場所で使う）。
 *
 * @receiver `transitionend` で受けたイベント
 * @return プロパティ名。読めなければ空文字
 */
fun Event.transitionProperty(): String =
    runCatching { unsafeCast<TransitionLike>().propertyName }.getOrDefault("")

/**
 * 単調増加の現在時刻（ms）。**ドラッグの速度測定はこれ**（壁時計を使うと時計合わせで飛ぶ）。
 *
 * @return `performance.now()`。使えない環境は 0
 */
fun nowMillis(): Double =
    runCatching { window.unsafeCast<PerformanceOwner>().performance.now() }.getOrDefault(0.0)

/**
 * 遅れて 1 回走らせる（`window.setTimeout` の**関数形**）。
 *
 * @param delayMillis 遅延（ms）
 * @param action 走らせる処理
 * @return 取り消しに使う id（[cancelAfter] に渡す）
 */
fun afterMillis(
    delayMillis: Int,
    action: () -> Unit,
): Int =
    // JS は戻り値を見ないので `null` を返す（kotlinx-browser のハンドラ形は `() -> JsAny?`）
    window.setTimeout({
        action()
        null
    }, delayMillis)

/**
 * [afterMillis] を取り消す（画面を離れるとき・ジェスチャをやり直したとき）。
 *
 * @param handle [afterMillis] の返り値
 */
fun cancelAfter(handle: Int) {
    runCatching { window.clearTimeout(handle) }
}

/**
 * 次のフレームで 1 回走らせる（慣性スクロール・ドラッグ中の自動スクロール）。
 *
 * @param action 走らせる処理（フレームの時刻 ms を受け取る）
 * @return 取り消しに使う id（[cancelFrame] に渡す）
 */
fun nextFrame(action: (Double) -> Unit): Int =
    window.requestAnimationFrame(action)

/**
 * [nextFrame] を取り消す。
 *
 * @param handle [nextFrame] の返り値
 */
fun cancelFrame(handle: Int) {
    runCatching { window.cancelAnimationFrame(handle) }
}
