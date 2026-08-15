package com.local.warz.gui;

import net.minenite.warzplugin.WarzPlugin;
import com.local.warz.config.AmmoCaliber;
import com.local.warz.config.GunWriter;
import com.local.warz.config.RoundWriter;
import com.local.warz.model.GunDefinition;
import com.local.warz.model.GunDraft;
import com.local.warz.model.RoundDefinition;
import com.local.warz.model.RoundDraft;
import com.local.warz.runtime.ItemFactory;
import com.local.warz.runtime.LaserCompanionBridge;
import com.local.warz.util.LaserBeams;
import com.local.warz.util.LaserOptics;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class GunEditorService {
    public enum Page {
        BROWSER, BASICS, FIRE, ACCURACY, EFFECTS, CLIP, FLAGS, SOUNDS, LASER_COLORS,
        ROUNDS, ROUND_LIBRARY, ROUND_EDIT
    }

    public enum Prompt {
        FILENAME, DISPLAY_NAME, AMMO_MATERIAL, BULLET_TYPE, RELOAD_TYPE,
        OUT_OF_AMMO, PERMISSION_MESSAGE, GUN_SOUND_ADD, NUMBER,
        REMNANT_MATERIAL, REMNANT_NAME, LASER_COLOR, MUZZLE_COLOR, ROUND_COLOR,
        ROUND_FILENAME, ROUND_DISPLAY_NAME
    }

    public static final class Session {
        public Page page = Page.BROWSER;
        public GunDraft draft;
        public boolean creating;
        public Prompt prompt;
        public String numberField;
        public int browserPage;
        public Inventory openInventory;
        public RoundDraft roundDraft;
        public boolean creatingRound;
        public String roundColorField;
        public int roundBrowserPage;
    }

    public static final class Holder implements InventoryHolder {
        private final UUID playerId;
        private Inventory inventory;

        public Holder(UUID playerId) {
            this.playerId = playerId;
        }

        public UUID playerId() {
            return playerId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }
    }

    private final WarzPlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public GunEditorService(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public Session session(Player player) {
        return sessions.computeIfAbsent(player.getUniqueId(), id -> new Session());
    }

    public void clear(Player player) {
        sessions.remove(player.getUniqueId());
    }

    public boolean hasPrompt(Player player) {
        Session session = sessions.get(player.getUniqueId());
        return session != null && session.prompt != null;
    }

    public void openBrowser(Player player) {
        Session session = session(player);
        session.page = Page.BROWSER;
        session.draft = null;
        session.prompt = null;
        render(player);
    }

    public void openCreate(Player player) {
        Session session = session(player);
        session.creating = true;
        session.draft = new GunDraft();
        session.page = Page.BASICS;
        session.prompt = null;
        render(player);
    }

    public void openEdit(Player player, GunDefinition def) {
        Session session = session(player);
        session.creating = false;
        boolean throwable = def.throwable();
        session.draft = GunDraft.from(def, throwable);
        session.page = Page.BASICS;
        session.prompt = null;
        render(player);
    }

    public void render(Player player) {
        Session session = session(player);
        Holder holder = new Holder(player.getUniqueId());
        Inventory inv;
        if (session.page == Page.BROWSER) {
            inv = ChestInventories.create(holder, 54, Component.text("WarZ — Guns", NamedTextColor.DARK_PURPLE));
            holder.setInventory(inv);
            fillBrowser(inv, session);
        } else if (session.page == Page.LASER_COLORS) {
            inv = ChestInventories.create(holder, 54, Component.text("Laser Color — pick a dye", NamedTextColor.RED));
            holder.setInventory(inv);
            fillEditor(inv, session);
        } else if (session.page == Page.ROUND_LIBRARY) {
            inv = ChestInventories.create(holder, 54, Component.text("Round Library", NamedTextColor.GOLD));
            holder.setInventory(inv);
            fillRoundLibrary(inv, session);
        } else if (session.page == Page.ROUND_EDIT) {
            inv = ChestInventories.create(holder, 54, Component.text("Edit Round: "
                    + (session.roundDraft == null ? "?" : session.roundDraft.fileName), NamedTextColor.GOLD));
            holder.setInventory(inv);
            fillRoundEdit(inv, session);
        } else {
            inv = ChestInventories.create(holder, 54, Component.text("Edit: " + session.draft.fileName, NamedTextColor.DARK_GREEN));
            holder.setInventory(inv);
            fillEditor(inv, session);
        }
        session.openInventory = inv;
        player.openInventory(inv);
    }

    private void fillBrowser(Inventory inv, Session session) {
        List<GunDefinition> guns = plugin.registry().all().stream()
                .sorted(Comparator.comparing(GunDefinition::fileName))
                .toList();
        int perPage = 45;
        int maxPage = Math.max(0, (guns.size() - 1) / perPage);
        session.browserPage = Math.min(session.browserPage, maxPage);
        int start = session.browserPage * perPage;
        for (int i = 0; i < perPage && start + i < guns.size(); i++) {
            GunDefinition gun = guns.get(start + i);
            ItemStack icon = plugin.items().create(gun, 1);
            ItemMeta meta = icon.getItemMeta();
            List<Component> lore = new ArrayList<>();
            lore.add(text("&7Click to edit"));
            lore.add(text("&8id: &f" + gun.fileName()));
            lore.add(text("&8cmd: &f" + gun.customModelData()));
            lore.add(text(gun.consumable()
                    ? "&8ammo: &fconsumable (self)"
                    : "&8ammo: &f" + gun.ammoMaterial() + " x" + gun.ammoAmtNeeded()));
            meta.lore(lore);
            icon.setItemMeta(meta);
            inv.setItem(i, icon);
        }
        inv.setItem(45, button(Material.ARROW, "&ePrevious Page", "&7Page " + (session.browserPage + 1)));
        inv.setItem(49, button(Material.EMERALD_BLOCK, "&aCreate New Gun", "&7Opens a blank editor"));
        inv.setItem(53, button(Material.ARROW, "&eNext Page", "&7Page " + (session.browserPage + 1)));
    }

    private void fillEditor(Inventory inv, Session session) {
        GunDraft d = session.draft;
        if (session.page == Page.LASER_COLORS) {
            renderLaserColorPalette(inv, d);
            inv.setItem(45, button(Material.ARROW, "&eBack to Flags", "&7Return without changing"));
            inv.setItem(49, button(dyeIconFor(d.laserSightColor), "&aCurrent Color",
                    "&f" + d.laserSightColor, "&7Selected laser color"));
            inv.setItem(53, button(Material.NAME_TAG, "&eCustom Hex…",
                    "&7Click then type in chat", "&8Examples: #FF2020  or  255,32,32"));
            return;
        }
        // Nav bar bottom
        inv.setItem(45, button(Material.BOOK, "&eBasics", pageLore(Page.BASICS, session.page)));
        inv.setItem(46, button(Material.CROSSBOW, "&eFire", pageLore(Page.FIRE, session.page)));
        inv.setItem(47, button(Material.TARGET, "&eAccuracy", pageLore(Page.ACCURACY, session.page)));
        inv.setItem(48, button(Material.TNT, "&eEffects", pageLore(Page.EFFECTS, session.page)));
        inv.setItem(49, button(Material.CHEST, "&eClip/Reload", pageLore(Page.CLIP, session.page)));
        inv.setItem(50, button(Material.LEVER, "&eFlags", pageLore(Page.FLAGS, session.page)));
        inv.setItem(51, button(Material.NOTE_BLOCK, "&eSounds", pageLore(Page.SOUNDS, session.page)));
        inv.setItem(52, button(Material.FIREWORK_STAR, "&eRounds/Ammo", pageLore(Page.ROUNDS, session.page)));
        inv.setItem(53, button(Material.LIME_CONCRETE, "&aSave Gun", "&7Writes config and reloads"));
        inv.setItem(44, button(Material.BARRIER, "&cBack to List", "&7Discard unsaved? Save first"));

        switch (session.page) {
            case BASICS -> {
                set(inv, 10, Material.NAME_TAG, "&bFile Name", "&f" + d.fileName, "&7Click: chat rename file id");
                set(inv, 11, Material.PAPER, "&bDisplay Name", "&f" + d.displayName, "&7Click: chat set display");
                set(inv, 12, Material.CLAY_BALL, "&bAmmo Material",
                        d.consumable ? "&7Ignored while consumable" : "&f" + d.ammoMaterial,
                        "&7Click: chat material name");
                set(inv, 13, Material.IRON_NUGGET, "&bAmmo Amt Needed", num(d.ammoAmtNeeded), tips(),
                        d.consumable ? "&8How many of this gun item to consume" : "");
                set(inv, 14, Material.IRON_SWORD, "&bGun Damage", num(d.gunDamage), tips());
                set(inv, 15, Material.SHIELD, "&bArmor Penetration", num(d.armorPenetration), tips());
                set(inv, 16, Material.OAK_SIGN, "&bOut Of Ammo Msg", "&f" + d.outOfAmmoMessage, "&7Click: chat message");
                set(inv, 19, Material.WRITABLE_BOOK, "&bPermission Message",
                        "&f" + (d.permissionMessage.isBlank() ? "(none)" : d.permissionMessage), "&7Click: chat message");
            }
            case FIRE -> {
                set(inv, 10, Material.REPEATER, "&bRounds Per Burst", num(d.roundsPerBurst), tips());
                set(inv, 11, Material.FIREWORK_STAR, "&bBullets Per Click", num(d.bulletsPerClick), tips());
                set(inv, 12, Material.CLOCK, "&bBullet Delay Time", num(d.bulletDelayTime), tips());
                set(inv, 13, Material.FEATHER, "&bBullet Speed", num(d.bulletSpeed), tips());
                set(inv, 14, Material.ENDER_PEARL, "&bMax Distance", num(d.maxDistance), tips());
                set(inv, 15, Material.COMPASS, "&bCan Go Past Max Dist", bool(d.canGoPastMaxDistance), "&7Click toggle");
                set(inv, 16, Material.SNOWBALL, "&bBullet Type",
                        "&f" + (d.bulletType.isBlank() ? "(default snowball)" : d.bulletType),
                        "&7Click: chat (laser, wither, smallfireball, crossbow...)");
                set(inv, 19, Material.HOPPER, "&bTime Until Release", num(d.releaseTime), tips(), "&8-1 = default");
                set(inv, 20, Material.PHANTOM_MEMBRANE, "&bFall Speed", num(d.fallSpeed), tips(),
                        "&70 = no gravity (normal guns)", "&7~0.01 = slow parachute (flares)");
            }
            case ACCURACY -> {
                set(inv, 10, Material.SPYGLASS, "&bAccuracy", num(d.accuracy), tips());
                set(inv, 11, Material.SPYGLASS, "&bAccuracy Aimed", num(d.accuracyAimed), tips());
                set(inv, 12, Material.SPYGLASS, "&bAccuracy Crouched", num(d.accuracyCrouched), tips());
                set(inv, 13, Material.PISTON, "&bRecoil (knockback)", num(d.recoil), tips());
                set(inv, 14, Material.ENDER_EYE, "&bRecoil Pitch (camera)", num(d.recoilPitch), tips());
                set(inv, 15, Material.SLIME_BALL, "&bKnockback", num(d.knockback), tips());
                set(inv, 16, Material.NOTE_BLOCK, "&bGun Volume", num(d.gunVolume), tips());
                set(inv, 19, Material.JUKEBOX, "&bLocal Gun Sound", bool(d.localGunSound), "&7Click toggle");
            }
            case EFFECTS -> {
                set(inv, 10, Material.TNT, "&bExplode Radius", num(d.explodeRadius), tips());
                set(inv, 11, Material.FIRE_CHARGE, "&bExplosion Damage", num(d.explosionDamage), tips(), "&8-1 = use gunDamage");
                set(inv, 12, Material.FLINT_AND_STEEL, "&bFire Radius", num(d.fireRadius), tips());
                set(inv, 13, Material.GLOWSTONE_DUST, "&bFlash Radius", num(d.flashRadius), tips());
                set(inv, 14, Material.CAMPFIRE, "&bHas Smoke Trail", bool(d.hasSmokeTrail),
                        "&7Always leave smoke particles", "&7(even without tracer rounds)", "&8Click toggle");
                set(inv, 15, Material.FIREWORK_ROCKET, "&bDestroy Bullet When Hit", bool(d.destroyBulletWhenHit), "&7Click toggle");
                set(inv, 16, Material.BLAZE_POWDER, "&bMuzzle Flash (gun default)", bool(d.muzzleFlash),
                        "&7Round can override per ammo type", "&8Click toggle");
                set(inv, 25, dyeIconFor(d.muzzleColor), "&bMuzzle Color",
                        "&f" + d.muzzleColor, "&7Click: type #RRGGBB in chat");
                set(inv, 26, Material.MAGMA_CREAM, "&bMuzzle Scale", num(d.muzzleScale), tips());
                Material remnantMat = d.remnantItem == null || d.remnantItem.isAir() ? Material.BARRIER : d.remnantItem;
                set(inv, 19, remnantMat, "&bRemnant Item",
                        d.remnantItem == null || d.remnantItem.isAir() ? "&cNONE (no leftover)" : "&f" + d.remnantItem.name(),
                        "&7After fuse/detonate, flying item vanishes",
                        "&7and this drops for players to pick up.",
                        "&7Click: chat material (or NONE)");
                set(inv, 20, Material.NAME_TAG, "&bRemnant Name",
                        d.remnantName == null || d.remnantName.isBlank() ? "&8(default item name)" : "&f" + d.remnantName,
                        "&7Click: chat display name");
                set(inv, 21, Material.IRON_NUGGET, "&bRemnant Amount", num(Math.max(1, d.remnantAmount)), tips());
                set(inv, 22, Material.CLOCK, "&bRemnant Pickup Delay", num(d.remnantPickupDelay), tips(), "&8Ticks before pickup");
                set(inv, 23, Material.STRUCTURE_VOID, "&bRemnant Lifetime", num(d.remnantLifetime), tips(),
                        "&8Ticks until despawn; -1 = vanilla");
            }
            case ROUNDS -> {
                if (d.ammoCaliber == null || d.ammoCaliber.isBlank()) {
                    d.ammoCaliber = AmmoCaliber.fromMaterial(d.ammoMaterial);
                }
                if (d.allowedRounds == null) {
                    d.allowedRounds = new ArrayList<>();
                }
                d.ammoMaterial = AmmoCaliber.defaultMaterial(d.ammoCaliber);
                set(inv, 10, Material.HOPPER, "&bAmmo Caliber", "&f" + d.ammoCaliber,
                        "&7Base item: &f" + d.ammoMaterial.name(),
                        "&8Click: cycle caliber");
                set(inv, 11, Material.BOOKSHELF, "&eOpen Round Library",
                        "&7Create/edit global round types",
                        "&7(FMJ, Tracer, HP, AP, …)");
                set(inv, 12, Material.CHEST, "&aGive Primary Round x64",
                        "&7Gives &f" + AmmoCaliber.primaryRound(d.ammoCaliber),
                        "&8Must be tagged — plain materials won't fire");
                set(inv, 13, Material.ENDER_CHEST, "&aGive All Allowed x16",
                        "&7One stack of each allowed round");
                set(inv, 14, Material.EMERALD, "&bAllow All Of Caliber",
                        "&7Resets allowed list to caliber defaults");
                set(inv, 15, Material.BARRIER, "&cClear Allowed Rounds",
                        "&7Empty = all rounds of this caliber on load");
                int slot = 19;
                for (RoundDefinition round : plugin.rounds().byCaliber(d.ammoCaliber)) {
                    if (slot >= 44) {
                        break;
                    }
                    boolean allowed = d.allowedRounds.isEmpty() || d.allowedRounds.contains(round.fileName());
                    set(inv, slot, round.material(),
                            (allowed ? "&a✔ " : "&8✘ ") + round.displayName(),
                            "&7id: &f" + round.fileName(),
                            "&7dmg x&f" + String.format("%.2f", round.damageMult())
                                    + (round.tracer() ? " &aTRACER" : "")
                                    + (round.subsonic() ? " &8SUB" : ""),
                            allowed ? "&aALLOWED &8· click to remove" : "&cblocked &8· click to allow");
                    slot++;
                }
            }
            case ROUND_LIBRARY, ROUND_EDIT -> {
                // handled in dedicated render branches
            }
            case CLIP -> {
                set(inv, 10, Material.CHEST, "&bHas Clip", bool(d.hasClip), "&7Click toggle");
                set(inv, 11, Material.GOLD_NUGGET, "&bMax Clip Size", num(d.maxClipSize), tips());
                set(inv, 12, Material.CLOCK, "&bReload Time", num(d.reloadTime), tips());
                set(inv, 13, Material.TRIPWIRE_HOOK, "&bReload Type", "&f" + d.reloadType,
                        "&7Click cycle: NORMAL / BOLT / PUMP / INDIVIDUAL_BULLET");
                set(inv, 14, Material.DROPPER, "&bReload Gun On Drop", bool(d.reloadGunOnDrop), "&7Click toggle");
            }
            case FLAGS -> {
                set(inv, 10, Material.POTION, "&bCan Aim", bool(d.canAim), "&7Click toggle");
                set(inv, 11, Material.WOODEN_SWORD, "&bCan Click Left", bool(d.canClickLeft), "&7Click toggle");
                set(inv, 12, Material.IRON_SWORD, "&bCan Click Right", bool(d.canClickRight), "&7Click toggle");
                set(inv, 13, Material.SKELETON_SKULL, "&bCan Headshot", bool(d.canHeadshot), "&7Click toggle");
                set(inv, 14, Material.LIGHTNING_ROD, "&bReset Hit Cooldown", bool(d.resetHitCooldown), "&7Click toggle");
                set(inv, 15, Material.NAME_TAG, "&bNeeds Permission", bool(d.needsPermission), "&7Click toggle");
                set(inv, 16, Material.SNOWBALL, "&bThrowable (projectile folder)", bool(d.throwable), "&7Click toggle");
                set(inv, 19, Material.FIRE_CHARGE, "&bConsumable (uses itself)", bool(d.consumable),
                        "&7When true, throwing/firing consumes", "&7this gun item — no separate ammo.",
                        "&8Click toggle");
                set(inv, 20, Material.REDSTONE_TORCH, "&bLaser Sight (pointer)", bool(d.laserSight),
                        "&7Visual only — does NOT damage.",
                        d.laserSight
                                ? "&aON &7· color &f" + d.laserSightColor + " &7· size &f" + String.format("%.2f", d.laserSightSize)
                                : "&8Off",
                        "&8Click toggle");
                set(inv, 21, Material.SPYGLASS, "&bAim-Only Laser &e(ADS required)", bool(d.laserSightAimOnly),
                        "&cIf ON: laser shows ONLY while aiming",
                        "&c(right-click / ADS) — hip-fire = invisible.",
                        "&aIf OFF: laser shows whenever the gun is held.",
                        "&8Click toggle");
                set(inv, 22, dyeIconFor(d.laserSightColor), "&bLaser Color",
                        "&f" + d.laserSightColor,
                        d.laserSightIr ? "&aIR mode &7— color forced to phosphor green" : "&7Live swatch of the beam color",
                        "&7Click: open dye palette");
                set(inv, 34, Material.ENDER_EYE, "&bIR Laser (NVG only)", bool(d.laserSightIr),
                        "&7Invisible without Quad NODs",
                        "&aON &7= only NVG wearers see the beam",
                        "&8Click toggle");
                set(inv, 23, Material.ENDER_PEARL, "&bLaser Range", num(d.laserSightRange), tips(),
                        "&8-1 = use gun maxDistance");
                set(inv, 24, Material.FEATHER, "&bLaser Size", num(d.laserSightSize), tips(),
                        "&7Companion draws a hairline; this nudges thickness.",
                        "&8Vanilla dust fallback uses this more directly.");
                set(inv, 25, Material.ARROW, "&bLaser Offset Right", num(d.laserSightOffsetRight), tips(),
                        "&7+ = toward gun hand side");
                set(inv, 28, Material.LADDER, "&bLaser Offset Up", num(d.laserSightOffsetUp), tips(),
                        "&7- = down toward hand/gun");
                set(inv, 29, Material.PISTON, "&bLaser Offset Forward", num(d.laserSightOffsetForward), tips(),
                        "&7+ = out toward muzzle");
                set(inv, 30, Material.BLAZE_POWDER, "&bLaser Density", num(d.laserSightDensity), tips(),
                        "&7Affects vanilla particle fallback density");
                set(inv, 31, Material.GLOWSTONE_DUST, "&bLaser Glow (real light)", bool(d.laserSightGlow),
                        "&7Places temporary LIGHT blocks", "&7along the beam — actual world light.",
                        "&8Click toggle");
                set(inv, 32, Material.GLOW_INK_SAC, "&bLaser Light Level", num(d.laserSightGlowStrength), tips(),
                        "&70–1 maps to light level 1–12");
                set(inv, 33, Material.ENDER_EYE, "&ePreview Laser (3s)",
                        "&7Fires a short visual preview",
                        "&7using this draft's laser settings.",
                        "&8Click while looking where you want the beam");
            }
            case LASER_COLORS -> {
                // filled earlier
            }
            case SOUNDS -> {
                set(inv, 10, Material.NOTE_BLOCK, "&bAdd Sound", "&7Click then type sound in chat",
                        "&8Examples: ghast fireball, explode, wither shoot");
                set(inv, 11, Material.BARRIER, "&cClear Sounds", "&7Removes all configured sounds");
                int slot = 19;
                for (String sound : d.gunSounds) {
                    if (slot >= 44) {
                        break;
                    }
                    set(inv, slot++, Material.MUSIC_DISC_13, "&e" + sound, "&7Left: keep", "&cRight: remove this sound");
                }
            }
            default -> {
            }
        }
    }

    private String[] pageLore(Page target, Page current) {
        return new String[]{
                current == target ? "&aCurrently open" : "&7Click to open",
                "&8Page: " + target.name()
        };
    }

    public void handleClick(Player player, int slot, boolean left, boolean right, boolean shift) {
        Session session = session(player);
        if (session.page == Page.BROWSER) {
            handleBrowserClick(player, session, slot, left);
            return;
        }
        if (session.page == Page.LASER_COLORS) {
            handleLaserColorClick(player, session, slot);
            return;
        }
        if (session.page == Page.ROUND_LIBRARY) {
            handleRoundLibraryClick(player, session, slot, left);
            return;
        }
        if (session.page == Page.ROUND_EDIT) {
            handleRoundEditClick(player, session, slot, left, right, shift);
            return;
        }
        if (slot == 44 || (slot >= 45 && slot <= 53)) {
            handleNav(player, session, slot);
            return;
        }
        GunDraft d = session.draft;
        if (d == null) {
            return;
        }
        switch (session.page) {
            case BASICS -> {
                if (slot == 10) ask(player, Prompt.FILENAME, null);
                else if (slot == 11) ask(player, Prompt.DISPLAY_NAME, null);
                else if (slot == 12) ask(player, Prompt.AMMO_MATERIAL, null);
                else if (slot == 13) adjustInt(player, "ammoAmtNeeded", v -> d.ammoAmtNeeded = clampInt(v, 0, 64), d.ammoAmtNeeded, left, right, shift);
                else if (slot == 14) adjustInt(player, "gunDamage", v -> d.gunDamage = clampInt(v, 0, 1000), d.gunDamage, left, right, shift);
                else if (slot == 15) adjustInt(player, "armorPenetration", v -> d.armorPenetration = clampInt(v, 0, 1000), d.armorPenetration, left, right, shift);
                else if (slot == 16) ask(player, Prompt.OUT_OF_AMMO, null);
                else if (slot == 19) ask(player, Prompt.PERMISSION_MESSAGE, null);
            }
            case FIRE -> {
                if (slot == 10) adjustInt(player, "roundsPerBurst", v -> d.roundsPerBurst = clampInt(v, 1, 50), d.roundsPerBurst, left, right, shift);
                else if (slot == 11) adjustInt(player, "bulletsPerClick", v -> d.bulletsPerClick = clampInt(v, 1, 64), d.bulletsPerClick, left, right, shift);
                else if (slot == 12) adjustInt(player, "bulletDelayTime", v -> d.bulletDelayTime = clampInt(v, 0, 200), d.bulletDelayTime, left, right, shift);
                else if (slot == 13) adjustDouble(player, "bulletSpeed", v -> d.bulletSpeed = clampDouble(v, 0.1, 20), d.bulletSpeed, left, right, shift, 0.1, 1.0);
                else if (slot == 20) adjustDouble(player, "fallSpeed", v -> d.fallSpeed = clampDouble(v, 0.0, 0.2), d.fallSpeed, left, right, shift, 0.002, 0.01);
                else if (slot == 14) adjustInt(player, "maxDistance", v -> d.maxDistance = clampInt(v, 0, 1000), d.maxDistance, left, right, shift);
                else if (slot == 15) { d.canGoPastMaxDistance = !d.canGoPastMaxDistance; render(player); }
                else if (slot == 16) ask(player, Prompt.BULLET_TYPE, null);
                else if (slot == 19) adjustInt(player, "releaseTime", v -> d.releaseTime = clampInt(v, -1, 1000), d.releaseTime, left, right, shift);
            }
            case ACCURACY -> {
                if (slot == 10) adjustDouble(player, "accuracy", v -> d.accuracy = clampDouble(v, 0, 5), d.accuracy, left, right, shift, 0.01, 0.1);
                else if (slot == 11) adjustDouble(player, "accuracyAimed", v -> d.accuracyAimed = clampDouble(v, 0, 5), d.accuracyAimed, left, right, shift, 0.01, 0.1);
                else if (slot == 12) adjustDouble(player, "accuracyCrouched", v -> d.accuracyCrouched = clampDouble(v, 0, 5), d.accuracyCrouched, left, right, shift, 0.01, 0.1);
                else if (slot == 13) adjustDouble(player, "recoil", v -> d.recoil = clampDouble(v, 0, 20), d.recoil, left, right, shift, 0.05, 0.5);
                else if (slot == 14) adjustDouble(player, "recoilPitch", v -> d.recoilPitch = clampDouble(v, 0, 90), d.recoilPitch, left, right, shift, 0.5, 2.0);
                else if (slot == 15) adjustDouble(player, "knockback", v -> d.knockback = clampDouble(v, 0, 50), d.knockback, left, right, shift, 0.1, 1.0);
                else if (slot == 16) adjustDouble(player, "gunVolume", v -> d.gunVolume = clampDouble(v, 0, 20), d.gunVolume, left, right, shift, 0.1, 1.0);
                else if (slot == 19) { d.localGunSound = !d.localGunSound; render(player); }
            }
            case EFFECTS -> {
                if (slot == 10) adjustDouble(player, "explodeRadius", v -> d.explodeRadius = clampDouble(v, 0, 32), d.explodeRadius, left, right, shift, 0.5, 1.0);
                else if (slot == 11) adjustInt(player, "explosionDamage", v -> d.explosionDamage = clampInt(v, -1, 1000), d.explosionDamage, left, right, shift);
                else if (slot == 12) adjustDouble(player, "fireRadius", v -> d.fireRadius = clampDouble(v, 0, 32), d.fireRadius, left, right, shift, 0.5, 1.0);
                else if (slot == 13) adjustDouble(player, "flashRadius", v -> d.flashRadius = clampDouble(v, 0, 32), d.flashRadius, left, right, shift, 0.5, 1.0);
                else if (slot == 14) { d.hasSmokeTrail = !d.hasSmokeTrail; render(player); }
                else if (slot == 15) { d.destroyBulletWhenHit = !d.destroyBulletWhenHit; render(player); }
                else if (slot == 16) { d.muzzleFlash = !d.muzzleFlash; render(player); }
                else if (slot == 25) ask(player, Prompt.MUZZLE_COLOR, null);
                else if (slot == 26) adjustDouble(player, "muzzleScale", v -> d.muzzleScale = (float) clampDouble(v, 0.2, 3.0), d.muzzleScale, left, right, shift, 0.05, 0.2);
                else if (slot == 19) ask(player, Prompt.REMNANT_MATERIAL, null);
                else if (slot == 20) ask(player, Prompt.REMNANT_NAME, null);
                else if (slot == 21) adjustInt(player, "remnantAmount", v -> d.remnantAmount = clampInt(v, 1, 64), Math.max(1, d.remnantAmount), left, right, shift);
                else if (slot == 22) adjustInt(player, "remnantPickupDelay", v -> d.remnantPickupDelay = clampInt(v, 0, 1200), d.remnantPickupDelay, left, right, shift);
                else if (slot == 23) adjustInt(player, "remnantLifetime", v -> d.remnantLifetime = clampInt(v, -1, 12000), d.remnantLifetime, left, right, shift);
            }
            case ROUNDS -> handleRoundsClick(player, session, d, slot);
            case CLIP -> {
                if (slot == 10) { d.hasClip = !d.hasClip; render(player); }
                else if (slot == 11) adjustInt(player, "maxClipSize", v -> d.maxClipSize = clampInt(v, 1, 5000), d.maxClipSize, left, right, shift);
                else if (slot == 12) adjustInt(player, "reloadTime", v -> d.reloadTime = clampInt(v, 1, 500), d.reloadTime, left, right, shift);
                else if (slot == 13) {
                    d.reloadType = cycleReload(d.reloadType);
                    render(player);
                } else if (slot == 14) { d.reloadGunOnDrop = !d.reloadGunOnDrop; render(player); }
            }
            case FLAGS -> {
                if (slot == 10) { d.canAim = !d.canAim; render(player); }
                else if (slot == 11) { d.canClickLeft = !d.canClickLeft; render(player); }
                else if (slot == 12) { d.canClickRight = !d.canClickRight; render(player); }
                else if (slot == 13) { d.canHeadshot = !d.canHeadshot; render(player); }
                else if (slot == 14) { d.resetHitCooldown = !d.resetHitCooldown; render(player); }
                else if (slot == 15) { d.needsPermission = !d.needsPermission; render(player); }
                else if (slot == 16) { d.throwable = !d.throwable; render(player); }
                else if (slot == 19) { d.consumable = !d.consumable; render(player); }
                else if (slot == 20) { d.laserSight = !d.laserSight; render(player); }
                else if (slot == 21) { d.laserSightAimOnly = !d.laserSightAimOnly; render(player); }
                else if (slot == 22) { session.page = Page.LASER_COLORS; render(player); }
                else if (slot == 23) adjustDouble(player, "laserSightRange", v -> d.laserSightRange = clampDouble(v, -1, 500), d.laserSightRange, left, right, shift, 1.0, 5.0);
                else if (slot == 24) adjustDouble(player, "laserSightSize", v -> d.laserSightSize = (float) clampDouble(v, 0.05, 2.0), d.laserSightSize, left, right, shift, 0.02, 0.1);
                else if (slot == 25) adjustDouble(player, "laserSightOffsetRight", v -> d.laserSightOffsetRight = clampDouble(v, -2, 2), d.laserSightOffsetRight, left, right, shift, 0.02, 0.1);
                else if (slot == 28) adjustDouble(player, "laserSightOffsetUp", v -> d.laserSightOffsetUp = clampDouble(v, -2, 2), d.laserSightOffsetUp, left, right, shift, 0.02, 0.1);
                else if (slot == 29) adjustDouble(player, "laserSightOffsetForward", v -> d.laserSightOffsetForward = clampDouble(v, -2, 2), d.laserSightOffsetForward, left, right, shift, 0.02, 0.1);
                else if (slot == 30) adjustDouble(player, "laserSightDensity", v -> d.laserSightDensity = clampDouble(v, 0.1, 5), d.laserSightDensity, left, right, shift, 0.05, 0.25);
                else if (slot == 31) { d.laserSightGlow = !d.laserSightGlow; render(player); }
                else if (slot == 32) adjustDouble(player, "laserSightGlowStrength", v -> d.laserSightGlowStrength = clampDouble(v, 0, 1), d.laserSightGlowStrength, left, right, shift, 0.05, 0.15);
                else if (slot == 33) previewLaser(player, d);
                else if (slot == 34) {
                    d.laserSightIr = !d.laserSightIr;
                    if (d.laserSightIr) {
                        d.laserSight = true;
                        d.laserSightGlow = false;
                        d.laserSightColor = "#2AFF4A";
                    }
                    render(player);
                }
            }
            case SOUNDS -> {
                if (slot == 10) ask(player, Prompt.GUN_SOUND_ADD, null);
                else if (slot == 11) {
                    d.gunSounds.clear();
                    render(player);
                } else if (slot >= 19 && right) {
                    int index = slot - 19;
                    if (index >= 0 && index < d.gunSounds.size()) {
                        d.gunSounds.remove(index);
                        render(player);
                    }
                }
            }
            default -> {
            }
        }
    }

    private void handleLaserColorClick(Player player, Session session, int slot) {
        GunDraft d = session.draft;
        if (slot == 45) {
            session.page = Page.FLAGS;
            render(player);
            return;
        }
        if (slot == 53) {
            ask(player, Prompt.LASER_COLOR, null);
            return;
        }
        if (slot >= 10 && slot < 10 + LASER_DYES.length) {
            DyeSwatch swatch = LASER_DYES[slot - 10];
            d.laserSightColor = swatch.hex();
            player.sendMessage(Component.text("Laser color set to " + swatch.name() + " (" + swatch.hex() + ")", NamedTextColor.GREEN));
            session.page = Page.FLAGS;
            render(player);
        }
    }

    private record DyeSwatch(Material dye, String name, String hex) {
    }

    private static final DyeSwatch[] LASER_DYES = {
            new DyeSwatch(Material.WHITE_DYE, "White", "#F9FFFE"),
            new DyeSwatch(Material.LIGHT_GRAY_DYE, "Light Gray", "#9D9D97"),
            new DyeSwatch(Material.GRAY_DYE, "Gray", "#474F52"),
            new DyeSwatch(Material.BLACK_DYE, "Black", "#1D1D21"),
            new DyeSwatch(Material.BROWN_DYE, "Brown", "#835432"),
            new DyeSwatch(Material.RED_DYE, "Red", "#FF2020"),
            new DyeSwatch(Material.ORANGE_DYE, "Orange", "#F9801D"),
            new DyeSwatch(Material.YELLOW_DYE, "Yellow", "#FED83D"),
            new DyeSwatch(Material.LIME_DYE, "Lime", "#80C71F"),
            new DyeSwatch(Material.GREEN_DYE, "Green", "#5E7C16"),
            new DyeSwatch(Material.CYAN_DYE, "Cyan", "#169C9C"),
            new DyeSwatch(Material.LIGHT_BLUE_DYE, "Light Blue", "#3AB3DA"),
            new DyeSwatch(Material.BLUE_DYE, "Blue", "#3C44AA"),
            new DyeSwatch(Material.PURPLE_DYE, "Purple", "#8932B8"),
            new DyeSwatch(Material.MAGENTA_DYE, "Magenta", "#C74EBD"),
            new DyeSwatch(Material.PINK_DYE, "Pink", "#F38BAA")
    };

    private static void renderLaserColorPalette(Inventory inv, GunDraft d) {
        set(inv, 4, dyeIconFor(d.laserSightColor), "&ePick a dye color",
                "&7Current: &f" + d.laserSightColor,
                "&8Click any dye below");
        for (int i = 0; i < LASER_DYES.length; i++) {
            DyeSwatch swatch = LASER_DYES[i];
            boolean selected = swatch.hex().equalsIgnoreCase(normalizeHex(d.laserSightColor));
            set(inv, 10 + i, swatch.dye(),
                    (selected ? "&a✔ " : "&b") + swatch.name(),
                    "&f" + swatch.hex(),
                    selected ? "&aCurrently selected" : "&7Click to use this color");
        }
    }

    private static Material dyeIconFor(String colorConfig) {
        String hex = normalizeHex(colorConfig);
        for (DyeSwatch swatch : LASER_DYES) {
            if (swatch.hex().equalsIgnoreCase(hex)) {
                return swatch.dye();
            }
        }
        // Approximate from hex channels
        try {
            if (hex.startsWith("#") && hex.length() >= 7) {
                int r = Integer.parseInt(hex.substring(1, 3), 16);
                int g = Integer.parseInt(hex.substring(3, 5), 16);
                int b = Integer.parseInt(hex.substring(5, 7), 16);
                DyeSwatch best = LASER_DYES[5]; // red default
                long bestDist = Long.MAX_VALUE;
                for (DyeSwatch swatch : LASER_DYES) {
                    int sr = Integer.parseInt(swatch.hex().substring(1, 3), 16);
                    int sg = Integer.parseInt(swatch.hex().substring(3, 5), 16);
                    int sb = Integer.parseInt(swatch.hex().substring(5, 7), 16);
                    long dist = (long) (r - sr) * (r - sr) + (long) (g - sg) * (g - sg) + (long) (b - sb) * (b - sb);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = swatch;
                    }
                }
                return best.dye();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return Material.RED_DYE;
    }

    private static String normalizeHex(String raw) {
        if (raw == null || raw.isBlank()) {
            return "#FF2020";
        }
        String value = raw.trim();
        if (value.startsWith("#")) {
            return value.toUpperCase(Locale.ROOT);
        }
        // named colors from LaserBeams chat aliases -> keep as-is for display; map common ones
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "red" -> "#FF2020";
            case "orange" -> "#F9801D";
            case "yellow" -> "#FED83D";
            case "lime", "green" -> "#80C71F";
            case "cyan", "aqua" -> "#169C9C";
            case "blue" -> "#3C44AA";
            case "purple", "magenta", "fuchsia" -> "#8932B8";
            case "pink" -> "#F38BAA";
            case "white" -> "#F9FFFE";
            case "black" -> "#1D1D21";
            case "gray", "grey" -> "#474F52";
            default -> value;
        };
    }

    private void handleBrowserClick(Player player, Session session, int slot, boolean left) {
        if (slot == 45) {
            session.browserPage = Math.max(0, session.browserPage - 1);
            render(player);
            return;
        }
        if (slot == 53) {
            session.browserPage++;
            render(player);
            return;
        }
        if (slot == 49) {
            openCreate(player);
            return;
        }
        if (slot < 0 || slot >= 45) {
            return;
        }
        List<GunDefinition> guns = plugin.registry().all().stream()
                .sorted(Comparator.comparing(GunDefinition::fileName))
                .toList();
        int index = session.browserPage * 45 + slot;
        if (index < guns.size() && left) {
            openEdit(player, guns.get(index));
        }
    }

    private void handleNav(Player player, Session session, int slot) {
        if (session.page == Page.ROUND_LIBRARY || session.page == Page.ROUND_EDIT) {
            return;
        }
        switch (slot) {
            case 44 -> openBrowser(player);
            case 45 -> { session.page = Page.BASICS; render(player); }
            case 46 -> { session.page = Page.FIRE; render(player); }
            case 47 -> { session.page = Page.ACCURACY; render(player); }
            case 48 -> { session.page = Page.EFFECTS; render(player); }
            case 49 -> { session.page = Page.CLIP; render(player); }
            case 50 -> { session.page = Page.FLAGS; render(player); }
            case 51 -> { session.page = Page.SOUNDS; render(player); }
            case 52 -> { session.page = Page.ROUNDS; render(player); }
            case 53 -> save(player, session);
            default -> {
            }
        }
    }

    private void save(Player player, Session session) {
        try {
            GunDraft draft = session.draft;
            draft.sanitizeFileName();
            Path folder = plugin.getDataFolder().toPath().resolve(draft.throwable ? "projectile" : "guns");
            Path other = plugin.getDataFolder().toPath().resolve(draft.throwable ? "guns" : "projectile");
            Path target = folder.resolve(draft.fileName);
            Path stale = other.resolve(draft.fileName);
            GunWriter.write(target, draft);
            Files.deleteIfExists(stale);
            plugin.reloadGuns();
            player.sendMessage(Component.text("Saved " + draft.fileName + " and reloaded guns.", NamedTextColor.GREEN));
            openEdit(player, plugin.registry().get(draft.fileName).orElseThrow());
        } catch (Exception e) {
            player.sendMessage(Component.text("Save failed: " + e.getMessage(), NamedTextColor.RED));
            plugin.getLogger().warning("Gun editor save failed: " + e.getMessage());
        }
    }

    public boolean handleChat(Player player, String message) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.prompt == null) {
            return false;
        }
        if (message.equalsIgnoreCase("cancel")) {
            session.prompt = null;
            session.numberField = null;
            session.roundColorField = null;
            player.sendMessage(Component.text("Cancelled.", NamedTextColor.YELLOW));
            Bukkit.getScheduler().runTask(plugin, () -> render(player));
            return true;
        }
        try {
            if (session.page == Page.ROUND_EDIT && session.roundDraft != null) {
                handleRoundChat(player, session, message);
                return true;
            }
            if (session.draft == null) {
                return false;
            }
            GunDraft d = session.draft;
            switch (session.prompt) {
                case FILENAME -> d.fileName = message.trim();
                case DISPLAY_NAME -> d.displayName = message.trim();
                case AMMO_MATERIAL -> {
                    Material mat = Material.matchMaterial(message.trim().toUpperCase(Locale.ROOT));
                    if (mat == null || !mat.isItem()) {
                        throw new IllegalArgumentException("Unknown item material");
                    }
                    d.ammoMaterial = mat;
                    d.ammoCaliber = AmmoCaliber.fromMaterial(mat);
                }
                case BULLET_TYPE -> d.bulletType = message.trim();
                case RELOAD_TYPE -> d.reloadType = message.trim().toUpperCase(Locale.ROOT);
                case OUT_OF_AMMO -> d.outOfAmmoMessage = message.trim();
                case PERMISSION_MESSAGE -> d.permissionMessage = message.trim();
                case GUN_SOUND_ADD -> d.gunSounds.add(message.trim());
                case REMNANT_MATERIAL -> {
                    String raw = message.trim();
                    if (raw.equalsIgnoreCase("none") || raw.equalsIgnoreCase("air")
                            || raw.equalsIgnoreCase("off") || raw.equalsIgnoreCase("false")) {
                        d.remnantItem = Material.AIR;
                    } else {
                        Material mat = Material.matchMaterial(raw.toUpperCase(Locale.ROOT));
                        if (mat == null || !mat.isItem()) {
                            throw new IllegalArgumentException("Unknown item material (or type NONE)");
                        }
                        d.remnantItem = mat;
                    }
                }
                case REMNANT_NAME -> d.remnantName = message.trim();
                case LASER_COLOR -> d.laserSightColor = normalizeHex(message.trim());
                case MUZZLE_COLOR -> d.muzzleColor = normalizeHex(message.trim());
                case NUMBER -> applyNumber(session, Double.parseDouble(message.trim()));
                default -> throw new IllegalArgumentException("Unsupported prompt");
            }
            session.prompt = null;
            session.numberField = null;
            player.sendMessage(Component.text("Updated.", NamedTextColor.GREEN));
            Bukkit.getScheduler().runTask(plugin, () -> render(player));
        } catch (Exception e) {
            player.sendMessage(Component.text("Invalid value: " + e.getMessage() + " (or type cancel)", NamedTextColor.RED));
        }
        return true;
    }

    private void handleRoundChat(Player player, Session session, String message) throws Exception {
        RoundDraft r = session.roundDraft;
        switch (session.prompt) {
            case ROUND_FILENAME -> r.fileName = message.trim();
            case ROUND_DISPLAY_NAME -> r.displayName = message.trim();
            case AMMO_MATERIAL -> {
                Material mat = Material.matchMaterial(message.trim().toUpperCase(Locale.ROOT));
                if (mat == null || !mat.isItem()) {
                    throw new IllegalArgumentException("Unknown item material");
                }
                r.material = mat;
            }
            case ROUND_COLOR -> {
                String hex = normalizeHex(message.trim());
                if ("tracer".equalsIgnoreCase(session.roundColorField)) {
                    r.tracerColor = hex;
                } else {
                    r.muzzleColor = hex;
                }
            }
            case NUMBER -> applyRoundNumber(session, Double.parseDouble(message.trim()));
            default -> throw new IllegalArgumentException("Unsupported round prompt");
        }
        session.prompt = null;
        session.numberField = null;
        session.roundColorField = null;
        player.sendMessage(Component.text("Updated.", NamedTextColor.GREEN));
        Bukkit.getScheduler().runTask(plugin, () -> render(player));
    }

    private void ask(Player player, Prompt prompt, String numberField) {
        Session session = session(player);
        session.prompt = prompt;
        session.numberField = numberField;
        player.closeInventory();
        player.sendMessage(Component.text("Type the new value in chat, or 'cancel'.", NamedTextColor.YELLOW));
    }

    private void adjustInt(Player player, String field, java.util.function.IntConsumer setter, int current,
                           boolean left, boolean right, boolean shift) {
        if (!(left || right)) {
            ask(player, Prompt.NUMBER, field);
            return;
        }
        int delta = shift ? 10 : 1;
        if (left) {
            delta = -delta;
        }
        setter.accept(current + delta);
        render(player);
    }

    private void adjustDouble(Player player, String field, java.util.function.DoubleConsumer setter, double current,
                              boolean left, boolean right, boolean shift, double step, double bigStep) {
        if (!(left || right)) {
            ask(player, Prompt.NUMBER, field);
            return;
        }
        double delta = shift ? bigStep : step;
        if (left) {
            delta = -delta;
        }
        setter.accept(current + delta);
        render(player);
    }

    /** Temporary visual preview of the draft laser (companion + vanilla particles). */
    private void previewLaser(Player player, GunDraft d) {
        if (!d.laserSight) {
            player.sendMessage(Component.text("Enable Laser Sight first.", NamedTextColor.YELLOW));
            return;
        }
        player.sendMessage(Component.text("Laser preview ~3s — look where you want the tip.", NamedTextColor.AQUA));
        for (int i = 0; i <= 60; i++) {
            final int tick = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                boolean infrared = d.laserSightIr;
                Color color = infrared
                        ? com.local.warz.runtime.NvgGear.IR_PHOSPHOR
                        : LaserBeams.parseColor(d.laserSightColor, Color.RED);
                float size = d.laserSightSize <= 0 ? 0.28f : d.laserSightSize;
                if (infrared) {
                    size = Math.max(0.08f, size * 0.55f);
                }
                double range = d.laserSightRange > 0 ? d.laserSightRange : Math.max(8, d.maxDistance);
                var eye = player.getEyeLocation();
                var aim = LaserBeams.aimPoint(player, eye, range, true, infrared);
                var muzzle = LaserBeams.muzzleOrigin(
                        eye,
                        d.laserSightOffsetRight,
                        d.laserSightOffsetUp,
                        d.laserSightOffsetForward
                );
                LaserOptics.BeamPath path = LaserOptics.traceFromTo(muzzle, aim, size, d.laserSightDensity, infrared);
                LaserCompanionBridge bridge = plugin.laserBridge();
                if (bridge != null) {
                    if (tick % 2 == 0) {
                        if (!infrared) {
                            List<Player> vanilla = bridge.vanillaViewersNear(muzzle);
                            if (!vanilla.isEmpty()) {
                                LaserOptics.spawnParticles(path, color, size, d.laserSightDensity, vanilla);
                            }
                        }
                        bridge.broadcastBeam(player, path, color, size, infrared);
                    }
                    if (tick == 60) {
                        bridge.clearBeam(player);
                    }
                } else if (tick % 2 == 0 && !infrared) {
                    LaserBeams.drawFromTo(muzzle, aim, color, size, d.laserSightDensity, false);
                }
            }, i);
        }
    }

    private void applyNumber(Session session, double value) {
        GunDraft d = session.draft;
        switch (session.numberField) {
            case "ammoAmtNeeded" -> d.ammoAmtNeeded = (int) value;
            case "gunDamage" -> d.gunDamage = (int) value;
            case "armorPenetration" -> d.armorPenetration = (int) value;
            case "roundsPerBurst" -> d.roundsPerBurst = (int) value;
            case "bulletsPerClick" -> d.bulletsPerClick = (int) value;
            case "bulletDelayTime" -> d.bulletDelayTime = (int) value;
            case "bulletSpeed" -> d.bulletSpeed = value;
            case "fallSpeed" -> d.fallSpeed = value;
            case "maxDistance" -> d.maxDistance = (int) value;
            case "releaseTime" -> d.releaseTime = (int) value;
            case "accuracy" -> d.accuracy = value;
            case "accuracyAimed" -> d.accuracyAimed = value;
            case "accuracyCrouched" -> d.accuracyCrouched = value;
            case "recoil" -> d.recoil = value;
            case "recoilPitch" -> d.recoilPitch = value;
            case "knockback" -> d.knockback = value;
            case "gunVolume" -> d.gunVolume = value;
            case "explodeRadius" -> d.explodeRadius = value;
            case "explosionDamage" -> d.explosionDamage = (int) value;
            case "fireRadius" -> d.fireRadius = value;
            case "flashRadius" -> d.flashRadius = value;
            case "maxClipSize" -> d.maxClipSize = (int) value;
            case "reloadTime" -> d.reloadTime = (int) value;
            case "remnantAmount" -> d.remnantAmount = (int) value;
            case "remnantPickupDelay" -> d.remnantPickupDelay = (int) value;
            case "remnantLifetime" -> d.remnantLifetime = (int) value;
            case "laserSightRange" -> d.laserSightRange = value;
            case "laserSightSize" -> d.laserSightSize = (float) value;
            case "laserSightOffsetRight" -> d.laserSightOffsetRight = value;
            case "laserSightOffsetUp" -> d.laserSightOffsetUp = value;
            case "laserSightOffsetForward" -> d.laserSightOffsetForward = value;
            case "laserSightDensity" -> d.laserSightDensity = value;
            case "laserSightGlowStrength" -> d.laserSightGlowStrength = value;
            case "muzzleScale" -> d.muzzleScale = (float) value;
            default -> throw new IllegalArgumentException("Unknown field");
        }
    }

    private void applyRoundNumber(Session session, double value) {
        RoundDraft r = session.roundDraft;
        switch (session.numberField) {
            case "customModelData" -> r.customModelData = (int) value;
            case "damageMult" -> r.damageMult = clampDouble(value, 0.05, 5);
            case "armorPenAdd" -> r.armorPenAdd = (int) value;
            case "accuracyMult" -> r.accuracyMult = clampDouble(value, 0.05, 5);
            case "speedMult" -> r.speedMult = clampDouble(value, 0.05, 5);
            case "knockbackMult" -> r.knockbackMult = clampDouble(value, 0, 5);
            case "rangeMult" -> r.rangeMult = clampDouble(value, 0.05, 5);
            case "tracerWidth" -> r.tracerWidth = (float) clampDouble(value, 0.01, 0.35);
            case "muzzleScale" -> r.muzzleScale = (float) clampDouble(value, 0.2, 3);
            case "explodeRadiusAdd" -> r.explodeRadiusAdd = clampDouble(value, 0, 16);
            case "fireRadiusAdd" -> r.fireRadiusAdd = clampDouble(value, 0, 16);
            case "setFireTicks" -> r.setFireTicks = (int) value;
            default -> throw new IllegalArgumentException("Unknown round field");
        }
    }

    private void handleRoundsClick(Player player, Session session, GunDraft d, int slot) {
        if (d.allowedRounds == null) {
            d.allowedRounds = new ArrayList<>();
        }
        if (slot == 10) {
            d.ammoCaliber = AmmoCaliber.next(d.ammoCaliber);
            d.ammoMaterial = AmmoCaliber.defaultMaterial(d.ammoCaliber);
            d.allowedRounds = new ArrayList<>(List.of(AmmoCaliber.defaultAllowed(d.ammoCaliber)));
            render(player);
            return;
        }
        if (slot == 11) {
            session.page = Page.ROUND_LIBRARY;
            render(player);
            return;
        }
        if (slot == 12) {
            String id = AmmoCaliber.primaryRound(d.ammoCaliber);
            plugin.rounds().get(id).ifPresent(round -> {
                plugin.items().giveOrDrop(player, plugin.items().createRound(round, 64));
                player.sendMessage(Component.text("Gave 64x " + round.fileName(), NamedTextColor.GREEN));
            });
            return;
        }
        if (slot == 13) {
            Iterable<String> ids = d.allowedRounds.isEmpty()
                    ? List.of(AmmoCaliber.defaultAllowed(d.ammoCaliber))
                    : d.allowedRounds;
            for (String id : ids) {
                plugin.rounds().get(id).ifPresent(round ->
                        plugin.items().giveOrDrop(player, plugin.items().createRound(round, 16)));
            }
            player.sendMessage(Component.text("Gave allowed rounds.", NamedTextColor.GREEN));
            return;
        }
        if (slot == 14) {
            d.allowedRounds = new ArrayList<>(List.of(AmmoCaliber.defaultAllowed(d.ammoCaliber)));
            render(player);
            return;
        }
        if (slot == 15) {
            d.allowedRounds.clear();
            render(player);
            return;
        }
        if (slot >= 19 && slot < 44) {
            List<RoundDefinition> rounds = plugin.rounds().byCaliber(d.ammoCaliber);
            int index = slot - 19;
            if (index >= rounds.size()) {
                return;
            }
            String id = rounds.get(index).fileName();
            if (d.allowedRounds.isEmpty()) {
                d.allowedRounds = new ArrayList<>(List.of(AmmoCaliber.defaultAllowed(d.ammoCaliber)));
            }
            if (d.allowedRounds.contains(id)) {
                d.allowedRounds.remove(id);
            } else {
                d.allowedRounds.add(id);
            }
            render(player);
        }
    }

    private void fillRoundLibrary(Inventory inv, Session session) {
        List<RoundDefinition> rounds = plugin.rounds().all().stream()
                .sorted(Comparator.comparing(RoundDefinition::fileName))
                .toList();
        int perPage = 45;
        int maxPage = Math.max(0, (rounds.size() - 1) / perPage);
        session.roundBrowserPage = Math.min(session.roundBrowserPage, maxPage);
        int start = session.roundBrowserPage * perPage;
        for (int i = 0; i < perPage && start + i < rounds.size(); i++) {
            RoundDefinition round = rounds.get(start + i);
            ItemStack icon = plugin.items().createRound(round, 1);
            ItemMeta meta = icon.getItemMeta();
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(text("&7Click to edit"));
            meta.lore(lore);
            icon.setItemMeta(meta);
            inv.setItem(i, icon);
        }
        inv.setItem(45, button(Material.ARROW, "&ePrev", "&7Page " + (session.roundBrowserPage + 1)));
        inv.setItem(49, button(Material.EMERALD_BLOCK, "&aCreate Round", "&7Blank round editor"));
        inv.setItem(52, button(Material.ARROW, "&eNext", "&7Page " + (session.roundBrowserPage + 1)));
        inv.setItem(53, button(Material.BARRIER, "&cBack to Gun Rounds", "&7Return"));
    }

    private void fillRoundEdit(Inventory inv, Session session) {
        RoundDraft r = session.roundDraft;
        if (r == null) {
            return;
        }
        set(inv, 10, Material.NAME_TAG, "&bFile Name", "&f" + r.fileName, "&7Click: chat rename");
        set(inv, 11, Material.PAPER, "&bDisplay Name", "&f" + r.displayName, "&7Click: chat rename");
        set(inv, 12, r.material, "&bMaterial", "&f" + r.material.name(), "&7Click: chat material");
        set(inv, 13, Material.HOPPER, "&bCaliber", "&f" + r.caliber, "&8Click: cycle");
        set(inv, 14, Material.COMPARATOR, "&bCustom Model Data", num(r.customModelData), tips());
        set(inv, 19, Material.IRON_SWORD, "&bDamage Mult", num(r.damageMult), tips());
        set(inv, 20, Material.IRON_CHESTPLATE, "&bArmor Pen Add", num(r.armorPenAdd), tips());
        set(inv, 21, Material.SPYGLASS, "&bAccuracy Mult", num(r.accuracyMult), tips());
        set(inv, 22, Material.FEATHER, "&bSpeed Mult", num(r.speedMult), tips());
        set(inv, 23, Material.SLIME_BALL, "&bKnockback Mult", num(r.knockbackMult), tips());
        set(inv, 24, Material.ENDER_PEARL, "&bRange Mult", num(r.rangeMult), tips());
        set(inv, 28, Material.SPECTRAL_ARROW, "&bTracer Round", bool(r.tracer), "&8Click toggle");
        set(inv, 29, dyeIconFor(r.tracerColor), "&bTracer Color", "&f" + r.tracerColor, "&7Click: chat hex");
        set(inv, 30, Material.STRING, "&bTracer Width", num(r.tracerWidth), tips());
        set(inv, 31, Material.BLAZE_POWDER, "&bMuzzle Flash", bool(r.muzzleFlash), "&8Click toggle");
        set(inv, 32, dyeIconFor(r.muzzleColor), "&bMuzzle Color", "&f" + r.muzzleColor, "&7Click: chat hex");
        set(inv, 33, Material.MAGMA_CREAM, "&bMuzzle Scale", num(r.muzzleScale), tips());
        set(inv, 34, Material.TNT, "&bExplode Radius Add", num(r.explodeRadiusAdd), tips());
        set(inv, 37, Material.FLINT_AND_STEEL, "&bFire Radius Add", num(r.fireRadiusAdd), tips());
        set(inv, 38, Material.CAMPFIRE, "&bSet Fire Ticks", num(r.setFireTicks), tips());
        set(inv, 39, Material.GRAY_DYE, "&bSubsonic", bool(r.subsonic),
                "&7No sonic crack / soft report",
                "&7+ suppressor = whisper",
                "&8Click toggle");
        set(inv, 40, Material.CHEST, "&aGive x64", "&7Preview this round item");
        inv.setItem(49, button(Material.LIME_CONCRETE, "&aSave Round", "&7Writes rounds/ file + reload"));
        inv.setItem(53, button(Material.BARRIER, "&cBack", "&7Round library"));
    }

    private void handleRoundLibraryClick(Player player, Session session, int slot, boolean left) {
        if (slot == 45) {
            session.roundBrowserPage = Math.max(0, session.roundBrowserPage - 1);
            render(player);
            return;
        }
        if (slot == 52) {
            session.roundBrowserPage++;
            render(player);
            return;
        }
        if (slot == 49) {
            session.creatingRound = true;
            session.roundDraft = new RoundDraft();
            session.page = Page.ROUND_EDIT;
            render(player);
            return;
        }
        if (slot == 53) {
            session.page = session.draft != null ? Page.ROUNDS : Page.BROWSER;
            render(player);
            return;
        }
        if (slot < 0 || slot >= 45 || !left) {
            return;
        }
        List<RoundDefinition> rounds = plugin.rounds().all().stream()
                .sorted(Comparator.comparing(RoundDefinition::fileName))
                .toList();
        int index = session.roundBrowserPage * 45 + slot;
        if (index < rounds.size()) {
            session.creatingRound = false;
            session.roundDraft = RoundDraft.from(rounds.get(index));
            session.page = Page.ROUND_EDIT;
            render(player);
        }
    }

    private void handleRoundEditClick(Player player, Session session, int slot,
                                      boolean left, boolean right, boolean shift) {
        RoundDraft r = session.roundDraft;
        if (r == null) {
            return;
        }
        if (slot == 53) {
            session.page = Page.ROUND_LIBRARY;
            render(player);
            return;
        }
        if (slot == 49) {
            saveRound(player, session);
            return;
        }
        if (slot == 40) {
            plugin.items().giveOrDrop(player, plugin.items().createRound(r.toDefinition(), 64));
            player.sendMessage(Component.text("Gave 64x " + r.fileName, NamedTextColor.GREEN));
            return;
        }
        if (slot == 10) ask(player, Prompt.ROUND_FILENAME, null);
        else if (slot == 11) ask(player, Prompt.ROUND_DISPLAY_NAME, null);
        else if (slot == 12) ask(player, Prompt.AMMO_MATERIAL, null);
        else if (slot == 13) {
            r.caliber = AmmoCaliber.next(r.caliber);
            r.material = AmmoCaliber.defaultMaterial(r.caliber);
            render(player);
        } else if (slot == 14) adjustInt(player, "customModelData", v -> r.customModelData = clampInt(v, 1, 99999), r.customModelData, left, right, shift);
        else if (slot == 19) adjustDouble(player, "damageMult", v -> r.damageMult = clampDouble(v, 0.05, 5), r.damageMult, left, right, shift, 0.05, 0.25);
        else if (slot == 20) adjustInt(player, "armorPenAdd", v -> r.armorPenAdd = clampInt(v, -20, 50), r.armorPenAdd, left, right, shift);
        else if (slot == 21) adjustDouble(player, "accuracyMult", v -> r.accuracyMult = clampDouble(v, 0.05, 5), r.accuracyMult, left, right, shift, 0.05, 0.25);
        else if (slot == 22) adjustDouble(player, "speedMult", v -> r.speedMult = clampDouble(v, 0.05, 5), r.speedMult, left, right, shift, 0.05, 0.25);
        else if (slot == 23) adjustDouble(player, "knockbackMult", v -> r.knockbackMult = clampDouble(v, 0, 5), r.knockbackMult, left, right, shift, 0.05, 0.25);
        else if (slot == 24) adjustDouble(player, "rangeMult", v -> r.rangeMult = clampDouble(v, 0.05, 5), r.rangeMult, left, right, shift, 0.05, 0.25);
        else if (slot == 28) { r.tracer = !r.tracer; render(player); }
        else if (slot == 29) {
            session.roundColorField = "tracer";
            ask(player, Prompt.ROUND_COLOR, null);
        } else if (slot == 30) adjustDouble(player, "tracerWidth", v -> r.tracerWidth = (float) clampDouble(v, 0.01, 0.35), r.tracerWidth, left, right, shift, 0.005, 0.02);
        else if (slot == 31) { r.muzzleFlash = !r.muzzleFlash; render(player); }
        else if (slot == 32) {
            session.roundColorField = "muzzle";
            ask(player, Prompt.ROUND_COLOR, null);
        } else if (slot == 33) adjustDouble(player, "muzzleScale", v -> r.muzzleScale = (float) clampDouble(v, 0.2, 3), r.muzzleScale, left, right, shift, 0.05, 0.2);
        else if (slot == 34) adjustDouble(player, "explodeRadiusAdd", v -> r.explodeRadiusAdd = clampDouble(v, 0, 16), r.explodeRadiusAdd, left, right, shift, 0.25, 1.0);
        else if (slot == 37) adjustDouble(player, "fireRadiusAdd", v -> r.fireRadiusAdd = clampDouble(v, 0, 16), r.fireRadiusAdd, left, right, shift, 0.25, 1.0);
        else if (slot == 38) adjustInt(player, "setFireTicks", v -> r.setFireTicks = clampInt(v, 0, 600), r.setFireTicks, left, right, shift);
        else if (slot == 39) { r.subsonic = !r.subsonic; render(player); }
    }

    private void saveRound(Player player, Session session) {
        try {
            RoundDraft draft = session.roundDraft;
            draft.sanitizeFileName();
            Path target = plugin.rounds().roundsDir().resolve(draft.fileName);
            RoundWriter.write(target, draft);
            plugin.reloadGuns();
            player.sendMessage(Component.text("Saved round " + draft.fileName, NamedTextColor.GREEN));
            session.creatingRound = false;
            session.roundDraft = RoundDraft.from(plugin.rounds().get(draft.fileName).orElseThrow());
            render(player);
        } catch (Exception e) {
            player.sendMessage(Component.text("Round save failed: " + e.getMessage(), NamedTextColor.RED));
        }
    }

    private static String cycleReload(String current) {
        return switch (current == null ? "" : current.toUpperCase(Locale.ROOT)) {
            case "NORMAL" -> "BOLT";
            case "BOLT" -> "PUMP";
            case "PUMP" -> "INDIVIDUAL_BULLET";
            default -> "NORMAL";
        };
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static ItemStack button(Material material, String name, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ItemFactory.colorize(name).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String line : loreLines) {
            lore.add(ItemFactory.colorize(line).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static void set(Inventory inv, int slot, Material material, String name, String... lore) {
        inv.setItem(slot, button(material, name, lore));
    }

    private static Component text(String legacy) {
        return ItemFactory.colorize(legacy).decoration(TextDecoration.ITALIC, false);
    }

    private static String num(int value) {
        return "&f" + value;
    }

    private static String num(double value) {
        if (Math.rint(value) == value) {
            return "&f" + ((int) value);
        }
        return "&f" + value;
    }

    private static String bool(boolean value) {
        return value ? "&aTRUE" : "&cFALSE";
    }

    private static String tips() {
        return "&7Left -1 / Right +1 / Shift x10 / Drop=type";
    }
}
