def _clean_number(value):
    """Return a finite float, or None for values Avalonia discards."""
    try:
        number = float(value)
        if number != number or number in (float("inf"), float("-inf")):
            return None
        return number
    except Exception:
        return None


def normalize_curve(curve_data, is_gas):
    try:
        if hasattr(curve_data, "to_dict") and not isinstance(curve_data, dict):
            curve_data = curve_data.to_dict(orient="records")
        if isinstance(curve_data, dict):
            columns = list(curve_data)
            values = []
            row_index = []
            seen_index = {}
            for column in columns:
                value = curve_data[column]
                if isinstance(value, dict):
                    values.append(value)
                    for index in value:
                        if index not in seen_index:
                            seen_index[index] = None
                            row_index.append(index)
                    continue
                if isinstance(value, (str, bytes)):
                    return []
                values.append(list(value))
            if not values:
                return []
            if row_index:
                row_count = len(row_index)
                if any(
                    not isinstance(value, dict) and len(value) != row_count
                    for value in values
                ):
                    return []
                curve_data = [
                    {
                        column: (
                            values[column_index].get(index)
                            if isinstance(values[column_index], dict)
                            else values[column_index][position]
                        )
                        for column_index, column in enumerate(columns)
                    }
                    for position, index in enumerate(row_index)
                ]
            else:
                row_count = len(values[0])
                if any(isinstance(value, dict) or len(value) != row_count for value in values):
                    return []
                curve_data = [
                    {
                        column: values[column_index][position]
                        for column_index, column in enumerate(columns)
                    }
                    for position in range(row_count)
                ]

        rows = []
        columns = []
        for source_row in curve_data:
            normalized_row = {
                str(column).split(".")[-1].lower(): value
                for column, value in source_row.items()
            }
            rows.append(normalized_row)
            for column in normalized_row:
                if column not in columns:
                    columns.append(column)

        flow_phase = "gas" if is_gas else "liquid"
        flow_column = next(
            (column for column in columns if flow_phase in column and "rate" in column),
            None,
        )
        pressure_column = next((column for column in columns if "pressure" in column), None)
        if flow_column is None or pressure_column is None:
            return []

        normalized = []
        for row in rows:
            flow = _clean_number(row.get(flow_column))
            pressure = _clean_number(row.get(pressure_column))
            if flow is None or pressure is None:
                continue
            normalized.append({"flow": flow, "pressure": pressure})
        return normalized
    except Exception:
        return []


def normalize_profile(profile):
    try:
        if isinstance(profile, dict) and profile:
            profile = profile[list(profile.keys())[0]]
        if hasattr(profile, "to_dict") and not isinstance(profile, dict):
            profile = profile.to_dict(orient="records")
        if isinstance(profile, dict):
            columns = list(profile)
            values = []
            row_index = []
            seen_index = {}
            for column in columns:
                value = profile[column]
                if isinstance(value, dict):
                    values.append(value)
                    for index in value:
                        if index not in seen_index:
                            seen_index[index] = None
                            row_index.append(index)
                    continue
                if isinstance(value, (str, bytes)):
                    return []
                values.append(list(value))
            if not values:
                return []
            if row_index:
                row_count = len(row_index)
                if any(
                    not isinstance(value, dict) and len(value) != row_count
                    for value in values
                ):
                    return []
                profile = [
                    {
                        column: (
                            values[column_index].get(index)
                            if isinstance(values[column_index], dict)
                            else values[column_index][position]
                        )
                        for column_index, column in enumerate(columns)
                    }
                    for position, index in enumerate(row_index)
                ]
            else:
                row_count = len(values[0])
                if any(isinstance(value, dict) or len(value) != row_count for value in values):
                    return []
                profile = [
                    {
                        column: values[column_index][position]
                        for column_index, column in enumerate(columns)
                    }
                    for position in range(row_count)
                ]

        source_rows = []
        columns = []
        for source_row in profile:
            normalized_row = {
                str(column).split(".")[-1].lower(): value
                for column, value in source_row.items()
            }
            source_rows.append(normalized_row)
            for column in normalized_row:
                if column not in columns:
                    columns.append(column)

        pressure_column = next((column for column in columns if "pressure" in column), None)
        depth_column = next(
            (
                column
                for column in columns
                if "depth" in column or "md" in column or "elevation" in column
            ),
            None,
        )
        temperature_column = next(
            (column for column in columns if "temperature" in column or "temp" in column),
            None,
        )
        if pressure_column is None or depth_column is None:
            return []

        rows = []
        for row in source_rows:
            depth = _clean_number(row.get(depth_column))
            pressure = _clean_number(row.get(pressure_column))
            if depth is None or pressure is None:
                continue
            temperature = (
                _clean_number(row.get(temperature_column))
                if temperature_column is not None
                else 0.0
            )
            rows.append(
                {
                    "depth": abs(depth),
                    "pressure": pressure,
                    "temperature": temperature if temperature is not None else 0.0,
                }
            )
        rows.sort(key=lambda item: item["depth"])
        return rows
    except Exception:
        return []


def validate_result_contract(run_task, ipr, vlp, profile):
    if run_task == "nodal":
        return "VALID_FULL" if ipr and vlp else "INVALID_EMPTY_NODAL"
    if run_task == "profile":
        return "VALID_FULL" if profile else "INVALID_EMPTY_PROFILE"
    if run_task == "combined":
        if not ipr or not vlp:
            return "INVALID_EMPTY_NODAL"
        return "VALID_FULL" if profile else "VALID_PARTIAL"
    raise ValueError("unsupported PIPESIM run task")
