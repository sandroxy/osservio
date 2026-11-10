#if canImport(UIKit)
import UIKit

/// A compositor-owned clip and fade. No screenshots, display link or persistent full-screen effect.
@MainActor
final class InspectorEntryTransition {
    private var animator: UIViewPropertyAnimator?
    private weak var window: UIWindow?
    private weak var entryWindow: UIWindow?
    private var blocker: UIView?
    private var generation: UInt64 = 0
    var isRunning: Bool { animator != nil }

    func run(window: UIWindow, entryWindow: UIWindow?, origin: CGRect, opening: Bool, animated: Bool) {
        cancel()
        self.window = window; self.entryWindow = entryWindow
        let duration: TimeInterval = !animated ? 0 : InspectorMotion.reduced ? 0.12 : opening ? 0.32 : 0.26
        guard duration > 0 else { window.isHidden = !opening; return }
        window.layoutIfNeeded()
        let blocker = UIView(frame: window.bounds); blocker.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        blocker.isAccessibilityElement = false; blocker.accessibilityViewIsModal = true
        window.addSubview(blocker); self.blocker = blocker
        if !InspectorMotion.reduced {
            let mask = CAShapeLayer(); mask.fillColor = UIColor.black.cgColor
            let small = UIBezierPath(roundedRect: origin, cornerRadius: min(origin.width, origin.height) / 2).cgPath
            // A nonzero final radius preserves path topology during interpolation.
            let large = UIBezierPath(roundedRect: window.bounds, cornerRadius: 0.5).cgPath
            let path = CABasicAnimation(keyPath: "path")
            path.fromValue = opening ? small : large; path.toValue = opening ? large : small
            path.duration = duration; path.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
            mask.path = opening ? large : small; window.layer.mask = mask
            mask.add(path, forKey: "Osservio.EntryReveal")
        }
        window.alpha = opening ? 0.12 : 1
        if !opening { entryWindow?.alpha = 0 }
        let ticket = generation
        let animation = UIViewPropertyAnimator(duration: duration, curve: .easeInOut) { window.alpha = opening ? 1 : 0 }
        if !opening { animation.addAnimations({ entryWindow?.alpha = 1 }, delayFactor: 0.6) }
        animation.addCompletion { [weak self, weak window, weak entryWindow] _ in
            guard let self, self.generation == ticket else { return }
            window?.isHidden = !opening
            window?.layer.mask = nil; window?.alpha = 1; entryWindow?.alpha = 1
            self.blocker?.removeFromSuperview(); self.blocker = nil; self.animator = nil
        }
        animator = animation; animation.startAnimation()
    }
    func cancel() {
        generation &+= 1
        animator?.stopAnimation(true); animator = nil
        window?.layer.mask?.removeAllAnimations(); window?.layer.mask = nil; window?.alpha = 1
        entryWindow?.alpha = 1
        blocker?.removeFromSuperview(); blocker = nil
        window = nil; entryWindow = nil
    }
}

/// Window geometry can change independently of the selected page or presented editor.
@MainActor
final class InspectorContentWindow: UIWindow {
    var geometryChanged: (() -> Void)?
    private var previousBounds = CGRect.zero
    override func layoutSubviews() {
        super.layoutSubviews()
        if previousBounds != bounds { previousBounds = bounds; geometryChanged?() }
    }
}

@MainActor
enum InspectorGlyphs {
    /// Original diagnostic-panel mark, distinct from the debug toggle's bug.
    static let entry: UIImage = UIGraphicsImageRenderer(size: CGSize(width: 24, height: 24)).image { _ in
        UIColor.black.setStroke()
        let outline = UIBezierPath(roundedRect: CGRect(x: 2, y: 4, width: 20, height: 16), cornerRadius: 3)
        outline.lineWidth = 1.7; outline.stroke()
        let wave = UIBezierPath(); wave.lineWidth = 1.7; wave.lineCapStyle = .round; wave.lineJoinStyle = .round
        wave.move(to: CGPoint(x: 5, y: 13))
        for point in [CGPoint(x: 7, y: 13), CGPoint(x: 9, y: 8), CGPoint(x: 12, y: 17),
                      CGPoint(x: 15, y: 10), CGPoint(x: 17, y: 13), CGPoint(x: 19, y: 13)] { wave.addLine(to: point) }
        wave.stroke()
    }.withRenderingMode(.alwaysTemplate)
}
#endif
