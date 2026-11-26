package app.konpeto.konpetomoide.webapp.js

import org.w3c.dom.Window

/*
 * `window.visualViewport` の言い直し（残作業 10・2026-11-23）。
 *
 * `kotlinx-browser` の `Window` は `visualViewport` を宣言していない（`DomGlue.kt` に書いた
 * とおり「無いものだけ必要な形で作る」対象）。キーボードで**見える範囲**を買う口はこれで、
 * iOS はキーボードで `innerHeight` も `visualViewport` も**両方縮む**（実測）ので、
 * 「見えている範囲」を区別できるのはこの口だけ。
 */

/**
 * `window.visualViewport`（**見えている範囲**の口）。
 *
 * レイアウトビューポート（`window.innerWidth` / `innerHeight`）とは**別物**で、iOS は
 * ソフトキーボードを出すと**両方**を動かす（2026-11-23 実測: `innerHeight` 874 → 589、
 * `visualViewport.height` → 470）。**入力支援バーを除いた高さが `height`** に入る。
 */
external interface VisualViewport : JsAny {
    /** 支援バーとキーボードを除いた見える高さ（CSS px）。 */
    val height: Double

    /** 支援バーとキーボードを除いた見える幅（CSS px）。 */
    val width: Double

    /** レイアウトビューポートに対する左上のオフセット（ページがずれた分）。 */
    val offsetTop: Double

    /** 拡大率（**1 が等倍**。iOS のページズームが効いていると 1 を超える）。 */
    val scale: Double

    /**
     * リスナを貼る。
     *
     * @param type `resize`（キーボードの出し入れ・回転） / `scroll`
     * @param callback 受ける口（イベント物件は使わないので中身は読まない）
     */
    fun addEventListener(
        type: String,
        callback: (JsAny) -> Unit,
    )
}

/** `Window` を [visualViewportOrNull] で引くための言い直し。 */
external interface WindowWithVisualViewport : JsAny {
    /** 持たないブラウザは `undefined`（Kotlin 側では null として扱う）。 */
    val visualViewport: VisualViewport?
}

/**
 * `window.visualViewport` を引く（**無いブラウザは null** で、その場合は呼び出し側が何もしない）。
 *
 * @receiver 対象の窓（通常は `kotlinx.browser.window`）
 * @return 見えている範囲の口。`visualViewport` を持たないブラウザでは null
 */
val Window.visualViewportOrNull: VisualViewport?
    get() = unsafeCast<WindowWithVisualViewport>().visualViewport
