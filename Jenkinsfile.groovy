import jenkins.model.*

def assemble(String module) {
    sh "./gradlew ${module}:assemble"
}

def lint(String module) {
    sh "./gradlew ${module}:lint"
    androidLint canComputeNew: false, canRunOnFailed: true, defaultEncoding: '', healthy: '', pattern: '**/lint-results*.xml', unHealthy: ''
}

def archive() {
    archiveArtifacts artifacts: 'library/build/outputs/aar/*.aar', fingerprint: true
    archiveArtifacts artifacts: 'example/build/outputs/apk/*.apk', fingerprint: true
}

def publishAarToBinTray() {
    def tagName = sh(returnStdout: true, script: "git describe --tags --abbrev=0 --exact-match").trim()
    def changeLog = sh(returnStdout: true, script: "./tools/generate-changelog").trim().replaceAll(~/'/, "\'")

    withCredentials([
        [$class: 'UsernamePasswordMultiBinding', credentialsId: 'bintray-kiall', usernameVariable: 'BINTRAY_USER', passwordVariable: 'BINTRAY_KEY'],
    ]) {
        sh './gradlew bintrayUpload -PbintrayUser=$BINTRAY_USER -PbintrayKey=$BINTRAY_KEY -PdryRun=false'
    }
}

def publishAarToGitHub() {
    def tagName = sh(returnStdout: true, script: "git describe --tags --abbrev=0 --exact-match").trim()
    def changeLog = sh(returnStdout: true, script: "./tools/generate-changelog").trim().replaceAll(~/'/, "\'")

    withCredentials([
            [$class: 'StringBinding', credentialsId: 'github-pat-kiall', variable: 'GITHUB_TOKEN'],
    ]) {
        sh(script: "github-release release --user kiall --repo android-htsp --tag ${tagName} --name ${tagName} --description '${changeLog}'")
        sh(script: "github-release upload --user kiall --repo android-htsp --tag ${tagName} --name ie.macinnes.htsp_${tagName}-release.aar --file library/build/outputs/aar/library-release.aar")
    }
}

return this;
