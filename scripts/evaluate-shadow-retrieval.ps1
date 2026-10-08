param(
    [string]$BaseUrl = 'http://127.0.0.1:8080/api',
    [string]$CasesPath = '',
    [double]$MinimumFactTop5Rate = 0.90
)

$ErrorActionPreference = 'Stop'
$headers = @{'X-NovelForge-Request'='1'}

function Decode-ApiText([string]$Text) {
    if($null -eq $Text -or -not $Text) { return $Text }
    $latin1=[Text.Encoding]::GetEncoding(28591)
    return [Text.Encoding]::UTF8.GetString($latin1.GetBytes($Text))
}

function Search([string]$NovelId,[string]$Query,[int]$BeforeChapter,[int]$Limit) {
    $body = @{query=$Query;beforeChapter=$BeforeChapter;limit=$Limit} | ConvertTo-Json
    $utf8 = [Text.Encoding]::UTF8.GetBytes($body)
    return Invoke-RestMethod -Uri "$BaseUrl/novels/$NovelId/shadow-retrievals" -Method Post `
        -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $utf8 -TimeoutSec 30
}

$novels = Invoke-RestMethod -Uri "$BaseUrl/novels" -TimeoutSec 15
$titleTotal=0; $titleTop1=0; $titleTop5=0
$boundaryFailures=@(); $sourceFailures=@(); $latencies=@()
foreach($entry in $novels) {
    if($entry.words -le 0) { continue }
    $view = Invoke-RestMethod -Uri "$BaseUrl/novels/$($entry.id)" -TimeoutSec 15
    $approved=@{}
    foreach($artifact in @($view.novel.artifacts | Where-Object {
        $_.approvedVersionId -and $_.kind -in @('CHARACTERS','CHAPTER')
    })) { $approved[$artifact.id]=$artifact.approvedVersionId }

    foreach($artifact in @($view.novel.artifacts | Where-Object {
        $_.kind -eq 'CHAPTER' -and $_.approvedVersionId
    })) {
        $version=@($artifact.versions | Where-Object {$_.id -eq $artifact.approvedVersionId})[0]
        $title=Decode-ApiText $version.title
        $run=Search $entry.id $title ($artifact.chapterNumber+1) 5
        $titleTotal++; $latencies+=@($run.latencyMs)
        $hitIds=@($run.hits | ForEach-Object {$_.artifactId})
        if($hitIds.Count -gt 0 -and $hitIds[0] -eq $artifact.id) { $titleTop1++ }
        if($hitIds -contains $artifact.id) { $titleTop5++ }
        foreach($hit in @($run.hits)) {
            if(-not $approved.ContainsKey($hit.artifactId) -or $approved[$hit.artifactId] -ne $hit.sourceVersionId) {
                $sourceFailures += "$($entry.id)/chapter-$($artifact.chapterNumber)/$($hit.artifactId)"
            }
        }
        $boundary=Search $entry.id $title $artifact.chapterNumber 10
        if(@($boundary.hits | ForEach-Object {$_.artifactId}) -contains $artifact.id) {
            $boundaryFailures += "$($entry.id)/chapter-$($artifact.chapterNumber)"
        }
    }
}

$factRows=@()
if($CasesPath) {
    $resolved=(Resolve-Path -LiteralPath $CasesPath).Path
    $cases=Get-Content -LiteralPath $resolved -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach($case in $cases) {
        $run=Search $case.novelId $case.query ([int]$case.beforeChapter) 10
        $rank=0
        for($i=0;$i -lt @($run.hits).Count;$i++) {
            if(@($case.expectedArtifactIds) -contains $run.hits[$i].artifactId) { $rank=$i+1; break }
        }
        $factRows += [pscustomobject]@{
            Query=$case.query; Rank=$rank
            TopChapter=if(@($run.hits).Count){$run.hits[0].chapterNumber}else{-1}
            TopTitle=if(@($run.hits).Count){Decode-ApiText $run.hits[0].title}else{''}
            LatencyMs=$run.latencyMs
        }
    }
}

$factPassed=@($factRows | Where-Object {$_.Rank -ge 1 -and $_.Rank -le 5}).Count
$factRate=if($factRows.Count){$factPassed/$factRows.Count}else{1.0}
$averageLatency=if($latencies.Count){[math]::Round(($latencies|Measure-Object -Average).Average,1)}else{0}
[pscustomobject]@{
    TitleCases=$titleTotal
    TitleTop1="$titleTop1/$titleTotal"
    TitleTop5="$titleTop5/$titleTotal"
    BoundaryFailures=$boundaryFailures.Count
    SourceOrIsolationFailures=$sourceFailures.Count
    FactTop5="$factPassed/$($factRows.Count)"
    AverageTitleLatencyMs=$averageLatency
} | Format-List
if($factRows.Count) { $factRows | Format-Table -AutoSize }

if($titleTop5 -ne $titleTotal) { throw 'Some confirmed chapter titles were not retrieved in the top five.' }
if($boundaryFailures.Count) { throw "Chapter boundary leakage: $($boundaryFailures -join '; ')" }
if($sourceFailures.Count) { throw "Novel isolation or approved-source mismatch: $($sourceFailures -join '; ')" }
if($factRate -lt $MinimumFactTop5Rate) { throw "Fact retrieval top-five rate $factRate is below $MinimumFactTop5Rate." }
Write-Host 'Shadow retrieval evaluation passed.'
