package app.konpeto.konpetomoide.webapp.dom

import app.konpeto.konpetomoide.webapp.js.jsJsonParse
import kotlinx.browser.document
import org.w3c.dom.Element
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLPreElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.ParentNode
import kotlin.js.JsAny

/*
 * 要素を組み立てる最小のヘルパ。
 *
 * 描画ランタイム（極小シグナル）は `reactive/Signal.kt`（K2-1）。**ここでは作った要素を返すだけ**にして、
 * 再描画はランタイムに持たせる（ここに状態を持つと、画面ごとに作り直せなくなる）。
 *
 * **Kotlin/Wasm では `as` でなく `unsafeCast` を使う**（実測）。`as` は JS 側の RTTI 判定に
 * なって落ちる可能性があるが、`document.createElement` の返りは作られたタグが確定なので
 * 型の言い直しで足りる。
 */

/**
 * id で要素を拾う。
 *
 * @param id 拾う id
 * @return 見付かった要素
 * @throws IllegalStateException `index.html` にその id が無いとき（構造のミスを画面で気づくため）
 */
fun elementById(id: String): HTMLElement =
    (document.getElementById(id) ?: error("#$id が見つかりません（index.html の構造を確認してください）"))
        .unsafeCast<HTMLElement>()

/**
 * セレクタに当たった要素を **Kotlin の列**で集める（`NodeList` を回す唯一の口）。
 *
 * `querySelectorAll` の返り値は `NodeList` で、`item(index)` を 1 個ずつ引すもの。for 文で回せず、
 * `Element` への言い直しも 1 個ずつ必要なので、ここで列に写してしまう（K2-7 の印刷エリア掃除が最初の使い手）。
 * **`document` に掛けるのは body 直下の要素を探すとき**（`@media print` の子セレクタは body の直子しか
 * 見ない・`print/PrintPages.kt`）
 *
 * @param selector 集めるセレクタ（`.print-area` など）
 * @receiver 探しかける親（`document` でも要素でも）
 * @return 見つかった要素の列（DOM 順）。使えない環境は空
 */
fun ParentNode.querySelectorAllList(selector: String): List<Element> {
    val nodes = runCatching { querySelectorAll(selector) }.getOrNull() ?: return emptyList()
    return (0 until nodes.length).mapNotNull { index ->
        val node = runCatching { nodes.item(index) }.getOrNull() ?: return@mapNotNull null
        runCatching { node.unsafeCast<Element>() }.getOrNull()
    }
}

/**
 * 子に `div` を足す。
 *
 * @param className 付けたいクラス名（無ければ空）
 * @receiver 足す先
 * @return 作った要素
 */
fun HTMLElement.appendDiv(className: String = ""): HTMLDivElement =
    createElement("div", className).unsafeCast<HTMLDivElement>()

/**
 * 子に `pre` を足す（読み出し専用の表示に使う）。
 *
 * @param className 付けたいクラス名（無ければ空）
 * @receiver 足す先
 * @return 作った要素
 */
fun HTMLElement.appendPre(className: String = ""): HTMLPreElement =
    createElement("pre", className).unsafeCast<HTMLPreElement>()

/**
 * 子に `textarea` を足す（実測値をコピーして報告できるようにする）。
 *
 * @param className 付けたいクラス名（無ければ空）
 * @param rows 表示行数
 * @receiver 足す先
 * @return 作った要素
 */
fun HTMLElement.appendTextArea(className: String = "", rows: Int = 6): HTMLTextAreaElement {
    val area = createElement("textarea", className).unsafeCast<HTMLTextAreaElement>()
    area.rows = rows
    return area
}

/**
 * 子に `button` を足す。ラベルは `agents.md` の UI 規則どおり**押下後に起きることを動詞で**書く。
 *
 * @param label ボタンに出る文字
 * @param className 付けたいクラス名（無ければ空）
 * @receiver 足す先
 * @return 作った要素
 */
fun HTMLElement.appendButton(label: String, className: String = ""): HTMLButtonElement {
    val button = createElement("button", className).unsafeCast<HTMLButtonElement>()
    button.textContent = label
    return button
}

