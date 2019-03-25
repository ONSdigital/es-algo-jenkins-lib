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
            TRAVIS_CI_URL = "https://travis-ci.com/ONSdigital/"
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
                        buildInfo.name = gitRepo.name(scm.getUserRemoteConfigs()[0].getUrl())
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
                parallel {
                    stage('Unit Test') {
                        agent { label "build.${agentMavenVersion}" }
                        steps {
                            unstash name: 'Checkout'
                            sh 'mvn cobertura:cobertura'
                        }
                        post {
                            always {
                                junit '**/target/surefire-reports/*.xml'
                                cobertura autoUpdateHealth: false,
                                        autoUpdateStability: false,
                                        coberturaReportFile: '**/target/site/cobertura/*.xml',
                                        conditionalCoverageTargets: '70, 0, 0',
                                        failUnhealthy: false,
                                        failUnstable: false,
                                        lineCoverageTargets: '80, 0, 0',
                                        maxNumberOfBuilds: 0,
                                        methodCoverageTargets: '80, 0, 0',
                                        onlyStable: false,
                                        zoomCoverageChart: false
                                stash includes: '**/target/*.exec, **/target/surefire-reports/*.xml, **/target/site/cobertura/*.xml', name: 'testresult-unittest'
                            }
                            success {
                                colourText("info", "Stage: ${env.STAGE_NAME} successful!")
                            }
                            failure {
                                colourText("warn", "Stage: ${env.STAGE_NAME} failed!")
                            }
                        }
                    }

                    stage('Analysis') {
                        agent { label "build.${agentMavenVersion}" }
                        steps {
                            unstash name: 'Checkout'
                            sh 'mvn checkstyle:checkstyle pmd:pmd pmd:cpd findbugs:findbugs spotbugs:check'
                        }
                        post {
                            always {
                                recordIssues enabledForFailure: true, tools: [mavenConsole(), java(), javaDoc()]
                                recordIssues enabledForFailure: true, tool: checkStyle()
                                recordIssues enabledForFailure: true, tool: cpd(pattern: '**/target/cpd.xml')
                                recordIssues enabledForFailure: true, tool: pmdParser(pattern: '**/target/pmd.xml')

                                stash includes: '**/target/*.xml', name: 'static-analysis-reports'
                            }
                            success {
                                colourText("info", "Stage: ${env.STAGE_NAME} successful!")
                            }
                            failure {
                                colourText("warn", "Stage: ${env.STAGE_NAME} failed!")
                            }
                        }
                    }
                }
            }

            stage('SonarQube') {
                agent { label "build.${agentMavenVersion}" }
                steps {
                    unstash name: 'Checkout'
                    unstash name: 'testresult-unittest'
                    unstash name: 'static-analysis-reports'

                    withSonarQubeEnv('SonarQube') {
                        sh '''
                            mvn install org.sonarsource.scanner.maven:sonar-maven-plugin:3.6.0.1398:sonar \
                            -Dsonar.java.pmd.reportPaths=./target/pmd.xml \
                            -Dsonar.java.checkstyle.reportPaths=./target/checkstyle-result.xml \
                            -Dsonar.jacoco.reportPaths=target/jacoco.exec \
                            -Dsonar.junit.reportPaths=./target/surefire-reports/ \
                            -Dsonar.java.spotbugs.reportPaths="./target/spotbugsXml.xml"
                        '''
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
                    colourText("info", "Currently deployed to Algorthmia via ${env.TRAVIS_CI_URL}${buildInfo.name}")
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
                    colourText("info", "Currently verfied in Algorthmia via ${env.TRAVIS_CI_URL}${buildInfo.name}")
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

            stage('Publish') {
                agent { label 'deploy.cf' }
                when {
                    allOf {
                        branch "master"
                        buildingTag()
                    }
                    // evaluate the when condition before entering this stage's agent, if any
                    beforeAgent true
                }
                steps {
                    colourText("info", "Currently verfied in Algorthmia via ${env.TRAVIS_CI_URL}${buildInfo.name}")
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
