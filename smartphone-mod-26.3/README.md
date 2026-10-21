# Smartphone-mod (Fabric, Minecraft 26.3) – versie 2.7.1

Een moderne in-game smartphone met twaalf apps en een pinautomaat die diamanten omzet in
digitaal saldo. De server is altijd de enige autoriteit: de client toont gegevens en stuurt
verzoeken, maar bepaalt nooit uitkomsten.

## Installeren

1. Zet Fabric Loader 0.19.5 of nieuwer en **Fabric API** (0.162.0+26.3 of nieuwer) in `mods/`.
2. Zet `smartphone-2.7.1.jar` in `mods/`, zowel op de server als bij elke speler.
   SQLite zit al in de jar, dus verder hoeft er niets geïnstalleerd te worden.
   **Server en spelers moeten precies dezelfde versie hebben.** Heeft een speler een andere versie,
   dan weigert de server hem bij het inloggen. Hij krijgt dan een melding welke versie hij nodig heeft
   (eerder gaf dit een crash bij het openen van Berichten of de pinautomaat).
3. Een bestaande wereld van versie 2.0.0 wordt bij de eerste start automatisch bijgewerkt.
   Saldo's, afschriften en berichten blijven behouden, en de oude zichtbaarheid wordt de nieuwe privacymodus.

**Recepten**
- Smartphone: ijzerstaven rond een glaspaneel, met redstone en een koperstaaf.
- Pinautomaat: ijzerblokken, glaspanelen, een diamant en een redstoneblok.
- Opslaghub: `IGI / CEC / IRI` (I = ijzerstaaf, G = glaspaneel, C = kist, E = enderparel, R = redstone).
- Koppeltool: een stok, een koperstaaf en een amethistscherf schuin onder elkaar (zoals een zwaard).
- Telefoonhoesje: combineer de telefoon met een of meer kleurstoffen in het craftingvenster.
  De kleur zie je op het 3D-model in je hand en als rand om het scherm.

## Apps

| App | Wat het doet |
|---|---|
| **Berichten** | Privéberichten en **groepschats** aan online en offline spelers. Je kunt een groep maken, leden toevoegen, de groep verlaten en foto's delen. Elk gesprek heeft ongelezen-badges. |
| **Bank** | Saldo en afschriften met omschrijving. Er zijn drie tabbladen. **Saldo** is voor overmaken, met een live kostenindicatie. **Tikkie** is voor echte betaalverzoeken: de ander betaalt of weigert met één tik. **Vast** is voor vaste overboekingen per X Minecraft-dagen. |
| **Kaart** | Een minimap in blokkleuren met spelers als stip, waarbij vrienden een groene ster krijgen. **Waypoints** kun je opslaan op je huidige positie. Met **Navigeer** verschijnt een kompas met afstand bovenin je scherm, ook als de telefoon dicht is. |
| **Marktplaats** | Je biedt items aan uit je inventaris. De server houdt het item in bewaring (escrow). Een koper betaalt via de bank en krijgt het item direct, of bij de volgende login als hij offline was. Je kunt items ook weer terugnemen. |
| **Camera** | De telefoon gaat dwars voor je beeld als zoeker en **je kunt gewoon blijven lopen en rondkijken**, bijvoorbeeld om iets te volgen dat beweegt. Linksklik maakt de foto, met het scrollwiel zoom je in (tot 8×), en rechtsklik, Esc of P brengt je terug naar de telefoon. De foto bevat precies wat in de zoeker staat. Hij komt in **Foto's** en kun je in een chat of groep delen. |
| **112** | Tik twee keer op SOS. Je locatie gaat naar alle online spelers of alleen naar admins (instelbaar). Ontvangers horen een alarm en zien een pulserende SOS-stip op de kaart. |
| **Instellingen** | Achtergrond (5 stuks), beltoon, trillen, pop-ups, berichtvoorbeeld, 24-uursklok, privacy (Niemand / Vrienden / Iedereen) en het vriendenbeheer, allemaal op één plek. Daaronder staan de tarieven van de server. |
| **Opslag** | Lijst van je opslaghubs, plus die van spelers die jou als vriend hebben toegevoegd. Tik op een hub om hem **van overal** te openen, met hetzelfde scherm als bij de hub zelf. Esc brengt je terug naar de telefoon. Kan uit, of geld kosten per keer openen. |
| **Parkour** | Alle parcours met jouw beste tijd en het record. Tik op een parcours voor de ranglijst en om een speler uit te dagen; een uitdaging accepteer of weiger je ook hier. |
| **CCTV** | Alleen zichtbaar met de rechten `smartphone.cctv.view` (kijken) of `smartphone.cctv.manage` (ook draaien en hernoemen), of als op. Lijst van alle beveiligingscamera's; tik om live mee te kijken. |
| **Rekenmachine**, **Klok** | Een rekenmachine en een klok die echte tijd én wereldtijd toont. Daarnaast een stopwatch met rondetijden en een timer die afgaat als de telefoon dicht is. |

