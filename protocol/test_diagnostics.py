"""Diagnostic semantics checked independently of the native document encoders."""
import copy
import unittest
from jsonschema import ValidationError
from test_conformance import fixture
from validate import ROOT, validate


class DiagnosticTests(unittest.TestCase):
    def check(self, document, accepted, reader=False):
        if accepted:
            validate(document["metadata"], document["payloads"], reader=reader)
        else:
            with self.assertRaises((ValidationError, ValueError)):
                validate(document["metadata"], document["payloads"], reader=reader)

    def test_shared_source_invariants(self):
        fields = dict(reason="reasonCode", pid="processID", pss="pssKiB", rss="rssKiB", type="exceptionType",
                      code="exceptionCode", duration="hangDurationMillis")
        rows = (ROOT.parent / "testing/system-diagnostic-cases.tsv").read_text().splitlines()
        for row in rows:
            name, source, field, value, expected = row.split("\t")
            document = fixture("diagnostic-android" if source == "android" else "diagnostic-hang" if source == "hang" else "diagnostic-crash")
            diagnostic = document["metadata"]["incident"]["systemDiagnostic"]
            diagnostic["intervalStartMillis"] = "100"
            diagnostic["intervalEndMillis"] = "100" if source == "android" else "200"
            if source == "android":
                diagnostic["kind"] = expected if expected != "invalid" else "managedCrash"
                diagnostic["androidExit"].update(reasonCode=4, status=0, processID=1, importance=100, pssKiB=None, rssKiB=None)
            else:
                diagnostic["metricKit"].update(signal=None, exceptionType=None, exceptionCode=None)
            if field == "start":
                diagnostic["intervalStartMillis"] = value
                if source == "android":
                    diagnostic["intervalEndMillis"] = value
            elif field == "kind":
                diagnostic["kind"] = value
            else:
                target = diagnostic["androidExit" if source == "android" else "metricKit"]
                target[fields.get(field, field)] = int(value) if field in ("reason", "pid", "importance", "signal") else value
            for reader in (False, True):
                with self.subTest(case=name, reader=reader):
                    self.check(document, expected != "invalid", reader)

    def test_origin_source_interval_and_required_fields(self):
        cases = [
            ("diagnostic-android", ("origin",), "manual"),
            ("absent", ("origin",), "systemDiagnostic"),
            ("diagnostic-android", ("systemDiagnostic", "kind"), "crash"),
            ("diagnostic-android", ("systemDiagnostic", "intervalEndMillis"), "9007199254740994"),
            ("diagnostic-android", ("systemDiagnostic", "androidExit"), None),
            ("diagnostic-crash", ("systemDiagnostic", "source"), "androidExitInfo"),
            ("diagnostic-crash", ("systemDiagnostic", "kind"), "nativeCrash"),
            ("diagnostic-hang", ("systemDiagnostic", "metricKit", "hangDurationMillis"), None),
            ("diagnostic-crash", ("systemDiagnostic", "metricKit", "metadata", "appVersion", "isTruncated"), 1),
            ("diagnostic-crash", ("systemDiagnostic", "metricKit", "exceptionCode"), 18446744073709551615),
        ]
        for base, path, value in cases:
            document = fixture(base)
            target = document["metadata"]["incident"]
            for key in path[:-1]:
                target = target[key]
            target[path[-1]] = value
            for reader in (False, True):
                with self.subTest(base=base, path=path, reader=reader):
                    self.check(document, False, reader)
        for base in ("diagnostic-android", "diagnostic-crash"):
            original = fixture(base)
            for field in original["metadata"]["incident"]["systemDiagnostic"]:
                document = copy.deepcopy(original)
                del document["metadata"]["incident"]["systemDiagnostic"][field]
                for reader in (False, True):
                    with self.subTest(base=base, missing=field, reader=reader):
                        self.check(document, False, reader)
        document = fixture("diagnostic-crash")
        document["metadata"]["incident"]["systemDiagnostic"]["androidExit"] = fixture("diagnostic-android")["metadata"]["incident"]["systemDiagnostic"]["androidExit"]
        self.check(document, False)
        self.check(document, False, reader=True)

    def test_metadata_bounds_and_reader_extensions(self):
        for text, accepted in (("汉" * 85 + "a", True), ("汉" * 85 + "ab", False), ("", False), ("\ud800", False)):
            document = fixture("diagnostic-crash")
            document["metadata"]["incident"]["systemDiagnostic"]["metricKit"]["metadata"]["appVersion"]["text"] = text
            for reader in (False, True):
                with self.subTest(text=repr(text), reader=reader):
                    self.check(document, accepted, reader)
        for path in ((), ("systemDiagnostic",), ("systemDiagnostic", "metricKit"),
                     ("systemDiagnostic", "metricKit", "metadata"), ("systemDiagnostic", "metricKit", "metadata", "appVersion")):
            document = fixture("diagnostic-crash")
            target = document["metadata"]["incident"]
            for key in path:
                target = target[key]
            target["futureOptionalField"] = {"opaque": True}
            with self.subTest(extension=path):
                self.check(document, False)
                self.check(document, True, reader=True)


if __name__ == "__main__":
    unittest.main()
