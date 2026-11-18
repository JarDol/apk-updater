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
- Android 12+: system może pozwolić na aktualizacje bez pytania dla apek zainstalowanych przez aktualizator.
  Decyduje Android; gdy wymaga potwierdzenia, aktualizator pokazuje systemowe okno lub powiadomienie.
- Bez tokena GitHub limit to 60 zapytań/h na całą sieć domową — w zupełności wystarcza;
  token (ustawienia) potrzebny tylko dla prywatnych repo.

## Instalacja pierwszej wersji

Pobierz `apk-updater-X.Y.Z.apk` z [Releases](../../releases) (np. przez Downloader) i zainstaluj.
Przy pierwszej instalacji apki trzeba zezwolić aktualizatorowi na instalowanie aplikacji.

## Przekazanie instalacji z E2Stream

E2Stream może przekazać ręcznie wybrane wydanie do APK Updatera przez `startActivityForResult`
(lub Activity Result API). Intent wymaga akcji `pl.jardol.apkupdater.action.REQUEST_INSTALL`,
`setPackage("pl.jardol.apkupdater")` i poniższych extras z prefiksem `pl.jardol.apkupdater.extra.`:

| Extra | Wartość |
| --- | --- |
| `REPOSITORY` | `JarDol/enigma2-epgscan` |
| `PACKAGE` | `pl.jardol.e2stream` |
| `VERSION` | Pełny oryginalny `tag_name`, np. `e2stream-0.18.75-beta.1` |
| `CHANNEL` | Wymagany `stable` lub `beta`; stable nie przyjmuje prerelease |
| `ALLOW_DOWNGRADE` | Domyślnie false; true wyłącznie po ostrzeżeniu i zgodzie użytkownika E2Stream |

Nie wolno dodawać `FLAG_ACTIVITY_NEW_TASK`: Updater wymaga systemowego `callingActivity` i sprawdza,
czy jest to E2Stream podpisany certyfikatem SHA-256
`a49dd189ff764da20075deb49ab2807943b841ad63f63ba108b4d78364098da6`.
Nazwy pakietów z extras ani referrer nie służą do autoryzacji. Inne aplikacje i inne podpisy są odrzucane.
Rotacja certyfikatu wymaga świadomej aktualizacji tego przypięcia w APK Updaterze.
Na Androidzie 9 odczyt certyfikatu pobranego archiwum APK wymaga dodatkowo starszej flagi
`GET_SIGNATURES`; dla zgodności archiwa otrzymują obie flagi na API 28+, ale odczyt bieżącego
podpisu nadal wymaga `signingInfo` (bez odwołania do starszych podpisów z historii).
Sprawdzanie tożsamości zainstalowanego wywołującego pozostaje bez zmian.
Brak podpisu lub podpis niezgodny z przypiętym certyfikatem powoduje odrzucenie APK.

Updater sprawdza dokładny release w zaufanym katalogu `updater.json`, nie przyjmuje adresu APK od wywołującego.
Pobrane APK musi mieć właściwy pakiet, wersję i przypięty podpis. Zgoda na downgrade nie omija ograniczeń Androida.
Kanał i wybór ręczny nie zmieniają ustawień automatycznych aktualizacji.

`RESULT_OK` oznacza **trwałe przyjęcie zadania WorkManager**, a nie pomyślną instalację.
Zawiera `REQUEST_ID` i `MESSAGE` z tym samym prefiksem extras. Dopiero wtedy E2Stream może się zamknąć.
`RESULT_CANCELED` z `MESSAGE` oznacza błąd przyjęcia; E2Stream powinien pozostać otwarty.
Przed przyjęciem wymagane są uprawnienie instalowania aplikacji i powiadomienia z włączonym kanałem Instalacja.
Po włączeniu brakujących uprawnień użytkownik ponawia zlecenie.
Pobieranie i instalacja należą już do Updatera; system może nadal wymagać potwierdzenia przez powiadomienie.
Ostateczny wynik, anulowanie i błędy są pokazywane przez Updatera; ostatni komunikat pozostaje przy E2Stream
także po ponownym uruchomieniu Updatera.
