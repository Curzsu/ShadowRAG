$text = [System.IO.File]::ReadAllText($env:TEMP + '\etf.md', [System.Text.Encoding]::UTF8)
Write-Host ('总字符数: ' + $text.Length)

# 后端 ParseService 的真实切分逻辑未知，先按512切
$chunks = @()
for($i=0; $i -lt $text.Length; $i+=512){
    $chunks += $text.Substring($i, [Math]::Min(512, $text.Length-$i))
}
Write-Host ('单块切分数: ' + $chunks.Count)

# 模拟后端 EmbeddingClient: batch-size=10, 一次传10个文本
$batchSize = 10
$failBatch = -1
for($start=0; $start -lt $chunks.Count; $start+=$batchSize){
    $end = [Math]::Min($start+$batchSize, $chunks.Count)
    $sub = $chunks[$start..($end-1)]
    $totalChars = ($sub | Measure-Object -Property Length -Sum).Sum
    $bodyObj = @{model='bge-m3'; input=$sub; dimension=1024; encoding_format='float'}
    $bodyJson = $bodyObj | ConvertTo-Json -Compress -Depth 5
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($bodyJson)
    try {
        $resp = Invoke-WebRequest -Uri 'http://localhost:11434/v1/embeddings' -Method Post -Body $bodyBytes -ContentType 'application/json' -TimeoutSec 30 -UseBasicParsing
        Write-Host ('批次 ' + $start + '-' + ($end-1) + ' (块数=' + $sub.Count + ', 总字符=' + $totalChars + '): HTTP ' + $resp.StatusCode + ' OK')
    } catch {
        $code = $_.Exception.Response.StatusCode.value__
        $errBody = ''
        try {
            $stream = $_.Exception.Response.GetResponseStream()
            $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
            $errBody = $reader.ReadToEnd()
        } catch {}
        Write-Host ('批次 ' + $start + '-' + ($end-1) + ' (块数=' + $sub.Count + ', 总字符=' + $totalChars + '): HTTP ' + $code + ' 失败')
        Write-Host ('错误体: ' + $errBody)
        $failBatch = $start
        break
    }
}

if($failBatch -ge 0){
    Write-Host '=== 失败批次的所有文本前100字符 ==='
    $sub2 = $chunks[$failBatch..([Math]::Min($failBatch+$batchSize-1, $chunks.Count-1))]
    foreach($c in $sub2){ Write-Host ('--- ' + $c.Substring(0,[Math]::Min(100,$c.Length))) }
}
