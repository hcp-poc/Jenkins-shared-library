
def call(Map config = [:]) {

    def remoteConfig = [
        url: config.repoUrl
    ]

    if (config.credentialsId) {
        remoteConfig.credentialsId = config.credentialsId
    }

    checkout([
        $class: 'GitSCM',
        branches: [[name: config.branch ?: '*/main']],
        userRemoteConfigs: [remoteConfig]
    ])
}
