#!/bin/bash

#Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
#SPDX-License-Identifier: MIT-0

set -m
#./tesim --simtime 100  --external-ctrl &
./tesim --simtime 100 --tplant 0.0001 --tctlr 0.001 --setidv 9 --per 0.7  --external-ctrl &
./rtclient -k
fg %1
