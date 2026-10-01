#!/bin/bash

set -euo pipefail

IMAGE_REPOSITORY="${1:?Image repository is required}"
IMAGE_TAG="${2:?Image tag is required}"

IMAGE="${IMAGE_REPOSITORY}:${IMAGE_TAG}"

echo "======================================"
echo "Docker Push"
echo "======================================"
echo "Image: ${IMAGE}"

docker push "${IMAGE}"

echo "======================================"
echo "Docker push completed successfully"
echo "Image: ${IMAGE}"
echo "======================================"
