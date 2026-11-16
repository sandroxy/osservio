# Inspector materials

Materials distinguish navigation from diagnostic content. They do not change
record retention, layout, committed selection or reading order. Both platforms
keep content cards, long readers, forms, filter sheets and destructive
confirmations focused on readable text. Neither platform requires all surfaces
to be transparent.

## Scene ownership

| Scene | Android | iOS |
| --- | --- | --- |
| Bottom navigation | Bounded GPU backdrop and movable lens when admitted; native fallback | System tab material on iOS 26 |
| Top actions | Existing native controls on the page header | System bar controls; existing header treatment |
| View latest | Existing readable floating pill | Existing readable floating pill |
| Short menus and confirmations | Existing native surfaces | System surfaces; no added optical layer |
| Filters and problem composer | Opaque content, top rounded corners | Opaque content in the system sheet |
| Details, JSON, cards, inputs | Opaque reading surfaces | Opaque reading surfaces |
| Switches and segments | Existing native controls | System controls |
| Floating entry | Existing native surface; no host capture | System glass where supported, native fallback otherwise |

Android enables only bottom-navigation enhancement by default, subject to device
admission. The host can opt out with `InspectorView.setEnhancedMaterialsEnabled(false)`.
Setting it to `false` restores the native navigation background and releases
material resources and listeners. The choice belongs to each viewer instance.
Setting it back to `true` reassesses admission without clearing a failure lock.
iOS independently uses its system navigation material by default where supported;
the host may disable it through
`InspectorViewController.enhancedMaterialsEnabled = false`. These main-thread
settings do not disable platform-owned control effects or alter capture.

## Android rendering and admission

Only the inspector's own navigation content is sampled. Source and tab surface
are siblings in the same window; the material never samples itself, the host
DecorView, a WebView or a SurfaceView. Source invalidation, scroll and geometry
changes update the recording. Geometry checks run on existing pre-draw events;
they do not schedule a continuous animation. Shader compilation occurs once per
active renderer, and optical configuration changes only when its inputs change.
The source RenderNode has a compositing layer, so background glyphs rasterize at
stable scale before optical transforms. Content changes still re-record this
source; it is not a frozen screenshot. The fixed vibrancy/blur chain is reused
while only refraction inputs change. No host View layer or software image capture
is forced. Detachment clears effects, compositing layers and display lists.
Platform glyph/texture caches can outlive these resources and remain part of
process memory; the SDK does not force global cache eviction or garbage collection.

The [navigation recipe](../native/android/vendor/backdrop/README.md) composes a
base, colored native labels and a moving lens. A tap selects its destination
directly. Holding the selected lens previews other tabs while the current page
remains visible; releasing commits once. Cancel, loss of focus, hiding or fallback
does not commit a preview. The native buttons remain the sole click, focus and
accessibility owners. A settling animation does not switch intermediate pages.

Android minSdk remains 23. API 33+, a hardware canvas, a valid laid-out source,
normal memory admission and device policy are required for the GPU path.
RuntimeShader and RenderNode types stay in an API-isolated implementation;
consumer rules prevent R8 from moving it into old-system entry points.

Recording includes a 32 px source margin and the enlarged control's overflow.
Limits are 786,432 logical pixels per recording node, 3,145,728 summed per viewer,
and 4,096 px per recording edge. The navigation reservation includes source,
colored-label recording, and the three layers' images, outer/inner shadows and
highlights before renderer creation. These limits are not GPU-memory byte limits,
frame-rate promises or authorization to render large panels. Intermediate
effects, native-label tint layers, highlight blending and platform caches consume
additional memory. Higher density, large fonts or a wider window may exceed the
budget and retain native navigation; the cap is not automatically raised.
An oversized surface
retains its native background without disabling other admitted surfaces.

| Condition | Behavior |
| --- | --- |
| Old API, software canvas, invalid source/window, sampling cycle, budget excess | Native Drawable; temporary readiness can recover |
| Power saving, high text contrast or animator scale zero | Native Drawable; recover when policy permits |
| Severe or higher thermal state | Native Drawable; recover at light/normal; moderate preserves restriction |
| Low-memory admission or memory pressure | Release and lock this viewer to native material |
| Shader, allocation, recording, drawing or linkage failure | Release and lock this viewer to native material |
| Hidden, minimized, detached or disposed | Release rendering resources, reservations and listeners |

A new viewer can retry after a locked failure. Catch boundaries handle recoverable
in-process failures; they cannot recover a GPU driver process crash, system kill
or unrecoverable process OOM. Android 34 no longer delivers several RUNNING memory
callbacks, so entry admission, bounded allocation and timely release are primary
protections, rather than reliance on a low-memory callback.

The original Drawable, padding and native controls remain available for fallback.
The optical shadow replaces native elevation only after successful rendering;
fallback restores the native elevation.
Native controls retain their hit regions and accessibility nodes. Accessibility
and thermal listeners exist only for enabled, eligible, attached visible surfaces.
Covering sheets and confirmations
release the navigation renderer until that navigation becomes usable again.

## iOS rendering

On iOS 26, the tab bar uses its system default material without stacking a custom
blur. Older systems keep the existing opaque tab background. Reduce Transparency
or Increase Contrast selects the opaque navigation surface and solid floating
entry; changes are observed live. Top actions, switches, alerts and sheets remain
owned by UIKit. System controls may retain native effects independently of the
optional navigation setting.

## Validation boundaries

Tests cover resource admission, fallback and failure lifetime, material selection,
and unchanged navigation. Consumer checks must separately cover first display
without scrolling, static content, scrolling, dynamic records, theme changes,
keyboard, larger text, sheets and collapse/restore. Builds and injected policy
tests do not establish real-device GPU compatibility, physical thermal signals,
energy use or prolonged memory stability. Device coverage belongs to each
consumer's verification record. No specific GPU family, refresh rate or device
resolution is guaranteed by an API-level check alone.

References: [RuntimeShader](https://developer.android.com/reference/android/graphics/RuntimeShader),
[ComponentCallbacks2](https://developer.android.com/reference/android/content/ComponentCallbacks2),
[UIKit design](https://developer.apple.com/videos/play/wwdc2025/284/).
