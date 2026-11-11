# Kubernetes deployment gate

Do not apply a StatefulSet yet: there is no guest-verified native AttoNATS
binary, authenticated inter-node transport, stable Raft identities, storage
lease/backup proof, readiness checks or quorum-safety evidence.

Planned resources: 3-replica StatefulSet, per-node PVC, PodDisruptionBudget,
anti-affinity, NetworkPolicy, authenticated peer TLS, liveness/readiness probes,
rolling migration protocol and audit dashboards. Keep service private by default.