/**
 * クラス名を足す（上書きではなく追記）。
 *
 * @param name 足すクラス名
 * @receiver 対象の要素
 */
fun HTMLElement.addClass(name: String) {
    classList.add(name)
}

/**
 * 子要素を作る（`className` を付けてから append する。**作って付けない**事故を防ぐ）。
 *
 * @param tag 作るときのタグ名
 * @param className 付けたいクラス名（無ければ空）
 * @receiver 足す先
 * @return 作った要素
 */
private fun HTMLElement.createElement(tag: String, className: String): HTMLElement {
    val child = document.createElement(tag)
    if (className.isNotEmpty()) child.className = className
    appendChild(child)
    return child.unsafeCast<HTMLElement>()
}

/**
 * 子に `main` を足す（画面本体。`.screen` が余白・最大幅・最小高さを決める）。
 *
 * @param className 付けたいクラス名（無ければ空）
 * @receiver 足す先
 * @return 作った要素
 */
fun HTMLElement.appendMain(className: String = ""): HTMLElement =
    createElement("main", className)

/**
 * 子に `header` を足す（画面の見出し行 `.screen-header`）。
 *
 * @param className 付けたいクラス名（無ければ空）
 * @receiver 足す先
 * @return 作った要素
 */
fun HTMLElement.appendHeader(className: String = ""): HTMLElement =
    createElement("header", className)

/**
 * 子に `h1` を足す（`.screen-title`）。
 *
 * @param className 付けたいクラス名（無ければ空）
 * @receiver 足す先
 * @return 作った要素
 */
fun HTMLElement.appendHeading(className: String = ""): HTMLElement =
    createElement("h1", className)

/**
 * 子に `p` を足す（状態表示の `.state` / `.state-error`）。
 *
 * @param className 付けたいクラス名（無ければ空）
 * @receiver 足す先
 * @return 作った要素
 */
fun HTMLElement.appendParagraph(className: String = ""): HTMLElement =
    createElement("p", className)

/**
 * 子に `a` を足す。**hash ルータなので `href = "#/albums/…"` をそのまま踏める**（JS 不要）。
 *
 * @param label リンクに出る文字
 * @param href 行き先（[app.konpeto.konpetomoide.webapp.nav.toHash] で組み立てる）
 * @param className 付けたいクラス名（無ければ空）
 * @receiver 足す先
 * @return 作った要素
 */
fun HTMLElement.appendAnchor(
    label: String,
    href: String,
    className: String = "",
): HTMLAnchorElement {
    val anchor = createElement("a", className).unsafeCast<HTMLAnchorElement>()
    anchor.href = href
    anchor.textContent = label
    return anchor
}

/**
 * 子要素を全部取り除く（画面を差し替えるときに前の画面の残骸を消す）。
 *
 * @receiver 空にする要素
 */
fun HTMLElement.clearChildren() {
    var child = firstChild
    while (child != null) {
        removeChild(child)
        child = firstChild
    }
}

/**
 * 子に `img` を足す。**`src` は後から置いてよい**（置いていない `<img>` は何も読みに行かないが、
 * `undefined` を代入すると `"undefined"` という相対 URL を読みに行く・`PhotoImage.tsx` の教訓）
 *
 * @param className 付けたいクラス名（無ければ空）
 * @receiver 足す先
 * @return 作った要素
 */
fun HTMLElement.appendImg(className: String = ""): HTMLImageElement =
    createElement("img", className).unsafeCast<HTMLImageElement>()

/**
 * 子に `span` を足す（テキストの箱・敷き色の内側 inline span など）。
 *
 * @param className 付けたいクラス名（無ければ空）
 * @receiver 足す先
 * @return 作った要素
 */
fun HTMLElement.appendSpan(className: String = ""): HTMLElement =
    createElement("span", className)

