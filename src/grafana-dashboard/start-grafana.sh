#!/bin/bash
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: MIT-0
docker run --rm \
 -p 8080:8080 \
 -v grafana-storage:/var/lib/grafana \
 -v $(pwd)/datasources:/etc/grafana/provisioning/datasources/ \
 -v $(pwd)/dashboards:/etc/grafana/provisioning/dashboards/ \
 --network host \
 --name=tep-grafana \
 -e "GF_INSTALL_PLUGINS=grafana-timestream-datasource" \
 -e "GF_SERVER_HTTP_PORT=8080" \
grafana/grafana:8.2.5
