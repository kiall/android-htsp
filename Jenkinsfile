#!/usr/bin/env groovy

node ('android-slave') {
    stage('Preparation') {
        step([$class: 'WsCleanup'])
        checkout scm
    }

    def common = load 'Jenkinsfile.groovy'

    stage('Assemble') {
        common.assemble()
    }
    stage('Lint') {
        common.lint()
    }
}
