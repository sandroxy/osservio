#include "OsservioCrashSupport.h"
#include <stdatomic.h>

_Static_assert(ATOMIC_INT_LOCK_FREE == 2, "Image admission requires lock-free atomics");
static atomic_uint admitted = 0;
static atomic_uint flags = 0;

bool OsservioCrashAdmitImage(uint32_t loadCommandBytes, uint32_t loadCommandCount) {
    if (loadCommandBytes <= 65536 && loadCommandCount <= 4096) {
        unsigned current = atomic_load_explicit(&admitted, memory_order_relaxed);
        // Each failed strong CAS observes another admission. The counter never
        // decreases, so contention cannot produce an unbounded retry loop.
        while (current < 1024) {
            if (atomic_compare_exchange_strong_explicit(&admitted, &current, current + 1,
                    memory_order_relaxed, memory_order_relaxed)) return true;
        }
    }
    atomic_store_explicit(&flags, 1, memory_order_relaxed);
    return false;
}

uint8_t OsservioCrashImageFlags(void) {
    return (uint8_t)atomic_load_explicit(&flags, memory_order_relaxed);
}
