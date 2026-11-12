# Artificial Russian ASR fixture

`pressure-ru.wav`: generated locally with Microsoft Irina Desktop (Windows SAPI), rate -1,
16 kHz mono signed PCM16. Text: «Запиши давление сто двадцать на восемьдесят. Пульс шестьдесят пять.»
No microphone recording or personal health data. Used only in the test APK.

This checks native decoding and parser integration, not accuracy on human voices or real phones.

Streaming regression: the fixture pauses between the measurement and pulse (silence near 4 s).
SpeechTest also inserts 0.8 s of PCM silence at sample 64000, then replays the first-endpoint
behavior used by live input in FREE and PRESSURE contexts. Both must retain pulse 65.
A second copy after 4 s of silence must remain outside the first turn. No new recording is stored.
