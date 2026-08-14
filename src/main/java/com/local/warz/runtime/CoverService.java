package com.local.warz.runtime;

import com.local.warz.projectile.Bullet;
import com.local.warz.util.LaserOptics;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Soft / hard / metal cover for bullets (glass stays on {@link GlassService}).
 */
public final class CoverService {
    public enum Kind {
        NONE, SOFT, HARD, METAL
    }

    public enum HitResult {
        NONE, PENETRATE, RICOCHET, STOP
    }

    private CoverService() {
    }

    public static boolean isIronBarsOrChain(Material type) {
        if (type == null) {
            return false;
        }
        if (type == Material.IRON_BARS) {
            return true;
        }
        String name = type.name();
        return name.equals("CHAIN") || name.endsWith("_CHAIN");
    }

    public static Kind classify(Block block) {
        if (block == null || block.getType().isAir()) {
            return Kind.NONE;
        }
        Material type = block.getType();
        String name = type.name();

        // Metal first (not polished stone / quartz — those are hard cover)
        if (isMetal(name, type)) {
            return Kind.METAL;
        }

        if (name.contains("GLASS") || name.contains("ICE")) {
            return Kind.NONE; // vanilla glass / ice — not tactical glass, not cover table
        }

        if (isSoft(name, type)) {
            return Kind.SOFT;
        }
        if (isHard(name, type)) {
            return Kind.HARD;
        }
        return Kind.NONE;
    }

    private static boolean isMetal(String name, Material type) {
        if (type == Material.IRON_BLOCK || type == Material.GOLD_BLOCK || type == Material.COPPER_BLOCK
                || type == Material.NETHERITE_BLOCK || type == Material.IRON_DOOR || type == Material.IRON_TRAPDOOR
                || type == Material.IRON_BARS
                || type == Material.ANVIL || type == Material.CHIPPED_ANVIL || type == Material.DAMAGED_ANVIL
                || type == Material.HOPPER || type == Material.LIGHTNING_ROD
                || type == Material.HEAVY_WEIGHTED_PRESSURE_PLATE || type == Material.LIGHT_WEIGHTED_PRESSURE_PLATE
                || type == Material.RAW_IRON_BLOCK || type == Material.RAW_GOLD_BLOCK
                || type == Material.RAW_COPPER_BLOCK || type == Material.CAULDRON
                || type == Material.LAVA_CAULDRON || type == Material.WATER_CAULDRON
                || type == Material.POWDER_SNOW_CAULDRON) {
            return true;
        }
        return name.equals("CHAIN") || name.endsWith("_CHAIN")
                || (name.contains("IRON") && !name.contains("ORE"))
                || (name.contains("COPPER") && !name.contains("ORE"))
                || (name.contains("GOLD") && !name.contains("ORE") && !name.contains("GOLDEN"))
                || name.contains("NETHERITE")
                || name.startsWith("WAXED_") && name.contains("COPPER")
                || name.equals("EXPOSED_COPPER") || name.equals("WEATHERED_COPPER")
                || name.equals("OXIDIZED_COPPER");
    }

