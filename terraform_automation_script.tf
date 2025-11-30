# ==============================================================================
# OKTA APPLICATION ONBOARDING - TERRAFORM MODULE
# ==============================================================================
# This module provides a complete, production-ready template for onboarding
# applications to Okta with OAuth 2.0/OIDC configuration
# ==============================================================================

terraform {
  required_version = ">= 1.6.0"

  required_providers {
    okta = {
      source  = "okta/okta"
      version = "~> 4.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.5"
    }
  }

  # Remote backend configuration (provided via CLI)
  backend "s3" {}
}

# ==============================================================================
# PROVIDER CONFIGURATION
# ==============================================================================

provider "okta" {
  org_name  = var.okta_org_name
  base_url  = var.okta_base_url
  # api_token should be set via OKTA_API_TOKEN environment variable
}

# ==============================================================================
# LOCAL VARIABLES
# ==============================================================================

locals {
  # Generate consistent naming
  app_label = var.app_display_name != "" ? var.app_display_name : title(replace(var.app_name, "-", " "))
  
  # Environment-specific configuration
  env_config = var.environment_config[var.environment]
  
  # Merge default tags with custom tags
  common_tags = merge(
    {
      Application  = var.app_name
      Environment  = var.environment
      ManagedBy    = "Terraform"
      CreatedBy    = "Jenkins-Automation"
      Owner        = var.owner_email
      CostCenter   = var.cost_center
      Workspace    = terraform.workspace
      LastModified = timestamp()
    },
    var.tags
  )
  
  # Grant types based on application type
  default_grant_types = {
    web     = ["authorization_code", "refresh_token"]
    spa     = ["authorization_code", "refresh_token"]
    native  = ["authorization_code", "refresh_token"]
    service = ["client_credentials"]
    browser = ["implicit"]
  }
  
  # Response types based on application type
  default_response_types = {
    web     = ["code"]
    spa     = ["code"]
    native  = ["code"]
    service = ["token"]
    browser = ["token", "id_token"]
  }
  
  # Apply environment-specific overrides
  final_grant_types    = length(var.grant_types) > 0 ? var.grant_types : local.default_grant_types[var.app_type]
  final_response_types = length(var.response_types) > 0 ? var.response_types : local.default_response_types[var.app_type]
  
  # Determine if MFA should be enforced based on environment
  enforce_mfa = var.environment == "prod" ? true : var.enforce_mfa
}

# ==============================================================================
# DATA SOURCES
# ==============================================================================

# Look up existing Okta groups
data "okta_group" "existing_groups" {
  for_each = toset(var.assign_existing_groups)
  name     = each.value
}

# Look up default authorization server
data "okta_auth_server" "default" {
  name = var.auth_server_name
}

# ==============================================================================
# OKTA OAUTH APPLICATION
# ==============================================================================

resource "okta_app_oauth" "application" {
  label                      = local.app_label
  type                       = var.app_type
  status                     = var.app_status
  
  # OAuth Configuration
  grant_types                = local.final_grant_types
  response_types             = local.final_response_types
  redirect_uris              = var.redirect_uris
  post_logout_redirect_uris  = var.post_logout_redirect_uris
  login_uri                  = var.login_uri
  
  # Token Configuration
  token_endpoint_auth_method = var.token_endpoint_auth_method
  refresh_token_rotation     = var.refresh_token_rotation
  refresh_token_leeway       = var.refresh_token_leeway
  
  # Access Token Configuration
  issuer_mode                = var.issuer_mode
  
  # Security Settings
  auto_key_rotation          = var.auto_key_rotation
  pkce_required              = var.pkce_required
  
  # CORS Settings
  cors_allowed_origins       = var.cors_allowed_origins
  
  # Login Configuration
  login_mode                 = var.login_mode
  login_scopes               = var.login_scopes
  
  # Consent Settings
  consent_method             = var.consent_method
  
  # Application Visibility
  hide_ios                   = var.hide_ios
  hide_web                   = var.hide_web
  
  # Application URLs
  policy_uri                 = var.policy_uri
  tos_uri                    = var.tos_uri
  
  # Application Metadata
  profile = jsonencode({
    environment     = var.environment
    workspace       = terraform.workspace
    managed_by      = "terraform"
    created_by      = "jenkins-automation"
    app_name        = var.app_name
    owner           = var.owner_email
    cost_center     = var.cost_center
    support_contact = var.support_contact
    description     = var.app_description
  })
  
  # Lifecycle Management
  lifecycle {
    # Prevent accidental deletion in production
    prevent_destroy = false
    
    # Ignore changes to user and group assignments (managed separately)
    ignore_changes = [
      users,
      groups
    ]
  }
  
  # Token Lifetimes
  dynamic "jwks" {
    for_each = var.jwks_uri != "" ? [1] : []
    content {
      uri = var.jwks_uri
    }
  }
}

