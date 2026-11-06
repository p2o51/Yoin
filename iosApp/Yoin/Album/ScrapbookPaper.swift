import SwiftUI

// The scrapbook's paper pieces (Android AlbumScrapbook.kt), shared by the iOS and Mac scrapbooks.

/// Card-cornered (16) ticket with 7 pt half-circle notches top and bottom at
/// the stub's edge (x = 48).
struct TicketShape: Shape {
    func path(in r: CGRect) -> Path {
        let card = Path(roundedRect: r, cornerRadius: Tokens.shape.CARD.cg, style: .continuous)
        let x = r.minX + Tokens.detail.SCRAPBOOK_TICKET_STUB.cg, n = Tokens.detail.SCRAPBOOK_TICKET_NOTCH.cg
        var notches = Path()
        notches.addEllipse(in: CGRect(x: x - n, y: r.minY - n, width: 2 * n, height: 2 * n))
        notches.addEllipse(in: CGRect(x: x - n, y: r.maxY - n, width: 2 * n, height: 2 * n))
        return card.subtracting(notches)
    }
}

/// Sticky note: 16 pt corners, bottom-right corner cut from (w−17, h) to
/// (w, h−17), with the folded flap drawn in the flap color.
struct StickyNote: View {
    var fill: Color
    var flap: Color
    var body: some View {
        GeometryReader { g in
            let w = g.size.width, h = g.size.height, c = Tokens.shape.CARD.cg
            ZStack {
                Path { p in
                    p.move(to: CGPoint(x: c, y: 0))
                    p.addLine(to: CGPoint(x: w - c, y: 0))
                    p.addQuadCurve(to: CGPoint(x: w, y: c), control: CGPoint(x: w, y: 0))
                    p.addLine(to: CGPoint(x: w, y: h - 17))
                    p.addLine(to: CGPoint(x: w - 17, y: h))
                    p.addLine(to: CGPoint(x: c, y: h))
                    p.addQuadCurve(to: CGPoint(x: 0, y: h - c), control: CGPoint(x: 0, y: h))
                    p.addLine(to: CGPoint(x: 0, y: c))
                    p.addQuadCurve(to: CGPoint(x: c, y: 0), control: .zero)
                }
                .fill(fill)
                Path { p in
                    p.move(to: CGPoint(x: w - 17, y: h))
                    p.addLine(to: CGPoint(x: w - 17, y: h - 13))
                    p.addQuadCurve(to: CGPoint(x: w - 13, y: h - 17), control: CGPoint(x: w - 17, y: h - 17))
                    p.addLine(to: CGPoint(x: w, y: h - 17))
                    p.closeSubpath()
                }
                .fill(flap)
            }
        }
    }
}

/// Receipt: 20 pt top corners, zig-zag bottom (≈14 pt teeth, 8 pt deep).
struct ReceiptShape: Shape {
    func path(in r: CGRect) -> Path {
        var p = Path()
        let teeth = max(1, Int((r.width / 14).rounded()))
        let tw = r.width / CGFloat(teeth)
        // Circular 20 pt arcs, as Compose's arcTo.
        let c = min(20, min(r.width, r.height) / 2)
        p.move(to: CGPoint(x: r.minX, y: r.minY + c))
        p.addArc(center: CGPoint(x: r.minX + c, y: r.minY + c), radius: c,
                 startAngle: .degrees(180), endAngle: .degrees(270), clockwise: false)
        p.addLine(to: CGPoint(x: r.maxX - c, y: r.minY))
        p.addArc(center: CGPoint(x: r.maxX - c, y: r.minY + c), radius: c,
                 startAngle: .degrees(270), endAngle: .degrees(0), clockwise: false)
        p.addLine(to: CGPoint(x: r.maxX, y: r.maxY - 8))
        for i in (0..<teeth).reversed() {
            let x0 = r.minX + CGFloat(i) * tw
            p.addLine(to: CGPoint(x: x0 + tw / 2, y: r.maxY))
            p.addLine(to: CGPoint(x: x0, y: r.maxY - 8))
        }
        p.closeSubpath()
        return p
    }
}

/// 16 pt tiles with a 1.2 pt dot at the centre.
struct DotPaper: View {
    var color: Color
    var body: some View {
        let pitch = Tokens.detail.SCRAPBOOK_DOT_SPACING.cg, dot = Tokens.detail.SCRAPBOOK_DOT_RADIUS.cg
        Canvas { gc, size in
            var y: CGFloat = pitch / 2
            while y < size.height {
                var x: CGFloat = pitch / 2
                while x < size.width + pitch {
                    gc.fill(Path(ellipseIn: CGRect(x: x - dot, y: y - dot, width: 2 * dot, height: 2 * dot)), with: .color(color))
                    x += pitch
                }
                y += pitch
            }
        }
        .padding(.horizontal, -16)
    }
}
