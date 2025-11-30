#!groovy
pipeline {
  agent any
  
  options {
    timestamps()
    disableConcurrentBuilds()
    buildDiscarder(logRotator(numToKeepStr: '30', daysToKeepStr: '90'))
    timeout(time: 30, unit: 'MINUTES')
  }
  
  parameters {
    string(
      name: 'APP_NAME', 
      defaultValue: 'sample-app', 
      description: 'Application name for Okta onboarding (lowercase, alphanumeric with hyphens only)'
    )
    string(
      name: 'TF_VAR_repo_url', 
      defaultValue: 'https://github.com/your-org/okta-tf-templates.git', 
      description: 'Terraform template repository URL'
    )
    choice(
      name: 'ENVIRONMENT',
      choices: ['dev', 'staging', 'prod'],
      description: 'Target environment for deployment'
    )
    string(
      name: 'GIT_BRANCH',
      defaultValue: 'main',
      description: 'Branch to checkout from template repository'
    )
    booleanParam(
      name: 'AUTO_APPLY', 
      defaultValue: false, 
      description: 'Automatically apply Terraform changes (use with caution in prod)'
    )
    booleanParam(
      name: 'DESTROY_MODE',
      defaultValue: false,
      description: 'Run terraform destroy instead of apply'
    )
  }
  
  environment {
    TF_VERSION = '1.6.0'
    TF_WORKSPACE = "${params.APP_NAME}-${params.ENVIRONMENT}"
    WORKSPACE_DIR = "workspaces/${params.APP_NAME}-${params.ENVIRONMENT}-${env.BUILD_NUMBER}"
    TF_IN_AUTOMATION = 'true'
    TF_INPUT = 'false'
    TF_PLUGIN_CACHE_DIR = "${WORKSPACE}/.terraform-cache"
    
    // Credential IDs - configure these in Jenkins credentials store
    OKTA_CREDENTIALS = credentials('okta-api-token')
    AWS_CREDENTIALS = credentials('aws-terraform-backend')
    SLACK_WEBHOOK = credentials('slack-webhook-url')
  }
  
  stages {
    stage('Input Validation') {
      steps {
        script {
          echo "=== Validating Pipeline Parameters ==="
          
          // Validate app name format
          if (!params.APP_NAME.matches('^[a-z0-9-]+$')) {
            error("APP_NAME must contain only lowercase letters, numbers, and hyphens")
          }
          
          if (params.APP_NAME.length() < 3 || params.APP_NAME.length() > 50) {
            error("APP_NAME must be between 3 and 50 characters")
          }
          
          // Validate environment-specific rules
          if (params.ENVIRONMENT == 'prod' && params.AUTO_APPLY) {
            echo "WARNING: AUTO_APPLY is enabled for PRODUCTION. Manual approval will be required."
          }
          
          // Display configuration summary
          echo """
            Configuration Summary:
            =====================
            Application Name: ${params.APP_NAME}
            Environment: ${params.ENVIRONMENT}
            Workspace: ${TF_WORKSPACE}
            Auto Apply: ${params.AUTO_APPLY}
            Destroy Mode: ${params.DESTROY_MODE}
            Git Branch: ${params.GIT_BRANCH}
          """
        }
      }
    }
    
    stage('Checkout Terraform Templates') {
      steps {
        script {
          echo "=== Checking out Terraform templates from repository ==="
          try {
            checkout([
              $class: 'GitSCM',
              branches: [[name: "*/${params.GIT_BRANCH}"]],
              userRemoteConfigs: [[
                url: params.TF_VAR_repo_url,
                credentialsId: 'git-credentials' // Configure in Jenkins
              ]],
              extensions: [
                [$class: 'CleanBeforeCheckout'],
                [$class: 'CloneOption', depth: 1, noTags: false, shallow: true]
              ]
            ])
            
            // Verify critical files exist
            sh """
              if [ ! -f "templates/main.tf" ]; then
                echo "ERROR: main.tf not found in templates directory"
                exit 1
              fi
            """
          } catch (Exception e) {
            error("Failed to checkout repository: ${e.message}")
          }
        }
      }
    }
    
    stage('Prepare Workspace') {
      steps {
        script {
          echo "=== Preparing Terraform workspace ==="
          sh """
            set -e
            
            # Create workspace directory
            mkdir -p ${WORKSPACE_DIR}
            mkdir -p ${TF_PLUGIN_CACHE_DIR}
            
            # Copy templates
            if [ -d "templates" ]; then
              cp -R templates/* ${WORKSPACE_DIR}/
              echo "Templates copied successfully"
            else
              echo "ERROR: templates directory not found"
              exit 1
            fi
            
            # Create terraform.tfvars file with parameterized values
            cat > ${WORKSPACE_DIR}/terraform.tfvars <<EOF
app_name = "${params.APP_NAME}"
environment = "${params.ENVIRONMENT}"
workspace = "${TF_WORKSPACE}"
EOF
            
            # Display workspace contents
            echo "Workspace contents:"
            ls -la ${WORKSPACE_DIR}/
          """
        }
      }
    }
    
    stage('Terraform Version Check') {
      steps {
        dir("${WORKSPACE_DIR}") {
          sh """
            terraform version
            echo "Using Terraform version: \$(terraform version | head -n1)"
          """
        }
      }
    }
    
    stage('Terraform Init') {
      steps {
        dir("${WORKSPACE_DIR}") {
          script {
            echo "=== Initializing Terraform ==="
            try {
              sh """
                set -e
                
                # Initialize with backend configuration
                terraform init \
                  -input=false \
                  -upgrade=false \
                  -backend-config="bucket=your-terraform-state-bucket" \
                  -backend-config="key=okta/${params.APP_NAME}/${params.ENVIRONMENT}/terraform.tfstate" \
                  -backend-config="region=us-east-1" \
                  -backend-config="encrypt=true" \
                  -backend-config="dynamodb_table=terraform-state-lock"
                
                echo "Terraform initialized successfully"
              """
            } catch (Exception e) {
              error("Terraform init failed: ${e.message}")
            }
          }
        }
      }
    }
    
    stage('Terraform Workspace') {
      steps {
        dir("${WORKSPACE_DIR}") {
          sh """
            set -e
            
            # Create or select workspace
            terraform workspace select ${TF_WORKSPACE} || terraform workspace new ${TF_WORKSPACE}
            
            echo "Current workspace: \$(terraform workspace show)"
          """
        }
      }
    }
    
    stage('Terraform Validate') {
      steps {
        dir("${WORKSPACE_DIR}") {
          script {
            echo "=== Validating Terraform configuration ==="
            try {
              sh """
                terraform validate -json > validation_output.json
                terraform validate
                echo "Validation successful"
              """
            } catch (Exception e) {
              sh "cat validation_output.json"
              error("Terraform validation failed: ${e.message}")
            }
          }
        }
      }
    }
    
    stage('Terraform Format Check') {
      steps {
        dir("${WORKSPACE_DIR}") {
          sh """
            set -e
            echo "=== Checking Terraform formatting ==="
            terraform fmt -check -recursive || {
              echo "WARNING: Terraform files are not properly formatted"
              echo "Run 'terraform fmt -recursive' to fix formatting"
              # Don't fail the build, just warn
            }
          """
        }
      }
    }
    
    stage('Security Scanning') {
      steps {
        dir("${WORKSPACE_DIR}") {
          script {
            echo "=== Running security scans ==="
            try {
              // Using tfsec for security scanning
              sh """
                # Install tfsec if not available
                if ! command -v tfsec &> /dev/null; then
                  echo "tfsec not found, skipping security scan"
                else
                  tfsec . --format junit > tfsec-report.xml || true
                  tfsec . --format default
                fi
              """
            } catch (Exception e) {
              echo "Security scanning completed with warnings: ${e.message}"
            }
          }
        }
      }
    }
    
    stage('Unit Testing') {
      steps {
        dir("${WORKSPACE_DIR}") {
          script {
            echo "=== Running Terraform unit tests ==="
            try {
              sh """
                # Run terraform-compliance if tests exist
                if [ -d "tests" ]; then
                  echo "Running compliance tests..."
                  
                  # Generate plan for testing
                  terraform plan -out=test.tfplan
                  terraform show -json test.tfplan > plan.json
                  
                  # Run custom validation scripts
                  if [ -f "tests/validate.sh" ]; then
                    bash tests/validate.sh
                  fi
                  
                  echo "Unit tests completed"
                else
                  echo "No tests directory found, skipping unit tests"
                fi
              """
            } catch (Exception e) {
              error("Unit tests failed: ${e.message}")
            }
          }
        }
      }
    }
    
    stage('Terraform Plan') {
      steps {
        dir("${WORKSPACE_DIR}") {
          script {
            echo "=== Generating Terraform plan ==="
            try {
              def planCommand = params.DESTROY_MODE ? 'destroy' : 'plan'
              def planFlags = params.DESTROY_MODE ? '-destroy' : ''
              
              sh """
                set -e
                
                # Set Okta credentials
                export OKTA_API_TOKEN=${OKTA_CREDENTIALS}
                
                # Generate plan
                terraform plan ${planFlags} \
                  -input=false \
                  -out=tfplan \
                  -var-file=terraform.tfvars \
                  -lock=true \
                  -lock-timeout=300s
                
                # Generate human-readable plan
                terraform show tfplan > tfplan.txt
                terraform show -json tfplan > tfplan.json
                
                # Display plan summary
                echo "=== Plan Summary ==="
                cat tfplan.txt
              """
              
              // Archive the plan for review
              archiveArtifacts artifacts: "${WORKSPACE_DIR}/tfplan.txt", allowEmptyArchive: false
              
            } catch (Exception e) {
              error("Terraform plan failed: ${e.message}")
            }
          }
        }
      }
    }
    
    stage('Manual Approval') {
      when {
        allOf {
          expression { params.AUTO_APPLY == false }
          expression { params.DESTROY_MODE == false }
        }
      }
      steps {
        script {
          echo "=== Waiting for manual approval ==="
          
          // Read plan summary
          def planSummary = sh(
            script: "head -n 50 ${WORKSPACE_DIR}/tfplan.txt",
            returnStdout: true
          ).trim()
          
          try {
            timeout(time: 60, unit: 'MINUTES') {
              input(
                message: "Review the Terraform plan and approve to apply changes",
                ok: "Apply Changes",
                parameters: [
                  text(
                    name: 'PLAN_REVIEW',
                    defaultValue: planSummary,
                    description: 'Terraform Plan Output (review before approving)'
                  )
                ]
              )
            }
          } catch (Exception e) {
            error("Manual approval timeout or rejection: ${e.message}")
          }
        }
      }
    }
    
    stage('Production Approval') {
      when {
        allOf {
          expression { params.ENVIRONMENT == 'prod' }
          expression { params.AUTO_APPLY == true }
        }
      }
      steps {
        script {
          echo "=== Production environment requires additional approval ==="
          timeout(time: 30, unit: 'MINUTES') {
            input(
              message: "PRODUCTION DEPLOYMENT - Approve to proceed",
              ok: "Deploy to Production",
              submitter: "prod-approvers" // Configure this group in Jenkins
            )
          }
        }
      }
    }
    
    stage('Terraform Apply') {
      when {
        expression { 
          params.AUTO_APPLY == true || currentBuild.result == null 
        }
      }
      steps {
        dir("${WORKSPACE_DIR}") {
          script {
            echo "=== Applying Terraform changes ==="
            try {
              sh """
                set -e
                
                # Set Okta credentials
                export OKTA_API_TOKEN=${OKTA_CREDENTIALS}
                
                # Apply the plan
                terraform apply -input=false -lock=true tfplan
                
                # Generate outputs
                terraform output -json > outputs.json
                
                echo "=== Terraform Outputs ==="
                terraform output
              """
              
              // Archive outputs
              archiveArtifacts artifacts: "${WORKSPACE_DIR}/outputs.json", allowEmptyArchive: false
              
            } catch (Exception e) {
              error("Terraform apply failed: ${e.message}")
            }
          }
        }
      }
    }
    
    stage('Terraform Destroy') {
      when {
        expression { params.DESTROY_MODE == true }
      }
      steps {
        dir("${WORKSPACE_DIR}") {
          script {
            echo "=== DESTROYING Terraform-managed infrastructure ==="
            
            timeout(time: 15, unit: 'MINUTES') {
              input(
                message: "WARNING: This will DESTROY all resources. Are you sure?",
                ok: "Yes, Destroy Resources",
                submitter: "admin" // Restrict to admins only
              )
            }
            
            try {
              sh """
                set -e
                
                # Set Okta credentials
                export OKTA_API_TOKEN=${OKTA_CREDENTIALS}
                
                # Destroy resources
                terraform destroy -auto-approve -var-file=terraform.tfvars
                
                echo "Resources destroyed successfully"
              """
            } catch (Exception e) {
              error("Terraform destroy failed: ${e.message}")
            }
          }
        }
      }
    }
    
    stage('Post-Deployment Validation') {
      when {
        allOf {
          expression { params.AUTO_APPLY == true || currentBuild.result == null }
          expression { params.DESTROY_MODE == false }
        }
      }
      steps {
        dir("${WORKSPACE_DIR}") {
          script {
            echo "=== Running post-deployment validation ==="
            try {
              sh """
                # Parse outputs and validate
                if [ -f "outputs.json" ]; then
                  echo "Validating outputs..."
                  
                  # Add custom validation logic here
                  # For example, check if app ID was created
                  if command -v jq &> /dev/null; then
                    APP_ID=\$(jq -r '.app_id.value // empty' outputs.json)
                    if [ -z "\$APP_ID" ]; then
                      echo "WARNING: app_id not found in outputs"
                    else
                      echo "Application created successfully with ID: \$APP_ID"
                    fi
                  fi
                fi
              """
            } catch (Exception e) {
              echo "Post-deployment validation completed with warnings: ${e.message}"
            }
          }
        }
      }
    }
    
    stage('Publish Artifacts') {
      steps {
        script {
          echo "=== Publishing build artifacts ==="
          
          // Archive all relevant files
          archiveArtifacts(
            artifacts: "${WORKSPACE_DIR}/tfplan,${WORKSPACE_DIR}/*.json,${WORKSPACE_DIR}/*.txt,${WORKSPACE_DIR}/*.xml",
            allowEmptyArchive: true,
            fingerprint: true
          )
        }
      }
    }
  }
  
  post {
    always {
      script {
        echo "=== Pipeline Cleanup and Reporting ==="
        
        // Publish test results
        junit(
          allowEmptyResults: true,
          testResults: "**/tfsec-report.xml,**/terraform-unittest.xml"
        )
        
        // Publish HTML reports if available
        publishHTML(
          target: [
            allowMissing: true,
            alwaysLinkToLastBuild: true,
            keepAll: true,
            reportDir: "${WORKSPACE_DIR}",
            reportFiles: '*.html',
            reportName: 'Terraform Report'
          ]
        )
      }
    }
    
    success {
      script {
        echo "=== Pipeline completed successfully ==="
        notifyBuild("SUCCESS", "Okta application onboarding completed successfully for ${params.APP_NAME}")
      }
    }
    
    failure {
      script {
        echo "=== Pipeline failed ==="
        notifyBuild("FAILED", "Okta application onboarding failed for ${params.APP_NAME}")
      }
    }
    
    unstable {
      script {
        echo "=== Pipeline completed with warnings ==="
        notifyBuild("UNSTABLE", "Okta application onboarding completed with warnings for ${params.APP_NAME}")
      }
    }
    
    aborted {
      script {
        echo "=== Pipeline was aborted ==="
        notifyBuild("ABORTED", "Okta application onboarding was aborted for ${params.APP_NAME}")
      }
    }
    
    cleanup {
      script {
        echo "=== Final cleanup ==="
        
        // Clean workspace but preserve artifacts
        // Only clean if not in destroy mode (may need to review destroy results)
        if (!params.DESTROY_MODE) {
          cleanWs(
            deleteDirs: true,
            disableDeferredWipeout: true,
            notFailBuild: true,
            patterns: [
              [pattern: '.terraform', type: 'INCLUDE'],
              [pattern: '.terraform-cache', type: 'INCLUDE'],
              [pattern: 'workspaces/**/tfplan', type: 'EXCLUDE']
            ]
          )
        }
      }
    }
  }
}

