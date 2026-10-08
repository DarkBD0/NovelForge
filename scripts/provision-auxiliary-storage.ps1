param(
    [string]$ConfigPath = 'config/auxiliary.local.env',
    [string]$ReferenceConfig = ''
)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$candidateConfig = if ([IO.Path]::IsPathRooted($ConfigPath)) { $ConfigPath } else { Join-Path $projectRoot $ConfigPath }
if(-not (Test-Path -LiteralPath $candidateConfig)) {
    if(-not $ReferenceConfig){throw 'Auxiliary config is missing. Copy config/auxiliary.local.example.env first.'}
    $reference=(Resolve-Path -LiteralPath $ReferenceConfig).Path
    $inside=$false;$username=$null;$password=$null
    foreach($raw in Get-Content -LiteralPath $reference -Encoding UTF8){
        $trimmed=$raw.Trim();$indent=$raw.Length-$raw.TrimStart().Length
        if($indent -eq 0 -and $trimmed -eq 'neo4j:'){$inside=$true;continue}
        if($inside -and $indent -eq 0 -and $trimmed){break}
        if(-not $inside){continue}
        if($trimmed.StartsWith('username:')){$username=$trimmed.Substring(9).Trim().Trim('"').Trim("'")}
        elseif($trimmed.StartsWith('password:')){$password=$trimmed.Substring(9).Trim().Trim('"').Trim("'")}
    }
    if(-not $username -or $null -eq $password){throw 'Neo4j settings were not found in the reference config.'}
    $localSettings=@(
        'NOVELFORGE_ES_ENABLED=false',
        'NOVELFORGE_ES_URL=http://127.0.0.1:9200',
        'NOVELFORGE_ES_INDEX=novelforge-local-memory',
        'NOVELFORGE_EMBEDDING_DIMENSIONS=1536',
        'NOVELFORGE_NEO4J_ENABLED=true',
        'NOVELFORGE_NEO4J_HTTP_URL=http://127.0.0.1:7474',
        "NOVELFORGE_NEO4J_USERNAME=$username",
        "NOVELFORGE_NEO4J_PASSWORD=$password",
        'NOVELFORGE_NEO4J_PROJECT_KEY=novelforge-local',
        'NOVELFORGE_PROJECTION_POLL_INTERVAL_MS=5000'
    )
    Set-Content -LiteralPath $candidateConfig -Value $localSettings -Encoding UTF8
    Write-Host 'Created the Git-ignored NovelForge auxiliary configuration.'
}
$resolvedConfig = (Resolve-Path -LiteralPath $candidateConfig).Path
$settings=@{}
$lineNumber=0
foreach($raw in Get-Content -LiteralPath $resolvedConfig -Encoding UTF8){
    $lineNumber++
    $line=$raw.Trim().TrimStart([char]0xFEFF)
    if(-not $line -or $line.StartsWith('#')){continue}
    $separator=$line.IndexOf('=')
    if($separator -lt 1){throw "Invalid auxiliary config line $lineNumber. Expected NAME=value."}
    $name=$line.Substring(0,$separator).Trim()
    $value=$line.Substring($separator+1).Trim().Trim('"').Trim("'")
    $settings[$name]=$value
}
$ElasticsearchUrl=$settings['NOVELFORGE_ES_URL']
$alias=if($settings.ContainsKey('NOVELFORGE_ES_INDEX')){$settings['NOVELFORGE_ES_INDEX']}else{'novelforge-local-memory'}
$Neo4jHttpUrl=$settings['NOVELFORGE_NEO4J_HTTP_URL']
$username=$settings['NOVELFORGE_NEO4J_USERNAME']
$password=$settings['NOVELFORGE_NEO4J_PASSWORD']
$projectKey=if($settings.ContainsKey('NOVELFORGE_NEO4J_PROJECT_KEY')){$settings['NOVELFORGE_NEO4J_PROJECT_KEY']}else{'novelforge-local'}
$EmbeddingDimensions=if($settings.ContainsKey('NOVELFORGE_EMBEDDING_DIMENSIONS')){[int]$settings['NOVELFORGE_EMBEDDING_DIMENSIONS']}else{1536}
if(-not $ElasticsearchUrl -or -not $Neo4jHttpUrl -or -not $username -or $null -eq $password){throw 'NovelForge auxiliary storage configuration is incomplete.'}
$indexTemplate='novelforge-local-memory-template-v1'
$physicalIndex='novelforge-local-memory-v1-000001'

