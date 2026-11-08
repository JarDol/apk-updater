# APK Updater

Aktualizator moich apek na Androida (telefon, tablet, Android TV). Sam znajduje repo na GitHubie,
pokazuje zainstalowaną i najnowszą wersję, pobiera APK i instaluje. Aktualizuje też sam siebie.

## Jak dodać nową apkę

1. W repo apki: **About → ⚙ → Topics** dodaj temat `android-app`.
2. W katalogu głównym repo dodaj plik `updater.json`:

   ```json
   {
     "name": "E2Stream",
     "package": "pl.jardol.e2stream",
     "asset": "e2stream-.*\\.apk"
   }
   ```

   - `name` – nazwa pokazywana w aktualizatorze,
   - `package` – `applicationId` apki (do sprawdzenia zainstalowanej wersji),
   - `asset` – opcjonalnie wyrażenie regularne nazwy pliku APK w release (domyślnie pierwszy `*.apk`).
3. Wydawaj nowe wersje jako GitHub Release z plikiem APK (tag `vX.Y.Z`).
   Brany jest najnowszy release, który nie jest draftem ani prerelease.

Ważne: każda wersja apki musi być podpisana **tym samym kluczem**, inaczej Android nie zainstaluje aktualizacji.

## Działanie

- Lista: repo konta `JarDol` z tematem `android-app` (konto i temat można zmienić w ustawieniach).
- „Auto: wł” – apka aktualizowana automatycznie przy sprawdzaniu w tle (domyślnie tylko sam aktualizator).
- Sprawdzanie w tle co 6 h (do zmiany w ustawieniach), powiadomienie o nowych wersjach.
- Android 12+: aktualizacje apek zainstalowanych przez aktualizator idą bez pytania.
  Starsze systemy (np. Mi Box) zawsze pokażą systemowe okno „Zainstaluj”.
- Bez tokena GitHub limit to 60 zapytań/h na całą sieć domową — w zupełności wystarcza;
  token (ustawienia) potrzebny tylko dla prywatnych repo.

## Instalacja pierwszej wersji

Pobierz `apk-updater-X.Y.Z.apk` z [Releases](../../releases) (np. przez Downloader) i zainstaluj.
Przy pierwszej instalacji apki trzeba zezwolić aktualizatorowi na instalowanie aplikacji.
