import sys
import unittest
from pathlib import Path

THIS = Path(__file__).resolve().parent
if str(THIS) not in sys.path:
    sys.path.insert(0, str(THIS))

import publication_simulation_figures as pf


class PublicationSimulationFiguresTest(unittest.TestCase):

    def test_required_sources_exist(self):

        required = [
            pf.STATS / "main_descriptive.csv",
            pf.STATS / "prefailure_descriptive.csv",
            pf.STATS / "full_transition_descriptive.csv",
            pf.STATS / "carble_lifecycle_summary.csv",
            pf.STATS / "full_resource_descriptive.csv",
            pf.STATS / "carble_phase_decision_summary.csv",
            pf.STATS / "threshold_robustness_descriptive.csv",
            pf.STATS / "scalability_descriptive.csv",
            pf.FULL / "full_carble_transition_events.csv",
        ]

        for path in required:
            self.assertTrue(
                path.exists(),
                msg=f"Missing publication-figure source: {path}",
            )

    def test_protocol_order_is_frozen(self):
        self.assertEqual(
            pf.PROTOCOL_ORDER,
            ["B0", "MM", "2RH", "CARBLE"],
        )

    def test_controlled_conditions_are_not_ordinally_connected(self):
        self.assertEqual(
            pf.PREF_ORDER,
            [
                "PF_A_M1",
                "PF_B1_M2",
                "PF_B2_M3",
                "PF_C_LOW",
            ],
        )

    def test_phase_success_schedule_is_frozen(self):
        self.assertEqual(
            pf.PHASE_SUCCESS,
            {
                1: 0.90,
                2: 0.75,
                3: 0.60,
                4: 0.45,
                5: 0.30,
                6: 0.15,
                7: 0.05,
            },
        )


if __name__ == "__main__":
    unittest.main()
