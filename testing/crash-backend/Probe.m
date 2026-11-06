#import <Foundation/Foundation.h>
#import "OsservioCrashBackend.h"
#import "OsservioCrashSupport.h"
#import "PLCrashAsync.h"
#import "PLCrashReport.pb-c.h"
#include <fcntl.h>
#include <mach/mach_time.h>
#include <signal.h>
#include <sys/stat.h>
#include <unistd.h>

#define REQUIRE(value, message) do { if (!(value)) { fprintf(stderr, "%s\n", message); return 70; } } while (0)

static void existingSignal(int value) { (void)value; }
static void existingException(NSException *value) { (void)value; }
static void *poisonAllocation(void *context, size_t size) {
    (void)context;
    void *result = malloc(size);
    if (result != NULL) memset(result, 0xa5, size);
    return result;
}
static void releaseAllocation(void *context, void *pointer) { (void)context; free(pointer); }

static uint64_t deadlineAfter(uint64_t milliseconds) {
    mach_timebase_info_data_t timebase;
    if (mach_timebase_info(&timebase) != KERN_SUCCESS || timebase.denom == 0) abort();
    return (uint64_t)((__uint128_t)mach_absolute_time() * timebase.numer / timebase.denom / 1000000) + milliseconds;
}

@interface PLCrashReporter (OsservioTestMetadata)
- (id)initWithApplicationIdentifier:(NSString *)identifier appVersion:(NSString *)version
               appMarketingVersion:(NSString *)marketing configuration:(PLCrashReporterConfig *)configuration;
@end

// Adapted from PLCrashReporter's testWriteLimits (Copyright 2008–2009 Plausible
// Labs Cooperative, Inc.), with overflow and post-rejection checks added.
// The retained upstream test and its license are in Vendor/PLCrashReporter.
static int writeBudget(NSString *directory) {
    NSString *path = [directory stringByAppendingPathComponent:@"budget.bin"];
    int fd = open(path.fileSystemRepresentation, O_RDWR | O_CREAT | O_EXCL, 0600);
    REQUIRE(fd >= 0, "fixture open failed");
    plcrash_async_file_t writer;
    plcrash_async_file_init(&writer, fd, 8);
    const uint32_t value = 1;
    REQUIRE(plcrash_async_file_write(&writer, &value, sizeof(value)), "first write failed");
    REQUIRE(!plcrash_async_file_write(&writer, &value, SIZE_MAX), "overflow length accepted");
    REQUIRE(plcrash_async_file_write(&writer, &value, sizeof(value)), "second write failed");
    REQUIRE(!plcrash_async_file_write(&writer, &value, sizeof(value)), "byte cap exceeded");
    REQUIRE(plcrash_async_file_flush(&writer), "flush failed");
    struct stat info;
    REQUIRE(fstat(fd, &info) == 0 && info.st_size == 8, "wrong stored byte count");
    REQUIRE(plcrash_async_file_close(&writer), "close failed");
    puts("budget passed");
    return 0;
}

static int inspectReport(NSData *data) {
    REQUIRE(data != nil && data.length <= 65536, "missing or oversized report");
    NSError *error = nil;
    PLCrashReport *report = [[PLCrashReport alloc] initWithData:data error:&error];
    REQUIRE(report != nil, "invalid report");
    NSData *marker = [@"OsservioCrash/1" dataUsingEncoding:NSUTF8StringEncoding];
    REQUIRE(report.customData.length == marker.length + 1, "wrong profile length");
    REQUIRE([[report.customData subdataWithRange:NSMakeRange(0, marker.length)] isEqualToData:marker], "wrong profile");
    REQUIRE(((const uint8_t *)report.customData.bytes)[marker.length] <= 1, "invalid image loss flags");
    REQUIRE(!report.hasProcessInfo && !report.hasExceptionInfo, "unexpected private fields");
    REQUIRE(report.threads.count == 1 && report.images.count > 0 && report.images.count <= 512, "invalid counts");
    PLCrashReportThreadInfo *thread = report.threads[0];
    REQUIRE(thread.crashed && thread.stackFrames.count > 0 && thread.stackFrames.count <= 128, "missing faulting stack");
    REQUIRE(thread.registers.count == 0, "registers present");
    for (PLCrashReportStackFrameInfo *frame in thread.stackFrames) REQUIRE(frame.symbolInfo == nil, "symbol text present");
    for (PLCrashReportBinaryImageInfo *image in report.images) REQUIRE(image.imageName.length == 0, "image path present");
    REQUIRE([data rangeOfData:[@"PRIVATE_CRASH_CANARY" dataUsingEncoding:NSUTF8StringEncoding]
                     options:0 range:NSMakeRange(0, data.length)].location == NSNotFound, "private content present");
    NSDictionary *receipt = @{ @"bytes": @(data.length), @"frames": @(thread.stackFrames.count),
                              @"images": @(report.images.count), @"signal": report.signalInfo.name };
    NSData *encoded = [NSJSONSerialization dataWithJSONObject:receipt options:NSJSONWritingSortedKeys error:&error];
    REQUIRE(encoded != nil, "receipt encoding failed");
    puts([[NSString alloc] initWithData:encoded encoding:NSUTF8StringEncoding].UTF8String);
    return 0;
}

