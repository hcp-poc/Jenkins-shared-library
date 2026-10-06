def call(Map config = [:]) {

    if (!config.imageRepository) {
        error "imageRepository is required"
    }

    if (!config.imageTag) {
        error "imageTag is required"
    }

    def imageRepository = config.imageRepository
    def imageTag = config.imageTag

    def scriptContent = libraryResource('scripts/docker-cleanup.sh')

    writeFile(
        file: 'docker-cleanup.sh',
        text: scriptContent
    )

    sh """
        chmod +x docker-cleanup.sh

        ./docker-cleanup.sh \
            '${imageRepository}' \
            '${imageTag}'
    """
}
