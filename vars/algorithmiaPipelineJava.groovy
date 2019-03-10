#!/usr/bin/env groovy

def call(body) {
    def pipelineParams = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    algorithmiaRepo = pipelineParams.algorithmiaRepo

    def buildInfo = Artifactory.newBuildInfo()
    def agentMavenVersion = 'maven_3.5.4'

    pipeline {
        libraries {
            lib('jenkins-pipeline-shared')
        }
        environment {
            GIT_REPO = gitRepo.name
            TRAVIS_CI_URL = "https://travis-ci.com/ONSdigital/${GIT_REPO}"
        }
        options {
            skipDefaultCheckout()
            buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '30'))
            timeout(time: 1, unit: 'HOURS')
            ansiColor('xterm')
        }
        agent { label 'download.jenkins.slave' }
        stages {
            stage('Checkout') {
                agent { label 'download.jenkins.slave' }
                steps {
                    checkout scm
                    script {
                        buildInfo.name = "${GIT_REPO}"
                        buildInfo.number = "${BUILD_NUMBER}"
                        buildInfo.env.collect()
                    }
                    colourText("info", "BuildInfo: ${buildInfo.name}-${buildInfo.number}")
                    stash name: 'Checkout'
                }
            }

            stage('Build') {
                agent { label "build.${agentMavenVersion}" }
                steps {
                    unstash name: 'Checkout'
                    sh "mvn compile"
                }
                post {
                    success {
                        colourText("info", "Stage: ${env.STAGE_NAME} successful!")
                    }
                    failure {
                        colourText("warn", "Stage: ${env.STAGE_NAME} failed!")
                    }
                }
            }

            stage('Validate') {
                agent { label "build.${agentMavenVersion}" }
                steps {
                    unstash name: 'Checkout'
                    sh 'mvn cobertura:cobertura'
                }
                post {
                    always {
                        junit '**/target/test-reports/*.xml'
                        cobertura autoUpdateHealth: false,
                                autoUpdateStability: false,
                                coberturaReportFile: 'target/site/coverage/cobertura.xml',
                                conditionalCoverageTargets: '70, 0, 0',
                                failUnhealthy: false,
                                failUnstable: false,
                                lineCoverageTargets: '80, 0, 0',
                                maxNumberOfBuilds: 0,
                                methodCoverageTargets: '80, 0, 0',
                                onlyStable: false,
                                zoomCoverageChart: false
                    }
                    success {
                        colourText("info", "Stage: ${env.STAGE_NAME} successful!")
                    }
                    failure {
                        colourText("warn", "Stage: ${env.STAGE_NAME} failed!")
                    }
                }
            }

            stage('Deploy') {
                agent { label 'deploy.cf' }
                when {
                    branch "master"
                    // evaluate the when condition before entering this stage's agent, if any
                    beforeAgent true
                }
                steps {
                    colourText("info", "Currently deployed to Algorthmia via ${env.TRAVIS_CI_URL}")
                }
                post {
                    success {
                        colourText("info", "Stage: ${env.STAGE_NAME} successful!")
                    }
                    failure {
                        colourText("warn", "Stage: ${env.STAGE_NAME} failed!")
                    }
                }
            }

            stage('Verify') {
                agent { label 'deploy.cf' }
                when {
                    branch "master"
                    // evaluate the when condition before entering this stage's agent, if any
                    beforeAgent true
                }
                steps {
                    colourText("info", "Currently verfied in Algorthmia via ${env.TRAVIS_CI_URL}")
                }
                post {
                    success {
                        colourText("info", "Stage: ${env.STAGE_NAME} successful!")
                    }
                    failure {
                        colourText("warn", "Stage: ${env.STAGE_NAME} failed!")
                    }
                }
            }
        }

        post {
            success {
                colourText("success", "All stages complete. Build was successful.")
                slackSend(
                        color: "good",
                        message: "${env.JOB_NAME} success: ${env.RUN_DISPLAY_URL}"
                )
            }
            unstable {
                colourText("warn", "Something went wrong, build finished with result ${currentResult}. This may be caused by failed tests, code violation or in some cases unexpected interrupt.")
                slackSend(
                        color: "warning",
                        message: "${env.JOB_NAME} unstable: ${env.RUN_DISPLAY_URL}"
                )
            }
            failure {
                colourText("warn", "Process failed at: ${env.NODE_STAGE}")
                slackSend(
                        color: "danger",
                        message: "${env.JOB_NAME} failed at ${env.STAGE_NAME}: ${env.RUN_DISPLAY_URL}"
                )
            }
        }
    }
}