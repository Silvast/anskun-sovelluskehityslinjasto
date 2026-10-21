# anskun-sovelluskehityslinjasto

Agenttinen sovelluskehityslinjasto harrasteprojekteille. Agentti ottaa GitHub-issueita priorisoidussa järjestyksessä, toteuttaa ne worktreessä ja avaa PR:n. Kaksi katselmoijaa ja fixer pallottelevat, kunnes blokkaavia löydöksiä ei ole, ja PR yhdistyy `develop`-haaraan. Sinä viet `developin` `mainiin`.

```
 OMA KONE (Pi)                                 GITHUB ACTIONS (Claude Code)
 ┌───────────────────────────────────────┐     ┌──────────────────────────────────────────┐
 │ agent:ready → worktree → implementer  │     │ reviewer A → reviewer B → (fixer → push) │
 │            → testit → push → PR ──────┼────►│   0 blokkaavaa → auto-merge developiin   │
 └───────────────────────────────────────┘     │   kierros > max → agent:needs-human      │
                                               └──────────────────────────────────────────┘
                                                        sinä: develop → main
```

Orkestroija on tavallista Babashka-koodia. Agentit tekevät vain rajatun työn: yksi ajo, yksi rooli. Kaikki tila on GitHubissa (labelit, PR:t, katselmoinnit), joten mikä tahansa ajo voi kaatua ja seuraava jatkaa.

## Vaatimukset

