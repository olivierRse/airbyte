#
# Copyright (c) 2022 Airbyte, Inc., all rights reserved.
#

import pytest as pytest
from airbyte_cdk.models import SyncMode
from airbyte_cdk.sources.declarative.datetime.min_max_datetime import MinMaxDatetime
from airbyte_cdk.sources.declarative.interpolation.interpolated_string import InterpolatedString
from airbyte_cdk.sources.declarative.stream_slicers.cartesian_product_stream_slicer import CartesianProductStreamSlicer
from airbyte_cdk.sources.declarative.stream_slicers.datetime_stream_slicer import DatetimeStreamSlicer
from airbyte_cdk.sources.declarative.stream_slicers.list_stream_slicer import ListStreamSlicer


@pytest.mark.parametrize(
    "test_name, stream_slicers, expected_slices",
    [
        (
            "test_single_stream_slicer",
            [ListStreamSlicer(["customer", "store", "subscription"], "owner_resource", None)],
            [{"owner_resource": "customer"}, {"owner_resource": "store"}, {"owner_resource": "subscription"}],
        ),
        (
            "test_two_stream_slicers",
            [
                ListStreamSlicer(["customer", "store", "subscription"], "owner_resource", None),
                ListStreamSlicer(["A", "B"], "letter", None),
            ],
            [
                {"owner_resource": "customer", "letter": "A"},
                {"owner_resource": "customer", "letter": "B"},
                {"owner_resource": "store", "letter": "A"},
                {"owner_resource": "store", "letter": "B"},
                {"owner_resource": "subscription", "letter": "A"},
                {"owner_resource": "subscription", "letter": "B"},
            ],
        ),
        (
            "test_list_and_datetime",
            [
                ListStreamSlicer(["customer", "store", "subscription"], "owner_resource", None),
                DatetimeStreamSlicer(
                    MinMaxDatetime(datetime="2021-01-01", datetime_format="%Y-%m-%d"),
                    MinMaxDatetime(datetime="2021-01-03", datetime_format="%Y-%m-%d"),
                    "1d",
                    InterpolatedString(""),
                    "%Y-%m-%d",
                    None,
                ),
            ],
            [
                {"owner_resource": "customer", "start_date": "2021-01-01", "end_date": "2021-01-01"},
                {"owner_resource": "customer", "start_date": "2021-01-02", "end_date": "2021-01-02"},
                {"owner_resource": "customer", "start_date": "2021-01-03", "end_date": "2021-01-03"},
                {"owner_resource": "store", "start_date": "2021-01-01", "end_date": "2021-01-01"},
                {"owner_resource": "store", "start_date": "2021-01-02", "end_date": "2021-01-02"},
                {"owner_resource": "store", "start_date": "2021-01-03", "end_date": "2021-01-03"},
                {"owner_resource": "subscription", "start_date": "2021-01-01", "end_date": "2021-01-01"},
                {"owner_resource": "subscription", "start_date": "2021-01-02", "end_date": "2021-01-02"},
                {"owner_resource": "subscription", "start_date": "2021-01-03", "end_date": "2021-01-03"},
            ],
        ),
    ],
)
def test_substream_slicer(test_name, stream_slicers, expected_slices):
    slicer = CartesianProductStreamSlicer(stream_slicers)
    slices = [s for s in slicer.stream_slices(SyncMode.incremental, stream_state=None)]
    assert slices == expected_slices


@pytest.mark.parametrize(
    "test_name, stream_slice, expected_state",
    [
        ("test_update_cursor_no_state_no_record", {}, None),
        ("test_update_cursor_partial_state", {"owner_resource": "customer"}, {"owner_resource": "customer"}),
        (
            "test_update_cursor_full_state",
            {"owner_resource": "customer", "date": "2021-01-01"},
            {"owner_resource": "customer", "date": "2021-01-01"},
        ),
    ],
)
def test_update_cursor(test_name, stream_slice, expected_state):
    stream_slicers = [
        ListStreamSlicer(["customer", "store", "subscription"], "owner_resource", None),
        DatetimeStreamSlicer(
            MinMaxDatetime(datetime="2021-01-01", datetime_format="%Y-%m-%d"),
            MinMaxDatetime(datetime="2021-01-03", datetime_format="%Y-%m-%d"),
            "1d",
            InterpolatedString("date"),
            "%Y-%m-%d",
            None,
        ),
    ]
    slicer = CartesianProductStreamSlicer(stream_slicers)
    slicer.update_cursor(stream_slice, None)
    updated_state = slicer.get_stream_state()
    assert expected_state == updated_state
