#!/bin/bash

set -e

mkdir -p packages/publish/
mkdir -p packages/archive/

echo "=> Copying minimal template Helm values file into publish folder..."
cp others/helm/x9-qrcode/chart/values-minimal.yaml packages/publish/values.yaml

echo "=> Generating .html with the REST APIs specification..."
redocly build-docs -o packages/publish/x9-qrcode-apis.html x9-qrcode-infrastructure/src/main/resources/apis/openapi.yaml
