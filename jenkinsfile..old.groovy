def repository = 'hydro-serving'

//Получаем версию x.x.x из файла /version
def getVersion(){
  try{
      version = sh script: "cat version", returnStdout: true ,label: "get version"
      return version.trim()
  }catch(e){
      return "file version not found" 
  }
}

def releaseGitbookDocs(desiredVersion, repository) {
   // Create and checkout the release branch
   sh "git checkout -b release-${desiredVersion}"

   // Replace all $released_version$ in .md files to desiredVersion
   sh "find docs -type f -not -path '*/\\.*' -name '*.md' -exec sed -i \"s/released[\\]_version/${desiredVersion}/g\" {} +"
   sh "git commit --allow-empty -a -m 'Releasing documentation for ${desiredVersion}'"

   pushSource(repository)
   sh "git checkout ${env.BRANCH_NAME}"
}

if (getJobType() == "RELEASE_JOB") {
  node("JenkinsOnDemand") {
    stage("Initialize") {
      cleanWs()
      loginDockerRepository()
    }

    stage("Checkout") {
      autoCheckout(repository)
    }

    stage("Create GitHub Release") {
      def curVersion = getVersion()
      def tagComment = generateTagComment()

      // Always maintain a latest version in a GitHub readme
      sh "echo ${curVersion}"
      sh "sed -i -E \"s/export HYDROSPHERE_RELEASE=.*/export HYDROSPHERE_RELEASE=${curVersion}/g\" README.md"
      sh "git commit --allow-empty -a -m 'Releasing ${curVersion}'"

      writeFile file: "/tmp/tagMessage${curVersion}", text: tagComment
      sh "git tag -a ${curVersion} --file /tmp/tagMessage${curVersion}"
      sh "git checkout ${env.BRANCH_NAME}"

      sh "cd helm && helm init --client-only --stable-repo-url https://charts.helm.sh/stable && helm package --dependency-update --version ${curVersion} serving"
      def releaseFile = "serving-${curVersion}.tgz"

      def sha = sh(script: "cd helm && shasum -a 256 -b ${releaseFile} | awk '{ print \$1 }'", returnStdout: true).trim()
      def sedCommand = "'s/[0-9]+\\.[0-9]+\\.[0-9]+\\/serving-[0-9]+\\.[0-9]+\\.[0-9]+\\.tgz/${curVersion}\\/serving-${curVersion}.tgz/g'"
      sh "cd helm && sed -i 'README.md' -E -e ${sedCommand} README.md"
      sh "cd helm && ./add_version.sh ${curVersion} ${sha}"
      
      sh "git commit --allow-empty -a -m 'Publishing new version ${curVersion}'"

      pushSource(repository)
      pushSource(repository, "refs/tags/${curVersion}")

      def releaseInfo = createReleaseInGithub(curVersion, tagComment, repository)
      def githubReleaseInfo = readJSON text: "${releaseInfo}"
      uploadFilesToGithub(githubReleaseInfo.id.toString(), "helm/" + releaseFile, "helm/" + releaseFile, repository)

      sh "git checkout gh-pages"
      sh "git merge master"
      pushSource(repository)
      sh "git checkout ${env.BRANCH_NAME}"
    }

    stage("Publish gitbook docs") {
       releaseGitbookDocs(getVersion(),repository)
    }

  }
} else {
    node("JenkinsOnDemand") {
      stage("Initialize") {
      cleanWs()
      loginDockerRepository()
    }

    stage("Checkout") {
      autoCheckout(repository)
    }

    stage("Test Release") {
      sh "cd helm && helm init --client-only --stable-repo-url https://charts.helm.sh/stable && helm dependency build serving"
      // lint
      sh "cd helm && rc=0; for chart in \$(ls -d ./*/); do helm lint \$chart || rc=\$?; done; return \$rc"
      // test
      sh "cd helm && helm template serving"
    }
  }
}
