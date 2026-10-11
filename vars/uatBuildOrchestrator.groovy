def call(Map config = [:]) {

    /*
     * =========================================================
     * UAT BUILD ORCHESTRATOR
     *
     * Target architecture:
     *
     * Developer/QA creates release branch
     *              |
     *              v
     *        UAT Build Flow
     *              |
     *        Approval 1
     *              |
     *      Build changed apps
     *              |
     *      Collect metadata
     *              |
     *      UAT Release Pipeline
     *
     * This pipeline does NOT deploy to UAT.
     * Deployment is handled by the single central
     * UAT Release Pipeline.
     * =========================================================
     */

    /*
     * ---------------------------------------------------------
     * 1. Validate release branch
     * ---------------------------------------------------------
     */

    if (!config.releaseBranch) {
        error "releaseBranch is required"
    }

    def releaseBranch = config.releaseBranch.trim()

    if (!releaseBranch.startsWith('release/')) {
        error "RELEASE_BRANCH must start with 'release/'"
    }

    def releaseVersion =
        releaseBranch.replaceFirst(/^release\//, '')

    if (!(releaseVersion ==~ /^\d{4}\.\d{2}\.\d{2}$/)) {
        error(
            "Invalid release version '${releaseVersion}'. " +
            "Expected format: YYYY.MM.##, for example 2026.10.01"
        )
    }

    echo "=============================================="
    echo "UAT BUILD ORCHESTRATOR"
    echo "=============================================="
    echo "Release Branch : ${releaseBranch}"
    echo "Release Version: ${releaseVersion}"
    echo "=============================================="


    /*
     * ---------------------------------------------------------
     * 2. Load application configuration
     * ---------------------------------------------------------
     */

    def applicationsYaml =
        libraryResource('config/uat-applications.yaml')

    def applicationsConfig =
        readYaml(text: applicationsYaml)

    def applications =
        applicationsConfig.applications

    if (!applications || applications.isEmpty()) {
        error "No applications found in uat-applications.yaml"
    }

    echo "Applications configured: ${applications.size()}"

    applications.each { app ->
        echo "  - ${app.name}"
    }


    /*
     * ---------------------------------------------------------
     * 3. Checkout current UAT environment manifest
     * ---------------------------------------------------------
     *
     * hcp-manifest-gitops is used only for release/environment
     * manifests.
     *
     * hcp-artifacts is NOT used here.
     * ---------------------------------------------------------
     */

    dir('uat-manifest') {

        deleteDir()

        checkoutApp(
            repoUrl:
                'https://github.com/hcp-poc/hcp-manifest-gitops.git',

            branch:
                'main',

            credentialsId:
                'git-creds'
        )
    }

    def currentManifest = [:]

    def manifestFile =
        'uat-manifest/environments/uat/manifest.yaml'

    if (fileExists(manifestFile)) {

        currentManifest =
            readYaml(file: manifestFile) ?: [:]

        echo ""
        echo "Current UAT manifest found."

    } else {

        echo ""
        echo "No current UAT manifest found."
        echo "This is treated as the first UAT release."
    }

    /*
     * ---------------------------------------------------------
     * 3A. Load previous release snapshot for Git traceability
     * ---------------------------------------------------------
     *
     * The current UAT environment manifest is the source of the
     * currently deployed image state.
     *
     * If an older environment manifest was accidentally created
     * without git_commit values, recover the Git commit from the
     * most recent release snapshot instead of losing traceability.
     * ---------------------------------------------------------
     */

    def previousReleaseManifest = [:]
    def previousReleaseVersion = ''

    try {

        def releaseManifestPaths = sh(
            script: '''
                find uat-manifest/releases -mindepth 2 -maxdepth 2                     -type f -name manifest.yaml                     -print 2>/dev/null | sort
            ''',
            returnStdout: true
        ).trim()

        if (releaseManifestPaths) {

            def candidateVersions = []

            releaseManifestPaths.split('\\n').each { path ->

                def match =
                    (path =~ /uat-manifest\/releases\/(\d{4}\.\d{2}\.\d{2})\/manifest\.yaml/)

                if (match.find()) {

                    def version = match.group(1)

                    if (version != releaseVersion &&
                        version < releaseVersion) {

                        candidateVersions << [
                            version: version,
                            path: path
                        ]
                    }
                }
            }

            if (!candidateVersions.isEmpty()) {

                def latestPreviousRelease =
                    candidateVersions.max {
                        it.version
                    }

                previousReleaseVersion =
                    latestPreviousRelease.version

                previousReleaseManifest =
                    readYaml(
                        file: latestPreviousRelease.path
                    ) ?: [:]

                echo ""
                echo "Previous release snapshot found."
                echo "Previous Release Version : ${previousReleaseVersion}"
                echo "Previous Release Manifest: ${latestPreviousRelease.path}"
            }
        }

    } catch (Exception e) {

        echo ""
        echo "Unable to load previous release snapshot."
        echo "Git commit fallback will use the current UAT manifest only."
        echo "Reason: ${e.getMessage() ?: 'Unknown error'}"
    }


    /*
     * ---------------------------------------------------------
     * 4. Load previous successful UAT build summary
     * ---------------------------------------------------------
     *
     * This is used to protect a release from being rebuilt.
     * If the same release version/branch was already built
     * successfully and the release branch commits are unchanged,
     * the existing build artifacts are reused.
     * ---------------------------------------------------------
     */

    def previousBuildSummary = [:]
    def previousBuildSummaryFile =
        'previous-uat-build/uat-build-summary.yaml'

    try {

        dir('previous-uat-build') {

            deleteDir()

            copyArtifacts(
                projectName: 'UAT/uat-build',
                selector: [$class: 'StatusBuildSelector', stable: false],
                filter: 'uat-build-summary.yaml',
                fingerprintArtifacts: true
            )
        }

        if (fileExists(previousBuildSummaryFile)) {

            previousBuildSummary =
                readYaml(
                    file:
                        previousBuildSummaryFile
                ) ?: [:]

            echo ""
            echo "Previous successful UAT build found."
            echo "Previous Release : ${previousBuildSummary.release ?: 'N/A'}"
            echo "Previous Branch  : ${previousBuildSummary.release_branch ?: 'N/A'}"

        } else {

            echo ""
            echo "No previous UAT build summary found."
            echo "This is treated as the first UAT build."
        }

    } catch (Exception e) {

        echo ""
        echo "No previous successful UAT build artifact available."
        echo "This is treated as the first UAT build."
    }


    /*
     * ---------------------------------------------------------
     * 5. Compare release branch against current UAT
     * ---------------------------------------------------------
     */

    def comparison = []

    applications.each { app ->

        def appName =
            app.name

        def repoUrl =
            app.repository

        def previousCommit = ''
        def previousImageTag = ''
        def previousImageDigest = ''

        /*
         * Read previous Git commit and image information from
         * the current UAT environment manifest.
         *
         * The manifest stores applications as a YAML list:
         *
         * applications:
         *   - name: nextjs-app-1
         *     git_commit: ...
         *
         * Support both the current list structure and the older
         * map-style structure.
         */

        def previousManifestApp = null

        if (currentManifest.applications instanceof List) {

            previousManifestApp =
                currentManifest.applications.find {
                    it.name == appName
                }

        } else if (currentManifest.applications instanceof Map) {

            previousManifestApp =
                currentManifest.applications[appName]
        }

        if (previousManifestApp) {

            previousCommit =
                previousManifestApp.git_commit ?:
                previousManifestApp.current_commit ?:
                previousManifestApp.git?.commit ?:
                ''

            previousImageTag =
                previousManifestApp.image_tag ?:
                previousManifestApp.image?.tag ?:
                ''

            previousImageDigest =
                previousManifestApp.image_digest ?:
                previousManifestApp.image?.digest ?:
                ''
        }

        /*
         * If the current UAT environment manifest does not contain
         * a Git commit, recover it from the latest release snapshot.
         * This protects Git traceability when an environment manifest
         * was previously written with an empty git_commit.
         */
        if (!previousCommit &&
            previousReleaseManifest &&
            previousReleaseManifest.applications) {

            def previousReleaseApp = null

            if (previousReleaseManifest.applications instanceof List) {

                previousReleaseApp =
                    previousReleaseManifest.applications.find {
                        it.name == appName
                    }

            } else if (previousReleaseManifest.applications instanceof Map) {

                previousReleaseApp =
                    previousReleaseManifest.applications[appName]
            }

            if (previousReleaseApp) {

                previousCommit =
                    previousReleaseApp.git_commit ?:
                    previousReleaseApp.current_commit ?:
                    previousReleaseApp.git?.commit ?:
                    previousReleaseApp.git?.sha ?:
                    ''

                if (!previousImageTag) {
                    previousImageTag =
                        previousReleaseApp.image_tag ?:
                        previousReleaseApp.image?.tag ?:
                        ''
                }

                if (!previousImageDigest) {
                    previousImageDigest =
                        previousReleaseApp.image_digest ?:
                        previousReleaseApp.image?.digest ?:
                        ''
                }

                if (previousCommit) {

                    echo ""
                    echo "Recovered Git commit from previous release snapshot."
                    echo "Application       : ${appName}"
                    echo "Previous Release  : ${previousReleaseVersion}"
                    echo "Recovered Commit  : ${previousCommit}"
                }
            }
        }

        def currentCommit = ''
        def branchExists = false
        def commitCount = 0
        def commits = []

        echo ""
        echo "----------------------------------------------"
        echo "Application       : ${appName}"
        echo "Repository        : ${repoUrl}"
        echo "Release Branch    : ${releaseBranch}"
        echo "Previous Commit   : ${previousCommit ?: 'N/A'}"
        echo "Previous Image    : ${previousImageTag ?: 'N/A'}"
        echo "Previous Digest   : ${previousImageDigest ?: 'N/A'}"
        echo "----------------------------------------------"


        /*
         * Checkout the release branch.
         */

        try {

            dir("source-${appName}") {

                deleteDir()

                checkoutApp(
                    repoUrl:
                        repoUrl,

                    branch:
                        releaseBranch,

                    credentialsId:
                        'git-creds'
                )

                branchExists = true

                currentCommit = sh(
                    script:
                        'git rev-parse HEAD',

                    returnStdout:
                        true
                ).trim()

                echo "Current Release Commit: ${currentCommit}"


                /*
                 * Calculate commits between the current
                 * UAT commit and the release branch commit.
                 */

                if (previousCommit &&
                    previousCommit != currentCommit) {

                    def commitOutput = sh(
                        script: """
                            git log ${previousCommit}..${currentCommit} \
                            --pretty=format:'%H|%s'
                        """,

                        returnStdout:
                            true
                    ).trim()

                    if (commitOutput) {

                        commitOutput
                            .split('\\n')
                            .each { line ->

                                def parts =
                                    line.split('\\|', 2)

                                commits << [

                                    commit:
                                        parts[0],

                                    message:
                                        parts.size() > 1
                                            ? parts[1]
                                            : ''
                                ]
                            }

                        commitCount =
                            commits.size()
                    }
                }
            }

        } catch (Exception e) {

            def errorMessage =
                e.getMessage() ?: ''

            /*
             * Only a genuinely missing branch is treated
             * as RELEASE_BRANCH_NOT_FOUND.
             *
             * Credential, Git, network and Jenkins errors
             * must fail the pipeline.
             */

            if (errorMessage.contains(
                    "Couldn't find any revision"
                ) ||
                errorMessage.contains(
                    "does not exist"
                )) {

                echo ""
                echo "Release branch not found for ${appName}"

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
         * Determine BUILD / SKIP
         * -----------------------------------------------------
         */

        def action = 'SKIP'
        def status = ''

        if (!branchExists) {

            /*
             * A missing release branch for an application that already
             * has a UAT artifact means there is no new application build
             * to perform. Reuse the existing UAT artifact and preserve
             * its Git commit.
             */
            if (previousCommit &&
                previousImageTag &&
                previousImageDigest) {

                status =
                    'NO_RELEASE_BRANCH_REUSE_PREVIOUS'

                action =
                    'SKIP'

            } else {

                status =
                    'RELEASE_BRANCH_NOT_FOUND'
            }

        } else if (!previousCommit) {

            /*
             * First UAT release.
             * If the application has the release branch,
             * it must be built.
             */

            status =
                'FIRST_UAT_RELEASE'

            action =
                'BUILD'

        } else if (previousCommit != currentCommit) {

            status =
                'CODE_CHANGED'

            action =
                'BUILD'

        } else {

            status =
                'NO_CODE_CHANGE'

            action =
                'SKIP'
        }

        /*
         * An unchanged application is not missing its Git commit.
         * It continues to use the exact artifact already deployed in UAT,
         * so its current UAT commit must remain the previous UAT commit.
         * This is also required for the UAT handoff summary and environment
         * manifest to retain complete Git traceability for SKIP applications.
         */
        if (action == 'SKIP' && previousCommit) {
            currentCommit = previousCommit
        }


        comparison << [

            name:
                appName,

            repository:
                repoUrl,

            releaseBranch:
                releaseBranch,

            branchExists:
                branchExists,

            previousCommit:
                previousCommit,

            currentCommit:
                currentCommit,

            previousImageTag:
                previousImageTag,

            previousImageDigest:
                previousImageDigest,

            commitCount:
                commitCount,

            commits:
                commits,

            status:
                status,

            action:
                action,

            buildJob:
                app.build_job,

            imageRepository:
                app.image_repository,

            jenkinsJob:
                '',

            jenkinsBuild:
                '',

            buildResult:
                '',

            imageTag:
                '',

            imageDigest:
                ''
        ]
    }


    /*
     * ---------------------------------------------------------
     * Same-release rebuild protection
     * ---------------------------------------------------------
     *
     * A release branch is considered frozen for a release version.
     *
     * If the same release was already built successfully:
     *
     *   - same release branch
     *   - same commit for every application
     *
     * then do NOT rebuild any application.
     *
     * If the same release version has moved to a different
     * commit, fail the pipeline and require a new release version.
     * ---------------------------------------------------------
     */

    def sameReleaseAlreadyBuilt = false

    if (previousBuildSummary &&
        previousBuildSummary.release == releaseVersion &&
        previousBuildSummary.release_branch == releaseBranch &&
        previousBuildSummary.applications) {

        def previousApplications =
            previousBuildSummary.applications

        def allApplicationsMatch =
            comparison.every { app ->

                def previousApp =
                    previousApplications.find {
                        it.name == app.name
                    }

                if (!previousApp) {
                    return false
                }

                def previousSummaryCommit =
                    previousApp.git_commit ?:
                    previousApp.current_commit ?:
                    ''

                /*
                 * For applications whose release branch still exists,
                 * compare the actual release branch commit.
                 *
                 * For applications whose release branch does not exist,
                 * reuse the existing UAT artifact when the orchestrator
                 * recovered the previous UAT commit and image details.
                 */
                if (app.branchExists) {

                    return app.currentCommit &&
                        previousSummaryCommit == app.currentCommit

                }

                return app.action == 'SKIP' &&
                    app.currentCommit &&
                    app.currentCommit == app.previousCommit &&
                    app.previousImageTag &&
                    app.previousImageDigest
            }

        if (allApplicationsMatch) {

            sameReleaseAlreadyBuilt = true

            echo ""
            echo "=============================================================="
            echo "SAME RELEASE ALREADY BUILT"
            echo "=============================================================="
            echo "Release Version : ${releaseVersion}"
            echo "Release Branch  : ${releaseBranch}"
            echo "Action          : REUSE EXISTING BUILD"
            echo "No application will be rebuilt."
            echo "=============================================================="

            comparison.each { app ->

                def previousApp =
                    previousApplications.find {
                        it.name == app.name
                    }

                app.action =
                    'SKIP'

                app.status =
                    'SAME_RELEASE_ALREADY_BUILT'

                app.jenkinsJob =
                    previousApp.jenkins?.job ?:
                    previousApp.build_job ?:
                    app.buildJob

                app.jenkinsBuild =
                    previousApp.jenkins?.build_number ?:
                    previousApp.build_number ?:
                    ''

                app.imageTag =
                    previousApp.image_tag ?:
                    ''

                app.imageDigest =
                    previousApp.image_digest ?:
                    ''

                app.buildResult =
                    'REUSED'

                echo ""
                echo "Application    : ${app.name}"
                echo "Git Commit     : ${app.currentCommit}"
                echo "Existing Job   : ${app.jenkinsJob}"
                echo "Existing Build : ${app.jenkinsBuild}"
                echo "Image Tag      : ${app.imageTag}"
                echo "Image Digest   : ${app.imageDigest}"
                echo "Action         : REUSE"
            }

        } else {

            def changedApplications =
                comparison.findAll { app ->

                    def previousApp =
                        previousApplications.find {
                            it.name == app.name
                        }

                    !previousApp ||
                    (
                        app.branchExists &&
                        (
                            !app.currentCommit ||
                            (
                                (previousApp.git_commit ?:
                                 previousApp.current_commit ?:
                                 '') != app.currentCommit
                            )
                        )
                    ) ||
                    (
                        !app.branchExists &&
                        !app.previousCommit
                    )
                }

            if (!changedApplications.isEmpty()) {

                error(
                    "Release ${releaseVersion} was already built successfully, " +
                    "but the release branch now has different application commits: " +
                    changedApplications.collect { it.name }.join(', ') +
                    ". Create a new release version instead of rebuilding the same release."
                )
            }
        }
    }


    /*
     * ---------------------------------------------------------
     * 6. Display complete comparison
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

        if (app.action == 'SKIP') {

            echo "Previous Image   : ${app.previousImageTag ?: 'N/A'}"
            echo "Previous Digest  : ${app.previousImageDigest ?: 'N/A'}"
        }

        if (app.commits) {

            echo "Commits between releases:"

            app.commits.each { commit ->

                echo(
                    "  ${commit.commit} - ${commit.message}"
                )
            }
        }
    }


    /*
     * ---------------------------------------------------------
     * 6. Prepare Approval 1
     * ---------------------------------------------------------
     */

    def buildApps =
        comparison.findAll {
            it.action == 'BUILD'
        }

    def skipApps =
        comparison.findAll {
            it.action == 'SKIP'
        }

    def missingApps =
        comparison.findAll {
            !it.branchExists
        }

    def approvalMessage =
        new StringBuilder()

    approvalMessage.append(
        "UAT PRE-BUILD APPROVAL\n"
    )

    approvalMessage.append(
        "==============================================\n"
    )

    approvalMessage.append(
        "Release Branch : ${releaseBranch}\n"
    )

    approvalMessage.append(
        "Release Version: ${releaseVersion}\n"
    )

    approvalMessage.append(
        "==============================================\n\n"
    )

    approvalMessage.append(
        "Review ALL applications before approving.\n\n"
    )

    comparison.each { app ->

        approvalMessage.append(
            "Application      : ${app.name}\n"
        )

        approvalMessage.append(
            "Branch Status    : " +
            "${app.branchExists ? 'FOUND' : 'NOT FOUND'}\n"
        )

        approvalMessage.append(
            "Previous Commit  : " +
            "${app.previousCommit ?: 'N/A'}\n"
        )

        approvalMessage.append(
            "Current Commit   : " +
            "${app.currentCommit ?: 'N/A'}\n"
        )

        approvalMessage.append(
            "Commit Count     : ${app.commitCount}\n"
        )

        approvalMessage.append(
            "Status           : ${app.status}\n"
        )

        approvalMessage.append(
            "Action           : ${app.action}\n"
        )

        if (app.action == 'SKIP') {

            approvalMessage.append(
                "Current UAT Image : " +
                "${app.previousImageTag ?: 'N/A'}\n"
            )

            approvalMessage.append(
                "Current UAT Digest: " +
                "${app.previousImageDigest ?: 'N/A'}\n"
            )
        }

        approvalMessage.append(
            "----------------------------------------------\n"
        )
    }

    approvalMessage.append("\n")

    approvalMessage.append(
        "BUILD applications: ${buildApps.size()}\n"
    )

    approvalMessage.append(
        "SKIP applications : ${skipApps.size()}\n"
    )

    approvalMessage.append(
        "Missing branches  : ${missingApps.size()}\n"
    )

    approvalMessage.append("\n")

    approvalMessage.append(
        "Approve to start the UAT application builds?"
    )


    /*
     * ---------------------------------------------------------
     * 7. APPROVAL 1 - BEFORE ANY BUILD
     * ---------------------------------------------------------
     */

    stage('Approval 1 - Pre-Build') {

        if (!buildApps.isEmpty()) {

            input(
                message:
                    approvalMessage.toString(),

                ok:
                    'Approve UAT Build',

                submitter:
                    'Nikhil,Ravi'
            )

        } else {

            echo ""
            echo "No application builds are required."
            echo "Approval 1 is not required for this run."
        }
    }


    /*
     * ---------------------------------------------------------
     * 8. Build changed applications in parallel
     * ---------------------------------------------------------
     */

    def buildResults = [:]

    if (!buildApps.isEmpty()) {

        stage('Build Changed Applications') {

            def parallelBuilds = [:]

            buildApps.each { app ->

                def appData = app

                parallelBuilds[app.name] = {

                    echo ""
                    echo "=============================================="
                    echo "Starting UAT Build"
                    echo "Application: ${appData.name}"
                    echo "=============================================="

                    def buildRun = build(

                        job:
                            appData.buildJob,

                        wait:
                            true,

                        propagate:
                            true,

                        parameters: [

                            string(
                                name:
                                    'RELEASE_BRANCH',

                                value:
                                    releaseBranch
                            ),

                            string(
                                name:
                                    'RELEASE_VERSION',

                                value:
                                    releaseVersion
                            )
                        ]
                    )

                    buildResults[appData.name] = [

                        job:
                            appData.buildJob,

                        buildNumber:
                            buildRun.number,

                        result:
                            buildRun.result
                    ]

                    echo ""
                    echo "Completed UAT Build"
                    echo "Application : ${appData.name}"
                    echo "Job         : ${appData.buildJob}"
                    echo "Build       : ${buildRun.number}"
                    echo "Result      : ${buildRun.result}"
                }
            }

            parallel parallelBuilds
        }

    } else {

        echo ""
        echo "No applications require a new UAT build."
    }


    /*
     * ---------------------------------------------------------
     * 9. Collect build metadata from application builds
     * ---------------------------------------------------------
     *
     * Application build pipelines already archive:
     *
     *   build-metadata.yaml
     *   image-digest.txt
     *
     * Copy Artifact retrieves those files from the exact
     * build number triggered above.
     *
     * This provides:
     *
     *   Git commit
     *   Jenkins job
     *   Jenkins build
     *   Image tag
     *   SHA256 digest
     * ---------------------------------------------------------
     */

    stage('Collect Build Metadata') {

        buildResults.each { appName, result ->

            def appData =
                comparison.find {
                    it.name == appName
                }

            if (!appData) {

                error(
                    "Application ${appName} not found in comparison"
                )
            }

            def outputDirectory =
                "build-output-${appName}"

            dir(outputDirectory) {

                deleteDir()

                copyArtifacts(
                    projectName: result.job,
                    selector: [
                        $class: 'SpecificBuildSelector',
                        buildNumber: "${result.buildNumber}"
                    ],
                    filter: 'build-metadata.yaml,image-digest.txt',
                    fingerprintArtifacts: true
                )

                if (!fileExists(
                        'build-metadata.yaml'
                    )) {

                    error(
                        "build-metadata.yaml not found for ${appName}"
                    )
                }

                if (!fileExists(
                        'image-digest.txt'
                    )) {

                    error(
                        "image-digest.txt not found for ${appName}"
                    )
                }

                def metadata =
                    readYaml(
                        file:
                            'build-metadata.yaml'
                    )

                def imageDigest =
                    readFile(
                        'image-digest.txt'
                    ).trim()

                if (!imageDigest) {

                    error(
                        "Image digest is empty for ${appName}"
                    )
                }

                appData.jenkinsJob =
                    metadata.jenkins.job

                appData.jenkinsBuild =
                    metadata.jenkins.build_number

                appData.imageTag =
                    metadata.image.tag

                appData.imageDigest =
                    imageDigest

                appData.buildResult =
                    result.result

                echo ""
                echo "=============================================="
                echo "BUILD METADATA"
                echo "=============================================="
                echo "Application    : ${appName}"
                echo "Jenkins Job    : ${appData.jenkinsJob}"
                echo "Jenkins Build  : ${appData.jenkinsBuild}"
                echo "Image Tag      : ${appData.imageTag}"
                echo "Image Digest   : ${appData.imageDigest}"
                echo "=============================================="
            }
        }


        /*
         * -----------------------------------------------------
         * Preserve immutable image information for SKIP apps.
         * -----------------------------------------------------
         */

        comparison.each { app ->

            if (app.action == 'SKIP' &&
                app.status != 'SAME_RELEASE_ALREADY_BUILT') {

                app.imageTag =
                    app.previousImageTag

                app.imageDigest =
                    app.previousImageDigest

                app.buildResult =
                    'SKIPPED'

                echo ""
                echo "=============================================="
                echo "UNCHANGED APPLICATION"
                echo "=============================================="
                echo "Application   : ${app.name}"
                echo "Action        : SKIP"
                echo "Image Tag     : ${app.imageTag ?: 'N/A'}"
                echo "Image Digest  : ${app.imageDigest ?: 'N/A'}"
                echo "=============================================="
            }
        }
    }


    /*
     * ---------------------------------------------------------
     * 10. Validate collected build information
     * ---------------------------------------------------------
     *
     * Every application must have the information required
     * by the central UAT Release Pipeline.
     * ---------------------------------------------------------
     */

    stage('Validate Build Information') {

        comparison.each { app ->

            if (!app.branchExists) {
                return
            }

            if (!app.currentCommit) {

                error(
                    "Missing Git commit for ${app.name}"
                )
            }

            if (!app.imageTag) {

                error(
                    "Missing image tag for ${app.name}"
                )
            }

            if (!app.imageDigest) {

                error(
                    "Missing image digest for ${app.name}"
                )
            }

            echo(
                "Validated build information for ${app.name}"
            )
        }
    }


    /*
     * ---------------------------------------------------------
     * 11. Create complete UAT build handoff summary
     * ---------------------------------------------------------
     *
     * This artifact is consumed by ONE central:
     *
     *     UAT/uat-release
     *
     * It contains information for ALL applications.
     * ---------------------------------------------------------
     */

    stage('Create UAT Build Summary') {

        def summary = [

            environment:
                'uat',

            release:
                releaseVersion,

            release_branch:
                releaseBranch,

            created_by:
                env.BUILD_USER ?: 'Jenkins',

            orchestrator_job:
                env.JOB_NAME,

            orchestrator_build:
                env.BUILD_NUMBER,

            applications:
                comparison.collect { app ->

                    [

                        name:
                            app.name,

                        repository:
                            app.repository,

                        branch:
                            app.releaseBranch,

                        branch_exists:
                            app.branchExists,

                        previous_commit:
                            app.previousCommit,

                        current_commit:
                            app.currentCommit,

                        git_commit:
                            app.currentCommit,

                        commit_count:
                            app.commitCount,

                        status:
                            app.status,

                        action:
                            app.action,

                        build_job:
                            app.jenkinsJob ?: app.buildJob,

                        build_number:
                            app.jenkinsBuild ?: '',

                        build_result:
                            app.buildResult ?: '',

                        image_repository:
                            app.imageRepository,

                        image_tag:
                            app.imageTag ?: '',

                        image_digest:
                            app.imageDigest ?: '',

                        previous_image_tag:
                            app.previousImageTag ?: '',

                        previous_image_digest:
                            app.previousImageDigest ?: '',

                        commits:
                            app.commits
                    ]
                }
        ]

        writeYaml(
            file:
                'uat-build-summary.yaml',

            data:
                summary,

            overwrite:
                true
        )

        echo ""
        echo "=============================================================="
        echo "UAT BUILD HANDOFF SUMMARY"
        echo "=============================================================="

        sh(
            script:
                'cat uat-build-summary.yaml'
        )

        archiveArtifacts(
            artifacts:
                'uat-build-summary.yaml',

            fingerprint:
                true
        )
    }


    /*
     * ---------------------------------------------------------
     * 12. Final summary
     * ---------------------------------------------------------
     */

    echo ""
    echo "=============================================================="
    echo "FINAL UAT BUILD SUMMARY"
    echo "=============================================================="

    comparison.each { app ->

        echo ""
        echo "Application    : ${app.name}"
        echo "Action         : ${app.action}"
        echo "Git Commit     : ${app.currentCommit ?: 'N/A'}"
        echo "Jenkins Job    : ${app.jenkinsJob ?: app.buildJob}"
        echo "Jenkins Build  : ${app.jenkinsBuild ?: 'N/A'}"
        echo "Image          : ${app.imageRepository}:${app.imageTag ?: 'N/A'}"
        echo "Digest         : ${app.imageDigest ?: 'N/A'}"
    }

    echo ""
    echo "=============================================================="
    echo "UAT BUILD ORCHESTRATOR COMPLETED"
    echo "=============================================================="
    echo "Release Version : ${releaseVersion}"
    echo "Build Summary   : uat-build-summary.yaml"
    echo "Next Pipeline   : UAT/uat-release"
    echo "=============================================================="


    /*
     * ---------------------------------------------------------
     * 13. Workspace cleanup - LAST
     * ---------------------------------------------------------
     */

    stage('Workspace Cleanup') {

        deleteDir()
    }


    return comparison
}
