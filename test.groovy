#!groovy​

node {

    def version
    def webAppTarget = "xxx"
    def sourceBranch = "develop"
    def releaseBranch = "quality-assurance"
    def nexusBaseRepoUrl = "http://xxx"
    def repositoryUrl = "http://xxx"
    def gitCredentialsId = "xxx"
    def nexusRepositoryId = "xxx"
    def configFileId = "xxx"
    def mvnHome = tool 'M3'

    def updateQAVersion = {
        def split = version.split('\\.')
        //always remove "-SNAPSHOT"
        split[2] = split[2].split('-SNAPSHOT')[0]
        //increment the middle number of version by 1
        split[1] = Integer.parseInt(split[1]) + 1
        //reset the last number to 0
        split[2] = 0
        version = split.join('.')
    }

    //FIXME: use SSH-Agent
   //FIXME: use SSH-Agent

sh "git config --replace-all credential.helper cache"
sh "git config --global --replace-all user.email gituser@xxx.de; git config --global --replace-all user.name gituser"

configFileProvider([configFile(fileId: "${configFileId}", variable: "MAVEN_SETTINGS")]) {

    stage('Clean') {
        deleteDir()
    }

    dir('qa') {
        stage('Checkout QA') {
                echo 'Load from GIT'
                git url: "${repositoryUrl}", credentialsId: "${gitCredentialsId}", branch: "${releaseBranch}"
       }

            stage('Increment QA version') {
                version = sh(returnStdout: true, script: "${mvnHome}/bin/mvn -q -N org.codehaus.mojo:exec-maven-plugin:1.3.1:exec -Dexec.executable='echo' -Dexec.args='\${project.version}'").toString().trim()
                echo 'Old Version:'
                echo version
                updateQAVersion()
                echo 'New Version:'
                echo version
            }

            stage('Set new QA version') {
                echo 'Clean Maven'
                sh "${mvnHome}/bin/mvn -B clean -s '$MAVEN_SETTINGS'"

                echo 'Set new version'
                sh "${mvnHome}/bin/mvn -B versions:set -DnewVersion=${version}"
            }

            stage('QA Build') {
                echo 'Execute maven build'
                sh "${mvnHome}/bin/mvn -B install -s '$MAVEN_SETTINGS'"
            }

            stage('Push new QA version') {
                echo 'Commit and push branch'
                sh "git commit -am \"New release candidate ${version}\""
                sh "git push origin ${releaseBranch}"
            }

            stage('Push new tag') {
                echo 'Tag and push'
                sh "git tag -a ${version} -m 'release tag'"
                sh "git push origin ${version}"
            }

            stage('QA artifact deploy') {
                echo 'Deploy artifact to Nexus repository'
                try {
                    sh "${mvnHome}/bin/mvn deploy:deploy-file -DpomFile=pom.xml -DrepositoryId=${nexusRepositoryId} -Durl=${nexusBaseRepoUrl} -Dfile=${webAppTarget}/target/${webAppTarget}-${version}.zip -Dpackaging=zip -s '$MAVEN_SETTINGS'"
                } catch (ex) {
                    println("Artifact could not be deployed to the nexus!")
                    println(ex.getMessage())
                }
            }

            stage('Deploy AEM Author') {
                echo 'deploy on author'
                withCredentials([usernamePassword(credentialsId: '6a613b0f-631b-453a-9f34-6a69e8676877', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
                    sh "curl -u ${USERNAME}:${PASSWORD} -F file=@\"${webAppTarget}/target/${webAppTarget}-${version}.zip\" -F force=true -F install=true http://doom.eggs.local:64592/crx/packmgr/service.jsp"
                }
            }

            stage('Deploy AEM Publish') {
                echo 'deploy on publish'
                withCredentials([usernamePassword(credentialsId: '3a25eefc-d446-4793-a621-9f15e4774126', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
                    sh "curl -u ${USERNAME}:${PASSWORD} -F file=@\"${webAppTarget}/target/${webAppTarget}-${version}.zip\" -F force=true -F install=true http://doom.eggs.local:64594/crx/packmgr/service.jsp"
                }
            }
        }

        dir('develop') {
            stage('Checkout develop') {
                echo 'Load from GIT'
                git url: "${repositoryUrl}", credentialsId: "${gitCredentialsId}", branch: "${sourceBranch}"
            }

            stage('Set new develop version') {
                echo 'Clean Maven'
                sh "${mvnHome}/bin/mvn -B clean -s '$MAVEN_SETTINGS'"

                echo 'Set new version'
                sh "${mvnHome}/bin/mvn -B versions:set -DnewVersion=${version}-SNAPSHOT"
            }

            stage('Develop Build') {
                echo 'Execute maven build'
                sh "${mvnHome}/bin/mvn -B install -s '$MAVEN_SETTINGS'"
            }

            stage('Push new develop version') {
                echo 'Commit and push branch'
                sh "git commit -am \"New QA release candidate ${version}\""
                sh "git push origin ${sourceBranch}"
            }
        }
    }

}
