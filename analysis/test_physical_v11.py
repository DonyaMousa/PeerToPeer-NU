import math
import unittest
import pandas as pd
from physical_experiment_analysis import summarize

class PhysicalAnalysisTests(unittest.TestCase):
    def row(self, event, **kw):
        d = dict(eventId=event, sessionId='s1', event=event, messageId='m1', protocol='CARBLE', runLabel='R01', conditionLabel='STABLE', packetType='EXPERIMENT', sourceNode='A', currentNode='A', destination='D', epochMs=1, elapsedMs=1)
        d.update(kw)
        return d
    def summary(self, rows):
        return summarize(pd.DataFrame(rows)).iloc[0]
    def test_missing_destination_is_unknown(self):
        r = self.summary([self.row('EXPERIMENT_PACKET_CREATED'), self.row('EXPERIMENT_END_TO_END_ACK', latencyMs=100)])
        self.assertTrue(math.isnan(r.PDR)); self.assertEqual(1, r.ackConfirmedRatio)
    def test_destination_receipt_counts(self):
        r = self.summary([self.row('EXPERIMENT_PACKET_CREATED'), self.row('EXPERIMENT_DELIVERED_AT_DESTINATION', currentNode='D')])
        self.assertEqual(1, r.PDR)
    def test_zero_requires_destination_evidence(self):
        r = self.summary([self.row('EXPERIMENT_PACKET_CREATED'), self.row('RUN_NODE_PRESENT', messageId='run-s1', currentNode='D')])
        self.assertEqual(0, r.PDR)
    def test_repeated_export_does_not_double_attempts(self):
        rows = [self.row('EXPERIMENT_PACKET_CREATED'), self.row('FORWARD_ATTEMPT')]
        r = self.summary(rows + rows)
        self.assertEqual(1, r.forwardAttemptsAllNodes)
    def test_duplicate_acks_do_not_bias_latency(self):
        rows = [self.row('EXPERIMENT_PACKET_CREATED'), self.row('EXPERIMENT_END_TO_END_ACK', latencyMs=100), self.row('EXPERIMENT_END_TO_END_ACK', eventId='later', epochMs=2, latencyMs=999)]
        self.assertEqual(100, self.summary(rows).medianAckLatencyMs)
    def test_first_hop_after_initial_carry(self):
        rows = [self.row('EXPERIMENT_PACKET_CREATED'), self.row('ROUTE_DECISION', stage='LOW', nextHop=''), self.row('ROUTE_DECISION', eventId='route2', epochMs=2, stage='HIGH', nextHop='B')]
        r = self.summary(rows)
        self.assertEqual('B:1', r.firstHopCounts); self.assertEqual('LOW:1', r.firstStageCounts)
    def test_aborted_run_is_flagged(self):
        r = self.summary([self.row('EXPERIMENT_PACKET_CREATED'), self.row('FORMAL_RUN_STOPPED')])
        self.assertTrue(r.runAborted); self.assertFalse(r.runComplete)
    def test_late_ack_does_not_rewrite_completed_run(self):
        rows = [self.row('EXPERIMENT_PACKET_CREATED'), self.row('FORMAL_RUN_COMPLETE', elapsedMs=10), self.row('EXPERIMENT_END_TO_END_ACK', elapsedMs=11, latencyMs=100)]
        r = self.summary(rows)
        self.assertEqual(0, r.ackedPackets); self.assertEqual(1, r.lateAckPackets)
    def test_sessions_not_mixed(self):
        rows = [self.row('EXPERIMENT_PACKET_CREATED'), self.row('EXPERIMENT_PACKET_CREATED', sessionId='s2', eventId='second', messageId='m2', protocol='TWO_RH')]
        self.assertEqual(2, len(summarize(pd.DataFrame(rows))))
    def test_2brh_export_aliases_are_comparable(self):
        rows = [
            self.row('EXPERIMENT_PACKET_CREATED', protocol='TWO_RH'),
            self.row('EXPERIMENT_PACKET_CREATED', sessionId='s2', eventId='second', messageId='m2', protocol='2BRH'),
        ]
        self.assertEqual({'2BRH'}, set(summarize(pd.DataFrame(rows)).protocol))

if __name__ == '__main__': unittest.main()
