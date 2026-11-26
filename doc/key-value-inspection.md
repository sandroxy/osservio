# Read-only key-value inspection

App > Key-value storage lists only domains explicitly registered by the host.
Core owns the read contract and owner lifetime; Runtime supplies the optional
`SharedPreferencesKeyValueSource` and `UserDefaultsKeyValueSource` adapters; UI
consumes Core without opening files or importing Runtime. Nothing is registered
by default. There is no sandbox discovery, database browser, RN bridge, runtime
object inspection, mutation tool or change subscription.

The host creates a `KeyValueInspection(owner, registrations)` for one account.
Each registration contains a `Domain` (stable ID, readable name, backend and scope)
and a `KeyValueSource`. Pass it as the last argument to the complete Android
`InspectorView` initializer, or as `keyValueInspection:` to the complete iOS
`InspectorViewController` initializer. Existing initializers remain available.
An unconfigured inspector shows the capability as unavailable.

For Android, supply an **existing, small** host-owned preference file:

```java
KeyValueSource source = new SharedPreferencesKeyValueSource(preferences, "user:42:");
KeyValueInspection storage = new KeyValueInspection(accountOwner,
    Collections.singletonList(new KeyValueInspection.Registration(
        new KeyValueInspection.Domain("settings", "Settings", "SharedPreferences", "Current account"), source)));
```

For iOS, name the exact app-owned persistent domain or suite:

```swift
let source = try UserDefaultsKeyValueSource(domainName: suiteName, keyPrefix: "user:42:")
let storage = try KeyValueInspection(owner: accountOwner, registrations: [
    .init(domain: .init(id: "settings", name: "Settings", backend: "UserDefaults", scope: "Current account"), source: source)
])
```

Prefixes are immutable and checked on both enumeration and direct reads. An empty
prefix explicitly exposes the entire registered domain. Prefixes are not inferred
from login state or key names; the host must choose a scope that actually matches
its ownership model. iOS uses the public CFPreferences exact-domain APIs, without
registered defaults, global-domain fallback or `synchronize`. This shows stored
domain values, which may differ from a business wrapper's effective/default value.
The bundled Runtime privacy manifest declares app-owned UserDefaults access;
hosts must account for their actual domains, including separate App Group usage.

## Reading and resources

Opening a domain reads its key index. Search is literal and confined to key names;
scrolling and searching do not read values. Selecting a key performs a fresh read
and displays its type, source, scope and read time. Refresh is explicit. Available
text can be viewed as raw text or bounded JSON and copied. Empty strings, missing
keys, unsupported values, partial previews and read failures stay distinct.
Stored values may change or disappear between the index and the value read; this
is not an atomic whole-domain snapshot or a guarantee of completed disk flushing.

There is one admitted read per inspection owner, no waiting query backlog, and
no periodic polling. A cancelled read drops delivery and stops cooperative key
enumeration. It cannot interrupt a platform call already in progress. Hidden
value pages cancel work and release their content; reopening performs a new read.
The JSON reader retains its existing parse, search and rendering budgets.

| Bound | Limit |
| --- | --- |
| Registered domains | 32 |
| Retained keys | 2,048 |
| Examined keys per enumeration | 8,192, including keys outside a native prefix |
| Exact UTF-8 bytes per key / domain label | 1,024 |
| Retained key-index UTF-8 bytes | 256 KiB |
| Value text | 64 KiB, scalar-preserving preview for longer strings |
| Collection formatting | 1,024 nodes/elements; iOS nesting at most 16 |

The bounds apply to SDK traversal, copies and presentation. **They are not a hard
ceiling on the native storage API's own allocations.** Android `getAll()` copies
the complete map before scope filtering. iOS obtains the complete native key list
and the selected native value before inspection bounds can apply. These adapters
are intended for small preference stores, not large caches. UI virtualization
does not make native enumeration paginated. Large/custom stores need a host-owned
`KeyValueSource` that can enumerate and read within its own backend limits.
Sources must return incomplete enumeration explicitly and stop when the visitor
returns false. They must never return arbitrarily sized preformatted values.

Android preserves scalar preferences and bounded string sets. iOS preserves
strings, booleans, numbers, dates, bounded data as Base64, and bounded JSON-compatible
arrays/dictionaries. Non-JSON property-list members inside collections are
reported unsupported, rather than replaced by invented text. Oversized collections
are not serialized. Platform acquisition failure is reported separately from absence.

## Ownership and output

Follow the [account transition contract](data-policy.md): call `storage.close()`
before changing accounts and dispose every inspector using it. Construct a new
owner with new immutable source bindings. Closing rejects pending results,
notifies visible storage pages to clear their values, and releases registrations;
it never closes, clears, migrates or deletes the host's store. Cancellation and
close observers are explicitly released by the UI. Native reads already underway
may finish using their original source, but cannot populate the next owner's UI.

Sources must use read-only storage APIs, not business getters that expire entries,
update access times, hydrate state or trigger network requests. Inspection has
CPU/allocation cost even though it makes no business-data writes. Hosts may wrap
the source to restrict keys or transform values before returning them. Ordinary
debug values are readable by default, under the established local trust model.

Values stay in the local reader. They are not recorded into logs, problem
snapshots, recent filters, protocol documents or automatic exports. Clipboard
copy requires an explicit action; a truncated preview is labelled before copy.
Copying does not become proof of persistence. Future framework adapters must
reuse the owner/read contracts and declare backend semantics; native support alone
does not make Zustand, MMKV, SQLite or other JS storage automatically visible.
