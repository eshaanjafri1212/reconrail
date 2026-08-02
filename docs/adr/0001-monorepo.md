# ADR-0001: Single monorepo for all ReconRail services

- **Status:** accepted
- **Date:** 2026-08-01
- **Deciders:** Eshaan Jafri

## Context and Problem Statement

ReconRail consists of ~6 Spring Boot services, an Angular frontend, and shared
infrastructure config. As a solo builder, I need to decide: one repository or
one repo per service?

## Decision Drivers

- Solo developer: minimize repo-management overhead
- Cross-service changes (e.g., a shared event schema) should be atomic — one PR
- Single CI pipeline and one place for docs/ADRs
- Portfolio visibility: one repo concentrates commits, PRs, and history

## Considered Options

1. Monorepo — all services as modules in one repository
2. Polyrepo — one repository per service

## Decision Outcome

Chosen option: **Monorepo**, because atomic cross-service changes, unified CI,
and concentrated portfolio signal outweigh polyrepo's independence benefits,
which mainly pay off for multiple teams — a constraint I don't have.

### Consequences

- Good: one clone, one CI config, atomic schema changes, single source of truth
- Bad: CI must use path filters so a frontend change doesn't rebuild all
  services; module boundaries need discipline since the compiler won't
  enforce repo separation
- Revisit when: multiple contributors own separate services, or services need
  independent release cadences