
#!/bin/bash

set -euo pipefail

IMAGE_REPOSITORY="${1:?Image repository is required}"
IMAGE_TAG="${2:?Image tag is required}"

IMAGE="${IMAGE_REPOSITORY}:${IMAGE_TAG}"

echo "======================================"
echo "Docker Build"
echo "======================================"
echo "Image: ${IMAGE}"

docker build \
    -t "${IMAGE}" \
    .

echo "======================================"
echo "Docker build completed successfully"
echo "Image: ${IMAGE}"
echo "======================================"
