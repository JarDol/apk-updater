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

- Ekran główny: lista podpiętych repo. Źródłem może być konto GitHub (wszystkie jego repo z tematem, domyślnie `JarDol` + `android-app`) albo pojedyncze repo (`właściciel/nazwa`). Po wejściu w repo — jego apki; WSTECZ wraca do listy. Przy jednym repo apki pokazują się od razu.
- Każde repo ma własny token (prywatne repo, wyższy limit) i własny kanał: `Stable` (tylko wydania bez prerelease) albo `Beta` (najnowsze wydanie, także prerelease) — wspólny dla wszystkich apek z tego repo. Kanał zmienisz przyciskiem `Kanał` na liście apek albo w ustawieniach repo.
- „Aktualizuj” pojawia się tylko, gdy dostępna wersja jest nowsza od zainstalowanej.
- Przycisk `Wersje` pozwala ręcznie wybrać dowolne pasujące wydanie z GitHuba, także starsze, zarówno dla zainstalowanej, jak i niezainstalowanej apki. Wybór ten nie zmienia automatycznych aktualizacji.
- Ustawienia mają sekcje: Repozytoria (dodawanie, edycja, usuwanie repo), Sprawdzanie (co ile godzin w tle) i Bezpieczeństwo (PIN uruchomienia, do 6 cyfr, wpisywany dwa razy). Każdą pozycję zmieniasz dopiero po naciśnięciu OK. Po opuszczeniu aplikacji ponowne wejście wymaga PIN-u. Sprawdzanie w tle działa niezależnie od blokady.
- Przy instalacji starszej wersji Android może ją zablokować. Wtedy może być konieczne odinstalowanie obecnej wersji i ponowna instalacja; odinstalowanie może usunąć dane apki. Starsza wersja może też nie obsłużyć danych nowszej.
- Sprawdzanie w tle i „Auto” śledzą kanał repo. Ta sama apka podpięta z kilku repo jest aktualizowana raz, do najnowszej wersji. Apki z własną aktualizacją (`selfUpdate`) i tak aktualizują się wg swoich ustawień.
- „Auto: wł” – apka bez własnej aktualizacji aktualizowana automatycznie przy sprawdzaniu w tle
  (domyślnie tylko sam aktualizator). Apki z `selfUpdate` – wg własnego ustawienia (wyżej).
- Limit zapytań GitHub (60/h bez tokena) liczony jest osobno dla każdego repo: po jego wyczerpaniu to repo zostaje z ostatnimi danymi i pokazuje, za ile limit się odnowi, a pozostałe działają dalej. Token podnosi limit do 5000/h.
- Sprawdzanie w tle co 6 h (do zmiany w ustawieniach), powiadomienie o nowych wersjach.
- Android 12+: aktualizacje apek zainstalowanych przez aktualizator idą bez pytania.
  Starsze systemy (np. Mi Box) zawsze pokażą systemowe okno „Zainstaluj”.
- Po potwierdzeniu przez Androida powodzenia konkretnej instalacji jej pobrane pliki APK i `.part`
  są usuwane. Przy anulowaniu lub błędzie pozostają w pamięci podręcznej; inne instalacje nie są sprzątane.
- Bez tokena GitHub limit to 60 zapytań/h na całą sieć domową — w zupełności wystarcza;
  token (ustawienia) potrzebny tylko dla prywatnych repo.

## Instalacja pierwszej wersji

Pobierz `apk-updater-X.Y.Z.apk` z [Releases](../../releases) (np. przez Downloader) i zainstaluj.
Przy pierwszej instalacji apki trzeba zezwolić aktualizatorowi na instalowanie aplikacji.
