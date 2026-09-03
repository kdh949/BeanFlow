path "transit/keys/beanflow-personal-data" {
  capabilities = ["read"]
}

path "transit/encrypt/beanflow-personal-data" {
  capabilities = ["update"]
}

path "transit/decrypt/beanflow-personal-data" {
  capabilities = ["update"]
}

path "transit/rewrap/beanflow-personal-data" {
  capabilities = ["update"]
}

path "transit/keys/beanflow-blind-index" {
  capabilities = ["read"]
}

path "transit/hmac/beanflow-blind-index/sha2-256" {
  capabilities = ["update"]
}
