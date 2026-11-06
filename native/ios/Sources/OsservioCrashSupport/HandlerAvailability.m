#import <Foundation/Foundation.h>
#include "OsservioCrashSupport.h"
#include <mach/mach.h>
#include <signal.h>
#include <sys/sysctl.h>
#include <unistd.h>

static OsservioCrashHandlerAvailability inspectMachPorts(bool task) {
    exception_mask_t masks[EXC_TYPES_COUNT];
    exception_handler_t ports[EXC_TYPES_COUNT];
    exception_behavior_t behaviors[EXC_TYPES_COUNT];
    thread_state_flavor_t flavors[EXC_TYPES_COUNT];
    mach_msg_type_number_t count = EXC_TYPES_COUNT;
    // Postmortem crash, resource and corpse notifications can belong to the OS;
    // they do not intercept these pre-termination BSD signal exceptions.
    const exception_mask_t fatalMask = EXC_MASK_BAD_ACCESS | EXC_MASK_BAD_INSTRUCTION |
        EXC_MASK_ARITHMETIC | EXC_MASK_SOFTWARE | EXC_MASK_BREAKPOINT;
    mach_port_t owner = task ? mach_task_self() : mach_thread_self();
    kern_return_t result = task
        ? task_get_exception_ports(owner, fatalMask, masks, &count, ports, behaviors, flavors)
        : thread_get_exception_ports(owner, fatalMask, masks, &count, ports, behaviors, flavors);
    if (!task) mach_port_deallocate(mach_task_self(), owner);
    if (result != KERN_SUCCESS) return OsservioCrashHandlerQueryFailed;
    bool found = false;
    for (mach_msg_type_number_t index = 0; index < count; index++) {
        if (ports[index] != MACH_PORT_NULL) found = true;
        if (MACH_PORT_VALID(ports[index])) {
            mach_port_deallocate(mach_task_self(), ports[index]);
        }
    }
    return found ? OsservioCrashMachHandlerPresent : OsservioCrashHandlersAvailable;
}

OsservioCrashHandlerAvailability OsservioCrashInspectHandlers(void) {
    int query[] = { CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid() };
    struct kinfo_proc process = {0};
    size_t size = sizeof(process);
    if (sysctl(query, 4, &process, &size, NULL, 0) != 0 || size != sizeof(process)) {
        return OsservioCrashHandlerQueryFailed;
    }
    if ((process.kp_proc.p_flag & P_TRACED) != 0) return OsservioCrashDebuggerPresent;
    if (NSGetUncaughtExceptionHandler() != NULL) return OsservioCrashExceptionHandlerPresent;
    const int signals[] = { SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGSEGV, SIGTRAP };
    for (size_t index = 0; index < sizeof(signals) / sizeof(signals[0]); index++) {
        struct sigaction action = {0};
        if (sigaction(signals[index], NULL, &action) != 0) return OsservioCrashHandlerQueryFailed;
        if (action.sa_handler != SIG_DFL) return OsservioCrashSignalHandlerPresent;
    }
    OsservioCrashHandlerAvailability task = inspectMachPorts(true);
    return task == OsservioCrashHandlersAvailable ? inspectMachPorts(false) : task;
}
