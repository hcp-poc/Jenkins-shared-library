#!/bin/bash

set -euo pipefail

APP_NAME="${1:?Application name is required}"
NAMESPACE="${2:?Namespace is required}"
MANIFEST_PATH="${3:?Manifest path is required}"
IMAGE_REPOSITORY="${4:?Image repository is required}"
IMAGE_TAG="${5:?Image tag is required}"

IMAGE="${IMAGE_REPOSITORY}:${IMAGE_TAG}"

echo "======================================"
echo "Kubernetes Deployment"
echo "======================================"
echo "Application : ${APP_NAME}"
echo "Namespace   : ${NAMESPACE}"
echo "Image       : ${IMAGE}"
echo "Manifest    : ${MANIFEST_PATH}"
echo "======================================"

sed -i "s|tagversion|${IMAGE_TAG}|g" "${MANIFEST_PATH}/deploy.yaml"

echo "Applying Kubernetes manifests..."

kubectl apply -f "${MANIFEST_PATH}" -n "${NAMESPACE}"

echo "Waiting for rollout..."

kubectl rollout status deployment/"${APP_NAME}" \
    -n "${NAMESPACE}" \
    --timeout=120s

echo "======================================"
echo "Deployment completed successfully"
echo "======================================"
