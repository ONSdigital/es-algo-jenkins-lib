#!/usr/bin/env groovy

def call(body) {
    def pipelineParams = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    algorithmiaRepo = pipelineParams.algorithmiaRepo

    def buildInfo = Artifactory.newBuildInfo()
    def agentPythonVersion = 'python_3.6.0'

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
                agent { label "build.${agentPythonVersion}" }
                steps {
                    unstash name: 'Checkout'
                    withCredentials([usernameColonPassword(credentialsId: 'af-py-pi-credentials', variable: 'USERPASS')]) {
                    sh'''
                        python3.6 --version
                        pip3.6 --version
                        pip3.6 install -r requirements.txt -i http://${USERPASS}@art-p-01/artifactory/api/pypi/yr-python/simple --trusted-host art-p-01
                    '''
                    
                    }
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
							agent { label "build.${agentPythonVersion}" }
							steps {
								unstash name: 'Checkout'
								withCredentials([usernameColonPassword(credentialsId: 'af-py-pi-credentials', variable: 'USERPASS')]) {
								sh'''
									pip3.6 install -r requirements.txt -i http://${USERPASS}@art-p-01/artifactory/api/pypi/yr-python/simple --trusted-host art-p-01
									pip3.6 install pytest -i http://${USERPASS}@art-p-01/artifactory/api/pypi/yr-python/simple --trusted-host art-p-01
									pip3.6 install pytest-cov -i http://${USERPASS}@art-p-01/artifactory/api/pypi/yr-python/simple --trusted-host art-p-01
									pytest --verbose --junit-xml test-reports/results.xml --cov=. --cov-report xml:coverage-report/coverage.xml > pylint.log
								'''
							
								}
							}
							post {
								always {
									junit '**/test-reports/*.xml'
									cobertura autoUpdateHealth: false,
											autoUpdateStability: false,
											coberturaReportFile: '**/coverage-report/*.xml',
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

						stage('Static Analysis') {
							agent { label "build.${agentPythonVersion}" }
							steps {
								unstash name: 'Checkout'
								withCredentials([usernameColonPassword(credentialsId: 'af-py-pi-credentials', variable: 'USERPASS')]) {
								sh'''
									pip3.6 install pylint -i http://${USERPASS}@art-p-01/artifactory/api/pypi/yr-python/simple --trusted-host art-p-01
									pylint src --exit-zero --msg-template='{path}:{module}:{line}: [{msg_id}({symbol}), {obj}] {msg}'
								 '''
								}
							}
							post {
								always {
									recordIssues enabledForFailure: true, tool: pyLint()
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
                        // evaluate the when condition before entering this stage's agent, if any
                        beforeAgent true
                    }
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
