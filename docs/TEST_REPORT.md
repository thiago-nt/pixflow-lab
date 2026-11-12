# Relatório de testes

## ETAPA 00

| Verificação | Estado | Evidência |
|---|---|---|
| Pré-flight | PASS | Versões registradas no fechamento. |
| Isolamento QPO | PASS | Somente inventário de nome/estado; nenhuma mutação. |
| Portas candidatas | PASS | 8080, 55432, 9092 e 6379 livres no pré-flight. |
| Maven multi-module | PASS | `.\\mvnw.cmd -B -ntp validate`: 7 módulos, BUILD SUCCESS. |
| G01–G24 | NOT_RUN | Fora do escopo; etapas correspondentes ainda não autorizadas. |

Nenhum resultado funcional, de carga ou integração é alegado nesta etapa.

## ETAPA 01

| Verificação | Estado | Evidência |
|---|---|---|
| Compose resolvido | PASS | `docker compose -p pixflow-lab config --quiet`. |
| PostgreSQL | PASS | Healthcheck e `pg_isready`; PostgreSQL 18.4. |
| Flyway | PASS | V001 aplicada e cinco tabelas obrigatórias encontradas. |
| Kafka KRaft | PASS | Broker healthy; três tópicos explícitos, autocreate desabilitado. |
| Publish/consume | PASS | Probe UUID publicada e consumida em `payment.requested.v1`. |
| Persistência após restart | PASS | Marker Kafka recuperado e histórico Flyway V001 preservado após restart de ambos os containers. |
| Isolamento | PASS | Rede e dois volumes exclusivos com prefixo `pixflow-lab`; nenhum volume QPO referenciado. |

Os gates funcionais G01–G24 continuam `NOT_RUN`; dependem das etapas de aplicação posteriores.

## ETAPA 01A

| Verificação | Estado | Evidência |
|---|---|---|
| Compose com profile inspect | PASS | Configuração válida; portas 8085/8086 publicadas somente em 127.0.0.1. |
| Kafka UI read-only | PASS | HTTP 200; três tópicos reais e partições/offsets retornados pela API; mensagem sintética publicada e consumida. |
| PostgreSQL Viewer read-only | PASS | HTTP 200; seis tabelas visíveis por SELECT; UPDATE negado pelo usuário `pixflow_inspector`. |
| Reinício e preservação do core | PASS | Painéis reiniciados; Kafka/PostgreSQL permaneceram ativos, tópicos preservados e Flyway continuou em 1/1 migration. |

## ETAPA 02

| Verificação | Estado | Evidência |
|---|---|---|
| Java 21 / Spring Boot | PASS | Reactor Maven compilado com Java 21 e Spring Boot 3.5.7. |
| Testes automatizados | PASS | 9 testes: 2 unitários e 7 de integração/Testcontainers, sem falhas ou skips. |
| Atomicidade | PASS | Teste com falha forçada no Outbox deixa zero linhas nas quatro tabelas. |
| Idempotência concorrente | PASS | Duas execuções simultâneas resultam no mesmo UUID e uma linha em cada tabela. |
| Contrato HTTP | PASS | Novo=201, replay=200, payload divergente=409, inválido=400 e ausente=404. |
| Outbox write-only | PASS | Um evento `payment.requested.v1`, sem `published_at`; nenhum publisher implementado. |
| PixFlow Live | PASS | `/lab` HTTP 200; dados dos endpoints read-only e capacidades futuras `NOT_IMPLEMENTED`. |
| Compose | PASS | PostgreSQL, Kafka e Payment API saudáveis; inspect preservado. |

G01 está apenas parcialmente demonstrado até a gravação da Outbox. G02–G04 possuem cobertura preliminar da API/idempotência. Os demais gates continuam `NOT_RUN` por dependerem das etapas futuras.

## GATE 03 — Transactional Outbox → Kafka

| Verificação | Estado | Evidência |
|---|---|---|
| Build e testes Maven | PASS | mvnw -B -ntp -pl outbox-relay,payment-api -am test: 21 testes, zero falhas/erros/skips; Java 21. |
| Claim/expiração PostgreSQL | PASS | Testcontainers PostgreSQL 18.4: claims concorrentes sem posse duplicada; claim expirado recuperado e ACK antigo recusado. |
| ACK antes de published_at | PASS | Teste confirma ordem ACK → update; falha injetada após ACK deixa evento pendente para recuperação. |
| Configuração e stack | PASS | docker compose -p pixflow-lab config --quiet e scripts/verify.ps1 passaram; migration V003 e round-trip Kafka confirmados. |
| E2E API → Outbox → Kafka | PASS | HTTP 201; paymentId 0eeb892e-20b5-4fb1-aacb-70a1e2d330c1 e eventId aaf57b61-27e9-4a7e-bcf2-dcb49035eb77. PostgreSQL marcou published_at; consumer leu a mesma mensagem na partição 2 com chave igual ao paymentId. |
| PixFlow Live | PASS | Endpoint read-only retornou eventId 611f1053-aa6e-41e6-aad4-33e4fb42d198 e published_at persistido para o pagamento de verificação. |
| Kafka indisponível e recuperação | PASS | HTTP 201 para pagamento bef358c2-23f5-4258-b770-183caa264f07; Outbox foi observada como não publicada com last_error presente; após restart, evento c8f99a82-7dc1-403f-9a62-92ab4bc51191 chegou a attempt_count 3 com published_at preenchido. Volume preservado. |
| Kafbat UI visual | NOT_RUN | Broker validado diretamente; inspeção visual automatizada não concluiu por indisponibilidade/timeout da automação de navegador. UI disponível em http://127.0.0.1:8085. |
| Testcontainers Kafka dedicado | NOT_RUN | O E2E utilizou o broker real da stack; não foi criado container Kafka efêmero de teste. |
| Recursos | PASS | Snapshot final de docker stats: relay ~153 MiB/384 MiB; API ~160 MiB/512 MiB; Kafka ~389 MiB/1 GiB; PostgreSQL ~38 MiB/768 MiB; painéis ~326 MiB combinados. |

git diff --check passou. V003 está aplicada no volume persistente. Limitações: entrega at-least-once, possível duplicata na janela ACK/commit e ausência de consumidor idempotente nesta etapa.