    private static boolean isSoft(String name, Material type) {
        if (type == Material.HAY_BLOCK || type == Material.TARGET || type == Material.NOTE_BLOCK
                || type == Material.BOOKSHELF || type == Material.CHISELED_BOOKSHELF
                || type == Material.LADDER || type == Material.SCAFFOLDING
                || type == Material.MELON || type == Material.PUMPKIN || type == Material.CARVED_PUMPKIN
                || type == Material.JACK_O_LANTERN || type == Material.BEE_NEST || type == Material.BEEHIVE) {
            return true;
        }
        // Explicit plank/log families (26.x keeps *_PLANKS / *_LOG / *_WOOD)
        if (name.endsWith("_PLANKS") || name.endsWith("_LOG") || name.endsWith("_WOOD")
                || name.endsWith("_STEM") || name.endsWith("_HYPHAE")
                || name.equals("PLANKS") || name.contains("PLANKS")) {
            return true;
        }
        return name.contains("WOOD") || name.contains("LOG") || name.contains("PLANK")
                || name.contains("STEM") || name.contains("HYPHAE")
                || name.endsWith("_DOOR") || name.endsWith("_TRAPDOOR") || name.endsWith("_FENCE")
                || name.endsWith("_FENCE_GATE") || name.endsWith("_SIGN") || name.endsWith("_HANGING_SIGN")
                || name.endsWith("_SLAB") && (name.contains("OAK") || name.contains("SPRUCE")
                || name.contains("BIRCH") || name.contains("JUNGLE") || name.contains("ACACIA")
                || name.contains("DARK_OAK") || name.contains("MANGROVE") || name.contains("CHERRY")
                || name.contains("BAMBOO") || name.contains("CRIMSON") || name.contains("WARPED"))
                || name.endsWith("_STAIRS") && (name.contains("OAK") || name.contains("SPRUCE")
                || name.contains("BIRCH") || name.contains("JUNGLE") || name.contains("ACACIA")
                || name.contains("DARK_OAK") || name.contains("MANGROVE") || name.contains("CHERRY")
                || name.contains("BAMBOO") || name.contains("CRIMSON") || name.contains("WARPED"))
                || name.contains("WOOL") || name.contains("CARPET") || name.contains("BED")
                || name.contains("LEAVES") || name.contains("MOSS") || name.contains("BAMBOO")
                || name.contains("CORK")                 || name.equals("BARREL") || name.equals("CHEST")
                || name.equals("TRAPPED_CHEST") || name.equals("CRAFTING_TABLE")
                || name.equals("CARTOGRAPHY_TABLE") || name.equals("FLETCHING_TABLE")
                || name.equals("SMITHING_TABLE") || name.equals("LOOM") || name.equals("COMPOSTER")
                || name.equals("DIRT") || name.equals("GRASS_BLOCK") || name.equals("DIRT_PATH")
                || name.equals("COARSE_DIRT") || name.equals("ROOTED_DIRT") || name.equals("PODZOL")
                || name.equals("MYCELIUM") || name.equals("MUD") || name.equals("PACKED_MUD")
                || name.equals("CLAY") || name.equals("GRAVEL") || name.equals("SAND")
                || name.equals("RED_SAND") || name.contains("SNOW");
    }

    private static boolean isHard(String name, Material type) {
        if (type == Material.OBSIDIAN || type == Material.CRYING_OBSIDIAN || type == Material.BEDROCK
                || type == Material.ANCIENT_DEBRIS || type == Material.END_STONE
                || type == Material.END_STONE_BRICKS) {
            return true;
        }
        return name.contains("STONE") || name.contains("DEEPSLATE") || name.contains("GRANITE")
                || name.contains("DIORITE") || name.contains("ANDESITE") || name.contains("BASALT")
                || name.contains("BLACKSTONE") || name.contains("COBBLE") || name.contains("BRICK")
                || name.contains("CONCRETE") || name.contains("TERRACOTTA") || name.contains("PRISMARINE")
                || name.contains("PURPUR") || name.contains("QUARTZ") || name.contains("SANDSTONE")
                || name.contains("NETHERRACK") || name.contains("NYLIUM") || name.contains("CALCIATE")
                || name.contains("TUFF") || name.contains("CALCITE") || name.contains("DRIPSTONE")
                || name.contains("AMETHYST") || name.contains("ORE");
    }

