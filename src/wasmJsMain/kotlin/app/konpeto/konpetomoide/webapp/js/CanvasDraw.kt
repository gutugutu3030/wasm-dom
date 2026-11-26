package app.konpeto.konpetomoide.webapp.js

import kotlin.js.JsAny

/*
 * canvas に**文字と経路を描く口**（K2-7・`js/CanvasText.kt` 相当の追加宣言）。
 *
 * `js/DomGlue.kt` の `Canvas2d` は縮小版生成に必要な 4 個（`fillStyle` / `fillRect` /
 * `drawImage` / `getImageData`）だけ持っていた。印刷解像度レンダ（`web/src/print/renderPage.ts`
 * に対応）はこれに加えて **19 個**必要。`kotlinx-browser` 0.5.0 は `CanvasRenderingContext2D` を
 * 持っていないので、`unsafeCast` で言い直す（`DomGlue.kt` の `Canvas2dProvider` と同じ手）。
 *
 * 1 個の interface に集めているのは、**描画中に読む型を 2 個に割ると「どっちが今の状態か」が
 * 分からなくなる**から（`fillStyle` は墨色 → 敷き色 → 墨色と往復する）。
 */

/** `CanvasRenderingContext2D` を、印刷レンダラが使う分だけ言い直した宣言。 */
@Suppress("TooManyFunctions")
external interface Canvas2dDraw : JsAny {
    /** 塗り色（`#rrggbb` / `rgba(...)`）。 */
    var fillStyle: String

    /** 線色（`rgba(...)`）。 */
    var strokeStyle: String

    /** 線の太さ（px）。 */
    var lineWidth: Double

    /** 線の端の形（`"round"` / `"butt"`）。 */
    var lineCap: String

    /** 線の継ぎ目の形（`"round"` / `"miter"`）。 */
    var lineJoin: String

    /** 書体（`"${weight} ${size}px ${css}"`）。 */
    var font: String

    /** 水平揃え（`"center"` 等）。 */
    var textAlign: String

    /** 垂直揃え（`"middle"` 等）。 */
    var textBaseline: String

    /** 今の状態（色・变换）を積む。 */
    fun save()

    /** 積んだ状態に戻す。 */
    fun restore()

    /**
     * 原点を動かす（**アイテムの中心へ揃える**のに使う）。
     *
     * @param x 動かす量 x（px）
     * @param y 動かす量 y（px）
     */
    fun translate(
        x: Double,
        y: Double,
    )

    /**
     * 回転する（**ラジアン**。画面 CSS の `rotate(deg)` と同じ組み立てをする）。
     *
     * @param angle 回転角（ラジアン）
     */
    fun rotate(angle: Double)

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
     * 長方形の枠だけ描く（**読めない形・読めない写真をその枠だけ残す**のに使う）。
     *
     * @param x 左上 x（px）
     * @param y 左上 y（px）
     * @param width 幅（px）
     * @param height 高さ（px）
     */
    fun strokeRect(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
    )

    /** 新しい経路を始める。 */
    fun beginPath()

    /**
     * 軸を置く。
     *
     * @param x x（px）
     * @param y y（px）
     */
    fun moveTo(
        x: Double,
        y: Double,
    )

    /**
     * 軸から線を延ばす。
     *
     * @param x 終点 x（px）
     * @param y 終点 y（px）
     */
    fun lineTo(
        x: Double,
        y: Double,
    )

    /** 今の経路を線で描く。 */
    fun stroke()

    /** 今の経路を塗る。 */
    fun fill()

    /**
     * 弧を積む（**タップだけで離した落書き**は点 1 個なので、丸めて塗る）。
     *
     * @param x 中心 x（px）
     * @param y 中心 y（px）
     * @param radius 半径（px）
     * @param startAngle 開始角（ラジアン）
     * @param endAngle 終了角（ラジアン）
     */
    fun arc(
        x: Double,
        y: Double,
        radius: Double,
        startAngle: Double,
        endAngle: Double,
    )

    /**
     * 文字を塗る。
     *
     * @param text 描く文字
     * @param x 揃え位置 x（px）
     * @param y 揃え位置 y（px）
     */
    fun fillText(
        text: String,
        x: Double,
        y: Double,
    )

    /**
     * 画像を縮んで描く。
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
     * 文字列の描画幅を測る（**改行位置と敷き色の幅**がこれに依存する）。
     *
     * @param text 測る文字列
     * @return 測り結果。使えない環境は `null`
     */
    fun measureText(text: String): JsTextMetrics?
}

/** `TextMetrics`（[Canvas2dDraw.measureText] の返り値）。 */
external interface JsTextMetrics : JsAny {
    /** 描画幅（px）。 */
    val width: Double
}

/**
 * 描画文脈を、**文字と経路を描く口**として言い直す。
 *
 * @receiver [context2d] で引いた 2d 文脈
 * @return 同じ文脈の言い直し（JS 側は同じオブジェクト）
 */
fun Canvas2d.asDraw(): Canvas2dDraw =
    unsafeCast<Canvas2dDraw>()
