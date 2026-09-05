import sys
import unittest
from pathlib import Path

import numpy as np
import pandas as pd

THIS = Path(__file__).resolve().parent
if str(THIS) not in sys.path:
    sys.path.insert(0, str(THIS))

import final_simulation_figures as fsf


class FinalSimulationFiguresTest(unittest.TestCase):

    def test_binned_confidence_does_not_double_weight_seed_events(self):
        rows = [
            # seed 1 has two events in same bin
            {
                "seed": 1,
                "eventTime": 10,
                "currentHopConfidence": 0.9,
                "routeConfidence": 0.8,
            },
            {
                "seed": 1,
                "eventTime": 15,
                "currentHopConfidence": 0.7,
                "routeConfidence": 0.6,
            },
            # seed 2 has one event in same bin
            {
                "seed": 2,
                "eventTime": 12,
                "currentHopConfidence": 0.6,
                "routeConfidence": 0.5,
            },
        ]

        out = fsf._binned_confidence(
            pd.DataFrame(rows),
            bin_width=25,
        )

        self.assertEqual(len(out), 1)
        self.assertEqual(int(out.iloc[0]["seeds"]), 2)

        # seed 1 median qCurrent=.8; seed 2=.6;
        # median across seeds=.7.
        self.assertAlmostEqual(
            float(out.iloc[0]["qCurrentMedian"]),
            0.7,
            places=12,
        )

    def test_thresholds_are_frozen(self):
        self.assertEqual(
            [x[0] for x in fsf.THRESHOLDS],
            [0.75, 0.65, 0.55, 0.45],
        )

    def test_protocol_order_is_frozen(self):
        self.assertEqual(
            fsf.PROTOCOL_ORDER,
            ["B0", "MM", "2RH", "CARBLE"],
        )

    def test_expected_figure_sources_exist(self):
        required = [
            fsf.STATS / "main_descriptive.csv",
            fsf.STATS / "prefailure_descriptive.csv",
            fsf.STATS / "full_transition_descriptive.csv",
            fsf.STATS / "carble_lifecycle_summary.csv",
            fsf.STATS / "full_resource_descriptive.csv",
            fsf.FULL / "full_carble_transition_events.csv",
        ]

        missing = [str(p) for p in required if not p.exists()]
        self.assertEqual(
            missing,
            [],
            msg="Missing frozen figure source files:\n" + "\n".join(missing),
        )


if __name__ == "__main__":
    unittest.main()
