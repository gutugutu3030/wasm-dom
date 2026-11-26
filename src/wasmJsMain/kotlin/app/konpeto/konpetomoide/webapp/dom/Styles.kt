package app.konpeto.konpetomoide.webapp.dom

import org.w3c.dom.HTMLElement

/*
 * インライン style に載せるヘルパ。
 *
 * `element.style.setProperty(name, value)` を使う理由は 1 つ ―― **CSS 変数（`--paper-ratio`）は
 * プロパティ代入では入らない**（`style.asDynamic()` は wasm から叩かない）。`setProperty` は
 * 通常のプロパティにも同じ効き方をするので、**style への書き込みはここに 1 本**に寄せる。
 */

/**
 * CSS に数値を渡すときの書式（`50` / `0.3` / `0`）。
 *
 * Kotlin の `Double.toString()` は整数にも `.0` を付ける（`50.0%`）。CSS は両方受け取るが、
 * **devtools で見比べる相手が `.0` 無しなので揃える**（SVG `points` に渡す数値と同じ話）。
 * ここが持つのは書式だけで、用紙の計算は検証しない。
 *
 * @receiver 出す数値
 * @return 接頭の小文字表記
 */
fun Double.toCssNumber(): String =
    when {
        // `-0.0` も `0`
        this == 0.0 -> "0"

        // 整数は `.0` を落とす（桁あふれは `toLong()` で化けるので範囲で絞る）
        this % 1.0 == 0.0 && this > -1e15 && this < 1e15 -> toLong().toString()

        else -> toString()
    }

/**
 * インライン style を 1 個載せる。**CSS 変数もこれ**（`--paper-ratio` 等）。
 *
 * @param name プロパティ名（CSS 表記そのまま。キャメルCase 不要）
 * @param value 値
 * @receiver 載せる要素
 */
fun HTMLElement.setStyle(
    name: String,
    value: String,
) {
    style.setProperty(name, value)
}

/**
 * インライン style をまとめて載せる（**後勝ち**。配置 style に用紙変数を重ねる使い方を想定）。
 *
 * @param styles 載せる style（`PageSize.toPaperStyle()` の返り値など）
 * @receiver 載せる要素
 */
fun HTMLElement.setStyles(styles: Map<String, String>) {
    styles.forEach { (name, value) -> setStyle(name, value) }
}

/**
 * `data-*` 属性を載せる（`setData("item-id", id)` → `data-item-id`）。
 *
 * **あたり判定はこの属性を実物要素に載せるのが約束** ―― 判定を CSS で再現すると DOM 実寸とズレる
 *
 * @param name `data-` 以降の名前（ハイフン区切り）
 * @param value 値
 * @receiver 載せる要素
 */
fun HTMLElement.setData(
    name: String,
    value: String,
) {
    setAttribute("data-$name", value)
}

/**
 * インライン style を 1 個消す（**遷移を切ったうえで位置だけ元に戻す**ときに使う）。
 *
 * `setStyle(name, "")` でも消えるが、「消す」という意図が読める方が後から触る人に速い。
 * CSS 変数も同じ口で消える
 *
 * @param name 消すプロパティ名（CSS 表記そのまま）
 * @receiver 消す要素
 */
fun HTMLElement.removeStyle(
    name: String,
) {
    style.removeProperty(name)
}
