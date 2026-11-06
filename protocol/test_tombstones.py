"""Independent address-evidence and version checks for Android system tombstones."""
import copy
import unittest
from jsonschema import ValidationError
from test_conformance import fixture
from validate import ROOT, validate, validators


class TombstoneTests(unittest.TestCase):
    def check(self, document, accepted, reader=False):
        if accepted:
            validate(document["metadata"], document["payloads"], reader=reader)
        else:
            with self.assertRaises((ValidationError, ValueError)):
                validate(document["metadata"], document["payloads"], reader=reader)

    def test_shared_restoration_bounds(self):
        for row in (ROOT.parent / "testing/android-tombstone-cases.tsv").read_text().splitlines():
            name, field, value, expected = row.split("\t")
            document = fixture("diagnostic-tombstone")
            trace = document["metadata"]["incident"]["systemDiagnostic"]["androidExit"]["tombstone"]
            if field in ("architecture", "signal", "code", "thread"):
                trace[{"architecture": "architectureCode", "signal": "signalNumber", "code": "signalCode", "thread": "threadID"}[field]] = int(value)
            elif field == "address":
                trace["faultAddress"] = None if value == "-" else value
            elif field in ("pc", "relative_pc"):
                trace["frames"][0]["programCounter" if field == "pc" else "relativeProgramCounter"] = value
            elif field == "build_id":
                trace["frames"][0]["buildID"] = None if value == "-" else "" if value == "empty" else "a" * int(value) if value in ("128", "130") else value
            elif field == "frames":
                trace["frames"] = [trace["frames"][0]] * int(value)
            else:
                self.fail("Unknown tombstone fixture field")
            for reader in (False, True):
                with self.subTest(case=name, reader=reader):
                    self.check(document, expected == "valid", reader)

    def test_shared_source_and_state_invariants(self):
        for row in (ROOT.parent / "testing/android-tombstone-states.tsv").read_text().splitlines():
            name, reason, state, present, expected = row.split("\t")
            document = fixture("diagnostic-tombstone")
            diagnostic = document["metadata"]["incident"]["systemDiagnostic"]
            diagnostic["kind"] = "nativeCrash" if reason == "5" else "managedCrash"
            diagnostic["androidExit"].update(reasonCode=int(reason), tombstoneState=state)
            if present == "false":
                diagnostic["androidExit"]["tombstone"] = None
            for reader in (False, True):
                with self.subTest(case=name, reader=reader):
                    self.check(document, expected == "valid", reader)

    def test_required_fields_and_exact_types(self):
        original = fixture("diagnostic-tombstone")
        for path in (("androidExit",), ("androidExit", "tombstone"), ("androidExit", "tombstone", "frames", 0)):
            value = original["metadata"]["incident"]["systemDiagnostic"]
            for key in path:
                value = value[key]
            for field in value:
                document = copy.deepcopy(original)
                target = document["metadata"]["incident"]["systemDiagnostic"]
                for key in path:
                    target = target[key]
                del target[field]
                for reader in (False, True):
                    with self.subTest(path=path, missing=field, reader=reader):
                        self.check(document, False, reader)
        for field, value in (("architectureCode", True), ("signalCode", 2**31), ("threadID", "99"), ("faultAddress", 2**64 - 1)):
            document = copy.deepcopy(original)
            document["metadata"]["incident"]["systemDiagnostic"]["androidExit"]["tombstone"][field] = value
            for reader in (False, True):
                self.check(document, False, reader)
        for build_id in ("ab\n", "aB", "\u0000", "😀", "ab "):
            document = copy.deepcopy(original)
            document["metadata"]["incident"]["systemDiagnostic"]["androidExit"]["tombstone"]["frames"][0]["buildID"] = build_id
            for reader in (False, True):
                self.check(document, False, reader)

    def test_reader_extensions_do_not_relax_known_constraints(self):
        document = fixture("diagnostic-tombstone")
        trace = document["metadata"]["incident"]["systemDiagnostic"]["androidExit"]["tombstone"]
        trace["optionalFutureFact"] = True
        trace["frames"][0]["optionalFutureFact"] = 1
        self.check(document, False)
        self.check(document, True, reader=True)
        trace["frames"][0]["programCounter"] = "01"
        self.check(document, False, reader=True)

    def test_preserved_revision_three_and_explicit_revision_selection(self):
        names = ("absent", "empty", "complete", "diagnostic-android", "diagnostic-crash", "diagnostic-hang", "diagnostic-signal")
        for name in names:
            for reader in (False, True):
                self.check(fixture("revisions/3/" + name), True, reader)
                for validator, document in zip(validators(reader, revision=3), fixture(name).values()):
                    with self.assertRaises(ValidationError):
                        validator.validate(document)
                mixed = fixture(name)
                mixed["payloads"]["revision"] = 3
                self.check(mixed, False, reader)
        old = fixture("revisions/3/diagnostic-android")
        old["metadata"]["incident"]["systemDiagnostic"]["androidExit"]["tombstone"] = {"futureField": True}
        self.check(old, False)
        self.check(old, True, reader=True)
