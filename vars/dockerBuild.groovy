def call(Map config = [:]) {

    def imageRepository = config.imageRepository
    def imageTag = config.imageTag ?: env.BUILD_NUMBER

    if (!imageRepository) {
        error "imageRepository is required"
    }

    sh """
        chmod +x scripts/docker-build.sh
        scripts/docker-build.sh '${imageRepository}' '${imageTag}'
    """
}
