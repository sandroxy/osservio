# Keep API-33 rendering types out of classes loaded on older Android versions.
# Obfuscation and removal of unreachable code are allowed; cross-boundary optimization is not.
-keep,allowshrinking,allowobfuscation class com.sandrox.osservio.ui.InspectorGlassRenderer { *; }
-keep,allowshrinking,allowobfuscation class com.sandrox.osservio.ui.InspectorGlassThermal { *; }
