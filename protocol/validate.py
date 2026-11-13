"""Development-only conformance checks; no archive import or SDK runtime reader."""
import json
import re
from pathlib import Path
from urllib.parse import urlsplit

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource


ROOT = Path(__file__).resolve().parent
ENVIRONMENT_KEYS = (
    "osVersion", "appIdentifier", "appVersion", "appBuild", "manufacturer", "deviceModel", "architecture",
    "thermalState", "physicalMemoryBytes", "processResidentBytes", "processProportionalBytes", "processFootprintBytes",
    "runtimeHeapBytes", "runtimeHeapLimitBytes", "processID", "batteryPercent", "lowPowerMode",
)
FORMATS = FormatChecker()
for name, lower, upper in [
    ("int64", -(2**63), 2**63 - 1), ("uint63", 0, 2**63 - 1),
    ("positive64", 1, 2**63 - 1), ("uint64", 0, 2**64 - 1),
    ("diagnosticCode", -(2**63), 2**64 - 1),
]:
    def integer(value, lower=lower, upper=upper):
        return isinstance(value, str) and re.fullmatch(r"0|-?[1-9][0-9]*", value) is not None and lower <= int(value) <= upper
    FORMATS.checks(name)(integer)
for limit in [256, 512, 4096, 4128, 16384, 65536]:
    def bounded(value, limit=limit):
        try:
            return isinstance(value, str) and len(value.encode("utf-8")) <= limit
        except UnicodeEncodeError:
            return False
    FORMATS.checks(f"utf8-{limit}")(bounded)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def utf8(value):
    return len(value.encode("utf-8")) if value is not None else 0


def header_bytes(headers):
    return sum(utf8(header["name"]) + utf8(header["value"]) for header in headers)


def valid_url(value, revision):
    if value == "[unavailable]":
        return
    parts = urlsplit(value)
    require(parts.scheme in ("http", "https", "ws", "wss") and parts.hostname
            and parts.hostname.isascii()
            and (revision >= 5 or (parts.username is None and parts.password is None
                 and "#" not in value and ("?" not in value or parts.query == "[redacted]")))
            and (parts.port is None or 1 <= parts.port <= 65535)
            and all(ord(char) >= 32 and ord(char) != 127 for char in value), "Invalid diagnostic URL")


def http_event(event, revision):
    status, state = event["statusCode"], event["state"]
    metrics = event.get("metrics") if revision >= 7 else None
    if metrics is not None:
        require(state not in ("pending", "receiving", "captureEnded"), "Metrics require a terminal observation")
        for phase in metrics["phases"]:
            require(phase["endMillis"] is None or int(phase["endMillis"]) >= int(phase["startMillis"]), "Phase ends before its start")
    require((state == "failed") == (event["failure"] is not None), "HTTP failure mismatch")
    require(status is not None or (event["responseURL"] is None and not event["responseHeaders"]), "Response metadata without status")
    valid_url(event["url"], revision)
    if event["responseURL"] is not None:
        valid_url(event["responseURL"], revision)
    for direction in ("request", "response"):
        body = event[direction + "Body"]
        require((body["state"] in ("available", "truncated")) == (body["blobID"] is not None), "HTTP body reference mismatch")
        require(body["blobID"] is None or body["blobID"].lower() == event["id"].lower() + "." + direction, "HTTP body owner mismatch")
        require(not body.get("isProcessed", False) or body["state"] in ("available", "truncated"), "Processed absent body")
        amount = int(body["observedBytes"])
        require(body["state"] != "pending" or amount == 0, "Pending body has byte count")
        require(body["state"] != "available" or amount <= 65536, "Oversized available body")
        require(body["state"] != "truncated" or amount > 0, "Truncated empty body")
    bodies = [event["requestBody"]["state"], event["responseBody"]["state"]]
    if state in ("pending", "receiving"):
        require(event["durationMillis"] is None and bodies == ["pending", "pending"]
                and int(event["revision"]) == (0 if state == "pending" else 1)
                and (state == "receiving") == (status is not None), "Invalid pending exchange")
    elif state == "captureEnded":
        require(event["durationMillis"] is None and all(value in ("omitted", "incomplete") for value in bodies)
                and int(event["revision"]) == (1 if status is None else 2), "Invalid ended exchange")
    else:
        require("pending" not in bodies and int(event["revision"]) == (1 if status is None else 2)
                and (state != "completed" or status is not None)
                and (state == "completed" or bodies[1] in ("omitted", "incomplete")), "Invalid terminal exchange")


