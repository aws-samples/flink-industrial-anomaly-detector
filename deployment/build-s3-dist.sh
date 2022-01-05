#!/bin/bash
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: MIT-0

set -e

command -v mvn >/dev/null 2>&1 || { echo >&2 "I require mvn but it's not installed.  Aborting."; exit 1; }
command -v aws >/dev/null 2>&1 || { echo >&2 "I require aws-cli but it's not installed.  Aborting."; exit 1; }
command -v git >/dev/null 2>&1 || { echo >&2 "I require git but it's not installed.  Aborting."; exit 1; }
command -v java >/dev/null 2>&1 || { echo >&2 "I require java but it's not installed.  Aborting."; exit 1; }
command -v javac >/dev/null 2>&1 || { echo >&2 "I require javac but it's not installed.  Aborting."; exit 1; }

git_revision=$(git rev-parse --short HEAD)

usage ()
{
  echo
  echo 'Usage : ./build-s3-dist.sh <source-bucket-base-name> <solution-name> <version-code> <aws-region> <kinesisStream>'
  echo
  echo " - Please provide the base source bucket name, solution name, version & region"
  echo " - For example: ./build-s3-dist.sh tep-kinesis-analytics-flink-artifacts tep-rcf-app v0.1 eu-central-1 tep-ingest"
  exit 1
}

# Check to see if input has been provided:
if [ -z "$1" ] || [ -z "$2" ] || [ -z "$3" ] || [ -z "$4" ] || [ -z "$5" ]; then
    usage
fi

# Get reference for all important folders
template_dir="$PWD"
template_dist_dir="$template_dir/global-s3-assets"
build_dist_dir="$template_dir/regional-s3-assets"
source_dir="$template_dir/../src/flink-rcf-app"
flink_app_base_name="tepFlinkAnomalyDetector-0.0.1-SNAPSHOT"

echo "------------------------------------------------------------------------------"
echo "[Init] Clean old dist folders"
echo "------------------------------------------------------------------------------"
echo "rm -rf $template_dist_dir"
rm -rf $template_dist_dir
echo "mkdir -p $template_dist_dir"
mkdir -p $template_dist_dir
echo "rm -rf $build_dist_dir"
rm -rf $build_dist_dir
echo "mkdir -p $build_dist_dir"
mkdir -p $build_dist_dir

echo "------------------------------------------------------------------------------"
echo "[Packaging] Global Assets: Cloudformation Templates"
echo "------------------------------------------------------------------------------"
echo "copy yaml templates and rename"
cp $template_dir/flink-anomaly-detect-tep.yaml $template_dist_dir/
cd $template_dist_dir
# Rename all *.yaml to *.template
for f in *.yaml; do
    mv -- "$f" "${f%.yaml}.template"
done

echo "------------------------------------------------------------------------------"
echo "[Build] parameterize CloudFormation template"
echo "------------------------------------------------------------------------------"

for template in $(find . -name "*.template"); do
  echo "  template=$template"
  sed -i -e "s/__S3_FLINK_APP_BUCKET_ARN__/arn:aws:s3:::$1-$4/g" \
      -e "s/__FLINK_APPLICATION_S3_PATH__/$2\/$3\/$git_revision\/$flink_app_base_name-$git_revision.jar/g" $template
done

echo "------------------------------------------------------------------------------"
echo "[Packaging] Region Assets: Flink Application JAR"
echo "------------------------------------------------------------------------------"


# Build Flink App
echo "Building Flink App"
cd $source_dir
mvn clean package
cp target/$flink_app_base_name.jar $build_dist_dir/$flink_app_base_name-$git_revision.jar


cd $template_dir

echo "------------------------------------------------------------------------------"
echo "[Uploading] S3 Region Assets: Flink Application JAR"
echo "------------------------------------------------------------------------------"

cd $build_dist_dir
cp $template_dist_dir/*.template .
if aws s3api head-bucket --bucket "$1-$4" 2>/dev/null;
  then echo "bucket s3://$1-$4 exists - all good.";
  else aws s3 mb s3://$1-$4;
fi
aws s3 sync . s3://$1-$4/$2/$3/$git_revision/

echo "-------------------------------"
echo "-------------------------------"
echo
echo "Completed building distribution"
echo
echo "Cloudformation command is:"
echo
echo "aws cloudformation create-stack \\"
echo " --stack-name flink-rcf-app \\"
echo " --template-body file://global-s3-assets/flink-anomaly-detect-tep.template \\"
echo " --parameters \\"
echo "     ParameterKey=TimeStreamIngestBatchSize,ParameterValue=50 \\"
echo "     ParameterKey=RcfShingleSize,ParameterValue=1 \\"
echo "     ParameterKey=RcfShingleCyclic,ParameterValue=false \\"
echo "     ParameterKey=RcfNumberOfTrees,ParameterValue=50 \\"
echo "     ParameterKey=RcfSampleSize,ParameterValue=256 \\"
echo "     ParameterKey=RcfLambda,ParameterValue=0.000390625 \\"
echo "     ParameterKey=RcfRandomSeed,ParameterValue=42 \\"
echo " --capabilities CAPABILITY_IAM"
echo
echo
echo "aws cloudformation update-stack \\"
echo " --stack-name flink-rcf-app \\"
echo " --template-body file://global-s3-assets/flink-anomaly-detect-tep.template \\"
echo " --parameters \\"
echo "     ParameterKey=TimeStreamIngestBatchSize,ParameterValue=50 \\"
echo "     ParameterKey=RcfShingleSize,ParameterValue=1 \\"
echo "     ParameterKey=RcfShingleCyclic,ParameterValue=false \\"
echo "     ParameterKey=RcfNumberOfTrees,ParameterValue=50 \\"
echo "     ParameterKey=RcfSampleSize,ParameterValue=256 \\"
echo "     ParameterKey=RcfLambda,ParameterValue=0.000390625 \\"
echo "     ParameterKey=RcfRandomSeed,ParameterValue=42 \\"
echo " --capabilities CAPABILITY_IAM"
echo