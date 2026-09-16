# ADR-0002: Java 21 + Spring Boot, PostgreSQL, React + TypeScript

**Status:** Accepted · **Date:** 2026-09-16

## Context
Small team; founder knows basic Java, SQL and HTML. Domain needs exact decimal math, XML (MeF), PDF form filling, strong typing and long-term maintainability. Must run on a modest VPS.

## Decision
- Backend: **Java 21, Spring Boot 3, Spring Modulith**, Flyway migrations, jOOQ or Spring Data JPA (jOOQ preferred for ledger queries)
- DB: **PostgreSQL 16+**
- Frontend: **React + TypeScript + Vite**, TanStack Query, a component library (e.g., Mantine or shadcn/ui), OpenAPI-generated client
- Packaging: Docker Compose, Caddy reverse proxy
- AI: Ollama container (optional profile)

## Alternatives
| Option | Why not |
|---|---|
| TypeScript/NestJS full stack | One language, but weaker decimal/XML/PDF tooling and founder already knows Java |
| PHP/WordPress plugin | Fits current hosting but poor fit for a financial system of record |
| Python/Django | Great for tax logic, but best tax libs are AGPL; Java better for typed money & JAXB |
| MySQL | Weaker RLS/constraints/NUMERIC handling than Postgres |

## Consequences
- Learning curve on React/TypeScript — mitigate with a simple, consistent component set.
- JVM memory footprint (~512 MB–1 GB) sets minimum VPS size at 4 GB.
