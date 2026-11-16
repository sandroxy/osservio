package com.sandrox.osservio.ui;

/** Logical recording sizes for the navigation recipe, computed before renderer creation. */
final class InspectorTabGeometry {
    private InspectorTabGeometry() { }
    static final int SOURCE_MARGIN = 32;
    static final int MAX_EDGE = 4096;

    /** Constrain the scaled outline, rather than its unscaled layout slot, to the visible window. */
    static float constrainLens(float position, float width, float scale, float left, float right) {
        float overflow = width * (scale - 1) / 2;
        float minimum = left + overflow, maximum = right - width - overflow;
        return minimum > maximum ? (left + right - width) / 2 : Math.max(minimum, Math.min(maximum, position));
    }

    static boolean reserve(InspectorGlassBudget budget, Object owner, int width, int height, float density, int count) {
        if (width <= 0 || height <= 0 || Float.isNaN(density) || Float.isInfinite(density) || density <= 0 || count <= 0
            || width > MAX_EDGE || height > MAX_EDGE || height <= 8 * density || width <= 8 * density) return false;
        double inkHeight = height - 8 * density, tabWidth = (width - 8 * density) / count;
        // The strip includes the held control's overflow, then the fixed source margin.
        long sourceWidth = (long) Math.ceil(width + 24 * density) + 2 * SOURCE_MARGIN;
        long sourceHeight = (long) Math.ceil(height + 28 * density) + 2 * SOURCE_MARGIN;
        long shadowMargin = (long) Math.ceil(48 * density);
        long edgeWidth = Math.max(sourceWidth, width + 2 * shadowMargin);
        long edgeHeight = Math.max(sourceHeight, (long) Math.ceil(height + 4 * density) + 2 * shadowMargin);
        if (edgeWidth > MAX_EDGE || edgeHeight > MAX_EDGE) return false;
        long source = sourceWidth * sourceHeight;
        long largest = Math.max(source, shadow(width, height, density));
        long required = source + area(width + 24 * density, height + 28 * density)
            + surface(width, height, density) + surface(width, inkHeight, density) + surface(tabWidth, inkHeight, density);
        return budget.reserve(owner, largest, required, edgeWidth, edgeHeight);
    }
    private static long area(double width, double height) { return (long) Math.ceil(width) * (long) Math.ceil(height); }
    private static long surface(double width, double height, float density) {
        return area(width + 16 * density, height + 16 * density)
            + shadow(width, height, density) + area(width + 2, height + 2) + area(width, height);
    }
    private static long shadow(double width, double height, float density) {
        long margin = (long) Math.ceil(48 * density);
        return ((long) Math.ceil(width) + 2 * margin) * ((long) Math.ceil(height + 4 * density) + 2 * margin);
    }
}
