import unittest

from worker.ptk_normalization import (
    _clean_number,
    normalize_curve,
    normalize_profile,
    validate_result_contract,
)


class ArrayLikeColumn:
    def __init__(self, values):
        self.values = tuple(values)

    def __iter__(self):
        return iter(self.values)

    def __len__(self):
        return len(self.values)

    def __getitem__(self, index):
        return self.values[index]


class CleanNumberTests(unittest.TestCase):
    def test_converts_finite_values_and_rejects_invalid_values(self):
        self.assertEqual(12.5, _clean_number("12.5"))
        for value in (None, "bad", float("nan"), float("inf"), float("-inf")):
            with self.subTest(value=value):
                self.assertIsNone(_clean_number(value))


class CurveNormalizationTests(unittest.TestCase):
    def test_filters_invalid_points_without_sorting_or_deduplicating(self):
        curve = [
            {"Result.LiquidRate": 30, "Result.Pressure": 300},
            {"Result.LiquidRate": None, "Result.Pressure": 200},
            {"Result.LiquidRate": 10, "Result.Pressure": "bad"},
            {"Result.LiquidRate": float("nan"), "Result.Pressure": 275},
            {"Result.LiquidRate": 25, "Result.Pressure": float("inf")},
            {"Result.Pressure": 225},
            {"Result.LiquidRate": 15},
            {"Result.LiquidRate": 30, "Result.Pressure": 300},
            {"Result.LiquidRate": 20, "Result.Pressure": 250},
        ]

        self.assertEqual(
            [
                {"flow": 30.0, "pressure": 300.0},
                {"flow": 30.0, "pressure": 300.0},
                {"flow": 20.0, "pressure": 250.0},
            ],
            normalize_curve(curve, is_gas=False),
        )
        self.assertEqual(
            [],
            normalize_curve(
                {"LiquidRate": [1, 2], "Pressure": [10]},
                is_gas=False,
            ),
        )

    def test_selects_gas_rate_and_rejects_missing_required_columns(self):
        gas_curve = [{"GasRate": "1.5", "Pressure": "250"}]
        self.assertEqual(
            [{"flow": 1.5, "pressure": 250.0}],
            normalize_curve(gas_curve, is_gas=True),
        )
        self.assertEqual([], normalize_curve([{"Pressure": 1}], is_gas=True))
        self.assertEqual([], normalize_curve([{"GasRate": 1}], is_gas=False))

    def test_accepts_column_oriented_data_without_external_packages(self):
        curve = {
            "Result.LiquidRate": [3, None, 1],
            "Result.Pressure": [30, 20, 10],
        }
        self.assertEqual(
            [
                {"flow": 3.0, "pressure": 30.0},
                {"flow": 1.0, "pressure": 10.0},
            ],
            normalize_curve(curve, is_gas=False),
        )

    def test_matches_dataframe_for_numpy_like_and_iterable_columns(self):
        curve = {
            "Result.GasRate": ArrayLikeColumn([4, float("nan"), 2]),
            "Result.Pressure": (value for value in [40, 30, 20]),
        }
        self.assertEqual(
            [
                {"flow": 4.0, "pressure": 40.0},
                {"flow": 2.0, "pressure": 20.0},
            ],
            normalize_curve(curve, is_gas=True),
        )

    def test_matches_dataframe_dict_mapping_index_union_and_alignment(self):
        curve = {
            "Result.LiquidRate": {
                "row-b": 2,
                "row-a": 1,
                "flow-only": 9,
            },
            "Result.Pressure": {
                "row-c": 30,
                "row-a": 10,
                "row-b": 20,
            },
        }
        self.assertEqual(
            [
                {"flow": 2.0, "pressure": 20.0},
                {"flow": 1.0, "pressure": 10.0},
            ],
            normalize_curve(curve, is_gas=False),
        )

        mixed_mapping_and_positional = {
            "LiquidRate": {"second": 2, "first": 1},
            "Pressure": ArrayLikeColumn([20, 10]),
        }
        self.assertEqual(
            [
                {"flow": 2.0, "pressure": 20.0},
                {"flow": 1.0, "pressure": 10.0},
            ],
            normalize_curve(mixed_mapping_and_positional, is_gas=False),
        )


