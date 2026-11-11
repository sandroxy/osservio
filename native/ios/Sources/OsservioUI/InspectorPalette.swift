import Foundation

/// Presentation-only colors. Semantic status and JSON value colors remain independent.
enum InspectorPalette: String, CaseIterable {
    case purple, blue, teal, green, rose
    var colors: (light: UInt32, dark: UInt32, lightTint: UInt32, darkTint: UInt32) {
        switch self {
        case .purple: return (0x5146D8, 0xB4ADFF, 0xEEECFF, 0x302C48)
        case .blue: return (0x215EBC, 0x9FC7FF, 0xE9F1FF, 0x203249)
        case .teal: return (0x08766F, 0x7DDDD1, 0xE4F5F1, 0x193C3A)
        case .green: return (0x34723C, 0xA3DFA7, 0xEAF4E8, 0x263C2A)
        case .rose: return (0xA93669, 0xF4A6C8, 0xFCEAF2, 0x452A39)
        }
    }
    var label: String { "ui_palette_" + rawValue }
}
