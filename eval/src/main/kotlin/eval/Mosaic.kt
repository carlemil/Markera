package eval

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.min

/**
 * Compose [tiles] into a [cols]x[rows] grid. Each tile is letterboxed into a
 * [cell]x[cell] square on a dark background with a thin gutter, preserving
 * aspect ratio so nothing is distorted.
 */
fun mosaic(tiles: List<BufferedImage>, cols: Int, rows: Int, cell: Int = 760, gap: Int = 8): BufferedImage {
    val w = cols * cell + (cols + 1) * gap
    val h = rows * cell + (rows + 1) * gap
    val canvas = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = canvas.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.color = Color(0x18, 0x18, 0x18)
    g.fillRect(0, 0, w, h)

    for ((i, tile) in tiles.take(cols * rows).withIndex()) {
        val r = i / cols
        val c = i % cols
        val cellX = gap + c * (cell + gap)
        val cellY = gap + r * (cell + gap)
        g.color = Color.BLACK
        g.fillRect(cellX, cellY, cell, cell)

        val scale = min(cell.toFloat() / tile.width, cell.toFloat() / tile.height)
        val dw = (tile.width * scale).toInt().coerceAtLeast(1)
        val dh = (tile.height * scale).toInt().coerceAtLeast(1)
        val dx = cellX + (cell - dw) / 2
        val dy = cellY + (cell - dh) / 2
        g.drawImage(tile, dx, dy, dw, dh, null)
    }
    g.dispose()
    return canvas
}