    /**
     * @return NONE if not cover; else penetrate / ricochet / stop
     */
    public static HitResult handleHit(Bullet bullet, Block block, BlockFace face) {
        Kind kind = classify(block);
        if (kind == Kind.NONE || bullet == null) {
            return HitResult.NONE;
        }
        String k = GlassService.key(block);
        if (bullet.ignoresPierce(k)) {
            nudgeThrough(bullet, block, face);
            return HitResult.PENETRATE;
        }

        BallisticsProfile shot = BallisticsProfile.of(bullet);
        Location at = block.getLocation().add(0.5, 0.5, 0.5);
        World world = block.getWorld();
        BlockFace hitFace = face != null && face.isCartesian() ? face : BlockFace.NORTH;
        Vector dir = bullet.velocity();
        if (dir == null || dir.lengthSquared() < 1.0e-12) {
            playStop(world, at, kind, shot);
            return HitResult.STOP;
        }
        dir = dir.clone().normalize();
        Vector normal = hitFace.getDirection();
        double headOn = Math.abs(dir.dot(normal)); // 1 = perpendicular impact, 0 = graze

        if (kind == Kind.METAL) {
            // Iron bars / chain-link: 2/3 chance to slip through with no real resistance.
            if (isIronBarsOrChain(block.getType())
                    && ThreadLocalRandom.current().nextFloat() < (2.0f / 3.0f)) {
                bullet.ignorePierceKey(k, 4);
                world.playSound(at, Sound.BLOCK_CHAIN_HIT, 0.35f, 1.55f);
                world.spawnParticle(Particle.CRIT, at, 3, 0.08, 0.08, 0.08, 0.02);
                nudgeThrough(bullet, block, hitFace);
                return HitResult.PENETRATE;
            }
            if (tryRicochet(bullet, block, k, at, world, kind, dir, normal, headOn, shot, 0.55)) {
                return HitResult.RICOCHET;
            }
            // Head-on metal: AP may punch; FMJ/HP stop with a clang
            if (shot.family() == BallisticsProfile.AmmoFamily.AP && shot.penNorm() >= 0.5f && headOn > 0.7) {
                bullet.scaleVelocity(shot.hardRetain() * 0.85);
                bullet.ignorePierceKey(k, 3);
                world.playSound(at, Sound.BLOCK_ANVIL_PLACE, 0.45f, 1.3f);
                world.spawnParticle(Particle.CRIT, at, 6, 0.1, 0.1, 0.1, 0.05);
                nudgeThrough(bullet, block, hitFace);
                return HitResult.PENETRATE;
            }
            playStop(world, at, kind, shot);
            return HitResult.STOP;
        }

        if (kind == Kind.SOFT) {
            if (!shot.penetratesSoft()) {
                playStop(world, at, kind, shot);
                // HP thuds into wood
                world.playSound(at, Sound.BLOCK_WOOD_HIT, 0.9f, 0.7f + shot.penNorm() * 0.3f);
                return HitResult.STOP;
            }
            bullet.scaleVelocity(shot.softRetain());
            bullet.ignorePierceKey(k, 8);
            float pitch = switch (shot.caliber()) {
                case "pistol", "handgun" -> 1.35f;
                case "sniper", "heavy" -> 0.75f;
                case "shotgun", "shot" -> 0.85f;
                default -> 1.05f;
            };
            world.playSound(at, Sound.BLOCK_WOOD_HIT, 0.85f, pitch);
            world.playSound(at, Sound.BLOCK_BAMBOO_HIT, 0.4f, pitch + 0.2f);
            world.spawnParticle(Particle.BLOCK, at, 10, 0.2, 0.2, 0.2, 0.02, block.getBlockData());
            nudgeThrough(bullet, block, hitFace);
            return HitResult.PENETRATE;
        }

        // HARD — angled graze can ricochet; otherwise pen (AP/HE) or stop
        if (tryRicochet(bullet, block, k, at, world, kind, dir, normal, headOn, shot, 0.48)) {
            return HitResult.RICOCHET;
        }
        if (!shot.penetratesHard()) {
            playStop(world, at, kind, shot);
            return HitResult.STOP;
        }
        bullet.scaleVelocity(shot.hardRetain());
        bullet.ignorePierceKey(k, 3);
        world.playSound(at, Sound.BLOCK_STONE_HIT, 1.0f, 0.7f + shot.penNorm() * 0.5f);
        world.playSound(at, Sound.BLOCK_DEEPSLATE_HIT, 0.5f, 1.2f);
        world.spawnParticle(Particle.BLOCK, at, 12, 0.2, 0.2, 0.2, 0.03, block.getBlockData());
        nudgeThrough(bullet, block, hitFace);
        return HitResult.PENETRATE;
    }

    /**
     * @param grazeMax head-on cosine below this counts as angled enough to bounce
     */
    private static boolean tryRicochet(Bullet bullet, Block block, String key, Location at, World world,
                                      Kind kind, Vector dir, Vector normal, double headOn,
                                      BallisticsProfile shot, double grazeMax) {
        if (!shot.canRicochet() || !bullet.canRicochetMore()) {
            return false;
        }
        if (headOn >= grazeMax) {
            return false;
        }
        if (ThreadLocalRandom.current().nextFloat() >= ricochetChance(shot, headOn, grazeMax, kind)) {
            return false;
        }
        Vector reflected = LaserOptics.reflectDirection(dir, normal);
        double retain = shot.ricochetRetain() * (kind == Kind.HARD ? 0.85 : 1.0);
        bullet.applyRicochet(reflected, retain);
        bullet.ignorePierceKey(key, 6);
        playRicochetFx(world, at, kind, shot, block);
        nudgeOut(bullet, block, reflected);
        return true;
    }

