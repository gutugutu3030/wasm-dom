package app.konpeto.konpetomoide.webapp.dom

import app.konpeto.konpetomoide.webapp.js.visualViewportOrNull
import kotlinx.browser.document
import kotlinx.browser.window
import kotlin.js.JsAny

/*
 * キーボードで**シート最下部の操作行が押せなくなる**回避（残作業 10・2026-11-23 実測）。
 *
 * 実測（iOS シミュレータ・`iosApp/UITests/ShellUITests.swift`）:
 * - iOS はソフトキーボードを出すと**レイアウトビューポートそのものを買う**（`innerHeight` 874 → 589）
 * - その**さらに上を入力支援バー（`^` `v` `✓`）が覆う**（589 のうち **481..538**）
 * - `position: fixed` のシートは縮んだ viewport に追従するが、**操作行 `.sheet-actions`
 *   （486..531）が支援バーの下に沈んで `キャンセル` / `作成` が押せなかった**
 *
 * `env(keyboard-inset-height)` は **Chrome 専用**で iOS は持たない。なので
 * **`innerHeight` と `visualViewport.height` の差**を CSS 変数に流す ―― `visualViewport.height` は
 * **支援バーを除いた見える高さ**（実測 470）なので、**差（119）が「キーボード + 支援バーで塞がる分」**
 * になる（`innerHeight` だけを見ると 51 px 足りない＝支援バー分が抜ける）。
 */

/** 流す CSS 変数名（`styles.css` の `.sheet-backdrop` が `padding-bottom` で読む）。 */
private const val KEYBOARD_INSET = "--keyboard-inset"

/**
 * もう 1 個流す変数名（**fixed 層が上にずれた分**）。
 *
 * iOS はキーボードで固定要素を**含めた層ごと上に送る**（2026-11-23 実測:
 * `.sheet-backdrop` の `getBoundingClientRect().top` が **`−309`**、`visualViewport.offsetTop` と一致）。
 * そのままだと**上 padding が画面外で無駄になり**、シート見出しがステータスバーの下に沈む。
 * `padding-top` にこの分を足すと**見えている帯の中に収まる**。
 */
private const val KEYBOARD_SHIFT = "--keyboard-shift"

/**
 * キーボードで塞がる分を `--keyboard-inset` として `document.body` に載け続ける（**起動時に 1 回**貼る）。
 *
 * `visualViewport` の `resize` / `scroll` ごとに書き換える。**キーボードが畳まれている間は 0**
 * なので、デスクトップのブラウザと iOS の通常時は**見え方が変わらない**。`visualViewport` の無い
 * ブラウザでは**何も貼らない**（CSS 側の `var(--keyboard-inset, 0px)` の既定が効く）。
 *
 * **貼る場所**: `App.kt` の起動時（[blockBrowserPinch] とおなじ位置）。
 */
fun installKeyboardInset() {
    val viewport = window.visualViewportOrNull ?: return

    /** 塞がる分と、fixed 層が上にずれた分を px で載せる（負値は来ないが `coerceAtLeast` で保険）。 */
    val applyInset: () -> Unit = {
        val insetPx = (window.innerHeight - viewport.height).coerceAtLeast(0.0)
        val shiftPx = viewport.offsetTop.coerceAtLeast(0.0)
        document.body?.let { body ->
            body.setStyle(KEYBOARD_INSET, "${insetPx.toCssNumber()}px")
            body.setStyle(KEYBOARD_SHIFT, "${shiftPx.toCssNumber()}px")
        }
    }
    val onChange: (JsAny) -> Unit = { applyInset() }

    viewport.addEventListener("resize", onChange)
    viewport.addEventListener("scroll", onChange)
    applyInset()
}