/*** Notification Functions ***/

def notifyBuild(String status, String message = "") {
  def colorMap = [
    'SUCCESS': '#36a64f',
    'FAILED': '#ff0000',
    'UNSTABLE': '#ffcc00',
    'ABORTED': '#808080'
  ]
  
  def color = colorMap[status] ?: '#808080'
  def icon = status == 'SUCCESS' ? ':white_check_mark:' : ':x:'
  
  def buildDuration = currentBuild.durationString.replace(' and counting', '')
  def changeDetails = getChangeSet()
  
  def slackMessage = """
${icon} *${status}*: ${env.JOB_NAME} #${env.BUILD_NUMBER}

*Application*: ${params.APP_NAME}
*Environment*: ${params.ENVIRONMENT}
*Duration*: ${buildDuration}
${message ? "*Message*: ${message}" : ""}

*Changes*:
${changeDetails ?: "No changes"}

<${env.BUILD_URL}|View Build> | <${env.BUILD_URL}console|Console Output>
  """.trim()
  
  try {
    slackSend(
      color: color,
      message: slackMessage,
      channel: '#okta-automation' // Configure your channel
    )
  } catch (Exception e) {
    echo "Failed to send Slack notification: ${e.message}"
  }
  
  // Also send email notification for failures
  if (status == 'FAILED') {
    emailext(
      subject: "${status}: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
      body: """
        Pipeline: ${env.JOB_NAME}
        Build Number: ${env.BUILD_NUMBER}
        Status: ${status}
        Application: ${params.APP_NAME}
        Environment: ${params.ENVIRONMENT}
        
        ${message}
        
        Check console output at: ${env.BUILD_URL}console
      """,
      recipientProviders: [developers(), requestor()],
      to: 'iam-team@yourcompany.com' // Configure your team email
    )
  }
}

@NonCPS
def getChangeSet() {
  if (currentBuild.changeSets.isEmpty()) {
    return "No changes in this build"
  }
  
  def changes = []
  def maxChanges = 5
  def changeCount = 0
  
  currentBuild.changeSets.each { cs ->
    cs.each { entry ->
      if (changeCount < maxChanges) {
        changes << "• ${entry.author.fullName}: ${entry.msg}"
        changeCount++
      }
    }
  }
  
  def result = changes.join("\n")
  if (changeCount >= maxChanges) {
    result += "\n... and more changes"
  }
  
  return result
}