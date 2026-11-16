# Inspector materials

Materials distinguish navigation from diagnostic content. They do not change
record retention, layout, selection, gestures or reading order. Both platforms
keep content cards, long readers, forms, filter sheets and destructive
confirmations focused on readable text. Neither platform requires all surfaces
to be transparent.

## Scene ownership

| Scene | Android | iOS |
| --- | --- | --- |
| Bottom navigation | Bounded GPU lens on eligible hardware | System tab material on iOS 26 |
| Top actions | Existing native controls on the page header | System bar controls; existing header treatment |
| View latest | Existing readable floating pill | Existing readable floating pill |
| Short menus and confirmations | Existing native surfaces | System surfaces; no added optical layer |
| Filters and problem composer | Opaque content, top rounded corners | Opaque content in the system sheet |
| Details, JSON, cards, inputs | Opaque reading surfaces | Opaque reading surfaces |
| Switches and segments | Existing native controls | System controls |
| Floating entry | Existing native surface; no host capture | System glass where supported, native fallback otherwise |

The host may disable the optional navigation material through Android
`InspectorView.setEnhancedMaterialsEnabled(false)` or iOS
`InspectorViewController.enhancedMaterialsEnabled = false`. These main-thread
settings do not disable platform-owned control effects or alter capture. They
also provide a consistent baseline for consumer comparisons.

## Android rendering and admission

Only the inspector's own navigation content is sampled. Source and tab surface
are siblings in the same window; the material never samples itself, the host
DecorView, a WebView or a SurfaceView. Source invalidation, scroll and geometry
changes update the recording. Geometry checks run on existing pre-draw events;
they do not schedule a continuous animation. Shader compilation occurs once per
active renderer, and effect configuration changes only with size, density,
radius or tint. No forced hardware layers or software image capture are used.

Android minSdk remains 23. API 33+, a hardware canvas, a valid laid-out source,
normal memory admission and device policy are required for the GPU path.
RuntimeShader and RenderNode types stay in an API-isolated implementation;
consumer rules prevent R8 from moving it into old-system entry points.

Recording includes a 32 px margin on each side. Limits are 786,432 recorded pixels
per surface, 1,048,576 per viewer, and 4,096 px per recording edge. Admission
precedes GPU allocation. These small-surface limits are not GPU-memory byte
limits, frame-rate promises or authorization to render large panels. Intermediate
effects and platform caches consume additional memory. An oversized surface
retains its native background without disabling other admitted surfaces.

| Condition | Behavior |
| --- | --- |
| Old API, software canvas, invalid source/window, sampling cycle, budget excess | Native Drawable; temporary readiness can recover |
| Power saving or high text contrast | Native Drawable; recover when policy permits |
| Severe or higher thermal state | Native Drawable; recover at light/normal; moderate preserves restriction |
| Low-memory admission or memory pressure | Release and lock this viewer to native material |
| Shader, allocation, recording, drawing or linkage failure | Release and lock this viewer to native material |
| Hidden, minimized, detached or disposed | Release rendering resources, reservations and listeners |

A new viewer can retry after a locked failure. Catch boundaries handle recoverable
in-process failures; they cannot recover a GPU driver process crash, system kill
or unrecoverable process OOM. Android 34 no longer delivers several RUNNING memory
callbacks, so entry admission, bounded allocation and timely release are primary
protections, rather than reliance on a low-memory callback.

The original Drawable, padding, outline and elevation remain in place. Foreground
native controls retain their hit regions and accessibility nodes. Accessibility
and thermal listeners exist only for eligible, attached visible surfaces.
The navigation interior has a theme-colored reading mask (alpha `0xF0` through
the middle 64%, fading to transparent at the top/bottom, 1 dp shape inset);
its optical edge remains visible. Covering sheets and confirmations
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
