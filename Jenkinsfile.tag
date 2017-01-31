#!/usr/bin/env groovy

node ('android-slave') {
    stage('Preparation') {
        step([$class: 'WsCleanup'])
        checkout scm
        sh 'env'
    }

    def common = load 'Jenkinsfile.groovy'

    stage('Build Library') {
        common.assemble("library")
    }

    stage('Build Example') {
        common.assemble("example")
    }

    stage('Lint') {
        parallel (
            library: {
                common.lint("library")
            },
            example: {
                common.lint("example")
            }
        )
    }

    stage('Archive') {
        common.archive()
    }

    stage('Publish') {
        parallel (
            bintray: {
                common.publishAarToBinTray()
            },
            github: {
                common.publishAarToGitHub()
            }
        )
    }

}