$templateBody=@{
    index_patterns=@('novelforge-local-memory-v1-*')
    priority=300
    template=@{
        settings=@{number_of_shards=1;number_of_replicas=0}
        mappings=@{
            dynamic='strict'
            properties=@{
                id=@{type='keyword'};novelId=@{type='keyword'};artifactId=@{type='keyword'};sourceVersionId=@{type='keyword'}
                chapterNumber=@{type='integer'};chunkType=@{type='keyword'};authorityState=@{type='keyword'}
                validFromChapter=@{type='integer'};validToChapter=@{type='integer'};confirmed=@{type='boolean'}
                title=@{type='text';fields=@{exact=@{type='keyword';ignore_above=512};cjk=@{type='text';analyzer='cjk'}}}
                text=@{type='text';fields=@{cjk=@{type='text';analyzer='cjk'}}}
                summary=@{type='text';fields=@{cjk=@{type='text';analyzer='cjk'}}}
                entityIds=@{type='keyword'};eventIds=@{type='keyword'}
                contentHash=@{type='keyword'};embeddingModel=@{type='keyword'};createdAt=@{type='date'}
                embedding=@{type='dense_vector';dims=$EmbeddingDimensions;index=$true;similarity='cosine'}
            }
        }
    }
} | ConvertTo-Json -Depth 12
Invoke-RestMethod -Uri "$($ElasticsearchUrl.TrimEnd('/'))/_index_template/$indexTemplate" -Method Put -ContentType 'application/json' -Body $templateBody -TimeoutSec 20 | Out-Null
try { Invoke-RestMethod -Uri "$ElasticsearchUrl/$physicalIndex" -Method Head -TimeoutSec 10 | Out-Null; $indexExists=$true }
catch { $indexExists=$false }
if(-not $indexExists){
    $indexBody=@{aliases=@{$alias=@{is_write_index=$true}}} | ConvertTo-Json -Depth 5
    Invoke-RestMethod -Uri "$ElasticsearchUrl/$physicalIndex" -Method Put -ContentType 'application/json' -Body $indexBody -TimeoutSec 20 | Out-Null
}
$cjkMapping=@{properties=@{
    title=@{type='text';fields=@{cjk=@{type='text';analyzer='cjk'}}}
    text=@{type='text';fields=@{cjk=@{type='text';analyzer='cjk'}}}
    summary=@{type='text';fields=@{cjk=@{type='text';analyzer='cjk'}}}
}} | ConvertTo-Json -Depth 8
Invoke-RestMethod -Uri "$ElasticsearchUrl/$physicalIndex/_mapping" -Method Put -ContentType 'application/json' -Body $cjkMapping -TimeoutSec 20 | Out-Null
$mapping=Invoke-RestMethod -Uri "$ElasticsearchUrl/$physicalIndex/_mapping" -TimeoutSec 20
$actualDimensions=$mapping.$physicalIndex.mappings.properties.embedding.dims
if($actualDimensions -ne $EmbeddingDimensions){throw "Elasticsearch vector dimension mismatch: $actualDimensions"}
Write-Host "Elasticsearch index ready: $physicalIndex (alias $alias, dims $EmbeddingDimensions)"

$basic=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${username}:${password}"))
$headers=@{Authorization="Basic $basic"}
$statements=@(
    @{statement='CREATE CONSTRAINT nf_projection_meta IF NOT EXISTS FOR (n:NF_ProjectionMeta) REQUIRE n.projectKey IS UNIQUE'},
    @{statement='CREATE CONSTRAINT nf_artifact_identity IF NOT EXISTS FOR (n:NF_Artifact) REQUIRE (n.novelId, n.artifactId) IS UNIQUE'},
    @{statement='CREATE CONSTRAINT nf_entity_identity IF NOT EXISTS FOR (n:NF_Entity) REQUIRE (n.novelId, n.entityId) IS UNIQUE'},
    @{statement='CREATE CONSTRAINT nf_event_identity IF NOT EXISTS FOR (n:NF_Event) REQUIRE (n.novelId, n.eventId) IS UNIQUE'},
    @{statement='CREATE CONSTRAINT nf_foreshadow_identity IF NOT EXISTS FOR (n:NF_Foreshadow) REQUIRE (n.novelId, n.foreshadowId) IS UNIQUE'},
    @{statement='CREATE CONSTRAINT nf_version_identity IF NOT EXISTS FOR (n:NF_Version) REQUIRE (n.novelId, n.versionId) IS UNIQUE'},
    @{statement='CREATE CONSTRAINT nf_chapter_identity IF NOT EXISTS FOR (n:NF_Chapter) REQUIRE (n.novelId, n.chapterNumber) IS UNIQUE'}
)
$body=@{statements=$statements} | ConvertTo-Json -Depth 6
$response=Invoke-RestMethod -Uri "$Neo4jHttpUrl/db/neo4j/tx/commit" -Method Post -Headers $headers -ContentType 'application/json' -Body $body -TimeoutSec 30
if($response.errors.Count -gt 0){throw ($response.errors | ConvertTo-Json -Compress)}
$metadataBody=@{statements=@(@{statement='MERGE (m:NF_ProjectionMeta {projectKey:$projectKey}) ON CREATE SET m.createdAt=datetime() SET m.schemaVersion=3,m.updatedAt=datetime()';parameters=@{projectKey=$projectKey}})} | ConvertTo-Json -Depth 6
$metadataResponse=Invoke-RestMethod -Uri "$Neo4jHttpUrl/db/neo4j/tx/commit" -Method Post -Headers $headers -ContentType 'application/json' -Body $metadataBody -TimeoutSec 30
if($metadataResponse.errors.Count -gt 0){throw ($metadataResponse.errors | ConvertTo-Json -Compress)}
Write-Host 'Neo4j projection schema ready in the dedicated NovelForge container.'
