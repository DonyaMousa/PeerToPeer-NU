import tempfile
import unittest
from pathlib import Path
import sys

import numpy as np
import pandas as pd

# Allow importing the sibling analysis module when run from project root.
THIS = Path(__file__).resolve().parent
if str(THIS) not in sys.path:
    sys.path.insert(0, str(THIS))

import final_simulation_statistics as fss


class FinalSimulationStatisticsUnitTest(unittest.TestCase):

    def test_holm_adjust_known_ordering(self):
        p = [0.01, 0.04, 0.03]
        adj = fss._holm_adjust(p)

        self.assertEqual(len(adj), 3)
        self.assertTrue(all(0.0 <= x <= 1.0 for x in adj))

        # Holm adjusted values for sorted p=.01,.03,.04:
        # .03, .06, .06, then mapped back.
        self.assertAlmostEqual(adj[0], 0.03, places=12)
        self.assertAlmostEqual(adj[1], 0.06, places=12)
        self.assertAlmostEqual(adj[2], 0.06, places=12)

    def test_rank_biserial_direction(self):
        self.assertAlmostEqual(
            fss._rank_biserial(np.array([1.0, 2.0, 3.0])),
            1.0,
            places=12,
        )
        self.assertAlmostEqual(
            fss._rank_biserial(np.array([-1.0, -2.0, -3.0])),
            -1.0,
            places=12,
        )
        self.assertAlmostEqual(
            fss._rank_biserial(np.array([0.0, 0.0])),
            0.0,
            places=12,
        )

    def test_jain_index(self):
        self.assertAlmostEqual(
            fss._jain([10.0, 10.0, 10.0]),
            1.0,
            places=12,
        )
        self.assertLess(
            fss._jain([30.0, 0.0, 0.0]),
            0.34,
        )

    def test_descriptive_uses_runs_not_packets(self):
        rows = []
        for protocol in ["B0", "MM", "2RH", "CARBLE"]:
            for seed in range(1, 31):
                rows.append(
                    {
                        "scenario": "S01",
                        "protocol": protocol,
                        "seed": seed,
                        "pdr": 0.9,
                    }
                )

        df = pd.DataFrame(rows)
        table = fss.descriptive_table(
            df,
            "scenario",
            ["pdr"],
        )

        self.assertEqual(len(table), 4)
        self.assertTrue((table["n"] == 30).all())

    def test_pairwise_preserves_seed_pairing(self):
        rows = []
        for seed in range(1, 31):
            rows.extend(
                [
                    {
                        "scenario": "S01",
                        "protocol": "B0",
                        "seed": seed,
                        "pdr": 0.80,
                    },
                    {
                        "scenario": "S01",
                        "protocol": "MM",
                        "seed": seed,
                        "pdr": 0.82,
                    },
                    {
                        "scenario": "S01",
                        "protocol": "2RH",
                        "seed": seed,
                        "pdr": 0.85,
                    },
                    {
                        "scenario": "S01",
                        "protocol": "CARBLE",
                        "seed": seed,
                        "pdr": 0.90,
                    },
                ]
            )

        df = pd.DataFrame(rows)
        result = fss.pairwise_carble_table(
            df,
            "scenario",
            ["pdr"],
            "TEST",
        )

        self.assertEqual(len(result), 3)
        self.assertTrue((result["nPairedSeeds"] == 30).all())

        b0 = result[result["baseline"] == "B0"].iloc[0]
        self.assertAlmostEqual(
            b0["meanPairedDifference_CARBLEminusBaseline"],
            0.10,
            places=12,
        )
        self.assertAlmostEqual(
            b0["pdrDifferencePercentagePoints"],
            10.0,
            places=12,
        )

    def test_validate_paired_design_rejects_missing_seed(self):
        rows = []
        for protocol in ["B0", "MM", "2RH", "CARBLE"]:
            for seed in range(1, 31):
                if protocol == "B0" and seed == 30:
                    continue
                rows.append(
                    {
                        "scenario": "S01",
                        "protocol": protocol,
                        "seed": seed,
                    }
                )

        with self.assertRaises(ValueError):
            fss.validate_paired_design(
                pd.DataFrame(rows),
                group_col="scenario",
                expected_groups=["S01"],
                family_name="TEST",
            )


if __name__ == "__main__":
    unittest.main()
