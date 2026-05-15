$sourceDir = "app/src/main/java/com/chouchou/music"
$targetDir = "app/src/main/java/chouchou/movie"

Remove-Item -Recurse -Force $targetDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $targetDir -Force | Out-Null

Get-ChildItem -Path $sourceDir -Filter "*.java" | ForEach-Object {
    $bytes = [System.IO.File]::ReadAllBytes($_.FullName)
    
    $bom = [byte[]]@(0xEF, 0xBB, 0xBF)
    $startIndex = 0
    if ($bytes.Length -ge 3 -and $bytes[0] -eq $bom[0] -and $bytes[1] -eq $bom[1] -and $bytes[2] -eq $bom[2]) {
        $startIndex = 3
        Write-Host "Removing BOM from: $($_.Name)"
    }
    
    $content = [System.Text.Encoding]::UTF8.GetString($bytes, $startIndex, $bytes.Length - $startIndex)
    $content = $content.Replace("package com.chouchou.music;", "package chouchou.movie;")
    
    $outputBytes = [System.Text.Encoding]::UTF8.GetBytes($content)
    [System.IO.File]::WriteAllBytes("$targetDir\$($_.Name)", $outputBytes)
    Write-Host "Processed: $($_.Name)"
}