package com.sandrox.osservio.ui;

/** Presentation-only tokens, independent of semantic status and JSON value colors. */
enum InspectorPalette {
    PURPLE("purple", R.string.osservio_ui_palette_purple, R.style.OsservioPalettePurple, 0xFF5146D8, 0xFFB4ADFF, 0xFFEEECFF, 0xFF302C48),
    BLUE("blue", R.string.osservio_ui_palette_blue, R.style.OsservioPaletteBlue, 0xFF215EBC, 0xFF9FC7FF, 0xFFE9F1FF, 0xFF203249),
    TEAL("teal", R.string.osservio_ui_palette_teal, R.style.OsservioPaletteTeal, 0xFF08766F, 0xFF7DDDD1, 0xFFE4F5F1, 0xFF193C3A),
    GREEN("green", R.string.osservio_ui_palette_green, R.style.OsservioPaletteGreen, 0xFF34723C, 0xFFA3DFA7, 0xFFEAF4E8, 0xFF263C2A),
    ROSE("rose", R.string.osservio_ui_palette_rose, R.style.OsservioPaletteRose, 0xFFA93669, 0xFFF4A6C8, 0xFFFCEAF2, 0xFF452A39);
    final String key;
    final int label, style, light, dark, lightTint, darkTint;
    InspectorPalette(String key, int label, int style, int light, int dark, int lightTint, int darkTint) {
        this.key = key; this.label = label; this.style = style; this.light = light; this.dark = dark;
        this.lightTint = lightTint; this.darkTint = darkTint;
    }
    static InspectorPalette from(String key) {
        for (InspectorPalette palette : values()) if (palette.key.equals(key)) return palette;
        return PURPLE;
    }
}
