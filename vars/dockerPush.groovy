def call(Map config = [:]) {

    if (!config.imageRepository) {
        error "imageRepository is required"
    }

    def imageRepository = config.imageRepository
    def imageTag = config.imageTag ?: env.BUILD_NUMBER

    def scriptContent = libraryResource('scripts/docker-push.sh')

    writeFile(
        file: 'docker-push.sh',
        text: scriptContent
    )

    sh """
        chmod +x docker-push.sh

        ./docker-push.sh \
            '${imageRepository}' \
            '${imageTag}'
    """
}
