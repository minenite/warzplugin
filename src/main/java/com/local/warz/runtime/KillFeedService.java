package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import com.local.warz.config.AmmoCaliber;
import com.local.warz.model.GunDefinition;
import com.local.warz.model.RoundDefinition;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Custom PvP kill lines — range / ADS / fall trickshots / suppressor / wallbang /
 * streaks / revenge / bleed-out / caliber flavor, with editable {@code kill-feed.yml}.
 */
public final class KillFeedService implements Listener {
    public enum HitKind {
        BULLET,
        HEADSHOT,
        EXPLODE,
        FIRE,
        THROWABLE
    }

    public enum RangeBand {
        POINT_BLANK,
        CLOSE,
        MID,
        LONG,
        EXTREME
    }

    /** Mutable shot flags captured at fire / impact. */
    public static final class ShotContext {
        public boolean aimed;
        public double rangeBlocks;
        public float fallDistance;
        public boolean suppressed;
        public boolean wallbang;
        public boolean throughGlass;
        public boolean ricochet;
        public boolean fromDrone;
        /** Round file name used for this shot (kill-feed shows bullet name). */
        public String roundId;
    }

    private final WarzPlugin plugin;
    private final KillFeedCatalog catalog;
    private final Map<UUID, Credit> credits = new ConcurrentHashMap<>();
    private final Map<UUID, Credit> bleedCredits = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> bledOut = new ConcurrentHashMap<>();
    private final Map<UUID, KillerState> killerStates = new ConcurrentHashMap<>();
    /** victim → who last killed them (for revenge). */
    private final Map<UUID, UUID> lastKillerOf = new ConcurrentHashMap<>();

    public KillFeedService(WarzPlugin plugin) {
        this.plugin = plugin;
        this.catalog = new KillFeedCatalog(plugin);
        this.catalog.reload();
    }

    public void reload() {
        catalog.reload();
    }

    public KillFeedCatalog catalog() {
        return catalog;
    }

    public void record(Player victim, Player killer, GunDefinition gun, HitKind kind,
                       ItemStack weaponSnapshot) {
        ShotContext ctx = new ShotContext();
        if (killer != null) {
            ctx.fallDistance = killer.getFallDistance();
            ctx.suppressed = plugin.items() != null
                    && plugin.items().hasSuppressor(killer.getInventory().getItemInMainHand());
            if (plugin.sessions() != null) {
                GunPlayerSession session = plugin.sessions().get(killer);
                if (session != null) {
                    ctx.aimed = session.isAimedIn();
                }
            }
            if (victim != null && killer.getWorld().equals(victim.getWorld())) {
                ctx.rangeBlocks = killer.getEyeLocation().distance(victim.getEyeLocation());
            }
            if (plugin.bigDrone() != null && plugin.bigDrone().isPiloting(killer)) {
                ctx.fromDrone = true;
            }
        }
        record(victim, killer, gun, kind, weaponSnapshot, ctx);
    }

    public void record(Player victim, Player killer, GunDefinition gun, HitKind kind,
                       ItemStack weaponSnapshot, boolean aimed) {
        ShotContext ctx = new ShotContext();
        ctx.aimed = aimed;
        if (killer != null) {
            ctx.fallDistance = killer.getFallDistance();
            ctx.suppressed = plugin.items() != null
                    && plugin.items().hasSuppressor(weaponSnapshot != null
                    ? weaponSnapshot : killer.getInventory().getItemInMainHand());
            if (victim != null && killer.getWorld().equals(victim.getWorld())) {
                ctx.rangeBlocks = killer.getEyeLocation().distance(victim.getEyeLocation());
            }
            if (plugin.bigDrone() != null && plugin.bigDrone().isPiloting(killer)) {
                ctx.fromDrone = true;
            }
        }
        record(victim, killer, gun, kind, weaponSnapshot, ctx);
    }

    public void record(Player victim, Player killer, GunDefinition gun, HitKind kind,
                       ItemStack weaponSnapshot, boolean aimed, double rangeBlocks, float fallDistance) {
        ShotContext ctx = new ShotContext();
        ctx.aimed = aimed;
        ctx.rangeBlocks = rangeBlocks;
        ctx.fallDistance = fallDistance;
        if (killer != null) {
            ctx.suppressed = plugin.items() != null
                    && plugin.items().hasSuppressor(weaponSnapshot != null
                    ? weaponSnapshot : killer.getInventory().getItemInMainHand());
            if (plugin.bigDrone() != null && plugin.bigDrone().isPiloting(killer)) {
                ctx.fromDrone = true;
            }
        }
        record(victim, killer, gun, kind, weaponSnapshot, ctx);
    }