def socket_event(event, revision):
    kind, payload = event["kind"], event["payload"]
    valid_url(event["url"], revision)
    require(event["statusCode"] is None or kind in ("opened", "failed"), "Unexpected handshake status")
    require(event["closeCode"] is None or kind in ("closeRequested", "closing", "closed", "cancelRequested"), "Unexpected close code")
    require((not event["headers"] and not event["headersLimited"]) or kind in ("connecting", "attached", "opened", "failed"), "Unexpected handshake headers")
    if kind == "message":
        require(event["direction"] is not None and event["outcome"] in ("received", "submitted", "queued", "sent", "rejected", "failed")
                and (event["direction"] == "incoming") == (event["outcome"] == "received")
                and payload is not None and event["failure"] is None and event["relatedMessageID"] is None, "Invalid message")
    elif kind == "messageResult":
        require(event["direction"] == "outgoing" and event["outcome"] in ("sent", "failed", "unobserved")
                and (event["outcome"] == "failed") == (event["failure"] is not None)
                and event["relatedMessageID"] is not None and payload is None, "Invalid send result")
    else:
        require(event["direction"] is None and event["outcome"] is None and payload is None and event["relatedMessageID"] is None
                and (kind == "failed") == (event["failure"] is not None), "Invalid connection observation")
    if payload is not None:
        require(not payload.get("isProcessed", False) or payload["state"] in ("available", "truncated"), "Processed absent payload")
        require((payload["state"] in ("available", "truncated")) == (payload["blobID"] is not None), "Payload reference mismatch")
        require(payload["blobID"] is None or payload["blobID"].lower() == event["id"].lower(), "Payload owner mismatch")
        if payload["state"] == "available":
            require(payload["byteCount"] is not None and int(payload["byteCount"]) <= 65536, "Invalid available payload")
        elif payload["state"] == "binary":
            require(payload["byteCount"] is not None, "Missing binary byte count")
        else:
            require(payload["byteCount"] is None, "Invented payload byte count")


def selections(group, content, references, family):
    selected = group["payloads"]
    require(set(selected) == set(references), "Detached or missing body selection")
    require(len({key.lower() for key in selected}) == len(selected), "Duplicate body identity")
    available = {key for key, value in selected.items() if value["state"] == "available"}
    require(available == set(content), "Body content does not match inclusion states")
    for key, text in content.items():
        amount, original = utf8(text), references[key]
        require(amount == selected[key]["textByteCount"], "Wrong included body byte count")
        if original.get("isProcessed", False):
            continue
        if family == "http":
            require(amount == int(original["observedBytes"]) if original["state"] == "available"
                    else original["state"] == "truncated" and amount < int(original["observedBytes"]), "Body disagrees with its observation")
        elif family == "logs":
            require(amount == original["textByteCount"], "Log details disagree with their reference")
        elif original["state"] == "available":
            require(amount == int(original["byteCount"]), "Payload disagrees with its observation")
    require(group["retainedPayloadBytes"] == sum(utf8(value) for value in content.values()), "Wrong payload total")


