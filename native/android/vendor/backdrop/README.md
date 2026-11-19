# Navigation backdrop sources

The Android UI navigation material derives its optical programs, three-layer
recipe and motion parameters from
[Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass/tree/eb4a0ea2e17542ea28c2e71088f7dd9acf77aad5),
commit `eb4a0ea2e17542ea28c2e71088f7dd9acf77aad5`, copyright 2025 Kyant,
under Apache 2.0. [Source fingerprints](source.json) identify the upstream inputs
and the whitespace-normalized AGSL programs. License and modification notices
are in `osservio-ui/src/main/resources/META-INF/osservio/` and travel in the AAR.

## Maintained implementation

`InspectorTabShaders` preserves the four original optical programs: inward
refraction, seven-sample dispersion, edge highlight and interactive light.
`InspectorTabRenderer` ports the recipe to Java, RenderNodes and RuntimeShader.
It uses three layers: a page backdrop with ordinary native labels, a separately
colored label backdrop, and the moving lens sampling both. For bottom navigation, its source is the
inspector page, never the host window or the navigation itself.
`InspectorSelectionMaterial` also binds this lens to native segmented choices.
Those controls have a known opaque backing, so they record native labels without
allocating redundant full-width base/label optical and shadow chains. The moving
lens keeps the same shader, parameters and motion. Scroll-direction arbitration
belongs to that binding; filter drafts still commit through Apply.

| Parameter | Value |
| --- | --- |
| Base blur and refraction band/amount | 8 dp, 24/24 dp |
| Colored label backdrop | 8 dp blur; band/amount up to 24/24 dp with press |
| Lens | No extra blur; band/amount up to 10/14 dp; seven-sample dispersion |
| Base tint | `#FAFAFA` or `#121212`, alpha 102/255 |
| Colored label scale | 1 to 1.2 with press |
| Interactive light | White PLUS at 8% overall and 15% local, driven by its spring |
| Outer shadow | 24 dp blur, 4 dp downshift, black at 10% |
| Lens inner shadow | Up to 8 dp blur/downshift, black at 15% with press |
| Base press scale | `1 + 16 dp / width` |
| Lens press scale | `(height in dp + 14) / (height in dp - 8)` |

The original vibrancy matrix, effect ordering, inverse sampling transforms,
rounded capsule geometry and restricted panel displacement are retained.
The interactive shader receives opaque white; Paint alpha premultiplies the
additive light. A translucent white uniform would incorrectly overexpose dark
content. Blur effects are reused until their radius changes. Source and colored
label recordings are reused while their relevant inputs remain unchanged.
The page source is rasterized into its existing bounded RenderNode at stable scale
before the optical transforms, avoiding scale-dependent background glyph caches.
Dynamic content invalidates this source normally; no frame is frozen. The lens's
final scaled outline is constrained to the visible window, including in RTL,
without changing spring or optical constants.

`InspectorTabMotion` uses mass-one analytic springs: position and press
damping/stiffness 1/1000; horizontal scale .6/250; vertical scale .7/250;
velocity deformation .5/300; panel return 1/300; interactive light .5/300.
Thresholds are .001 except velocity (.01) and panel pixels (.5). The velocity
input is the position spring's analytic velocity divided by the number of
intervals, not Compose's historical pointer velocity estimate.

## Native integration differences

- The SDK's existing View buttons own clicks, keyboard focus, selected state and
  accessibility. Their actual icon/TextView content supplies both label layers;
  there are no hand-drawn replacement strings or duplicate invisible buttons.
- Native font layout, compact-window geometry, colors, insets and RTL placement
  follow the current inspector. The source strip retains a 12/14 dp overflow
  around the actual bar, plus the 32 px sampling margin. Pages are not reparented.
- Selection commits only on a native click or successful drag release. Preview
  and committed state are separate; cancel and interruption return to the
  committed page. External selection and rebuilding synchronize the lens.
- The inspector session owns policy, logical recording admission, observers and
  locked fallback. Unsupported hardware keeps the existing native buttons and
  Drawable. No upstream control wrapper, Compose runtime, sensor or bitmap
  capture is included.
- The frame clock runs only while a spring is active, follows the system animator
  scale and stops on hidden/detached surfaces. Zero animation scale selects the
  native fallback. Page updates may still cause redraws when the springs rest.

Changes to shaders, constants, transforms or motion require visual comparison
of light/dark first display, static selection, held lens, slow/reversed drag and
dynamic content, plus resource and consumer checks. The source license does not
imply visual equivalence on all devices or a particular refresh-rate guarantee.