    public void record(Player victim, Player killer, GunDefinition gun, HitKind kind,
                       ItemStack weaponSnapshot, ShotContext ctx) {
        if (victim == null || killer == null || gun == null || kind == null || ctx == null) {
            return;
        }
        HitKind use = kind;
        if (gun.throwable() && (use == HitKind.BULLET || use == HitKind.EXPLODE || use == HitKind.FIRE)) {
            if (use == HitKind.BULLET) {
                use = HitKind.THROWABLE;
            }
        }
        if (!ctx.fromDrone && plugin.bigDrone() != null && plugin.bigDrone().isPiloting(killer)) {
            ctx.fromDrone = true;
        }
        String gunId = gun.fileName() == null ? "" : gun.fileName();
        if (!ctx.fromDrone && gunId.toLowerCase(Locale.ROOT).contains("law_drone")) {
            ctx.fromDrone = true;
        }
        if (!ctx.suppressed && plugin.items() != null) {
            ctx.suppressed = plugin.items().hasSuppressor(weaponSnapshot);
        }

        RoundDefinition round = null;
        if (ctx.roundId != null && plugin.rounds() != null) {
            round = plugin.rounds().get(ctx.roundId).orElse(null);
        }
        // Chat {gun} token shows the bullet used; hover still includes gun + attachments.
        String gunName = round != null ? plainRoundName(round) : plainGunName(gun);
        String cal = round != null
                ? AmmoCaliber.normalize(round.caliber())
                : AmmoCaliber.normalize(gun.ammoCaliber());
        List<Component> hover = plugin.items() != null
                ? plugin.items().killFeedHoverLines(gun, weaponSnapshot, ctx.rangeBlocks, round)
                : List.of(Component.text("No attachments", NamedTextColor.DARK_GRAY));

        Credit credit = new Credit(
                killer.getUniqueId(),
                killer.getName(),
                gunId,
                gunName,
                cal,
                use,
                gun.throwable(),
                gun.explodeRadius() > 0 || gun.explosionDamage() > 0,
                gun.fireRadius() > 0,
                ctx.aimed,
                Math.max(0.0, ctx.rangeBlocks),
                Math.max(0f, ctx.fallDistance),
                ctx.suppressed,
                ctx.wallbang,
                ctx.throughGlass,
                ctx.ricochet,
                ctx.fromDrone,
                false,
                hover,
                System.currentTimeMillis());
        credits.put(victim.getUniqueId(), credit);
        bleedCredits.put(victim.getUniqueId(), credit);
    }

    /** Call just before a bleed-out {@code setHealth(0)} so the gun credit still applies. */
    public void markBleedOut(Player victim) {
        if (victim == null) {
            return;
        }
        UUID id = victim.getUniqueId();
        bledOut.put(id, Boolean.TRUE);
        Credit prior = credits.get(id);
        if (prior == null) {
            prior = bleedCredits.get(id);
        }
        if (prior != null) {
            Credit refreshed = prior.withBleedOut(true).withTime(System.currentTimeMillis());
            credits.put(id, refreshed);
            bleedCredits.put(id, refreshed);
        }
    }

    public boolean apply(PlayerDeathEvent event) {
        if (event == null || event.getEntity() == null) {
            return false;
        }
        Player victim = event.getEntity();
        UUID victimId = victim.getUniqueId();
        boolean bleed = Boolean.TRUE.equals(bledOut.remove(victimId));
        Credit credit = credits.remove(victimId);
        if (credit == null) {
            credit = bleedCredits.remove(victimId);
        } else {
            bleedCredits.remove(victimId);
        }
        if (credit == null) {
            return false;
        }
        long age = System.currentTimeMillis() - credit.atMs;
        long maxAge = bleed || credit.bleedOut ? catalog.bleedCreditMs : catalog.creditMs;
        if (age > maxAge) {
            return false;
        }
        if (bleed && !credit.bleedOut) {
            credit = credit.withBleedOut(true);
        }

        int streak = updateStreak(credit.killerId);
        boolean revenge = isRevenge(credit.killerId, victimId);
        lastKillerOf.put(victimId, credit.killerId);

        Pick pick = pickTemplate(credit, revenge, streak,
                credit.killerId.equals(victimId));
        // Count this kill before building hover stats on the names.
        if (plugin.profileStats() != null) {
            plugin.profileStats().touchName(credit.killerId, credit.killerName);
            plugin.profileStats().touchName(victimId, victim.getName());
            if (!credit.killerId.equals(victimId)) {
                plugin.profileStats().recordPvP(credit.killerId, victimId);
            }
        }
        Component message = buildMessage(victimId, victim.getName(), credit, pick);
        event.deathMessage(message);

        Player killer = Bukkit.getPlayer(credit.killerId);
        if (killer != null && killer.isOnline()) {
            celebrate(killer, victim, credit, pick);
        }
        rememberTemplate(credit.killerId, pick.template());
        return true;
    }

    private int updateStreak(UUID killerId) {
        long now = System.currentTimeMillis();
        KillerState prev = killerStates.get(killerId);
        int streak = 1;
        if (prev != null && now - prev.lastKillMs <= catalog.streakWindowMs) {
            streak = prev.streak + 1;
        }
        String lastTemplate = prev != null ? prev.lastTemplate : null;
        killerStates.put(killerId, new KillerState(streak, now, lastTemplate));
        return streak;
    }

    private void rememberTemplate(UUID killerId, String template) {
        KillerState prev = killerStates.get(killerId);
        if (prev == null) {
            killerStates.put(killerId, new KillerState(1, System.currentTimeMillis(), template));
        } else {
            killerStates.put(killerId, new KillerState(prev.streak, prev.lastKillMs, template));
        }
    }

    private boolean isRevenge(UUID killerId, UUID victimId) {
        UUID whoKilledKiller = lastKillerOf.get(killerId);
        return whoKilledKiller != null && whoKilledKiller.equals(victimId);
    }

    private Component buildMessage(UUID victimId, String victimName, Credit credit, Pick pick) {
        String killer = credit.killerName == null ? "Someone" : credit.killerName;
        String victim = victimName == null ? "Someone" : victimName;
        String[] parts = pick.template().split("\\{gun}", -1);
        Component out = Component.empty().decoration(TextDecoration.ITALIC, false);
        for (int i = 0; i < parts.length; i++) {
            out = out.append(parseNameHovers(parts[i], credit.killerId, killer, victimId, victim, pick.bold()));
            if (i < parts.length - 1) {
                out = out.append(gunNameComponent(credit, pick.bold()));
            }
        }
        return out;
    }

