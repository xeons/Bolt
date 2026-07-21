# Bolt (Sponge 7.4) — Runtime Test Checklist

The Sponge port is **compile-verified only**; nothing below has been run on a live server. This
list validates it against a real **SpongeVanilla 7.4** server (Minecraft 1.12.2, Java 8).

## Setup

- [ ] SpongeVanilla 7.4 server (MC 1.12.2) running on **Java 8**; `Bolt-1.2.0.jar` in `mods/`.
- [ ] Two accounts: **OWNER** and **OTHER**. Test OTHER as a **non-op, survival** player
      (op/creative bypass protection via `bolt.admin`, so a non-op is the real test).
- [ ] Do interaction tests **away from world spawn** — vanilla spawn protection can mask/deny
      interactions and make it look like a Bolt bug.
- [ ] Default storage is SQLite. To also test the in-memory path, set `database.type = none`.

### 0. Load & config
- [ ] Server starts with no crash; Bolt is listed; **check the log for a hard failure at plugin
      scan** (the multi-release strip fixed this — regression check).
- [ ] `config/bolt/bolt.conf` is generated with `settings` / `database` / `protections` / `access`
      / `sources` / `blocks` / `entities` sections.
- [ ] `config/bolt/lang/` is populated (en.properties etc.).
- [ ] **Startup log has no `Unknown block/entity in config, skipping: …` warnings** — if it does,
      those 1.12.2 catalog ids need correcting.
- [ ] `/bolt` lists subcommands; `/bolt help` and `/bolt help lock` print help text.
- [ ] **Messages render** (not blank). If everything is silent, the `BoltComponents` Text bridge
      is broken (was previously a false alarm caused by spawn protection — retest away from spawn).

---

## Core protection

### 1. Single chest lock/unlock
- [ ] OWNER `/lock` → click a chest → "Locked private chest".
- [ ] OTHER right-clicks it → denied, chest does not open, "…locked with a magical spell".
- [ ] OWNER `/info` → click → shows type, owner name, created/last-accessed times.
- [ ] OWNER `/unlock` → click → "Unlocked …"; OTHER can now open it.

### 2. Auto-protect on place
- [ ] OWNER places a chest (no command) → auto "Locked private chest"; OTHER can't open.
- [ ] OTHER places a chest → owned by OTHER; OWNER can't open it.

### 3. Double chest + matcher  ⚠️ (chest matcher unverified)
- [ ] Make a double chest; `/lock` **one** half → click.
- [ ] OTHER opens the **other** half → denied.
- [ ] OWNER breaks the **protected** half → the remaining half stays protected (protection
      migrated, silently). OWNER breaks the remaining single chest → "Unlocked".

### 4. Break protection  ⚠️ (relies on cancelling the dig on `InteractBlockEvent.Primary`)
- [ ] OTHER cannot break **either** half of a locked double chest (dig is cancelled).
- [ ] OWNER can break their own; a **single** locked chest → protection removed + "Unlocked".

### 5. Doors
- [ ] `/lock` the **bottom** half of a door → click. OTHER can't open or break **either** half.
- [ ] A lever wired to OWNER's **private** door still opens it (private allows redstone).

---

## Inventory / containers  ⚠️ HIGHEST RISK (Sponge inventory events unverified)

### 6. Hoppers / transfer  ⚠️ (needs `ChangeInventoryEvent.Transfer.Pre` to fire for hoppers)
- [ ] Hopper under a **private**-locked chest → **does not drain** it. *(Critical.)*
- [ ] Lock a chest as `public` (`/lock public` → click) → a hopper **does** pull from it.
- [ ] Hopper **minecart** running under a locked chest → blocked.
- [ ] Hopper pointing **into** a locked chest → blocked (unless the type allows deposit).

### 7. In-GUI click/drag gating  ⚠️ (slot-direction + player-slot filtering unverified)
Lock a chest that already has items as a **`display`** protection (`/lock display` → click); a
display type is openable but grants neither deposit nor withdraw. As OTHER:
- [ ] Can **open** and view it.
- [ ] Taking an item → denied. Placing an item → denied.
- [ ] **Rearranging within the chest** (swap/move stacks) → denied.
- [ ] Shift-click, click-drag, number-key (hotbar swap), and double-click-collect → all denied.
- [ ] `deposit` type: OTHER can put items **in** but not take out. `withdrawal` type: the reverse.

### 8. Inventory open gate
- [ ] With a `display` chest, opening is allowed; with `private`, opening is denied (redundant with
      the interact gate, but confirms `InteractInventoryEvent.Open`).

