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
