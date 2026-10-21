# Destino Azure — planejamento

Somente planejamento: nada aqui cria recursos ou exige credenciais Azure.

Mapeamento futuro: Traefik → API Management; containers → AKS; PostgreSQL → Flexible Server; Kafka → Event Hubs Kafka; Keycloak → Entra ID; segredos → Key Vault; OTel → Azure Monitor/Application Insights; imagens → ACR; evidências → Blob Storage; Redis local → Azure Managed Redis.

Event Hubs exigirá TLS/SASL próprio. Compatibilidade de produtor/consumidor não implica transações Kafka, administração de tópicos ou semântica idêntica. IaC Bicep será referência até autorização explícita para validação em nuvem.