# ==============================================================================
# GROUP ASSIGNMENTS
# ==============================================================================

# Assign existing Okta groups to the application
resource "okta_app_group_assignment" "existing_groups" {
  for_each = data.okta_group.existing_groups
  
  app_id   = okta_app_oauth.application.id
  group_id = each.value.id
  priority = var.group_assignment_priority
  
  profile = jsonencode({
    assigned_at = timestamp()
    environment = var.environment
    assigned_by = "terraform"
  })
}

# Create application-specific groups
resource "okta_group" "app_groups" {
  for_each = var.create_app_groups ? toset(var.app_group_roles) : []
  
  name        = "${var.app_name}-${each.value}-${var.environment}"
  description = "${local.app_label} ${title(each.value)} - ${upper(var.environment)} Environment"
  
  custom_profile_attributes = jsonencode({
    application = var.app_name
    environment = var.environment
    role        = each.value
    managed_by  = "terraform"
  })
}

# Assign app-specific groups to application
resource "okta_app_group_assignment" "app_groups" {
  for_each = okta_group.app_groups
  
  app_id   = okta_app_oauth.application.id
  group_id = each.value.id
  priority = var.group_assignment_priority + 10
  
  profile = jsonencode({
    assigned_at = timestamp()
    environment = var.environment
    role        = each.key
  })
}

# ==============================================================================
# USER ASSIGNMENTS
# ==============================================================================

# Direct user assignments (if specified)
resource "okta_app_user" "direct_users" {
  for_each = toset(var.assign_users)
  
  app_id   = okta_app_oauth.application.id
  user_id  = each.value
  
  # Optional: Add user-specific profile attributes
  profile = jsonencode({
    assigned_at = timestamp()
    environment = var.environment
  })
}

# ==============================================================================
# OAUTH SCOPES
# ==============================================================================

# Assign OAuth scopes to the application
resource "okta_app_oauth_api_scope" "scopes" {
  app_id = okta_app_oauth.application.id
  issuer = data.okta_auth_server.default.issuer
  scopes = var.oauth_scopes
}

# ==============================================================================
# SIGN-ON POLICY
# ==============================================================================

# Create custom sign-on policy for the application
resource "okta_app_signon_policy" "app_policy" {
  count       = var.create_signon_policy ? 1 : 0
  name        = "${var.app_name}-signon-policy-${var.environment}"
  description = "Sign-on policy for ${local.app_label} in ${upper(var.environment)}"
}

# Default policy rule
resource "okta_app_signon_policy_rule" "default_rule" {
  count     = var.create_signon_policy ? 1 : 0
  
  policy_id = okta_app_signon_policy.app_policy[0].id
  name      = "${var.app_name}-default-rule"
  priority  = 1
  
  # Access configuration
  access                      = var.policy_access
  
  # Factor requirements
  factor_mode                 = local.enforce_mfa ? "2FA" : var.factor_mode
  
  # Re-authentication settings
  re_authentication_frequency = var.re_authentication_frequency
  
  # Network constraints
  network_connection          = var.policy_network_connection
  
  # User exclusions
  users_excluded              = var.policy_users_excluded
  
  # Device assurance (if specified)
  dynamic "platform_include" {
    for_each = length(var.platform_include) > 0 ? [1] : []
    content {
      type = var.platform_include[0].type
      os_type = var.platform_include[0].os_type
    }
  }
}

# Additional policy rules for specific scenarios
resource "okta_app_signon_policy_rule" "admin_rule" {
  count     = var.create_signon_policy && var.create_admin_rule ? 1 : 0
  
  policy_id = okta_app_signon_policy.app_policy[0].id
  name      = "${var.app_name}-admin-rule"
  priority  = 0
  
  # Stricter settings for admin access
  access                      = "ALLOW"
  factor_mode                 = "2FA"
  re_authentication_frequency = "PT1H"
  
  # Only apply to admin group
  groups_included = [for g in okta_group.app_groups : g.id if strcontains(g.name, "admin")]
}

