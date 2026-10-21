# Decisões arquiteturais

## ADR-001 — Monorepo Maven multi-module
**Status:** aceito. Aplicações deployáveis separadas e contratos enxutos em módulo próprio.

## ADR-002 — PostgreSQL como autoridade
**Status:** aceito. Idempotência, estados, Outbox/Inbox, leases, fencing e auditoria usam constraints e transações no PostgreSQL. Redis nunca é fonte de verdade.

## ADR-003 — Entrega at-least-once
**Status:** aceito. O relay pode republicar após ACK; consumidores deduplicam. Não se alega exactly-once entre Kafka, PostgreSQL e PSP.

## ADR-004 — Borda local com Traefik
**Status:** aceito. Traefik cobre funções locais básicas; APIM não é tratado como emulador offline.

## ADR-005 — Perfis opcionais
**Status:** aceito. Cache, observabilidade, segurança, Azure emulator e carga são opt-in; baseline independe de Redis.

## ADR-006 — Azure-first sem provisionamento
**Status:** aceito. O desenho considera Azure futuro, sem login, criação, cobrança ou validação em nuvem nesta fase.
