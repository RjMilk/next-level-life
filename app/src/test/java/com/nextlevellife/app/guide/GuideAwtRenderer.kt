package com.nextlevellife.app.guide

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage

/** JVM backend for the exact paths used by GuideFigure; no separate mannequin geometry. */
internal object GuideAwtRenderer {
    val background = Color(0x18, 0x1B, 0x21)

    fun render(guide: ExerciseGuide, pose: GuidePose, size: Int = 384): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.color = background
            g.fillRect(0, 0, size, size)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.scale(size.toDouble(), size.toDouble())
            for (shape in GuideGeometry.scene(guide, pose)) {
                val path = Path2D.Float()
                for (op in shape.path) when (op) {
                    is GuidePathOp.Move -> path.moveTo(op.point.x, op.point.y)
                    is GuidePathOp.Line -> path.lineTo(op.point.x, op.point.y)
                    is GuidePathOp.Cubic -> path.curveTo(op.control1.x, op.control1.y, op.control2.x, op.control2.y, op.end.x, op.end.y)
                    GuidePathOp.Close -> path.closePath()
                }
                g.color = Color(GuidePalette.argb(shape.paint).toInt(), true)
                g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, shape.alpha)
                if (shape.strokeWidth > 0f) {
                    g.stroke = BasicStroke(shape.strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g.draw(path)
                } else {
                    g.fill(path)
                }
            }
        } finally {
            g.dispose()
        }
        return image
    }
}
