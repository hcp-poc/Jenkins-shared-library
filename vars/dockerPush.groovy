def call(Map config = [:]) {

    if (!config.imageRepository) {
        error "imageRepository is required"
    }

    if (!config.credentialsId) {
        error "credentialsId is required"
    }

    def imageRepository = config.imageRepository
    def imageTag = config.imageTag ?: env.BUILD_NUMBER
    def credentialsId = config.credentialsId

    def scriptContent = libraryResource('scripts/docker-push.sh')

    writeFile(
        file: 'docker-push.sh',
        text: scriptContent
    )

    withCredentials([
        usernamePassword(
            credentialsId: credentialsId,
            usernameVariable: 'DOCKER_USERNAME',
            passwordVariable: 'DOCKER_PASSWORD'
        )
    ]) {

        sh '''
            set -e

            echo "$DOCKER_PASSWORD" | docker login \
                --username "$DOCKER_USERNAME" \
                --password-stdin

            chmod +x docker-push.sh

            ./docker-push.sh \
                "${IMAGE_REPOSITORY}" \
                "${IMAGE_TAG}"

            docker logout
        '''
    }
}
