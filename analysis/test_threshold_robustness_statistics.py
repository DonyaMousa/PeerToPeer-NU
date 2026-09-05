import sys
import unittest
from pathlib import Path

import numpy as np
import pandas as pd

THIS = Path(__file__).resolve().parent
if str(THIS) not in sys.path:
    sys.path.insert(0, str(THIS))

import threshold_robustness_statistics as trs


class ThresholdRobustnessStatisticsTest(unittest.TestCase):

    def test_source_exists(self):
        self.assertTrue(
            trs.SOURCE.exists(),
            msg=f"Missing robustness dataset: {trs.SOURCE}",
        )

    def test_holm_two_tests(self):
        adjusted = trs._holm(
            [0.01, 0.04]
        )

        self.assertAlmostEqual(
            adjusted[0],
            0.02,
            places=12,
        )

        self.assertAlmostEqual(
            adjusted[1],
            0.04,
            places=12,
        )

    def test_rank_biserial_direction(self):
        self.assertAlmostEqual(
            trs._rank_biserial(
                np.array(
                    [1.0, 2.0, 3.0]
                )
            ),
            1.0,
            places=12,
        )

        self.assertAlmostEqual(
            trs._rank_biserial(
                np.array(
                    [-1.0, -2.0, -3.0]
                )
            ),
            -1.0,
            places=12,
        )

    def test_real_design_is_90_rows(self):
        df = trs._read()

        self.assertEqual(
            len(df),
            90,
        )

        for config in trs.CONFIGS:
            self.assertEqual(
                len(
                    df[
                        df["thresholdConfig"]
                        == config
                    ]
                ),
                30,
            )


if __name__ == "__main__":
    unittest.main()
