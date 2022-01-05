#!/bin/bash

#Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
#SPDX-License-Identifier: MIT-0

set -e
sudo rm -rf libs
docker build -t tesim-build-image:01 -f dockerfile-build .
mkdir -p libs
docker run --rm -v $(pwd):/exchange tesim-build-image:01 cp -R /aws /exchange/libs/
docker run --rm -v $(pwd):/exchange tesim-build-image:01 cp /tesim/c/tesim /tesim/c/rtclient/rtclient /exchange/
docker build -t tesim-runner:01 -f dockerfile-runner .

