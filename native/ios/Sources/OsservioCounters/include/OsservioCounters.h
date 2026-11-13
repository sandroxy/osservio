#ifndef OSSERVIO_COUNTERS_H
#define OSSERVIO_COUNTERS_H
#include <stdbool.h>
#include <stdint.h>

typedef struct OsservioCounters OsservioCounters;
OsservioCounters *OsservioCountersCreate(void);
void OsservioCountersDestroy(OsservioCounters *counters);
void OsservioCountersIncrement(OsservioCounters *counters, uint32_t index);
int64_t OsservioCountersRead(const OsservioCounters *counters, uint32_t index);
bool OsservioCountersIncomplete(const OsservioCounters *counters);
#endif