Omalla koneella: [Babashka](https://babashka.org), `git`, [`gh`](https://cli.github.com) kirjautuneena, [Pi](https://pi.dev).
Actionsissa kaikki asennetaan automaattisesti.

## Käyttöönotto harrasteprojektissa

1. **Konfiguraatio.** Kopioi `examples/project/.agent/config.edn` ja `examples/project/AGENTS.md` projektiisi ja täytä ne. `AGENTS.md` on laadun kannalta tärkein tiedosto.
2. **Labelit.** Projektin hakemistossa:
   ```bash
   bb --config ~/src/anskun-sovelluskehityslinjasto/bb.edn setup-labels
   ```
3. **Haarat.** Luo `develop`. Asetuksissa:
   - Settings → General → *Allow auto-merge* päälle.
   - Branch protection `develop`ille: vaadi CI-tarkistukset. Auto-merge odottaa niitä.
   - Branch protection `main`ille: vain sinä mergeät.
4. **Secretit** (Settings → Secrets and variables → Actions):
   - `CLAUDE_CODE_OAUTH_TOKEN`: aja omalla koneella `claude setup-token` (Pro/Max-tilaus).
   - `PIPELINE_TOKEN`: fine-grained PAT vain tälle repolle, oikeudet *Contents*, *Pull requests* ja *Issues* (read/write). Oletus-`GITHUB_TOKEN` ei kelpaa, koska sillä tehty push ei käynnistä CI:tä eikä seuraavaa review-kierrosta.
5. **Workflow.** Kopioi `examples/project/.github/workflows/linjasto.yml` projektiin.
   Jos tämä linjastorepo on yksityinen, salli sen workflowien käyttö: tämän repon Settings → Actions → General → *Access* → "Accessible from repositories owned by the user".
6. **Kokeile käsin**, ennen kuin ajastat mitään:
   ```bash
   cd ~/src/oma-projekti
   bb --config ~/src/anskun-sovelluskehityslinjasto/bb.edn run-issue 42
   ```
7. **Yöajo.** Kopioi `systemd/`-tiedostot `~/.config/systemd/user/`-kansioon, korjaa polut ja:
   ```bash
   systemctl --user daemon-reload
   systemctl --user enable --now linjasto.timer
   ```

## Komennot

Aja projektin hakemistossa (tai anna `--project <hakemisto>`):

| Komento | Missä | Mitä |
|---|---|---|
| `bb ohje` | | Komennot |
| `bb config` | | Yhdistetty konfiguraatio |
| `bb setup-labels` | kone | Luo labelit |
| `bb next` | kone | Prioriteettijono |
| `bb run-issue 42` | kone | Toteuta issue ja avaa PR |
| `bb run-next` | kone | Orkestroija: ota jonosta enintään `:max-issues-per-run` issueta, toteuta, katselmoi ja mergeä developiin (lukittu) |
| `bb sync` | kone | Päivitä auto-mergellä myöhemmin mergetyt PR:t release-PR:ään |
| `bb review-round 17` | Actions | Yksi katselmointikierros |
| `bb review-loop 17` | kone | Kierroksia kunnes valmis, testaukseen ilman Actionsia |
| `bb mark-merged 17` | Actions | Issue saa labelin `agent:in-develop` |
| `bb status` | kone | Viimeisimmän ajon tila; `bb status -f` päivittyy jatkuvasti |
| `bb log` | kone | Tapahtumalokin polku |
| `bb dashboard` | kone | Selainnäkymä kaikista projekteista, oletus <http://localhost:8787> |
| `bb test` | | Linjaston omat yksikkötestit |

Mallin voi vaihtaa ajokohtaisesti ilman konfiguraation muokkausta:

```bash
bb run-issue 13 --model opencode-go/minimax-m3       # implementer
bb review-loop 7 --model reviewer-b=sonnet --model fixer=opus
```

Etusijajärjestys: komentorivin `--model` voittaa projektin `.agent/config.edn`:n, ja se voittaa linjaston oletuksen (implementerille `opencode-go/minimax-m3`).

## Päivän kulku

1. **Yöllä** systemd-timer käynnistää `run-next`in. Se ottaa jonosta issuen, toteuttaa sen (Pi),
   avaa PR:n, katselmoi sen (Claude Code: reviewer A, reviewer B, fixer) ja mergeää `developiin`.
   Sitten seuraava issue, kunnes jono on tyhjä tai `:max-issues-per-run` täynnä.
2. Jokainen `developiin` mergetty issue lisätään **release-PR:ään** (`develop → main`).
   Kierroksen alussa ja lopussa `run-next` päivittää myös auto-mergellä myöhemmin mergetyt PR:t.
3. Jumiin jäänyt issue (testit, kolme kierrosta blokkaavia, suojattu polku) saa labelin
   `agent:needs-human`, ja kierros jatkaa seuraavaan.
4. **Aamulla** dashboardin *Valmiina mainiin* -osio näyttää release-PR:n issuet ja
   *Pysähtyneet*-osio jumiin jääneet syineen. Katso `develop` ja mergeä release-PR GitHubissa:
   listan issuet sulkeutuvat.

Jos haluat katselmoinnin Actionsiin omalta koneelta, aseta `:review-in-run-next false`.

### Mitä releaseen tulee

Oletuksena kaikki puhtaaksi katselmoitu menee `developiin` ja sitä kautta seuraavaan releaseen.
Kun haluat päättää itse:

- **Yksittäinen issue:** lisää siihen label `agent:hold`. Linjasto toteuttaa ja katselmoi sen,
  mutta PR saa labelin `agent:approved` ja jää odottamaan. Mergeä se GitHubissa, kun haluat sen
  mukaan; seuraava `run-next` tai `bb sync` lisää sen release-PR:ään.
- **Kaikki:** `:merge-to-develop :manual` projektin `.agent/config.edn`:ssä.
- **Jo developissa oleva muutos pois:** `git revert` sen squash-commit ja poista rivi release-PR:stä.

### Release notes ja julkaisu

- Aina kun issue lisätään release-PR:ään, agentti (`:release-notes`-rooli) kirjoittaa
  kuvaukseen englanninkieliset release notesit ja ehdottaa versionostoa (`minor`, jos mukana on
  uutta käyttäjälle, muuten `patch`). Jos muokkaat notesia käsin, linjasto ei kirjoita niitä yli.
- Kun mergeät release-PR:n mainiin, seuraava `run-next` tai `bb sync` julkaisee sen:
  versio `package.json`iin (ja `package-lock.json`iin), uusi osio `CHANGELOG.md`:hen,
  commit `Release vX.Y.Z` mainiin, tagi, GitHub Release notesien kanssa ja lopuksi main
  yhdistetään takaisin `developiin`. Release-PR saa labelin `agent:released`.
- Asetukset: `:release {:notes true :publish true :version-file "package.json" :changelog "CHANGELOG.md" :tag-prefix "v"}`.
- Jos `main` on suojattu niin, ettei siihen voi pushata, julkaisu tehdään ilman versionostoa
  ja release-PR:ään tulee siitä maininta.

Kaikki linjaston GitHubiin kirjoittamat tekstit ovat englanniksi; agenttien kieli on `:language`.

## Seuranta

Orkestroija kirjaa jokaisen vaiheen ja agentin työkalukutsun tapahtumalokiin
`~/.local/state/linjasto/<projekti>.jsonl` (tai `$LINJASTO_STATE_DIR`) ja tulostaa
saman terminaaliin:

```
18:26:04 aloitetaan issue #13: change timeframe
18:26:04 [implementer] aloittaa (opencode-go/minimax-m3)
18:26:05 [implementer] lukee src/chart.ts
18:26:09 [implementer] muokkaa src/chart.ts
18:26:12 [implementer] ajaa npm test
```

**Dashboard:** `bb dashboard` käynnistää paikallisen selainnäkymän osoitteeseen
<http://localhost:8787> (vain 127.0.0.1, portti `--port`-lipulla). Siinä näkyvät kaikki
projektit, joista on lokia: käynnissä olevan ajon vaihe ja agentti, tapahtumat
reaaliajassa, prioriteettijono (seuraavana vuorossa oleva issue korostettuna) sekä
ajohistoria. Jono haetaan GitHubista minuutin välein. Sivu päivittyy kahden sekunnin välein.

Toisesta terminaalista: `bb status -f` näyttää käynnissä olevan issuen, vaiheen,
agentin ja viimeisimmät tapahtumat. Agentit ajetaan suoratoistotilassa
(`pi --mode json`, `claude --output-format stream-json`), joten työkalukutsut näkyvät heti.

## Tilakone

| Label | Merkitys |
|---|---|
| `agent:ready` | **Sinä** hyväksyt issuen linjastolle. Vain näitä käsitellään. |
| `agent:working` | Implementer työskentelee |
| `agent:review` | PR auki, review-silmukka käynnissä |
| `agent:merging` | Ei blokkaavia, auto-merge odottaa CI:tä |
| `agent:in-develop` | Mergetty developiin |
| `agent:needs-human` | Pysähtyi. Syy on kommentissa. Palauta `agent:ready` yrittääksesi uudelleen. |
| `agent:hold` | **Sinä** issueen: katselmoi, mutta älä mergeä developiin |
| `agent:approved` | PR katselmoitu puhtaaksi, odottaa sinun mergeäsi |
| `agent:released` | Release-PR julkaistu (tagi ja GitHub Release) |

Prioriteetti tulee labeleista `prio:1`, `prio:2`, `prio:3` (sitten vanhin ensin) tai GitHub Projectin kentästä (`:queue {:source :project …}`).

## Release-PR develop → main

Kun PR mergetään `developiin`, linjasto lisää issuen pysyvään release-PR:ään
(`develop → main`) ja luo sen, jos sitä ei vielä ole. Rivit ovat muotoa
`- Closes #13 · PR #14 · otsikko`, joten kun mergeät release-PR:n `mainiin`,
GitHub sulkee listan issuet. Seuraava developiin mergetty issue avaa uuden release-PR:n.

Release-PR päivittyy itsestään, kun `develop` liikkuu, joten sinulle jää vain
katselmointi ja merge. Asetukset: `:release {:enabled true :main-branch "main" :title "…"}`.

Jos auto-merge odottaa CI:tä, merge tapahtuu myöhemmin ja projektin workflow ajaa
`mark-merged`-komennon PR:n sulkeutuessa. Ilman workflowta voit ajaa sen käsin:
`bb mark-merged <pr>`.

## Review-silmukka

- Katselmoijat palauttavat JSON-löydöksiä: `blocking`, `suggestion` tai `nit`.
- Reviewer B näkee A:n löydökset ja voi kiistää ne. Kiistettyjä ei korjata.
- Vain blokkaavat pitävät silmukan käynnissä. Muut jäävät PR:ään sinua varten.
- Kierrosnumero on katselmoinnin rungossa (`<!-- linjasto:round=N -->`). Kun `:max-rounds` ylittyy, PR saa labelin `agent:needs-human`.
- Actionsissa yksi ajo tekee yhden kierroksen. Fixerin push käynnistää seuraavan (`synchronize`), ja `concurrency` pitää ajot peräkkäin.

## Runnerit

Runner valitaan roolikohtaisesti `.agent/config.edn`:ssä:

| Runner | Komento | Huomioita |
|---|---|---|
| `:claude-code` | `claude -p --output-format json` | Katselmoijat saavat `--json-schema`n ja vain lukutyökalut. |
| `:pi` | `pi --model <malli> <args> -p <prompt>` | Malli `:model`-avaimesta, muut liput `:args`-avaimesta. |
| `:command` | mikä tahansa | Prompti stdiniin, vastaus stdoutista. Paikallisille malleille. |

Promptit ovat `prompts/`-kansiossa. Projekti voi ylikirjoittaa minkä tahansa niistä tiedostolla `.agent/prompts/<nimi>.md`.

## Turvarajat

- Agentit eivät committaa eivätkä pushaa. Orkestroija tekee sen.
- Muutokset polkuihin `:protected-paths` (oletus `.github/workflows/`, `.agent/`) pysäyttävät työn.
- Vain `agent:ready`-labeloidut issuet käsitellään. Issuen teksti päätyy promptiin, joten älä labeloi vieraiden kirjoittamia issueita lukematta niitä.
- `run-issue` ei koske omaan checkoutiisi; kaikki työ tehdään worktreessä `<projekti>-worktrees/`-kansiossa.

## Tiedossa olevat puutteet

- Implementeria ei vielä ajeta kontissa. Seuraava askel: devcontainer, johon worktree mountataan.
- Review-ketjuja ei resolvata automaattisesti; fixer vastaa yhteenvetokommentilla.
- Budjettikattoa tokeneina ei ole; raja tulee kierrosmäärästä ja Actionsin aikarajasta.
- GitHub Project -jono on toteutettu, mutta testaamatta oikeaa projektia vasten.
