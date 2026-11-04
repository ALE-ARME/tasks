# Build
- **Non compilare mai in locale**: niente `gradlew`, `assemble`, `compileKotlin`, `test`, niente Gradle, e non aprire il progetto per verificarlo. Un agente che lavora a questo repo non lancia build sul PC dell'utente
- Le modifiche si verificano **solo su GitHub Actions**:
  - `Sync Upstream Desktop Release` (`.github/workflows/sync-desktop-release.yml`): gira ogni giorno alle 04:23 UTC (o a mano), prende l'ultima release stabile di `tasks/tasks` e verifica se ha toccato il codice dell'app desktop (`composeApp`, `kmp`, `data`, `gradle`, `version.properties`, eccetera)
  - `Build Desktop MSI` (`.github/workflows/desktop-msi.yml`): se ci sono modifiche, fa il checkout del tag upstream, applica `patches/desktop-caldav-unlock.patch` con `git apply --3way` (fallback `patch -F3`), compila `:composeApp:packageMsi` su Windows e pubblica l'MSI sulla release `desktop-<tag>`
- L'MSI finisce come allegato della run e sulla release `desktop-<tag>` del fork
- La patch tocca `CoreModule.kt`, `DesktopModule.kt` e `CaldavSynchronizer.kt`: se una release upstream cambia quelle righe la build fallisce con l'errore di `git apply`, e va rigenerata la patch (non si prova a buildare in locale)
- Se una build fallisce, si corregge leggendo il log della run (`gh run view <id> --log-failed`) e ripushando: non si prova a riprodurre l'errore in locale
