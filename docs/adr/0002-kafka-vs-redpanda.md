# ADR-0002: Event streaming platform — Apache Kafka vs Redpanda

- **Status:** proposed
- **Date:** 2026-08-02
- **Deciders:** Eshaan Jafri

## Context and Problem Statement

ReconRail's ingestion → reconciliation pipeline is event-driven and needs a
Kafka-API-compatible event streaming platform. The production host is a single
OCI Always Free ARM VM with 2 OCPUs and 12 GB RAM shared across ~6 Spring Boot
services, PostgreSQL, Redis, and the streaming platform itself. Memory is the
scarcest resource.

Apache Kafka (KRaft mode, single broker) is the industry standard and carries
the strongest interview/resume signal, but typically wants ~1 GB+ of JVM heap.
Redpanda is Kafka-API-compatible, ships as a single C++ binary with a much
smaller memory footprint, and officially supports ARM64 — but is less
universally recognized.

Decision deferred to Phase 2 (event backbone), after hands-on evaluation.

## Decision Drivers

- Must fit within the 12 GB RAM budget alongside all other services
- Learning and interview value (real Kafka vs "Kafka-compatible")
- ARM64 support and operational simplicity on Docker Compose

## Considered Options

_To be evaluated in Phase 2._

## Decision Outcome

_Pending._