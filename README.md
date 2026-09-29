# CrossBridge 1.2.2

CrossBridge 1.2.2 è distribuito come **un unico JAR universale** per:

- Spigot/Paper backend
- BungeeCord e proxy compatibili
- Velocity

## Perché funziona con un solo JAR

Il file contiene tre entrypoint separati nello stesso artefatto:

- `plugin.yml` -> `dev.crossbridge.spigot.CrossBridgeSpigot`
- `bungee.yml` -> `dev.crossbridge.bungee.CrossBridgeBungee`
- `velocity-plugin.json` -> `dev.crossbridge.velocity.CrossBridgeVelocity`

Il loader della piattaforma sceglie il descriptor che conosce. Non viene usata una singola classe Java che tenti di ereditare tre API differenti, cosa che tecnicamente non è possibile.

È incluso anche `PlatformDetector` per individuare a runtime l'ambiente API presente.

## Installazione

Usa `CrossBridge-1.2.2.jar` sia sui backend sia sul proxy. Non mischiare i vecchi JAR platform-specifici.

Per Velocity 4.x serve Java 25+ secondo la documentazione attuale di PaperMC.

## Collegamento

Tutti i backend usano il canale `crossbridge:main` e lo stesso protocollo `BridgePacket`. Bungee e Velocity implementano il router proxy-side, quindi un backend può inviare verso:

- un altro server, con `target=<server>`
- tutti gli altri server, con `target=*`
- il proxy, con `target=proxy`

La protezione HMAC usa lo stesso `shared-secret` tra proxy e backend. Il proxy valida anche il campo `source` rispetto al backend che ha materialmente inviato il plugin message.

Velocity usa il modello nativo `PluginMessageEvent` + `ChannelRegistrar` e invio verso `RegisteredServer`, quindi non viene introdotto un trasporto parallelo solo per Velocity.

## Spigot / Paper

Comandi Bukkit sostituiti direttamente da CrossBridge:

`/gamemode <survival|creative|adventure|spectator> [player]`

`/gm <mode> [player]`

`/fly [player]`

`/tp <player>`

`/tp <player> <target>`

`/tp <x> <y> <z> [yaw pitch]`

`/tphere <player>`

`/heal [player]`

`/feed [player]`

`/speed <walk|fly> <1-10> [player]`

`/clear [player]`

`/kill <player>`

`/vanish [player]`

La registrazione usa il CommandMap Bukkit, quindi i comandi vengono esposti con i nomi normali invece di `/xeon <comando>`.

## Bungee / Velocity

Comandi principali:

`/xeonProxy servers`

`/xeonProxy server list`

`/xeonProxy server create <nome> <host:porta> [restricted]`

`/xeonProxy server delete <nome>`

`/xeonProxy server info <nome>`

`/xeonProxy server ping <nome>`

`/xeonProxy server connect <player> <server>`

`/xeonProxy player <send|message|kick|info> ...`

`/xeonProxy broadcast <messaggio>`

`/xeonProxy protect`

`/xeonProxy status`

I server Bungee creati da XeonProxy sono persistiti in `servers.properties`; la variante Velocity usa lo stesso schema di persistenza nel proprio data directory.

## Build

Il parent contiene i moduli `common`, `spigot`, `bungee`, `velocity` e `universal`.

Il modulo `universal` raccoglie gli adapter platform-specifici e fornisce i tre descriptor in un singolo JAR.

Per il modulo Velocity usa JDK 25+ e `velocity-api 4.2.1-SNAPSHOT` come documentato da PaperMC.