/**
 * 子に **SVG 要素**を足す（`svg` / `polyline` を同じ関数で足す）。
 *
 * **`document.createElement("svg")` では namespace が無く、描画されない**（K2-2 の実測ポイント）。
 * `document.createElementNS("http://www.w3.org/2000/svg", …)` が要る。
 *
 * そしてもう 1 つ癖: **SVG 要素の `className` は文字列ではなく `SVGAnimatedString`**（代入できない）。
 * なのでクラス名も `setAttribute("class", …)` で載せる（`viewBox` 等も属性で置く）
 *
 * @param tag SVG のタグ名（`svg` / `polyline` …）
 * @param className 付けたいクラス名（無ければ空）
 * @receiver 足す先（HTML 要素でも SVG 要素でもよい）
 * @return 作った要素
 */
fun Element.appendSvgChild(
    tag: String,
    className: String = "",
): Element {
    val child = document.createElementNS(SVG_NAMESPACE, tag)
    // SVG では `className` に代入できないので属性で載せる
    if (className.isNotEmpty()) child.setAttribute("class", className)
    appendChild(child)
    return child.unsafeCast<Element>()
}

/** SVG の namespace（`createElementNS` に渡す固定値）。 */
private const val SVG_NAMESPACE = "http://www.w3.org/2000/svg"

/**
 * 子に `ul` を足す（`.album-grid` のような一覧の箱）。
 *
 * @param className 付けたいクラス名（無ければ空）
 * @receiver 足す先
 * @return 作った要素
 */
fun HTMLElement.appendList(className: String = ""): HTMLElement =
    createElement("ul", className)

/**
 * 子に `li` を足す（`.album-card` など、**リンクと操作ボタンを一緒に並べる箱**）。
 *
 * @param className 付けたいクラス名（無ければ空）
 * @receiver 足す先
 * @return 作った要素
 */
fun HTMLElement.appendListItem(className: String = ""): HTMLElement =
    createElement("li", className)

/**
 * 子に `form` を足す（`.sheet`。**submit を `preventDefault()` すればページはリロードされない**）。
 *
 * @param className 付けたいクラス名（無ければ空）
 * @receiver 足す先
 * @return 作った要素
 */
fun HTMLElement.appendForm(className: String = ""): HTMLElement =
    createElement("form", className)

/**
 * 子に `label` を足す（`.field` / `.preset` のように**入力と見出しをまとめる箱**）。
 *
 * @param className 付けたいクラス名（無ければ空）
 * @receiver 足す先
 * @return 作った要素
 */
fun HTMLElement.appendLabel(className: String = ""): HTMLElement =
    createElement("label", className)

/**
 * 子に `h2` を足す（`.sheet-title`。画面タイトルは [appendHeading] の `h1`）
 *
 * @param className 付けたいクラス名（無ければ空）
 * @receiver 足す先
 * @return 作った要素
 */
fun HTMLElement.appendSubheading(className: String = ""): HTMLElement =
    createElement("h2", className)

/**
 * 子に `input` を足す。**`value` はブラウザが覚えている現在値でもあり、読むのもここ**
 * （`event.currentTarget` を `HTMLInputElement` に言い直して `.value`）。
 *
 * `checked` と `disabled` は代入で扱う（`HTMLInputElement` のプロパティ）。**radio は `checked` を
 * 後から付け替えても group の排他はブラウザがやってくれる**ので、選択状態は Kotlin 側の信号で持つ
 *
 * @param type `text` / `radio` など
 * @param className 付けたいクラス名（無ければ空）
 * @param name 入力群の名前（radio group を揃える）
 * @param value 送信値（radio はプリセット id）
 * @param placeholder 空のときの見本
 * @receiver 足す先
 * @return 作った要素
 */
fun HTMLElement.appendInput(
    type: String,
    className: String = "",
    name: String = "",
    value: String = "",
    placeholder: String = "",
): HTMLInputElement {
    val input = createElement("input", className).unsafeCast<HTMLInputElement>()
    input.type = type
    if (name.isNotEmpty()) input.name = name
    if (value.isNotEmpty()) input.value = value
    if (placeholder.isNotEmpty()) input.placeholder = placeholder
    return input
}

