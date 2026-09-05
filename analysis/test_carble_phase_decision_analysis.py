import sys
import unittest
from pathlib import Path

import numpy as np
import pandas as pd

THIS = Path(__file__).resolve().parent
if str(THIS) not in sys.path:
    sys.path.insert(0, str(THIS))

import carble_phase_decision_analysis as cpda


class CarblePhaseDecisionAnalysisTest(unittest.TestCase):

    def test_phase_metadata_is_frozen(self):
        self.assertEqual(
            cpda.PHASES,
            {
                1: (0, 150, 0.90, 0),
                2: (150, 300, 0.75, 0),
                3: (300, 450, 0.60, 0),
                4: (450, 600, 0.45, 0),
                5: (600, 750, 0.30, 2),
                6: (750, 900, 0.15, 5),
                7: (900, 1050, 0.05, 5),
            },
        )

    def test_regime_escalation_order_is_frozen(self):
        self.assertEqual(
            cpda.REGIME_LEVEL,
            {
                "HIGH": 0,
                "M1": 1,
                "M2": 2,
                "M3": 3,
                "LOW": 4,
            },
        )

    def test_medium_event_requires_stage(self):
        row = pd.Series(
            {
                "regime": "MEDIUM",
                "mediumStage": "M2",
            }
        )
        self.assertEqual(
            cpda._classify_stage(row),
            "M2",
        )

    def test_phase_by_seed_share_reconciliation(self):
        rows = []

        # Synthetic full 30 seeds x 7 phases.
        for seed in range(1, 31):
            for phase in range(1, 8):
                start = cpda.PHASES[phase][0]

                rows.extend(
                    [
                        {
                            "seed": seed,
                            "eventTime": start + 1,
                            "currentHopConfidence": 0.9,
                            "routeConfidence": 0.9,
                            "regime": "HIGH",
                            "mediumStage": np.nan,
                            "stage": "HIGH",
                            "phaseIndex": phase,
                        },
                        {
                            "seed": seed,
                            "eventTime": start + 2,
                            "currentHopConfidence": 0.7,
                            "routeConfidence": 0.7,
                            "regime": "MEDIUM",
                            "mediumStage": "M1",
                            "stage": "M1",
                            "phaseIndex": phase,
                        },
                    ]
                )

        result = cpda.build_phase_by_seed(
            pd.DataFrame(rows)
        )

        self.assertEqual(
            len(result),
            210,
        )

        shares = (
            result[
                [
                    "highShare",
                    "m1Share",
                    "m2Share",
                    "m3Share",
                    "lowShare",
                ]
            ]
            .sum(axis=1)
            .to_numpy(float)
        )

        self.assertTrue(
            np.allclose(
                shares,
                1.0,
            )
        )

        self.assertTrue(
            np.allclose(
                result["meanEscalationLevel"],
                0.5,
            )
        )

    def test_real_event_file_exists(self):
        self.assertTrue(
            cpda.EVENT_FILE.exists(),
            msg=f"Missing frozen CARBLE event file: {cpda.EVENT_FILE}",
        )


if __name__ == "__main__":
    unittest.main()
