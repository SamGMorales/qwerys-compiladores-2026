# Genera iconos PWA con colores de marca QWERYS (sidenav #0F172A, primary #2563EB).
# Ejecutar desde la raíz del frontend: .\scripts\generate-pwa-icons.ps1

Add-Type -AssemblyName System.Drawing

$iconsDir = Join-Path $PSScriptRoot "..\src\assets\icons"
if (-not (Test-Path $iconsDir)) {
    New-Item -ItemType Directory -Path $iconsDir -Force | Out-Null
}

$bg = [System.Drawing.Color]::FromArgb(26, 26, 46)        # #1A1A2E (manifest V3)
$accent = [System.Drawing.Color]::FromArgb(22, 33, 62)   # #16213E (manifest V3)
$text = [System.Drawing.Color]::FromArgb(241, 245, 249)   # #F1F5F9

$sizes = @(72, 96, 128, 144, 152, 192, 384, 512)

foreach ($size in $sizes) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $g.Clear($bg)

    $margin = [int]($size * 0.12)
    $rect = New-Object System.Drawing.Rectangle $margin, $margin, ($size - 2 * $margin), ($size - 2 * $margin)
    $brush = New-Object System.Drawing.SolidBrush $accent
    $g.FillEllipse($brush, $rect)
    $brush.Dispose()

    $fontSize = [Math]::Max(8.0, [single]($size * 0.38))
    $font = [System.Drawing.Font]::new("Segoe UI", $fontSize, [System.Drawing.FontStyle]::Bold)
    $sf = New-Object System.Drawing.StringFormat
    $sf.Alignment = [System.Drawing.StringAlignment]::Center
    $sf.LineAlignment = [System.Drawing.StringAlignment]::Center
    $textBrush = New-Object System.Drawing.SolidBrush $text
    $g.DrawString("Q", $font, $textBrush, ($size / 2), ($size / 2), $sf)
    $textBrush.Dispose()
    $font.Dispose()
    $sf.Dispose()

    $outPath = Join-Path $iconsDir "icon-${size}x${size}.png"
    $bmp.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose()
    $bmp.Dispose()
    Write-Host "Created $outPath"
}

Write-Host "Done. Regenerar tras cambiar logo: .\scripts\generate-pwa-icons.ps1"
