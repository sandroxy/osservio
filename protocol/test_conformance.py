"""Independent protocol checks, not a native runtime deserializer."""
import copy
import json
import unittest
from jsonschema import Draft202012Validator, ValidationError
from validate import ROOT, validate


FIXTURES = ROOT.parent / "testing" / "incident-export"


def fixture(name):
    return json.loads((FIXTURES / f"{name}.json").read_text(encoding="utf-8"))


class ConformanceTests(unittest.TestCase):
    def check(self, document, accepted, reader=False):
        if accepted:
            validate(document["metadata"], document["payloads"], reader=reader)
        else:
            with self.assertRaises((ValidationError, ValueError)):
                validate(document["metadata"], document["payloads"], reader=reader)

    def test_schemas_are_valid(self):
        for path in sorted(ROOT.glob("*.schema.json")):
            with self.subTest(schema=path.name):
                Draft202012Validator.check_schema(json.loads(path.read_text(encoding="utf-8")))

    def test_shared_native_goldens(self):
        for name in ("absent", "empty", "complete"):
            for reader in (False, True):
                with self.subTest(fixture=name, reader=reader):
                    self.check(fixture(name), True, reader)

    def test_corruption_and_compatibility(self):
        cases = fixture("cases")
        self.assertEqual(len(cases), len({case["name"] for case in cases}))
        for case in cases:
            document = fixture(case["base"])
            for change in case["changes"]:
                target = document
                for part in change["path"][:-1]:
                    target = target[part]
                field = change["path"][-1]
                if change.get("remove"):
                    del target[field]
                else:
                    target[field] = change["value"]
            for reader in (False, True):
                with self.subTest(case=case["name"], reader=reader):
                    self.check(document, case["readerAccepts" if reader else "writerAccepts"], reader)

    def test_http_observation_lifecycle(self):
        for state in fixture("http-lifecycle"):
            document = fixture("complete")
            group = document["metadata"]["incident"]["http"]
            event = group["events"][0]
            for field in ("revision", "state", "statusCode", "failure", "durationMillis"):
                event[field] = state[field]
            event["responseURL"] = None
            event["requestHeaders"] = []
            event["responseHeaders"] = []
            for field in ("requestBody", "responseBody"):
                event[field] = {"state": state["bodyState"], "blobID": None, "observedBytes": "0"}
            group["payloads"], document["payloads"]["http"] = {}, {}
            group["retainedPayloadBytes"] = 0
            group["retainedTextBytes"] = len((event["method"] + event["url"]).encode("utf-8"))
            for reader in (False, True):
                with self.subTest(state=state["name"], reader=reader):
                    self.check(document, True, reader)
                    invalid = copy.deepcopy(document)
                    invalid["metadata"]["incident"]["http"]["events"][0]["revision"] = "7"
                    self.check(invalid, False, reader)

    def test_utf8_boundary_and_non_scalar_text(self):
        for text, accepted in (("汉" * 1365 + "a", True), ("汉" * 1365 + "ab", False), ("\ud800", False)):
            document = fixture("absent")
            document["metadata"]["incident"]["note"] = {"text": text, "isPrivate": False, "isTruncated": False}
            for reader in (False, True):
                with self.subTest(size=len(text), reader=reader):
                    self.check(document, accepted, reader)

    def test_collection_bound_and_duplicate_identity(self):
        document = fixture("complete")
        group = document["metadata"]["incident"]["webSockets"]
        group["events"].append(copy.deepcopy(group["events"][0]))
        group["sourceEventCount"] += 1
        group["retainedTextBytes"] += len(group["events"][0]["url"].encode("utf-8"))
        for reader in (False, True):
            self.check(document, False, reader)
        document = fixture("empty")
        group = document["metadata"]["incident"]["logs"]
        sample = fixture("complete")["metadata"]["incident"]["logs"]["events"][0]
        group["events"] = [dict(sample, sequence=str(index + 1)) for index in range(2001)]
        group["sourceEventCount"], group["omittedEventCount"] = 2001, 0
        group["retainedTextBytes"] = 2001 * len((sample["category"] + sample["message"]).encode("utf-8"))
        for reader in (False, True):
            self.check(document, False, reader)


if __name__ == "__main__":
    unittest.main()