    /**
     * Parses a template chunk, replacing {@code {killer}} / {@code {victim}} with
     * hoverable names (KDR / kills / deaths).
     */
    private Component parseNameHovers(String chunk, UUID killerId, String killerName,
                                      UUID victimId, String victimName, boolean bold) {
        if (chunk == null || chunk.isEmpty()) {
            return Component.empty();
        }
        Component out = Component.empty().decoration(TextDecoration.ITALIC, false);
        int i = 0;
        while (i < chunk.length()) {
            int kPos = chunk.indexOf("{killer}", i);
            int vPos = chunk.indexOf("{victim}", i);
            int next;
            boolean isKiller;
            if (kPos < 0 && vPos < 0) {
                out = out.append(legacyChunk(chunk.substring(i), bold));
                break;
            }
            if (kPos >= 0 && (vPos < 0 || kPos < vPos)) {
                next = kPos;
                isKiller = true;
            } else {
                next = vPos;
                isKiller = false;
            }
            String before = chunk.substring(i, next);
            String[] split = splitTrailingColor(before);
            out = out.append(legacyChunk(split[0], bold));
            String nameColor = split[1];
            if (isKiller) {
                out = out.append(playerNameComponent(killerId, killerName, nameColor, bold));
                i = next + "{killer}".length();
            } else {
                out = out.append(playerNameComponent(victimId, victimName, nameColor, bold));
                i = next + "{victim}".length();
            }
        }
        return out;
    }

    private Component playerNameComponent(UUID id, String name, String color, boolean bold) {
        if (plugin.profileStats() != null) {
            return plugin.profileStats().nameWithStatsHover(id, name, color, bold);
        }
        return legacyChunk((color == null ? "&6" : color) + (name == null ? "Someone" : name), bold);
    }

    private static Component legacyChunk(String chunk, boolean bold) {
        Component piece = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(chunk == null ? "" : chunk)
                .decoration(TextDecoration.ITALIC, false);
        if (bold) {
            piece = piece.decoration(TextDecoration.BOLD, true);
        }
        return piece;
    }

