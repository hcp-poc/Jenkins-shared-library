def call(Map config = [:]) {

    if (!config.appName) {
        error "appName is required"
    }

    if (!config.namespace) {
        error "namespace is required"
    }

    if (!config.manifestPath) {
        error "manifestPath is required"
    }

    if (!config.imageRepository) {
        error "imageRepository is required"
    }

    def appName = config.appName
    def namespace = config.namespace
    def manifestPath = config.manifestPath
    def imageRepository = config.imageRepository
    def imageTag = config.imageTag ?: env.BUILD_NUMBER

    def scriptContent = libraryResource('scripts/k8s-deploy.sh')

    writeFile(
        file: 'k8s-deploy.sh',
        text: scriptContent
    )

    sh """
        chmod +x k8s-deploy.sh

        ./k8s-deploy.sh \
            '${appName}' \
            '${namespace}' \
            '${manifestPath}' \
            '${imageRepository}' \
            '${imageTag}'
    """
}
