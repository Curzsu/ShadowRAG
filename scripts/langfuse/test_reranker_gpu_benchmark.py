import unittest
from reranker_gpu_benchmark import validate_scores, compare_rankings

class BenchmarkTest(unittest.TestCase):
    def test_duplicate_missing_and_nonfinite_scores_are_rejected(self):
        for rows in [[{'index':0,'score':.3}]*2,[{'index':0,'score':.3}], [{'index':0,'score':float('nan')},{'index':1,'score':.2}]]:
            with self.assertRaises(ValueError): validate_scores(rows,2)
    def test_rank_change_is_not_hidden_by_unchanged_hit(self):
        sample={'keys':['a','b','c'],'relevant':['a']}
        comparison=compare_rankings(sample,[{'index':0,'score':.9},{'index':1,'score':.8},{'index':2,'score':.7}],
            [{'index':1,'score':.9},{'index':0,'score':.8},{'index':2,'score':.7}])
        self.assertFalse(comparison['exact_order_equal'])
        self.assertEqual(1,comparison['cpu']['hit_at_5']); self.assertEqual(1,comparison['gpu']['hit_at_5'])
        self.assertEqual(1,comparison['cpu']['mrr_at_5']); self.assertEqual(.5,comparison['gpu']['mrr_at_5'])

if __name__=='__main__': unittest.main()
