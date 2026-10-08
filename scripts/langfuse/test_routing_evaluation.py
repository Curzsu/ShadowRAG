import unittest
from routing_evaluation import evaluate

class RoutingTest(unittest.TestCase):
    def test_wrong_and_failure_remain_in_denominator(self):
        cases=[dict(id=str(i),expectedRoute='DIRECT',annotationStatus='pending_human_review',query='q'+str(i),split='test',history=[],tags=['test'],source='constructed') for i in range(3)]
        def row(i,route):
            return dict(id=str(i), predictedRoute=route,error=None,trace_id='a'*32,observation_id=(str(i+1)*16),policy_sha256='b'*64,selectedAttempt=1,attempts=[dict(attempt=1,predictedRoute=route,error=None,observation_id='c'*16)])
        rows=[row(0,'DIRECT'),row(1,'SEARCH'),dict(row(2,None),error='MODEL_ERROR',selectedAttempt=None)]
        report,scores=evaluate(cases,rows,'run','b'*64)
        self.assertEqual(1/3,report['accuracy'])
        self.assertEqual(1/3,report['direct_accuracy'])
        self.assertEqual(1,report['failure_count'])
        self.assertEqual('candidate_unreviewed',report['evaluation_status'])
        self.assertEqual([1,0],[s['value'] for s in scores])
        self.assertEqual('a'*32,scores[0]['traceId'])
    def test_missing_prediction_rejected(self):
        case=dict(id='1',query='q',expectedRoute='DIRECT',annotationStatus='human_reviewed',split='test',history=[],tags=['test'],source='constructed')
        with self.assertRaisesRegex(ValueError,'prediction_coverage_invalid'): evaluate([case],[],'run','hash')
    def test_all_failures_produce_zero_accuracy_without_scores(self):
        case=dict(id='1',query='q',expectedRoute='SEARCH',annotationStatus='human_reviewed',split='test',history=[],tags=['test'],source='constructed')
        row=dict(id='1',predictedRoute=None,error='MODEL_ERROR',trace_id='a'*32,observation_id='b'*16,policy_sha256='c'*64,selectedAttempt=None,attempts=[])
        report,scores=evaluate([case],[row],'run','c'*64)
        self.assertEqual(0,report['accuracy']); self.assertEqual(1,report['failure_count']); self.assertEqual([],scores)
    def test_selected_attempt_must_match(self):
        case=dict(id='1',query='q',expectedRoute='DIRECT',annotationStatus='human_reviewed',split='test',history=[],tags=['test'],source='constructed')
        row=dict(id='1',predictedRoute='DIRECT',error=None,trace_id='a'*32,observation_id='b'*16,policy_sha256='c'*64,selectedAttempt=2,attempts=[dict(attempt=1,predictedRoute='DIRECT',error=None)])
        with self.assertRaises(ValueError): evaluate([case],[row],'run','c'*64)

if __name__=='__main__': unittest.main()