# ==============================================================================
# TRUSTED ORIGINS
# ==============================================================================

# Create trusted origins for CORS
resource "okta_trusted_origin" "app_origins" {
  for_each = toset(var.trusted_origins)
  
  name   = "${var.app_name}-${replace(each.value, "/[^a-zA-Z0-9]/", "-")}"
  origin = each.value
  scopes = ["CORS", "REDIRECT"]
  
  lifecycle {
    create_before_destroy = true
  }
}

# ==============================================================================
# APPLICATION LOGO
# ==============================================================================

# Upload application logo if provided
resource "okta_app_logo" "app_logo" {
  count  = var.app_logo_file != "" ? 1 : 0
  
  app_id = okta_app_oauth.application.id
  file   = var.app_logo_file
}

# ==============================================================================
# FEATURE FLAGS
# ==============================================================================

# Enable specific features for the application
resource "okta_app_oauth_post_logout_redirect_uri" "logout_uris" {
  count = length(var.post_logout_redirect_uris) > 0 ? 1 : 0
  
  app_id = okta_app_oauth.application.id
  uri    = var.post_logout_redirect_uris[0]
}

# ==============================================================================
# SECRETS MANAGEMENT (Optional AWS Secrets Manager Integration)
# ==============================================================================

# Generate unique identifier for secrets
resource "random_uuid" "secret_version" {
  keepers = {
    app_id = okta_app_oauth.application.id
  }
}

# Store credentials in AWS Secrets Manager (if enabled)
resource "null_resource" "store_secrets" {
  count = var.store_secrets_aws ? 1 : 0
  
  triggers = {
    app_id        = okta_app_oauth.application.id
    client_id     = okta_app_oauth.application.client_id
    client_secret = okta_app_oauth.application.client_secret
    version       = random_uuid.secret_version.result
  }
  
  provisioner "local-exec" {
    command = <<-EOT
      aws secretsmanager create-secret \
        --name "okta/${var.environment}/${var.app_name}" \
        --description "Okta credentials for ${var.app_name} in ${var.environment}" \
        --secret-string '{
          "client_id": "${okta_app_oauth.application.client_id}",
          "client_secret": "${okta_app_oauth.application.client_secret}",
          "app_id": "${okta_app_oauth.application.id}",
          "issuer": "${data.okta_auth_server.default.issuer}",
          "environment": "${var.environment}"
        }' \
        --region ${var.aws_region} \
        --tags Key=Application,Value=${var.app_name} Key=Environment,Value=${var.environment} Key=ManagedBy,Value=Terraform || \
      aws secretsmanager update-secret \
        --secret-id "okta/${var.environment}/${var.app_name}" \
        --secret-string '{
          "client_id": "${okta_app_oauth.application.client_id}",
          "client_secret": "${okta_app_oauth.application.client_secret}",
          "app_id": "${okta_app_oauth.application.id}",
          "issuer": "${data.okta_auth_server.default.issuer}",
          "environment": "${var.environment}"
        }' \
        --region ${var.aws_region}
      
      aws secretsmanager tag-resource \
        --secret-id "okta/${var.environment}/${var.app_name}" \
        --tags Key=LastRotated,Value=$(date -u +"%Y-%m-%dT%H:%M:%SZ") \
        --region ${var.aws_region}
    EOT
  }
  
  provisioner "local-exec" {
    when    = destroy
    command = "echo 'Note: Secrets remain in AWS Secrets Manager. Manual cleanup required if needed.'"
  }
  
  depends_on = [okta_app_oauth.application]
}

# ==============================================================================
# NOTIFICATIONS (Optional Slack Integration)
# ==============================================================================

resource "null_resource" "notify_deployment" {
  count = var.slack_webhook_url != "" ? 1 : 0
  
  triggers = {
    app_id = okta_app_oauth.application.id
  }
  
  provisioner "local-exec" {
    command = <<-EOT
      curl -X POST ${var.slack_webhook_url} \
        -H 'Content-Type: application/json' \
        -d '{
          "text": "🚀 New Okta Application Deployed",
          "blocks": [
            {
              "type": "section",
              "text": {
                "type": "mrkdwn",
                "text": "*Application:* ${var.app_name}\n*Environment:* ${var.environment}\n*Status:* ${okta_app_oauth.application.status}\n*App ID:* ${okta_app_oauth.application.id}"
              }
            }
          ]
        }'
    EOT
  }
  
  depends_on = [okta_app_oauth.application]
}

