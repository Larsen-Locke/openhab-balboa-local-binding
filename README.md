# Balboa Binding

This binding integrates Balboa hot tub / spa control units that are connected to the local network
via a Balboa Wi-Fi module (BWA / WiFi Module 50350 and compatible). It connects directly to the unit
over TCP/IP and exposes its status (temperature, pumps, lights, blower, mister, filter cycle, heat
mode, ...) as openHAB channels.

## Supported Things

There is exactly one thing type:

| Thing        | Thing Type ID | Description                             |
|--------------|----------------|------------------------------------------|
| Balboa IP Unit | `balboa-ip`  | A Balboa control unit reachable over IP |

The set of channels a thing actually gets depends on the equipment configuration reported by the
control unit itself (which pumps/lights/blower/mister are installed, one- or two-speed) - the binding
reads this out automatically after connecting and builds the channel list accordingly, so different
tubs will show a different subset of the channels listed below.

## Discovery

The binding can discover Balboa Wi-Fi modules on the local network: it broadcasts a UDP discovery
request on port 30303, which every reachable module answers directly. Discovery only finds modules on
the same local network segment as the openHAB server. It runs only when a scan is started from the UI,
never in the background. Things can also still be added manually with the host/IP address of the Balboa
Wi-Fi module.

## Thing Configuration

| Parameter          | Type    | Required | Default | Description                                                        |
|---------------------|---------|----------|---------|----------------------------------------------------------------------|
| `host`              | text    | yes      | -       | Hostname or IP address of the Balboa Wi-Fi module                   |
| `port`              | integer | yes      | 4257    | TCP port of the Balboa Wi-Fi module                                  |
| `reconnectInterval` | integer | yes      | 30      | Seconds before the first reconnect attempt after a disconnect; further attempts back off exponentially, capped at 10 minutes |
| `pollingInterval`   | integer | yes      | 60      | Seconds between polling requests sent to keep the connection alive   |
| `syncClock`         | boolean | no       | false   | Set the unit's clock to the openHAB time (regional settings) whenever it is off by more than one minute, at most every five minutes. Keeps filter cycles on time across daylight saving changes |

If the unit stops responding without properly closing the connection (for example if its Wi-Fi module loses
power), the binding notices that no data has been received for a while, closes the connection itself and goes
through the same reconnect logic as for a normal disconnect.

### `balboa.things` Example

```java
Thing balboa:balboa-ip:mySpa "My Spa" [ host="192.168.1.50", port=4257, reconnectInterval=30, pollingInterval=60 ]
```

## Channels

Channels marked _(dynamic)_ are only present if the corresponding equipment is configured on the unit.

| Channel               | Type               | Read/Write | Description                                              |
|------------------------|---------------------|:----------:|------------------------------------------------------------|
| `current-temperature`  | `Number:Temperature` |     R      | Current water temperature                                 |
| `target-temperature`   | `Number:Temperature` |    R/W     | Target water temperature                                  |
| `temperature-scale`    | `String`             |    R/W     | Display scale: `C` or `F`                                  |
| `temperature-range`    | `String`             |    R/W     | Temperature range: `LOW` or `HIGH`                          |
| `heat-mode`            | `String`             |    R/W     | Heat mode: `READY`, `REST`, `READY_IN_REST`                 |
| `filter`               | `String`             |     R      | Filter cycle status: `OFF`, `1`, `2`, `1+2`                  |
| `priming`              | `Contact`             |     R      | Priming indicator                                          |
| `circulation`          | `Contact`             |     R      | Circulation pump status                                    |
| `heater`               | `Contact`             |     R      | Heater status                                              |
| `pump-<n>` _(dynamic)_ | `Switch` or `String` |    R/W     | Jet pump `n`: `Switch` if one-speed, `String` (`OFF`/`LOW`/`HIGH`) if two-speed |
| `light-<n>` _(dynamic)_| `Switch` or `String` |    R/W     | Light `n`: `Switch` if one-level, `String` (`OFF`/`LOW`/`HIGH`) if two-level |
| `aux-<n>` _(dynamic)_  | `Switch`             |    R/W     | Auxiliary output `n`                                        |
| `blower` _(dynamic)_   | `Switch` or `String` |    R/W     | Blower: `Switch` if one-speed, `String` (`OFF`/`LOW`/`HIGH`) if two-speed |
| `mister1`/`mister2` _(dynamic)_ | `Switch` or `String` | R/W | Mister: `Switch` if one-speed, `String` (`OFF`/`LOW`/`HIGH`) if two-speed |
| `target-temperature-high` | `Number:Temperature` | R/W | Desired target temperature of the `HIGH` temperature range, see below |
| `target-temperature-low` | `Number:Temperature` | R/W | Desired target temperature of the `LOW` temperature range, see below |
| `temperature-range-switch` | `Switch` | R/W | `temperature-range` as a plain switch: `ON` = `HIGH`, `OFF` = `LOW` |
| `heat-mode-switch` | `Switch` | R/W | `heat-mode` as a plain switch: `ON` = `READY`, `OFF` = `REST` or `READY_IN_REST` |
| `last-seen` | `DateTime` | R | When the last message was received from the unit, updated at most every 30 seconds; useful to detect a stale connection (for example with the Expire profile) |
| `fault-code` | `String` | R | Most recent entry of the unit's fault log, or `NONE` if the log is empty |
| `fault-time` | `String` | R | When that fault occurred, for example `2 days ago at 14:32` (best effort: the unit only reports the time of day and how many days ago) |
| `filter-1-start`, `filter-1-duration`, `filter-2-start`, `filter-2-duration` | `String` | R/W | Start time and duration of the two filter cycles, as `HH:MM`; send `HH:MM` to change them (durations 00:15 to 24:00) |
| `filter-2-enabled` | `Contact` | R | Whether filter cycle 2 is enabled (filter cycle 1 cannot be disabled) |
| `circulation-switch` _(dynamic)_ | `Switch` | R/W | Circulation pump, switchable (only if the unit has one) |
| `heat-state` | `String` | R | Heater: `OFF`, `HEATING`, `HEAT_WAITING` |
| `spa-state` | `String` | R | Unit state: `RUNNING`, `INITIALIZING`, `HOLD`, `AB_TEMPS_ON`, `TEST` |
| `notification` | `Contact` | R | `OPEN` while a reminder or notification is pending |
| `clear-notification` | `Switch` | W | Send `ON` to clear a pending reminder/notification (push button, falls back to `OFF`) |
| `panel-locked` | `Contact` | R | `OPEN` if the control panel is locked |
| `hold-mode` | `Switch` | R/W | Hold (standby) mode for maintenance |
| `spa-time` | `String` | R/W | The unit's clock as `HH:MM`; send `HH:MM` or a DateTime to set it |
| `clock-24h` | `Switch` | R/W | Clock display format: `ON` = 24h, `OFF` = 12h |
| `clock-offset` | `Number:Time` | R | Minutes the unit's clock is ahead of (+) or behind (−) openHAB |
| `filter-2-enabled-switch` | `Switch` | R/W | Enable/disable filter cycle 2 |
| `fault-count` | `Number` | R | Number of entries in the fault log |
| `low-range-min`, `low-range-max`, `high-range-min`, `high-range-max` _(advanced)_ | `Number:Temperature` | R | Target temperature limits the unit reports per range; target temperatures are clamped to them |
| `model`, `software-version` _(advanced)_ | `String` | R | Model and software version of the control unit |
| `mac-address` _(advanced)_ | `String` | R | MAC address of the Wi-Fi module |

