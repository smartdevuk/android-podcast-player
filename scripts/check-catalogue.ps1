param([Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string[]]$Queries)
$ErrorActionPreference = 'Stop'
$fixtureDirectory = Join-Path $PSScriptRoot '..\.tooling\catalogue'
New-Item -ItemType Directory -Path $fixtureDirectory -Force | Out-Null
$failed = 0
$results = foreach ($query in $Queries) {
    $address = 'https://itunes.apple.com/search?media=podcast&entity=podcast&country=GB&limit=1&term=' + [uri]::EscapeDataString($query)
    try {
        $data = Invoke-RestMethod -Uri $address -TimeoutSec 30
        $show = $data.results | Select-Object -First 1
        if (!$show.feedUrl) { throw 'No feed was found.' }
        $response = Invoke-WebRequest -Uri $show.feedUrl -TimeoutSec 30 -UserAgent 'Podcasts catalogue check'
        [xml]$feed = $response.Content
        $episodes = $feed.SelectNodes('//*[local-name()="item"]')
        $fixtureName = ([Guid]::NewGuid().ToString('N')) + '.xml'
        [System.IO.File]::WriteAllText((Join-Path $fixtureDirectory $fixtureName), $response.Content)
        [PSCustomObject]@{ query = $query; title = $show.collectionName; feed = $show.feedUrl; episodes = $episodes.Count; fixture = $fixtureName }
    } catch { $failed++; Write-Warning 'One catalogue query could not be verified.' }
}
ConvertTo-Json -InputObject @($results) -Depth 4 | Set-Content -LiteralPath (Join-Path $fixtureDirectory 'coverage.json') -Encoding utf8
Write-Output ('Verified {0} of {1} queries. Samples and report are in the ignored .tooling/catalogue directory.' -f @($results).Count, $Queries.Count)
if ($failed -gt 0) { exit 1 }
