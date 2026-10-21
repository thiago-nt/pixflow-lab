# Limitações

- Não há integração Pix, SPI, DICT, banco ou PSP real, nem certificação regulatória.
- A ETAPA 00 contém documentação e estrutura Maven; nenhum fluxo funcional existe.
- Kafka single-node não demonstra alta disponibilidade.
- Event Hubs não é equivalente integral ao Kafka; incompatibilidades serão `UNSUPPORTED`.
- Traefik não é APIM; Keycloak não é Entra ID; Azurite não é Blob Storage integral.
- Fencing protege atualizações locais, mas não impede sozinho efeitos externos duplicados.
- Sem idempotência e consulta confiável no PSP, incerteza exige revisão manual.
- Redis pode perder/expirar dados sem perda financeira porque nunca será autoritativo.
- Desempenho e capacidade só serão publicados após medições reproduzíveis.