**Bediening**
- Open de telefoon met rechtsklik terwijl je hem vasthoudt, of met de **sneltoets P** (instelbaar
  bij Besturing). Voor de sneltoets moet de telefoon wel ergens in je inventaris zitten.
- Sluiten gaat met Esc, P, E (inventaris) of door naast de telefoon te klikken.
- Bij een naamveld (Bank, of zoeken in Berichten) zie je een zoeklijst met spelers.
  Online spelers staan bovenaan met een groene stip. Tab vult de eerste naam in.
- De rekenmachine werkt ook met het toetsenbord: cijfers, `+ - * /`, Enter, Backspace en C.
- Instellingen: **thema** Donker, Licht of Auto (licht overdag en donker 's nachts in de wereld) en
  zes **beltonen** (je hoort ze bij het kiezen).
- Foto's van de Camera worden ook op je eigen computer bewaard, in `.minecraft/screenshots/smartphone/`.
- De pinautomaat heeft animaties: het geld vliegt erin, het saldo telt op, er komt geld uit de uitgifte
  en je hoort piepjes, munten en een biljettenteller.

Meldingen verschijnen als banner in de telefoon, of als kaartje rechtsboven in je scherm
wanneer de telefoon dicht is. Ze hebben een eigen geluid of trilling. De rest van de afwerking:
- apps schuiven open en dicht;
- de telefoon schuift omhoog bij het openen;
- er is een statusbalk met echte tijd en een dynamic island;
- je gaat terug naar het startscherm met de home-indicator.

## Opslagnetwerk (nieuw in 2.4.0)

Eén blok waarmee je bij alle gekoppelde kisten tegelijk kunt, zodat je niet meer langs je sorter hoeft te lopen.
De items blijven gewoon in je kisten liggen: de hub kijkt erin en pakt eruit.

**Koppelen met de Koppeltool**
1. Rechtsklik op de **Opslaghub**: de tool onthoudt die hub (je ziet hem glimmen en de tooltip toont welke).
2. Rechtsklik op een kist, vat of shulkerdoos: gekoppeld. Nog een keer klikken ontkoppelt hem. Bij een
   dubbele kist gaan beide helften altijd samen.
3. Voor een hele gang tegelijk: **shift + rechtsklik op twee hoeken**. Alles daartussen wordt gekoppeld.
   Hoppers worden overgeslagen, zodat je sorter er niet in komt. Chunks die niet geladen zijn worden
   overgeslagen; loop daarheen en doe het nog eens.
4. Houd je de tool vast, dan zie je groene sterretjes op gekoppelde kisten in de buurt en een lichtstraal op de hub.
   Shift + rechtsklik in de lucht maakt de tool leeg.

Een afgebroken kist wordt vanzelf ontkoppeld. Standaard kan één hub 4096 kisten koppelen binnen 256 blokken.

**Het scherm**
- Alle items van alle kisten bij elkaar, met het totaal per soort. Zoeken op naam (of `@mod`), sorteren op
  aantal, naam of ID.
- Lege hand: linksklik pakt een stack, rechtsklik een halve stack, en shift-klik zet hem direct in je inventaris.
- Iets vast aan je muis: linksklik slaat alles op, rechtsklik slaat er één op. Shift-klik in je inventaris
  slaat die stack op.
- De knop ⇩ slaat je hele inventaris op, behalve je hotbar.
- Opslaan vult eerst bestaande stapels aan, dan lege vakken in kisten waar dat item al ligt, en pas daarna
  een willekeurig leeg vak. Zo blijft een gesorteerde opslag netjes.
- Het balkje onder het raster laat zien hoe vol het netwerk is.

**Wie mag erbij?**
- Bij het blok zelf kan iedereen erbij, net als bij een kist. Met `storageHubPrivate: true` mogen alleen de eigenaar,
  diens vrienden en admins erbij.
- Via de telefoon kunnen alleen de eigenaar en spelers die de eigenaar als vriend heeft toegevoegd erbij
  (Instellingen → vrienden).
- Kisten in niet-geladen chunks worden geladen zolang het scherm open is, zonder dat ze tikken. Tot ze geladen
  zijn, staat er "x laden…" bij.

## Parkour (nieuw in 2.5.0)

Volledig server-side en met gewone blokken: er komen geen nieuwe blokken of items bij.

**Parcours maken (admin)**
```
/parkour create <naam>            nieuw parcours
/parkour setstart <naam>          ga op de start staan (drukplaat of blok onder je voeten)
/parkour addcheckpoint <naam>     op elk checkpoint, in volgorde
/parkour setfinish <naam>         op de finish
/parkour hologram <naam> [remove] zwevende top 10 op jouw plek
/parkour setreset <naam> <y|here|auto>   onder deze hoogte word je teruggezet (auto = 3 onder het laagste blok)
/parkour water <naam> on|off      water zet je terug (lava altijd)
/parkour removecheckpoint <naam> [nr] · info <naam> · resettimes <naam> [speler] · delete <naam>
/parkour reward <naam>                    beloning bekijken
/parkour reward <naam> additem            voegt het item/de stapel in je hand toe (mag vaker)
/parkour reward <naam> money <bedrag>     saldo op de bank
/parkour reward <naam> clear | resetclaims [speler]
/parkour protect <naam> auto [marge]      beschermt het gebied rond alle parcoursblokken (standaard 4 blokken marge)
/parkour protect <naam> pos1 | pos2 | off zelf twee hoeken kiezen, of uitzetten
```
**Beloning:** spelers krijgen die alleen bij hun eerste keer halen (niet bij een oefenrun met effecten).
Ze zien bij de start wat ze kunnen verdienen. Items komen in de inventaris (vol = op de grond), geld op de bank.

**Bescherming:** start-, checkpoint- en finishblokken kun je nooit breken. Met `protect` kan in het hele gebied
niemand breken, blokken plaatsen of emmers legen, behalve admins in creative. Explosies (creepers, TNT)
worden niet tegengehouden: zet daarvoor eventueel `mobGriefing` uit of houd mobs weg bij het parcours.
Staat je op een drukplaat of tapijt, dan is dat het blok; anders het blok onder je voeten.

**Spelen**
- Loop op de start; de timer begint zodra je eraf stapt. De tijd staat in je actiebalk, met checkpoints en je record.
- Checkpoints moeten in volgorde. Bij elk checkpoint zie je of je voor of achter ligt op je record.
- Vallen onder de resethoogte, water of lava: terug naar je laatste checkpoint (tijd loopt door).
  **Twee keer snel sneaken** doet hetzelfde. Geen valschade tijdens een run.
- Vliegen, elytra, creative, teleporteren (/tp, enderparel, chorus fruit) of doodgaan stopt de run.
  Sinds 2.7.1 kijkt de mod naar echte teleports in plaats van naar hoe ver je in één tick bewoog; snel vallen
  bij lag stopte de run daardoor soms ten onrechte.
- Met speed, jump boost, levitation of slow falling telt de run niet voor de ranglijst.
- Nieuw serverrecord: iedereen krijgt een bericht.

**Racen**
- `/parkour race <naam> <speler>` of via de Parkour-app: de ander krijgt een melding met [Accepteren]/[Weigeren].
- `/parkour race <naam>` zonder speler: iedereen die binnen 8 blokken van de start staat doet mee.
- Iedereen moet binnen 32 blokken van de start staan (`parkourRaceRadius`). Jullie worden op de start gezet,
  staan stil tijdens 3-2-1 en bij GO gaan jullie. In de actiebalk zie je hoe ver de koploper is. Wie het eerst
  finisht wint; gewonnen races staan in de ranglijst.

Overige commando's voor iedereen: `/parkour list`, `/parkour top <naam>`, `/parkour reset`, `/parkour leave` (of `/pk`).

## Beveiligingscamera's / CCTV (nieuw in 2.6.0)

**Camera:** craft met `III / IOG / .R.` (I = ijzerstaaf, O = observer, G = glaspaneel, R = redstone). Hang hem op
een muur, aan het plafond of zet hem op de grond. Met de manage-recht: rechtsklik draait hem 22,5°, shift +
rechtsklik kantelt hem. Een naam geef je met een aambeeld of in de app. Elke camera houdt zijn eigen chunk
geladen (zoals /forceload), `cctvKeepLoaded` in de config.

**Live kijken:** in de CCTV-app op een camera tikken. Je beeld springt naar de camera; je lichaam blijft staan.
Slepen met de muis draait de camera (alleen met manage), scrollen zoomt (tot 6×), ← / → wisselt van camera,
Esc gaat terug naar de telefoon. Krijg je schade, dan word je direct teruggezet. Het rode lampje op de camera
knippert zolang iemand meekijkt.

**Hoe het werkt:** de server slaat geen beelden op. Tijdens het kijken behandelt de server de camera als
jouw "ogen" voor het versturen van chunks en entities (mixins op `ChunkMap`), en jouw eigen Minecraft tekent het
echte beeld. Camera's in een andere dimensie dan waar je staat kun je niet bekijken.

### Terugspoelen en clips (nieuw in 2.7.0)

- **⏪ 30s terug (R):** tijdens live kijken spoel je terug naar wat de camera de laatste 30 seconden zag.
  Je krijgt een tijdlijn (klikken of slepen), pauze/afspelen (spatie), ½× / 1× / 2× en **● LIVE (L)** om terug te gaan.
  Esc in een opname gaat eerst terug naar live.
- **✂ Clip (C):** bewaart de laatste 30 seconden (of de opname die je bekijkt) op **je eigen computer** in
  `.minecraft/cctv/clips/`. In de app staan ze onder het tabblad **Clips**: ▶ speelt af (vanuit de camera),
  🗑 verwijdert. Een clip is klein (een paar kB), want het is geen video maar een opname van posities.
- **MP4:** zet de opname om naar een gewone video (20 fps, in je venstergrootte, bij grote schermen verkleind tot ongeveer 960 pixels breed) in `.minecraft/cctv/videos/`, om te delen
  buiten Minecraft. Dit duurt even lang als de opname zelf; het beeld wordt in die tijd vastgelegd.
- **Hoe het werkt:** de server onthoudt alleen in het geheugen, voor de laatste 30 seconden, waar spelers, mobs,
  items en voertuigen binnen `cctvRecordRadius` blokken stonden (10× per seconde), plus welke blokken veranderden.
  Bij terugspoelen krijgt jouw client die gegevens en speelt ze na met nep-entities in het echte camerabeeld.
  Op de schijf van de server komt niets. Na een herstart of het weghalen van de camera is het buffer leeg.

**Rechten:** `smartphone.cctv.view` en `smartphone.cctv.manage` via LuckPerms; zonder permissieplugin alleen ops.

## Admincommando's (permissieniveau 2)

```
/phone balance <speler>
/phone give|take|set <speler> <bedrag>
/phone log [speler] [aantal]       laatste overboekingen
/phone suspicious                  grote bedragen (24 uur) en spelers met veel overboekingen per uur
/phone export                      alle transacties naar <wereld>/smartphone/transactions-<datum>.csv
/phone backup                      maak nu een back-up van de database
```
Spelernamen worden aangevuld met Tab.

## Back-ups

- **Automatisch:** de server maakt elke 24 uur (en bij het starten, als de laatste back-up ouder is)
  een kopie van de database in `<wereld>/smartphone/backups/`. De laatste 7 blijven bewaard.
  De server hoeft daarvoor niet uit.
- **Bij een update:** voordat de database wordt bijgewerkt naar een nieuwere versie, maakt de mod
  eerst een extra kopie (`before-upgrade-…`).
- **Terugzetten:** stop de server, vervang `smartphone/smartphone.db` door de back-up (en verwijder
  `smartphone.db-wal` en `smartphone.db-shm`), en start de server weer.
Elke admincorrectie komt ook in het logboek, als type `ADMIN` met de naam van de admin.

## Configuratie (`config/smartphone.json`, op de server)

| Sleutel | Standaard | Betekenis |
|---|---|---|
| `currency` | diamond = 1, diamond_block = 9 | Items die de pinautomaat accepteert, met hun waarde |
| `startingBalance` | 0 | Startsaldo voor nieuwe accounts |
| `maxTransfer` | 1 000 000 | Grootste enkele overboeking |
| `transferFeePercent` / `transferFeeMinimum` | 0 / 0 | Transactiekosten (gaan uit de economie) |
| `dailyInterestPercent` / `interestMaxBalance` | 0 / 1 000 | Rente per Minecraft-dag, over maximaal dit saldo |
| `marketFeePercent` | 0 | Percentage dat de Marktplaats inhoudt bij verkoop |
| `maxListingsPerPlayer` / `maxListingPrice` | 10 / 1 000 000 | Limieten voor de Marktplaats |
| `maxGroupMembers` / `maxWaypoints` / `maxPhotosPerPlayer` | 20 / 30 / 40 | Limieten per speler |
| `emergencyTarget` | `all` | `all` of `admins`: wie een 112-melding krijgt |
| `emergencyCooldownSeconds` | 60 | Wachttijd tussen noodmeldingen |
| `suspiciousAmount` / `suspiciousTransfersPerHour` | 1 000 / 20 | Drempels voor `/phone suspicious` |
| `backupIntervalHours` / `backupsToKeep` | 24 / 7 | Automatische back-ups (0 = uit) |
| `maxMessageLength`, `messageCooldownMs`, `transferCooldownMs` | 256, 750, 1000 | Spambeveiliging |
| `gpsRadius` / `gpsIntervalTicks` / `gpsVisibleByDefault` | 256 / 10 / true | GPS-instellingen |
| `storageMaxLinks` / `storageMaxRange` / `storageMaxAreaSize` | 4096 / 256 / 128 | Opslagnetwerk: kisten per hub, afstand tot de hub, grootste gebied voor shift-koppelen |
| `storageRemoteAccess` / `storageRemoteFee` | true / 0 | Opslag openen via de telefoon, en wat dat per keer kost |
| `storageHubPrivate` | false | Ook bij het blok alleen eigenaar, vrienden en admins |
| `parkourRaceRadius` / `parkourMarkerParticles` | 32 / true | Afstand tot de start om mee te racen; deeltjes op start, checkpoints en finish |
| `cctvKeepLoaded` / `cctvViewDistance` | true / 4 | Camera's houden hun chunk geladen; zichtafstand (chunks) tijdens meekijken |
| `cctvRecordRadius` | 24 | Straal (blokken, 4–64) rond een camera die 30 seconden in het geheugen wordt bijgehouden voor terugspoelen |

De persoonlijke instellingen (achtergrond, geluid enzovoort) staan per speler in `config/smartphone-client.json`.

## Hoe het veilig blijft

- Elk pakket van client naar server wordt opnieuw gecontroleerd: zit de telefoon in de hand,
  geldt er een cooldown, zijn de invoerwaarden geldig.
- Geld verplaatst altijd binnen één SQL-transactie met `UPDATE … WHERE balance >= ?`.
  Een saldo kan daardoor nooit negatief worden of twee keer uitgegeven worden.
- Marktplaats: het item wordt eerst uit de inventaris gehaald en daarna pas opgeslagen. Een koop is atomair
  (de status gaat van `ACTIVE` naar `SOLD` in dezelfde transactie als de betaling). Items voor
  offline spelers worden precies één keer uitgeleverd (`DELETE … RETURNING`).
- Foto's: de server accepteert alleen echte JPEG's van maximaal 320×320 en 60 kB. Je kunt een foto
  alleen bekijken als je de eigenaar bent of in het gesprek zit waarin hij gedeeld is.
- Vaste overboekingen en rente worden verwerkt bij de wisseling van Minecraft-dag. Daarbij worden
  maximaal 7 gemiste dagen ingehaald.

## Bouwen

```
./gradlew build          # jar in build/libs/
./gradlew runClient      # dev-client
./gradlew runServer      # dev-server
```
Vereist JDK 25.

## Projectindeling

- `server/`: `PhoneDatabase` (SQLite, schema-versie 5 met automatische migratie), plus
  `BankService`, `EconomyService` (vaste opdrachten en rente), `MessageService`, `GpsService`,
  `MarketService`, `PhotoService`, `EmergencyService` en `AdminCommands`.
- `network/`: `C2S`- en `S2C`-payloads. Foto's gaan via `registerLarge`.
- `client/phone/`: `PhoneScreen` (frame, animaties, app-stack) en `PhoneApp`. Elke app staat in `apps/`.
- `client/ui/`: `UiKit` (widgets in iOS-stijl) en `PhoneField`.
- `client/hud/Notifications`: pop-ups, geluiden, het badge op je HUD en het navigatiekompas.
- `storage/`: `StorageHubBlock`/`StorageHubBlockEntity` (onthoudt gekoppelde posities, eigenaar en naam),
  `LinkerItem` (de Koppeltool), `StorageNetwork` (lezen, uitnemen en opslaan, op de serverthread),
  `StorageMenu` (inventaris-slots plus netwerk via eigen pakketten) en `StorageService` (koppelen, toegang,
  openen op afstand, hub-register in de database, deeltjes). Het scherm is `client/screen/StorageScreen`,
  de telefoon-app `client/phone/apps/StorageApp`.
- `parkour/`: `Course` (start, checkpoints, finish als JSON in de database), `ParkourService` (runs, races,
  ranglijst, hologram) en `ParkourCommands`. Telefoon: `ParkourApp` en `ParkourCourseApp`.
- `cctv/`: `CctvCameraBlock`/`CctvCameraBlockEntity`, `CctvService` (meekijken, chunk-tickets, lijst/hernoemen),
  `CctvRecorder` (30-secondenbuffer in het geheugen, export als gecomprimeerde blob) en `CctvPerms`.
  Client: `client/cctv/CctvView`, `CctvScreen`, `CctvReplay` (afspelen met nep-entities), `CctvClips`
  (.cctv-bestanden) en `CctvMp4` (JCodec, zit in de jar). Protocolversie 7.
