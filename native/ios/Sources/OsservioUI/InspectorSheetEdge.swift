#if canImport(UIKit)
import UIKit

/// Static edge light. The sheet keeps its opaque body and native input owners.
@MainActor
final class InspectorSheetEdge: UIView {
    static let height: CGFloat = 44
    private var color: UIColor?
    private var wash: CGGradient?, corners: CGGradient?, rim: CGGradient?
    var cornerRadius: CGFloat = 28 { didSet { if oldValue != cornerRadius { setNeedsDisplay() } } }

    init() {
        super.init(frame: .zero)
        isOpaque = false; isUserInteractionEnabled = false; accessibilityElementsHidden = true
        contentMode = .redraw; backgroundColor = InspectorTheme.canvas
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }

    func update(appearance: UIUserInterfaceStyle) {
        isHidden = appearance != .dark
        guard !isHidden else { return }
        let accent = InspectorTheme.accent.resolvedColor(with: UITraitCollection(userInterfaceStyle: appearance))
        guard color != accent else { return }
        color = accent
        func gradient(_ base: UIColor, _ alphas: [CGFloat], _ stops: [CGFloat]) -> CGGradient? {
            CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(),
                       colors: alphas.map { base.withAlphaComponent($0 / 255).cgColor } as CFArray, locations: stops)
        }
        wash = gradient(accent, [76, 52, 18, 0], [0, 0.14, 0.48, 1])
        corners = gradient(accent, [52, 14, 0], [0, 0.5, 1])
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        accent.getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        let highlight = UIColor(red: (red + 1) / 2, green: (green + 1) / 2, blue: (blue + 1) / 2, alpha: 1)
        rim = gradient(highlight, [150, 44, 0], [0, 0.4, 1])
        setNeedsDisplay()
    }

    override func draw(_ rect: CGRect) {
        guard !isHidden, let context = UIGraphicsGetCurrentContext(), !bounds.isEmpty,
              let wash, let corners, let rim else { return }
        let radius = min(cornerRadius, bounds.width / 2)
        let shape = UIBezierPath(roundedRect: CGRect(x: 0, y: 0, width: bounds.width, height: max(bounds.height, radius * 2)),
                                 byRoundingCorners: [.topLeft, .topRight], cornerRadii: CGSize(width: radius, height: radius))
        context.saveGState(); context.addPath(shape.cgPath); context.clip()
        context.clip(to: CGRect(x: 0, y: 0, width: bounds.width, height: Self.height))
        context.drawLinearGradient(wash, start: .zero, end: CGPoint(x: 0, y: Self.height), options: [])
        for x in [radius, bounds.width - radius] {
            let center = CGPoint(x: x, y: 2)
            context.drawRadialGradient(corners, startCenter: center, startRadius: 0, endCenter: center, endRadius: 48, options: [])
        }
        let edge = UIBezierPath()
        edge.move(to: CGPoint(x: 0.5, y: bounds.height))
        edge.addLine(to: CGPoint(x: 0.5, y: radius))
        edge.addArc(withCenter: CGPoint(x: radius, y: radius), radius: max(0, radius - 0.5), startAngle: .pi, endAngle: .pi * 1.5, clockwise: true)
        edge.addLine(to: CGPoint(x: bounds.width - radius, y: 0.5))
        edge.addArc(withCenter: CGPoint(x: bounds.width - radius, y: radius), radius: max(0, radius - 0.5), startAngle: -.pi / 2, endAngle: 0, clockwise: true)
        edge.addLine(to: CGPoint(x: bounds.width - 0.5, y: bounds.height))
        context.addPath(edge.cgPath); context.setLineWidth(0.65); context.replacePathWithStrokedPath(); context.clip()
        context.drawLinearGradient(rim, start: .zero, end: CGPoint(x: 0, y: Self.height), options: [])
        context.restoreGState()
    }
}
#endif
