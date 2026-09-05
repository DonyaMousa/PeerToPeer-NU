import sys
import unittest
from pathlib import Path

THIS = Path(__file__).resolve().parent
if str(THIS) not in sys.path:
    sys.path.insert(0, str(THIS))

import final_simulation_figures as figs


class FinalSimulationFiguresTest(unittest.TestCase):

    def test_required_sources_exist(self):
        required = [
            figs.STATS / "main_descriptive.csv",
            figs.STATS / "prefailure_descriptive.csv",
            figs.STATS / "full_transition_descriptive.csv",
            figs.STATS / "carble_lifecycle_summary.csv",
            figs.STATS / "full_resource_descriptive.csv",
            figs.STATS / "carble_phase_decision_summary.csv",
            figs.STATS / "threshold_robustness_descriptive.csv",
            figs.STATS / "scalability_descriptive.csv",
            figs.FULL / "full_carble_transition_events.csv",
        ]

        for path in required:
            self.assertTrue(
                path.exists(),
                msg=f"Missing figure source: {path}",
            )

    def test_protocol_order_is_frozen(self):
        self.assertEqual(
            figs.PROTOCOL_ORDER,
            ["B0", "MM", "2RH", "CARBLE"],
        )

    def test_prefailure_order_is_frozen(self):
        self.assertEqual(
            figs.PREF_ORDER,
            [
                "PF_A_M1",
                "PF_B1_M2",
                "PF_B2_M3",
                "PF_C_LOW",
            ],
        )


if __name__ == "__main__":
    unittest.main()
