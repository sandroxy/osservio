# Liquid Glass optical kernel

The optional Android UI module retains an unbound inward optical kernel from
[Liquid-Glass-Android v2.0.11](https://github.com/QWEA0/Liquid-Glass-Android/tree/fc6bd34fa3290638c3594cc39386f549d1316ab5),
commit `fc6bd34fa3290638c3594cc39386f549d1316ab5`, copyright (c) 2025–2026
pandadog, under MIT. [Original file hashes](source.json) identify the four Kotlin
sources used to trace the shader, material defaults, effective parameter mapping,
and backdrop recording. Osservio distributes derived source, not the upstream AAR.

## Maintained subset and differences

- `InspectorGlassShader` retains the original AGSL equations and uniforms;
  Java string encoding removes comments and whitespace only.
- `InspectorGlassRenderer` maps the upstream effective CLEAR inward-lens values:
  2 px quantized blur, 32 px recording margin, white base tint `0x14FFFFFF`,
  dimming 0.16, saturation 1.4, dispersion 0.1, specular strength 1.2, falloff 2,
  light direction (0.866, 0.5). Bevel, refraction cap, sampling bounds and rim band
  retain upstream size/density mapping. The navigation radius is 32 dp, capped
  at half the short side. Light mode adds no glass tint; dark mode adds canvas
  color at alpha `0x14`.
- Only the single inward surface is retained. There is no outward composite
  node, sensor, press animation, automatic brightness sampling, shape merging,
  software blur, JNI, upstream control wrapper, bitmap cache, or optional widget
  dependency. REGULAR is not a selectable material in this integration.
- Lifecycle, admission and native component binding are Osservio-owned Java.
  The existing navigation control draws the optical background before its
  original children. Its original Drawable remains underneath, including during
  startup and failure. Its antialiased edge therefore composites over the native
  surface. No reflection, class-name matching or view-tree discovery is used.
- No production scene binds this kernel. The active bottom navigation uses the
  separate [navigation backdrop recipe](../backdrop/README.md). No additional
  foreground reading mask is drawn by this retained surface implementation.
- API-specific implementations are isolated from API-23 signatures and protected
  from R8 optimization across the boundary. AndroidX annotations are compile-only;
  this subset adds no Kotlin or third-party runtime dependency.

The [MIT license](../../osservio-ui/src/main/resources/META-INF/osservio/liquid-glass-LICENSE.txt)
is included in UI Java resources and must remain in consuming artifacts.
See [material behavior](../../../../doc/inspector-materials.md) for source scope,
budgets, fallback, supported scenes and verification boundaries. Future upstream
updates require renewed equation/parameter comparison and real-scene validation.
