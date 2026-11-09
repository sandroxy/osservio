# Material Symbols

The optional UI module includes nine rounded, unfilled 24 dp Android vectors
from Google's [Material Symbols](https://github.com/google/material-design-icons),
pinned to commit `10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7`.

- `osservio_close.xml`: `symbols/android/close/materialsymbolsrounded/close_24px.xml`
- `osservio_back.xml`: `symbols/android/arrow_back_ios_new/materialsymbolsrounded/arrow_back_ios_new_24px.xml`
- `osservio_minimize.xml`: `symbols/android/close_fullscreen/materialsymbolsrounded/close_fullscreen_24px.xml`
- `osservio_entry.xml`: `symbols/android/center_focus_weak/materialsymbolsrounded/center_focus_weak_24px.xml`
- `osservio_filter.xml`: `symbols/android/tune/materialsymbolsrounded/tune_24px.xml`
- `osservio_pause.xml`: `symbols/android/pause/materialsymbolsrounded/pause_24px.xml`
- `osservio_play.xml`: `symbols/android/play_arrow/materialsymbolsrounded/play_arrow_24px.xml`
- `osservio_more.xml`: `symbols/android/more_horiz/materialsymbolsrounded/more_horiz_24px.xml`
- `osservio_search.xml`: `symbols/android/search/materialsymbolsrounded/search_24px.xml`

Local changes: resource filenames use the SDK namespace; the upstream
`?attr/colorControlNormal` tint was removed so callers can apply the inspector's
scoped colors without an AppCompat theme dependency. Path geometry is unchanged.
Each vector identifies the adaptation. Only these vectors are included; no font,
icon library runtime, network loader or Material Components dependency is added.

The [upstream Apache 2.0 license](../../osservio-ui/src/main/resources/META-INF/osservio/material-symbols-LICENSE.txt)
is packaged in the UI AAR's Java resources. Upstream's repository has no required
NOTICE file for these vectors. Inspect the standalone consumer when updating;
a library-only resource build does not validate theme-attribute linkage.
