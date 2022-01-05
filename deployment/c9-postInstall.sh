#!/bin/bash
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: MIT-0
echo
echo "---SETTING UP IAM INSTANCE PROFILE"
read -p 'Please enter cloudformation stack name (default: flink-rcf-app): ' CFN_STACK_NAME
CFN_STACK_NAME="${CFN_STACK_NAME:=flink-rcf-app}"

CLOUD9_INSTANCE_PROFILE=$( \
aws cloudformation describe-stacks \
--stack-name $CFN_STACK_NAME \
--query "Stacks[0].Outputs[?OutputKey=='01IAMCloud9Profile'].OutputValue" \
--output text \
)

echo
echo "---STARTING APACHE FLINK APPLICATION USING THE FOLLOWING COMMAND:"
FLINK_START_CMD=$( \
aws cloudformation describe-stacks \
--stack-name $CFN_STACK_NAME \
--query "Stacks[0].Outputs[?OutputKey=='06FLinkAppStartCmd'].OutputValue" \
--output text \
)
echo $FLINK_START_CMD
eval "$FLINK_START_CMD"

echo
echo "---SETTING UP jq & yq"
wget https://github.com/mikefarah/yq/releases/download/v4.16.2/yq_linux_amd64 && sudo mv yq_linux_amd64 /usr/bin/yq && sudo chmod 755 /usr/bin/yq
sudo yum -y install jq

echo
echo "---SETTING UP GRAFANA DATASOURCE"
C9_REGION=$(curl http://169.254.169.254/latest/dynamic/instance-identity/document | jq -r .region)
yq eval ".datasources[0].jsonData.defaultRegion = \"$C9_REGION\"" -i ../src/grafana-dashboard/datasources/datasource.yaml

echo
echo "---SETTING UP INSTANCE PROFILE"

INSTANCE_ID=$(aws ec2 describe-instances \
--filters "Name=private-dns-name,Values=$(hostname)" \
--output text \
--query 'Reservations[*].Instances[*].InstanceId')
aws ec2 associate-iam-instance-profile \
--iam-instance-profile Name=$CLOUD9_INSTANCE_PROFILE \
--instance-id $INSTANCE_ID

echo
echo "---SETTING UP MAVEN"

echo "installing maven to /opt/maven..."
wget https://dlcdn.apache.org/maven/maven-3/3.8.4/binaries/apache-maven-3.8.4-bin.tar.gz
sudo tar xf apache-maven-*.tar.gz -C /opt
sudo ln -sf /opt/apache-maven-3.8.4 /opt/maven
echo \
'export JAVA_HOME=/usr/lib/jvm/jre-11-openjdk
export M2_HOME=/opt/maven
export MAVEN_HOME=/opt/maven
export PATH=${M2_HOME}/bin:${PATH}' | sudo tee /etc/profile.d/maven.sh > /dev/null
sudo chmod +x /etc/profile.d/maven.sh
source /etc/profile.d/maven.sh
rm apache-maven*.tar.gz
echo "...done"