# ==============================================================================
# OUTPUTS
# ==============================================================================

output "app_id" {
  description = "The ID of the Okta application"
  value       = okta_app_oauth.application.id
}

output "app_name" {
  description = "The name of the application"
  value       = okta_app_oauth.application.label
}

output "client_id" {
  description = "OAuth client ID"
  value       = okta_app_oauth.application.client_id
  sensitive   = false
}

output "client_secret" {
  description = "OAuth client secret"
  value       = okta_app_oauth.application.client_secret
  sensitive   = true
}

output "sign_on_url" {
  description = "Application sign-on URL"
  value       = "https://${var.okta_org_name}.${var.okta_base_url}/app/${okta_app_oauth.application.name}/${okta_app_oauth.application.id}/sso/saml"
}

output "issuer_uri" {
  description = "OAuth issuer URI"
  value       = data.okta_auth_server.default.issuer
}

output "authorization_endpoint" {
  description = "OAuth authorization endpoint"
  value       = "${data.okta_auth_server.default.issuer}/v1/authorize"
}

output "token_endpoint" {
  description = "OAuth token endpoint"
  value       = "${data.okta_auth_server.default.issuer}/v1/token"
}

output "userinfo_endpoint" {
  description = "OIDC userinfo endpoint"
  value       = "${data.okta_auth_server.default.issuer}/v1/userinfo"
}

output "jwks_uri" {
  description = "JWKS URI for token validation"
  value       = "${data.okta_auth_server.default.issuer}/v1/keys"
}

output "app_groups" {
  description = "Application-specific groups created"
  value = {
    for k, v in okta_group.app_groups :
    k => {
      id   = v.id
      name = v.name
    }
  }
}

output "assigned_groups_count" {
  description = "Number of groups assigned to the application"
  value       = length(okta_app_group_assignment.existing_groups) + length(okta_app_group_assignment.app_groups)
}

output "oauth_scopes" {
  description = "OAuth scopes configured for the application"
  value       = var.oauth_scopes
}

output "aws_secret_name" {
  description = "AWS Secrets Manager secret name (if created)"
  value       = var.store_secrets_aws ? "okta/${var.environment}/${var.app_name}" : null
}

output "configuration_summary" {
  description = "Summary of application configuration"
  value = {
    app_id           = okta_app_oauth.application.id
    app_name         = okta_app_oauth.application.label
    app_type         = var.app_type
    environment      = var.environment
    status           = okta_app_oauth.application.status
    pkce_enabled     = var.pkce_required
    mfa_enforced     = local.enforce_mfa
    grant_types      = local.final_grant_types
    response_types   = local.final_response_types
    groups_created   = length(okta_group.app_groups)
    groups_assigned  = length(okta_app_group_assignment.existing_groups) + length(okta_app_group_assignment.app_groups)
  }
}

output "integration_guide" {
  description = "Quick integration guide URLs"
  value = {
    admin_console    = "https://${var.okta_org_name}-admin.${var.okta_base_url}/admin/app/${okta_app_oauth.application.name}/instance/${okta_app_oauth.application.id}/#tab-general"
    end_user_url     = "https://${var.okta_org_name}.${var.okta_base_url}/app/${okta_app_oauth.application.name}/${okta_app_oauth.application.id}/sso/saml"
    oidc_discovery   = "${data.okta_auth_server.default.issuer}/.well-known/openid-configuration"
    oauth_discovery  = "${data.okta_auth_server.default.issuer}/.well-known/oauth-authorization-server"
  }
}

output "security_recommendations" {
  description = "Security configuration status and recommendations"
  value = {
    pkce_status          = var.pkce_required ? "✓ Enabled (Recommended)" : "⚠ Disabled - Consider enabling"
    mfa_status           = local.enforce_mfa ? "✓ Enforced" : "⚠ Not enforced - Recommended for production"
    token_rotation       = var.refresh_token_rotation == "ROTATE" ? "✓ Enabled" : "⚠ Static tokens - Consider rotation"
    auto_key_rotation    = var.auto_key_rotation ? "✓ Enabled" : "⚠ Disabled - Recommended"
    secret_rotation_due  = "Rotate client secret every 90 days"
  }
}