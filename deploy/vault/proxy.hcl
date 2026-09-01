vault {
  retry {
    num_retries = 5
  }
}

auto_auth {
  method {
    type       = "approle"
    mount_path = "auth/approle"

    config = {
      role_id_file_path                   = "/run/secrets/BEANFLOW_VAULT_ROLE_ID"
      secret_id_file_path                 = "/run/secrets/BEANFLOW_VAULT_SECRET_ID"
      remove_secret_id_file_after_reading = false
    }
  }
}

api_proxy {
  use_auto_auth_token = "force"
}

listener "tcp" {
  address     = "127.0.0.1:8100"
  tls_disable = true
}
