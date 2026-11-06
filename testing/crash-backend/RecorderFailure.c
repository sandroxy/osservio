// Test executable only: force a genuine partial sigaction installation failure.
// This interposer is never compiled into the SDK or consumer applications.
#include <errno.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdbool.h>

static atomic_bool enabled = false;
static atomic_int registrations = 0;

void OsservioProbeFailSecondHandler(void) { atomic_store(&enabled, true); }

static int reject_second_handler(int number, const struct sigaction *action, struct sigaction *previous) {
    if (atomic_load(&enabled) && action != NULL && (action->sa_flags & SA_SIGINFO) != 0 &&
        atomic_fetch_add(&registrations, 1) == 1) {
        errno = EPERM;
        return -1;
    }
    return sigaction(number, action, previous);
}

__attribute__((used)) static const struct { const void *replacement; const void *original; }
    interpose __attribute__((section("__DATA,__interpose"))) = {
        (const void *)&reject_second_handler, (const void *)&sigaction
    };