The `priming` channel now only reports the actual priming mode. Earlier versions also showed it as open while a
reminder was pending (the unit reports both in the same byte).

### Target temperature per range

The unit's "set temperature" message has no range selector: it always applies to whichever temperature range is
currently active, also on the unit's own control panel. `target-temperature-high` and `target-temperature-low` let you
set a value for each range independently. A value for the range that is not active right now cannot be sent
immediately, so the binding keeps it and sends it as soon as the unit switches into that range.

## Full Example

`balboa.things`:

```java
Thing balboa:balboa-ip:mySpa "My Spa" [ host="192.168.1.50", port=4257 ]
```

`balboa.items`:

```java
Number:Temperature  Spa_CurrentTemp   "Current Temperature [%.1f %unit%]" { channel="balboa:balboa-ip:mySpa:current-temperature" }
Number:Temperature  Spa_TargetTemp    "Target Temperature [%.1f %unit%]"  { channel="balboa:balboa-ip:mySpa:target-temperature" }
Switch               Spa_Pump1         "Jet Pump 1"                        { channel="balboa:balboa-ip:mySpa:pump-1" }
Switch               Spa_Light1        "Lights"                            { channel="balboa:balboa-ip:mySpa:light-1" }
```

## Building

```shell
mvn clean verify
```

This is a standalone Maven project that uses `bnd-maven-plugin` to produce an OSGi bundle.
openHAB core artifacts are resolved from the openHAB JFrog repository configured in `pom.xml`; adjust `openhab.core.version` there to the openHAB version you run.

`mvn verify` also runs the checks openHAB applies to its official add-ons: Spotless for the code style (`mvn spotless:apply` fixes it) and the static code analysis (Checkstyle, PMD, SpotBugs), whose report ends up in `target/code-analysis/report.html`.
`sat/` holds the rule parameters and general suppressions of openhab-addons.

Drop the resulting `target/io.github.larsenlocke.binding.balboa-*.jar` into your openHAB `addons/` folder and remove any previous `io.github.larsenlocke.binding.balboa-*.jar` from it first.

## Origin and License

This binding is based on the Balboa binding written by [Carl Önnheim](https://github.com/carlonnheim/openhab-addons/tree/balboa) in 2020.
[ddempsey3](https://github.com/ddempsey3/openhab-addons/tree/balboa-3) updated it for openHAB 3.0 in December 2020, and this binding continues from that version: it was ported to openHAB 5.2 and extended by Larsen-Locke.
[Thomas Binder (binderth)](https://github.com/binderth) contributed the fix for the lock-order deadlocks between the protocol, its writer and the reconnect job, and the clock, spa state, notification, hold, circulation and filter cycle channels.

Balboa does not publish the protocol of its Wi-Fi module. The message layouts of the fault log and the filter cycles are taken from the community protocol documentation of [ccutrer/balboa_worldwide_app](https://github.com/ccutrer/balboa_worldwide_app) (`doc/protocol.md`). The decoding of the system information, the setup parameters, the Wi-Fi module identification and the further status fields follows [pybalboa](https://github.com/garbled1/pybalboa).

It is licensed under the [Eclipse Public License 2.0](LICENSE), like the original binding. The source files keep the copyright line of the openHAB contributors next to the one of Larsen-Locke and contributors, and the `NOTICE` file shipped in the jar states the origin.

This is an independent project. It is not affiliated with, authorized or endorsed by Balboa Water Group; "Balboa" is a name of its respective owner.

Most of the code changed since the port, the tests and the documentation were written with the help of an AI coding assistant (Claude by Anthropic). The maintainer reviews the changes and tests every release on real hardware before publishing it.