    /** {@code [textWithoutTrailingColor, trailingColorOrDefault]} */
    private static String[] splitTrailingColor(String before) {
        if (before == null || before.isEmpty()) {
            return new String[]{"", "&6"};
        }
        int end = before.length();
        int pos = end;
        while (pos >= 2 && before.charAt(pos - 2) == '&') {
            char code = Character.toLowerCase(before.charAt(pos - 1));
            if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')
                    || code == 'k' || code == 'l' || code == 'm' || code == 'n'
                    || code == 'o' || code == 'r') {
                pos -= 2;
            } else {
                break;
            }
        }
        if (pos == end) {
            return new String[]{before, "&6"};
        }
        String color = before.substring(pos);
        // Prefer last color code for the name (skip formats like &l alone).
        String nameColor = "&6";
        for (int i = 0; i + 1 < color.length(); i += 2) {
            if (color.charAt(i) != '&') {
                break;
            }
            char c = Character.toLowerCase(color.charAt(i + 1));
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
                nameColor = "&" + c;
            }
        }
        return new String[]{before.substring(0, pos), nameColor};
    }

    private Component gunNameComponent(Credit credit, boolean ignoredBold) {
        Component hoverBody;
        if (credit.attachmentHover == null || credit.attachmentHover.isEmpty()) {
            hoverBody = Component.text("No attachments", NamedTextColor.DARK_GRAY);
        } else {
            hoverBody = Component.empty();
            for (int i = 0; i < credit.attachmentHover.size(); i++) {
                if (i > 0) {
                    hoverBody = hoverBody.append(Component.newline());
                }
                hoverBody = hoverBody.append(credit.attachmentHover.get(i));
            }
        }
        // Never bold the weapon/bullet token — hover carries the detail.
        Component name = Component.text(credit.gunDisplayName, NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, false);
        return name.hoverEvent(HoverEvent.showText(hoverBody));
    }

    private void celebrate(Player killer, Player victim, Credit credit, Pick pick) {
        if (!catalog.sounds && !catalog.toasts) {
            return;
        }
        RangeBand band = rangeBand(credit.rangeBlocks);
        boolean special = pick.bold() || pick.category() == Category.TRICKSHOT
                || pick.category() == Category.EXTREME || pick.category() == Category.REVENGE
                || pick.category() == Category.STREAK || (credit.kind == HitKind.HEADSHOT && band == RangeBand.EXTREME);
        if (!special) {
            return;
        }
        if (catalog.sounds) {
            Sound sound = switch (pick.category()) {
                case TRICKSHOT -> Sound.UI_TOAST_CHALLENGE_COMPLETE;
                case REVENGE -> Sound.BLOCK_NOTE_BLOCK_BIT;
                case STREAK -> Sound.ENTITY_PLAYER_LEVELUP;
                case EXTREME -> Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
                default -> Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
            };
            float pitch = pick.category() == Category.TRICKSHOT ? 1.35f
                    : (pick.category() == Category.EXTREME ? 1.6f : 1.15f);
            killer.playSound(killer.getLocation(), sound, 0.55f, pitch);
        }
        if (catalog.toasts) {
            String toast = switch (pick.category()) {
                case TRICKSHOT -> "&f&lNOSCOPE TRICKSHOT";
                case REVENGE -> "&c&lREVENGE";
                case STREAK -> "&e&lSTREAK x" + Math.max(2, killerStates.getOrDefault(
                        credit.killerId, new KillerState(2, 0, null)).streak);
                case EXTREME -> "&b&lEXTREME RANGE &f" + Math.round(credit.rangeBlocks) + "m";
                case WALLBANG -> "&7&lWALLBANG";
                case BLEED -> "&4&lBLEED OUT";
                default -> credit.kind == HitKind.HEADSHOT ? "&c&lHEADSHOT" : null;
            };
            if (toast != null) {
                killer.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize(toast));
                if (pick.bold() || pick.category() == Category.EXTREME || pick.category() == Category.TRICKSHOT) {
                    killer.showTitle(Title.title(
                            LegacyComponentSerializer.legacyAmpersand().deserialize(toast),
                            Component.text(victim.getName(), NamedTextColor.GOLD),
                            Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(900), Duration.ofMillis(250))));
                }
            }
        }
    }

    private Pick pickTemplate(Credit credit, boolean revenge, int streak, boolean suicide) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        if (suicide) {
            return new Pick(choose(rng, credit.killerId, catalog.poolOrDefault("suicide", new String[]{
                    "&6{killer} &7ended themselves with a &6{gun}",
                    "&6{killer} &7had a negligent discharge with a &6{gun}"
            })), false, Category.NORMAL);
        }

        HitKind kind = credit.kind;
        if (kind == HitKind.THROWABLE && credit.explosive) {
            kind = HitKind.EXPLODE;
        } else if (kind == HitKind.THROWABLE && credit.incendiary) {
            kind = HitKind.FIRE;
        }

        RangeBand band = rangeBand(credit.rangeBlocks);
        boolean sniper = isSniper(credit);
        boolean headshot = kind == HitKind.HEADSHOT;
        boolean gunKill = kind == HitKind.BULLET || kind == HitKind.HEADSHOT;
        GunFamily family = gunFamily(credit);

        // Priority specials
        if (credit.bleedOut && gunKill) {
            return pickFrom(rng, credit, Category.BLEED, true, bleedLines(band));
        }
        if (gunKill && !credit.aimed && credit.fallDistance > catalog.trickshotFallBlocks) {
            return pickFrom(rng, credit, Category.TRICKSHOT, true, trickshotLines(headshot, band));
        }
        if (revenge && gunKill) {
            return pickFrom(rng, credit, Category.REVENGE, false, revengeLines(band));
        }
        if (credit.throughGlass && gunKill) {
            return pickFrom(rng, credit, Category.WALLBANG, false, glassLines(headshot, band));
        }
        if (credit.wallbang && gunKill) {
            return pickFrom(rng, credit, Category.WALLBANG, false, wallbangLines(headshot, band));
        }
        if (credit.ricochet && gunKill) {
            return pickFrom(rng, credit, Category.WALLBANG, false, ricochetLines(headshot, band));
        }
        if (streak >= 3 && gunKill) {
            return pickFrom(rng, credit, Category.STREAK, false, streakLines(streak, band));
        }
        if (streak == 2 && gunKill) {
            return pickFrom(rng, credit, Category.STREAK, false, backToBackLines(band));
        }
        if (credit.fromDrone && gunKill) {
            return pickFrom(rng, credit, Category.DRONE, false, droneLines(headshot, band));
        }
        if (credit.suppressed && gunKill) {
            return pickFrom(rng, credit, Category.SUPPRESSED, false, suppressedLines(headshot, sniper, band));
        }
        if (headshot && band == RangeBand.EXTREME) {
            return pickFrom(rng, credit, Category.EXTREME, false, extremeHeadshotLines(sniper));
        }

        String[] lines = switch (kind) {
            case HEADSHOT -> headshotLines(sniper, credit.aimed, band, family);
            case EXPLODE -> explodeLines(band, credit.fromDrone);
            case FIRE -> fireLines(band);
            case THROWABLE -> throwableLines(band);
            case BULLET -> bulletLines(sniper, credit.aimed, band, family, credit.suppressed);
        };
        Category cat = band == RangeBand.EXTREME ? Category.EXTREME
                : (headshot ? Category.HEADSHOT : Category.NORMAL);
        return pickFrom(rng, credit, cat, false, lines);
    }

    private Pick pickFrom(ThreadLocalRandom rng, Credit credit, Category category,
                          boolean bold, String[] lines) {
        String[] pool = catalog.poolOrDefault(category.name().toLowerCase(Locale.ROOT), lines);
        List<String> filtered = filterContext(pool, credit.aimed, rangeBand(credit.rangeBlocks));
        String chosen = choose(rng, credit.killerId, filtered.isEmpty() ? pool : filtered.toArray(new String[0]));
        // Accent colors by category (applied when template uses &7 verbs — rewrite lightly)
        chosen = accent(chosen, category, credit.kind);
        return new Pick(chosen, bold, category);
    }

    private String accent(String template, Category category, HitKind kind) {
        if (template == null) {
            return "";
        }
        // Only recolor plain gray verbs; leave trickshot &f / custom colors alone.
        return switch (category) {
            case HEADSHOT -> template.replace(" &7", " &c");
            case EXTREME -> template.replace(" &7", " &b");
            case SUPPRESSED -> template.replace(" &7", " &8");
            case BLEED -> template.replace(" &7", " &4");
            case WALLBANG -> template.replace(" &7", " &7");
            case EXPLODE -> template; // explode pools already warm
            default -> {
                if (kind == HitKind.EXPLODE) {
                    yield template.replace(" &7", " &6");
                }
                if (kind == HitKind.FIRE) {
                    yield template.replace(" &7", " &6");
                }
                yield template;
            }
        };
    }

    private String choose(ThreadLocalRandom rng, UUID killerId, String[] lines) {
        if (lines == null || lines.length == 0) {
            return "&6{killer} &7killed &6{victim} &7with a &6{gun}";
        }
        if (lines.length == 1 || catalog.templateCooldownMs <= 0 || killerId == null) {
            return lines[rng.nextInt(lines.length)];
        }
        KillerState state = killerStates.get(killerId);
        String last = state != null ? state.lastTemplate : null;
        long now = System.currentTimeMillis();
        boolean cooling = state != null && last != null
                && now - state.lastKillMs <= catalog.templateCooldownMs;
        if (!cooling) {
            return lines[rng.nextInt(lines.length)];
        }
        List<String> alt = new ArrayList<>(lines.length);
        for (String line : lines) {
            if (!line.equals(last)) {
                alt.add(line);
            }
        }
        if (alt.isEmpty()) {
            return lines[rng.nextInt(lines.length)];
        }
        return alt.get(rng.nextInt(alt.size()));
    }

    // ---- pools ----------------------------------------------------------------

    private static String[] trickshotLines(boolean headshot, RangeBand band) {
        List<String> out = new ArrayList<>();
        if (headshot) {
            out.add("&6{killer} &fnoscope trickshotted &6{victim} &fin the head with a &6{gun}");
            out.add("&6{killer} &fhit a falling noscope headshot on &6{victim} &fwith a &6{gun}");
            out.add("&6{killer} &fpulled a mid-air noscope headshot on &6{victim} &fusing a &6{gun}");
        } else {
            out.add("&6{killer} &fnoscope trickshotted &6{victim} &fwith a &6{gun}");
            out.add("&6{killer} &fhit a falling noscope on &6{victim} &fwith a &6{gun}");
            out.add("&6{killer} &fpulled a mid-air noscope on &6{victim} &fusing a &6{gun}");
        }
        switch (band) {
            case POINT_BLANK -> out.add("&6{killer} &fnoscope trickshotted &6{victim} &fpoint blank with a &6{gun}");
            case CLOSE -> out.add("&6{killer} &fnoscope trickshotted &6{victim} &ffrom close range with a &6{gun}");
            case LONG -> out.add("&6{killer} &fnoscope trickshotted &6{victim} &ffrom long range with a &6{gun}");
            case EXTREME -> out.add("&6{killer} &fnoscope trickshotted &6{victim} &ffrom across the map with a &6{gun}");
            case MID -> out.add("&6{killer} &fclipped &6{victim} &fwith a falling noscope from a &6{gun}");
        }
        return out.toArray(new String[0]);
    }

    private static String[] revengeLines(RangeBand band) {
        List<String> out = new ArrayList<>();
        out.add("&6{killer} &7got revenge on &6{victim} &7with a &6{gun}");
        out.add("&6{killer} &7paid &6{victim} &7back with a &6{gun}");
        out.add("&6{killer} &7settled the score with &6{victim} &7using a &6{gun}");
        if (band == RangeBand.LONG || band == RangeBand.EXTREME) {
            out.add("&6{killer} &7got long-range revenge on &6{victim} &7with a &6{gun}");
        }
        if (band == RangeBand.POINT_BLANK) {
            out.add("&6{killer} &7got point-blank revenge on &6{victim} &7with a &6{gun}");
        }
        return out.toArray(new String[0]);
    }

    private static String[] backToBackLines(RangeBand band) {
        return new String[]{
                "&6{killer} &7back-to-back killed &6{victim} &7with a &6{gun}",
                "&6{killer} &7chained another onto &6{victim} &7using a &6{gun}",
                "&6{killer} &7kept the streak alive on &6{victim} &7with a &6{gun}"
        };
    }

    private static String[] streakLines(int streak, RangeBand band) {
        return new String[]{
                "&6{killer} &7is on a tear — dropped &6{victim} &7with a &6{gun}",
                "&6{killer} &7is cooking — finished &6{victim} &7using a &6{gun}",
                "&6{killer} &7ran it up on &6{victim} &7with a &6{gun}",
                "&6{killer} &7(" + streak + " streak) deleted &6{victim} &7with a &6{gun}"
        };
    }

    private static String[] suppressedLines(boolean headshot, boolean sniper, RangeBand band) {
        List<String> out = new ArrayList<>();
        if (headshot) {
            out.add("&6{killer} &7silently headshotted &6{victim} &7with a &6{gun}");
            out.add("&6{killer} &7whispered a headshot into &6{victim} &7using a &6{gun}");
        } else {
            out.add("&6{killer} &7silenced &6{victim} &7with a &6{gun}");
            out.add("&6{killer} &7quietly dropped &6{victim} &7using a &6{gun}");
            out.add("&6{killer} &7muted &6{victim} &7with a &6{gun}");
        }
        if (sniper) {
            out.add("&6{killer} &7quietly assassinated &6{victim} &7with a &6{gun}");
        }
        if (band == RangeBand.POINT_BLANK) {
            out.add("&6{killer} &7silenced &6{victim} &7point blank with a &6{gun}");
        }
        return out.toArray(new String[0]);
    }

    private static String[] glassLines(boolean headshot, RangeBand band) {
        List<String> out = new ArrayList<>();
        out.add("&6{killer} &7shattered glass into &6{victim} &7with a &6{gun}");
        out.add("&6{killer} &7punched through glass to drop &6{victim} &7using a &6{gun}");
        if (headshot) {
            out.add("&6{killer} &7glass-peek headshotted &6{victim} &7with a &6{gun}");
        }
        return out.toArray(new String[0]);
    }

    private static String[] wallbangLines(boolean headshot, RangeBand band) {
        List<String> out = new ArrayList<>();
        out.add("&6{killer} &7wallbanged &6{victim} &7with a &6{gun}");
        out.add("&6{killer} &7shot through cover to kill &6{victim} &7using a &6{gun}");
        if (headshot) {
            out.add("&6{killer} &7wallbang headshotted &6{victim} &7with a &6{gun}");
        }
        return out.toArray(new String[0]);
    }

    private static String[] ricochetLines(boolean headshot, RangeBand band) {
        List<String> out = new ArrayList<>();
        out.add("&6{killer} &7ricocheted a round into &6{victim} &7with a &6{gun}");
        out.add("&6{killer} &7banked a shot off cover into &6{victim} &7using a &6{gun}");
        if (headshot) {
            out.add("&6{killer} &7ricochet headshotted &6{victim} &7with a &6{gun}");
        }
        return out.toArray(new String[0]);
    }

    private static String[] droneLines(boolean headshot, RangeBand band) {
        List<String> out = new ArrayList<>();
        out.add("&6{killer} &7struck &6{victim} &7from a drone with a &6{gun}");
        out.add("&6{killer} &7painted &6{victim} &7from a UAV using a &6{gun}");
        out.add("&6{killer} &7drone-striked &6{victim} &7with a &6{gun}");
        if (headshot) {
            out.add("&6{killer} &7landed a drone headshot on &6{victim} &7with a &6{gun}");
        }
        if (band == RangeBand.EXTREME || band == RangeBand.LONG) {
            out.add("&6{killer} &7reached out from the drone and deleted &6{victim} &7with a &6{gun}");
        }
        return out.toArray(new String[0]);
    }

    private static String[] bleedLines(RangeBand band) {
        return new String[]{
                "&6{killer} &7bled &6{victim} &7out with a &6{gun}",
                "&6{victim} &7bled out after &6{killer} &7hit them with a &6{gun}",
                "&6{killer} &7let &6{victim} &7bleed out from a &6{gun}",
                "&6{killer} &7finished &6{victim} &7the slow way with a &6{gun}"
        };
    }

    private static String[] extremeHeadshotLines(boolean sniper) {
        List<String> out = new ArrayList<>();
        out.add("&6{killer} &7landed an extreme-range headshot on &6{victim} &7with a &6{gun}");
        out.add("&6{killer} &7deleted &6{victim} &7from across the map with a &6{gun}");
        out.add("&6{killer} &7one-tapped &6{victim} &7from another zip code with a &6{gun}");
        if (sniper) {
            out.add("&6{killer} &7laser-beamed &6{victim}&7's skull from extreme range with a &6{gun}");
        }
        return out.toArray(new String[0]);
    }

    private static String[] headshotLines(boolean sniper, boolean aimed, RangeBand band, GunFamily family) {
        List<String> out = new ArrayList<>();
        out.add("&6{killer} &7headshotted &6{victim} &7with a &6{gun}");
        out.add("&6{killer} &7put one through &6{victim}&7's skull with a &6{gun}");
        out.add("&6{killer} &7landed a clean headshot on &6{victim} &7using a &6{gun}");
        out.add("&6{killer} &7dropped &6{victim} &7with a headshot from a &6{gun}");
        if (sniper) {
            out.add("&6{killer} &7assassinated &6{victim} &7with a &6{gun}");
            out.add("&6{killer} &7sniped &6{victim} &7clean through the head with a &6{gun}");
            if (aimed) {
                out.add("&6{killer} &7dropped &6{victim} &7with a scoped headshot from a &6{gun}");
            }
        }
        if (family == GunFamily.SHOTGUN) {
            out.add("&6{killer} &7blew &6{victim}&7's head off with a &6{gun}");
        }
        switch (band) {
            case POINT_BLANK -> out.add("&6{killer} &7headshotted &6{victim} &7point blank with a &6{gun}");
            case CLOSE -> out.add("&6{killer} &7popped &6{victim}&7's head from close range with a &6{gun}");
            case LONG -> out.add("&6{killer} &7landed a long-range headshot on &6{victim} &7using a &6{gun}");
            case EXTREME -> out.add("&6{killer} &7landed an extreme-range headshot on &6{victim} &7with a &6{gun}");
            case MID -> {
            }
        }
        return out.toArray(new String[0]);
    }

    private static String[] bulletLines(boolean sniper, boolean aimed, RangeBand band,
                                        GunFamily family, boolean suppressed) {
        List<String> out = new ArrayList<>();
        switch (family) {
            case SHOTGUN -> {
                out.add("&6{killer} &7blasted &6{victim} &7with a &6{gun}");
                out.add("&6{killer} &7pumped &6{victim} &7full of lead using a &6{gun}");
                out.add("&6{killer} &7turned &6{victim} &7into Swiss cheese with a &6{gun}");
            }
            case SMG -> {
                out.add("&6{killer} &7sprayed down &6{victim} &7with a &6{gun}");
                out.add("&6{killer} &7stitched &6{victim} &7using a &6{gun}");
                out.add("&6{killer} &7hosed &6{victim} &7with a &6{gun}");
            }
            case PISTOL -> {
                out.add("&6{killer} &7gunned down &6{victim} &7with a &6{gun}");
                out.add("&6{killer} &7popped &6{victim} &7using a &6{gun}");
                out.add("&6{killer} &7finished &6{victim} &7with a &6{gun}");
            }
            case LAUNCHER -> {
                out.add("&6{killer} &7erased &6{victim} &7with a &6{gun}");
                out.add("&6{killer} &7deleted &6{victim} &7using a &6{gun}");
            }
            case ENERGY -> {
                out.add("&6{killer} &7zapped &6{victim} &7with a &6{gun}");
                out.add("&6{killer} &7ionized &6{victim} &7using a &6{gun}");
            }
            case BOW -> {
                out.add("&6{killer} &7skewered &6{victim} &7with a &6{gun}");
                out.add("&6{killer} &7pinned &6{victim} &7using a &6{gun}");
            }
            case SNIPER -> {
                out.add("&6{killer} &7assassinated &6{victim} &7with a &6{gun}");
                out.add("&6{killer} &7sniped &6{victim} &7with a &6{gun}");
                out.add("&6{killer} &7picked off &6{victim} &7with a &6{gun}");
                out.add("&6{killer} &7neutralized &6{victim} &7with a &6{gun}");
                if (aimed) {
                    out.add("&6{killer} &7lined up &6{victim} &7and dropped them with a &6{gun}");
                }
            }
            default -> {
                out.add("&6{killer} &7killed &6{victim} &7with a &6{gun}");
                out.add("&6{killer} &7gunned down &6{victim} &7using a &6{gun}");
                out.add("&6{killer} &7dropped &6{victim} &7with a &6{gun}");
                out.add("&6{killer} &7took out &6{victim} &7using a &6{gun}");
                out.add("&6{killer} &7ended &6{victim} &7with a &6{gun}");
                out.add("&6{killer} &7wasted &6{victim} &7using a &6{gun}");
                out.add("&6{killer} &7finished &6{victim} &7with a &6{gun}");
            }
        }
        if (suppressed) {
            out.add("&6{killer} &7silenced &6{victim} &7with a &6{gun}");
        }
        switch (band) {
            case POINT_BLANK -> {
                out.add("&6{killer} &7blasted &6{victim} &7point blank with a &6{gun}");
                out.add("&6{killer} &7executed &6{victim} &7up close with a &6{gun}");
            }
            case CLOSE -> out.add("&6{killer} &7dropped &6{victim} &7from close range with a &6{gun}");
            case LONG -> {
                out.add("&6{killer} &7took down &6{victim} &7at range using a &6{gun}");
                out.add("&6{killer} &7eliminated &6{victim} &7from afar with a &6{gun}");
            }
            case EXTREME -> {
                out.add("&6{killer} &7eliminated &6{victim} &7from across the map with a &6{gun}");
                if (sniper || family == GunFamily.SNIPER) {
                    out.add("&6{killer} &7landed an extreme-range snipe on &6{victim} &7with a &6{gun}");
                }
            }
            case MID -> {
            }
        }
        return out.toArray(new String[0]);
    }

    private static String[] explodeLines(RangeBand band, boolean drone) {
        List<String> out = new ArrayList<>();
        out.add("&6{killer} &7blew &6{victim} &7into pieces using a &6{gun}");
        out.add("&6{killer} &7turned &6{victim} &7into confetti with a &6{gun}");
        out.add("&6{killer} &7obliterated &6{victim} &7with a &6{gun}");
        out.add("&6{killer} &7vaporized &6{victim} &7with a &6{gun}");
        if (drone) {
            out.add("&6{killer} &7erased &6{victim} &7from a drone with a &6{gun}");
        }
        if (band == RangeBand.POINT_BLANK || band == RangeBand.CLOSE) {
            out.add("&6{killer} &7cook-off'd &6{victim} &7up close with a &6{gun}");
        }
        if (band == RangeBand.LONG || band == RangeBand.EXTREME) {
            out.add("&6{killer} &7lobbed a long-range boom onto &6{victim} &7with a &6{gun}");
        }
        return out.toArray(new String[0]);
    }

    private static String[] fireLines(RangeBand band) {
        List<String> out = new ArrayList<>();
        out.add("&6{killer} &7set &6{victim} &7ablaze with a &6{gun}");
        out.add("&6{killer} &7cooked &6{victim} &7alive using a &6{gun}");
        out.add("&6{killer} &7torched &6{victim} &7with a &6{gun}");
        out.add("&6{killer} &7lit &6{victim} &7up using a &6{gun}");
        if (band == RangeBand.POINT_BLANK) {
            out.add("&6{killer} &7torched &6{victim} &7point blank with a &6{gun}");
        }
        return out.toArray(new String[0]);
    }

    private static String[] throwableLines(RangeBand band) {
        List<String> out = new ArrayList<>();
        out.add("&6{killer} &7fragged &6{victim} &7with a &6{gun}");
        out.add("&6{killer} &7got &6{victim} &7with a well-placed &6{gun}");
        out.add("&6{killer} &7caught &6{victim} &7with a &6{gun}");
        if (band == RangeBand.POINT_BLANK || band == RangeBand.CLOSE) {
            out.add("&6{killer} &7stuck &6{victim} &7up close with a &6{gun}");
        }
        if (band == RangeBand.LONG || band == RangeBand.EXTREME) {
            out.add("&6{killer} &7nailed &6{victim} &7from range with a &6{gun}");
        }
        return out.toArray(new String[0]);
    }

    private static List<String> filterContext(String[] lines, boolean aimed, RangeBand band) {
        List<String> filtered = new ArrayList<>(lines.length);
        boolean close = band == RangeBand.POINT_BLANK || band == RangeBand.CLOSE;
        boolean far = band == RangeBand.LONG || band == RangeBand.EXTREME;
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (!aimed && lower.contains("scoped")) {
                continue;
            }
            if (!far && (lower.contains("from afar") || lower.contains("long-range")
                    || lower.contains("at range") || lower.contains("across the map")
                    || lower.contains("extreme-range") || lower.contains("from long range")
                    || lower.contains("another zip"))) {
                continue;
            }
            if (!close && (lower.contains("point blank") || lower.contains("up close")
                    || lower.contains("close range") || lower.contains("point-blank"))) {
                continue;
            }
            filtered.add(line);
        }
        return filtered;
    }

    private RangeBand rangeBand(double blocks) {
        if (blocks < catalog.rangePointBlank) {
            return RangeBand.POINT_BLANK;
        }
        if (blocks < catalog.rangeClose) {
            return RangeBand.CLOSE;
        }
        if (blocks < catalog.rangeLong) {
            return RangeBand.MID;
        }
        if (blocks < catalog.rangeExtreme) {
            return RangeBand.LONG;
        }
        return RangeBand.EXTREME;
    }

    private static boolean isSniper(Credit credit) {
        return gunFamily(credit) == GunFamily.SNIPER;
    }

    private static GunFamily gunFamily(Credit credit) {
        if (credit == null) {
            return GunFamily.RIFLE;
        }
        String cal = credit.ammoCaliber == null ? "" : credit.ammoCaliber.toLowerCase(Locale.ROOT);
        String id = credit.gunId == null ? "" : credit.gunId.toLowerCase(Locale.ROOT);
        if (credit.throwable) {
            return GunFamily.THROWABLE;
        }
        if (cal.equals("sniper") || cal.equals("heavy")
                || id.contains("barret") || id.contains("isolator") || id.contains("dragunov")
                || id.contains("l118") || id.equals("msr") || id.contains("awp") || id.contains("sniper")) {
            return GunFamily.SNIPER;
        }
        if (cal.equals("shotgun") || cal.equals("shot")
                || id.contains("spas") || id.contains("aa12") || id.contains("m1014")
                || id.contains("1887") || id.contains("shot")) {
            return GunFamily.SHOTGUN;
        }
        if (cal.equals("pistol") || cal.equals("handgun")
                || id.contains("deagle") || id.equals("m9") || id.contains("usp")
                || id.contains("python") || id.contains("magnum") || id.contains("executioner")) {
            return GunFamily.PISTOL;
        }
        if (cal.equals("rocket") || cal.equals("launcher")
                || id.contains("law") || id.contains("javelin") || id.contains("m79")
                || id.contains("rpg")) {
            return GunFamily.LAUNCHER;
        }
        if (cal.equals("energy") || cal.equals("plasma") || cal.equals("laser")
                || id.contains("ray") || id.contains("skull")) {
            return GunFamily.ENERGY;
        }
        if (cal.equals("arrow") || cal.equals("bolt") || id.contains("crossbow") || id.contains("bow")) {
            return GunFamily.BOW;
        }
        if (cal.equals("flare") || id.contains("flare")) {
            return GunFamily.FLARE;
        }
        if (id.contains("mac") || id.contains("ump") || id.contains("mp7") || id.contains("p90")
                || id.contains("pp90") || id.contains("pp-90") || id.contains("smg")) {
            return GunFamily.SMG;
        }
        return GunFamily.RIFLE;
    }

    private static String plainRoundName(RoundDefinition round) {
        if (round == null) {
            return "Round";
        }
        String raw = round.displayName() == null || round.displayName().isBlank()
                ? round.fileName()
                : round.displayName();
        if (raw == null || raw.isBlank()) {
            return round.fileName() != null ? round.fileName() : "Round";
        }
        String plain = PlainTextComponentSerializer.plainText().serialize(
                LegacyComponentSerializer.legacyAmpersand().deserialize(raw));
        plain = plain.replaceAll("§.", "").trim();
        return plain.isEmpty() ? round.fileName() : plain;
    }

    private static String plainGunName(GunDefinition gun) {
        String raw = gun.displayName() == null || gun.displayName().isBlank()
                ? gun.fileName()
                : gun.displayName();
        if (raw == null || raw.isBlank()) {
            return "Gun";
        }
        String plain = PlainTextComponentSerializer.plainText().serialize(
                LegacyComponentSerializer.legacyAmpersand().deserialize(raw));
        plain = plain.replaceAll("§.", "").trim();
        return plain.isEmpty() ? "Gun" : plain;
    }

    private enum Category {
        NORMAL, HEADSHOT, TRICKSHOT, REVENGE, STREAK, SUPPRESSED, WALLBANG,
        DRONE, BLEED, EXTREME, EXPLODE
    }

    private enum GunFamily {
        RIFLE, SNIPER, SHOTGUN, SMG, PISTOL, LAUNCHER, ENERGY, BOW, FLARE, THROWABLE
    }

    private record Pick(String template, boolean bold, Category category) {
    }

    private record KillerState(int streak, long lastKillMs, String lastTemplate) {
    }

    private record Credit(
            UUID killerId,
            String killerName,
            String gunId,
            String gunDisplayName,
            String ammoCaliber,
            HitKind kind,
            boolean throwable,
            boolean explosive,
            boolean incendiary,
            boolean aimed,
            double rangeBlocks,
            float fallDistance,
            boolean suppressed,
            boolean wallbang,
            boolean throughGlass,
            boolean ricochet,
            boolean fromDrone,
            boolean bleedOut,
            List<Component> attachmentHover,
            long atMs
    ) {
        Credit withBleedOut(boolean v) {
            return new Credit(killerId, killerName, gunId, gunDisplayName, ammoCaliber, kind,
                    throwable, explosive, incendiary, aimed, rangeBlocks, fallDistance,
                    suppressed, wallbang, throughGlass, ricochet, fromDrone, v, attachmentHover, atMs);
        }

        Credit withTime(long ms) {
            return new Credit(killerId, killerName, gunId, gunDisplayName, ammoCaliber, kind,
                    throwable, explosive, incendiary, aimed, rangeBlocks, fallDistance,
                    suppressed, wallbang, throughGlass, ricochet, fromDrone, bleedOut, attachmentHover, ms);
        }
    }
}
