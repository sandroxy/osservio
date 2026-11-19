# Liquid Glass optical kernel

The optional Android UI module uses an inward optical kernel for its floating entry from
[Liquid-Glass-Android v2.0.11](https://github.com/QWEA0/Liquid-Glass-Android/tree/fc6bd34fa3290638c3594cc39386f549d1316ab5),
commit `fc6bd34fa3290638c3594cc39386f549d1316ab5`, copyright (c) 2025–2026
pandadog, under MIT. [Original file hashes](source.json) identify the four Kotlin
sources used to trace the shader, material defaults, effective parameter mapping,
and backdrop recording. Osservio distributes derived source, not the upstream AAR.

## Maintained subset and differences

- `InspectorGlassShader` retains the original AGSL equations and uniforms;
  Java string encoding removes comments and whitespace only.
- `InspectorGlassRenderer` retains the upstream inward-lens geometry. The light
  floating entry uses its native surface color at alpha `0xB3`, no absorbing
  glass tint and no saturation boost. Light blur is 1.5 dp, quantized down to
  0.5 px and capped at one third of the 32 px recording margin. Dark appearance
  keeps the effective REGULAR mapping: 6 px blur, white base tint `0x24FFFFFF`,
  saturation 1.4 and no custom glass tint.
  Both have no dimming, dispersion 0.1, specular strength 1.0, falloff 2 and
  light direction (0.866, 0.5). Bevel, refraction cap, sampling bounds and rim
  band retain upstream size/density mapping. The circular radius is half the
  short side. Appearance comes from the host, not the inspector preference.
- Only the single inward surface is retained. There is no outward composite
  node, sensor, press animation, automatic brightness sampling, shape merging,
  software blur, JNI, upstream control wrapper, bitmap cache, or optional widget
  dependency. Adaptive tint remains disabled; the floating entry uses its
  appearance's neutral tint without background readback or a material selector.
- Lifecycle, admission and native component binding are Osservio-owned Java.
  The floating button draws the clipped content before its bug glyph. Its original Drawable remains underneath, including during
  startup and failure. Its antialiased edge therefore composites over the native
  surface. No reflection, class-name matching or view-tree discovery is used.
- Bottom navigation uses the separate [navigation backdrop recipe](../backdrop/README.md).
- API-specific implementations are isolated from API-23 signatures and protected
  from R8 optimization across the boundary. AndroidX annotations are compile-only;
  this subset adds no Kotlin or third-party runtime dependency.

The [MIT license](../../osservio-ui/src/main/resources/META-INF/osservio/liquid-glass-LICENSE.txt)
is included in UI Java resources and must remain in consuming artifacts.
See [material behavior](../../../../doc/inspector-materials.md) for source scope,
budgets, fallback, supported scenes and verification boundaries. Future upstream
updates require renewed equation/parameter comparison and real-scene validation.
