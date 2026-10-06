#!/bin/bash

set -euo pipefail

IMAGE_REPOSITORY="${1:?Image repository is required}"
IMAGE_TAG="${2:?Image tag is required}"

IMAGE="${IMAGE_REPOSITORY}:${IMAGE_TAG}"

echo "======================================"
echo "Docker Cleanup"
echo "======================================"
echo "Removing local image: ${IMAGE}"

if docker image inspect "${IMAGE}" >/dev/null 2>&1; then
    docker rmi "${IMAGE}"
    echo "Local Docker image removed successfully"
else
    echo "Image not found locally: ${IMAGE}"
fi

echo "======================================"
