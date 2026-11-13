"""Repeated, overlapping, absent, and incomplete phase evidence must survive independently."""
import copy
import unittest
from jsonschema import ValidationError
from test_conformance import FIXTURES, fixture
from validate import validate, validators


class HTTPMetricsTests(unittest.TestCase):
    def test_previous_revision_and_unknown_optional_fields(self):
        for path in sorted((FIXTURES / "revisions" / "6").glob("*.json")):
            doc = fixture("revisions/6/" + path.stem)
            for reader in (False, True): validate(doc["metadata"], doc["payloads"], reader)
        current = fixture("http-metrics")
        for reader in (False, True):
            validate(current["metadata"], current["payloads"], reader)
            for validator, doc in zip(validators(reader, 6), current.values()):
                with self.assertRaises(ValidationError): validator.validate(doc)
        current["metadata"]["incident"]["http"]["events"][0]["metrics"]["future"] = True
        validate(current["metadata"], current["payloads"], True)
        with self.assertRaises(ValidationError): validate(current["metadata"], current["payloads"])

    def test_invalid_phases_and_unknown_are_not_zero(self):
        source = fixture("http-metrics")
        for mutation in ("backwards", "negative", "tooMany", "missingEnd", "wrongType", "protocolLimit", "byteCount"):
            current = copy.deepcopy(source); group = current["metadata"]["incident"]["http"]
            metrics = group["events"][0]["metrics"]
            if mutation == "backwards": metrics["phases"][0]["endMillis"] = "-1"
            elif mutation == "negative": metrics["phases"][0]["startMillis"] = "-1"
            elif mutation == "tooMany": metrics["phases"] *= 5
            elif mutation == "missingEnd": del metrics["phases"][0]["endMillis"]
            elif mutation == "wrongType": metrics["phases"][0]["startMillis"] = 0
            elif mutation == "protocolLimit": metrics["protocol"] = "a" * 33
            else: group["retainedTextBytes"] -= 1
            for reader in (False, True):
                with self.subTest(mutation=mutation, reader=reader), self.assertRaises((ValidationError, ValueError)):
                    validate(current["metadata"], current["payloads"], reader)
        current = copy.deepcopy(source); group = current["metadata"]["incident"]["http"]
        group["events"][0]["metrics"] = {"protocol": None, "cache": "unknown", "connectionReused": None, "limited": False, "phases": []}
        group["retainedTextBytes"] -= 2
        validate(current["metadata"], current["payloads"])


if __name__ == "__main__": unittest.main()
