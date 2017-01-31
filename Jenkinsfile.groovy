import jenkins.model.*

def assemble() {
    sh './gradlew assemble'
}

def archive() {
    archiveArtifacts artifacts: 'library/build/outputs/aar/*.aar', fingerprint: true
    stash includes: 'library/build/outputs/aar/*.aar', name: 'built-aar'
}

def lint() {
    sh './gradlew lint'
    androidLint canComputeNew: false, canRunOnFailed: true, defaultEncoding: '', healthy: '', pattern: '**/lint-results*.xml', unHealthy: ''
}

def publishAarToBinTray() {
    def tagName = sh(returnStdout: true, script: "git describe --tags --abbrev=0 --exact-match").trim()
    def changeLog = sh(returnStdout: true, script: "./tools/generate-changelog").trim().replaceAll(~/'/, "\'")

    withCredentials([
        [$class: 'UsernamePasswordMultiBinding', credentialsId: 'kiall-bintray', usernameVariable: 'BINTRAY_USER', passwordVariable: 'BINTRAY_KEY'],
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


def withGithubNotifier(Closure<Void> job) {
   notifyGithub('STARTED')
   catchError {
      currentBuild.result = 'SUCCESS'
      job()
   }
   notifyGithub(currentBuild.result)
}
 
def notifyGithub(String result) {
   switch (result) {
      case 'STARTED':
         setGitHubPullRequestStatus(context: env.JOB_NAME, message: "Build started", state: 'PENDING')
         break
      case 'FAILURE':
         setGitHubPullRequestStatus(context: env.JOB_NAME, message: "Build error", state: 'FAILURE')
         break
      case 'UNSTABLE':
         setGitHubPullRequestStatus(context: env.JOB_NAME, message: "Build unstable", state: 'FAILURE')
         break
      case 'SUCCESS':
         setGitHubPullRequestStatus(context: env.JOB_NAME, message: "Build finished successfully", state: 'SUCCESS')
         break
   }
}

return this;
