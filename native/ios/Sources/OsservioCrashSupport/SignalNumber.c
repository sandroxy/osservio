#include "OsservioCrashSupport.h"

void OsservioCrashFormatSignalNumber(int32_t value, char output[13]) {
    char digits[10];
    unsigned count = 0, position = 0;
    uint32_t magnitude = value < 0 ? 0U - (uint32_t)value : (uint32_t)value;
    do {
        digits[count++] = (char)('0' + magnitude % 10);
        magnitude /= 10;
    } while (magnitude != 0);
    output[position++] = '#';
    if (value < 0) output[position++] = '-';
    while (count > 0) output[position++] = digits[--count];
    output[position] = '\0';
}