---

## Entities  ⚠️ (entity spawn/interact events unverified)

### 9. Entity protection & auto-protect
- [ ] Place an **item frame** → auto-locked. OTHER can't rotate (right-click), can't take its item
      (left-click), can't break it.
- [ ] Place an **armor stand** → OTHER can't take/place armor or break it.
- [ ] Place a **painting** → OTHER can't break it.
- [ ] Place a **chest minecart** on rails → auto-locked; OTHER can't open it.

---

## Access management

### 10. Trust (global access list)
- [ ] OWNER `/bolt trust add player OTHER` → "Edited trust access list"; OTHER can now open **all**
      of OWNER's protections.
- [ ] `/bolt trust` (no args) → lists the access list.
- [ ] `/bolt trust remove player OTHER` → access revoked.

### 11. Edit / modify (click-based, single protection)
- [ ] `/bolt edit add OTHER` → click a chest → OTHER can open **that** chest only.
- [ ] `/bolt modify add normal player OTHER` → click → same result.
- [ ] `/bolt modify remove normal player OTHER` → click → revoked.

### 12. Password
- [ ] OWNER `/bolt modify add normal password hunter2` → click a chest.
- [ ] OTHER `/bolt password hunter2`, then opens that chest → allowed.
- [ ] OTHER relogs → password cleared → denied again.
- [ ] `config/bolt/bolt.conf` has a non-empty `settings.password-salt`.

### 13. Groups
- [ ] `/bolt group create mygroup OTHER` → created; `/bolt group list mygroup` → shows OTHER.
- [ ] `/bolt trust add group mygroup` → group members can access OWNER's protections.
- [ ] `/bolt group add mygroup THIRD`, `/bolt group remove mygroup OTHER`, `/bolt group delete mygroup`.

### 14. Modes (persisted)
- [ ] `/bolt mode nolock` → place a chest → **not** auto-locked. Toggle off → auto-lock resumes.
- [ ] `/bolt mode nospam` → auto-lock/unlock messages suppressed.
- [ ] `/bolt mode persist` → `/lock` then click several blocks → the action persists across clicks.
- [ ] Relog → modes persist (`config/bolt/players/<uuid>.properties` exists).
- [ ] Set `settings.default-modes = ["nospam"]`, `/bolt admin reload`, have a **fresh** player join →
      they have nospam without setting it.

---

## Admin (`bolt.admin` / op)

### 15. Admin commands
- [ ] `/bolt admin` → STATUS with block/entity counts.
- [ ] `/bolt admin reload` → "Reloaded"; edits to `bolt.conf` take effect.
- [ ] `/bolt admin flush` → reports a pending-save count.
- [ ] `/bolt admin purge OTHER` → removes all of OTHER's protections.
- [ ] `/bolt admin find OWNER` → lists OWNER's protections; `/bolt admin nearby 10` → nearby list.
- [ ] `/bolt admin expire 30 days` → removes protections unused for 30 days.
- [ ] `/bolt admin transfer OWNER NEWOWNER` → reassigns all; `/bolt admin transfer OWNER` → click a
      protection to transfer just that one.
- [ ] `/bolt admin storage export` → creates `config/bolt/export.db`; `storage import` → restores.
- [ ] `/bolt admin trust OTHER add player THIRD` → edits OTHER's access list.
- [ ] `/bolt admin debug` → click a protection → prints its raw data.
- [ ] `/bolt admin cleanup` → removes protections whose world no longer exists / block no longer
      matches.

---

## Persistence & environment

### 16. Storage
- [ ] With `database.type = sqlite` (default): lock several things, **restart** the server →
      protections survive. `config/bolt/bolt.db` exists.
- [ ] With `database.type = none`: protections are gone after restart (expected).
- [ ] On shutdown, no SQL errors in the log (flush + close on `GameStoppingServerEvent`).

### 17. Environmental protection
- [ ] TNT/creeper explosion next to a locked chest → the chest survives.
- [ ] A piston cannot push a **private**-locked block.

---

## Priority order (test these first — most likely to break)

1. **§0** load + messages render + no config-id warnings.
2. **§6** hopper does not drain a private chest (whole inventory path hinges on this event firing).
3. **§7** display-chest click/drag gating.
4. **§3–§4** double-chest matching + break-attempt cancel.
5. **§9** entity auto-protect + interact.
6. **§16** SQLite survives a restart.

When something fails, grab `logs/latest.log` (and `crash-reports/` if it crashed) — Sponge sends
most errors there, not to chat.
