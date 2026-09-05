import sys
import unittest
from pathlib import Path

THIS = Path(__file__).resolve().parent
if str(THIS) not in sys.path:
    sys.path.insert(0, str(THIS))

import final_research_tables as tables


class FinalResearchTablesTest(unittest.TestCase):

    def test_required_sources_exist(self):
        required = [
            "main_descriptive.csv",
            "main_resource_descriptive.csv",
            "prefailure_descriptive.csv",
            "full_transition_descriptive.csv",
            "prefailure_pairwise_carble.csv",
            "full_transition_pairwise_carble.csv",
            "carble_phase_decision_summary.csv",
            "threshold_robustness_descriptive.csv",
            "threshold_robustness_stage_reach.csv",
            "scalability_descriptive.csv",
            "scalability_pairwise_carble.csv",
            "full_resource_descriptive.csv",
        ]

        missing = [
            name
            for name in required
            if not (tables.STATS / name).exists()
        ]

        self.assertEqual(
            missing,
            [],
            msg="Missing table source files:\n" + "\n".join(missing),
        )

    def test_static_design_contains_all_families(self):
        df = tables.table_t01_design()
        self.assertEqual(len(df), 12)
        self.assertIn("FULL", set(df["ID"]))
        self.assertIn("THRESH", set(df["ID"]))
        self.assertIn("SCALE", set(df["ID"]))


if __name__ == "__main__":
    unittest.main()
