# Creates clearly labelled synthetic test audio using installed Windows voices.
Add-Type -AssemblyName System.Speech
$fixtureRoot = Join-Path $PSScriptRoot '..\tests\audio'
New-Item -ItemType Directory -Force -Path $fixtureRoot | Out-Null
$format = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
$utterances = @(
    @{ file='a1'; voice='Microsoft Irina Desktop'; text='Добрый день. Предлагаю начать обсуждение проекта. Сегодня мы проверяем работу приложения. Все записи остаются на нашем устройстве.' },
    @{ file='b1'; voice='Microsoft Pavel'; text='Здравствуйте. Я подготовил результаты тестирования. Распознавание речи работает без подключения к интернету. Давайте посмотрим на результаты.' },
    @{ file='a2'; voice='Microsoft Irina Desktop'; text='Спасибо за проверку. Теперь вернёмся к нашему проекту. Нам нужно сохранить расшифровку разговора и подготовить демонстрацию.' }
)
foreach ($utterance in $utterances) {
    $synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
    try {
        $synth.SelectVoice($utterance.voice)
        $synth.SetOutputToWaveFile((Join-Path $fixtureRoot ($utterance.file + '.wav')), $format)
        $synth.Speak($utterance.text)
    } finally { $synth.Dispose() }
}
$utterances | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $fixtureRoot 'utterances.json') -Encoding utf8
