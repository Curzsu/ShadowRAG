import json

d = json.load(open(r'E:\Curzsu\ShadowRAG\docs\interview\eval_results.json', encoding='utf-8'))

for strat in ['bm25', 'knn', 'hybrid']:
    results = d['details'][strat]
    hs = sum(r['hit'] for r in results)
    rs = sum(r['rr'] for r in results)
    n = len(results)
    print(f"\n{'='*55}")
    print(f"策略: {strat}  (共 {n} 条)")
    print(f"{'='*55}")
    print(f"{'Q':<5} {'难度':<8} {'Hit':<5} {'RR':<6}")
    print('-'*30)
    for r in results:
        print(f"Q{r['qid']:<4} {r['difficulty']:<8} {int(r['hit']):<5} {r['rr']:.2f}")
    print('-'*30)
    print(f"求和:  Hit 总和={int(hs)}  RR 总和={rs:.2f}")
    print(f"平均:  Hit@5 = {hs}/{n} = {hs/n:.4f}")
    print(f"       MRR   = {rs:.2f}/{n} = {rs/n:.4f}")
