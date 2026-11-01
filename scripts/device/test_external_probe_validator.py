import unittest
from check_external_probe import validate


def report():
    events = []
    for provider in ("gps", "network", "fused"):
        for api in ("last", "current", "updates"):
            for t in (range(1_000, 60_000, 1_000) if api == "updates" else [1_000]):
                events.append(dict(provider=provider, api=api, matchesExpected=True,
                                   reportedProvider=provider, elapsedMs=t))
    events += [dict(provider="webview", api="geolocation", matchesExpected=True),
               dict(provider="probe", api="complete", elapsedMs=60_000)]
    return dict(durationMs=60_000, events=events)


class SustainedDeliveryTest(unittest.TestCase):
    def test_continuous_delivery_passes(self):
        self.assertEqual([], validate(report()))

    def test_first_three_frames_cannot_hide_later_stall(self):
        data = report()
        data["events"] = [e for e in data["events"] if e["api"] != "updates" or e["elapsedMs"] <= 3_000]
        self.assertEqual(3, len(validate(data)))

    def test_late_real_fix_is_rejected(self):
        data = report()
        next(e for e in data["events"] if e.get("elapsedMs") == 35_000)["matchesExpected"] = False
        self.assertTrue(validate(data))

    def test_temporary_gap_is_rejected_even_if_last_frame_arrives(self):
        data = report()
        data["events"] = [e for e in data["events"] if not (e["api"] == "updates" and 10_000 < e["elapsedMs"] < 25_000)]
        self.assertEqual(3, len(validate(data)))

    def test_early_completion_is_rejected(self):
        data = report()
        data["events"][-1]["elapsedMs"] = 3_000
        self.assertTrue(validate(data))


if __name__ == "__main__":
    unittest.main()
