#include "OsservioCounters.h"
#include <stdatomic.h>
#include <stdlib.h>

// Apple arm64/x86_64 counters must never fall back to a library lock.
_Static_assert(ATOMIC_LLONG_LOCK_FREE == 2 && ATOMIC_BOOL_LOCK_FREE == 2, "Lock-free counters required");
struct OsservioCounters { atomic_llong values[64]; atomic_bool incomplete; };

OsservioCounters *OsservioCountersCreate(void) {
    OsservioCounters *result = calloc(1, sizeof(*result));
    if (result != NULL) {
        for (unsigned int i = 0; i < 64; i++) atomic_init(&result->values[i], 0);
        atomic_init(&result->incomplete, false);
    }
    return result;
}
void OsservioCountersDestroy(OsservioCounters *counters) { free(counters); }
void OsservioCountersIncrement(OsservioCounters *counters, uint32_t index) {
    if (counters == NULL || index >= 64) return;
    long long current = atomic_load_explicit(&counters->values[index], memory_order_relaxed);
    // Accounting must not spin indefinitely when capture threads contend.
    for (unsigned int attempt = 0; attempt < 4; attempt++) {
        if (current == INT64_MAX) { atomic_store_explicit(&counters->incomplete, true, memory_order_relaxed); return; }
        if (atomic_compare_exchange_weak_explicit(&counters->values[index], &current, current + 1,
                memory_order_relaxed, memory_order_relaxed)) return;
    }
    atomic_store_explicit(&counters->incomplete, true, memory_order_relaxed);
}
int64_t OsservioCountersRead(const OsservioCounters *counters, uint32_t index) {
    return counters == NULL || index >= 64 ? 0 : atomic_load_explicit(&counters->values[index], memory_order_relaxed);
}
bool OsservioCountersIncomplete(const OsservioCounters *counters) {
    return counters == NULL || atomic_load_explicit(&counters->incomplete, memory_order_relaxed);
}