int main(int argc, char **argv) {
    @autoreleasepool {
        OsservioCrashCaptureSetArmed(true);
        REQUIRE(argc == 3, "expected mode and fixture path");
        NSString *mode = @(argv[1]), *path = @(argv[2]);
        if ([mode isEqualToString:@"decoder-initialization"]) {
            // Exercise generic initialization with non-zero allocator memory,
            // including the no-unknown-fields cleanup fixed upstream in 1.5.1.
            ProtobufCMessageDescriptor descriptor = plcrash__crash_report__report_info__descriptor;
            descriptor.message_init = NULL;
            ProtobufCAllocator allocator = { poisonAllocation, releaseAllocation, NULL };
            const uint8_t encoded[] = { 0x08, 0x00 }; // Required user_requested = false.
            ProtobufCMessage *message = protobuf_c_message_unpack(&descriptor, &allocator, sizeof(encoded), encoded);
            REQUIRE(message != NULL, "valid message rejected");
            REQUIRE(message->n_unknown_fields == 0 && message->unknown_fields == NULL, "unknown field pointer not initialized");
            protobuf_c_message_free_unpacked(message, &allocator);
            puts("decoder initialization passed");
            return 0;
        }
        if ([mode isEqualToString:@"image-budget"]) {
            REQUIRE(OsservioCrashImageFlags() == 0, "unexpected initial loss flag");
            for (int index = 0; index < 1024; index++) REQUIRE(OsservioCrashAdmitImage(65536, 4096), "image admission rejected early");
            REQUIRE(!OsservioCrashAdmitImage(1, 1) && OsservioCrashImageFlags() == 1, "image count cap missing");
            puts("image admission budget passed");
            return 0;
        }
        if ([mode isEqualToString:@"image-size"]) {
            REQUIRE(!OsservioCrashAdmitImage(65537, 1), "oversized load commands admitted");
            REQUIRE(!OsservioCrashAdmitImage(1, 4097), "too many load commands admitted");
            for (int index = 0; index < 1024; index++) REQUIRE(OsservioCrashAdmitImage(1, 1), "rejected image consumed admission");
            REQUIRE(OsservioCrashImageFlags() == 1, "image loss flag missing");
            puts("image work limits passed");
            return 0;
        }
        if ([mode isEqualToString:@"budget"]) return writeBudget(path);
        if ([mode isEqualToString:@"read"]) {
            NSData *data = [NSData dataWithContentsOfFile:path];
            NSError *error = nil;
            PLCrashReport *report = [[PLCrashReport alloc] initWithData:data error:&error];
            REQUIRE(report.hasReportInfo && !report.userRequested, "fatal report mislabeled as requested");
            return inspectReport(data);
        }
        if ([mode isEqualToString:@"invalid"]) {
            NSError *error = nil;
            NSData *data = [NSData dataWithContentsOfFile:path];
            REQUIRE(data != nil && data.length <= 65536, "invalid fixture size");
            REQUIRE([[PLCrashReport alloc] initWithData:data error:&error] == nil, "incomplete report accepted");
            puts("incomplete report rejected");
            return 0;
        }
        OsservioCrashHandlerAvailability availability = OsservioCrashInspectHandlers();
        if (availability != OsservioCrashHandlersAvailable) {
            fprintf(stderr, "fixture process has an existing handler or query failure: %d\n", availability);
            return 71;
        }
        BOOL conflict = NO;
        if ([mode isEqualToString:@"signal-conflict"] || [mode isEqualToString:@"ignored-signal"]) {
            struct sigaction action = {0};
            action.sa_handler = [mode isEqualToString:@"ignored-signal"] ? SIG_IGN : existingSignal;
            sigemptyset(&action.sa_mask);
            REQUIRE(sigaction(SIGABRT, &action, NULL) == 0, "fixture signal install failed");
            REQUIRE(OsservioCrashInspectHandlers() == OsservioCrashSignalHandlerPresent, "signal conflict missed");
            conflict = YES;
        } else if ([mode isEqualToString:@"exception-conflict"]) {
            NSSetUncaughtExceptionHandler(existingException);
            REQUIRE(OsservioCrashInspectHandlers() == OsservioCrashExceptionHandlerPresent, "exception conflict missed");
            conflict = YES;
        } else if ([mode isEqualToString:@"mach-conflict"]) {
            mach_port_t port;
            REQUIRE(mach_port_allocate(mach_task_self(), MACH_PORT_RIGHT_RECEIVE, &port) == KERN_SUCCESS, "fixture port failed");
            REQUIRE(mach_port_insert_right(mach_task_self(), port, port, MACH_MSG_TYPE_MAKE_SEND) == KERN_SUCCESS, "fixture right failed");
            REQUIRE(task_set_exception_ports(mach_task_self(), EXC_MASK_BAD_ACCESS, port, EXCEPTION_DEFAULT, THREAD_STATE_NONE)
                    == KERN_SUCCESS, "fixture exception install failed");
            REQUIRE(OsservioCrashInspectHandlers() == OsservioCrashMachHandlerPresent, "Mach conflict missed");
            conflict = YES;
        }
        PLCrashReporterConfig *config = [[PLCrashReporterConfig alloc]
            initWithSignalHandlerType:PLCrashReporterSignalHandlerTypeBSD
            symbolicationStrategy:PLCrashReporterSymbolicationStrategyNone
            shouldRegisterUncaughtExceptionHandler:NO basePath:path maxReportBytes:[mode isEqualToString:@"small-budget"] ? 128 : 65536];
        PLCrashReporter *reporter = [[PLCrashReporter alloc] initWithConfiguration:config];
        if ([mode isEqualToString:@"metadata"]) {
            NSString *exact = [@"" stringByPaddingToLength:128 withString:@"😀" startingAtIndex:0];
            NSString *oversized = [@"" stringByPaddingToLength:257 withString:@"x" startingAtIndex:0];
            reporter = [[PLCrashReporter alloc] initWithApplicationIdentifier:@"io.osservio.fixture"
                appVersion:exact appMarketingVersion:oversized configuration:config];
            NSError *metadataError = nil;
            NSData *data = [reporter generateLiveReportAndReturnError:&metadataError];
            PLCrashReport *report = [[PLCrashReport alloc] initWithData:data error:&metadataError];
            REQUIRE(report != nil, "metadata report failed");
            REQUIRE([report.applicationInfo.applicationVersion isEqualToString:exact], "exact UTF-8 boundary lost");
            REQUIRE(report.applicationInfo.applicationMarketingVersion.length == 0, "oversized metadata was not omitted");
            NSString *embeddedNull = [[NSString alloc] initWithBytes:"before\0after" length:12 encoding:NSUTF8StringEncoding];
            reporter = [[PLCrashReporter alloc] initWithApplicationIdentifier:@"io.osservio.fixture"
                appVersion:embeddedNull appMarketingVersion:@"fixture" configuration:config];
            data = [reporter generateLiveReportAndReturnError:&metadataError];
            report = [[PLCrashReport alloc] initWithData:data error:&metadataError];
            REQUIRE(report != nil && report.applicationInfo.applicationVersion.length == 0, "embedded null was silently truncated");
            puts("metadata boundary passed");
            return 0;
        }
        reporter.customData = [@"PRIVATE_CRASH_CANARY" dataUsingEncoding:NSUTF8StringEncoding];
        NSError *error = nil;
        if ([mode isEqualToString:@"live"]) return inspectReport([reporter generateLiveReportAndReturnError:&error]);
        BOOL enabled = [reporter enableCrashReporterAndReturnError:&error];
        if (conflict) {
            REQUIRE(!enabled && error.code == PLCrashReporterErrorResourceBusy, "conflicting install accepted");
            if ([mode isEqualToString:@"signal-conflict"] || [mode isEqualToString:@"ignored-signal"]) {
                struct sigaction current = {0};
                REQUIRE(sigaction(SIGABRT, NULL, &current) == 0, "fixture query failed");
                REQUIRE(current.sa_handler == ([mode isEqualToString:@"ignored-signal"] ? SIG_IGN : existingSignal), "host signal handler replaced");
            } else if ([mode isEqualToString:@"exception-conflict"]) {
                REQUIRE(NSGetUncaughtExceptionHandler() == existingException, "host exception handler replaced");
            } else REQUIRE(OsservioCrashInspectHandlers() == OsservioCrashMachHandlerPresent, "host Mach handler replaced");
            puts("conflict preserved");
            return 0;
        }
        REQUIRE(enabled, "recording installation failed");
        if ([mode isEqualToString:@"on"] || [mode isEqualToString:@"small-budget"]) OsservioCrashCaptureUpdate(true, 0);
        else if ([mode isEqualToString:@"detail"]) OsservioCrashCaptureUpdate(false, deadlineAfter(10000));
        else if ([mode isEqualToString:@"off"]) { OsservioCrashCaptureUpdate(true, 0); OsservioCrashCaptureUpdate(false, 0); }
        else if ([mode isEqualToString:@"expired"]) { OsservioCrashCaptureUpdate(false, deadlineAfter(1)); usleep(10000); }
        else if ([mode isEqualToString:@"baseline-after-expiry"]) { OsservioCrashCaptureUpdate(true, deadlineAfter(1)); usleep(10000); }
        else if ([mode isEqualToString:@"default-off"]) { /* The process starts without a capture permission. */ }
        else return 72;
        puts(reporter.crashReportPath.fileSystemRepresentation);
        fflush(stdout);
        raise(SIGABRT);
        return 73;
    }
}
