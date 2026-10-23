# PixFlow Lab

Laboratório local de pagamentos Pix **inteiramente fictícios**, orientado a Azure e voltado ao estudo de processamento assíncrono confiável. Não há conectividade com Pix/SPI/DICT, PSP real ou recursos Azure nesta POC.

## Estado atual

Este repositório concluiu a **ETAPA 01**: PostgreSQL 18.4 Alpine, Kafka 4.1.1 KRaft e migrations Flyway 11.20.2. Microsserviços Java ainda não foram implementados.

## Pré-requisitos verificados

- Windows 11 e PowerShell 5.1
- Java 21 LTS
- Maven 3.9.10 (o Maven Wrapper é fixado em 3.9.10)
- Docker Desktop 28.0.4 e Docker Compose 2.34.0
- WSL 2.4.13, kernel 5.15.167.4
- Git 2.47.1

O Docker Desktop disponibilizou aproximadamente 1,93 GiB ao engine no pré-flight. Antes de iniciar a stack completa na ETAPA 01, será necessário revisar uma ampliação segura desse limite; nenhuma configuração do Docker/WSL será alterada sem aprovação. Perfis opcionais não subirão por padrão.

## Estrutura planejada

```text
pixflow-lab/
|-- shared-contracts/
|-- payment-api/
|-- outbox-relay/
|-- payment-worker/
|-- psp-simulator/
|-- reconciliation-worker/
|-- docs/
`-- deploy/azure/
```

Os módulos são cascas Maven deliberadamente vazias nesta etapa. Código Spring Boot, banco, Kafka e Docker Compose entram somente nas etapas autorizadas seguintes.

## Build da ETAPA 00

```powershell
cd C:\poc\pixflow-lab
.\mvnw.cmd -B -ntp validate
git status --short
```

## Infraestrutura local

```powershell
cd C:\poc\pixflow-lab
.\scripts\bootstrap.ps1 # cria .env somente se ausente, sobe e valida
.\scripts\status.ps1
.\scripts\verify.ps1
.\scripts\logs.ps1
.\scripts\down.ps1      # preserva volumes
```

PostgreSQL fica disponível apenas no loopback em `127.0.0.1:55432`; Kafka em `127.0.0.1:19092`. Entre containers, use `postgres:5432` e `kafka:9092`. Consulte [Infraestrutura](docs/INFRASTRUCTURE.md).

### Visualização opcional da infraestrutura

Os painéis do profile `inspect` são locais, opcionais e não sobem com o baseline:

```powershell
cd C:\poc\pixflow-lab
.\scripts\inspect.ps1 Start
.\scripts\inspect.ps1 Status
```

- Kafka UI: http://127.0.0.1:8085 — tópicos, partições, mensagens e consumer groups em modo read-only.
- PostgreSQL Viewer: http://127.0.0.1:8086 — schemas, tabelas, índices e consultas `SELECT` com usuário SQL restrito.

Para inspecionar Kafka, abra o cluster `pixflow-lab` e selecione um dos tópicos `payment.*.v1`. No pgweb, navegue pelo schema `public` ou execute apenas consultas como `SELECT * FROM flyway_schema_history ORDER BY installed_rank`.

Pare somente os painéis, sem afetar PostgreSQL ou Kafka:

```powershell
.\scripts\inspect.ps1 Stop
```

O script gera uma senha aleatória em `secrets/pgweb_password.txt`, já ignorada pelo Git. O pgweb também usa `--readonly`, e o usuário `pixflow_inspector` não possui privilégios de escrita. Limites adicionais: Kafka UI 512 MiB/0,5 CPU; pgweb 128 MiB/0,25 CPU; inicializador SQL 128 MiB/0,25 CPU.

## Isolamento obrigatório

- Todo futuro comando Compose deve usar `docker compose -p pixflow-lab ...` dentro deste repositório.
- Não usar `container_name`; rede, volumes e recursos serão gerenciados pelo projeto `pixflow-lab`.
- O container existente `qpo-postgres-phase09` e seus volumes não podem ser parados, reiniciados, removidos, renomeados ou modificados.
- Não executar prune/reset global. Um futuro reset deverá exigir confirmação e atuar apenas nos recursos desta POC.
- Portas 8080, 55432, 9092 e 6379 estavam livres no pré-flight, mas deverão ser verificadas novamente imediatamente antes do uso.

## Documentação

- [Arquitetura](docs/ARCHITECTURE.md)
- [Decisões](docs/DECISOES.md)
- [Limitações](docs/LIMITACOES.md)
- [Relatório de testes](docs/TEST_REPORT.md)
- [Infraestrutura](docs/INFRASTRUCTURE.md)
- [Destino Azure](deploy/azure/README.md)

## Segurança

Use somente dados sintéticos. Não versione `.env`, tokens, chaves, dumps, credenciais, dados bancários ou pessoais. Redis será apenas cache auxiliar descartável e PostgreSQL permanecerá autoritativo.
