import json, urllib.request, urllib.error

text = open('/tmp/etf.md', encoding='utf-8').read()
print('总字符数:', len(text))

chunk_size = 512
chunks = [text[i:i+chunk_size] for i in range(0, len(text), chunk_size)]
print('切分块数:', len(chunks))

fail_idx = None
for idx, c in enumerate(chunks):
    body = json.dumps({'model': 'bge-m3', 'input': [c], 'dimension': 1024, 'encoding_format': 'float'}).encode('utf-8')
    req = urllib.request.Request('http://localhost:11434/v1/embeddings', data=body, headers={'Content-Type': 'application/json'})
    try:
        resp = urllib.request.urlopen(req, timeout=30)
        resp.read()
        print(f'块{idx} (字符{len(c)}): HTTP {resp.status} OK')
    except urllib.error.HTTPError as e:
        err = e.read().decode('utf-8', 'ignore')[:300]
        print(f'块{idx} (字符{len(c)}): HTTP {e.code} 失败 -> {err}')
        fail_idx = idx
        break

if fail_idx is not None:
    print(f'\n=== 失败块{fail_idx} 原文前200字符 ===')
    print(chunks[fail_idx][:200])
