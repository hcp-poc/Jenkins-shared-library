```groovy
def call(Map config = [:]) {

    if (!config.releaseBranch) {
        error "releaseBranch is required"
    }

    def releaseBranch = config.releaseBranch.trim()

    if (!releaseBranch.startsWith('release/')) {
        error "RELEASE_BRANCH must start with 'release/'"
    }

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

    if (!applications || applications.isEmpty()) {
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

    def manifestFile =
        'uat-manifest/environments/uat/manifest.yaml'

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
        echo "----------------------------------------------"
        echo "Checking: ${appName}"
        echo "Repository: ${repoUrl}"
        echo "Release branch: ${releaseBranch}"
        echo "Previous UAT commit: ${previousCommit ?: 'N/A'}"
        echo "----------------------------------------------"

        /*
         * Checkout the requested release branch.
         *
         * IMPORTANT:
         * Do not silently convert every checkout error into
         * "branch not found". A real Git/credential failure
         * must fail the pipeline.
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

                echo "Current release commit: ${currentCommit}"

                /*
                 * Get commit history between releases.
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

            /*
             * Only treat the checkout as a missing branch when
             * Jenkins/Git explicitly reports that no revision
             * was found.
             */

            def errorMessage =
                e.getMessage() ?: ''

            if (errorMessage.contains(
                    'Couldn\'t find any revision'
                ) ||
                errorMessage.contains(
                    'Couldn't find any revision'
                ) ||
                errorMessage.contains(
                    'does not exist'
                )) {

                echo "Release branch does not exist for ${appName}"

                branchExists = false
                currentCommit = ''

            } else {

                error(
                    "Unable to check ${appName}: ${errorMessage}"
                )
            }
        }

        /*
         * -----------------------------------------------------
         * Determine action
         * -----------------------------------------------------
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
     * 4. Build approval summary
     * ---------------------------------------------------------
     */

    def approvalMessage = new StringBuilder()

    approvalMessage.append(
        "UAT PRE-BUILD APPROVAL\\n"
    )

    approvalMessage.append(
        "==============================================\\n"
    )

    approvalMessage.append(
        "Release Branch : ${releaseBranch}\\n"
    )

    approvalMessage.append(
        "Release Version: ${releaseVersion}\\n"
    )

    approvalMessage.append(
        "==============================================\\n\\n"
    )

    approvalMessage.append(
        "Review ALL applications before approving.\\n\\n"
    )

    comparison.each { app ->

        approvalMessage.append(
            "Application      : ${app.name}\\n"
        )

        approvalMessage.append(
            "Release Branch   : ${app.branchExists ? app.releaseBranch : 'NOT FOUND'}\\n"
        )

        approvalMessage.append(
            "Previous Commit  : ${app.previousCommit ?: 'N/A'}\\n"
        )

        approvalMessage.append(
            "Current Commit   : ${app.currentCommit ?: 'N/A'}\\n"
        )

        approvalMessage.append(
            "Commit Count     : ${app.commitCount}\\n"
        )

        approvalMessage.append(
            "Status            : ${app.status}\\n"
        )

        approvalMessage.append(
            "Action            : ${app.action}\\n"
        )

        approvalMessage.append(
            "----------------------------------------------\\n"
        )
    }

    def buildApps = comparison.findAll {
        it.action == 'BUILD'
    }

    def skipApps = comparison.findAll {
        it.action == 'SKIP'
    }

    approvalMessage.append("\\n")

    approvalMessage.append(
        "BUILD applications: ${buildApps.size()}\\n"
    )

    approvalMessage.append(
        "SKIP applications : ${skipApps.size()}\\n"
    )

    approvalMessage.append("\\n")

    approvalMessage.append(
        "Approve to start the UAT application builds?"
    )

    /*
     * ---------------------------------------------------------
     * 5. Print same information to console
     * ---------------------------------------------------------
     */

    echo ""
    echo "=============================================================="
    echo "UAT RELEASE COMPARISON"
    echo "=============================================================="

    comparison.each { app ->

        echo ""
        echo "Application      : ${app.name}"
        echo "Release Branch   : ${app.branchExists ? app.releaseBranch : 'NOT FOUND'}"
        echo "Previous Commit  : ${app.previousCommit ?: 'N/A'}"
        echo "Current Commit   : ${app.currentCommit ?: 'N/A'}"
        echo "Commit Count     : ${app.commitCount}"
        echo "Status           : ${app.status}"
        echo "Action           : ${app.action}"

        if (app.commits) {

            echo "Commits between releases:"

            app.commits.each { commit ->

                echo "  ${commit.commit} - ${commit.message}"
            }
        }
    }

    /*
     * ---------------------------------------------------------
     * 6. APPROVAL 1
     * ---------------------------------------------------------
     */

    stage('Approval 1 - Pre-Build') {

        input(
            message: approvalMessage.toString(),
            ok: 'Approve UAT Build',
            submitter: 'Nikhil,Ravi'
        )
    }

    /*
     * ---------------------------------------------------------
     * 7. Trigger changed application builds
     * ---------------------------------------------------------
     */

    if (buildApps.isEmpty()) {

        echo ""
        echo "No applications require a new UAT build."
        echo "UAT Build Orchestrator completed."

        return comparison
    }

    stage('Build Changed Applications') {

        def buildResults = [:]
        def parallelBuilds = [:]

        buildApps.each { app ->

            def appData = app

            parallelBuilds[app.name] = {

                echo ""
                echo "=============================================="
                echo "Starting UAT build: ${appData.name}"
                echo "=============================================="

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

                echo "Completed: ${appData.name}"
                echo "Build Number: ${buildRun.number}"
            }
        }

        parallel parallelBuilds

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
    }

    return comparison
}
```
