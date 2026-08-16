# Bolt (Sponge 7.4) - Permissions

Sponge has **no `plugin.yml`-style permission defaults**. Where the Bukkit build ships a
`plugin.yml` that grants the everyday nodes to *everyone* (`default: true`) and the admin nodes
to *ops* (`default: op`), Sponge's permission model is all-or-nothing out of the box:

- **Ops** (`/op`, or `level >= 2` in `ops.json`) implicitly have **every** permission - Bolt works
  fully for them with no setup.
- **Non-ops** have **no** permissions at all unless a **permissions plugin grants them**. Without
  one, a normal survival player can't even run `/lock`.

So to reproduce Bukkit's behaviour for regular players you must install a permissions manager
(**[LuckPerms for Sponge](https://luckperms.net/download)** is the standard choice) and grant the
"player" nodes below to the `default` group. This file lists every node Bolt checks, its Bukkit
default, and what to do on Sponge.

> Node names are **identical** to the Bukkit build - the same `bolt.*` strings are checked in
> `BoltCommands`, `AdminCommands`, `InteractionHandler`, and `BoltPlugin`. Only the *defaulting*
> differs between platforms.

---

## Quick start (LuckPerms)

Grant every normal player the everyday commands, and let ops keep admin:

```
# give all players the "default: true" Bukkit nodes
/lp group default permission set bolt.command true
/lp group default permission set bolt.command.lock true
/lp group default permission set bolt.command.unlock true
/lp group default permission set bolt.command.info true
/lp group default permission set bolt.command.edit true
/lp group default permission set bolt.command.modify true
/lp group default permission set bolt.command.trust true
/lp group default permission set bolt.command.group true
/lp group default permission set bolt.command.mode true
/lp group default permission set bolt.command.password true
/lp group default permission set bolt.command.transfer true
/lp group default permission set bolt.command.help true

# admins/mods (or just rely on /op)
/lp group admin permission set bolt.admin true
/lp group admin permission set bolt.command.admin true
```

`bolt.admin` alone is enough to *use* every admin subcommand and to bypass all protections; the
per-subcommand `bolt.command.admin.<sub>` nodes only matter if you want to hand out admin
piecemeal (e.g. give a moderator `find`/`nearby` but not `purge`).

---

## Command permissions

| Node | Bukkit default | Grants |
|---|---|---|
| `bolt.command` | everyone | Run `/bolt` at all (the base command + `/bolt help`). |
| `bolt.command.lock` | everyone | `/lock` and `/bolt lock`. |
| `bolt.command.unlock` | everyone | `/unlock` and `/bolt unlock`. |
| `bolt.command.info` | everyone | `/bolt info`. |
| `bolt.command.info.full` | op | See full info (exact owner UUID + timestamps) on protections you **don't** own. Owners always see full info of their own. |
| `bolt.command.edit` | everyone | `/bolt edit` (click-based single-protection access edit). |
| `bolt.command.modify` | everyone | `/bolt modify` (click-based, typed source/access edit incl. passwords). |
| `bolt.command.trust` | everyone | `/bolt trust` (global access list). |
| `bolt.command.group` | everyone | `/bolt group` (create/list/add/remove/delete groups). |
| `bolt.command.mode` | everyone | `/bolt mode` (nolock / nospam / persist …). |
| `bolt.command.password` | everyone | `/bolt password` (unlock password-protected containers). |
| `bolt.command.transfer` | everyone | `/bolt transfer <player>` (click-based; transfer a protection you own to another player). |
| `bolt.command.help` | everyone | `/bolt help [subcommand]`. |
| `bolt.command.admin` | op | `/bolt admin` and its subcommands (see below). |

> The Bukkit `plugin.yml` also lists `bolt.command.callback` (`default: true`). The Sponge port
> has **no** clickable-chat callback system, so that node is **unused here** - you don't need to
> grant it.

### Admin subcommands

`bolt.admin` (or op) covers all of these. Grant individually only for partial admin access.

| Node | Subcommand |
|---|---|
| `bolt.command.admin.cleanup` | `/bolt admin cleanup` - drop protections whose world/block no longer exists. |
| `bolt.command.admin.debug` | `/bolt admin debug` - click a protection to dump its raw data. |
| `bolt.command.admin.expire` | `/bolt admin expire <n> <unit>` - remove protections unused for a period. |
| `bolt.command.admin.find` | `/bolt admin find <player>` - list a player's protections. |
| `bolt.command.admin.flush` | `/bolt admin flush` - report/force pending saves. |
| `bolt.command.admin.nearby` | `/bolt admin nearby <radius>` - list nearby protections. |
| `bolt.command.admin.purge` | `/bolt admin purge <player>` - delete all of a player's protections. |
| `bolt.command.admin.reload` | `/bolt admin reload` - reload `bolt.conf` + lang. |
| `bolt.command.admin.report` | `/bolt admin report` - protection statistics. |
| `bolt.command.admin.storage` | `/bolt admin storage export\|import`. |
| `bolt.command.admin.transfer` | `/bolt admin transfer <from> [to]` - reassign ownership. |
| `bolt.command.admin.trust` | `/bolt admin trust <player> …` - edit another player's access list. |

> The Bukkit list also has `bolt.command.admin.convert` (LWC/Lockette migration). The Sponge port
> **omits migration entirely** (there was no LWC on Sponge 7.4), so this node is unused.

---

## Bypass / staff roles

| Node | Bukkit default | Grants |
|---|---|---|
| `bolt.admin` | op | Full bypass - access, open, break, and modify **any** protection - plus the ability to run `/bolt admin …`, and `force` on `/lock`. This is the master staff node. |
| `bolt.mod` | op | Moderator bypass - access/open protections without owning them (a lighter bypass than `bolt.admin`). |

Internally these are checked as permission *sources* (`bolt.admin` / `bolt.mod`) inside the
`canAccess` engine, so granting them lets a player through the same paths an owner would take.

---

## Protection notifications

Staff-facing notification: a holder is told who owns a protection when they interact with it
(block or entity), in place of the usual "locked" denial. Suppressed by `/bolt mode nospam`.

| Node | Bukkit default | Grants |
|---|---|---|
| `bolt.protection.notify` | op | On interacting with any protection, see who owns it instead of being silently denied. |
| `bolt.protection.notify.self` | nobody | Also be notified about your **own** protections (normally suppressed for the owner). |

> Owner-name resolution is cache-only on Sponge (online players + `UserStorageService`); an owner
> who has never joined this server shows the generic "…is locked" message rather than their name.
> (Unrelated to the redstone `NotifyNeighborBlockEvent` handler, which needs no permission.)

---

## Restricted type nodes

These gate *which* protection/access/source **types** a player may apply. A type is only gated if
it is marked `restricted` in `bolt.conf`; unrestricted types need no permission. This is how you
stop normal players from creating, say, `admin`-access protections.

| Node pattern | Example | Grants |
|---|---|---|
| `bolt.type.protection.<type>` | `bolt.type.protection.private` | Create a protection of that type via `/lock <type>`. |
| `bolt.type.access.<type>` | `bolt.type.access.normal` | Use that access type in `/bolt modify`/`trust`. |
| `bolt.type.source.<type>` | `bolt.type.source.password` | Use that source type (`player`/`group`/`password`/`permission`). |

Bukkit ships two of these as `default: false` (nobody-by-default) - mirror them if you use those
features:

| Node | Meaning |
|---|---|
| `bolt.type.source.door` | Allow the `door` source type. |
| `bolt.type.access.autoclose` | Allow the `autoclose` access type. |

---

## Per-block lock permission

When a protectable is configured to `require-permission`, locking that specific block/entity is
gated by a per-type node built from its (short) id:

| Node pattern | Example |
|---|---|
| `bolt.protection.lock.<id>` | `bolt.protection.lock.chest`, `bolt.protection.lock.item_frame` |

Only relevant for protectables you've flagged as permission-required in `bolt.conf`; ordinary
protectables don't check it.

---

## Access-granting permission sources

Owners can grant access to a **permission** (rather than a player/group) via
`/bolt modify add permission <node>`. Bolt then checks whether the accessing player has:

| Node pattern | Meaning |
|---|---|
| `bolt.permission.<node>` | A player carrying this passes the corresponding permission-source access entry. |

These are entirely owner-defined - you grant `bolt.permission.<node>` to whichever players/groups
should satisfy that access entry. (In the Bukkit `plugin.yml` the interaction primitives -
`bolt.permission.interact`, `.open`, `.deposit`, `.withdraw`, `.modify`, `.mount`, `.edit`,
`.destroy`, `.redstone`, `.entity_interact`, `.entity_break_door`, `.auto_close` - are declared
`default: false` so they exist to be granted; they carry the same meaning here.)

---

## Notes for migrating from a Bukkit setup

- **Copy your node grants verbatim.** Every `bolt.*` string is the same; only re-express your
  `plugin.yml` / permissions-plugin config against the Sponge permissions plugin.
- **You cannot rely on implicit defaults.** On Bukkit, `default: true` nodes work without any
  permissions plugin. On Sponge, non-ops get *nothing* implicitly - you must grant the everyday
  nodes (the Quick Start block above) or install a permissions plugin that does.
- **Ops need no setup.** If you only ever use ops for staff and don't mind every non-op being
  unable to lock anything, Bolt still runs - but that's rarely what you want on a survival server.
- **Unused-on-Sponge nodes:** `bolt.command.callback` and `bolt.command.admin.convert` -
  present in the Bukkit `plugin.yml` but not checked by this port (no chat-callback system,
  no LWC migration).