    private static void playRicochetFx(World world, Location at, Kind kind, BallisticsProfile shot, Block block) {
        float pitch = 1.2f + shot.penNorm() * 0.4f;
        if (kind == Kind.METAL) {
            world.playSound(at, Sound.BLOCK_ANVIL_LAND, 0.4f, 1.6f + shot.penNorm() * 0.4f);
            world.playSound(at, Sound.ENTITY_IRON_GOLEM_HURT, 0.28f, 1.8f);
            world.spawnParticle(Particle.CRIT, at, 10, 0.15, 0.15, 0.15, 0.1);
        } else {
            world.playSound(at, Sound.BLOCK_STONE_HIT, 0.9f, pitch);
            world.playSound(at, Sound.BLOCK_GRAVEL_HIT, 0.55f, 1.3f);
            world.spawnParticle(Particle.CRIT, at, 8, 0.12, 0.12, 0.12, 0.08);
            world.spawnParticle(Particle.BLOCK, at, 14, 0.18, 0.18, 0.18, 0.04, block.getBlockData());
        }
    }

    private static float ricochetChance(BallisticsProfile shot, double headOn, double grazeMax, Kind kind) {
        // Shallower = more likely (headOn → 0)
        float graze = (float) Math.max(0.0, 1.0 - headOn / Math.max(0.05, grazeMax));
        float base = switch (shot.family()) {
            case FMJ, STANDARD -> 0.88f;
            case AP -> 0.50f;
            case SLUG -> 0.62f;
            case THERMAL -> 0.40f;
            default -> 0.25f;
        };
        if (kind == Kind.HARD) {
            base *= 0.85f;
        }
        return Math.min(0.97f, base * (0.50f + graze * 0.50f));
    }

    private static void playStop(World world, Location at, Kind kind, BallisticsProfile shot) {
        float calPitch = switch (shot.caliber()) {
            case "pistol", "handgun" -> 1.4f;
            case "sniper", "heavy" -> 0.65f;
            case "shotgun", "shot" -> 0.8f;
            case "rocket", "launcher" -> 0.45f;
            default -> 1.0f;
        };
        switch (kind) {
            case METAL -> {
                world.playSound(at, Sound.BLOCK_ANVIL_LAND, 0.55f, calPitch);
                world.playSound(at, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 0.35f, calPitch + 0.3f);
                world.spawnParticle(Particle.CRIT, at, 10, 0.12, 0.12, 0.12, 0.06);
            }
            case SOFT -> {
                world.playSound(at, Sound.BLOCK_WOOD_BREAK, 0.7f, calPitch);
                world.spawnParticle(Particle.BLOCK, at, 8, 0.15, 0.15, 0.15, 0.02,
                        Material.OAK_PLANKS.createBlockData());
            }
            case HARD -> {
                world.playSound(at, Sound.BLOCK_STONE_BREAK, 0.75f, calPitch * 0.9f);
                world.playSound(at, Sound.BLOCK_GRAVEL_HIT, 0.4f, 0.8f);
                world.spawnParticle(Particle.BLOCK, at, 10, 0.15, 0.15, 0.15, 0.02,
                        Material.STONE.createBlockData());
            }
            default -> {
            }
        }
    }

    private static void nudgeThrough(Bullet bullet, Block block, BlockFace face) {
        Entity proj = bullet.getProjectile();
        if (proj == null || !proj.isValid()) {
            return;
        }
        Vector dir = bullet.velocity();
        if (dir == null || dir.lengthSquared() < 1.0e-8) {
            dir = face != null ? face.getDirection().multiply(-1) : new Vector(0, 0, 1);
        } else {
            dir = dir.clone().normalize();
        }
        // Full blocks need a longer nudge than glass panes so the snowball clears the cell
        Location to = block.getLocation().add(0.5, 0.5, 0.5).add(dir.clone().multiply(1.35));
        proj.teleport(to);
        bullet.reassertVelocity();
    }

    private static void nudgeOut(Bullet bullet, Block block, Vector reflected) {
        Entity proj = bullet.getProjectile();
        if (proj == null || !proj.isValid()) {
            return;
        }
        Vector dir = reflected.clone().normalize();
        Location to = block.getLocation().add(0.5, 0.5, 0.5).add(dir.multiply(1.15));
        proj.teleport(to);
        bullet.reassertVelocity();
    }
}
