param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$CasesPath = 'runtime/evaluations/historical-continuity-cases.local.json',
    [string]$CaseName = '',
    [string]$RunTag = '',
    [int]$TimeoutSeconds = 600
)

$ErrorActionPreference='Stop'
$base=$BaseUrl.TrimEnd('/')
$headers=@{'X-NovelForge-Request'='1'}
$resolvedCases=(Resolve-Path -LiteralPath $CasesPath).Path
$cases=@(Get-Content -LiteralPath $resolvedCases -Raw -Encoding UTF8 | ConvertFrom-Json)
if($cases.Count -lt 1){throw 'Historical continuity A/B case set is empty.'}
if($CaseName){
    $cases=@($cases | Where-Object {$_.name -eq $CaseName})
    if($cases.Count -lt 1){throw "Historical continuity A/B case not found: $CaseName"}
}

function Stable-Key([object]$Case,[string]$Variant,[object]$Candidate){
    $source="$($Case.novelId)|$($Case.chapterNumbers -join ',')|$RunTag|$Variant|$($Candidate.title)|$($Candidate.content)|$($Candidate.summary)"
    $bytes=[Text.Encoding]::UTF8.GetBytes($source)
    $hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
    return "historical-fixed-$Variant-$($hash.Substring(0,40))"
}

function Invoke-Ab([object]$Case,[string]$Variant,[object]$Candidate){
    $body=@{
        requestKey=Stable-Key $Case $Variant $Candidate
        chapterNumbers=@($Case.chapterNumbers)
        title=$Candidate.title
        content=$Candidate.content
        summary=$Candidate.summary
    } | ConvertTo-Json -Depth 8
    return Invoke-RestMethod -Method Post `
        -Uri "$base/api/novels/$($Case.novelId)/shadow-historical-structured-memory-continuity-ab" `
        -Headers $headers -ContentType 'application/json; charset=utf-8' `
        -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec $TimeoutSeconds
}

function Formal-Memory([string]$NovelId){
    $memory=Invoke-RestMethod -Uri "$base/api/novels/$NovelId/structured-memory" -TimeoutSec 30
    return [pscustomobject]@{entities=@($memory.entities).Count;relations=@($memory.relations).Count}
}

function Historical-Quotes([object]$Report){
    $quotes=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach($group in @($Report.reconstruction.aggregate.facts,$Report.reconstruction.aggregate.entities,
            $Report.reconstruction.aggregate.relations)){
        foreach($item in @($group)){
            foreach($entry in @($item.evidence)){
                if($entry.quote){[void]$quotes.Add([string]$entry.quote)}
            }
        }
    }
    return $quotes
}

function Confirmed-Quote([string]$Evidence){
    if(-not $Evidence){return ''}
    # Keep the source ASCII-only so Windows PowerShell 5.1 does not misread UTF-8 punctuation.
    $match=[regex]::Match($Evidence,'\u5df2\u786e\u8ba4\u4f9d\u636e\s*[\uFF1A:]\s*[\u201C"]([^\u201D"]+)[\u201D"]')
    if($match.Success){return $match.Groups[1].Value}
    return ''
}

function Evidence-Grounded([object]$Report){
    $issues=@($Report.historicalMemory.review.issueDetails)
    if($issues.Count -eq 0){return $false}
    $quotes=Historical-Quotes $Report
    foreach($issue in $issues){
        $cited=Confirmed-Quote ([string]$issue.evidence)
        if($cited.Length -lt 4){return $false}
        $matched=$false
        foreach($source in $quotes){
            if($source.Contains($cited) -or $cited.Contains($source)){$matched=$true;break}
        }
        if(-not $matched){return $false}
    }
    return $true
}

$rows=@();$failures=@()
foreach($case in $cases){
    $beforeView=Invoke-RestMethod -Uri "$base/api/novels/$($case.novelId)" -TimeoutSec 30
    $beforeRevision=[long]$beforeView.novel.revision
    $beforeMemory=Formal-Memory $case.novelId
    $conflict=Invoke-Ab $case 'conflict' $case.conflict
    $clean=Invoke-Ab $case 'clean' $case.clean
    $afterView=Invoke-RestMethod -Uri "$base/api/novels/$($case.novelId)" -TimeoutSec 30
    $afterMemory=Formal-Memory $case.novelId

    $expected=@($case.expectedIssueIds)
    $actual=@($conflict.historicalMemory.review.issueDetails | ForEach-Object {$_.issueId})
    $expectedFound=@($actual | Where-Object {$expected -contains $_}).Count -gt 0
    $grounded=Evidence-Grounded $conflict
    $cleanCount=[int]$clean.historicalMemory.acceptedFindings
    $differentHashes=$conflict.official.contextHash -ne $conflict.historicalMemory.contextHash `
        -and $clean.official.contextHash -ne $clean.historicalMemory.contextHash
    $unchanged=$beforeRevision -eq [long]$afterView.novel.revision `
        -and $beforeMemory.entities -eq $afterMemory.entities `
        -and $beforeMemory.relations -eq $afterMemory.relations
    $reconstructionUsable=$conflict.reconstruction.status -in @('SUCCEEDED','PARTIAL') `
        -and @($conflict.reconstruction.chapters | Where-Object status -EQ 'SUCCEEDED').Count -gt 0

    $passed=$expectedFound -and $grounded -and $cleanCount -eq 0 -and $differentHashes `
        -and $unchanged -and $reconstructionUsable
    if(-not $passed){$failures += [string]$case.name}
    $rows += [pscustomobject]@{
        Case=$case.name
        Reconstruction=$conflict.reconstruction.status
        SuccessfulChapters=@($conflict.reconstruction.chapters|Where-Object status -EQ 'SUCCEEDED').Count
        ConflictOfficial=[int]$conflict.official.acceptedFindings
        ConflictHistorical=[int]$conflict.historicalMemory.acceptedFindings
        ExpectedIssueFound=$expectedFound
        EvidenceGrounded=$grounded
        CleanHistorical=$cleanCount
        RevisionUnchanged=$unchanged
        OfficialMs=([int]$conflict.official.durationMillis+[int]$clean.official.durationMillis)
        HistoricalMs=([int]$conflict.historicalMemory.durationMillis+[int]$clean.historicalMemory.durationMillis)
        Passed=$passed
    }
}

$rows | Format-Table -AutoSize
$summary=[pscustomobject]@{
    Cases=$rows.Count
    Passed=@($rows|Where-Object Passed).Count
    ConflictRecall=@($rows|Where-Object ExpectedIssueFound).Count
    GroundedEvidence=@($rows|Where-Object EvidenceGrounded).Count
    CleanFalsePositives=@($rows|Where-Object {$_.CleanHistorical -gt 0}).Count
    MutationFailures=@($rows|Where-Object {-not $_.RevisionUnchanged}).Count
    FailedCases=$failures
}
$summary | Format-List
if($failures.Count){throw "Historical continuity A/B failed: $($failures -join ', ')"}
Write-Host 'Historical continuity A/B fixed set passed.'
