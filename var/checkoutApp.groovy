def call(Map config = [:]) {

    checkout([
        $class: 'GitSCM',
        branches: [[name: config.branch ?: '*/main']],
        userRemoteConfigs: [[
            url: config.repoUrl,
            credentialsId: config.credentialsId
        ]]
    ])
}