def semantics(metadata, payloads):
    incident = metadata["incident"]
    require(incident["id"].lower() == payloads["incidentID"].lower(), "Documents belong to different incidents")
    total = utf8(incident["note"]["text"]) if incident["note"] is not None else 0
    if metadata["revision"] >= 2:
        diagnostic = incident["systemDiagnostic"]
        require((incident["origin"] == "systemDiagnostic") == (diagnostic is not None), "Incident origin conflicts with its evidence")
        if diagnostic is not None:
            total += diagnostic_semantics(diagnostic, metadata["revision"])
    if incident["environment"] is not None:
        for key in ENVIRONMENT_KEYS:
            value = incident["environment"]["values"][key]
            if value["type"] == "text":
                total += utf8(value["value"])
            if key == "batteryPercent" and value["type"] == "number":
                require(int(value["value"]) <= 100, "Battery percentage out of range")
    for family in ("breadcrumbs", "logs", "http", "webSockets"):
        group = incident[family]
        has_payloads = family in ("http", "webSockets") or family == "logs" and metadata["revision"] >= 6
        if group is None:
            require(not has_payloads or not payloads[family], "Content has no attached group")
            continue
        events = group["events"]
        require(group["sourceEventCount"] - len(events) == group["omittedEventCount"], "Wrong source event accounting")
        ids = [(value["sessionID"].lower(), value["sequence"]) if family == "logs" else value["id"].lower() for value in events]
        require(len(ids) == len(set(ids)), "Duplicate event identity")
        size, references = 0, {}
        for event in events:
            if family == "breadcrumbs":
                size += utf8(event["label"]) + utf8(event["message"])
            elif family == "logs":
                size += utf8(event["category"]) + utf8(event["message"])
            elif family == "http":
                http_event(event, metadata["revision"])
                if metadata["revision"] >= 7 and event["metrics"] is not None: size += utf8(event["metrics"]["protocol"])
                size += utf8(event["method"]) + utf8(event["url"]) + utf8(event["responseURL"]) + header_bytes(event["requestHeaders"] + event["responseHeaders"])
            else:
                socket_event(event, metadata["revision"])
                size += utf8(event["url"]) + header_bytes(event["headers"])
            bodies = [event["requestBody"], event["responseBody"]] if family == "http" else [event.get("details") if family == "logs" and has_payloads else event.get("payload") if family == "webSockets" else None]
            for body in bodies:
                if body is not None and body["blobID"] is not None:
                    require(body["blobID"] not in references, "Duplicate body reference")
                    references[body["blobID"]] = body
        require(group["retainedTextBytes"] == size, "Wrong metadata text total")
        total += size
        if has_payloads:
            selections(group, payloads[family], references, family)
            total += group["retainedPayloadBytes"]
    require(total <= 2097152, "Export exceeds text admission limit")


def diagnostic_semantics(diagnostic, revision):
    start, end = int(diagnostic["intervalStartMillis"]), int(diagnostic["intervalEndMillis"])
    require(start <= end, "Reversed diagnostic interval")
    if diagnostic["source"] == "appleSignal":
        signal = diagnostic["appleSignal"]
        require(signal is not None and diagnostic["androidExit"] is None and diagnostic["metricKit"] is None,
                "Invalid Apple signal fields")
        require(diagnostic["kind"] == "nativeCrash" and start == end and start > 0 and start % 1000 == 0,
                "Apple signal must preserve its whole-second recorder time")
        return apple_signal_semantics(signal)
    if revision >= 3:
        require(diagnostic["appleSignal"] is None, "Foreign signal evidence")
    if diagnostic["source"] == "androidExitInfo":
        exit_info = diagnostic["androidExit"]
        require(exit_info is not None and diagnostic["metricKit"] is None, "Invalid Android diagnostic fields")
        require(start == end and start >= 0, "Android exit must preserve its exact time")
        kind = {2: "signalTermination", 3: "lowMemoryTermination", 4: "managedCrash", 5: "nativeCrash", 6: "anr"}.get(exit_info["reasonCode"], "otherExit")
        require(diagnostic["kind"] == kind, "Android reason classification mismatch")
        if revision >= 4:
            state, trace = exit_info["tombstoneState"], exit_info["tombstone"]
            require((state == "available") == (trace is not None), "Tombstone availability mismatch")
            require((exit_info["reasonCode"] != 5) == (state == "notApplicable"), "Tombstone belongs only to native crashes")
            return 0 if trace is None else sum(utf8(frame["buildID"]) for frame in trace["frames"])
        return 0
    metric = diagnostic["metricKit"]
    require(metric is not None and diagnostic["androidExit"] is None, "Invalid MetricKit fields")
    if diagnostic["kind"] == "crash":
        require(metric["hangDurationMillis"] is None, "Crash has a hang duration")
    else:
        require(diagnostic["kind"] == "hang" and metric["hangDurationMillis"] is not None
                and all(metric[field] is None for field in ("signal", "exceptionType", "exceptionCode")), "Invalid hang evidence")
    # Readers ignore unknown optional metadata without changing known-field semantics.
    return utf8(metric["exceptionCode"]) + sum(utf8(metric["metadata"][key]["text"])
        for key in ("appVersion", "appBuild", "osVersion", "deviceModel") if key in metric["metadata"])


