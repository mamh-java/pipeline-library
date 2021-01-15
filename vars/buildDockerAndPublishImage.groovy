
import io.jenkins.infra.DockerConfig
import io.jenkins.infra.InfraConfig

def call(String imageName, Map config=[:]) {

  // Initialize the groovy context
  final DockerConfig dockerConfig = new DockerConfig(imageName, config, new InfraConfig(env))

  // Retrieve Library's Static File Resources
  final String makefileContent = libraryResource 'io/jenkins/infra/docker/Makefile'
  final String podYamlTemplate = libraryResource 'io/jenkins/infra/docker/pod-template.yml'
  // Customize Pod label to improve build analysis
  final String yamlPodDef = podYamlTemplate.replaceAll('\\$IMAGE_NAME', imageName).replaceAll('\\$?\\{IMAGE_NAME\\}', imageName)

  // This function uses Declarative Syntax as we haven't met (yet) use case where only a subpart of the pipeline is needed: either we run it "whole" or not.
  // It means that the function is acting more as a "template" of an initial declarative standalone pipeline, than a real life well-constructed library.
  // A second reason is to lower the contribution bar by avoiding the "know very well Groovy" requirement (but this argument can be disputed as the test unit is pure Groovy...)
  // If you feel like to rewrite this function in scripted, you can spend this effort of course :)
  pipeline {
    agent {
      kubernetes {
        inheritFrom 'jnlp-linux'
        defaultContainer 'builder'
        yaml yamlPodDef
      } // kubernetes
    } // agent

    environment {
      BUILD_DATE = "${dockerConfig.buildDate}"
      IMAGE_NAME = "${dockerConfig.imageName}"
      DOCKERFILE = "${dockerConfig.dockerfile}"
    } // environment

    stages {
      stage('Prepare') {
        environment {
          DOCKER_REGISTRY = credentials("${dockerConfig.credentials}")
        }
        steps {
          sh 'echo "${DOCKER_REGISTRY_PSW}" | img login -u "${DOCKER_REGISTRY_USR}" --password-stdin'
          script {
            // Retrieve Makefile
            writeFile file: 'Makefile', text: makefileContent
          }
        } // steps
      } // stage

      stage("Lint") {
        steps {
          sh 'make lint'
        } // steps
        post {
          always {
            recordIssues enabledForFailure: true, tool: hadoLint(pattern: 'hadolint.json')
          }
        } // post
      } //stage

      stage("Build") {
        steps {
          sh 'make build'
        } // steps
      } //stage

      stage("Test") {
        when {
          expression { fileExists 'cst.yml' }
        }
        steps {
          sh 'make test'
        } // steps
        post {
          always {
            junit '**/*.xml'
          }
        } // post
      } //stage

      stage("Deploy") {
        when {
          anyOf {
            branch dockerConfig.mainBranch
            buildingTag()
          }
        } // when
        environment {
          DOCKER_IMAGE_TAG = "${env.TAG_NAME ? env.TAG_NAME : 'latest'}"
        }
        steps {
          sh "IMAGE_DEPLOY_NAME=${dockerConfig.getFullImageName()}:${DOCKER_IMAGE_TAG} make deploy"
        } // steps
      } //stage
    } // stages

    post {
      cleanup {
        sh 'img logout'
      } // cleanup
    } // post
  } // pipeline
} // call
