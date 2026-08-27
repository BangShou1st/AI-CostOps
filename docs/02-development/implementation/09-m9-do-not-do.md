# M9 Scope Exclusions

Do not implement these under AIC-074–083:

```text
gateway/ runtime
Spring WebFlux / Reactor Netty Gateway
OpenAI-compatible proxy
streaming proxy
realtime budget reservation
realtime usage metering
settlement
provider routing/failover
RabbitMQ/Kafka without AIC-081 evidence decision
Kubernetes
SAML/SCIM
FX engine
ERP/GL
prompt observability/product features
```

If implementation uncovers a real V1 correctness/security defect, stop the assigned task boundary, record the defect, and handle it as a scoped bugfix rather than silently expanding M9.
