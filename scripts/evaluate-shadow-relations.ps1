param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$NovelId = ''
)
$ErrorActionPreference='Stop'
$base=$BaseUrl.TrimEnd('/')
$response=Invoke-RestMethod -Uri "$base/api/novels" -TimeoutSec 20
$novels=@(); foreach($item in $response){$novels += $item}
if($NovelId){$novels=@($novels | Where-Object id -EQ $NovelId);if($novels.Count -eq 0){throw "Novel not found: $NovelId"}}
$results=@()
foreach($novel in $novels){
    $report=Invoke-RestMethod -Uri "$base/api/novels/$($novel.id)/shadow-relation-evaluation" -TimeoutSec 30
    $results += [pscustomobject]@{
        novelId=$novel.id
        title=$novel.title
        status=$report.status
        scope=$report.scope
        latencyMs=$report.latencyMs
        comparisons=$report.comparisons
        diagnostic=$report.diagnostic
    }
}
$results | ConvertTo-Json -Depth 8
if(@($results | Where-Object status -In @('MISMATCH','UNAVAILABLE')).Count -gt 0){exit 2}
exit 0
