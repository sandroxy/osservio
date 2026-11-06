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
]:
    def integer(value, lower=lower, upper=upper):
        return isinstance(value, str) and re.fullmatch(r"0|-?[1-9][0-9]*", value) is not None and lower <= int(value) <= upper
    FORMATS.checks(name)(integer)
for limit in [256, 512, 4096, 4128, 65536]:
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


def valid_url(value):
    if value == "[unavailable]":
        return
    parts = urlsplit(value)
    require(parts.scheme in ("http", "https", "ws", "wss") and parts.hostname
            and parts.hostname.isascii() and parts.username is None and parts.password is None
            and "#" not in value and ("?" not in value or parts.query == "[redacted]")
            and (parts.port is None or 1 <= parts.port <= 65535)
            and all(ord(char) >= 32 and ord(char) != 127 for char in value), "Invalid diagnostic URL")


def http_event(event):
    status, state = event["statusCode"], event["state"]
    require((state == "failed") == (event["failure"] is not None), "HTTP failure mismatch")
    require(status is not None or (event["responseURL"] is None and not event["responseHeaders"]), "Response metadata without status")
    valid_url(event["url"])
    if event["responseURL"] is not None:
        valid_url(event["responseURL"])
    for direction in ("request", "response"):
        body = event[direction + "Body"]
        require((body["state"] in ("available", "truncated")) == (body["blobID"] is not None), "HTTP body reference mismatch")
        require(body["blobID"] is None or body["blobID"].lower() == event["id"].lower() + "." + direction, "HTTP body owner mismatch")
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


def socket_event(event):
    kind, payload = event["kind"], event["payload"]
    valid_url(event["url"])
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
        if family == "http":
            require(amount == int(original["observedBytes"]) if original["state"] == "available"
                    else original["state"] == "truncated" and amount < int(original["observedBytes"]), "Body disagrees with its observation")
        elif original["state"] == "available":
            require(amount == int(original["byteCount"]), "Payload disagrees with its observation")
    require(group["retainedPayloadBytes"] == sum(utf8(value) for value in content.values()), "Wrong payload total")


def semantics(metadata, payloads):
    incident = metadata["incident"]
    require(incident["id"].lower() == payloads["incidentID"].lower(), "Documents belong to different incidents")
    total = utf8(incident["note"]["text"]) if incident["note"] is not None else 0
    if incident["environment"] is not None:
        for key in ENVIRONMENT_KEYS:
            value = incident["environment"]["values"][key]
            if value["type"] == "text":
                total += utf8(value["value"])
            if key == "batteryPercent" and value["type"] == "number":
                require(int(value["value"]) <= 100, "Battery percentage out of range")
    for family in ("breadcrumbs", "logs", "http", "webSockets"):
        group = incident[family]
        if group is None:
            require(family not in payloads or not payloads[family], "Content has no attached group")
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
                http_event(event)
                size += utf8(event["method"]) + utf8(event["url"]) + utf8(event["responseURL"]) + header_bytes(event["requestHeaders"] + event["responseHeaders"])
            else:
                socket_event(event)
                size += utf8(event["url"]) + header_bytes(event["headers"])
            bodies = [event["requestBody"], event["responseBody"]] if family == "http" else [event.get("payload")]
            for body in bodies:
                if body is not None and body["blobID"] is not None:
                    require(body["blobID"] not in references, "Duplicate body reference")
                    references[body["blobID"]] = body
        require(group["retainedTextBytes"] == size, "Wrong metadata text total")
        total += size
        if family in ("http", "webSockets"):
            selections(group, payloads[family], references, family)
            total += group["retainedPayloadBytes"]
    require(total <= 2097152, "Export exceeds text admission limit")


def tolerant(schema):
    if isinstance(schema, dict):
        return {key: tolerant(value) for key, value in schema.items() if not (key == "additionalProperties" and value is False)}
    if isinstance(schema, list):
        return [tolerant(value) for value in schema]
    return schema


def validators(reader=False):
    schemas = [json.loads((ROOT / f"{name}.schema.json").read_text()) for name in ("incident", "payloads")]
    if reader:
        schemas = [tolerant(schema) for schema in schemas]
    registry = Registry().with_resources((schema["$id"], Resource.from_contents(schema)) for schema in schemas)
    return [Draft202012Validator(schema, registry=registry, format_checker=FORMATS) for schema in schemas]


def validate(metadata, payloads, reader=False):
    for validator, document in zip(validators(reader), (metadata, payloads)):
        validator.validate(document)
    semantics(metadata, payloads)