class ProfileNormalizationTests(unittest.TestCase):
    def test_uses_absolute_depth_stable_sort_and_zero_temperature_default(self):
        profile = [
            {"MeasuredDepth": -20, "Pressure": 200, "Temperature": 70},
            {"MeasuredDepth": 10, "Pressure": 100, "Temperature": None},
            {"MeasuredDepth": -10, "Pressure": 110, "Temperature": "bad"},
            {"MeasuredDepth": "bad", "Pressure": 300, "Temperature": 90},
            {"MeasuredDepth": 30, "Pressure": float("inf"), "Temperature": 100},
            {"MeasuredDepth": float("nan"), "Pressure": 400, "Temperature": 110},
            {"MeasuredDepth": 25, "Temperature": 80},
            {"Pressure": 250, "Temperature": 80},
            {"MeasuredDepth": 15, "Pressure": 150, "Temperature": float("nan")},
            {"MeasuredDepth": 16, "Pressure": 160, "Temperature": float("inf")},
        ]

        self.assertEqual(
            [
                {"depth": 10.0, "pressure": 100.0, "temperature": 0.0},
                {"depth": 10.0, "pressure": 110.0, "temperature": 0.0},
                {"depth": 15.0, "pressure": 150.0, "temperature": 0.0},
                {"depth": 16.0, "pressure": 160.0, "temperature": 0.0},
                {"depth": 20.0, "pressure": 200.0, "temperature": 70.0},
            ],
            normalize_profile(profile),
        )

    def test_unwraps_first_profile_mapping_and_defaults_missing_temperature_column(self):
        profile = {"Well_1": [{"Elevation": -5, "Pressure": 50}]}
        self.assertEqual(
            [{"depth": 5.0, "pressure": 50.0, "temperature": 0.0}],
            normalize_profile(profile),
        )

    def test_matches_dataframe_for_nested_column_oriented_profile(self):
        profile = {
            "Well_1": {
                "Result.MeasuredDepth": ArrayLikeColumn([-20, -10, 10]),
                "Result.Pressure": (value for value in [200, 110, 100]),
                "Result.Temperature": ArrayLikeColumn([70, None, 60]),
            }
        }
        self.assertEqual(
            [
                {"depth": 10.0, "pressure": 110.0, "temperature": 0.0},
                {"depth": 10.0, "pressure": 100.0, "temperature": 60.0},
                {"depth": 20.0, "pressure": 200.0, "temperature": 70.0},
            ],
            normalize_profile(profile),
        )

    def test_matches_dataframe_nested_dict_mapping_index_union_and_alignment(self):
        profile = {
            "Well_1": {
                "Result.MeasuredDepth": {
                    "station-z": -10,
                    "station-a": 10,
                    "station-m": -20,
                },
                "Result.Pressure": {
                    "station-a": 100,
                    "station-m": 200,
                    "station-z": 300,
                    "pressure-only": 400,
                },
                "Result.Temperature": {
                    "station-a": 60,
                    "station-z": 80,
                },
            }
        }
        self.assertEqual(
            [
                {"depth": 10.0, "pressure": 300.0, "temperature": 80.0},
                {"depth": 10.0, "pressure": 100.0, "temperature": 60.0},
                {"depth": 20.0, "pressure": 200.0, "temperature": 0.0},
            ],
            normalize_profile(profile),
        )


class ResultContractTests(unittest.TestCase):
    def test_nodal_and_profile_contracts(self):
        point = [{"value": 1}]
        self.assertEqual("VALID_FULL", validate_result_contract("nodal", point, point, []))
        self.assertEqual("INVALID_EMPTY_NODAL", validate_result_contract("nodal", [], point, []))
        self.assertEqual("INVALID_EMPTY_NODAL", validate_result_contract("nodal", point, [], []))
        self.assertEqual("VALID_FULL", validate_result_contract("profile", [], [], point))
        self.assertEqual("INVALID_EMPTY_PROFILE", validate_result_contract("profile", [], [], []))

    def test_combined_full_partial_and_invalid_contracts(self):
        point = [{"value": 1}]
        self.assertEqual("VALID_FULL", validate_result_contract("combined", point, point, point))
        self.assertEqual("VALID_PARTIAL", validate_result_contract("combined", point, point, []))
        self.assertEqual(
            "INVALID_EMPTY_NODAL", validate_result_contract("combined", [], point, point)
        )
        self.assertEqual(
            "INVALID_EMPTY_NODAL", validate_result_contract("combined", point, [], point)
        )

    def test_rejects_unknown_run_task(self):
        with self.assertRaises(ValueError):
            validate_result_contract("unknown", [], [], [])


if __name__ == "__main__":
    unittest.main()
