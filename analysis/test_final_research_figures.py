import sys
import unittest
from pathlib import Path

THIS = Path(__file__).resolve().parent
if str(THIS) not in sys.path:
    sys.path.insert(0, str(THIS))

import final_research_figures as figs


class FinalResearchFiguresTest(unittest.TestCase):

    def test_all_required_sources_exist(self):

        required = [
            figs.STATS / "main_descriptive.csv",
            figs.STATS / "prefailure_descriptive.csv",
            figs.STATS / "full_transition_descriptive.csv",
            figs.STATS / "main_resource_descriptive.csv",
            figs.STATS / "full_resource_descriptive.csv",
            figs.STATS / "carble_lifecycle_summary.csv",
            figs.STATS / "carble_phase_decision_summary.csv",
            figs.STATS / "threshold_robustness_descriptive.csv",
            figs.STATS / "scalability_descriptive.csv",
            figs.STATS / "scalability_pairwise_carble.csv",
            figs.FULL / "full_carble_transition_events.csv",
        ]

        for path in required:
            self.assertTrue(
                path.exists(),
                msg=f"Missing final-figure source: {path}",
            )

    def test_frozen_orders(self):
        self.assertEqual(
            figs.PROTOCOL_ORDER,
            ["B0", "MM", "2RH", "CARBLE"],
        )

        self.assertEqual(
            figs.MAIN_ORDER,
            ["S01", "S02", "S03", "S04", "S05"],
        )

        self.assertEqual(
            figs.PREF_ORDER,
            [
                "PF_A_M1",
                "PF_B1_M2",
                "PF_B2_M3",
                "PF_C_LOW",
            ],
        )

        self.assertEqual(
            figs.THRESHOLD_ORDER,
            ["EARLY", "NOMINAL", "LATE"],
        )

    def test_phase_schedule(self):
        self.assertEqual(
            figs.PHASE_SUCCESS,
            [0.90, 0.75, 0.60, 0.45, 0.30, 0.15, 0.05],
        )


if __name__ == "__main__":
    unittest.main()