/**
 * 子に `nav` を足す（`.strip` のように**一覧そのものに名前を付けたい箱**）。
 *
 * `role` は `nav` が既に持っている（スクリーンリーダーに「ページ一覧」と読ませるのは
 * `aria-label` のほう）
 *
 * @param className 付けたいクラス名（無ければ空）
 * @receiver 足す先
 * @return 作った要素
 */
fun HTMLElement.appendNav(className: String = ""): HTMLElement =
    createElement("nav", className)

/**
 * 子に `section` を足す（**設定画面の区分 `.menu-section`**・K2-8）。
 *
 * `div` でも組めるが、`section` にすると `aria-label` で「この塊は何の設定か」が読み上げられる
 * （設定画面は 4 区分が縦に並ぶので、見出しを目で追えない人に区分の境目が伝わらない）。
 * `.menu-section + .menu-section` は CSS 側で **2 個目から自動で区切り線**が出る（区分の目印に
 * クラスを増やさない・D-44）
 *
 * @param className 付けたいクラス名（無ければ空）
 * @param label 区域の名前（`aria-label`）。空は付けない
 * @receiver 足す先
 * @return 作った要素
 */
fun HTMLElement.appendSection(
    className: String = "",
    label: String = "",
): HTMLElement {
    val section = createElement("section", className)
    if (label.isNotEmpty()) section.setAttribute("aria-label", label)
    return section
}

/**
 * 子に `footer` を足す（`header` の対。印刷プレビューの操作バー `.print-bar`）。
 *
 * @param className 付けたいクラス名（無ければ空）
 * @receiver 足す先
 * @return 作った要素
 */
fun HTMLElement.appendFooter(className: String = ""): HTMLElement =
    createElement("footer", className)

/**
 * 子に `figure` を足す（**1 枚の実物とそのキャプション**を 1 塊にしておく箱）。
 *
 * 印刷プレビューの `.print-sheet` がこれ。`img` だけだと「何ページ目か」が構造的に落ちる
 *
 * @param className 付けたいクラス名（無ければ空）
 * @receiver 足す先
 * @return 作った要素
 */
fun HTMLElement.appendFigure(className: String = ""): HTMLElement =
    createElement("figure", className)

/**
 * 子に `figcaption` を足す（`figure` のキャプション）。
 *
 * @param className 付けたいクラス名（無ければ空）
 * @receiver 足す先
 * @return 作った要素
 */
fun HTMLElement.appendFigCaption(className: String = ""): HTMLElement =
    createElement("figcaption", className)

/**
 * 子に `style` を足す（**印刷の `@page size` を用紙ごとに注入する**ため・K2-7）。
 *
 * `textContent` に CSS の文字列を置くだけ（`CSSStyleSheet` API は使わない）。`@page` は
 * 生きている `style` 要素でないと効かないので、**画面本体ではなく `.print-area` に載せる**
 *
 * @param className 付けたいクラス名（無ければ空）
 * @receiver 足す先
 * @return 作った要素（`textContent` に CSS を置く）
 */
fun HTMLElement.appendStyleElement(className: String = ""): HTMLElement =
    createElement("style", className)

/**
 * 箱の中に見えるところまで要素を送る（**ページをめくったときに対応するサムネイルを引き込む**）。
 *
 * `.thumb` は `touch-action: none`（長押しドラッグ＝並べ替えに取られる）なので、**サムネイル上は
 * 慣性スクロールが効かない**。めくったページを自分で見せないと「今のページが画面外」が起きる
 * （`web/src/screens/AlbumViewerScreen.tsx` と同じ理由）。
 *
 * `scrollIntoView` の options 形（`behavior` / `inline` / `block`）は `kotlinx-browser` が
 * `JsAny` しか受け取らないので、`JSON.parse` で作る（`js/IdbGlue.kt` の実測）
 *
 * @receiver 見せたい要素
 */
fun HTMLElement.scrollIntoViewNearest() {
    val options =
        jsJsonParse("""{"behavior":"smooth","inline":"nearest","block":"nearest"}""")
            ?.let { value -> runCatching { value.unsafeCast<JsAny>() }.getOrNull() }
            ?: return
    runCatching { scrollIntoView(options) }
}
