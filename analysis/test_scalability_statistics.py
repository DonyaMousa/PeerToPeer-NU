import sys
import unittest
from pathlib import Path

import numpy as np

THIS = Path(__file__).resolve().parent
if str(THIS) not in sys.path:
    sys.path.insert(0, str(THIS))

import scalability_statistics as ss


class ScalabilityStatisticsTest(unittest.TestCase):

    def test_source_exists(self):
        self.assertTrue(
            ss.SOURCE.exists(),
            msg=f"Missing scalability dataset: {ss.SOURCE}",
        )

    def test_real_design_has_1200_rows(self):
        df = ss._read()

        self.assertEqual(
            len(df),
            1200,
        )

    def test_each_cell_has_30_seeds_per_protocol(self):
        df = ss._read()

        for topology in ss.TOPOLOGIES:
            for node_count in ss.NODE_COUNTS:
                for protocol in ss.PROTOCOLS:
                    cell = df[
                        (df["topology"] == topology)
                        & (df["nodeCount"] == node_count)
                        & (df["protocol"] == protocol)
                    ]

                    self.assertEqual(
                        len(cell),
                        30,
                    )

    def test_holm_three_tests(self):
        adjusted = ss._holm(
            [0.01, 0.03, 0.04]
        )

        self.assertTrue(
            all(
                0.0 <= p <= 1.0
                for p in adjusted
            )
        )

        self.assertAlmostEqual(
            adjusted[0],
            0.03,
            places=12,
        )

    def test_rank_biserial_direction(self):
        self.assertAlmostEqual(
            ss._rank_biserial(
                np.array(
                    [1.0, 2.0, 3.0]
                )
            ),
            1.0,
            places=12,
        )

        self.assertAlmostEqual(
            ss._rank_biserial(
                np.array(
                    [-1.0, -2.0, -3.0]
                )
            ),
            -1.0,
            places=12,
        )


if __name__ == "__main__":
    unittest.main()
