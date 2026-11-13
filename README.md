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
   Kilka apek w jednym repo (monorepo, np. `enigma2-epgscan`) — lista w `apps`, a `tag` to prefiks tagu wydań danej apki:

   ```json
   {
     "apps": [
       { "name": "E2Stream", "package": "pl.jardol.e2stream", "tag": "e2stream-", "asset": "e2stream-.*\\.apk" }
     ]
   }
   ```

   Z `tag` brana jest najwyższa wersja spośród wydań o tym prefiksie (nie musi być oznaczona jako „latest”).
3. Wydawaj nowe wersje jako GitHub Release z plikiem APK (tag `vX.Y.Z`).
   Brany jest najnowszy release, który nie jest draftem ani prerelease.

Ważne: każda wersja apki musi być podpisana **tym samym kluczem**, inaczej Android nie zainstaluje aktualizacji.

## Apki z własną aktualizacją (`selfUpdate`)

Jeśli apka sama umie się aktualizować i ma opcję „Automatyczna aktualizacja: tak/nie”, dodaj w `updater.json`
`"selfUpdate": true`. Wtedy o automatycznej aktualizacji decyduje **ustawienie w apce**, nie przełącznik w APK Updaterze:

- **tak** — APK Updater aktualizuje ją w tle (apka może też sama przy starcie),
- **nie** — APK Updater nic nie robi sam i nie powiadamia; aktualizacja tylko ręcznie z poziomu apki.

Apka udostępnia ustawienie przez provider `content://<pakiet>.updateinfo/settings` (kolumna `auto`: 1/0).
Wzór: `UpdateInfoProvider.kt` w E2Stream (`JarDol/enigma2-epgscan`, katalog `e2stream/`). Gdy apka nie ma providera
(starsza wersja), traktowane jest to jak „nie”.

## Działanie

- Lista: repo konta `JarDol` z tematem `android-app` (konto i temat można zmienić w ustawieniach).
- Dwa ekrany (przyciski `Stable` i `Beta` u góry): `Stable` pokazuje tylko wydania bez prerelease, `Beta` – najnowszą wersję apki (prerelease albo stable, co nowsze). Każdą apkę aktualizujesz osobno, więc beta dla jednej nie wpływa na pozostałe. „Aktualizuj” pojawia się tylko, gdy dostępna wersja jest nowsza od zainstalowanej.
- Przy zainstalowanej apce przycisk `Wersje` pozwala ręcznie wybrać dowolne pasujące wydanie z GitHuba, także starsze. Wybór ten nie zmienia automatycznych aktualizacji.
- Przy instalacji starszej wersji Android może ją zablokować. Wtedy może być konieczne odinstalowanie obecnej wersji i ponowna instalacja; odinstalowanie może usunąć dane apki. Starsza wersja może też nie obsłużyć danych nowszej.
- Sprawdzanie w tle i „Auto” trzymają się ścieżki, na której apka jest zainstalowana: wersja beta (`-beta.N`) śledzi betę, pozostałe – stable.
- „Auto: wł” – apka bez własnej aktualizacji aktualizowana automatycznie przy sprawdzaniu w tle
  (domyślnie tylko sam aktualizator). Apki z `selfUpdate` – wg własnego ustawienia (wyżej).
- Limit zapytań GitHub (60/h bez tokena): po jego wyczerpaniu lista zostaje z ostatnich danych, a ostrzeżenie pokazuje, za ile się odnowi; do tego czasu aplikacja nie odpytuje GitHuba. Token w ustawieniach podnosi limit do 5000/h.
- Sprawdzanie w tle co 6 h (do zmiany w ustawieniach), powiadomienie o nowych wersjach.
- Android 12+: aktualizacje apek zainstalowanych przez aktualizator idą bez pytania.
  Starsze systemy (np. Mi Box) zawsze pokażą systemowe okno „Zainstaluj”.
- Bez tokena GitHub limit to 60 zapytań/h na całą sieć domową — w zupełności wystarcza;
  token (ustawienia) potrzebny tylko dla prywatnych repo.

## Instalacja pierwszej wersji

Pobierz `apk-updater-X.Y.Z.apk` z [Releases](../../releases) (np. przez Downloader) i zainstaluj.
Przy pierwszej instalacji apki trzeba zezwolić aktualizatorowi na instalowanie aplikacji.
