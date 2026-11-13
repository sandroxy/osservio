"""Structured-log selection, compatibility and corruption are independent of native encoders."""
import copy
import unittest
from jsonschema import ValidationError
from test_conformance import FIXTURES, fixture
from validate import validate, validators


class StructuredLogTests(unittest.TestCase):
    def check(self, document, accepted):
        for reader in (False, True):
            with self.subTest(reader=reader):
                if accepted:
                    validate(document["metadata"], document["payloads"], reader)
                else:
                    with self.assertRaises((ValueError, ValidationError)):
                        validate(document["metadata"], document["payloads"], reader)

    def test_previous_revision_and_unknown_optional_fields(self):
        for path in sorted((FIXTURES / "revisions" / "5").glob("*.json")):
            self.check(fixture("revisions/5/" + path.stem), True)
        current = fixture("structured-log")
        for reader in (False, True):
            for validator, document in zip(validators(reader, 5), current.values()):
                with self.assertRaises(ValidationError): validator.validate(document)
        future = copy.deepcopy(current)
        future["metadata"]["incident"]["logs"]["events"][0]["details"]["futureOptional"] = True
        validate(future["metadata"], future["payloads"], reader=True)
        with self.assertRaises(ValidationError): validate(future["metadata"], future["payloads"])

    def test_content_selection_states_and_no_invented_bytes(self):
        source = fixture("structured-log")
        self.check(source, True)
        blob = next(iter(source["payloads"]["logs"]))
        for state in ("notSelected", "unavailable", "busy", "budgetExceeded"):
            document = copy.deepcopy(source); group = document["metadata"]["incident"]["logs"]
            group["payloads"][blob] = {"state": state, "textByteCount": 0}; group["retainedPayloadBytes"] = 0
            document["payloads"]["logs"] = {}
            self.check(document, True)
        for target, value in (("blobID", None), ("textByteCount", 0), ("textByteCount", 1), ("state", "limited")):
            document = copy.deepcopy(source)
            document["metadata"]["incident"]["logs"]["events"][0]["details"][target] = value
            self.check(document, False)
        for mutation in ("missingSelection", "missingContent", "extraContent", "badTotal", "duplicateReference"):
            document = copy.deepcopy(source); group = document["metadata"]["incident"]["logs"]
            if mutation == "missingSelection": group["payloads"] = {}
            elif mutation == "missingContent": document["payloads"]["logs"] = {}
            elif mutation == "extraContent": document["payloads"]["logs"]["dddddddd-dddd-dddd-dddd-dddddddddddd"] = "x"
            elif mutation == "badTotal": group["retainedPayloadBytes"] += 1
            else: group["events"][1]["details"] = copy.deepcopy(group["events"][0]["details"])
            self.check(document, False)


if __name__ == "__main__": unittest.main()
