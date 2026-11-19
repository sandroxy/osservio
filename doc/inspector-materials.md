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
| Top actions | Bounded local optical surface and press feedback over native buttons | System bar controls; existing header treatment |
| Record problem | Emphasized local optical action | System prominent glass on iOS 26; filled action on older systems |
| View latest | Existing readable floating pill | Existing readable floating pill |
| Short menus and confirmations | Existing native surfaces | System surfaces; no added optical layer |
| Filters and problem composer | Opaque content, top rounded corners; material on header actions | Opaque content in the system sheet; native bar actions |
| Details, JSON, cards, inputs | Opaque reading surfaces | Opaque reading surfaces |
| Switches and segments | Native switches; shared moving lens over native segmented choices | System controls, with native segmented material |
| Floating entry | Bounded frosted circular lens over ordinary Activity content, native fallback otherwise | System Regular glass where supported, native fallback otherwise |

The floating entry follows the host's effective light/dark appearance, independently
of the inspector's appearance preference. Its palette and language remain inspector
settings. Android reads the Activity theme and configuration; iOS inherits the
host root view's traits through a noninteractive observer, including explicit
window/controller overrides. Neither implementation changes the host's appearance
or infers it from sampled pixels.

Android enables bottom navigation, segmented choices, designated action controls
and the floating entry when device policy permits.
The host can opt out of the floating material with
`InspectorSession.setFloatingEntryMaterialEnabled(false)` and of inspector control materials with `InspectorView.setEnhancedMaterialsEnabled(false)`.
Setting it to `false` restores native control backgrounds and releases
material resources and listeners. The choice belongs to each viewer instance.
Setting it back to `true` reassesses admission without clearing a failure lock.
iOS independently uses its system navigation material by default where supported;
the host may disable it through
`InspectorViewController.enhancedMaterialsEnabled = false`. These main-thread
settings do not disable platform-owned control effects or alter capture.

## Android rendering and admission

The bottom bar samples the inspector’s own navigation content. Source and tab surface
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

The floating entry uses the [inward lens](../native/android/vendor/liquid-glass/README.md)
with a neutral body and modest blur in light appearance. Dark appearance uses
the upstream regular mapping without a custom glass tint. Neither appearance
adds a glyph outline. These Android material recipes do not reproduce Apple's
system Regular effect.
Its 48 dp button sits beside the Activity content hierarchy. The GPU recording
covers the button and its sampling margin inside the same window's
`android.R.id.content`. The original button fill stays beneath the lens, with
the bug and native press feedback above it. The window's solid background color
or the themed button fill supplies a base for transparent content. The entry
uses the native button on secure windows, outside the visible content, or over a
WebView, SurfaceView, TextureView or software layer. The eligibility check
visits intersecting branches with a fixed work limit. Host invalidation, dragging
and docking update the source coordinates on existing frames. Pausing the
Activity, opening the viewer, hiding the entry or disposing the session releases
its material. Floating-entry and navigation opt-outs and renderer failures are
independent.

The [navigation recipe](../native/android/vendor/backdrop/README.md) composes a
base, colored native labels and a moving lens. A tap selects its destination
directly. Holding the selected lens previews other tabs while the current page
remains visible; releasing commits the nearest tab once, including when the
finger drifts above or below the bar. Cancel, loss of focus, hiding or fallback
does not commit a preview. The native buttons remain the sole click, focus and
accessibility owners. A settling animation does not switch intermediate pages.
Analytic springs advance using elapsed frame time, so missed frames do not
artificially prolong a selection transition. They stop when settled or suspended.

Android minSdk remains 23. API 33+, a hardware canvas, a valid laid-out source,
normal memory admission and device policy are required for the GPU path.
RuntimeShader and RenderNode types stay in an API-isolated implementation;
consumer rules prevent R8 from moving it into old-system entry points.

Recording includes a 32 px source margin and the enlarged control's overflow.
Limits are 786,432 logical pixels per recording node, 4,718,592 summed per viewer, with a 3,145,728 limit per selection control,
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
The bottom bar switches to its optical shadow after successful rendering. The floating
entry keeps its native elevation around the circular lens.
Native controls retain their hit regions and accessibility nodes. Accessibility
and thermal listeners exist only for enabled, eligible, attached visible surfaces.
Covering sheets and confirmations
release the navigation renderer until that navigation becomes usable again.

## Actions and segmented choices

Android's native icon buttons, sheet header actions and inline problem action
retain their own input, enabled state, focus and accessibility behavior. The
material does not receive touch events. It uses the existing inward-lens program
over the known opaque inspector backing or emphasized theme color, with no host
or page capture. A bounded press spring transforms drawing only, leaving layout
and hit regions unchanged. Each surface records only its small geometry and
reuses that recording until size or theme changes. Keyboard-focused controls
retain their native focus background. Disabled, detached or covered controls
release rendering resources; shared policy changes also reach attached controls
that previously opted out. Detached candidates are weakly held.

Within an inspector, network scope, HTTP/WebSocket detail sections,
structured/source readers and runtime sections share the navigation lens and
motion implementation. Their
opaque backing does not need the bottom bar's two full-width optical chains;
the moving lens still samples the separately colored native labels with the
same refraction, dispersion and spring recipe. Lens expansion respects the actual
drawing viewport in clipped reader headers without changing text-scroll clipping.
The original RadioButtons own
selection, keyboard and accessibility. A horizontal drag from the selected lens
previews a destination; release commits the nearest choice once. Vertical motion
remains available to the containing list. Cancellation, interception, focus loss
or fallback restores the committed choice. Filter changes remain drafts until
Apply, and scope-row reuse preserves settling motion without rebuilding during
preview. Long labels and large fonts use full-width native choices instead of
truncation. iOS uses UISegmentedControl and the corresponding full-width fallback.

The combined viewer budget is 4,718,592 logical pixels, accommodating a bottom
bar, one inline selector and small actions. Each selection surface retains the
previous 3,145,728 pixel limit and each recording remains limited to 786,432
pixels and 4,096 px per edge. Admission includes simultaneous surfaces before
allocation. These are recording-area limits, not native heap or GPU byte limits;
native shadows and intermediate effects have additional costs. Floating-entry
admission and its separate session remain independent.

## iOS rendering

On iOS 26, the tab bar uses its system default material without stacking a custom
blur. Older systems keep the existing opaque tab background. Reduce Transparency
or Increase Contrast selects the opaque navigation surface and solid floating
entry; changes are observed live. Top actions, switches, alerts and sheets remain
owned by UIKit. System controls may retain native effects independently of the
optional navigation setting. The floating entry uses unmodified system Regular
glass on iOS 26 and system material blur on older systems. Its symbol belongs to
the effect view's `contentView`; no custom glass tint, outline or dark optical parameters
are added.
The inline problem action uses the system prominent glass button configuration,
with a filled fallback and the same label, icon and target. Its tint follows the
inspector palette. Segmented controls preserve UIKit's interactive material and
refresh their title colors without emitting a selection action. Internal
controls follow the inspector appearance; they do not inherit the floating
entry's host-appearance ownership.

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
