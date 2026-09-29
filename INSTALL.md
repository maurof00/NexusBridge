# CrossBridge 1.2.2 - installazione universal JAR

## Un solo file

Usa lo stesso file `CrossBridge-1.2.2.jar` su tutti i backend Spigot/Paper e sul proxy.

Non installare contemporaneamente i vecchi JAR `CrossBridge-Spigot` o `CrossBridge-Bungee`.

## Spigot / Paper

Copia il JAR in:

`plugins/CrossBridge-1.2.2.jar`

Configura `plugins/CrossBridge/config.yml` con un `server-id` differente per ogni backend e lo stesso `shared-secret` del proxy.

## BungeeCord / Waterfall-compatible

Copia lo stesso JAR in `plugins/` del proxy.

Il proxy legge le impostazioni di sicurezza da `plugins/CrossBridge/config.yml` e salva i server creati dinamicamente in `plugins/CrossBridge/servers.properties`.

## Velocity

Copia lo stesso JAR nella cartella `plugins/` del proxy Velocity.

Velocity carica `velocity-plugin.json` e avvia `dev.crossbridge.velocity.CrossBridgeVelocity`. La configurazione del bridge usa ancora `crossbridge:main`, quindi i backend Spigot/Paper non richiedono un secondo plugin solo per passare da Bungee a Velocity.

Velocity 4.x richiede Java 25 o superiore nella documentazione attuale di PaperMC.

## Comandi proxy

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

## Spigot/Paper: comandi nativi sostituiti

Con `override-bukkit-commands: true` CrossBridge prende il controllo dei comandi diretti `/gamemode`, `/gm`, `/teleport`, `/tp`, `/fly`, `/tphere`, `/heal`, `/feed`, `/speed`, `/clear`, `/kill` e `/vanish`. Non devi usare `/xeon gm`, `/xeon tp` ecc.

Se un altro plugin fornisce già un comando omonimo, CrossBridge non ne cancella la registrazione: sostituisce soltanto le implementazioni Bukkit/vanilla riconoscibili.

## Bridge cross-server

Il backend invia i pacchetti sul canale `crossbridge:main`. Il proxy verifica il `source`, autentica HMAC quando richiesto e instrada il pacchetto verso il backend indicato da `target`.

Su Velocity i messaggi ricevuti su `crossbridge:main` vengono marcati come handled dopo il controllo dell'identificatore, evitando il forwarding indiscriminato verso il backend.
