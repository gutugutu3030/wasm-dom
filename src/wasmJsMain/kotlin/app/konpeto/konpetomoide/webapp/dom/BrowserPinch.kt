package app.konpeto.konpetomoide.webapp.dom

import app.konpeto.konpetomoide.webapp.js.CtrlKeyWheelEvent
import app.konpeto.konpetomoide.webapp.js.OptionsEventTarget
import app.konpeto.konpetomoide.webapp.js.passiveFalseOptions
import kotlinx.browser.document
import kotlinx.browser.window

/*
 * ブラウザ自身のジェスチャを抑止する（K2-1・`web/src/editor/gestures.ts` の `blockBrowserPinch()` と同じ）。
 *
 * 編集画面のピンチ（2 本指）をブラウザのページズームに取られると、指の生データが
 * `pointercancel` で消える（`doc/kb-editor-gesture.md` の E-11 と同じ罠）。なので**起動時に 1 回**貼る。
 */

/**
 * トラックパッド / Safari のページズームを抑止する（**起動時に 1 回**呼ぶ）。
 *
 * 止めるものは 2 種類:
 *
 * 1. **トラックパッドのピンチ** ＝ `ctrl` を伴う `wheel`。`window` に貼る `wheel` は**既定が
 *    `passive: true`** なので `{ passive: false }` を添えないと `preventDefault()` が黙られる
 *    （`kotlinx-browser` は boolean 形しか無いので `js/DomGlue.kt` に言い直した）
 * 2. **Safari 固有のページズーム**（`gesturestart` / `gesturechange` / `gestureend`）。受ける口が
 *    `document` なのも TS と同じ
 *
 * 面にかける `touch-action: none` は画面側の仕事なのでここでは触らない（`touch-action` は
 * **ヒット経路の積**で効く・`doc/kb-editor-gesture.md`）。
 */
fun blockBrowserPinch() {
    window.unsafeCast<OptionsEventTarget>().addEventListener(
        "wheel",
        { event ->
            val wheel = event.unsafeCast<CtrlKeyWheelEvent>()
            if (wheel.ctrlKey) wheel.preventDefault()
        },
        passiveFalseOptions(),
    )

    for (type in listOf("gesturestart", "gesturechange", "gestureend")) {
        document.addEventListener(type) { it.preventDefault() }
    }
}
