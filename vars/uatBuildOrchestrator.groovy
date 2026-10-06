def call(Map config = [:]) {

    if (!config.releaseBranch) {
        error "releaseBranch is required"
    }

    def releaseBranch = config.releaseBranch
    def releaseVersion = releaseBranch.replaceFirst(/^release\//, '')

    echo "=============================================="
    echo "UAT BUILD ORCHESTRATOR"
    echo "=============================================="
    echo "Release Branch : ${releaseBranch}"
    echo "Release Version: ${releaseVersion}"
    echo "=============================================="

    /*
     * ---------------------------------------------------------
     * 1. Load application configuration
     * ---------------------------------------------------------
     */

    def applicationsYaml = libraryResource(
        'config/uat-applications.yaml'
    )

    def applicationsConfig = readYaml(
        text: applicationsYaml
    )

    def applications = applicationsConfig.applications

    if (!applications || applications.size() == 0) {
        error "No applications found in uat-applications.yaml"
    }

    /*
     * ---------------------------------------------------------
     * 2. Checkout current UAT manifest
     * ---------------------------------------------------------
     */

    dir('uat-manifest') {

        deleteDir()

        checkoutApp(
            repoUrl: 'https://github.com/hcp-poc/hcp-manifest-gitops.git',
            branch: 'main',
            credentialsId: 'git-creds'
        )
    }

    def currentManifest = [:]

    def manifestFile = 'uat-manifest/environments/uat/manifest.yaml'

    if (fileExists(manifestFile)) {

        currentManifest = readYaml(
            file: manifestFile
        ) ?: [:]

        echo "Current UAT manifest found."

    } else {

        echo "No current UAT manifest found."
        echo "This will be treated as the first UAT release."
    }

    /*
     * ---------------------------------------------------------
     * 3. Compare all applications
     * ---------------------------------------------------------
     */

    def comparison = []

    applications.each { app ->

        def appName = app.name
        def repoUrl = app.repository

        def previousCommit = ''

        if (currentManifest.applications &&
            currentManifest.applications[appName] &&
            currentManifest.applications[appName].git) {

            previousCommit =
                currentManifest.applications[appName].git.commit ?: ''
        }

        def currentCommit = ''
        def branchExists = false
        def commitCount = 0
        def commits = []

        echo ""
        echo "Checking ${appName}"
        echo "Repository: ${repoUrl}"
        echo "Release branch: ${releaseBranch}"

        /*
         * Checkout release branch.
         * If branch does not exist, mark application as SKIP.
         */

        try {

            dir("source-${appName}") {

                deleteDir()

                checkoutApp(
                    repoUrl: repoUrl,
                    branch: releaseBranch,
                    credentialsId: 'git-creds'
                )

                branchExists = true

                currentCommit = sh(
                    script: 'git rev-parse HEAD',
                    returnStdout: true
                ).trim()

                echo "Release branch commit: ${currentCommit}"

                /*
                 * Get commits between previous UAT commit
                 * and current release commit.
                 */

                if (previousCommit &&
                    previousCommit != currentCommit) {

                    def commitOutput = sh(
                        script: """
                            git log ${previousCommit}..${currentCommit} \
                            --pretty=format:'%H|%s'
                        """,
                        returnStdout: true
                    ).trim()

                    if (commitOutput) {

                        commitOutput.split('\\n').each { line ->

                            def parts = line.split(
                                '\\|',
                                2
                            )

                            commits << [
                                commit : parts[0],
                                message: parts.size() > 1
                                    ? parts[1]
                                    : ''
                            ]
                        }

                        commitCount = commits.size()
                    }
                }
            }

        } catch (Exception e) {

            echo "Release branch not found for ${appName}"

            branchExists = false
            currentCommit = ''
        }

        /*
         * Determine action
         */

        def action = 'SKIP'
        def status = ''

        if (!branchExists) {

            status = 'RELEASE_BRANCH_NOT_FOUND'

        } else if (!previousCommit) {

            status = 'FIRST_UAT_RELEASE'
            action = 'BUILD'

        } else if (previousCommit != currentCommit) {

            status = 'CODE_CHANGED'
            action = 'BUILD'

        } else {

            status = 'NO_CODE_CHANGE'
            action = 'SKIP'
        }

        comparison << [
            name            : appName,
            repository      : repoUrl,
            releaseBranch   : releaseBranch,
            branchExists    : branchExists,
            previousCommit  : previousCommit,
            currentCommit   : currentCommit,
            commitCount     : commitCount,
            commits         : commits,
            status          : status,
            action          : action,
            buildJob        : app.build_job,
            imageRepository : app.image_repository
        ]
    }

    /*
     * ---------------------------------------------------------
     * 4. Display complete comparison
     * ---------------------------------------------------------
     */

    echo ""
    echo "=============================================================="
    echo "UAT RELEASE COMPARISON"
    echo "=============================================================="
    echo "Release Branch : ${releaseBranch}"
    echo "Release Version: ${releaseVersion}"
    echo "=============================================================="

    comparison.each { app ->

        echo ""
        echo "Application       : ${app.name}"
        echo "Release Branch    : ${app.releaseBranch}"
        echo "Branch Exists     : ${app.branchExists}"
        echo "Previous Commit   : ${app.previousCommit ?: 'N/A'}"
        echo "Current Commit    : ${app.currentCommit ?: 'N/A'}"
        echo "Commit Count      : ${app.commitCount}"
        echo "Status            : ${app.status}"
        echo "Action            : ${app.action}"

        if (app.commits) {

            echo "Commits between releases:"

            app.commits.each { commit ->

                echo "  ${commit.commit} - ${commit.message}"
            }
        }
    }

    echo ""
    echo "=============================================================="
    echo "PRE-BUILD APPROVAL REQUIRED"
    echo "=============================================================="
    echo "Review ALL applications above."
    echo "Only applications marked BUILD will be built."
    echo "=============================================================="

    /*
     * ---------------------------------------------------------
     * 5. Approval 1
     * ---------------------------------------------------------
     */

    input(
        message: "Approve UAT build for ${releaseVersion}?",
        ok: "Approve UAT Build",
        submitter: "Nikhil,Ravi"
    )

    /*
     * ---------------------------------------------------------
     * 6. Trigger changed application builds
     * ---------------------------------------------------------
     */

    def changedApps = comparison.findAll {
        it.action == 'BUILD'
    }

    if (changedApps.size() == 0) {

        echo ""
        echo "No applications require a new UAT build."
        echo "UAT build process completed."

        return comparison
    }

    echo ""
    echo "=============================================================="
    echo "TRIGGERING UAT APPLICATION BUILDS"
    echo "=============================================================="

    def buildResults = [:]

    def parallelBuilds = [:]

    changedApps.each { app ->

        def appData = app

        parallelBuilds[app.name] = {

            echo "Starting build for ${appData.name}"

            def buildRun = build(
                job: appData.buildJob,
                wait: true,
                propagate: true,
                parameters: [
                    string(
                        name: 'RELEASE_BRANCH',
                        value: releaseBranch
                    ),
                    string(
                        name: 'RELEASE_VERSION',
                        value: releaseVersion
                    )
                ]
            )

            buildResults[appData.name] = [
                job         : appData.buildJob,
                buildNumber : buildRun.number,
                result      : buildRun.result
            ]

            echo "Completed ${appData.name}"
            echo "Build Number: ${buildRun.number}"
        }
    }

    parallel parallelBuilds

    /*
     * ---------------------------------------------------------
     * 7. Print build summary
     * ---------------------------------------------------------
     */

    echo ""
    echo "=============================================================="
    echo "UAT BUILD SUMMARY"
    echo "=============================================================="

    comparison.each { app ->

        if (buildResults.containsKey(app.name)) {

            def result = buildResults[app.name]

            echo "${app.name}"
            echo "  Action       : BUILD"
            echo "  Jenkins Job  : ${result.job}"
            echo "  Build Number : ${result.buildNumber}"
            echo "  Result       : ${result.result}"

        } else {

            echo "${app.name}"
            echo "  Action       : ${app.action}"
            echo "  Build        : Not triggered"
        }
    }

    return comparison
}