def apple_signal_semantics(signal):
    symbols = {
        "SIGABRT": (),
        "SIGBUS": ("BUS_NOOP", "BUS_ADRALN", "BUS_ADRERR", "BUS_OBJERR"),
        "SIGFPE": ("FPE_NOOP", "FPE_FLTDIV", "FPE_FLTOVF", "FPE_FLTUND", "FPE_FLTRES", "FPE_FLTINV", "FPE_FLTSUB", "FPE_INTDIV", "FPE_INTOVF"),
        "SIGILL": ("ILL_NOOP", "ILL_ILLOPC", "ILL_ILLTRP", "ILL_PRVOPC", "ILL_ILLOPN", "ILL_ILLADR", "ILL_PRVREG", "ILL_COPROC", "ILL_BADSTK"),
        "SIGSEGV": ("SEGV_NOOP", "SEGV_MAPERR", "SEGV_ACCERR"), "SIGTRAP": ("TRAP_BRKPT", "TRAP_TRACE"),
    }
    code = signal["code"]
    numeric = re.fullmatch(r"#(0|-?[1-9][0-9]*)", code)
    require((numeric is not None and -(2**31) <= int(numeric[1]) < 2**31) or code in symbols[signal["signal"]],
            "Invalid signal-specific code")
    require(signal["threadNumber"] is not None or not signal["frames"], "Frames without a faulting thread")
    total = utf8(signal["reportID"]) + utf8(signal["signal"]) + utf8(code)
    for image in signal["images"]:
        require(int(image["baseAddress"]) + int(image["size"]) < 2**64, "Overflowing image extent")
        require((image["cpuType"] is None) == (image["cpuSubtype"] is None), "Incomplete CPU identity")
        total += utf8(image["uuid"])
    return total + sum(utf8(signal["metadata"][key]["text"])
        for key in ("appVersion", "appBuild", "osVersion", "deviceModel") if key in signal["metadata"])


def tolerant(schema):
    if isinstance(schema, dict):
        return {key: tolerant(value) for key, value in schema.items() if not (key == "additionalProperties" and value is False)}
    if isinstance(schema, list):
        return [tolerant(value) for value in schema]
    return schema


def validators(reader=False, revision=7):
    require(type(revision) is int and revision in (1, 2, 3, 4, 5, 6, 7), "Unsupported incident revision")
    schemas = [json.loads((directory / f"{name}.schema.json").read_text())
               for directory in (ROOT / "revisions" / "1", ROOT / "revisions" / "2", ROOT / "revisions" / "3", ROOT / "revisions" / "4", ROOT / "revisions" / "5", ROOT / "revisions" / "6", ROOT) for name in ("incident", "payloads")]
    if reader:
        schemas = [tolerant(schema) for schema in schemas]
    registry = Registry().with_resources((schema["$id"], Resource.from_contents(schema)) for schema in schemas)
    selected = schemas[(revision - 1) * 2:revision * 2]
    return [Draft202012Validator(schema, registry=registry, format_checker=FORMATS) for schema in selected]


def validate(metadata, payloads, reader=False):
    for validator, document in zip(validators(reader, metadata.get("revision")), (metadata, payloads)):
        validator.validate(document)
    semantics(metadata, payloads)
