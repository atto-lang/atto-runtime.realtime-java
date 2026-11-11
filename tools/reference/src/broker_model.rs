//! Executable, dependency-free state-machine models for auditing Atto actors.
//! Models are NOT a broker implementation, not network services, and not guest evidence.
//! The Atto counterparts must be tested on a real compiler/runtime before promotion.

use std::collections::{BTreeMap, BTreeSet};

use crate::subject_matches;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DeliveryToken {
    pub sequence: u64,
    pub epoch: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ModelError {
    Saturated,
    InvalidCapacity,
    StaleEpoch,
    InvalidAck,
    Overflow,
    QuorumUnavailable,
    Inflight,
    Sealed,
    NoDelivery,
}

/// Explicit ACK model: tracks holes. ACK of a later delivery cannot skip an
/// earlier unacknowledged message, and unsolicited ACK cannot move the floor.
pub struct AckLedger {
    next_sequence: u64,
    ack_floor: u64,
    epoch: u64,
    maximum_unsettled: usize,
    pending: BTreeMap<u64, u32>,
    acked_beyond_floor: BTreeSet<u64>,
}

impl AckLedger {
    pub fn new(maximum_unsettled: usize) -> Result<Self, ModelError> {
        if maximum_unsettled == 0 {
            return Err(ModelError::InvalidCapacity);
        }
        Ok(Self {
            next_sequence: 1,
            ack_floor: 0,
            epoch: 1,
            maximum_unsettled,
            pending: BTreeMap::new(),
            acked_beyond_floor: BTreeSet::new(),
        })
    }

    pub fn deliver(&mut self) -> Result<DeliveryToken, ModelError> {
        if self.pending.len() + self.acked_beyond_floor.len() >= self.maximum_unsettled {
            return Err(ModelError::Saturated);
        }
        let sequence = self.next_sequence;
        self.next_sequence = self
            .next_sequence
            .checked_add(1)
            .ok_or(ModelError::Overflow)?;
        self.pending.insert(sequence, 1);
        Ok(DeliveryToken {
            sequence,
            epoch: self.epoch,
        })
    }

    pub fn redeliver(&mut self, token: &DeliveryToken) -> Result<u32, ModelError> {
        if token.epoch != self.epoch {
            return Err(ModelError::StaleEpoch);
        }
        let attempts = self
            .pending
            .get_mut(&token.sequence)
            .ok_or(ModelError::NoDelivery)?;
        *attempts = attempts.checked_add(1).ok_or(ModelError::Overflow)?;
        Ok(*attempts)
    }

    pub fn acknowledge(&mut self, token: &DeliveryToken) -> Result<u64, ModelError> {
        if token.epoch != self.epoch {
            return Err(ModelError::StaleEpoch);
        }
        if self.pending.remove(&token.sequence).is_none() {
            return Err(ModelError::InvalidAck);
        }
        self.acked_beyond_floor.insert(token.sequence);
        while let Some(next) = self.ack_floor.checked_add(1) {
            if !self.acked_beyond_floor.remove(&next) {
                break;
            }
            self.ack_floor = next;
        }
        Ok(self.ack_floor)
    }

    pub fn advance_epoch(&mut self) -> Result<(), ModelError> {
        self.epoch = self.epoch.checked_add(1).ok_or(ModelError::Overflow)?;
        // Persisted pending deliveries remain pending across restart. Old
        // delivery tokens cannot mutate the new incarnation.
        Ok(())
    }

    pub fn ack_floor(&self) -> u64 {
        self.ack_floor
    }

    pub fn in_flight(&self) -> usize {
        self.pending.len()
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AppendTicket {
    pub sequence: u64,
    pub term: u64,
    pub epoch: u64,
}

/// Single-flight ordered append gate. Durability is a trusted internal
/// dependency, not established by these vote counts alone.
pub struct AppendGate {
    replicas: u32,
    next_sequence: u64,
    committed: u64,
    term: u64,
    epoch: u64,
    inflight: Option<AppendTicket>,
    sealed: bool,
}

impl AppendGate {
    pub fn new(replicas: u32) -> Result<Self, ModelError> {
        if replicas == 0 || replicas > 9 || replicas.is_multiple_of(2) {
            return Err(ModelError::InvalidCapacity);
        }
        Ok(Self {
            replicas,
            next_sequence: 1,
            committed: 0,
            term: 1,
            epoch: 1,
            inflight: None,
            sealed: false,
        })
    }

    pub fn reserve(&mut self) -> Result<AppendTicket, ModelError> {
        if self.sealed {
            return Err(ModelError::Sealed);
        }
        if self.inflight.is_some() {
            return Err(ModelError::Inflight);
        }
        let sequence = self.next_sequence;
        self.next_sequence = sequence.checked_add(1).ok_or(ModelError::Overflow)?;
        let ticket = AppendTicket {
            sequence,
            term: self.term,
            epoch: self.epoch,
        };
        self.inflight = Some(ticket.clone());
        Ok(ticket)
    }

    pub fn commit(&mut self, ticket: &AppendTicket, durable_votes: u32) -> Result<u64, ModelError> {
        if self.sealed || ticket.term != self.term || ticket.epoch != self.epoch {
            return Err(ModelError::StaleEpoch);
        }
        if self.inflight.as_ref() != Some(ticket)
            || ticket.sequence != self.committed.checked_add(1).ok_or(ModelError::Overflow)?
        {
            return Err(ModelError::InvalidAck);
        }
        if durable_votes > self.replicas || durable_votes < self.replicas / 2 + 1 {
            return Err(ModelError::QuorumUnavailable);
        }
        self.committed = ticket.sequence;
        self.inflight = None;
        Ok(self.committed)
    }

    pub fn fence(&mut self) -> Result<(), ModelError> {
        self.sealed = true;
        self.term = self.term.checked_add(1).ok_or(ModelError::Overflow)?;
        self.epoch = self.epoch.checked_add(1).ok_or(ModelError::Overflow)?;
        // Reconcile on-disk log before becoming writable in another epoch.
        self.inflight = None;
        Ok(())
    }

    pub fn committed(&self) -> u64 {
        self.committed
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Subscription {
    pub id: u64,
    pub subject: String,
    pub queue: Option<String>,
}

/// Deterministic queue-group routing oracle. Each publication is delivered
/// to every normal subscriber and one matching member per queue group.
pub struct RouterModel {
    subscriptions: BTreeMap<u64, Subscription>,
    group_cursors: BTreeMap<String, usize>,
    maximum_subscriptions: usize,
}

impl RouterModel {
    pub fn new(maximum_subscriptions: usize) -> Self {
        Self {
            subscriptions: BTreeMap::new(),
            group_cursors: BTreeMap::new(),
            maximum_subscriptions,
        }
    }

    pub fn subscribe(&mut self, subscription: Subscription) -> Result<(), ModelError> {
        if !crate::valid_subscribe_pattern(&subscription.subject)
            || subscription
                .queue
                .as_ref()
                .is_some_and(|q| q.is_empty() || q.chars().any(char::is_whitespace))
        {
            return Err(ModelError::InvalidAck);
        }
        if !self.subscriptions.contains_key(&subscription.id)
            && self.subscriptions.len() >= self.maximum_subscriptions
        {
            return Err(ModelError::Saturated);
        }
        if self.subscriptions.contains_key(&subscription.id) {
            return Err(ModelError::InvalidAck);
        }
        self.subscriptions.insert(subscription.id, subscription);
        Ok(())
    }

    pub fn unsubscribe(&mut self, subscription_id: u64) -> bool {
        self.subscriptions.remove(&subscription_id).is_some()
    }

    pub fn recipients(&mut self, subject: &str) -> Vec<u64> {
        if !crate::valid_publish_subject(subject) {
            return Vec::new();
        }
        let mut direct = Vec::new();
        let mut grouped: BTreeMap<String, Vec<u64>> = BTreeMap::new();
        for subscription in self.subscriptions.values() {
            if !subject_matches(&subscription.subject, subject) {
                continue;
            }
            if let Some(group) = &subscription.queue {
                grouped
                    .entry(group.clone())
                    .or_default()
                    .push(subscription.id);
            } else {
                direct.push(subscription.id);
            }
        }
        for (group, eligible) in grouped {
            let cursor = self.group_cursors.entry(group).or_insert(0);
            direct.push(eligible[*cursor % eligible.len()]);
            *cursor = cursor.wrapping_add(1);
        }
        direct.sort_unstable();
        direct
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ack_hole_cannot_advance_floor() {
        let mut consumer = AckLedger::new(3).unwrap();
        let one = consumer.deliver().unwrap();
        let two = consumer.deliver().unwrap();
        let three = consumer.deliver().unwrap();
        assert_eq!(consumer.acknowledge(&three), Ok(0));
        assert_eq!(consumer.acknowledge(&two), Ok(0));
        assert_eq!(consumer.acknowledge(&one), Ok(3));
        assert_eq!(consumer.ack_floor(), 3);
        assert_eq!(consumer.in_flight(), 0);
        assert_eq!(consumer.acknowledge(&one), Err(ModelError::InvalidAck));
    }

    #[test]
    fn unsolicited_or_future_ack_is_denied() {
        let mut consumer = AckLedger::new(1).unwrap();
        assert_eq!(
            consumer.acknowledge(&DeliveryToken {
                sequence: 1000,
                epoch: 1,
            }),
            Err(ModelError::InvalidAck)
        );
        let one = consumer.deliver().unwrap();
        assert_eq!(consumer.deliver(), Err(ModelError::Saturated));
        assert_eq!(consumer.acknowledge(&one), Ok(1));
        assert_eq!(consumer.deliver().unwrap().sequence, 2);
    }

    #[test]
    fn redelivery_and_epoch_fencing() {
        let mut consumer = AckLedger::new(2).unwrap();
        let one = consumer.deliver().unwrap();
        assert_eq!(consumer.redeliver(&one), Ok(2));
        consumer.advance_epoch().unwrap();
        assert_eq!(consumer.redeliver(&one), Err(ModelError::StaleEpoch));
        assert_eq!(consumer.acknowledge(&one), Err(ModelError::StaleEpoch));
        let current = DeliveryToken {
            sequence: one.sequence,
            epoch: 2,
        };
        assert_eq!(consumer.redeliver(&current), Ok(3));
        assert_eq!(consumer.acknowledge(&current), Ok(1));
    }

    #[test]
    fn sparse_acks_are_memory_bounded() {
        let mut consumer = AckLedger::new(2).unwrap();
        let _one = consumer.deliver().unwrap();
        let two = consumer.deliver().unwrap();
        assert_eq!(consumer.acknowledge(&two), Ok(0));
        assert_eq!(consumer.deliver(), Err(ModelError::Saturated));
    }

    #[test]
    fn strict_quorum_required_before_commit() {
        let mut group = AppendGate::new(3).unwrap();
        let ticket = group.reserve().unwrap();
        assert_eq!(group.reserve(), Err(ModelError::Inflight));
        assert_eq!(group.commit(&ticket, 1), Err(ModelError::QuorumUnavailable));
        assert_eq!(group.committed(), 0);
        assert_eq!(group.commit(&ticket, 2), Ok(1));
        assert_eq!(group.committed(), 1);
    }

    #[test]
    fn old_leader_cannot_commit_after_fencing() {
        let mut group = AppendGate::new(3).unwrap();
        let ticket = group.reserve().unwrap();
        group.fence().unwrap();
        assert_eq!(group.commit(&ticket, 3), Err(ModelError::StaleEpoch));
        assert_eq!(group.reserve(), Err(ModelError::Sealed));
        assert_eq!(group.committed(), 0);
    }

    #[test]
    fn invalid_replication_factors_fail_closed() {
        assert!(AppendGate::new(0).is_err());
        assert!(AppendGate::new(2).is_err());
        assert!(AppendGate::new(10).is_err());
        assert!(AppendGate::new(1).is_ok());
    }

    #[test]
    fn queue_groups_only_choose_eligible_subscribers() {
        let mut router = RouterModel::new(10);
        for subscription in [
            Subscription {
                id: 1,
                subject: "orders.*".into(),
                queue: None,
            },
            Subscription {
                id: 2,
                subject: "orders.created".into(),
                queue: Some("workers".into()),
            },
            Subscription {
                id: 3,
                subject: "orders.shipped".into(),
                queue: Some("workers".into()),
            },
            Subscription {
                id: 4,
                subject: "orders.>".into(),
                queue: Some("workers".into()),
            },
        ] {
            router.subscribe(subscription).unwrap();
        }
        assert_eq!(router.recipients("orders.created"), vec![1, 2]);
        assert_eq!(router.recipients("orders.created"), vec![1, 4]);
        assert_eq!(router.recipients("orders.shipped"), vec![1, 3]);
        assert!(router.unsubscribe(3));
        assert_eq!(router.recipients("orders.shipped"), vec![1, 4]);
    }

    #[test]
    fn subscriptions_are_bounded_and_unique() {
        let mut router = RouterModel::new(1);
        let subscription = Subscription {
            id: 1,
            subject: ">".into(),
            queue: None,
        };
        router.subscribe(subscription.clone()).unwrap();
        assert_eq!(router.subscribe(subscription), Err(ModelError::InvalidAck));
        assert_eq!(
            router.subscribe(Subscription {
                id: 2,
                subject: "x".into(),
                queue: None,
            }),
            Err(ModelError::Saturated)
        );
        assert_eq!(router.recipients("x"), vec![1]);
    }
}
