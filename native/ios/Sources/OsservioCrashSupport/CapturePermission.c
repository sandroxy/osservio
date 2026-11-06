#include "OsservioCrashSupport.h"
#include <mach/mach_time.h>
#include <stdatomic.h>
#include <limits.h>

_Static_assert(ATOMIC_LLONG_LOCK_FREE == 2, "Crash admission requires lock-free 64-bit atomics");
_Static_assert(ATOMIC_BOOL_LOCK_FREE == 2, "Crash arming requires lock-free atomics");
static _Atomic unsigned long long permission = 0;
static _Atomic bool armed = false;
static const uint64_t baselineBit = UINT64_C(1) << 63;

void OsservioCrashCaptureUpdate(bool baseline, uint64_t detailedDeadlineUptimeMillis) {
    uint64_t deadline = 0;
    if (detailedDeadlineUptimeMillis > 0) {
        mach_timebase_info_data_t timebase;
        if (mach_timebase_info(&timebase) != KERN_SUCCESS || timebase.numer == 0) {
            atomic_store_explicit(&permission, 0, memory_order_release);
            return;
        }
        // Core's SystemLogClock uses DispatchTime uptime nanoseconds, the same
        // mach_absolute_time domain. Preserve its absolute deadline so delayed
        // normal-execution delivery cannot extend permission.
        __uint128_t end = (__uint128_t)detailedDeadlineUptimeMillis * 1000000 * timebase.denom / timebase.numer;
        deadline = end < baselineBit ? (uint64_t)end : baselineBit - 1;
    }
    atomic_store_explicit(&permission, deadline | (baseline ? baselineBit : 0), memory_order_release);
}

void OsservioCrashCaptureSetArmed(bool value) {
    atomic_store_explicit(&armed, value, memory_order_release);
}

bool OsservioCrashCaptureAllowsRecording(void) {
    if (!atomic_load_explicit(&armed, memory_order_acquire)) return false;
    uint64_t value = atomic_load_explicit(&permission, memory_order_acquire);
    if (value & baselineBit) return true;
    return value != 0 && mach_absolute_time() < value;
}
