"""Address-only crash evidence, checked independently of native codecs."""
import copy
import unittest
from jsonschema import ValidationError
from test_conformance import fixture
from validate import ROOT, validate


class SignalCrashTests(unittest.TestCase):
    def check(self, document, accepted, reader=False):
        if accepted:
            validate(document["metadata"], document["payloads"], reader=reader)
        else:
            with self.assertRaises((ValidationError, ValueError)):
                validate(document["metadata"], document["payloads"], reader=reader)

    def test_shared_model_invariants(self):
        for row in (ROOT.parent / "testing/apple-signal-cases.tsv").read_text().splitlines():
            name, field, value, expected = row.split("\t")
            document = fixture("diagnostic-signal")
            diagnostic = document["metadata"]["incident"]["systemDiagnostic"]
            signal = diagnostic["appleSignal"]
            if field == "signal_code":
                signal["signal"], signal["code"] = value.split(":")
            elif field in ("code", "id", "address"):
                signal[{"code": "code", "id": "reportID", "address": "faultAddress"}[field]] = value
            elif field == "time":
                diagnostic["intervalStartMillis"] = diagnostic["intervalEndMillis"] = value
            elif field == "frames":
                signal["frames"] = ["18446744073709551615"] * int(value)
            elif field == "images":
                signal["images"] = [copy.deepcopy(signal["images"][0])] * int(value)
            elif field == "thread":
                signal["threadNumber"] = None if value == "-" else int(value)
            elif field == "missing_stack":
                signal["threadNumber"], signal["frames"] = None, []
            elif field in ("image_size", "image_uuid", "cpu_type"):
                signal["images"][0][{"image_size": "size", "image_uuid": "uuid", "cpu_type": "cpuType"}[field]] = None if value == "-" else value
            elif field == "missing_cpu":
                signal["images"][0]["cpuType"] = signal["images"][0]["cpuSubtype"] = None
            elif field == "metadata":
                signal["metadata"]["appVersion"]["text"] = "a\0b" if value == "nul" else "x" * 257
            else:
                self.fail("Unknown shared fixture field: " + field)
            for reader in (False, True):
                with self.subTest(case=name, reader=reader):
                    self.check(document, expected == "valid", reader)

    def test_source_integrity_precision_and_known_field_rejection(self):
        changes = [
            (("source",), "metricKit"), (("kind",), "crash"), (("intervalEndMillis",), "1700000001000"),
            (("appleSignal",), None), (("appleSignal", "faultAddress"), 18446744073709551615),
            (("appleSignal", "frames"), ["-1"]), (("appleSignal", "frames"), ["01"]),
            (("appleSignal", "threadNumber"), True), (("appleSignal", "imageCacheIncomplete"), 1),
            (("appleSignal", "metadata", "appVersion", "isTruncated"), True),
            (("appleSignal", "images", 0, "uuid"), "12345678123412341234abcdef123456\n"),
            (("appleSignal", "images", 0, "cpuSubtype"), "18446744073709551616"),
        ]
        for path, value in changes:
            document = fixture("diagnostic-signal")
            target = document["metadata"]["incident"]["systemDiagnostic"]
            for key in path[:-1]:
                target = target[key]
            target[path[-1]] = value
            for reader in (False, True):
                with self.subTest(path=path, reader=reader):
                    self.check(document, False, reader)
        for source in ("diagnostic-crash", "diagnostic-android"):
            document = fixture(source)
            document["metadata"]["incident"]["systemDiagnostic"]["appleSignal"] = fixture("diagnostic-signal")["metadata"]["incident"]["systemDiagnostic"]["appleSignal"]
            self.check(document, False)
            self.check(document, False, reader=True)

    def test_missing_required_fields_and_optional_extensions(self):
        original = fixture("diagnostic-signal")
        paths = [("appleSignal",), ("appleSignal", "images", 0), ("appleSignal", "metadata", "appVersion")]
        for path in paths:
            target = original["metadata"]["incident"]["systemDiagnostic"]
            for key in path:
                target = target[key]
            for field in target:
                document = copy.deepcopy(original)
                changed = document["metadata"]["incident"]["systemDiagnostic"]
                for key in path:
                    changed = changed[key]
                del changed[field]
                for reader in (False, True):
                    with self.subTest(path=path, missing=field, reader=reader):
                        self.check(document, False, reader)
            document = copy.deepcopy(original)
            changed = document["metadata"]["incident"]["systemDiagnostic"]
            for key in path:
                changed = changed[key]
            changed["futureOptionalField"] = {"opaque": True}
            self.check(document, False)
            self.check(document, True, reader=True)


if __name__ == "__main__":
    unittest.main()
